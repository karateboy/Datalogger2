package models.mongodb

import com.google.inject.Inject
import models.ModelHelper.errorHandler
import models.{MonitorTypeGroup, MonitorTypeGroupDb}
import org.mongodb.scala.result.DeleteResult

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MonitorTypeGroupOp @Inject()(mongodb: MongoDB) extends MonitorTypeGroupDb {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model._

  lazy private val colName = "monitorTypeGroups"
  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[MonitorTypeGroup]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[MonitorTypeGroup](colName).withCodecRegistry(codecRegistry)

  override def getMonitorTypeGroups: Future[Seq[MonitorTypeGroup]] = {
    val f = collection.find().toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def upsertMonitorTypeGroup(mtg: MonitorTypeGroup): Future[Boolean] = {

    val f = collection.replaceOne(Filters.equal("_id", mtg._id), mtg, ReplaceOptions().upsert(true)).toFuture()
    f.failed.foreach(errorHandler)
    for(_ <- f) yield true
  }

  override def deleteMonitorTypeGroup(_id: String): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

}
