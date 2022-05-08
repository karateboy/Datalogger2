package models

import com.google.inject.ImplementedBy
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future
case class ConstantRuleID(monitor:String, monitorType:String)
case class ConstantRule(_id: ConstantRuleID, enable:Boolean, count:Int)
object ConstantRule {
  implicit val reads1 = Json.reads[ConstantRuleID]
  implicit val reads = Json.reads[ConstantRule]
  implicit val write1 = Json.writes[ConstantRuleID]
  implicit val write = Json.writes[ConstantRule]

}

trait ConstantRuleDB {

  def getRules(): Future[Seq[ConstantRule]]

  def upsert(rule: ConstantRule): Future[UpdateResult]

  def delete(_id: ConstantRuleID): Future[DeleteResult]
}
