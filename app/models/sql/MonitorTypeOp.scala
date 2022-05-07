package models.sql

import models.{MonitorType, MonitorTypeDB}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
@Singleton
class MonitorTypeOp @Inject()() extends MonitorTypeDB{
  override def logDiMonitorType(mt: String, v: Boolean): Unit = ???

  override def getList: List[MonitorType] = ???

  override def newMonitorType(mt: MonitorType): Future[InsertOneResult] = ???

  override def deleteMonitorType(_id: String): Unit = ???

  override def upsertMonitorTypeFuture(mt: MonitorType): Future[UpdateResult] = ???
}
