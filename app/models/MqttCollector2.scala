package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, DateTimeFormat}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import org.eclipse.paho.client.mqttv3._
import play.api._
import play.api.libs.json._

import java.nio.file.Files
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

case class EventConfig(instId: String, bit: Int, seconds: Option[Int])

case class MqttConfig2(topic: String)

object MqttCollector2 extends DriverOps {

  val defaultGroup = "_"

  implicit val r1: Reads[EventConfig] = Json.reads[EventConfig]
  implicit val w1: OWrites[EventConfig] = Json.writes[EventConfig]
  implicit val write: OWrites[MqttConfig2] = Json.writes[MqttConfig2]
  implicit val read: Reads[MqttConfig2] = Json.reads[MqttConfig2]

  override def getMonitorTypes(param: String): List[String] = {
    List("LAT", "LAT", "PM25")
  }

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[MqttConfig2]
    ret.fold(
      error => {
        Logger.error(s"MQTT2: ${JsError.toJson(error)}")
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

  def validateParam(json: String): MqttConfig2 = {
    val ret = Json.parse(json).validate[MqttConfig2]
    ret.fold(
      error => {
        Logger.error(s"MQTT2: ${JsError.toJson(error)}")
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  trait Factory {
    def apply(id: String, protocolParam: ProtocolParam, config: MqttConfig2): Actor
  }

  sealed trait MqttMessage2

  case object CreateClient extends MqttMessage2

  case object ConnectBroker extends MqttMessage2

  case object SubscribeTopic extends MqttMessage2

  case object CheckTimeout extends MqttMessage2

  case class HandleMessage(message:String) extends MqttMessage2

  val timeout = 15 // mintues
}

import javax.inject._

class MqttCollector2 @Inject()(monitorOp: MonitorOp, alarmOp: AlarmOp,
                              recordOp: RecordOp, dataCollectManagerOp: DataCollectManagerOp,
                               mqttSensorOp: MqttSensorOp)
                             (@Assisted id: String,
                              @Assisted protocolParam: ProtocolParam,
                              @Assisted config: MqttConfig2) extends Actor with MqttCallback {
  import MqttCollector2._
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
  var lastDataArrival: DateTime = DateTime.now

  val watchDog = context.system.scheduler.schedule(Duration(1, SECONDS),
    Duration(timeout, MINUTES), self, CheckTimeout)

  self ! CreateClient

  def receive = handler(MonitorStatus.NormalStat, Map.empty[String, Sensor])

  def handler(collectorState: String, sensorMap: Map[String, Sensor]): Receive = {
    case CreateClient =>
      Logger.info(s"Init Mqtt client ${protocolParam.host.get} ${config.toString}")
      val url = if (protocolParam.host.get.contains(":"))
        s"tcp://${protocolParam.host.get}"
      else
        s"tcp://${protocolParam.host.get}:1883"

      import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
      import org.eclipse.paho.client.mqttv3.{MqttAsyncClient, MqttException}
      val  tmpDir = Files.createTempDirectory(MqttAsyncClient.generateClientId()).toFile().getAbsolutePath();
      Logger.info(s"$id uses $tmpDir as tempDir")
      val dataStore = new MqttDefaultFilePersistence(tmpDir)
      try {
        mqttClientOpt = Some(new MqttAsyncClient(url, MqttAsyncClient.generateClientId(), dataStore))
        mqttClientOpt map {
          client =>
            client.setCallback(this)
        }
        self ! ConnectBroker
      } catch {
        case e: MqttException =>
          Logger.error("Unable to set up client: " + e.toString)
          import scala.concurrent.duration._
          alarmOp.log(alarmOp.instStr(id), alarmOp.Level.ERR, s"無法連接:${e.getMessage}")
          context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, CreateClient)
      }
    case ConnectBroker =>
      Future {
        blocking {
          mqttClientOpt map {
            client =>
              val conOpt = new MqttConnectOptions
              conOpt.setAutomaticReconnect(true)
              conOpt.setCleanSession(false)
              try {
                val conToken = client.connect(conOpt, null, null)
                conToken.waitForCompletion()
                Logger.info(s"MqttCollector $id: Connected")
                self ! SubscribeTopic
              } catch {
                case ex: Exception =>
                  Logger.error("connect broker failed.", ex)
                  context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectBroker)
              }
          }
        }
      }

    case SubscribeTopic =>
      Future {
        blocking {
          mqttClientOpt map {
            client =>
              try {
                val subToken = client.subscribe(config.topic, 2, null, null)
                subToken.waitForCompletion()
                Logger.info(s"MqttCollector $id: Subscribed")
              } catch {
                case ex: Exception =>
                  Logger.error("Subscribe failed", ex)
                  context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, SubscribeTopic)
              }
          }
        }
      }
    case CheckTimeout=>
      val duration = new org.joda.time.Duration(lastDataArrival, DateTime.now())
      if(duration.getStandardMinutes > timeout) {
        Logger.error(s"Mqtt ${id} no data timeout!")
        context.parent ! RestartMyself
      }

      for(map <- mqttSensorOp.getSensorMap) yield {
        context become handler(collectorState, map)
      }

    case SetState(id, state) =>
      Logger.warn(s"$id ignore $self => $state")
      context become handler(state, sensorMap)

    case HandleMessage(message) =>
      messageHandler(message, sensorMap)
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
    try {
      lastDataArrival = DateTime.now
      self ! HandleMessage(new String(message.getPayload))
    } catch {
      case ex: Exception =>
        Logger.error("failed to handleMessage", ex)
    }

  }

  def messageHandler(payload: String, sensorMap: Map[String, Sensor]): Unit = {
    val mtMap = Map[String, String](
      "pm2_5" -> MonitorType.PM25,
      "pm10" -> MonitorType.PM10,
      "humidity" -> MonitorType.HUMID,
      "o3" -> MonitorType.O3,
      "temperature"-> MonitorType.TEMP,
      "voc"-> MonitorType.VOC,
      "no2"-> MonitorType.NO2,
      "h2s"-> MonitorType.H2S,
      "nh3"-> MonitorType.NH3,
      "co"-> MonitorType.CO
    )

    val ret = Json.parse(payload).validate[Message]
    ret.fold(err => {
      Logger.error(s"MQTT2: ${JsError.toJson(err)}")
    },
      message => {
        val mtData: Seq[Option[MtRecord]] =
          for (data <- message.data) yield {
            val sensor = (data \ "sensor").get.validate[String].get
            val value: Option[Double] = (data \ "value").get.validate[Double].fold(
              err => {
                // Logger.error(s"MQTT2: value ${JsError.toJson(err)}")
                None
              },
              v => Some(v)
            )
            for {mt <- mtMap.get(sensor)
                 v <- value
                 } yield {
              MtRecord(mt, v, MonitorStatus.NormalStat)
            }
          }
        val latlon = Seq(MtRecord(MonitorType.LAT, message.lat, MonitorStatus.NormalStat),
          MtRecord(MonitorType.LNG, message.lon, MonitorStatus.NormalStat))
        val mtDataList: Seq[MtRecord] = mtData.flatten ++ latlon
        val time = DateTime.parse(message.time, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
        if(sensorMap.contains(message.id)){
          val sensor = sensorMap(message.id)
          val mtList = mtDataList.map(_.mtName)
          val monitor = monitorOp.map(sensor.monitor)
          if(monitor.location.isEmpty)
            monitor.location = Some(Seq(message.lat, message.lon))

          if(!mtList.forall(monitor.monitorTypes.contains(_))){
            monitor.monitorTypes = Set(monitor.monitorTypes ++ mtList :_*).toSeq
          }
          monitorOp.upsert(monitor)
          val recordList = RecordList(time.toDate, mtDataList, sensor.monitor)
          val f = recordOp.upsertRecord(recordList)(recordOp.MinCollection)
          f.onFailure(ModelHelper.errorHandler)
          dataCollectManagerOp.checkMinDataAlarm(recordList.mtDataList, Some(sensor.group))
        }
      })
  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {

  }

  case class Message(id: String, lat: Double, lon: Double, time: String, data: Seq[JsValue])

}
