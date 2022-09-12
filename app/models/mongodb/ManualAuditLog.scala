package models.mongodb

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonString
import org.mongodb.scala.result.UpdateResult

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class ManualAuditLogOp @Inject()(mongodb: MongoDB) extends ManualAuditLogDB {
  lazy private val collectionName = "auditLogs"
  lazy private val collection = mongodb.database.getCollection(collectionName)
  import org.mongodb.scala.model.Filters._
  override def upsertLog(log: ManualAuditLog):Future[UpdateResult] = {
    import org.mongodb.scala.bson.BsonDateTime
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(and(equal("dataTime", log.dataTime: BsonDateTime), equal("mt", log.mt)),
      toDocument(log), ReplaceOptions().upsert(true)).toFuture()

    f.onFailure(errorHandler)
    f
  }

  private def toDocument(al: ManualAuditLog) = {
    import org.mongodb.scala.bson._
    Document("dataTime" -> (al.dataTime: BsonDateTime), "mt" -> al.mt,
      "modifiedTime" -> (al.modifiedTime: BsonDateTime), "operator" -> al.operator, "changedStatus" -> al.changedStatus, "reason" -> al.reason)
  }

  init

  import org.mongodb.scala.model.Filters._

  override def queryLog2(startTime: DateTime, endTime: DateTime): Future[Seq[ManualAuditLog2]] = {
    import org.mongodb.scala.bson.BsonDateTime
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val future = collection.find(and(gte("dataTime", startTime: BsonDateTime), lt("dataTime", endTime: BsonDateTime))).sort(ascending("dataTime")).toFuture()
    for (f <- future) yield {
      f.map {
        toAuditLog2
      }
    }
  }

  private def toAuditLog2(doc: Document) = {
    val dataTime = new DateTime(doc.get("dataTime").get.asDateTime().getValue)
    val mt = (doc.get("mt").get.asString().getValue)
    val modifiedTime = new DateTime(doc.get("modifiedTime").get.asDateTime().getValue)
    val operator = doc.get("operator").get.asString().getValue
    val changedStatus = doc.get("changedStatus").get.asString().getValue
    val reason = doc.get("reason").get.asString().getValue
    val monitor = doc.get("monitor").getOrElse(BsonString(Monitor.activeId)).asString().getValue

    ManualAuditLog2(dataTime = dataTime.getMillis, mt = mt, modifiedTime = modifiedTime.getMillis, operator = operator,
      changedStatus = changedStatus, reason = reason, monitor = monitor)
  }

  private def init() {
    import org.mongodb.scala.model.Indexes._
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(ascending("dataTime", "mt"))
        })
      }
    }
  }
}