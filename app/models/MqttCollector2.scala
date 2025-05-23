package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, DateTimeFormat}
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.{errorHandler, waitReadyResult}
import models.Protocol.{ProtocolParam, tcp}
import org.eclipse.paho.client.mqttv3._
import play.api._
import play.api.libs.json._

import java.nio.file.Files
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration, MINUTES}
import scala.concurrent.{Future, blocking}
import scala.util.Success

case class MqttConfig2(topic: String, group: String, eventConfig: EventConfig)

case class EventConfig(instId: String, bit: Int, seconds: Option[Int])

case class MqttConfig(topic: String, monitor: String, eventConfig: EventConfig)

object MqttCollector2 extends DriverOps {
  val logger: Logger = Logger(this.getClass)
  val defaultGroup = "_"

  implicit val r1: Reads[EventConfig] = Json.reads[EventConfig]
  implicit val w1: OWrites[EventConfig] = Json.writes[EventConfig]
  implicit val write: OWrites[MqttConfig2] = Json.writes[MqttConfig2]
  implicit val read: Reads[MqttConfig2] = Json.reads[MqttConfig2]

  override def getMonitorTypes(param: String): List[String] = {
    List(MonitorType.LAT, MonitorType.LNG, MonitorType.PM25, MonitorType.PM10, MonitorType.HUMID)
  }

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[MqttConfig2]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val config = validateParam(param)
    val f2 = f.asInstanceOf[Factory]
    f2(id, protocol, config)
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[MqttConfig2]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  trait Factory {
    def apply(id: String, protocolParam: ProtocolParam, config: MqttConfig2): Actor
  }

  case object CreateClient

  case object ConnectBroker

  case object SubscribeTopic

  case object CheckTimeout

  val timeout = 15 // mintues

  override def id: String = "mqtt_client2"

  override def description: String = "MQTT Client2"

  override def protocol: List[String] = List(tcp)
}

import javax.inject._

class MqttCollector2 @Inject()(monitorDB: MonitorDB, alarmOp: AlarmDB,
                               recordOp: RecordDB,
                               mqttSensorOp: MqttSensorDB, monitorTypeDB: MonitorTypeDB)
                              (@Assisted id: String,
                               @Assisted protocolParam: ProtocolParam,
                               @Assisted config: MqttConfig2) extends Actor with MqttCallback {

  import MqttCollector2._
  import DataCollectManager._

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
  @volatile var mqttClientOpt: Option[MqttAsyncClient] = None
  @volatile var lastDataArrival: DateTime = DateTime.now

  val watchDog = context.system.scheduler.scheduleAtFixedRate(FiniteDuration(1, MINUTES),
    Duration(timeout, MINUTES), self, CheckTimeout)

  @volatile var sensorMap: Map[String, Sensor] = {
    waitReadyResult(mqttSensorOp.getSensorMap)
  }
  self ! CreateClient

  def receive: Receive = handler(MonitorStatus.NormalStat)

  def handler(collectorState: String): Receive = {
    case CreateClient =>
      logger.info(s"Init Mqtt client ${protocolParam.host.get} ${config.toString}")
      val url = if (protocolParam.host.get.contains(":"))
        s"tcp://${protocolParam.host.get}"
      else
        s"tcp://${protocolParam.host.get}:1883"

      import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
      import org.eclipse.paho.client.mqttv3.{MqttAsyncClient, MqttException}
      val tmpDir = Files.createTempDirectory(MqttAsyncClient.generateClientId()).toFile().getAbsolutePath();
      logger.info(s"$id uses $tmpDir as tempDir")
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
          logger.error("Unable to set up client: " + e.toString)
          import scala.concurrent.duration._
          alarmOp.log(alarmOp.instrumentSrc(id), Alarm.Level.ERR, s"無法連接:${e.getMessage}")
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
                logger.info(s"MqttCollector $id: Connected")
                self ! SubscribeTopic
              } catch {
                case ex: Exception =>
                  logger.error("connect broker failed.", ex)
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
                logger.info(s"MqttCollector $id: Subscribed")
              } catch {
                case ex: Exception =>
                  logger.error("Subscribe failed", ex)
                  context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, SubscribeTopic)
              }
          }
        }
      }
    case CheckTimeout =>
      for (map <- mqttSensorOp.getSensorMap) {
        sensorMap = map
      }

      val duration = new org.joda.time.Duration(lastDataArrival, DateTime.now())
      if (duration.getStandardMinutes > timeout) {
        logger.error(s"Mqtt ${id} no data timeout!")
        context.parent ! RestartMyself
      }

    case SetState(id, state) =>
      logger.warn(s"$id ignore $self => $state")
  }

  override def postStop(): Unit = {
    mqttClientOpt map {
      client =>
        logger.info("Disconnecting")
        val discToken: IMqttToken = client.disconnect(null, null)
        discToken.waitForCompletion()
    }
  }

  override def connectionLost(cause: Throwable): Unit = {
  }

  override def messageArrived(topic: String, message: MqttMessage): Unit = {
    try {
      lastDataArrival = DateTime.now
      messageHandler(topic, new String(message.getPayload))
    } catch {
      case ex: Exception =>
        logger.error("failed to handleMessage", ex)
    }

  }

  def messageHandler(topic: String, payload: String): Unit = {
    val mtMap = Map[String, String](
      "pm2_5" -> MonitorType.PM25,
      "pm10" -> MonitorType.PM10,
      "humidity" -> MonitorType.HUMID
    )
    val ret = Json.parse(payload).validate[Message]
    ret.fold(err => {
      logger.error(JsError.toJson(err).toString())
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
                 mtCase = monitorTypeDB.map(mt)
                 } yield
              monitorTypeDB.getMinMtRecordByRawValue(mt, Some(v), MonitorStatus.NormalStat)(mtCase.fixedM, mtCase.fixedB)
          }
        val latCase = monitorTypeDB.map(MonitorType.LAT)
        val lngCase = monitorTypeDB.map(MonitorType.LNG)
        val latlon = Seq(monitorTypeDB.getMinMtRecordByRawValue(MonitorType.LAT, Some(message.lat), MonitorStatus.NormalStat)(latCase.fixedM, latCase.fixedB),
          monitorTypeDB.getMinMtRecordByRawValue(MonitorType.LNG, Some(message.lon), MonitorStatus.NormalStat)(lngCase.fixedM, lngCase.fixedB))
        val mtDataList: Seq[MtRecord] = mtData.flatten ++ latlon
        val time = DateTime.parse(message.time, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
          .withSecondOfMinute(0)

        if (sensorMap.contains(message.id)) {
          val sensor = sensorMap(message.id)
          val f = recordOp.upsertRecord(recordOp.MinCollection)(RecordList.factory(time.toDate, mtDataList, sensor.monitor))
          f.failed.foreach(ModelHelper.errorHandler)
        } else {
          monitorDB.upsertMonitor(Monitor(message.id, message.id, Some(message.lat), Some(message.lon)))
          val sensor = Sensor(id = message.id, topic = topic, monitor = message.id, group = config.group)
          mqttSensorOp.upsert(sensor).andThen({
            case Success(_) =>
              sensorMap = sensorMap + (message.id -> sensor)
          })

          val f = recordOp.upsertRecord(recordOp.MinCollection)(RecordList.factory(time.toDate, mtDataList, sensor.monitor))
          f.failed.foreach(errorHandler)
        }
      })
  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {

  }

  case class Message(id: String, lat: Double, lon: Double, time: String, data: Seq[JsValue])

}
