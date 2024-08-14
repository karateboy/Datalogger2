package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{MonitorStatus, MonitorStatusDB}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class MonitorStatusOp @Inject()(mongodb: MongoDB) extends MonitorStatusDB {

  override def msList: Seq[MonitorStatus] = defaultStatus
}
