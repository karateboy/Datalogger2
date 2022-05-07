package models.sql

import models.{Group, GroupDB}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class GroupOp @Inject()()extends GroupDB{
  override def newGroup(group: Group): Unit = ???

  override def deleteGroup(_id: String): DeleteResult = ???

  override def updateGroup(group: Group): UpdateResult = ???

  override def getGroupByID(_id: String): Option[Group] = ???

  override def getAllGroups(): Seq[Group] = ???

  override def addMonitor(_id: String, monitorID: String): Future[UpdateResult] = ???
}
