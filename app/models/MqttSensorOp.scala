package models
import models.ModelHelper.errorHandler
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success


case class Sensor(id:String, topic: String, monitor: String, group:String)
object MqttSensor {
  implicit val write = Json.writes[Sensor]
  implicit val read = Json.reads[Sensor]
}

@Singleton
class MqttSensorOp @Inject()(mongoDB: MongoDB, groupOp: GroupOp) {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val ColName = "sensors"
  val codecRegistry = fromRegistries(fromProviders(classOf[Sensor]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Sensor](ColName).withCodecRegistry(codecRegistry)

  import org.mongodb.scala.model._
  collection.createIndex(Indexes.descending("id"), IndexOptions().unique(true))
  collection.createIndex(Indexes.descending("group"))
  collection.createIndex(Indexes.descending("topic"))
  collection.createIndex(Indexes.descending("monitor"))

  def getSensorList(group:String) = {
    val f = collection.find(Filters.eq("group", group)).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def getAllSensorList = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def getSensorMap = {
    for(sensorList <- getAllSensorList) yield {
      val pairs =
        for(sensor<-sensorList) yield
          sensor.id -> sensor

      pairs.toMap
    }
  }

  def getSensor(id:String): Future[Seq[Sensor]] = {
    val f = collection.find(Filters.equal("id", id)).toFuture()
    f onFailure(errorHandler())
    f
  }

  def upsert(sensor:Sensor)={
    val f = collection.replaceOne(Filters.equal("id", sensor.id), sensor, ReplaceOptions().upsert(true)).toFuture()
    f onFailure(errorHandler)
    groupOp.addMonitor(sensor.group, sensor.monitor) onFailure(errorHandler())
    f
  }

  def delete(id:String) = {
    val f = collection.deleteOne(Filters.equal("id", id)).toFuture()
    f onFailure(errorHandler)
    f
  }

  def deleteByMonitor(monitor:String)  = {
    val f = collection.deleteOne(Filters.equal("monitor", monitor)).toFuture()
    f onFailure(errorHandler)
    f
  }
}
