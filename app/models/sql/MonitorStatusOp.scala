package models.sql

import models.{MonitorStatus, MonitorStatusDB}

import javax.inject.{Inject, Singleton}
@Singleton
class MonitorStatusOp @Inject()() extends MonitorStatusDB{
  override def map(key: String): MonitorStatus = ???

  override def getExplainStr(tag: String): String = ???
}
