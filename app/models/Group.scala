package models

import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{BulkWriteOptions, Filters, UpdateOneModel, UpdateOptions}
import play.api._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions


case class Group(_id: String, name: String, monitors:Seq[String], monitorTypes: Seq[String], admin:Boolean)

import javax.inject._

@Singleton
class GroupOp @Inject()(mongoDB: MongoDB) {

  import org.mongodb.scala._

  val ColName = "groups"
  val codecRegistry = fromRegistries(fromProviders(classOf[Group], DEFAULT_CODEC_REGISTRY))
  val collection: MongoCollection[Group] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  implicit val read = Json.reads[Group]
  implicit val write = Json.writes[Group]
  val PLATFORM_ADMIN = "platformAdmin"

  val defaultGroup : Seq[Group] =
    Seq(
      Group(_id = PLATFORM_ADMIN, "平台管理團隊", Seq.empty[String], Seq.empty[String], true)
    )
  def init() {
    for(colNames <- mongoDB.database.listCollectionNames().toFuture()){
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
      }
    }

    val f = collection.countDocuments().toFuture()
    f.onSuccess({
      case count: Long =>
        if (count == 0) {
          createDefaultGroup
        }
    })
    f.onFailure(errorHandler)
  }

  init

  def createDefaultGroup = {
    val f = collection.insertMany(defaultGroup).toFuture()
    f onFailure(errorHandler())
  }

  def newGroup(group: Group) = {
    val f = collection.insertOne(group).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  def deleteGroup(_id: String) = {
    val f = collection.deleteOne(equal("_id", _id)).toFuture()
    waitReadyResult(f)
  }

  def updateGroup(group: Group) = {
    val f = collection.replaceOne(equal("_id", group._id), group).toFuture()
    waitReadyResult(f)
  }

  def getGroupByID(_id: String) = {
    val f = collection.find(equal("_id", _id)).first().toFuture()
    f.onFailure {
      errorHandler
    }
    val group = waitReadyResult(f)
    if(group != null)
      Some(group)
    else
      None
  }

  def getAllGroups() = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }
}
