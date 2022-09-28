package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.ImplementedBy
import play.api.libs.json.Json

import java.time.Instant
import java.util.Date
import scala.concurrent.Future

trait InstrumentStatusDB {
  case class Status(key: String, value: Double)
  case class InstrumentStatusJSON(time:Long, instID: String, statusList: Seq[Status]){
    def toInstrumentStatus(monitor:String): InstrumentStatus =
      InstrumentStatus(time = Date.from(Instant.ofEpochMilli(time)), instID = instID,
        statusList = statusList, monitor = monitor)
  }

  case class InstrumentStatus(time: Date, instID: String, statusList: Seq[Status], monitor: String = Monitor.activeId) {
    def excludeNaN = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatus(time, instID, validList)
    }
    def toJSON = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatusJSON(time.getTime, instID, validList)
    }
  }

  def getLatestMonitorRecordTimeAsync(monitor:String) : Future[Option[DateTime]]

  implicit val stRead = Json.reads[Status]
  implicit val isRead = Json.reads[InstrumentStatus]
  implicit val stWrite = Json.writes[Status]
  implicit val isWrite = Json.writes[InstrumentStatus]
  implicit val jsonWrite = Json.writes[InstrumentStatusJSON]
  implicit val jsonRead = Json.reads[InstrumentStatusJSON]

  def log(is: InstrumentStatus): Unit

  def queryFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]]

  def queryAsync(id:String, start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]]

  def queryMonitorAsync(monitor:String, id:String, start:DateTime, end:DateTime) : Future[Seq[InstrumentStatus]]
  def formatValue(v: Double, prec: Int = 2): String =s"%.${prec}f".format(v)
}
