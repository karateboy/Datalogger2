package models

import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future

case class SpikeRuleID(monitor:String, monitorType:String)
case class SpikeRule(_id: SpikeRuleID, enable:Boolean, abs: Double)
object SpikeRule {
  implicit val reads1 = Json.reads[SpikeRuleID]
  implicit val reads = Json.reads[SpikeRule]
  implicit val write1 = Json.writes[SpikeRuleID]
  implicit val write = Json.writes[SpikeRule]
}

trait SpikeRuleDB {

  def getRules(): Future[Seq[SpikeRule]]

  def upsert(rule: SpikeRule): Future[UpdateResult]

  def delete(_id: SpikeRuleID): Future[DeleteResult]
}
