package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{AlarmConfig, User, UserDB}
import org.mongodb.scala.model.Updates
import play.api.Logger

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class UserOp @Inject()(mongoDB: MongoDB) extends UserDB {
  val logger: Logger = Logger(getClass)

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala._
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val ColName = "users"
  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[User], classOf[AlarmConfig]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection: MongoCollection[User] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  private def init(): Unit = {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.failed.foreach(errorHandler)
      }
    }

    val f = collection.countDocuments().toFuture()
    f.foreach(count => {
      if (count == 0) {
        logger.info("Create default user:" + defaultUser.toString)
        newUser(defaultUser)
      }
    })
    f.failed.foreach(errorHandler)
  }

  init()

  override def newUser(user: User): Unit = {
    val f = collection.insertOne(user).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  override def deleteUser(email: String): Unit = {
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
    f.failed.foreach(errorHandler)
    val user = waitReadyResult(f)
    Option(user)
  }

  override def getAllUsers(): Seq[User] = {
    val f = collection.find().toFuture()
    f.failed.foreach(errorHandler)
    waitReadyResult(f)
  }

  override def getAdminUsers(): Seq[User] = {
    val f = collection.find(equal("isAdmin", true)).toFuture()
    f.failed.foreach(errorHandler)
    waitReadyResult(f)
  }

}
