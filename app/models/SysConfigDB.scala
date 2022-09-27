package models

import models.CdxUploader.{CdxConfig, CdxMonitorType}
import org.mongodb.scala.result.UpdateResult

import java.time.Instant
import java.util.Date
import scala.concurrent.Future

trait SysConfigDB {

  val Logo = "Logo"
  val SpectrumLastParseTime = "SpectrumLastParseTime"
  val WeatherLastParseTime = "WeatherLastParseTime"
  val WeatherSkipLine = "WeatherSkipLine"
  val EffectiveRatio = "EffectiveRatio"
  val AlertEmailTaget = "AlertEmailTarget"
  val CDX_CONFIG = "CdxConfig"
  val CDX_MONITOR_TYPES = "CdxMonitorTypes"
  val ACTIVE_MONITOR_ID = "ActiveMonitorId"
  val ALARM_UPGRADED = "AlarmUpgraded"
  val CALIBRATION_UPGRADED = "CalibrationUpgraded"
  val INSTRUMENT_STATUS_UPGRADED = "InstrumentStatusUpgraded"
  val EPA_LAST_RECORD_TIME = "EPA_Last_Record_Time"

  def getSpectrumLastParseTime(): Future[Instant]

  def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult]

  def getWeatherLastParseTime(): Future[Instant]

  def setWeatherLastParseTime(dt: Instant): Future[UpdateResult]

  def getWeatherSkipLine(): Future[Int]

  def setWeatherSkipLine(v: Int): Future[UpdateResult]

  def getEffectiveRatio(): Future[Double]

  def setEffectiveRation(v: Double): Future[UpdateResult]

  def getAlertEmailTarget(): Future[Seq[String]]

  def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult]

  def getCdxConfig(): Future[CdxConfig]

  def setCdxConfig(config:CdxConfig): Future[UpdateResult]

  def getCdxMonitorTypes() : Future[Seq[CdxMonitorType]]

  def setCdxMonitorTypes(monitorTypes: Seq[CdxMonitorType]) : Future[UpdateResult]

  def getActiveMonitorId() : Future[String]

  def setActiveMonitorId(id:String) : Future[UpdateResult]

  def getAlarmUpgraded() : Future[Boolean]
  def setAlarmUpgraded(v:Boolean) : Future[UpdateResult]

  def getCalibrationUpgraded(): Future[Boolean]

  def setCalibrationUpgraded(v: Boolean): Future[UpdateResult]

  def getInstrumentStatusUpgraded(): Future[Boolean]

  def setInstrumentStatusUpgraded(v: Boolean): Future[UpdateResult]

  def getEpaLastRecordTime() : Future[Date]

  def setEpaLastRecordTime(v:Date) : Future[UpdateResult]
}
