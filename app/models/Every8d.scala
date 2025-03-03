package models
import play.api._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class Every8d @Inject()(config: Configuration, WSClient: WSClient) {
  val logger: Logger = Logger(this.getClass)
  private val accountOpt = config.getOptional[String]("every8d.account")
  private val passwordOpt = config.getOptional[String]("every8d.password")

  for(account <- accountOpt; password <- passwordOpt)
    logger.info(s"every8d account:$account password:$password")

  def sendSMS(subject:String, content:String, mobileList:List[String]): Option[Future[WSResponse]] = {
    for(account <- accountOpt; password <- passwordOpt) yield
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