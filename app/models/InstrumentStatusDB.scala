package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.ImplementedBy
import play.api.libs.json.Json

import scala.concurrent.Future

trait InstrumentStatusDB {
  case class Status(key: String, value: Double)
  case class InstrumentStatusJSON(time:Long, instID: String, statusList: Seq[Status])
  case class InstrumentStatus(time: DateTime, instID: String, statusList: Seq[Status]) {
    def excludeNaN = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatus(time, instID, validList)
    }
    def toJSON = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatusJSON(time.getMillis, instID, validList)
    }
  }


  implicit val stRead = Json.reads[Status]
  implicit val isRead = Json.reads[InstrumentStatus]
  implicit val stWrite = Json.writes[Status]
  implicit val isWrite = Json.writes[InstrumentStatus]
  implicit val jsonWrite = Json.writes[InstrumentStatusJSON]

  def log(is: InstrumentStatus): Unit

  def query(id: String, start: Imports.DateTime, end: Imports.DateTime): Seq[InstrumentStatus]

  def queryFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]]

  def formatValue(v: Double, prec: Int = 2): String =s"%.${prec}f".format(v)
}
