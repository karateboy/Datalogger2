package models
import play.api.libs.json._
import models.ModelHelper._


object Protocol{
  case class ProtocolParam(protocol:String, host:Option[String], comPort:Option[Int], speed:Option[Int])
  implicit val ppReader = Json.reads[ProtocolParam]
  implicit val ppWrite = Json.writes[ProtocolParam]

  val tcp = "tcp"
  val serial = "serial"
  val tcpCli = "tcpCli"
  def map = Map(tcp->"TCP", serial->"RS232", tcpCli->"TCP CLI")
}