package models

import models.ModelHelper._
import org.mongodb.scala.model._
import play.api._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global

case class Monitor(_id: String, desc: String, lat: Double = 0, lng: Double = 0)

import javax.inject._

object Monitor {
  val SELF_ID = "me"
  val selfMonitor = Monitor(SELF_ID, "本站")
  var activeID = SELF_ID

}

@Singleton
class MonitorOp @Inject()(mongoDB: MongoDB, config: Configuration, sensorOp: MqttSensorOp) {
  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  import Monitor._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "monitors"

  val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)
  val hasSelfMonitor = config.getBoolean("selfMonitor").getOrElse(false)

  var map: Map[String, Monitor] = {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    pairs.toMap
  }


  for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
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


  def mvList = mList.map(_._id).filter(_id => {
    hasSelfMonitor || _id != Monitor.SELF_ID
  })

  def ensureMonitor(_id: String) = {
    if (!map.contains(_id)) {
      newMonitor(Monitor(_id, _id))
    }
  }

  def ensureMonitor(m: Monitor) =
    if (!map.contains(m._id))
      newMonitor(m)

  def newMonitor(m: Monitor): Unit = {
    map = map + (m._id -> m)

    val f = collection.insertOne(m).toFuture()
    f onFailure (errorHandler)
  }

  def format(v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  def upsert(m: Monitor) = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    map = map + (m._id -> m)
  }

  def refresh {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    map = pairs.toMap
  }

  private def mList: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("_id")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  def deleteMonitor(_id: String) = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f.andThen({
      case _ =>
        sensorOp.deleteByMonitor(_id)
        map = map.filter(p => p._1 != _id)
    })
  }
}