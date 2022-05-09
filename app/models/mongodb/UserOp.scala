package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{AlarmConfig, Group, User, UserDB}
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.model.Updates
import play.api.Logger

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class UserOp @Inject()(mongoDB: MongoDB) extends UserDB {

  import org.mongodb.scala._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val ColName = "users"
  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[User], classOf[AlarmConfig]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection: MongoCollection[User] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  private def init() {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
      }
    }

    val f = collection.countDocuments().toFuture()
    f.onSuccess({
      case count: Long =>
        if (count == 0) {
          Logger.info("Create default user:" + defaultUser.toString())
          newUser(defaultUser)
        }
    })
    f.onFailure(errorHandler)
  }

  init

  override def newUser(user: User) = {
    val f = collection.insertOne(user).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  override def deleteUser(email: String) = {
    val f = collection.deleteOne(equal("_id", email)).toFuture()
    waitReadyResult(f)
  }

  override def updateUser(user: User): Unit = {
    if (user.password != "") {
      val f = collection.replaceOne(equal("_id", user._id), user).toFuture()
      waitReadyResult(f)
    } else {
      val updates = {
        if (user.group.isEmpty)
          Updates.combine(
            Updates.set("name", user.name),
            Updates.set("isAdmin", user.isAdmin),
            Updates.set("monitorTypeOfInterest", user.monitorTypeOfInterest)
          )
        else
          Updates.combine(
            Updates.set("name", user.name),
            Updates.set("isAdmin", user.isAdmin),
            Updates.set("group", user.group.get),
            Updates.set("monitorTypeOfInterest", user.monitorTypeOfInterest)
          )
      }

      val f = collection.findOneAndUpdate(equal("_id", user._id), updates).toFuture()
      waitReadyResult(f)
    }
  }

  override def getUserByEmail(email: String): Option[User] = {
    val f = collection.find(equal("_id", email)).first().toFuture()
    f.onFailure {
      errorHandler
    }
    val user = waitReadyResult(f)
    if (user != null)
      Some(user)
    else
      None
  }

  override def getAllUsers(): Seq[User] = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

  override def getAdminUsers(): Seq[User] = {
    val f = collection.find(equal("isAdmin", true)).toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

}
