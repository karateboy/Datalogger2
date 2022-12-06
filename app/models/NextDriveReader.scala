package models

import akka.actor._
import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.{getHourBetween, waitReadyResult}
import org.apache.http.HttpStatus
import play.api._
import play.api.libs.json.{Json, OWrites, Reads}
import play.api.libs.ws.WSClient

import java.io.File
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES}
import scala.io.{Codec, Source}
import scala.util.Success

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

  case class SensorQueryParam(query: Seq[Sensor], time: TimeParam, maxCount: Int)

  implicit val timeParamWrite: OWrites[TimeParam] = Json.writes[TimeParam]
  implicit val sensorWrite: OWrites[Sensor] = Json.writes[Sensor]
  implicit val sensorQueryParamWrite: OWrites[SensorQueryParam] = Json.writes[SensorQueryParam]

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

  case class Device(deviceUuid:String, model:String, name:String, onlineStatus:String)
  implicit val deviceReads: Reads[Device] = Json.reads[Device]
  def getDevicePhase: Receive = {
    case GetDevice =>
      try {
        val f = WSClient.url(s"https://ioeapi.nextdrive.io/v1/gateways/${config.productID}/devices")
          .withHeaders(("X-ND-TOKEN", config.key))
          .get()
        for(resp <-f if resp.status == HttpStatus.SC_OK){
          for(deviceList <- resp.json.validate[Seq[Device]].asOpt){
            val noCameraList = deviceList.filter(dev=>dev.model != "Camera")
            noCameraList.foreach(device=>monitorDB.ensure(Monitor(device.deviceUuid, device.name)))
            context become getSensorDataPhase(noCameraList)
            self ! GetSensorData
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("fail to get devices ", ex)
          context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetDevice)
      }
  }


  def getSensorDataPhase(devices: Seq[Device]): Receive = {
    case GetSensorData =>
      try{
        val dtF = sysConfig.getLastDataTime
        for(dt<-dtF){
          val queryParam = SensorQueryParam(query = devices.map(device=>Sensor(device.deviceUuid, mtList)),
            time = TimeParam(dt.getEpochSecond*1000, Instant.now.getEpochSecond * 1000),
            maxCount = 500)
          val f = WSClient.url("https://ioeapi.nextdrive.io/v1/device-data/query")
            .withHeaders(("X-ND-TOKEN", config.key))
            .post(Json.toJson(queryParam))
        }

      }catch{
        case ex:Throwable=>
          Logger.error("failed to get sensor data", ex)
      } finally {
        timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, GetSensorData))
      }
  }

  def fileParser(file: File): Unit = {
    import scala.collection.mutable.ListBuffer
    for (mt <- mtList)
      monitorTypeOp.ensure(mt)

    Logger.debug(s"parsing ${file.getAbsolutePath}")
    val skipLines = waitReadyResult(sysConfig.getWeatherSkipLine())

    var processedLine = 0
    val src = Source.fromFile(file)(Codec.UTF8)
    try {
      val lines = src.getLines().drop(4 + skipLines)
      var dataBegin = LocalDateTime.MAX
      var dataEnd = LocalDateTime.MIN
      val docList = ListBuffer.empty[RecordList]
      for (line <- lines if processedLine < 2000) {
        try {
          val token: Array[String] = line.split(",")
          if (token.length < mtList.length) {
            throw new Exception("unexpected file length")
          }
          val dt: LocalDateTime = try {
            LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy-MM-dd HH:mm:ss\""))
          } catch {
            case _: DateTimeParseException =>
              LocalDateTime.parse(token(0), DateTimeFormatter.ofPattern("\"yyyy/MM/dd HH:mm:ss\""))
          }

          if (dt.isBefore(dataBegin))
            dataBegin = dt

          if (dt.isAfter(dataEnd))
            dataEnd = dt

          val mtRecordOpts: Seq[Option[MtRecord]] =
            for ((mt, idx) <- mtList.zipWithIndex) yield {
              try {
                val value = token(idx + 2).toDouble
                Some(MtRecord(mt, Some(value), MonitorStatus.NormalStat))
              } catch {
                case _: Exception =>
                  None
              }
            }

          docList.append(RecordList(time = Date.from(dt.atZone(ZoneId.systemDefault()).toInstant), monitor = Monitor.activeId,
            mtDataList = mtRecordOpts.flatten))
        } catch {
          case ex: Throwable =>
            Logger.warn("skip unknown line", ex)
        } finally {
          processedLine = processedLine + 1
        }
      }

      if (docList.nonEmpty) {
        Logger.debug(s"update ${docList.head}")
        sysConfig.setWeatherSkipLine(skipLines + processedLine)
        recordOp.upsertManyRecords(recordOp.MinCollection)(docList)

        val start = new DateTime(Date.from(dataBegin.atZone(ZoneId.systemDefault()).toInstant))
        val end = new DateTime(Date.from(dataEnd.atZone(ZoneId.systemDefault()).toInstant))

        for (current <- getHourBetween(start, end))
          dataCollectManagerOp.recalculateHourData(Monitor.activeId, current)(monitorTypeOp.activeMtvList, monitorTypeOp)
      }
    } finally {
      src.close()
    }
  }

  override def postStop(): Unit = {
    for (timer <- timerOpt)
      timer.cancel()

    super.postStop()
  }

}
