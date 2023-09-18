package models
import play.api._
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.libs.json._

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class Every8d @Inject()(config: Configuration, WSClient: WSClient) {
  private val account = config.getString("every8d.account").get
  val password: String = config.getString("every8d.password").get
  Logger.info(s"every8d account:$account password:$password")

  def sendSMS(subject:String, content:String, mobileList:List[String]): Future[WSResponse] = {
      WSClient.url("https://api.e8d.tw/API21/HTTP/sendSMS.ashx")
        .post(Map(
          "UID" -> Seq(account),
          "PWD" -> Seq(password),
          "SB" -> Seq(subject),
          "MSG" -> Seq(content),
          "DEST" -> Seq(mobileList.mkString(",")),
          "ST" -> Seq("")))
  }
}