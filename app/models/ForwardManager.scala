package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import play.api._
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._

import javax.inject.Inject

case class LatestRecordTime(time: Long)

case class ForwardManagerConfig(server: String, monitor: String)

object ForwardManager {
  implicit val latestRecordTimeRead: Reads[LatestRecordTime] = Json.reads[LatestRecordTime]

  def getConfig(configuration: Configuration): Option[ForwardManagerConfig] = {
    try {
      for (serverConfig <- configuration.getConfig("server")
           if serverConfig.getBoolean("enable").getOrElse(false)) yield {
        val server = serverConfig.getString("host").getOrElse("localhost")
        val monitor = serverConfig.getString("monitor").getOrElse("A01")
        ForwardManagerConfig(server, monitor)
      }
    } catch {
      case ex: Exception =>
        Logger.error("failed to get server config", ex)
        None
    }
  }

  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }

  case class ForwardHourRecord(start: DateTime, end: DateTime)

  case class ForwardMinRecord(start: DateTime, end: DateTime)

  case object ForwardHour

  case object ForwardMin

}

class ForwardManager @Inject()(hourRecordForwarderFactory: HourRecordForwarder.Factory, minRecordForwarderFactory: MinRecordForwarder.Factory)
                              (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor with InjectedActorSupport {

  import ForwardManager._

  Logger.info(s"create forwarder to $server/$monitor")

  val hourRecordForwarder: ActorRef = injectedChild(hourRecordForwarderFactory(server, monitor), "hourForwarder")

  val minRecordForwarder: ActorRef = injectedChild(minRecordForwarderFactory(server, monitor), "minForwarder")


  def receive: Receive = handler

  def handler: Receive = {
    case ForwardHour =>
      hourRecordForwarder ! ForwardHour

    case ForwardHourRecord =>
      hourRecordForwarder ! ForwardHourRecord

    case ForwardMin =>
      minRecordForwarder ! ForwardMin

    case ForwardMinRecord =>
      minRecordForwarder ! ForwardMinRecord

  }

  override def postStop(): Unit = {
  }
}