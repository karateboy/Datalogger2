package models

import play.api.Configuration

import javax.inject.{Inject, Singleton}

case class LoggerConfig(storeSecondData:Boolean, selfMonitor:Boolean,
                        trendShowActual:Boolean, db:String, bypassLogin:Boolean)

@Singleton
class LoggerConfigOp @Inject()(configuration: Configuration){
  def getConfig() : LoggerConfig = {
    val storeSecondData = configuration.getBoolean("logger.storeSecondData").getOrElse(false)
    val selfMonitor = configuration.getBoolean("logger.selfMonitor").getOrElse(true)
    val trendShowActual = configuration.getBoolean("logger.trendShowActual").getOrElse(true)
    val db = configuration.getString("logger.db").getOrElse("nosql")
    val bypassLogin = configuration.getBoolean("logger.bypassLogin").getOrElse(false)
    LoggerConfig(storeSecondData, selfMonitor = selfMonitor, trendShowActual = trendShowActual,
      db = db, bypassLogin = bypassLogin)
  }
}

