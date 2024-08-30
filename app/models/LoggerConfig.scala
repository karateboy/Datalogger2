package models

import play.api.Configuration

import javax.inject.{Inject, Singleton}

case class LoggerConfig(storeSecondData: Boolean,
                        selfMonitor: Boolean,
                        trendShowActual: Boolean,
                        db: String,
                        bypassLogin: Boolean,
                        upload: Boolean,
                        fromEmail:String,
                        pm25HourAvgUseLastRecord:Boolean,
                        alertEmail:Boolean)

object LoggerConfig {
  var config: LoggerConfig = _

  def init(configuration: Configuration): Unit = {
    config = getConfig(configuration)
  }

  private def getConfig(configuration: Configuration): LoggerConfig = {
    val storeSecondData = configuration.getBoolean("logger.storeSecondData").getOrElse(false)
    val selfMonitor = configuration.getBoolean("logger.selfMonitor").getOrElse(true)
    val trendShowActual = configuration.getBoolean("logger.trendShowActual").getOrElse(true)
    val db = configuration.getString("logger.db").getOrElse("nosql")
    val bypassLogin = configuration.getBoolean("logger.bypassLogin").getOrElse(false)
    val upload = configuration.getBoolean("logger.upload").getOrElse(false)
    val fromEmail = configuration.getString("logger.fromEmail").getOrElse("AirIoT <airiot@wecc.com.tw>")
    val pm25HourAvgUseLastRecord = configuration.getBoolean("logger.pm25HourAvgUseLastRecord").getOrElse(false)
    val alertEmail = configuration.getBoolean("logger.alertEmail").getOrElse(false)
    LoggerConfig(storeSecondData, selfMonitor = selfMonitor, trendShowActual = trendShowActual,
      db = db, bypassLogin = bypassLogin, fromEmail = fromEmail,
      pm25HourAvgUseLastRecord = pm25HourAvgUseLastRecord,
      upload = upload,
      alertEmail = alertEmail)
  }
}

