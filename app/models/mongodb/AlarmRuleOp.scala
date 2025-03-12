package models.mongodb

import models.ModelHelper.errorHandler
import models.{AlarmRule, AlarmRuleDb}
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
@Singleton
class AlarmRuleOp @Inject()(mongodb: MongoDB) extends AlarmRuleDb{

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "alarmRules"
  lazy val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[AlarmRule]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[AlarmRule](colName).withCodecRegistry(codecRegistry)

  private def init(): Unit = {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.failed.foreach(errorHandler)
      }
    }
  }

  init()
  override def getRulesAsync: Future[Seq[AlarmRule]] = {
    val f = collection.find().toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def upsertAsync(rule: AlarmRule): Future[UpdateResult] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.ReplaceOptions
    ruleTriggerMap -= rule._id
    val f = collection.replaceOne(equal("_id", rule._id), rule, ReplaceOptions().upsert(true)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }


  override def deleteAsync(_id: String): Future[DeleteResult] = {
    import org.mongodb.scala.model.Filters._
    ruleTriggerMap -= _id
    val f = collection.deleteOne(equal("_id", _id)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }
}
