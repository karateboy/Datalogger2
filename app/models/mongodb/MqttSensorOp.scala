package models.mongodb

import models.ModelHelper.errorHandler
import models.{GroupDB, MqttSensorDB, Sensor}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class MqttSensorOp @Inject()(mongodb: MongoDB, groupOp: GroupDB) extends MqttSensorDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val ColName = "sensors"
  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[Sensor]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[Sensor](ColName).withCodecRegistry(codecRegistry)

  import org.mongodb.scala.model._

  collection.createIndex(Indexes.descending("id"), IndexOptions().unique(true))
  collection.createIndex(Indexes.descending("group"))
  collection.createIndex(Indexes.descending("topic"))
  collection.createIndex(Indexes.descending("monitor"))

  override def getAllSensorList: Future[Seq[Sensor]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure (errorHandler())
    f
  }

  override def upsert(sensor: Sensor): Future[UpdateResult] = {
    val f = collection.replaceOne(Filters.equal("id", sensor.id), sensor, ReplaceOptions().upsert(true)).toFuture()
    f onFailure (errorHandler)
    groupOp.addMonitor(sensor.group, sensor.monitor) onFailure (errorHandler())
    f
  }

  override def delete(id: String): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("id", id)).toFuture()
    f onFailure (errorHandler)
    f
  }
}
