package models

case class Monitor(_id: String,
                   desc: String,
                   lat: Option[Double] = Some(23.60158080805296),
                   lng: Option[Double] = Some(121.00400631100668))

object Monitor {
  @volatile var activeId = "me"
  val defaultMonitor = Monitor(activeId, "本站")

  def setActiveMonitorId(_id: String): Unit = {
    activeId = _id
  }
}

