package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CalibrationJSON(monitorType: String, startTime: Long, endTime: Long, zero_val: Option[Double],
                           span_std: Option[Double], span_val: Option[Double])

case class Calibration(monitorType: String, startTime: Date, endTime: Date, zero_val: Option[Double],
                       span_std: Option[Double], span_val: Option[Double], monitor:String = Monitor.activeId) {
  def zero_dev: Option[Double] = zero_val.map(Math.abs)

  def span_dev_ratio = for (s_dev <- span_dev; std <- span_std)
    yield s_dev / std * 100

  def span_dev =
    for (span <- span_val; std <- span_std)
      yield Math.abs(span_val.get - span_std.get)

  def toJSON = {
    CalibrationJSON(monitorType, startTime.getTime, endTime.getTime, zero_val,
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

trait CalibrationDB {

  implicit val reads = Json.reads[Calibration]
  implicit val writes = Json.writes[Calibration]
  implicit val jsonWrites = Json.writes[CalibrationJSON]

  def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]]

  def monitorCalibrationReport(monitors:Seq[String], start:Date, end:Date) :Future[Seq[Calibration]]
  def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]]

  def getCalibrationMap(startDate: DateTime, endDate: DateTime)
                       (implicit monitorTypeOp: MonitorTypeDB): Future[Map[String, List[(DateTime, Calibration)]]] = {
    val begin = (startDate - 5.day)
    val end = (endDate + 1.day)

    val f = calibrationReportFuture(begin, end)
    f onFailure errorHandler()
    for (calibrationList <- f)
      yield {
        import scala.collection.mutable._
        val resultMap = Map.empty[String, ListBuffer[(DateTime, Calibration)]]
        for (item <- calibrationList.filter { c => c.success } if item.monitorType != MonitorType.NO2) {
          val lb = resultMap.getOrElseUpdate(item.monitorType, ListBuffer.empty[(DateTime, Calibration)])
          lb.append((new DateTime(item.endTime), item))
        }
        resultMap.map(kv => kv._1 -> kv._2.toList).toMap
      }
  }

  def insertFuture(cal: Calibration): Unit
  def getLatestMonitorRecordTimeAsync(monitor:String) : Future[Option[DateTime]]
}
