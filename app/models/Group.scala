package models

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.result.{DeleteResult, InsertOneResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Success


case class Ability(action:String, subject:String)
case class Group(_id: String, name: String, monitors:Seq[String], monitorTypes: Seq[String],
                 admin:Boolean, abilities: Seq[Ability], parent:Option[String] = None,
                 lineToken:Option[String] = None,
                 lineNotifyColdPeriod:Option[Int] = Some(30))

import javax.inject._
object Group {
  val PLATFORM_ADMIN = "platformAdmin"
  val PLATFORM_USER = "platformUser"
  var lastHourLineNotify = Map.empty[String, DateTime]
  var lastMinLineNotify = Map.empty[String, DateTime]
}

@Singleton
class GroupOp @Inject()(mongoDB: MongoDB) {
  import Group._
  import org.mongodb.scala._

  val ColName = "groups"
  val codecRegistry = fromRegistries(fromProviders(classOf[Group], classOf[Ability], DEFAULT_CODEC_REGISTRY))
  val collection: MongoCollection[Group] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  implicit val readAbility = Json.reads[Ability]
  implicit val writeAbility = Json.writes[Ability]
  implicit val read = Json.reads[Group]
  implicit val write = Json.writes[Group]

  val ACTION_READ = "read"
  val ACTION_MANAGE = "manage"
  val ACTION_SET = "set"

  val SUBJECT_ALL = "all"
  val SUBJECT_DASHBOARD = "Dashboard"
  val SUBJECT_DATA = "Data"
  val SUBJECT_ALARM = "Alarm"

  val defaultGroup : Seq[Group] =
    Seq(
      Group(_id = PLATFORM_ADMIN, "平台管理團隊", Seq.empty[String], Seq.empty[String],
        admin = true, Seq(Ability(ACTION_MANAGE, SUBJECT_ALL))),
      Group(_id = PLATFORM_USER, "平台使用者", Seq.empty[String], Seq.empty[String],
        admin = false, Seq(Ability(ACTION_READ, SUBJECT_DASHBOARD),
          Ability(ACTION_READ, SUBJECT_DATA),
          Ability(ACTION_SET, SUBJECT_ALARM)))
    )

  def init(): Unit = {
    for(colNames <- mongoDB.database.listCollectionNames().toFuture()){
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
        f.andThen({
          case Success(_) =>
            createDefaultGroup
        })
      }
    }
  }

  init()

  private def createDefaultGroup = {
    for(group <- defaultGroup) yield {
      val f = collection.insertOne(group).toFuture()
      f
    }
  }

  def newGroup(group: Group): InsertOneResult = {
    val f = collection.insertOne(group).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  var map : Map[String, Group] = {
    val groups = getAllGroups
    groups.map(g=>g._id->g).toMap
  }

  def deleteGroup(_id: String): DeleteResult = {
    val f = collection.deleteOne(equal("_id", _id)).toFuture()
    map = map- _id
    waitReadyResult(f)
  }

  def updateGroup(group: Group): UpdateResult = {
    val f = collection.replaceOne(equal("_id", group._id), group).toFuture()
    map = map + (group._id->group)
    waitReadyResult(f)
  }

  def getGroupByID(_id: String): Option[Group] = {
    val f = collection.find(equal("_id", _id)).first().toFuture()
    f.onFailure {
      errorHandler
    }
    val group = waitReadyResult(f)
    Option(group)
  }

  def getAllGroups: Seq[Group] = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

  def addMonitor(_id: String, monitorID:String): Future[UpdateResult] = {
    val f = collection.updateOne(Filters.equal("_id", _id), Updates.addToSet("monitors", monitorID)).toFuture()
    f onFailure errorHandler
    f
  }
}
