package models.sql

import models.{Monitor, MonitorDB}
import org.mongodb.scala.result.DeleteResult

import javax.inject.{Inject, Singleton}
import scala.collection.immutable
import scala.concurrent.Future
@Singleton
class MonitorOp @Inject()() extends MonitorDB{
  //override val hasSelfMonitor: Boolean = _
  //override var map: Map[String, Monitor] = _

  override def mvList: immutable.Seq[String] = ???

  override def ensureMonitor(_id: String): Unit = ???

  override def newMonitor(m: Monitor): Unit = ???

  override def format(v: Option[Double]): String = ???

  override def upsert(m: Monitor): Unit = ???

  override def deleteMonitor(_id: String): Future[DeleteResult] = ???
}
