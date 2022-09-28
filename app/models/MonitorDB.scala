package models

import org.mongodb.scala.result.DeleteResult
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
trait MonitorDB {

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  var map: Map[String, Monitor] = Map.empty[String, Monitor]

  def mvList: Seq[String] = synchronized(map.map(_._1).toSeq)

  def ensure(_id: String): Unit = {
    synchronized{
      if (!map.contains(_id)) {
        val monitor = Monitor(_id, _id)
        upsert(monitor)
      }
    }
  }

  def ensure(m:Monitor):Unit = {
    synchronized{
      if (!map.contains(m._id))
        upsert(m)
    }
  }



  def upsert(m: Monitor): Unit

  protected def deleteMonitor(_id: String): Future[DeleteResult]

  def delete(_id:String, sysConfigDB: SysConfigDB) : Future[DeleteResult] = {
    for(ret<- deleteMonitor(_id)) yield {
      refresh(sysConfigDB)
      ret
    }
  }

  def mList: List[Monitor]

  def refresh(sysConfigDB: SysConfigDB) {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }

    for(activeId <- sysConfigDB.getActiveMonitorId())
      Monitor.setActiveMonitorId(activeId)

    synchronized{
      map = pairs.toMap
    }
  }
}
