package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{Monitor, MonitorDB, MqttSensorDB}
import org.mongodb.scala.model.{Filters, ReplaceOptions, Sorts}
import org.mongodb.scala.result.DeleteResult
import play.api.Configuration

import javax.inject.{Inject, Singleton}
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class MonitorOp @Inject()(mongodb: MongoDB, config: Configuration, sensorOp: MqttSensorDB) extends MonitorDB {

  import Monitor._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val hasSelfMonitor = config.getBoolean("selfMonitor").getOrElse(false)
  private val colName = "monitors"
  private val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  private val collection = mongodb.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)
  override var map: Map[String, Monitor] = {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    pairs.toMap
  }


  for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(colName)) {
      val f = mongodb.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      for (_ <- f) {
        for (ret <- collection.countDocuments(Filters.exists("_id")).toFuture())
          if (ret == 0) {
            for (_ <- collection.insertOne(selfMonitor).toFuture())
              refresh
          }
      }
    } else
      refresh
  }


  override def mvList: immutable.Seq[String] = mList.map(_._id)

  private def mList: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("_id")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  override def ensureMonitor(_id: String): Unit = {
    if (!map.contains(_id)) {
      newMonitor(Monitor(_id, _id))
    }
  }

  override def newMonitor(m: Monitor): Unit = {
    map = map + (m._id -> m)

    val f = collection.insertOne(m).toFuture()
    f onFailure (errorHandler)
  }

  override def format(v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  override def upsert(m: Monitor): Unit = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    map = map + (m._id -> m)
  }

  override def deleteMonitor(_id: String): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f.andThen({
      case _ =>
        sensorOp.deleteByMonitor(_id)
        map = map.filter(p => p._1 != _id)
    })
  }

  private def refresh {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    map = pairs.toMap
  }
}
