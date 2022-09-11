package models

import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import scala.concurrent.Future

case class InstrumentStatusTypeMap(instrumentId: String, statusTypeSeq: Seq[InstrumentStatusType], monitor:Option[String] = None)
trait InstrumentStatusTypeDB {
  implicit val r1 = Json.reads[InstrumentStatusType]
  implicit val reads = Json.reads[InstrumentStatusTypeMap]
  implicit val w1 = Json.writes[InstrumentStatusType]
  implicit val writes = Json.writes[InstrumentStatusTypeMap]

  def getAllInstrumentStatusTypeListAsync(monitor:String) : Future[Seq[InstrumentStatusTypeMap]]
  def upsertInstrumentStatusTypeMapAsync(monitor:String, map:Seq[InstrumentStatusTypeMap]) : Future[UpdateResult]
}
