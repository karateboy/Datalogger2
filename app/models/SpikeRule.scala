package models

import models.ModelHelper.errorHandler
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class SpikeRuleID(monitor:String, monitorType:String)
case class SpikeRule(_id: SpikeRuleID, enable:Boolean, abs: Float)
object SpikeRule {
  implicit val reads1 = Json.reads[SpikeRuleID]
  implicit val reads = Json.reads[SpikeRule]
  implicit val write1 = Json.writes[SpikeRuleID]
  implicit val write = Json.writes[SpikeRule]

}

@Singleton
class SpikeRuleOp @Inject()(mongoDB: MongoDB) {
  val ColName = "spikeRules"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[SpikeRule], classOf[SpikeRuleID]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[SpikeRule](ColName).withCodecRegistry(codecRegistry)

  for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(ColName)) {
      val f = mongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  def getRules(): Future[Seq[SpikeRule]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def upsert(rule:SpikeRule): Future[UpdateResult] ={
    val f = collection.replaceOne(Filters.equal("_id", rule._id), rule, ReplaceOptions()
      .upsert(true)).toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(_id:SpikeRuleID): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
}



