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

  private def updateMt(): (List[String], List[String], Map[String, MonitorType]) = {
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
    refreshMtv
  }

  {
    val colNames = waitReadyResult(mongodb.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) { // New
      waitReadyResult(mongodb.database.createCollection(colName).toFuture())
      updateMt
    }
  }
  Logger.info("MonitorTypeOp init complete!")

  override def getList(): List[MonitorType] = {
    val f = collection.find().toFuture()
    waitReadyResult(f).toList
  }

  override def newMonitorType(mt: MonitorType): Future[InsertOneResult] = {
    map = map + (mt._id -> mt)
    if (mt.signalType)
      signalMtvList = signalMtvList.:+(mt._id)
    else
      mtvList = mtvList.:+(mt._id)

    val f = collection.insertOne(mt).toFuture()
    f onFailure errorHandler
    f
  }

  override def deleteMonitorType(_id: String): Unit = {
    if (map.contains(_id)) {
      val mt = map(_id)
      map = map - _id
      if (mt.signalType)
        signalMtvList = signalMtvList.filter(p => p != _id)
      else
        mtvList = mtvList.filter(p => p != _id)

      val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
      f onFailure errorHandler
    }
  }

  override def upsertMonitorTypeFuture(mt: MonitorType): Future[UpdateResult] = {
    import org.mongodb.scala.model.ReplaceOptions
    map = map + (mt._id -> mt)
    if (mt.signalType) {
      if (!signalMtvList.contains(mt._id))
        signalMtvList = signalMtvList :+ mt._id
    } else {
      if (!mtvList.contains(mt._id))
        mtvList = mtvList :+ mt._id
    }
    val f = collection.replaceOne(Filters.equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def logDiMonitorType(mt: String, v: Boolean): Unit = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    val previousValue = diValueMap.getOrElse(mt, !v)
    diValueMap = diValueMap + (mt -> v)
    if (previousValue != v) {
      val mtCase = map(mt)
      if (v)
        alarmDB.log(alarmDB.src(), alarmDB.Level.WARN, s"${mtCase.desp}=>觸發", 1)
      else
        alarmDB.log(alarmDB.src(), alarmDB.Level.INFO, s"${mtCase.desp}=>解除", 1)
    }
  }
  /*
  override def upsertMonitorType(mt: MonitorType): Future[UpdateResult] = {
    import org.mongodb.scala.model.ReplaceOptions
    map = map + (mt._id -> mt)
    if (mt.signalType) {
      if (!signalMtvList.contains(mt._id))
        signalMtvList = signalMtvList :+ mt._id
    } else {
      if (!mtvList.contains(mt._id))
        mtvList = mtvList :+ mt._id
    }

    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f onFailure errorHandler
    f
  }
*/
}
