package models

import org.mongodb.scala.result.DeleteResult

import scala.concurrent.Future

case class MonitorTypeGroup(_id: String, name: String, mts: Seq[String])
trait MonitorTypeGroupDb {

  def getMonitorTypeGroups: Future[Seq[MonitorTypeGroup]]
  def upsertMonitorTypeGroup(mtg: MonitorTypeGroup): Future[Boolean]
  def deleteMonitorTypeGroup(_id: String): Future[DeleteResult]
}
