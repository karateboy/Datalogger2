package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.ImplementedBy
import play.api.libs.json.Json

import scala.concurrent.Future

case class CalibrationJSON(monitorType: String, startTime: Long, endTime: Long, zero_val: Option[Double],
                           span_std: Option[Double], span_val: Option[Double])

case class Calibration(monitorType: String, startTime: DateTime, endTime: DateTime, zero_val: Option[Double],
                       span_std: Option[Double], span_val: Option[Double]) {
  def zero_dev: Option[Double] = zero_val.map(Math.abs)

  def span_dev_ratio = for (s_dev <- span_dev; std <- span_std)
    yield s_dev / std * 100

  def span_dev =
    for (span <- span_val; std <- span_std)
      yield Math.abs(span_val.get - span_std.get)

  def toJSON = {
    CalibrationJSON(monitorType, startTime.getMillis, endTime.getMillis, zero_val,
      span_std, span_val)
  }

  def success(implicit monitorTypeOp: MonitorTypeDB): Boolean = {
    val mtCase = monitorTypeOp.map(monitorType)
    passStandard(zero_val, mtCase.zd_law) &&
      passStandard(span_val, mtCase.span_dev_law)
  }

  def passStandard(vOpt: Option[Double], stdOpt: Option[Double]): Boolean = {
    val retOpt =
      for {
        v <- vOpt
        std <- stdOpt
      } yield if (Math.abs(v) < Math.abs(std))
        true
      else
        false

    retOpt.fold(true)(v => v)
  }

  def canCalibrate: Boolean = {
    val retOpt =
      for {spanValue <- span_val
           spanStd <- span_std
           zeroVal <- zero_val
           } yield
        (spanValue - zeroVal) != 0 && spanStd != 0

    retOpt.fold(false)(v => v)
  }

  def calibrate(valueOpt: Option[Double]): Option[Double] =
    for {spanValue <- span_val
         spanStd <- span_std if spanStd != 0
         zeroVal <- zero_val if spanValue - zeroVal != 0
         value <- valueOpt
         } yield
      (value - zeroVal) * spanStd / (spanValue - zeroVal)
}

@ImplementedBy(classOf[models.mongodb.CalibrationOp])
trait CalibrationDB {

  implicit val reads = Json.reads[Calibration]
  implicit val writes = Json.writes[Calibration]
  implicit val jsonWrites = Json.writes[CalibrationJSON]

  def calibrationReport(start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration]

  def calibrationReportFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Calibration]]

  def calibrationReportFuture(start: Imports.DateTime): Future[Seq[Calibration]]

  def calibrationReport(mt: String, start: Imports.DateTime, end: Imports.DateTime): Seq[Calibration]

  def getCalibrationMap(startDate: Imports.DateTime, endDate: Imports.DateTime)
                       (implicit monitorTypeOp: MonitorTypeDB): Future[Map[String, List[(Imports.DateTime, Calibration)]]]

  def insert(cal: Calibration)
}
