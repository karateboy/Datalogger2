package models

import models.CdxUploader.CdxMonitorType
import play.api.libs.json.{Json, OWrites}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

case class NewTaipeiOpenDataConfig(enable: Boolean, pid: String, site:String, api: String, url: String)

@javax.inject.Singleton
class NewTaipeiOpenData @Inject()(WSClient: WSClient,
                                  alarmDB: AlarmDB,
                                  monitorTypeDB: MonitorTypeDB,
                                  configuration: Configuration) extends Enumeration {
  private case class DataEntry(siteid: String,
                               itemid: String,
                               pollutantitem: String,
                               itemchname: String,
                               itemvalue: Option[Double],
                               itemunit: String,
                               datetime: String)

  private case class PayLoad(pid: String, content: Seq[DataEntry])


  def getConfig: Option[NewTaipeiOpenDataConfig] =
    for {config <- configuration.getOptional[Configuration]("newTaipeiOpenData")
         enable <- config.getOptional[Boolean]("enable")
         pid <- config.getOptional[String]("pid")
         site <- config.getOptional[String]("site")
         api <- config.getOptional[String]("api")
         url <- config.getOptional[String]("url")
         } yield
      NewTaipeiOpenDataConfig(enable, pid, site, api, url)


  val logger: Logger = Logger(getClass)

  val pollutantMap: Map[String, String] = Map(
    MonitorType.PRESS ->"Pressure",
    MonitorType.PM25 -> "PM2.5",
    MonitorType.PM10 -> "PM10",
    MonitorType.NO2 -> "NO2",
    MonitorType.HUMID -> "RH",
    MonitorType.WIN_SPEED -> "Wind Speed",
    MonitorType.CH4 -> "CH4",
    MonitorType.NMHC -> "NMHC",
    MonitorType.NOX -> "NOX",
    MonitorType.RAIN -> "RAIN",
    MonitorType.SO2 -> "SO2",
    MonitorType.THC -> "THC",
    MonitorType.CO -> "CO",
    MonitorType.O3 -> "O3",
    MonitorType.NO -> "NO",
    MonitorType.TEMP -> "Ambient Temp",
    MonitorType.WIN_DIRECTION -> "Wind Direction",
  )

  def upload(recordList: RecordList, mtConfigs: Seq[CdxMonitorType], dryRun: Boolean = false): Unit =
    for (config <- getConfig if config.enable) {

      try {
        val localDateTime = recordList._id.time.toInstant
          .atZone(ZoneId.systemDefault())
          .toLocalDateTime
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        val dateTimeString = localDateTime.format(formatter)
        val dataEntries = recordList.mtDataList.zipWithIndex flatMap { pair =>
          val (mtRecord, idx) = pair
          val mt = monitorTypeDB.map(mtRecord.mtName)
          val pollutantName = pollutantMap.getOrElse(mt._id, mtRecord.mtName)
          val cdxMtConfig = mtConfigs.find(mtConfig => mtConfig.mt == mtRecord.mtName).getOrElse(CdxMonitorType(mtRecord.mtName, mtRecord.mtName, None, None))

          cdxMtConfig match {
            case CdxMonitorType(_, _, Some(min), Some(max)) =>
              for (v <- mtRecord.value if v >= min && v <= max) yield
                DataEntry(config.site, "%02d".format(idx + 1), pollutantName, mt.desp, mtRecord.value, mt.unit, dateTimeString)

            case CdxMonitorType(_, _, None, Some(max)) =>
              for (v <- mtRecord.value if v <= max) yield
                DataEntry(config.site, "%02d".format(idx + 1), pollutantName, mt.desp, mtRecord.value, mt.unit, dateTimeString)

            case CdxMonitorType(_, _, Some(min), None) =>
              for (v <- mtRecord.value if v >= min) yield
                DataEntry(config.site, "%02d".format(idx + 1), pollutantName, mt.desp, mtRecord.value, mt.unit, dateTimeString)

            case _ =>
              Some(DataEntry(config.site, "%02d".format(idx + 1), pollutantName, mt.desp, mtRecord.value, mt.unit, dateTimeString))
          }
        }
        val payload = PayLoad(config.pid, dataEntries)

        implicit val w1: OWrites[DataEntry] = Json.writes[DataEntry]
        implicit val w2: OWrites[PayLoad] = Json.writes[PayLoad]
        val postUrl = s"${config.url}api/v1/open.dataset.content.update"

        if(dryRun) {
          logger.info(s"Dry run: would post to $postUrl with payload: ${Json.toJson(payload)}")
          return
        }

        val f = WSClient.url(postUrl)
          .addHttpHeaders("Content-Type" -> "application/json", "Authorization" -> config.api)
          .post(Json.toJson(payload))
        f.onComplete {
          case Success(response) =>
            if (response.status == 200) {
              alarmDB.log(alarmDB.src(), Alarm.Level.INFO, s"新北OpenData上傳${recordList._id.time.toString}小時值成功")
            } else {
              alarmDB.log(alarmDB.src(), Alarm.Level.ERR, s"新北OpenData上傳${recordList._id.time.toString}小時值失敗 status=${response.status} 錯誤訊息 ${response.body}")
            }
          case Failure(ex) =>
            throw ex
        }
      } catch {
        case ex: Throwable =>
          logger.error("新北OpenData上傳錯誤", ex)
          alarmDB.log(alarmDB.src(), Alarm.Level.ERR, s"新北OpenData上傳${recordList._id.time.toString}小時值失敗 錯誤訊息 ${ex.getMessage}")
      }
    }
}
