package models

import org.mongodb.scala.model._
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.bson._
import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.MongoCollection

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Image(_id: ObjectId, fileName: String, content: Array[Byte])
@javax.inject.Singleton
class ImageOp @Inject() (mongoDB: MongoDB) {
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[Image]), DEFAULT_CODEC_REGISTRY)

  private val COLNAME = "Image"
  val collection: MongoCollection[Image] = mongoDB.database.getCollection[Image](COLNAME).withCodecRegistry(codecRegistry)

  def getImage(objId: ObjectId): Future[Image] = {
    val f = collection.find(Filters.eq("_id", objId)).first().toFuture()
    f.onFailure(errorHandler)
    f
  }
}
