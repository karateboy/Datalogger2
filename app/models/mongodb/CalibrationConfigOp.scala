package models.mongodb

import models.ModelHelper.errorHandler
import models.{CalibrationConfig, CalibrationConfigDB, PointCalibrationConfig}
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.model.Filters

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationConfigOp @Inject()(mongoDB: MongoDB) extends CalibrationConfigDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[CalibrationConfig], classOf[PointCalibrationConfig]), DEFAULT_CODEC_REGISTRY)
  lazy val colName = "calibrationConfigs"
  lazy private val collection = mongoDB.database.getCollection[CalibrationConfig](colName).withCodecRegistry(codecRegistry)

  private def init(): Unit = {
    import org.mongodb.scala.model.Indexes._
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongoDB.database.createCollection(colName).toFuture()
        f.failed.foreach(errorHandler)
        f.foreach(_ => collection.createIndex(ascending("name")))
      }
    }
  }

  init()

  override def upsertFuture(calibrationConfig: CalibrationConfig): Future[Boolean] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(equal("_id", calibrationConfig._id), calibrationConfig, ReplaceOptions().upsert(true)).toFuture()
    f.failed.foreach(errorHandler)
    f map (_.wasAcknowledged())
  }

  override def getListFuture: Future[Seq[CalibrationConfig]] = {
    val f = collection.find().toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def deleteFuture(_id: String): Future[Boolean] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f.failed.foreach(errorHandler)
    f map (_.wasAcknowledged())
  }
}
