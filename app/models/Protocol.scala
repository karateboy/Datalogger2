package models
import models.ModelHelper._
import play.api.libs.json._


object Protocol{
  case class ProtocolParam(protocol:String, host:Option[String], comPort:Option[Int], speed:Option[Int])
  implicit val ppReader: Reads[ProtocolParam] = Json.reads[ProtocolParam]
  implicit val ppWrite: OWrites[ProtocolParam] = Json.writes[ProtocolParam]

  val tcp = "tcp"
  val serial = "serial"
  val tcpCli = "tcpCli"
  val serialCli = "serialCli"
  def map: Map[String, String] = Map(tcp->"TCP", serial->"RS232", tcpCli->"TCP CLI", serialCli->"RS232 CLI")
}