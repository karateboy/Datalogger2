package models

import play.api.Configuration

case class LoggerConfig(storeSecondData: Boolean,
                        selfMonitor: Boolean,
                        trendShowActual: Boolean,
                        db: String,
                        bypassLogin: Boolean,
                        fromEmail: String,
                        pm25HourAvgUseLastRecord: Boolean,
                        alertEmail: Boolean,
                        autoExport: Boolean,
                        exportMtList: Seq[String],
                        exportPath: String)

object LoggerConfig {
  var config: LoggerConfig = _

  def init(configuration: Configuration): Unit = {
    config = getConfig(configuration)
  }

  private def getConfig(configuration: Configuration): LoggerConfig = {
    val storeSecondData = configuration.getOptional[Boolean]("logger.storeSecondData").getOrElse(false)
    val selfMonitor = configuration.getOptional[Boolean]("logger.selfMonitor").getOrElse(true)
    val trendShowActual = configuration.getOptional[Boolean]("logger.trendShowActual").getOrElse(true)
    val db = configuration.getOptional[String]("logger.db").getOrElse("nosql")
    val bypassLogin = configuration.getOptional[Boolean]("logger.bypassLogin").getOrElse(false)
    val fromEmail = configuration.getOptional[String]("logger.fromEmail").getOrElse("AirIoT <airiot@wecc.com.tw>")
    val pm25HourAvgUseLastRecord = configuration.getOptional[Boolean]("logger.pm25HourAvgUseLastRecord").getOrElse(false)
    val alertEmail = configuration.getOptional[Boolean]("logger.alertEmail").getOrElse(false)
    val autoExport = configuration.getOptional[Boolean]("logger.autoExport").getOrElse(false)
    val exportMtList = configuration.getOptional[Seq[String]]("logger.exportMtList").getOrElse(Seq.empty)
    val exportPath = configuration.getOptional[String]("logger.exportPath").getOrElse("C:/Temp/")

    LoggerConfig(storeSecondData,
      selfMonitor = selfMonitor,
      trendShowActual = trendShowActual,
      db = db,
      bypassLogin = bypassLogin,
      fromEmail = fromEmail,
      pm25HourAvgUseLastRecord = pm25HourAvgUseLastRecord,
      alertEmail = alertEmail,
      autoExport = autoExport,
      exportMtList = exportMtList,
      exportPath = exportPath
    )
  }
}

