package models.mongodb

import models.CdxUploader.{CdxConfig, CdxMonitorType}
import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{AQI, CdxUploader, Monitor, SysConfigDB}
import org.mongodb.scala.bson.{BsonDateTime, BsonNumber, BsonString, BsonValue, Document}
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SysConfig @Inject()(mongodb: MongoDB) extends SysConfigDB {
  lazy private val ColName = "sysConfig"
  lazy private val collection = mongodb.database.getCollection(ColName)

  lazy private val valueKey = "value"

  private val defaultConfig = Map(
    Logo -> Document(valueKey -> Array.empty[Byte], "filename" -> ""),
    SpectrumLastParseTime -> Document(valueKey -> new Date(0)),
    WeatherLastParseTime -> Document(valueKey -> new Date(0)),
    WeatherSkipLine -> Document(valueKey -> 0),
    AlertEmailTarget -> Document(valueKey -> Seq("karateboy.tw@gmail.com")),
    EffectiveRatio -> Document(valueKey -> 0.75),
    CDX_CONFIG -> Document(valueKey -> Json.toJson(CdxUploader.defaultConfig).toString()),
    CDX_MONITOR_TYPES -> Document(valueKey -> Json.toJson(CdxUploader.defaultMonitorTypes).toString()),
    ACTIVE_MONITOR_ID -> Document(valueKey -> Monitor.activeId),
    AQI_MONITOR_TYPES -> Document(valueKey ->AQI.defaultMappingTypes),
    EPA_LAST_RECORD_TIME -> Document(valueKey -> Date.from(Instant.parse("2022-01-01T00:00:00.000Z"))),
    LINE_TOKEN -> Document(valueKey -> ""),
    SMS_PHONES -> Document(valueKey -> ""),
    LINE_CHANNEL_TOKEN -> Document(valueKey -> "")
  )

  override def getSpectrumLastParseTime: Future[Instant] = getInstant(SpectrumLastParseTime)()

  init

  override def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult] = setInstant(SpectrumLastParseTime)(dt)

  override def getWeatherLastParseTime: Future[Instant] = getInstant(WeatherLastParseTime)()

  private def getInstant(tag: String)(): Future[Instant] =
    get(tag).map(
      v =>
        Instant.ofEpochMilli(v.asDateTime().getValue)
    )

  override def setWeatherLastParseTime(dt: Instant): Future[UpdateResult] = setInstant(WeatherLastParseTime)(dt)

  /*
  override def getLogo: Future[LogoImage] = {
    val f = collection.find(Filters.equal("_id", Logo)).headOption()
    f onFailure (errorHandler())
    for (ret <- f) yield {
      val doc = ret.getOrElse(defaultConfig(Logo))
      val image = doc(valueKey).asBinary().getData
      val filename = doc("filename").asString().getValue
      LogoImage(filename, image)
    }
  }

  override def setLogo(logo: LogoImage): Future[UpdateResult] = {
    val doc = Document(valueKey -> logo.image, "filename" -> logo.filename)
    val f = collection.replaceOne(Filters.equal("_id", Logo), doc).toFuture()
    f onFailure (errorHandler)
    f
  }
*/

  private def setInstant(tag: String)(dt: Instant): Future[UpdateResult] = set(tag, BsonDateTime(Date.from(dt)))

  override def getWeatherSkipLine: Future[Int] = get(WeatherSkipLine).map(_.asNumber().intValue())

  override def setWeatherSkipLine(v: Int): Future[UpdateResult] = set(WeatherSkipLine, BsonNumber(v))

  override def getEffectiveRatio: Future[Double] = get(EffectiveRatio).map(_.asNumber().doubleValue())

  override def setEffectiveRation(v: Double): Future[UpdateResult] = set(EffectiveRatio, BsonNumber(v))

  private def set(_id: String, v: BsonValue) = upsert(_id, Document(valueKey -> v))

  private def upsert(_id: String, doc: Document): Future[UpdateResult] = {
    val uo = new ReplaceOptions().upsert(true)
    val f = collection.replaceOne(Filters.equal("_id", _id), doc, uo).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def getAlertEmailTarget: Future[Seq[String]] =
    for (v <- get(AlertEmailTarget)) yield
      v.asArray().toList.map(_.asString().getValue)

  override def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult] = set(AlertEmailTarget, emails)

  private def set(_id: String, v: Seq[String]) = upsert(_id, Document(valueKey -> v))

  override def getCdxConfig(): Future[CdxConfig] = get(CDX_CONFIG).map {
    value =>
      Json.parse(value.asString().getValue).validate[CdxConfig].asOpt.getOrElse(CdxUploader.defaultConfig)
  }

  private def get(_id: String): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id)).headOption()
    f.failed.foreach(errorHandler)
    for (ret <- f) yield {
      val doc = ret.getOrElse(defaultConfig(_id))
      doc(valueKey)
    }
  }

  override def setCdxConfig(config: CdxConfig): Future[UpdateResult] =
    set(CDX_CONFIG, BsonString(Json.toJson(config).toString()))

  override def getCdxMonitorTypes(): Future[Seq[CdxUploader.CdxMonitorType]] = get(CDX_MONITOR_TYPES).map {
    value =>
      Json.parse(value.asString().getValue).validate[Seq[CdxMonitorType]].asOpt.getOrElse(CdxUploader.defaultMonitorTypes)
  }

  override def setCdxMonitorTypes(monitorTypes: Seq[CdxMonitorType]): Future[UpdateResult] =
    set(CDX_MONITOR_TYPES, BsonString(Json.toJson(monitorTypes).toString()))

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongodb.database.createCollection(ColName).toFuture()
        f.failed.foreach(errorHandler)
        waitReadyResult(f)
      }
    }
  }

  override def getActiveMonitorId: Future[String] = get(ACTIVE_MONITOR_ID).map(_.asString().getValue)

  override def setActiveMonitorId(id: String): Future[UpdateResult] = set(ACTIVE_MONITOR_ID, BsonString(id))

  override def getAqiMonitorTypes: Future[Seq[String]] =
    for (v <- get(AQI_MONITOR_TYPES)) yield
      v.asArray().toSeq.map(_.asString().getValue)
  override def setAqiMonitorTypes(monitorTypes: Seq[String]): Future[UpdateResult] =
    set(AQI_MONITOR_TYPES, monitorTypes)


  override def getEpaLastRecordTime: Future[Date] = get(EPA_LAST_RECORD_TIME)
    .map(v => Date.from(Instant.ofEpochMilli(v.asDateTime().getValue)))

  override def setEpaLastRecordTime(v: Date): Future[UpdateResult] = set(EPA_LAST_RECORD_TIME, BsonDateTime(v))

  override def getLineToken: Future[String] = get(LINE_TOKEN).map(_.asString().getValue)

  override def setLineToken(token: String): Future[UpdateResult] = set(LINE_TOKEN, BsonString(token))

  override def getSmsPhones: Future[Seq[String]] = get(SMS_PHONES).map(_.asString().getValue.split(",").toSeq)

  override def setSmsPhones(phones: Seq[String]): Future[UpdateResult] = set(SMS_PHONES, BsonString(phones.mkString(",")))

  override def getLineChannelToken: Future[String] =
    get(LINE_CHANNEL_TOKEN).map(_.asString().getValue)

  override def setLineChannelToken(token: String): Future[UpdateResult] =
    set(LINE_CHANNEL_TOKEN, BsonString(token))
}
