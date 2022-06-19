package models

import com.google.inject.ImplementedBy
import models.Protocol.ProtocolParam
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait InstrumentDB {

  implicit val ipRead = Json.reads[InstrumentStatusType]
  implicit val reader = Json.reads[Instrument]
  implicit val ipWrite = Json.writes[InstrumentStatusType]
  implicit val writer = Json.writes[Instrument]
  implicit val infoWrites = Json.writes[InstrumentInfo]

  def upsertInstrument(inst: Instrument): Boolean

  def getInstrumentList(): Seq[Instrument]

  def getInstrumentMap(): Future[Map[String, Instrument]] = {
    for (instruments <- getAllInstrumentFuture) yield
      instruments.map(inst => inst._id -> inst).toMap
  }

  def getAllInstrumentFuture: Future[Seq[Instrument]]

  def getInstrumentFuture(id:String) : Future[Instrument]

  def delete(id: String): Boolean

  def activate(id: String): Future[UpdateResult]

  def deactivate(id: String): Future[UpdateResult]

  def setState(id: String, state: String): Future[UpdateResult]

  def updateStatusType(id: String, statusList: List[InstrumentStatusType]): Future[UpdateResult]

  def getStatusTypeMap(id: String): Map[String, InstrumentStatusType] = {
    val instList = getInstrument(id)
    if (instList.length != 1)
      throw new Exception("no such Instrument")

    val statusTypes = instList(0).statusType.getOrElse(List.empty[InstrumentStatusType])
    val kv =
      for (kv <- statusTypes)
        yield
          kv.key -> kv

    Map(kv: _*)
  }

  def getInstrument(id: String): Seq[Instrument]
}
