package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.MqttCollector.{ConnectBroker, CreateClient, SubscribeTopic}
import models.Protocol.ProtocolParam
import org.eclipse.paho.client.mqttv3._
import play.api._
import play.api.libs.json.{JsError, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES}

case class MqttConfig(topic: String, monitor: String)

object MqttCollector extends DriverOps {
  var count = 0

  override def getMonitorTypes(param: String): List[String] = {
    List("LAT", "LAT", "PM25")
  }

  override def verifyParam(json: String) = {
    json
  }

  implicit val reads = Json.reads[MqttConfig]

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Factory])
    val config = validateParam(param)
    val f2 = f.asInstanceOf[Factory]
    f2(id, protocol, config)
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[MqttConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  trait Factory {
    def apply(id: String, protocolParam: ProtocolParam, config: MqttConfig): Actor
  }

  case object CreateClient

  case object ConnectBroker

  case object SubscribeTopic
}

import javax.inject._

class MqttCollector @Inject()(monitorTypeOp: MonitorTypeOp, alarmOp: AlarmOp, system: ActorSystem)
                             (@Assisted id: String,
                              @Assisted protocolParam: ProtocolParam,
                              @Assisted config: MqttConfig) extends Actor with MqttCallback {


  var mqttClientOpt: Option[MqttAsyncClient] = None

  def receive = handler(MonitorStatus.NormalStat)

  def handler(collectorState: String): Receive = {
    case CreateClient =>
      Logger.info(s"Init Mqtt client ${config.topic}")
      val url = if (protocolParam.host.get.contains(":"))
        s"tcp://${protocolParam.host}"
      else
        s"tcp://${protocolParam.host}:1833"

      val clientId = "MqttCollector"
      import org.eclipse.paho.client.mqttv3.{MqttAsyncClient, MqttConnectOptions, MqttException}
      import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
      val tmpDir = System.getProperty("java.io.tmpdir")
      val dataStore = new MqttDefaultFilePersistence(tmpDir)
      try {
        val conOpt = new MqttConnectOptions
        conOpt.setCleanSession(true)
        mqttClientOpt = Some(new MqttAsyncClient(url, clientId, dataStore))
        mqttClientOpt map {
          _.setCallback(this)
        }
        self ! ConnectBroker
      } catch {
        case e: MqttException =>
          Logger.error("Unable to set up client: " + e.toString)
          import scala.concurrent.duration._
          alarmOp.log(alarmOp.instStr(id), alarmOp.Level.ERR, s"無法連接:${e.getMessage}")
          system.scheduler.scheduleOnce(Duration(1, MINUTES), self, CreateClient)
      }
    case ConnectBroker =>
      mqttClientOpt map {
        client =>
          val conOpt = new MqttConnectOptions
          conOpt.setCleanSession(true)
          val conToken = client.connect(conOpt, null, null)
          conToken.setActionCallback(new IMqttActionListener {
            override def onSuccess(asyncActionToken: IMqttToken): Unit = {
              Logger.info("MqttCollector: Connected")
              self ! SubscribeTopic
            }

            override def onFailure(asyncActionToken: IMqttToken, exception: Throwable): Unit = {
              Logger.error("Connect Broker failed", exception)
              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectBroker)
            }
          })
      }
    case SubscribeTopic =>
      mqttClientOpt map {
        client =>
          val subToken = client.subscribe(config.topic, 3, null, null)
          subToken.setActionCallback(new IMqttActionListener {
            override def onSuccess(asyncActionToken: IMqttToken): Unit = {
              Logger.info("MqttCollector: Subscribed")
            }

            override def onFailure(asyncActionToken: IMqttToken, exception: Throwable): Unit = {
              Logger.error("Subscribe failed", exception)
              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, SubscribeTopic)
            }
          }
          )
      }
    case SetState(id, state) =>
      Logger.warn(s"Ignore $self => $state")
  }


  override def postStop(): Unit = {
    mqttClientOpt map {
      client =>
        Logger.info("Disconnecting")
        val discToken: IMqttToken = client.disconnect(null, null)
        discToken.waitForCompletion()
    }
  }

  override def connectionLost(cause: Throwable): Unit = {

  }

  override def messageArrived(topic: String, message: MqttMessage): Unit = {
    Logger.info(new String(message.getPayload))
  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {

  }
}
