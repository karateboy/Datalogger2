package models

case class Monitor(_id: String, desc: String, lat: Option[Double] = None, lng: Option[Double] = None)

object Monitor {
  val SELF_ID = "me"
  val selfMonitor = Monitor(SELF_ID, "本站")
  var activeID = SELF_ID

  def setActiveMonitor(m: Monitor): Unit = {
    activeID = m._id
  }
}

