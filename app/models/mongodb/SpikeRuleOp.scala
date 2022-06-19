package models.mongodb

import models.ModelHelper.errorHandler
import models.{SpikeRule, SpikeRuleDB, SpikeRuleID}
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class SpikeRuleOp @Inject()(mongodb: MongoDB) extends SpikeRuleDB {
  lazy private val ColName = "spikeRules"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[SpikeRule], classOf[SpikeRuleID]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[SpikeRule](ColName).withCodecRegistry(codecRegistry)

  for(colNames <- mongodb.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(ColName)) {
      val f = mongodb.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  override def getRules(): Future[Seq[SpikeRule]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  override def upsert(rule:SpikeRule): Future[UpdateResult] ={
    val f = collection.replaceOne(Filters.equal("_id", rule._id), rule, ReplaceOptions()
      .upsert(true)).toFuture()
    f onFailure(errorHandler())
    f
  }

  override def delete(_id:SpikeRuleID): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure errorHandler()
    f
  }
}



