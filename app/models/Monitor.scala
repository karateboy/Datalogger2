package models

case class Monitor(_id: String, desc: String, lat: Option[Double] = None, lng: Option[Double] = None)

object Monitor {
  @volatile var activeId = "me"
  val defaultMonitor = Monitor(activeId, "本站")

  def setActiveMonitorId(_id: String): Unit = {
    activeId = _id
  }
}

