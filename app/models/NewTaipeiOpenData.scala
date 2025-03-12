package models

import models.CdxUploader.CdxMonitorType
import play.api.libs.json.{Json, OWrites}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

case class NewTaipeiOpenDataConfig(enable: Boolean, pid: String, api: String, url: String)

@javax.inject.Singleton
class NewTaipeiOpenData @Inject()(WSClient: WSClient,
                                  alarmDB: AlarmDB,
                                  monitorTypeDB: MonitorTypeDB,
                                  configuration: Configuration) extends Enumeration {
  private case class DataEntry(SiteId: String, ItemId: String, ItemEngName: String, ItemChName: String, ItemValue: Option[Double], ItemUnit: String)

  private case class PayLoad(pid: String, content: Seq[DataEntry])


  def getConfig: Option[NewTaipeiOpenDataConfig] =
    for {config <- configuration.getOptional[Configuration]("newTaipeiOpenData")
         enable <- config.getOptional[Boolean]("enable")
         pid <- config.getOptional[String]("pid")
         api <- config.getOptional[String]("api")
         url <- config.getOptional[String]("url")
         } yield
      NewTaipeiOpenDataConfig(enable, pid, api, url)


  val logger: Logger = Logger(getClass)

  def upload(recordList: RecordList, mtConfigs: Seq[CdxMonitorType]): Unit =
    for (config <- getConfig if config.enable) {

      try {
        val dataEntries = recordList.mtDataList.zipWithIndex flatMap  { pair =>
          val (mtRecord, idx) = pair
          val mt = monitorTypeDB.map(mtRecord.mtName)
          val cdxMtConfig =  mtConfigs.find(mtConfig=>mtConfig.mt == mtRecord.mtName).getOrElse(CdxMonitorType(mtRecord.mtName, mtRecord.mtName, None, None))

          cdxMtConfig match {
            case CdxMonitorType(_, _, Some(min), Some(max))=>
              for(v<-mtRecord.value if v>= min && v <= max) yield
                DataEntry(config.pid, "%02d".format(idx + 1), mt._id, mt.desp, mtRecord.value, mt.unit)

            case CdxMonitorType(_, _, None, Some(max))=>
              for(v<-mtRecord.value if v <= max) yield
                DataEntry(config.pid, "%02d".format(idx + 1), mt._id, mt.desp, mtRecord.value, mt.unit)

            case CdxMonitorType(_, _, Some(min), None)=>
              for(v<-mtRecord.value if v>= min) yield
                DataEntry(config.pid, "%02d".format(idx + 1), mt._id, mt.desp, mtRecord.value, mt.unit)

            case _=>
              Some(DataEntry(config.pid, "%02d".format(idx + 1), mt._id, mt.desp, mtRecord.value, mt.unit))
          }
        }
        val payload = PayLoad(config.pid, dataEntries)

        implicit val w1: OWrites[DataEntry] = Json.writes[DataEntry]
        implicit val w2: OWrites[PayLoad] = Json.writes[PayLoad]
        val postUrl = s"${config.url}api/v1/open.dataset.content.update"
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
