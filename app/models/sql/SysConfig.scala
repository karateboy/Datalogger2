package models.sql

import models.{LogoImage, SysConfigDB}
import org.mongodb.scala.result.UpdateResult

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class SysConfig @Inject()() extends SysConfigDB{
  override def getLogo: Future[LogoImage] = ???

  override def setLogo(logo: LogoImage): Future[UpdateResult] = ???

  override def getSpectrumLastParseTime(): Future[Instant] = ???

  override def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult] = ???

  override def getWeatherLastParseTime(): Future[Instant] = ???

  override def setWeatherLastParseTime(dt: Instant): Future[UpdateResult] = ???

  override def getWeatherSkipLine(): Future[Int] = ???

  override def setWeatherSkipLine(v: Int): Future[UpdateResult] = ???

  override def getEffectiveRatio(): Future[Double] = ???

  override def setEffectiveRation(v: Double): Future[UpdateResult] = ???

  override def getAlertEmailTarget(): Future[Seq[String]] = ???

  override def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult] = ???
}
