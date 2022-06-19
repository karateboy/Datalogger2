package models.mongodb

import models.ModelHelper._
import models.{Ability, Group, GroupDB}
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Success

@Singleton
class GroupOp @Inject()(mongodb: MongoDB) extends GroupDB {
  import org.mongodb.scala._

  lazy val ColName = "groups"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[Group], classOf[Ability], DEFAULT_CODEC_REGISTRY))
  lazy val collection: MongoCollection[Group] = mongodb.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  private def init() {
    for(colNames <- mongodb.database.listCollectionNames().toFuture()){
      if (!colNames.contains(ColName)) {
        val f = mongodb.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
        f.andThen({
          case Success(_) =>
            createDefaultGroup
        })
      }
    }
  }

  init

  private def createDefaultGroup = {
    for(group <- defaultGroup) yield {
      val f = collection.insertOne(group).toFuture()
      f
    }
  }

  override def newGroup(group: Group) = {
    val f = collection.insertOne(group).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  override def deleteGroup(_id: String): DeleteResult = {
    val f = collection.deleteOne(equal("_id", _id)).toFuture()
    waitReadyResult(f)
  }

  override def updateGroup(group: Group): UpdateResult = {
    val f = collection.replaceOne(equal("_id", group._id), group).toFuture()
    waitReadyResult(f)
  }

  override def getGroupByID(_id: String): Option[Group] = {
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

  override def getAllGroups(): Seq[Group] = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

  override def addMonitor(_id: String, monitorID:String): Future[UpdateResult] = {
    val f = collection.updateOne(Filters.equal("_id", _id), Updates.addToSet("monitors", monitorID)).toFuture()
    f onFailure(errorHandler)
    f
  }
}
