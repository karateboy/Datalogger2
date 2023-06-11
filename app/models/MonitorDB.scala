package models

import org.mongodb.scala.result.DeleteResult
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
trait MonitorDB {

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  def mvList: List[String] = mList.map(_._id)
  @volatile var map: Map[String, Monitor] = Map.empty[String, Monitor]


  def ensure(_id: String): Unit = synchronized{
    if (!map.contains(_id)) {
      upsert(Monitor(_id, _id))
    }
  }

  def ensure(m: Monitor): Unit = synchronized {
    if (!map.contains(m._id)) {
      upsert(m)
    }
  }
  protected def upsert(m: Monitor): Unit

  def upsertMonitor(m: Monitor): Unit = {
    synchronized {
      map = map + (m._id -> m)
    }

    upsert(m)
  }

  protected def deleteMonitor(_id: String): Future[DeleteResult]

  def delete(_id:String, sysConfigDB: SysConfigDB) : Future[DeleteResult] = {
    for(ret<- deleteMonitor(_id)) yield {
      refresh(sysConfigDB)
      ret
    }
  }

  def mList: List[Monitor]

  def refresh(sysConfigDB: SysConfigDB): Unit = {
    val pairs =
      for (m <- mList) yield {
        m._id -> m
      }

    for(activeId <- sysConfigDB.getActiveMonitorId)
      Monitor.setActiveMonitorId(activeId)

    map = pairs.toMap
  }
}
