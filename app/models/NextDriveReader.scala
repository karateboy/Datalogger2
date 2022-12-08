package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.getHourBetween
import org.apache.http.HttpStatus
import play.api._
import play.api.libs.json.JsError.toJson
import play.api.libs.json.{JsError, Json, OWrites, Reads}
import play.api.libs.ws.WSClient

import java.time.Instant
import java.util.Date
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.util.{Failure, Success}

case class NextDriveConfig(enable: Boolean, key: String, productID: String)

object NextDriveReader {
  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient): Unit = {
    def getConfig: Option[NextDriveConfig] = {
      for {config <- configuration.getConfig("nextDriveReader")
           enable <- config.getBoolean("enable")
           dir <- config.getString("key")
           productID <- config.getString("productID")
           }
      yield
        NextDriveConfig(enable, dir, productID)
    }

    for (config <- getConfig if config.enable)
      actorSystem.actorOf(props(config, sysConfig, monitorDB, monitorTypeOp, recordOp,
        dataCollectManagerOp, WSClient), "nextDriveReader")
  }

  def props(config: NextDriveConfig, sysConfig: SysConfigDB, monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp, WSClient: WSClient) =
    Props(new NextDriveReader(config, sysConfig, monitorDB, monitorTypeOp, recordOp, dataCollectManagerOp, WSClient))

  case object GetDevice

  case object GetSensorData

  case class TimeParam(startTime: Long, endTime: Long)

  case class Sensor(deviceUuid: String, scopes: Seq[String])

  case class SensorQueryParam(queries: Seq[Sensor], time: TimeParam, maxCount: Int, offset: Option[Int] = None)

  implicit val timeParamWrite: OWrites[TimeParam] = Json.writes[TimeParam]
  implicit val sensorWrite: OWrites[Sensor] = Json.writes[Sensor]
  implicit val sensorQueryParamWrite: OWrites[SensorQueryParam] = Json.writes[SensorQueryParam]

  case class SensorData(pid: String, deviceUuid: String, model: String, scope: String,
                        generatedTime: Option[Long], uploadedTime: Option[Long],
                        value: String)

  case class QueryResponse(results: Seq[SensorData], offset: Option[Int], totalCount: Option[Int])

  implicit val sensorDataRead: Reads[SensorData] = Json.reads[SensorData]
  implicit val queryResponseRead: Reads[QueryResponse] = Json.reads[QueryResponse]
}

class NextDriveReader(config: NextDriveConfig, sysConfig: SysConfigDB, monitorDB: MonitorDB,
                      monitorTypeOp: MonitorTypeDB, recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
                      WSClient: WSClient) extends Actor {
  Logger.info(s"NextDriveReader start")

  import NextDriveReader._
  import context.dispatcher

  val mtList: Seq[String] = Seq("normalUsage", "reverseUsage", "totalSoldElectricity")


  for (mt <- mtList) {
    monitorTypeOp.ensure(mt)
    recordOp.ensureMonitorType(mt)
  }

  @volatile var timerOpt: Option[Cancellable] = None


  override def receive: Receive = getDevicePhase

  self ! GetDevice

  case class Device(deviceUuid: String, model: String, name: String, onlineStatus: String)

  implicit val deviceReads: Reads[Device] = Json.reads[Device]

  case class GetDeviceResponse(devices: Seq[Device])

  implicit val getDeviceResponseReads: Reads[GetDeviceResponse] = Json.reads[GetDeviceResponse]

  def getDevicePhase: Receive = {
    case GetDevice =>
      try {
        val f = WSClient.url(s"https://ioeapi.nextdrive.io/v1/gateways/${config.productID}/devices")
          .withHeaders(("X-ND-TOKEN", config.key))
          .get()

        f.onComplete({
          case Success(resp) =>
            if (resp.status != HttpStatus.SC_OK) {
              Logger.error(s"get device failed ${resp.status}")
              context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
            } else {
              val deviceResponseOpt = resp.json.validate[GetDeviceResponse].asOpt
              if (deviceResponseOpt.isEmpty) {
                Logger.error(s"invalid response ${resp.json.toString()}")
                context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
              } else {
                val deviceResponse = deviceResponseOpt.get
                val noCameraList = deviceResponse.devices.filter(dev => dev.model != "Camera")
                noCameraList.foreach(device => monitorDB.ensure(Monitor(device.deviceUuid, device.name)))
                context become getSensorDataPhase(noCameraList)
                Logger.info("NextDrive Collector enter GetSensorDataPhase")
                self ! GetSensorData
              }
            }

          case Failure(exception) =>
            Logger.error("fail to get devices ", exception)
            context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
        })
      } catch {
        case ex: Throwable =>
          Logger.error("fail to get devices ", ex)
          context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
      }
  }

  private def updateMinRecords(sensorData: Seq[SensorData]): Future[Unit] = {
    val docList = ListBuffer.empty[RecordList]
    sensorData.foreach(sensor => {
      for {
        generatedTime <- sensor.generatedTime
      } {
        val value = try {
          Some(sensor.value.toDouble)
        } catch {
          case ex: Throwable =>
            None
        }
        val mtDataList = Seq(MtRecord(sensor.scope, value, MonitorStatus.NormalStat))
        docList.append(RecordList(time = Date.from(Instant.ofEpochMilli(generatedTime)), monitor = sensor.deviceUuid,
          mtDataList = mtDataList))
      }
    })
    if (docList.nonEmpty)
      recordOp.upsertManyRecords(recordOp.MinCollection)(docList).map(_ => Unit)
    else
      Future.successful(Unit)
  }

  def getSensorData(lastDataTime: Instant, devices: Seq[Device], offset: Option[Int], totalCount:Option[Int]): Future[Unit] = {
    val queryParam = SensorQueryParam(queries = devices.map(device => Sensor(device.deviceUuid, mtList)),
      time = TimeParam(lastDataTime.getEpochSecond * 1000, Instant.now.getEpochSecond * 1000),
      offset = offset,
      maxCount = 500)
    Logger.info(queryParam.toString)
    val f = WSClient.url("https://ioeapi.nextdrive.io/v1/device-data/query")
      .withHeaders(("X-ND-TOKEN", config.key))
      .post(Json.toJson(queryParam))

    f onComplete({
      case Success(response)=>
        if (response.status != HttpStatus.SC_OK) {
          Logger.error(s"getSensorData resp code=${response.status} body=${response.json}")
        }else{
          response.json.validate[QueryResponse].fold(
            err => {
              Logger.error(toJson(JsError(err)).toString())
              Logger.error(response.json.toString())
            },
            ret => {
              //Store DB
              updateMinRecords(ret.results).andThen({
                case Success(_) =>
                  //Recursive if offset != totalCount
                  if ((totalCount.nonEmpty && totalCount != ret.offset)||
                    (ret.totalCount.nonEmpty && ret.totalCount != ret.offset))
                    getSensorData(lastDataTime, devices, ret.offset, ret.totalCount)
                  else {
                    // Update last data
                    val lastEpochMs = ret.results.flatMap(_.generatedTime).max
                    val start = new DateTime(Date.from(lastDataTime))
                    val end = new DateTime(Date.from(Instant.ofEpochMilli(lastEpochMs)))
                    for (m <- monitorDB.mvList; current <- getHourBetween(start, end))
                      dataCollectManagerOp.recalculateHourData(m, current)(monitorTypeOp.activeMtvList, monitorTypeOp)

                    sysConfig.setLastDataTime(Instant.ofEpochMilli(lastEpochMs)).map(Success(_))
                  }
                case Failure(exception) =>
                  Logger.error(s"fail to update min data", exception)
              })
            }
          )
        }

      case Failure(exception)=>
        Logger.error("fail to getSensorData", exception)
    })
    f.map(_=>Unit)
  }

  def getSensorDataPhase(devices: Seq[Device]): Receive = {
    case GetSensorData =>
      try {
        val ret: Future[Unit] = {
          for (lastDataTime <- sysConfig.getLastDataTime) yield {
            getSensorData(lastDataTime = lastDataTime, devices = devices, offset = None, totalCount = None)
          } recover ({
            case ex =>
              Logger.error("failed to get sensor data", ex)
          })
        } flatMap (x => x)
        ret.andThen({
          case Success(_) =>

        })
      } catch {
        case ex: Throwable =>
          Logger.error("failed to get sensor data", ex)
      } finally {
        timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetSensorData))
      }
  }

  override def postStop(): Unit = {
    for (timer <- timerOpt)
      timer.cancel()

    super.postStop()
  }

}
