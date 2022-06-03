package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{AlarmDB, MonitorType, MonitorTypeDB, ThresholdConfig}
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import play.api.Logger

import javax.inject.{Inject, Singleton}

@Singleton
class MonitorTypeOp @Inject()(mongodb: MongoDB, alarmDB: AlarmDB) extends MonitorTypeDB {

  import org.mongodb.scala.bson._

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent._

  implicit object TransformMonitorType extends BsonTransformer[MonitorType] {
    def apply(mt: MonitorType): BsonString = new BsonString(mt.toString)
  }

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy val codecRegistry = fromRegistries(fromProviders(classOf[MonitorType], classOf[ThresholdConfig]), DEFAULT_CODEC_REGISTRY)
  lazy val colName = "monitorTypes"
  lazy val collection: MongoCollection[MonitorType] = mongodb.database.getCollection[MonitorType](colName).withCodecRegistry(codecRegistry)

  private def updateMt(): Unit = {
    def getUpdates(mt: MonitorType): Bson =
      Updates.combine(
        Updates.setOnInsert("_id", mt._id),
        Updates.setOnInsert("desp", mt.desp),
        Updates.setOnInsert("unit", mt.unit),
        Updates.setOnInsert("prec", mt.prec),
        Updates.setOnInsert("order", mt.order),
        Updates.setOnInsert("signalType", mt.signalType))

    val updateModels =
      for (mt <- defaultMonitorTypes) yield {

        UpdateOneModel(
          Filters.eq("_id", mt._id),
          getUpdates(mt), UpdateOptions().upsert(true))
      }

    val f = collection.bulkWrite(updateModels, BulkWriteOptions().ordered(true)).toFuture()
    f.onFailure(errorHandler)
    waitReadyResult(f)
  }

  {
    val colNames = waitReadyResult(mongodb.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) { // New
      waitReadyResult(mongodb.database.createCollection(colName).toFuture())
      updateMt()
    }
    refreshMtv
  }

  override def getList(): List[MonitorType] = {
    val f = collection.find().toFuture()
    waitReadyResult(f).toList
  }

  override def deleteItemFuture(_id: String): Unit = {
      val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
      f onFailure errorHandler
  }

  override def upsertItemFuture(mt: MonitorType): Future[UpdateResult] = {
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(Filters.equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

}
