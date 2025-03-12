package models

import play.api.Configuration

import javax.inject.{Inject, Singleton}

@Singleton
class EpaMonitorOp @Inject()(configuration: Configuration, monitorTypeDB: MonitorTypeDB) {
  def getEpaMonitors: Option[Seq[Monitor]] = {
    def toMonitor(config: Configuration) = {
      val id = config.get[Int]("id")
      val name = config.get[String]("name")
      val lat = config.get[Double]("lat")
      val lng = config.get[Double]("lng")
      Monitor(s"Epa$id", name, Some(lat), Some(lng))
    }

    for {config <- configuration.getOptional[Configuration]("openData")
         enable <- config.getOptional[Boolean]("enable") if enable
         monitors <- config.getOptional[Seq[Configuration]]("monitors")
         }
    yield
      monitors.map(toMonitor)
  }

  val map: Map[Int, Monitor] = getEpaMonitors.map(
    epaMonitors => epaMonitors.map(m => m._id.drop(3).toInt -> m).toMap)
    .getOrElse(Map.empty[Int, Monitor])

  val upstream: Option[String] =
      for {config <- configuration.getOptional[Configuration]("openData")
           enable <- config.getOptional[Boolean]("enable") if enable
           } yield
        config.get[String]("upstream")
}
