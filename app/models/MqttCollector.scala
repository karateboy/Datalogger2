package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, DateTimeFormat}
import com.google.inject.assistedinject.Assisted
import models.MqttCollector.{ConnectBroker, CreateClient, SubscribeTopic}
import models.Protocol.ProtocolParam
import org.eclipse.paho.client.mqttv3._
import play.api._
import play.api.libs.json.{JsError, Json, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES}

case class EventConfig(instId: String, bit: Int)

case class MqttConfig(topic: String, monitor: String, eventConfig: EventConfig)

object MqttCollector extends DriverOps {
  var count = 0
  implicit val r1 = Json.reads[EventConfig]
  implicit val w1 = Json.writes[EventConfig]
  implicit val write = Json.writes[MqttConfig]
  implicit val read = Json.reads[MqttConfig]

  override def getMonitorTypes(param: String): List[String] = {
    List("LAT", "LAT", "PM25")
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[MqttConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

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

class MqttCollector @Inject()(monitorTypeOp: MonitorTypeOp, alarmOp: AlarmOp, system: ActorSystem,
                              recordOp: RecordOp, monitorOp: MonitorOp)
                             (@Assisted id: String,
                              @Assisted protocolParam: ProtocolParam,
                              @Assisted config: MqttConfig) extends Actor with MqttCallback {


  monitorOp.ensureMonitor(config.monitor)

  val payload =
    """{"id":"861108035994663",
      |"desc":"柏昇SAQ-200",
      |"manufacturerId":"aeclpad",
      |"lat":24.9816875,
      |"lon":121.5361633,
      |"time":"2021-02-15 21:06:27",
      |"attributes":[{"key":"mac_id","value":"861108035994663"},{"key":"devstat","value":0},{"key":"sb_id","value":"03f4a2bc"},{"key":"mb_id","value":"203237473047500A00470055"},{"key":"errorcode","value":"00000000000000000000000000000000"}],
      |"data":[{"sensor":"co","value":"NA","unit":"ppb"},
      |{"sensor":"o3","value":"NA","unit":"ppb"},
      |{"sensor":"noise","value":"NA","unit":"dB"},
      |{"sensor":"voc","value":235,"unit":""},{"sensor":"pm2_5","value":18,"unit":"µg/m3"},{"sensor":"pm1","value":16,"unit":"µg/m3"},{"sensor":"pm10","value":29,"unit":"µg/m3"},{"sensor":"no2","value":"NA","unit":"ppb"},{"sensor":"humidity","value":69.5,"unit":"%"},{"sensor":"temperature","value":19.15,"unit":"℃"},{"sensor":"humidity_main","value":47.9,"unit":"%"},{"sensor":"temperature_main","value":24.52,"unit":"℃"},{"sensor":"volt","value":36645,"unit":"v"},{"sensor":"ampere","value":48736,"unit":"mA"},{"sensor":"devstat","value":0,"unit":""}]}
      |
      |""".stripMargin

  implicit val reads = Json.reads[Message]
  var mqttClientOpt: Option[MqttAsyncClient] = None


  self ! CreateClient

  def messageHandler(payload: String): Unit = {
    val mtMap = Map[String, String](
      "pm2_5" -> monitorTypeOp.PM25,
      "pm10" -> monitorTypeOp.PM10,
      "humidity" -> "HUMID"
    )
    val ret = Json.parse(payload).validate[Message]
    ret.fold(err => {
      Logger.error(JsError.toJson(err).toString())
    },
      message => {
        val mtData: Seq[Option[MtRecord]] =
          for (data <- message.data) yield {
            val sensor = (data \ "sensor").get.validate[String].get
            val value: Option[Double] = (data \ "value").get.validate[Double].fold(
              err => {
                None
              },
              v => Some(v)
            )
            for {mt <- mtMap.get(sensor)
                 v <- value
                 } yield
              MtRecord(mt, v, MonitorStatus.NormalStat)

          }
        val latlon = Seq(MtRecord(monitorTypeOp.LAT, message.lat, MonitorStatus.NormalStat),
          MtRecord(monitorTypeOp.LNG, message.lon, MonitorStatus.NormalStat))
        val mtDataList: Seq[MtRecord] = mtData.flatten ++ latlon
        val time = DateTime.parse(message.time, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
        val recordList = RecordList(time.toDate, mtDataList, config.monitor)
        val f = recordOp.upsertRecord(recordList)(recordOp.MinCollection)
        f.onFailure(ModelHelper.errorHandler)
      })
  }


  def receive = handler(MonitorStatus.NormalStat)

  def handler(collectorState: String): Receive = {
    case CreateClient =>
      Logger.info(s"Init Mqtt client ${protocolParam.host.get} ${config.toString}")
      val url = if (protocolParam.host.get.contains(":"))
        s"tcp://${protocolParam.host.get}"
      else
        s"tcp://${protocolParam.host.get}:1883"

      val clientId = s"WeccMqttCollector_$id"
      import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
      import org.eclipse.paho.client.mqttv3.{MqttAsyncClient, MqttException}
      val tmpDir = System.getProperty("java.io.tmpdir")
      val dataStore = new MqttDefaultFilePersistence(tmpDir)
      try {
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
          try {
            val conToken = client.connect(conOpt, null, null)
            conToken.waitForCompletion()
            Logger.info(s"MqttCollector $id: Connected")
            self ! SubscribeTopic
          } catch {
            case ex: Exception =>
              Logger.error("connect broker failed.", ex)
              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectBroker)
          }
      }
    case SubscribeTopic =>
      mqttClientOpt map {
        client =>
          try {
            val subToken = client.subscribe(config.topic, 2, null, null)
            subToken.waitForCompletion()
            Logger.info(s"MqttCollector $id: Subscribed")
          } catch {
            case ex: Exception =>
              Logger.error("Subscribe failed", ex)
              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, SubscribeTopic)
          }
      }
    case SetState(id, state) =>
      Logger.warn(s"$id ignore $self => $state")
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
    try{
      messageHandler(new String(message.getPayload))
    }catch{
      case ex:Exception=>
        Logger.debug(new String(message.getPayload))
        Logger.error("failed to handleMessage", ex)
    }

  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {

  }

  case class Message(id: String, desc: String, lat: Double, lon: Double, time: String, data: Seq[JsValue])

}
