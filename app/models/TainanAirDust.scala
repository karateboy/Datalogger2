package models

import play.api.libs.ws.WSClient

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class TainanAirDustData(dataType: String,
                             bid: String,
                             lat: String,
                             lon: String,
                             devID: String,
                             detectTimestamp: String,
                             pm25: Option[String],
                             pm10: Option[String])

object TainanAirDustData {

  import play.api.libs.json.Json

  implicit val reads = Json.reads[TainanAirDustData]
  implicit val writes = Json.writes[TainanAirDustData]

  def apply(controlNo: String, deviceId: String, lat: Double, lon: Double, pm25: Option[Double], pm10: Option[Double]): TainanAirDustData = {
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    TainanAirDustData("airDustNotify",
      controlNo,
      s"N$lat",
      s"E$lon",
      deviceId,
      now,
      pm25.map(_.toString),
      pm10.map(_.toString))
  }
}

@Singleton
class TainanAirDust @Inject()(WSClient: WSClient) {
  val logger = play.api.Logger(getClass)
  import play.api.libs.json.Json

  def sendAirDustData(data: TainanAirDustData): Future[Unit] = {
    val url = "http://118.163.139.76:8068/api/airDust/notify"
    WSClient.url(url).post(Json.toJson(data)).map { response =>
      if (response.status != 200) {
        logger.error(s"Failed to send air dust data to $url, status:${response.status}, body:${response.body}")
      }
    }
  }
}
