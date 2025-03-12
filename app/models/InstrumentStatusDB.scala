package models

import com.github.nscala_time.time.Imports
import play.api.libs.json.{Json, OWrites, Reads}

import java.util.Date
import scala.concurrent.Future
object InstrumentStatusDB {
  case class Status(key: String, value: Double)

  case class InstrumentStatus(time: Date, instID: String, statusList: Seq[Status]) {
    def excludeNaN: InstrumentStatus = {
      val validList = statusList.filter { s => !(s.value.isNaN || s.value.isInfinite || s.value.isNegInfinity) }
      InstrumentStatus(time, instID, validList)
    }

  }


  implicit val stRead: Reads[Status] = Json.reads[Status]
  implicit val isRead: Reads[InstrumentStatus] = Json.reads[InstrumentStatus]
  implicit val stWrite: OWrites[Status] = Json.writes[Status]
  implicit val isWrite: OWrites[InstrumentStatus] = Json.writes[InstrumentStatus]
}

trait InstrumentStatusDB {
  import InstrumentStatusDB._
  def log(is: InstrumentStatus): Unit

  def query(id: String, start: Imports.DateTime, end: Imports.DateTime): Seq[InstrumentStatus]

  def queryFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]]

  def formatValue(v: Double, prec: Int = 2): String =s"%.${prec}f".format(v)
}
