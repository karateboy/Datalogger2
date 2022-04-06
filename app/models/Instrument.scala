package models

import models.Protocol.ProtocolParam
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class InstrumentInfo(_id: String, instType: String, state: String,
                          protocol: String, protocolParam: String, monitorTypes: String,
                          calibrationTime: Option[String], inst: Instrument)

case class InstrumentStatusType(key: String, addr: Int, desc: String, unit: String, prec: Option[Int] = None)

case class Instrument(_id: String, instType: String,
                      protocol: ProtocolParam, param: String, active: Boolean,
                      state: String,
                      statusType: Option[List[InstrumentStatusType]]) {

  def replaceParam(newParam: String): Instrument = {
    Instrument(_id, instType, protocol, newParam, active, state, statusType)
  }
}

import models.ModelHelper._
import org.mongodb.scala._

@Singleton
class InstrumentOp @Inject()(mongodb: MongoDB) {
  implicit val ipRead = Json.reads[InstrumentStatusType]
  implicit val reader = Json.reads[Instrument]
  implicit val ipWrite = Json.writes[InstrumentStatusType]
  implicit val writer = Json.writes[Instrument]
  implicit val infoWrites = Json.writes[InstrumentInfo]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[Instrument], classOf[ProtocolParam], classOf[InstrumentStatusType]), DEFAULT_CODEC_REGISTRY)
  val collectionName = "instruments"
  val collection = mongodb.database.getCollection[Instrument](collectionName).withCodecRegistry(codecRegistry)

  def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
      }
    }
  }

  init

  import org.mongodb.scala.model.Filters._

  def upsertInstrument(inst: Instrument): Boolean = {
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(equal("_id", inst._id), inst, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  def getInstrumentList(): Seq[Instrument] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }

  def getInstrumentMap(): Future[Map[String, Instrument]] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    for (instruments <- f) yield
      instruments.map(inst => inst._id -> inst).toMap
  }

  def getInstrumentFuture(id: String): Future[Instrument] = {
    val f = collection.find(equal("_id", id)).first().toFuture()
    f onFailure errorHandler
    f
  }

  def getAllInstrumentFuture: Future[Seq[Instrument]] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    f
  }

  def delete(id: String): Boolean = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    f onFailure errorHandler
    waitReadyResult(f).getDeletedCount == 1
  }

  def activate(id: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", true)).toFuture()
    f onFailure errorHandler
    f
  }

  def deactivate(id: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", false)).toFuture()
    f onFailure errorHandler
    f
  }

  def setState(id: String, state: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("state", state)).toFuture()
    f onFailure errorHandler
    f
  }

  def updateStatusType(id: String, statusList: List[InstrumentStatusType]) = {
    import org.mongodb.scala.model.Updates._

    val f = collection.updateOne(equal("_id", id), set("statusType", statusList)).toFuture()
    f onFailure errorHandler
    f.onSuccess({
      case _ =>
        ForwardManager.updateInstrumentStatusType
    })
    f
  }

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

  def getInstrument(id: String): Seq[Instrument] = {
    val f = collection.find(equal("_id", id)).toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }
}