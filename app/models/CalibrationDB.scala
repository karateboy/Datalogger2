package models

import com.github.nscala_time.time.Imports._
import models.Calibration.CalibrationListMap
import models.ModelHelper._
import play.api.libs.json.{Json, OWrites, Reads}

import java.util.Date
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class Calibration(monitorType: String,
                       startTime: Date,
                       endTime: Date,
                       zero_val: Option[Double],
                       span_std: Option[Double],
                       span_val: Option[Double],
                       zero_success: Option[Boolean] = None,
                       span_success: Option[Boolean] = None,
                       point3: Option[Double] = None,
                       point3_std: Option[Double] = None,
                       point3_success: Option[Boolean] = None,
                       point4: Option[Double] = None,
                       point4_std: Option[Double] = None,
                       point4_success: Option[Boolean] = None,
                       point5: Option[Double] = None,
                       point5_std: Option[Double] = None,
                       point5_success: Option[Boolean] = None,
                       point6: Option[Double] = None,
                       point6_std: Option[Double] = None,
                       point6_success: Option[Boolean] = None) {

  private def span_dev_ratioOpt: Option[Double] =
    for (s_dev <- span_devOpt; std <- span_std)
      yield s_dev / std * 100

  def span_devOpt: Option[Double] =
    for (span <- span_val; std <- span_std)
      yield Math.abs(span - std)

  def success(implicit monitorTypeOp: MonitorTypeDB): Boolean = {
    val mtCase = monitorTypeOp.map(monitorType)
    passZeroStandard(zero_val, mtCase.zd_law) &&
      passSpanStandard(mtCase)
  }

  def multipointSuccess(): Boolean = {
    val successList = List(zero_success, span_success, point3_success, point4_success, point5_success, point6_success)
    successList.forall(_.getOrElse(true))
  }

  private def passZeroStandard(vOpt: Option[Double], stdOpt: Option[Double]): Boolean = {
    val retOpt =
      for {
        v <- vOpt
        std <- stdOpt
      } yield if (Math.abs(v) < Math.abs(std))
        true
      else
        false

    retOpt.getOrElse(true)
  }

  private def passSpanStandard(mtCase: MonitorType): Boolean = {
    val retOpt =
      for (span_dev_ratio <- span_dev_ratioOpt; span_dev_law <- mtCase.span_dev_law) yield
        span_dev_ratio < span_dev_law

    retOpt.getOrElse(true)
  }

  def calibrate(valueOpt: Option[Double]): Option[Double] =
    for {spanValue <- span_val
         spanStd <- span_std if spanStd != 0
         zeroVal <- zero_val if spanValue - zeroVal != 0
         value <- valueOpt
         } yield
      (value - zeroVal) * spanStd / (spanValue - zeroVal)

  private val M: Option[Double] =
    for {zeroVal <- zero_val
         spanVal <- span_val
         spanStd <- span_std if spanVal != zeroVal} yield
      spanStd / (spanVal - zeroVal)

  private val B: Option[Double] =
    for {
      zeroVal <- zero_val
      spanVal <- span_val
      spanStd <- span_std if spanVal != zeroVal} yield
      (-zeroVal * spanStd) / (spanVal - zeroVal)

}

object Calibration {
  type CalibrationListMap = Map[String, List[(DateTime, Calibration)]]
  val emptyCalibrationListMap = Map.empty[String, List[(DateTime, Calibration)]]

  def findTargetCalibrationMB(calibrationListMap: CalibrationListMap, mt: String, target: DateTime): Option[(Option[Double], Option[Double])] = {
    calibrationListMap.get(mt).flatMap(calibrationList => {
      val candidate = calibrationList.takeWhile(p => p._1 < target).map(_._2)
      candidate.lastOption
    }).map(calibration => (calibration.M, calibration.B))
  }

}

trait CalibrationDB {

  implicit val reads: Reads[Calibration] = Json.reads[Calibration]
  implicit val writes: OWrites[Calibration] = Json.writes[Calibration]

  def calibrationReport(start: DateTime, end: DateTime): Seq[Calibration]

  def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]]

  def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]]

  def calibrationReport(mt: String, start: DateTime, end: DateTime): Seq[Calibration]

  def getCalibrationListMapFuture(startDate: DateTime, endDate: DateTime)
                                 (implicit monitorTypeOp: MonitorTypeDB): Future[CalibrationListMap] = {
    val begin = startDate - 3.day
    val end = endDate

    val f = calibrationReportFuture(begin, end)
    f onFailure errorHandler()
    for (calibrationList <- f)
      yield {
        val resultMap = mutable.Map.empty[String, ListBuffer[(DateTime, Calibration)]]
        for (item <- calibrationList.filter { c => c.success } if item.monitorType != MonitorType.NO2) {
          val lb = resultMap.getOrElseUpdate(item.monitorType, ListBuffer.empty[(DateTime, Calibration)])
          lb.append((new DateTime(item.endTime), item))
        }
        resultMap.map(kv => kv._1 -> kv._2.toList).toMap
      }
  }

  def insertFuture(cal: Calibration): Unit

  def getLatestCalibration(mt: String): Future[Option[Calibration]]
}
