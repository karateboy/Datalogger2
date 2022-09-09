package models

import org.mongodb.scala.result.UpdateResult

import scala.concurrent.Future

trait InstrumentStatusTypeDB {
  def getAllInstrumentStatusTypeListAsync(monitor:String) : Future[Seq[InstrumentStatusTypeMap]]
  def upsertInstrumentStatusTypeMapAsync(monitor:String, map:Seq[InstrumentStatusTypeMap]) : Future[UpdateResult]
}
