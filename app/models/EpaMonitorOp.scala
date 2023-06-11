package models

import play.api.Configuration

import javax.inject.{Inject, Singleton}

@Singleton
class EpaMonitorOp @Inject()(configuration: Configuration, monitorTypeDB: MonitorTypeDB) {
  def getEpaMonitors: Option[Seq[Monitor]] = {
    def toMonitor(config: Configuration) = {
      val id = config.getInt("id").get
      val name = config.getString("name").get
      val lat = config.getDouble("lat").get
      val lng = config.getDouble("lng").get
      Monitor(s"Epa$id", name, Some(lat), Some(lng))
    }

    for {config <- configuration.getConfig("openData")
         enable <- config.getBoolean("enable") if enable
         monitors <- config.getConfigSeq("monitors")
         }
    yield
      monitors.map(toMonitor)
  }

  val map: Map[Int, Monitor] = getEpaMonitors.map(
    epaMonitors => epaMonitors.map(m => m._id.drop(3).toInt -> m).toMap)
    .getOrElse(Map.empty[Int, Monitor])

  val upstream: Option[String] = {
    val ret =
      for {config <- configuration.getConfig("openData")
           enable <- config.getBoolean("enable") if enable
           } yield
        config.getString("upstream")

    ret.flatten
  }
}
