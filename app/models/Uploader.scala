package models

import org.apache.http.HttpStatus
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import java.time.{LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Uploader {
  case class UploadData(DataInfo: Seq[ItemData])

  case class ItemData(lon: Double, lat: Double, id: String,
                      TimeCode: String, time: String, deviceId: String, value: String, InstrumentCode: String)

  implicit val w1 = Json.writes[ItemData]
  implicit val w2 = Json.writes[UploadData]

  def upload(ws: WSClient)(recordList: RecordList, monitor: Monitor): Unit = {
    val itemData = recordList.mtDataList.map(mtData => {
      val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
      ItemData(lon = monitor.lng.getOrElse(120.1826),
        lat = monitor.lat.getOrElse(23.7608),
        deviceId = "Vans03",
        id = mtData.mtName,
        TimeCode = "001",
        time = formatter.format(recordList._id.time.toInstant),
        value = mtData.value.map(_.toString).getOrElse(""),
        InstrumentCode = mtData.status)
    })

    if(!LoggerConfig.config.upload)
      return

    val url = "https://www.yesylepb.com.tw/WebService/MonitorCarData.ashx"
    Logger.debug(s"upload to $url")
    Logger.debug(Json.toJson(UploadData(itemData)).toString())
    val f = ws.url("https://www.yesylepb.com.tw/WebService/MonitorCarData.ashx")
      .withHeaders(("RequiredValidateToken", "OwEsu5KJAAAMPdPpZfYN"), ("method", "UploadMonitorCarData"))
      .post(Json.toJson(UploadData(itemData)))

    f.onComplete({
      case Success(resp) =>
        if (resp.status == HttpStatus.SC_OK) {
          Logger.info(s"Success upload ${recordList._id.time}")
          Logger.debug(s"${resp.json.toString()}")
        } else {
          Logger.error(s"Failed to upload ${resp.status} ${resp.json.toString()}")
        }
      case Failure(exception) =>
        Logger.error(s"failed to upload ${recordList._id.time}", exception)
    })
  }
}
