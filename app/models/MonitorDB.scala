package models

import com.google.inject.ImplementedBy
import org.mongodb.scala.result.DeleteResult
import play.api.libs.json.Json

import scala.collection.immutable
import scala.concurrent.Future

@ImplementedBy(classOf[mongodb.MonitorOp])
trait MonitorDB {

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]
  val hasSelfMonitor: Boolean = true
  var map: Map[String, Monitor] = Map.empty[String, Monitor]

  def mvList: immutable.Seq[String]

  def ensureMonitor(_id: String): Unit

  def newMonitor(m: Monitor): Unit

  def format(v: Option[Double]): String

  def upsert(m: Monitor): Unit

  def deleteMonitor(_id: String): Future[DeleteResult]
}
