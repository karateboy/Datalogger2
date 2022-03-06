package models

import models.ModelHelper.errorHandler
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class VariationRuleID(monitor:String, monitorType:String)
case class VariationRule(_id: VariationRuleID, enable:Boolean, abs:Double)
object VariationRule {
  implicit val reads1 = Json.reads[VariationRuleID]
  implicit val reads = Json.reads[VariationRule]
  implicit val write1 = Json.writes[VariationRuleID]
  implicit val write = Json.writes[VariationRule]

}

@Singleton
class VariationRuleOp @Inject()(mongoDB: MongoDB) {
  val ColName = "variationRules"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[VariationRule], classOf[VariationRuleID]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[VariationRule](ColName).withCodecRegistry(codecRegistry)

  for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(ColName)) {
      val f = mongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  def getRules(): Future[Seq[VariationRule]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def upsert(rule:VariationRule): Future[UpdateResult] ={
    val f = collection.replaceOne(Filters.equal("_id", rule._id), rule, ReplaceOptions()
      .upsert(true)).toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(_id:VariationRuleID): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
}



