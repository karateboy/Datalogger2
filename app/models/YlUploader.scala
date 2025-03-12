package models

import play.api.{Configuration, Logger}
import play.api.libs.json.{Json, OWrites}
import play.api.libs.ws.WSClient

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

case class YlUploaderConfig(upload:Boolean, deviceId: String, timeCode: String, url:String, token:String)
object YlUploader {
  val logger: Logger = Logger(this.getClass)
  case class UploadData(DataInfo: Seq[ItemData])

  case class ItemData(lon: Double, lat: Double, id: String,
                      TimeCode: String, time: String, deviceId: String, value: String, InstrumentCode: String)

  implicit val w1: OWrites[ItemData] = Json.writes[ItemData]
  implicit val w2: OWrites[UploadData] = Json.writes[UploadData]

  def getConfig(rootConfig: play.api.Configuration): Option[YlUploaderConfig] =
    for(config <- rootConfig.getOptional[Configuration]("ylUploader")) yield {
      val upload = config.get[Boolean]("upload")
      val deviceId = config.get[String]("deviceId")
      val timeCode = config.get[String]("timeCode")
      val url = config.get[String]("url")
      val token = config.get[String]("token")
      YlUploaderConfig(upload, deviceId, timeCode, url, token)
    }

  def upload(ws: WSClient)(recordList: RecordList, monitor: Monitor, config:YlUploaderConfig): Unit = {
    val itemData = recordList.mtDataList.map(mtData => {
      val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
      ItemData(lon = monitor.lng.getOrElse(120.1826),
        lat = monitor.lat.getOrElse(23.7608),
        deviceId = config.deviceId,
        id = mtData.mtName,
        TimeCode = config.timeCode,
        time = formatter.format(recordList._id.time.toInstant),
        value = mtData.value.map(_.toString).getOrElse(""),
        InstrumentCode = mtData.status)
    })

    if(config.upload) {
      val url = config.url
      logger.debug(s"upload to $url")
      logger.debug(Json.toJson(UploadData(itemData)).toString())
      val f = ws.url(url)
        .withHttpHeaders(("RequiredValidateToken", config.token), ("method", "UploadMonitorCarData"))
        .post(Json.toJson(UploadData(itemData)))

      f.onComplete({
        case Success(resp) =>
          if (resp.status == 200) {
            logger.info(s"Success upload ${recordList._id.time}")
            logger.debug(s"${resp.json.toString()}")
          } else {
            logger.error(s"Failed to upload ${resp.status} ${resp.json.toString()}")
          }
        case Failure(exception) =>
          logger.error(s"failed to upload ${recordList._id.time}", exception)
      })
    }
  }
}
