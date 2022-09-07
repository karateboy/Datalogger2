package models

import org.mongodb.scala.result.UpdateResult

import scala.concurrent.Future

case class InstrumentStatusTypeMap(instrumentId: String, statusTypeSeq: Seq[InstrumentStatusType], monitor:Option[String] = None)
trait InstrumentStatusTypeMapDB {
  def getInstrumentStatusTypeMapAsync(monitor:String) : Future[Seq[InstrumentStatusTypeMap]]
  def upsertInstrumentStatusTypeAsync(monitor:String, statusTypes: Seq[InstrumentStatusTypeMap]) : Future[UpdateResult]
}
