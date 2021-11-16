package models

import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.ObjectId

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import ModelHelper._
import play.api.libs.json.Json
case class Health(me:Double)
case class Evaluation(Time:Date, Health:Health)
object SoundEvaluation {
  import org.mongodb.scala.model._
  implicit val w1 = Json.writes[Health]
  implicit val writes = Json.writes[Evaluation]

  def getLatestEvaluation(mongoDB: MongoDB) = {
    val database: MongoDatabase = mongoDB.mongoClient.getDatabase("SoundEvaluation");
    import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
    import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
    import org.mongodb.scala.bson.codecs.Macros._

    val codecRegistry = fromRegistries(fromProviders(classOf[Evaluation], classOf[Health]), DEFAULT_CODEC_REGISTRY)
    val colName = "Evaluation"
    val collection = database.getCollection[Evaluation](colName).withCodecRegistry(codecRegistry)


    val f = collection.find(Filters.exists("_id"))
      .sort(Sorts.descending("Time")).limit(1).toFuture()
    f onFailure errorHandler
    f
  }
}
