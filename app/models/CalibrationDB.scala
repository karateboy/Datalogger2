package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.Logger
import play.api.libs.json.Json

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CalibrationJSON(monitorType: String, startTime: Long, endTime: Long, zero_val: Option[Double],
                           span_std: Option[Double], span_val: Option[Double])

case class Calibration(monitorType: String, startTime: DateTime, endTime: DateTime, zero_val: Option[Double],
                       span_std: Option[Double], span_val: Option[Double]) {
  def zero_dev: Option[Double] = zero_val.map(Math.abs)

  def span_dev_ratioOpt: Option[Double] = for (s_dev <- span_devOpt; std <- span_std)
    yield s_dev / std * 100

  def span_devOpt: Option[Double] =
    for (span <- span_val; std <- span_std)
      yield Math.abs(span - std)

  def toJSON = {
    CalibrationJSON(monitorType, startTime.getMillis, endTime.getMillis, zero_val,
      span_std, span_val)
  }

  def success(implicit monitorTypeOp: MonitorTypeDB): Boolean = {
    val mtCase = monitorTypeOp.map(monitorType)
    passZeroStandard(zero_val, mtCase.zd_law) &&
      passSpanStandard(mtCase)
  }

  def passZeroStandard(vOpt: Option[Double], stdOpt: Option[Double]): Boolean = {
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

  def passSpanStandard(mtCase:MonitorType):Boolean = {
    val retOpt =
      for(span_dev_ratio<-span_dev_ratioOpt;span_dev_law<-mtCase.span_dev_law) yield
        span_dev_ratio < span_dev_law

    retOpt.getOrElse(true)
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

  def calibrationReport(start: DateTime, end: DateTime): Seq[Calibration]

  def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]]

  def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]]

  def calibrationReport(mt: String, start: DateTime, end: DateTime): Seq[Calibration]

  def getCalibrationMap(startDate: DateTime, endDate: DateTime)
                       (implicit monitorTypeOp: MonitorTypeDB): Future[Map[String, List[(DateTime, Calibration)]]] = {
    val begin = (startDate - 5.day)
    val end = (endDate + 1.day)

    val f = calibrationReportFuture(begin, end)
    f onFailure errorHandler()
    for (calibrationList <- f)
      yield {
        import scala.collection.mutable.Map
        val resultMap = mutable.Map.empty[String, ListBuffer[(DateTime, Calibration)]]
        val calibrationSuccessFull = calibrationList.filter { c => c.success }
        for (item <- calibrationList.filter { c => c.success } if item.monitorType != MonitorType.NO2) {
          val lb = resultMap.getOrElseUpdate(item.monitorType, ListBuffer.empty[(DateTime, Calibration)])
          lb.append((item.endTime, item))
        }
        resultMap.map(kv => kv._1 -> kv._2.toList).toMap
      }
  }

  def insertFuture(cal: Calibration): Unit
}
