package models

import com.google.inject.ImplementedBy
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.Future
case class EmailTarget(_id:String, topic:Seq[String])
object EmailTarget {
  implicit val reads = Json.reads[EmailTarget]
  implicit val writes = Json.writes[EmailTarget]
}

trait EmailTargetDB {

  def upsert(et: EmailTarget): Future[UpdateResult]

  def get(_id: String): Future[EmailTarget]

  def upsertMany(etList: Seq[EmailTarget]): Future[BulkWriteResult]

  def getList(): Future[Seq[EmailTarget]]

  def delete(_id: String): Future[DeleteResult]

  def deleteAll(): Future[DeleteResult]
}
