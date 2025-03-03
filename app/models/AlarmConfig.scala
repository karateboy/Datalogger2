package models
import play.api.libs.json.{Json, OWrites, Reads}

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversions.`list asScalaBuffer`
import scala.language.implicitConversions

case class AlarmConfig(enable:Boolean, statusFilter:Seq[String])
object AlarmConfig {
  val defaultConfig: AlarmConfig = AlarmConfig(enable = false, Seq.empty[String])
  implicit val acRead: Reads[AlarmConfig] = Json.reads[AlarmConfig]
  implicit val acWrite: OWrites[AlarmConfig] = Json.writes[AlarmConfig]
  
  import org.mongodb.scala.bson._
  implicit object TransformAlarmConfig extends BsonTransformer[AlarmConfig] {
    def apply(config: AlarmConfig): BsonDocument = {
      Document("enable"->config.enable, "statusFilter"->config.statusFilter).toBsonDocument
    }
  }
  implicit def toAlarmConfig(doc:BsonDocument): Option[AlarmConfig] ={
    if(doc.get("enable").isBoolean && doc.get("statusFilter").isArray){
      val enable = doc.get("enable").asBoolean().getValue
      val bsonStatusFilter = doc.get("statusFilter").asArray().getValues
      val statusFilter = bsonStatusFilter.map { x => x.asString().getValue }
      Some(AlarmConfig(enable, statusFilter))
    }else{
      None
    }
  }
}