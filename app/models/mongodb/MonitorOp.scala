package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{LoggerConfig, Monitor, MonitorDB, MqttSensorDB}
import org.mongodb.scala.model.{Filters, ReplaceOptions, Sorts}
import org.mongodb.scala.result.DeleteResult
import play.api.Configuration

import javax.inject.{Inject, Singleton}
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class MonitorOp @Inject()(mongodb: MongoDB, sysConfig: SysConfig) extends MonitorDB {

  import Monitor._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "monitors"
  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)
  map = {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    pairs.toMap
  }


  for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(colName)) {
      val f = mongodb.database.createCollection(colName).toFuture()
      f.failed.foreach(errorHandler)
      for (_ <- f) {
        for (ret <- collection.countDocuments(Filters.exists("_id")).toFuture())
          if (ret == 0 && LoggerConfig.config.selfMonitor) {
            for (_ <- collection.insertOne(defaultMonitor).toFuture())
              refresh(sysConfig)
          }
      }
    } else
      refresh(sysConfig)
  }


  override def mList: List[Monitor] = {
    val f = collection.find().toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  override def upsert(m: Monitor): Unit = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.failed.foreach(errorHandler)
    map = map + (m._id -> m)
  }

  override def deleteMonitor(_id: String): Future[DeleteResult] = {
    map = map - _id
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }
}
