package models

import com.google.inject.ImplementedBy
import models.Group.{PLATFORM_ADMIN, PLATFORM_USER}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future

case class Ability(action:String, subject:String)
case class Group(_id: String, name: String, monitors:Seq[String], monitorTypes: Seq[String],
                 admin:Boolean, abilities: Seq[Ability], parent:Option[String] = None)

object Group {
  val PLATFORM_ADMIN = "platformAdmin"
  val PLATFORM_USER = "platformUser"
}

trait GroupDB {

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
  val defaultGroup: Seq[Group] =
    Seq(
      Group(_id = PLATFORM_ADMIN, "平台管理團隊", Seq.empty[String], Seq.empty[String],
        true, Seq(Ability(ACTION_MANAGE, SUBJECT_ALL))),
      Group(_id = PLATFORM_USER, "平台使用者", Seq.empty[String], Seq.empty[String],
        false, Seq(Ability(ACTION_READ, SUBJECT_DASHBOARD),
          Ability(ACTION_READ, SUBJECT_DATA),
          Ability(ACTION_SET, SUBJECT_ALARM)))
    )

  def newGroup(group: Group)

  def deleteGroup(_id: String): DeleteResult

  def updateGroup(group: Group): UpdateResult

  def getGroupByID(_id: String): Option[Group]

  def getAllGroups(): Seq[Group]

  def addMonitor(_id: String, monitorID: String): Future[UpdateResult]
}
