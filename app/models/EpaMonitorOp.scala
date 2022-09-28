package models

import play.api.Configuration

import javax.inject.{Inject, Singleton}

@Singleton
class EpaMonitorOp @Inject()(configuration: Configuration) {
  def getEpaMonitors(): Option[Seq[Monitor]] = {
    def toMonitor(config: Configuration) = {
      val id = config.getInt("id").get
      val name = config.getString("name").get
      val lat = config.getDouble("lat").get
      val lng = config.getDouble("lng").get
      Monitor(s"Epa$id", name, Some(lat), Some(lng), epaId = Some(id))
    }

    for {config <- configuration.getConfig("openData")
         enable <- config.getBoolean("enable") if enable
         monitors <- config.getConfigSeq("monitors")
         }
    yield
      monitors.map(toMonitor(_))
  }

  val map: Map[Int, Monitor] = getEpaMonitors().map(
    epaMonitors => epaMonitors.map(m => m.epaId.get -> m).toMap)
    .getOrElse(Map.empty[Int, Monitor])
}
