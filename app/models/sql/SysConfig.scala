package models.sql

import com.mongodb.client.result.UpdateResult
import models.CdxUploader.{CdxConfig, CdxMonitorType}
import models.{CdxUploader, Monitor, SysConfigDB}
import play.api.libs.json.Json
import scalikejdbc._

import java.sql.Blob
import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SysConfig @Inject()(sqlServer: SqlServer) extends SysConfigDB {
  val tabName = "sysConfig"

  override def getSpectrumLastParseTime(): Future[Instant] = Future {
    val valueOpt = get(SpectrumLastParseTime)
    val ret =
      for (value <- valueOpt) yield
        Instant.parse(value.v)
    ret.getOrElse(Instant.EPOCH)
  }

  override def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult] = Future {
    val ret = set(SpectrumLastParseTime, dt.toString)
    UpdateResult.acknowledged(ret, ret, null)
  }

  init()

  override def getWeatherLastParseTime(): Future[Instant] = Future {
    val valueOpt = get(WeatherLastParseTime)
    val ret =
      for (value <- valueOpt) yield
        Instant.parse(value.v)
    ret.getOrElse(Instant.EPOCH)
  }

  override def setWeatherLastParseTime(dt: Instant): Future[UpdateResult] = Future {
    val ret = set(WeatherLastParseTime, dt.toString)
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getWeatherSkipLine(): Future[Int] = Future {
    val valueOpt = get(WeatherSkipLine)
    val ret =
      for (value <- valueOpt) yield
        value.v.toInt
    ret.getOrElse(0)
  }

  override def setWeatherSkipLine(v: Int): Future[UpdateResult] = Future {
    val ret = set(WeatherSkipLine, v.toString)
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getEffectiveRatio(): Future[Double] = Future {
    val valueOpt = get(EffectiveRatio)
    val ret =
      for (value <- valueOpt) yield
        value.v.toDouble
    ret.getOrElse(0.75)
  }

  override def setEffectiveRation(v: Double): Future[UpdateResult] = Future {
    val ret = set(EffectiveRatio, v.toString)
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getAlertEmailTarget(): Future[Seq[String]] = Future {
    val valueOpt = get(AlertEmailTaget)
    val ret =
      for (value <- valueOpt) yield
        value.v.split(",").filter(_.nonEmpty).toSeq
    ret.getOrElse(Seq.empty[String])
  }

  override def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult] = Future {
    val ret = set(AlertEmailTaget, emails.mkString(","))
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getCdxConfig(): Future[CdxUploader.CdxConfig] = Future {
    val valueOpt = get(CDX_CONFIG)
    val ret =
      for (value <- valueOpt) yield
        Json.parse(value.v).validate[CdxConfig].asOpt.getOrElse(CdxUploader.defaultConfig)
    ret.getOrElse(CdxUploader.defaultConfig)
  }

  private def get(key: String): Option[Value] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         Select *
         From [dbo].[sysConfig]
         Where [id] = $key
         """.map(mapper).first().apply()
  }

  def mapper(rs: WrappedResultSet): Value =
    Value(rs.string("value"), rs.blobOpt("blob"))

  override def setCdxConfig(config: CdxConfig): Future[UpdateResult] = Future {
    val ret = set(CDX_CONFIG, Json.toJson(config).toString())
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getCdxMonitorTypes(): Future[Seq[CdxUploader.CdxMonitorType]] = Future {
    val valueOpt = get(CDX_MONITOR_TYPES)
    val ret =
      for (value <- valueOpt) yield
        Json.parse(value.v).validate[Seq[CdxMonitorType]].asOpt.getOrElse(CdxUploader.defaultMonitorTypes)

    ret.getOrElse(CdxUploader.defaultMonitorTypes)
  }

  override def setCdxMonitorTypes(monitorTypes: Seq[CdxMonitorType]): Future[UpdateResult] = Future {
    val ret = set(CDX_MONITOR_TYPES, Json.toJson(monitorTypes).toString())
    UpdateResult.acknowledged(ret, ret, null)
  }

  private def set(key: String, value: String): Int = {
    implicit val session: DBSession = AutoSession
    sql"""
          Update [dbo].[sysConfig]
          Set [value] = $value
          Where [id] = $key
          IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[sysConfig]
            ([id], [value])
            VALUES
              ($key, $value)
            END
         """.update().apply()
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
        CREATE TABLE [dbo].[sysConfig](
	      [id] [nvarchar](50) NOT NULL,
	      [value] [nvarchar](1024) NOT NULL,
	      [blob] [varbinary](max) NULL,
      CONSTRAINT [PK_sysConfig] PRIMARY KEY CLUSTERED
      (
	      [id] ASC
      )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
      ) ON [PRIMARY] TEXTIMAGE_ON [PRIMARY]
           """.execute().apply()
    }
  }

  case class Value(v: String, blob: Option[Blob])

  override def getActiveMonitorId(): Future[String] = Future {
    val valueOpt = get(ACTIVE_MONITOR_ID)
    val ret =
      for (value <- valueOpt) yield
        value.v
    ret.getOrElse(Monitor.activeId)
  }

  private def getBoolean(key:String, defaultValue:Boolean): Future[Boolean] = Future {
    val valueOpt = get(key)
    val ret: Option[Boolean] =
      for (value <- valueOpt) yield value.v.toBoolean
    ret.getOrElse(defaultValue)
  }

  override def setActiveMonitorId(id: String): Future[UpdateResult] = Future {
    val ret = set(ACTIVE_MONITOR_ID, id)
    UpdateResult.acknowledged(ret, ret, null)
  }


  private def setBoolean(key:String)(v:Boolean): Future[UpdateResult] = Future {
    val ret = set(key, v.toString())
    UpdateResult.acknowledged(ret, ret, null)
  }

  private def getDate(key: String, defaultValue: Date): Future[Date] = Future {
    val valueOpt = get(key)
    val ret: Option[Date] =
      for (value <- valueOpt) yield Date.from(Instant.ofEpochMilli(value.v.toLong))
    ret.getOrElse(defaultValue)
  }

  private def setDate(key: String)(v: Date): Future[UpdateResult] = Future {
    val ret = set(key, v.getTime.toString)
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getAlarmUpgraded(): Future[Boolean] = getBoolean(ALARM_UPGRADED, false)

  override def setAlarmUpgraded(v: Boolean): Future[UpdateResult] = setBoolean(ALARM_UPGRADED)(v)

  override def getCalibrationUpgraded(): Future[Boolean] = getBoolean(CALIBRATION_UPGRADED, false)

  override def setCalibrationUpgraded(v: Boolean): Future[UpdateResult] = setBoolean(CALIBRATION_UPGRADED)(v)

  override def getInstrumentStatusUpgraded(): Future[Boolean] = getBoolean(INSTRUMENT_STATUS_UPGRADED, false)

  override def setInstrumentStatusUpgraded(v: Boolean): Future[UpdateResult] = setBoolean(INSTRUMENT_STATUS_UPGRADED)(v)

  override def getEpaLastRecordTime(): Future[Date] =
    getDate(EPA_LAST_RECORD_TIME, Date.from(Instant.parse("2022-01-01T00:00:00.000Z")))

  override def setEpaLastRecordTime(v: Date): Future[UpdateResult] = setDate(EPA_LAST_RECORD_TIME)(v)
}
