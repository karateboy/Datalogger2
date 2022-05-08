package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{MonitorStatus, MonitorStatusDB, StatusType}
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject.{Inject, Singleton}

@Singleton
class MonitorStatusOp @Inject()(mongodb: MongoDB) extends MonitorStatusDB {
  private val collectionName = "status"
  private val collection = mongodb.database.getCollection(collectionName)

  import MonitorStatus._
  import org.mongodb.scala._

  private def toDocument(ms: MonitorStatus) = {
    Document(Json.toJson(ms).toString())
  }

  private def toMonitorStatus(doc: Document) = {
    Json.parse(doc.toJson()).validate[MonitorStatus].asOpt.get
  }

  private def init() {
    def insertDefaultStatus {
      val f = collection.insertMany(defaultStatus.map {
        toDocument
      }).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _ =>
          refreshMap
      })
    }

    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            insertDefaultStatus
        })
      }
    }
  }

  init

  override def msList: Seq[MonitorStatus] = {
    val f = collection.find().toFuture()
    f.onFailure(errorHandler)
    waitReadyResult(f).map {
      toMonitorStatus
    }
  }
}
