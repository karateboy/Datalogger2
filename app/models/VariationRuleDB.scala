package models

import com.google.inject.ImplementedBy
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future
case class VariationRuleID(monitor:String, monitorType:String)
case class VariationRule(_id: VariationRuleID, enable:Boolean, abs:Double)
object VariationRule {
  implicit val reads1 = Json.reads[VariationRuleID]
  implicit val reads = Json.reads[VariationRule]
  implicit val write1 = Json.writes[VariationRuleID]
  implicit val write = Json.writes[VariationRule]
}

trait VariationRuleDB {

  def getRules(): Future[Seq[VariationRule]]

  def upsert(rule: VariationRule): Future[UpdateResult]

  def delete(_id: VariationRuleID): Future[DeleteResult]
}
