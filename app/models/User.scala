package models

import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.Updates
import org.mongodb.scala.result.DeleteResult
import play.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions


case class User(_id: String,
                password: String,
                name: String,
                isAdmin: Boolean,
                group: Option[String],
                monitorTypeOfInterest: Seq[String],
                alertEmail: Option[String],
                smsPhone: Option[String] = None) {
  def getEmailTargets: Seq[String] = {
    alertEmail match {
      case Some(email) =>
        if (email.isEmpty)
          Seq.empty[String]
        else if (email.contains(","))
          email.split(",").map(_.trim)
        else if (email.contains(";"))
          email.split(";").map(_.trim)
        else
          Seq(email)
      case None =>
        Seq.empty[String]
    }
  }
}

import javax.inject._

@Singleton
class UserOp @Inject()(mongoDB: MongoDB) {

  import org.mongodb.scala._

  val ColName = "users"
  val codecRegistry = fromRegistries(fromProviders(classOf[User], classOf[AlarmConfig]), DEFAULT_CODEC_REGISTRY)
  val collection: MongoCollection[User] = mongoDB.database.withCodecRegistry(codecRegistry).getCollection(ColName)

  def init() {
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
          val defaultUser = User("sales@wecc.com.tw", "abc123", "Aragorn", true, Some(Group.PLATFORM_ADMIN),
            Seq(MonitorType.PM25), None)
          Logger.info("Create default user:" + defaultUser.toString)
          newUser(defaultUser)
        }
    })
    f.onFailure(errorHandler)
  }


  init

  def newUser(user: User) = {
    val f = collection.insertOne(user).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._

  def deleteUser(email: String): DeleteResult = {
    val f = collection.deleteOne(equal("_id", email)).toFuture()
    waitReadyResult(f)
  }

  def updateUser(user: User): Unit = {
    if (user.password != "") {
      val f = collection.replaceOne(equal("_id", user._id), user).toFuture()
      waitReadyResult(f)
    } else {
      val commonUpdates =
        Seq(
          Updates.set("name", user.name),
          Updates.set("isAdmin", user.isAdmin),
          Updates.set("monitorTypeOfInterest", user.monitorTypeOfInterest),
          Updates.set("alertEmail", user.getEmailTargets.mkString(";"))
        )

      val updateGroupOpt = for (group <- user.group) yield
        Updates.set("group", group)

      val updateSmsPhoneOpt = for (smsPhone <- user.smsPhone) yield
        Updates.set("smsPhone", smsPhone)

      val updates = Updates.combine(commonUpdates ++ Seq(updateGroupOpt, updateSmsPhoneOpt).flatten: _*)
      val f = collection.findOneAndUpdate(equal("_id", user._id), updates).toFuture()
      waitReadyResult(f)
    }

  }

  def getUserByEmail(email: String): Option[User] = {
    val f = collection.find(equal("_id", email)).first().toFuture()
    f.onFailure {
      errorHandler
    }
    val user = waitReadyResult(f)
    Option(user)
  }

  def getAllUsers: Seq[User] = {
    val f = collection.find().toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

  def getAllUserFuture(): Future[Seq[User]] = {
    val f = collection.find().toFuture()
    f onFailure errorHandler
    f
  }

  def getAdminUsers(): Seq[User] = {
    val f = collection.find(equal("isAdmin", true)).toFuture()
    f.onFailure {
      errorHandler
    }
    waitReadyResult(f)
  }

  def getAlertEmailUsers: Future[Seq[User]] = {
    val f = collection.find(exists("alertEmail", true)).toFuture()
    f onFailure errorHandler()
    f
  }

  def getUsersByGroupFuture(group: String): Future[Seq[User]] = {
    val f = collection.find(equal("group", group)).toFuture()
    f.onFailure {
      errorHandler
    }
    f
  }
}
