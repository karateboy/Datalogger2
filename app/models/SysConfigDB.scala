package models

import models.CdxUploader.{CdxConfig, CdxMonitorType}
import org.mongodb.scala.result.UpdateResult

import java.time.Instant
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

  // def getLogo: Future[LogoImage]

  // def setLogo(logo: LogoImage): Future[UpdateResult]

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
}
