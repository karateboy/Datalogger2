package models

import akka.actor.{Actor, Props, _}
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.{ProtocolParam, serial}
import models.mongodb.CalibrationOp
import play.api._
import play.api.libs.json.{JsError, Json}

import scala.concurrent.ExecutionContext.Implicits.global

case class Baseline9000Config(calibrationTime: Option[LocalTime],
                              raiseTime: Option[Int],
                              downTime: Option[Int],
                              holdTime: Option[Int],
                              calibrateZeoSeq: Option[String],
                              calibrateSpanSeq: Option[String])

object Baseline9000Collector extends DriverOps {

  var count = 0
  implicit val cfgRead = Json.reads[Baseline9000Config]
  implicit val cfgWrite = Json.writes[Baseline9000Config]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[Baseline9000Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    List("CH4", "NMHC", "THC")
  }

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Baseline9000Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Baseline9000Collector.Factory])
    val f2 = f.asInstanceOf[Baseline9000Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: Baseline9000Config): Actor
  }

  case object OpenComPort

  case object ReadData

  override def id: String = "baseline9000"

  override def description: String = "Baseline 9000 MNME Analyzer"

  override def protocol: List[String] = List(serial)
}

import javax.inject._

class Baseline9000Collector @Inject()
(instrumentOp: InstrumentDB, calibrationOp: CalibrationDB,
 monitorTypeOp: MonitorTypeDB)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: Baseline9000Config) extends Actor {

  import Baseline9000Collector._
  import ModelHelper._
  import TapiTxx._

  import scala.concurrent.duration._
  import scala.concurrent.{Future, blocking}

  val StartShippingDataByte: Byte = 0x11
  val StopShippingDataByte: Byte = 0x13
  val AutoCalibrationByte: Byte = 0x12
  val BackToNormalByte: Byte = 0x10
  val ActivateMethaneZeroByte: Byte = 0x0B
  val ActivateMethaneSpanByte: Byte = 0x0C
  val ActivateNonMethaneZeroByte: Byte = 0xE
  val ActivateNonMethaneSpanByte: Byte = 0xF
  val mtCH4 = ("CH4")
  val mtNMHC = ("NMHC")
  val mtTHC = ("THC")
  var collectorState = {
    val instrument = instrumentOp.getInstrument(id)
    instrument(0).state
  }
  var serialCommOpt: Option[SerialComm] = None
  var timerOpt: Option[Cancellable] = None
  var calibrateRecordStart = false
  var calibrateTimerOpt: Option[Cancellable] = None

  override def preStart() = {
    timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, OpenComPort))
  }

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  def receive = openComPort

  def openComPort: Receive = {
    case OpenComPort =>
      Future {
        blocking {
          serialCommOpt = Some(SerialComm.open(protocolParam.comPort.get))
          context become comPortOpened
          Logger.info(s"${self.path.name}: Open com port.")
          timerOpt = if (collectorState == MonitorStatus.NormalStat) {
            for (serial <- serialCommOpt) {
              serial.port.writeByte(StartShippingDataByte)
            }
            Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadData))
          } else {
            Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, SetState(id, MonitorStatus.NormalStat)))
          }
        }
      } onFailure serialErrorHandler
  }

  def serialErrorHandler: PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. Close com port.", ex)
      for (serial <- serialCommOpt) {
        SerialComm.close(serial)
        serialCommOpt = None
      }
      context become openComPort
      timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort))
  }

  def readData = {
    Future {
      blocking {
        for (serial <- serialCommOpt) {
          val lines = serial.getMessageByCrWithTimeout(3)
          for (line <- lines) {
            val parts = line.split('\t')
            if (parts.length >= 4) {
              val ch4Value = parts(2).toDouble
              val nmhcValue = parts(4).toDouble
              val ch4 = MonitorTypeData(mtCH4, ch4Value, collectorState)
              val nmhc = MonitorTypeData(mtNMHC, nmhcValue, collectorState)
              val thc = MonitorTypeData(mtTHC, (ch4Value + nmhcValue), collectorState)

              if (calibrateRecordStart)
                self ! ReportData(List(ch4, nmhc, thc))

              context.parent ! ReportData(List(ch4, nmhc, thc))
            }
          }
        }
        timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
      }
    } onFailure serialErrorHandler
  }

  def comPortOpened: Receive = {
    case ReadData =>
      readData

    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          instrumentOp.setState(id, state)
          if (state == MonitorStatus.NormalStat) {
            for (serial <- serialCommOpt) {
              serial.port.writeByte(BackToNormalByte)
              serial.port.writeByte(StartShippingDataByte)
            }
            timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
          }
        }
      } onFailure serialErrorHandler

    case AutoCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      instrumentOp.setState(id, collectorState)
      context become calibrationHandler(AutoZero, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      instrumentOp.setState(id, collectorState)
      context become calibrationHandler(ManualZero, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.SpanCalibrationStat
      instrumentOp.setState(id, collectorState)
      context become calibrationHandler(ManualSpan, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart
  }

  def calibrationHandler(calibrationType: CalibrationType, mt: String, startTime: com.github.nscala_time.time.Imports.DateTime,
                         calibrationDataList: List[MonitorTypeData], zeroValue: Option[Double]): Receive = {
    case ReadData =>
      readData

    case RaiseStart =>
      Future {
        blocking {
          Logger.info(s"${calibrationType} RasieStart: $mt")
          val cmd =
            if (calibrationType.zero) {
              config.calibrateZeoSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              if (mt == mtCH4)
                ActivateMethaneZeroByte
              else
                ActivateNonMethaneZeroByte
            } else {
              config.calibrateSpanSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              if (mt == mtCH4)
                ActivateMethaneSpanByte
              else
                ActivateNonMethaneSpanByte
            }

          for (serial <- serialCommOpt)
            serial.port.writeByte(cmd)

          calibrateTimerOpt = for(raiseTime <- config.raiseTime) yield
            context.system.scheduler.scheduleOnce(Duration(raiseTime, SECONDS), self, HoldStart)
        }
      } onFailure serialErrorHandler

    case ReportData(mtDataList) =>
      if(calibrateRecordStart){
        val data = mtDataList.filter { data => data.mt == mt }
        context become calibrationHandler(calibrationType, mt, startTime, data ::: calibrationDataList, zeroValue)
      }

    case HoldStart =>
      Logger.debug(s"${calibrationType} HoldStart: $mt")
      calibrateRecordStart = true
      calibrateTimerOpt = for(holdTime<-config.holdTime) yield
        context.system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart)

    case DownStart =>
      Logger.debug(s"${calibrationType} DownStart: $mt")
      calibrateRecordStart = false

      if (calibrationType.zero) {
        config.calibrateZeoSeq map {
          seqNo =>
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
        }
      } else {
        config.calibrateSpanSeq map {
          seqNo =>
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
        }
      }

      Future {
        blocking {
          for (serial <- serialCommOpt)
            serial.port.writeByte(BackToNormalByte)

          calibrateTimerOpt = if (calibrationType.auto && calibrationType.zero)
            Some(context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, CalibrateEnd))
          else {
            collectorState = MonitorStatus.CalibrationResume
            instrumentOp.setState(id, collectorState)

            for(downTime<-config.downTime) yield
            context.system.scheduler.scheduleOnce(Duration(downTime, SECONDS), self, CalibrateEnd)
          }
        }
      } onFailure serialErrorHandler

    case CalibrateEnd =>
      val values = calibrationDataList.map {
        _.value
      }
      val avg = if (values.length == 0)
        None
      else
        Some(values.sum / values.length)

      if (calibrationType.auto && calibrationType.zero) {
        Logger.info(s"$mt zero calibration end. ($avg)")
        collectorState = MonitorStatus.SpanCalibrationStat
        instrumentOp.setState(id, collectorState)
        context become calibrationHandler(AutoSpan, mt, startTime, List.empty[MonitorTypeData], avg)
        self ! RaiseStart
      } else {
        Logger.info(s"$mt calibration end.")
        val cal =
          if (calibrationType.auto) {
            Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, zeroValue, monitorTypeOp.map(mt).span, avg)
          } else {
            if (calibrationType.zero) {
              Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, avg, None, None)
            } else {
              Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, None, monitorTypeOp.map(mt).span, avg)
            }
          }
        calibrationOp.insertFuture(cal)

        if (mt == mtCH4) {
          if (calibrationType.auto) {
            collectorState = MonitorStatus.ZeroCalibrationStat
            instrumentOp.setState(id, collectorState)
            context become calibrationHandler(AutoZero,
              mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
          } else {
            if (calibrationType.zero) {
              collectorState = MonitorStatus.ZeroCalibrationStat
              instrumentOp.setState(id, collectorState)
              context become calibrationHandler(ManualZero,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
            } else {
              collectorState = MonitorStatus.SpanCalibrationStat
              instrumentOp.setState(id, collectorState)
              context become calibrationHandler(ManualSpan,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
            }
          }
          self ! RaiseStart
        } else {
          collectorState = MonitorStatus.NormalStat
          instrumentOp.setState(id, collectorState)
          context become comPortOpened
        }
      }

    case SetState(id, state) =>
      Future {
        blocking {
          if (state == MonitorStatus.NormalStat) {
            collectorState = state
            instrumentOp.setState(id, state)

            for (serial <- serialCommOpt) {
              serial.port.writeByte(BackToNormalByte)
              serial.port.writeByte(StartShippingDataByte)
            }
            calibrateTimerOpt map {
              timer => timer.cancel()
            }
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
            context become comPortOpened
          } else {
            Logger.info(s"Ignore setState $state during calibration")
          }
        }
      } onFailure serialErrorHandler
  }

  override def postStop() = {
    for (timer <- timerOpt) {
      timer.cancel()
    }
    for (serial <- serialCommOpt) {
      SerialComm.close(serial)
      serialCommOpt = None
    }
  }

  case object RaiseStart

  case object HoldStart

  case object DownStart

  case object CalibrateEnd

}