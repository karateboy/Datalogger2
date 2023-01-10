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
import java.time.temporal.ChronoUnit
import java.util.Date
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.util.{Failure, Success}

case class NextDriveConfig(enable: Boolean, key: String, productIDs: Seq[String])

object NextDriveReader {
  def start(configuration: Configuration, actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient): Unit = {
    def getConfig: Option[NextDriveConfig] = {
      for {config <- configuration.getConfig("nextDriveReader")
           enable <- config.getBoolean("enable")
           dir <- config.getString("key")
           productIDs <- config.getStringSeq("productIDs")
           }
      yield
        NextDriveConfig(enable, dir, productIDs)
    }

    for (config <- getConfig if config.enable)
      actorSystem.actorOf(props(config, sysConfig, monitorDB, monitorTypeOp, recordOp,
        dataCollectManagerOp, WSClient), "nextDriveReader")
  }

  def props(config: NextDriveConfig, sysConfig: SysConfigDB, monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp, WSClient: WSClient): Props =
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

  val mtList: Seq[String] = Seq(MonitorType.NORMAL_USAGE, MonitorType.POWER)

  val monitors = Seq(
    Monitor(_id = "site1", "九份子1"),
    Monitor(_id = "site2", "九份子2"),
    Monitor(_id = "site3", "九份子3"),
    Monitor(_id = "site4", "九份子4"),
    Monitor(_id = "site5", "九份子5"),
    Monitor(_id = "site6", "九份子6")
  )
  for (mt <- mtList) {
    monitorTypeOp.ensure(mt)
    recordOp.ensureMonitorType(mt)
  }

  for (m <- monitors)
    monitorDB.ensure(m)

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
        val futures = config.productIDs.map(productID => {
          WSClient.url(s"https://ioeapi.nextdrive.io/v1/gateways/${productID}/devices")
            .withHeaders(("X-ND-TOKEN", config.key))
            .get()
        })
        val f = Future.sequence(futures)

        f.onComplete({
          case Success(responses) =>
            if (!responses.forall(resp => resp.status == HttpStatus.SC_OK)) {
              val noOK = responses.zip(config.productIDs)
                .filter(t =>
                  t._1.status != HttpStatus.SC_OK).map(t => (t._2, t._1.status))

              Logger.error(s"some get device failed ${noOK}")
              //context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
            }

            {
              val getDevices = responses.flatMap(_.json.validate[GetDeviceResponse].asOpt)
              if (getDevices.isEmpty) {
                Logger.error(s"invalid response: no device!")
                context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
              } else {
                val noCameraList = getDevices.flatMap(getDevice => getDevice.devices.filter(_.model != "Camera"))
                Logger.info(s"no camer List #=${noCameraList.size}")
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

  private def getSensorData(lastDataTime: Instant, device: Device, offset: Option[Int], totalCount: Option[Int]): Future[Unit] = {
    val queryParam = SensorQueryParam(queries = Seq(Sensor(device.deviceUuid, Seq(MonitorType.NORMAL_USAGE))),
      time = TimeParam(lastDataTime.getEpochSecond * 1000, Instant.now.getEpochSecond * 1000),
      offset = offset,
      maxCount = 500)
    Logger.info(Json.toJson(queryParam).toString())
    val f = WSClient.url("https://ioeapi.nextdrive.io/v1/device-data/query")
      .withHeaders(("X-ND-TOKEN", config.key))
      .post(Json.toJson(queryParam))

    f onComplete ({
      case Success(response) =>
        if (response.status != HttpStatus.SC_OK) {
          Logger.error(s"getSensorData resp code=${response.status} body=${response.json}")
        } else {
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
                  if ((totalCount.nonEmpty && totalCount != ret.offset) ||
                    (ret.totalCount.nonEmpty && ret.totalCount != ret.offset))
                    getSensorData(lastDataTime, device, ret.offset, ret.totalCount)
                  else if (ret.results.flatMap(_.generatedTime).nonEmpty) {
                    // Update last data
                    val lastEpochMs = ret.results.flatMap(_.generatedTime).max
                    val start = new DateTime(Date.from(lastDataTime))
                    val end = new DateTime(Date.from(Instant.ofEpochMilli(lastEpochMs)))

                    def upsertPower(m: String) = {
                      val ret =
                        for (data <- recordOp.getRecordListFuture(recordOp.MinCollection)(start, end.plusMinutes(1), Seq(m))) yield {
                          val powerData = data.sliding(2).flatMap(slice => {
                            val head = slice.head
                            for (headRecord <- head.mtDataList.find(mtRecord => mtRecord.mtName == MonitorType.NORMAL_USAGE);
                                 tailRecord <- slice.last.mtDataList.find(mtRecord => mtRecord.mtName == MonitorType.NORMAL_USAGE);
                                 headValue <- headRecord.value;
                                 tailValue <- tailRecord.value) yield {
                              val mtDataList = head.mtDataList.filter(mtRecord => mtRecord.mtName != MonitorType.POWER) :+
                                MtRecord(MonitorType.POWER, Some(tailValue - headValue), MonitorStatus.NormalStat)
                              RecordList(_id = head._id, mtDataList = mtDataList)
                            }
                          })
                          val powerDataList = powerData.toSeq
                          if (powerDataList.nonEmpty)
                            recordOp.upsertManyRecords(recordOp.MinCollection)(powerDataList)
                          else
                            Future.successful(Unit)
                        }
                      ret.flatMap(x => x)
                    }

                    val monitor = monitorDB.map(device.deviceUuid)
                    upsertPower(monitor._id).andThen({
                      case Success(_) =>
                        for (current <- getHourBetween(start, end))
                          dataCollectManagerOp.recalculateHourData(monitor._id, current)(monitorTypeOp.activeMtvList, monitorTypeOp)
                      case Failure(exception) =>
                        Logger.error("failed to upsert Power", exception)
                    })
                    monitor.lastDataTime = Some(end.toDate)
                    monitorDB.upsertMonitor(monitor)
                  }
                case Failure(exception) =>
                  Logger.error(s"fail to update min data", exception)
              })
            }
          )
        }

      case Failure(exception) =>
        Logger.error("fail to getSensorData", exception)
    })
    f.map(_ => Unit)
  }

  private def getSensorDataPhase(devices: Seq[Device]): Receive = {
    case GetSensorData =>
      try {
        val futures: Seq[Future[Unit]] =
          devices.map(device => {
            val monitor = monitorDB.map(device.deviceUuid)
            val lastDataTime = monitor.lastDataTime
              .getOrElse(Date.from(Instant.now().minus(5, ChronoUnit.DAYS))).toInstant
            getSensorData(lastDataTime = lastDataTime, device = device, offset = None, totalCount = None)
          })
        Future.sequence(futures).andThen({
          case Success(_) =>
            timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetSensorData))
          case Failure(exception) =>
            Logger.error("failed", exception)
            timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetSensorData))
        })
      } catch {
        case ex: Throwable =>
          Logger.error("failed to get sensor data", ex)
          timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetSensorData))
      }
  }

  override def postStop(): Unit = {
    for (timer <- timerOpt)
      timer.cancel()

    super.postStop()
  }

}
