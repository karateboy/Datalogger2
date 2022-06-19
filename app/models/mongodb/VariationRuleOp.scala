package models.mongodb

import models.ModelHelper.errorHandler
import models.{VariationRule, VariationRuleDB, VariationRuleID}
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class VariationRuleOp @Inject()(mongodb: MongoDB) extends VariationRuleDB {
  lazy private val ColName = "variationRules"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[VariationRule], classOf[VariationRuleID]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[VariationRule](ColName).withCodecRegistry(codecRegistry)

  for(colNames <- mongodb.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(ColName)) {
      val f = mongodb.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  override def getRules(): Future[Seq[VariationRule]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  override def upsert(rule:VariationRule): Future[UpdateResult] ={
    val f = collection.replaceOne(Filters.equal("_id", rule._id), rule, ReplaceOptions()
      .upsert(true)).toFuture()
    f onFailure(errorHandler())
    f
  }

  override def delete(_id:VariationRuleID): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
}



