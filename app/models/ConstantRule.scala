package models

import models.ModelHelper.errorHandler
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ConstantRuleID(monitor:String, monitorType:String)
case class ConstantRule(_id: ConstantRuleID, enable:Boolean, count:Int)
object ConstantRule {
  implicit val reads1 = Json.reads[ConstantRuleID]
  implicit val reads = Json.reads[ConstantRule]
  implicit val write1 = Json.writes[ConstantRuleID]
  implicit val write = Json.writes[ConstantRule]

}

@Singleton
class ConstantRuleOp @Inject()(mongoDB: MongoDB) {
  val ColName = "constantRules"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[ConstantRule], classOf[ConstantRuleID]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[ConstantRule](ColName).withCodecRegistry(codecRegistry)

  for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
    if (!colNames.contains(ColName)) {
      val f = mongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  def getRules(): Future[Seq[ConstantRule]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def upsert(rule:ConstantRule): Future[UpdateResult] ={
    val f = collection.replaceOne(Filters.equal("_id", rule._id), rule, ReplaceOptions()
      .upsert(true)).toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(_id:ConstantRuleID): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
}



