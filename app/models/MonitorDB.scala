package models

import org.mongodb.scala.result.DeleteResult
import play.api.libs.json.Json

import scala.concurrent.Future

trait MonitorDB {

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]
  val hasSelfMonitor: Boolean = true
  var map: Map[String, Monitor] = Map.empty[String, Monitor]

  def mvList: Seq[String] = map.map(_._1).toSeq

  def ensureMonitor(_id: String): Unit = {
    if (!map.contains(_id)) {
      upsert(Monitor(_id, _id))
    }
  }

  def format(v: Option[Double]): String = if (v.isEmpty)
    "-"
  else
    v.get.toString

  def upsert(m: Monitor): Unit

  def deleteMonitor(_id: String): Future[DeleteResult]

  def mList: List[Monitor]

  def refresh {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }
    map = pairs.toMap
  }
}
