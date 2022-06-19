package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.Protocol.ProtocolParam
import models.{ForwardManager, Instrument, InstrumentDB, InstrumentStatusType}
import org.mongodb.scala.result.UpdateResult

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class InstrumentOp @Inject()(mongodb: MongoDB) extends InstrumentDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[Instrument], classOf[ProtocolParam], classOf[InstrumentStatusType]), DEFAULT_CODEC_REGISTRY)
  lazy private val collectionName = "instruments"
  lazy private val collection = mongodb.database.getCollection[Instrument](collectionName).withCodecRegistry(codecRegistry)

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
      }
    }
  }

  init

  import org.mongodb.scala.model.Filters._

  override def upsertInstrument(inst: Instrument): Boolean = {
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(equal("_id", inst._id), inst, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  override def getInstrumentList(): Seq[Instrument] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }

  override def getInstrumentMap(): Future[Map[String, Instrument]] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    for (instruments <- f) yield
      instruments.map(inst => inst._id -> inst).toMap
  }

  override def getInstrumentFuture(id: String): Future[Instrument] = {
    val f = collection.find(equal("_id", id)).first().toFuture()
    f onFailure errorHandler
    f
  }

  override def getAllInstrumentFuture: Future[Seq[Instrument]] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    f
  }

  override def delete(id: String): Boolean = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    f onFailure errorHandler
    waitReadyResult(f).getDeletedCount == 1
  }

  override def activate(id: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", true)).toFuture()
    f onFailure errorHandler
    f
  }

  override def deactivate(id: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", false)).toFuture()
    f onFailure errorHandler
    f
  }

  override def setState(id: String, state: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("state", state)).toFuture()
    f onFailure errorHandler
    f
  }

  override def updateStatusType(id: String, statusList: List[InstrumentStatusType]): Future[UpdateResult] = {
    import org.mongodb.scala.model.Updates._

    val f = collection.updateOne(equal("_id", id), set("statusType", statusList)).toFuture()
    f onFailure errorHandler
    f.onSuccess({
      case _ =>
        ForwardManager.updateInstrumentStatusType
    })
    f
  }

  override def getInstrument(id: String): Seq[Instrument] = {
    val f = collection.find(equal("_id", id)).toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }
}
