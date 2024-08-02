package models

import akka.actor._
import models.DataCollectManager._

import java.time.Instant
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.util.Random

object MultiCalibrator {

  def start(calibrationConfig: CalibrationConfig,
            instrumentMap: Map[String, InstrumentParam],
            instrumentCollectorMap: Map[String, ActorRef])
           (implicit context: ActorContext, calibrationDB: CalibrationDB, monitorTypeDB: MonitorTypeDB): ActorRef = {
    context.actorOf(Props(new MultiCalibrator(calibrationConfig,
      instrumentMap,
      instrumentCollectorMap,
      monitorTypeDB,
      calibrationDB)), name = s"Calibrator-${Random.nextInt}")
  }

  private case object PointCalibrationStart

  private case object RaiseStart

  private case object HoldStart

  private case object PointCalibrationEnd

  case class MultiCalibrationDone(calibrationConfig: CalibrationConfig)

  case class TriggerVault(zero: Boolean, on: Boolean)
}

class MultiCalibrator(calibrationConfig: CalibrationConfig,
                      instrumentMap: Map[String, InstrumentParam],
                      instrumentCollectorMap: Map[String, ActorRef],
                      monitorTypeDB: MonitorTypeDB,
                      calibrationDB: CalibrationDB) extends Actor with ActorLogging {

  import MultiCalibrator._

  log.info(s"Start multi-calibrator ${calibrationConfig._id}")
  private val calibrationMonitorTypes: Seq[String] = calibrationConfig.instrumentIds.flatMap(instId => {
    val inst = instrumentMap(instId)
    val monitorTypes = inst.mtList
    monitorTypes
  }).distinct

  private def getDefaultCalibrationMap: Map[String, Calibration] = {
    val pair =
      for (monitorType <- calibrationMonitorTypes) yield {
        monitorType -> Calibration(monitorType = monitorType,
          startTime = Date.from(Instant.now),
          endTime = Date.from(Instant.now),
          zero_val = None,
          span_std = None,
          span_val = monitorTypeDB.map(monitorType).span)
      }
    pair.toMap
  }

  private def calibrateAction(point: Int, value: Boolean): Unit = {
    val pointConfig = calibrationConfig.pointConfigs(point)
    val zero = point == 0

    for (doBit <- pointConfig.calibrateDO)
      context.parent ! WriteDO(doBit, value)

    for (seq <- pointConfig.calibrateSeq)
      context.parent ! ExecuteSeq(seq, value)

    calibrationConfig.instrumentIds.foreach(instId => {
      for (collector <- instrumentCollectorMap.get(instId))
        collector ! TriggerVault(zero, value)
    })
  }

  import context.dispatcher

  self ! PointCalibrationStart

  private def updateCalibrationMap(endTime: Date,
                                   point: Int,
                                   valueMap: Map[String, Seq[Double]],
                                   calibrationMap: Map[String, Calibration]): Map[String, Calibration] = {
    var newMap = calibrationMap
    for ((monitorType, values) <- valueMap) {
      val calibration = calibrationMap(monitorType)
      val avgOpt = if (values.nonEmpty) Some(values.sum / values.size) else None
      val mtCase = monitorTypeDB.map(monitorType)
      val pointConfig = calibrationConfig.pointConfigs(point)

      def getSpanStd: Option[Double] = {
        for (fullSpan <- mtCase.span; spanPercent <- pointConfig.fullSpanPercent) yield
          fullSpan * spanPercent / 100
      }

      def getPointSuccess: Option[Boolean] =
        for (devLaw <- pointConfig.deviationAllowance; span <- getSpanStd) yield {
          val avg = avgOpt.getOrElse(Double.MaxValue)
          val dev = math.abs((avg - span) / span)
          dev * 100 < devLaw
        }

      point match {
        case 0 =>
          val zero_success =
            for (law <- mtCase.zd_law) yield {
              val avg = avgOpt.getOrElse(Double.MaxValue)
              val diff = math.abs(avg - 0)
              diff < law
            }
          newMap += monitorType -> calibration.copy(endTime = endTime, zero_val = avgOpt, zero_success = zero_success)

        case 1 =>
          newMap += monitorType -> calibration.copy(endTime = endTime, span_val = avgOpt, span_std = getSpanStd, span_success = getPointSuccess)
        case 2 =>
          newMap += monitorType -> calibration.copy(endTime = endTime, point3 = avgOpt, point3_std = getSpanStd, point3_success = getPointSuccess)
        case 3 =>
          newMap += monitorType -> calibration.copy(endTime = endTime, point4 = avgOpt, point4_std = getSpanStd, point4_success = getPointSuccess)
        case 4 =>
          newMap += monitorType -> calibration.copy(endTime = endTime, point5 = avgOpt, point5_std = getSpanStd, point5_success = getPointSuccess)
        case 5 =>
          newMap += monitorType -> calibration.copy(endTime = endTime, point6 = avgOpt, point6_std = getSpanStd, point6_success = getPointSuccess)
        case _ =>
        // Purge point no update
      }
    }
    newMap
  }

  def handler(point: Int,
              valueMap: Map[String, Seq[Double]],
              recording: Boolean,
              calibrationMap: Map[String, Calibration]): Receive = {
    case PointCalibrationStart =>
      if (point >= calibrationConfig.pointConfigs.length) {
        log.info("All point calibration is done.")
        val calibrations = calibrationMap.values.toSeq
        calibrations.foreach(calibration => {
          calibrationDB.insertFuture(calibration)
        })
        context.parent ! MultiCalibrationDone(calibrationConfig)
      } else if (!calibrationConfig.pointConfigs(point).enable) {
        log.info(s"Skip point $point")
        context.become(handler(point + 1, Map.empty[String, Seq[Double]], recording = false, calibrationMap))
        self ! PointCalibrationStart
      } else {
        log.info(s"Start calibrating point $point")
        context.become(handler(point, Map.empty[String, Seq[Double]], recording = false, calibrationMap))
        self ! RaiseStart
      }

    case RaiseStart =>
      val pointConfig = calibrationConfig.pointConfigs(point)
      calibrateAction(point, value = true)
      context.system.scheduler.scheduleOnce(FiniteDuration(pointConfig.raiseTime, SECONDS), self, HoldStart)

    case HoldStart =>
      val pointConfig = calibrationConfig.pointConfigs(point)
      context become handler(point, valueMap, recording = true, calibrationMap)
      context.system.scheduler.scheduleOnce(FiniteDuration(pointConfig.holdTime, SECONDS), self, PointCalibrationEnd)

    case PointCalibrationEnd =>
      calibrateAction(point, value = false)
      val newCalibrationMap = updateCalibrationMap(Date.from(Instant.now), point, valueMap, calibrationMap)

      context.become(handler(point + 1, Map.empty, recording = false, newCalibrationMap))
      self ! PointCalibrationStart

    case ReportData(_dataList) =>
      if (recording) {
        var newMap = valueMap
        _dataList.foreach {
          data => {
            val monitorType = data.mt
            val value = data.value
            val seq = newMap.getOrElse(monitorType, Seq.empty[Double])
            newMap += monitorType -> (seq :+ value)
          }
        }
        context.become(handler(point, newMap, recording = true, calibrationMap))
      }

    case StopMultiCalibration(config) =>
      log.info(s"Stop multi-calibration ${config._id}")
      calibrationConfig.instrumentIds.foreach(instId => {
        for (collector <- instrumentCollectorMap.get(instId)) {
          collector ! TriggerVault(zero = true, on = false)
          collector ! TriggerVault(zero = false, on = false)
        }
      })
      self ! PoisonPill
  }

  def receive: Receive = handler(0, Map.empty[String, Seq[Double]], recording = false, getDefaultCalibrationMap)
}