package models.mongodb

import models.CdxUploader.{CdxConfig, CdxMonitorType}
import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{CdxUploader, SysConfigDB}
import org.mongodb.scala.bson.{BsonBoolean, BsonDateTime, BsonNumber, BsonString, BsonValue, Document}
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.collection.JavaConversions.asScalaBuffer
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
    AlertEmailTaget -> Document(valueKey -> Seq("karateboy.tw@gmail.com")),
    EffectiveRatio -> Document(valueKey -> 0.75),
    CDX_CONFIG -> Document(valueKey -> Json.toJson(CdxUploader.defaultConfig).toString()),
    CDX_MONITOR_TYPES -> Document(valueKey -> Json.toJson(CdxUploader.defaultMonitorTypes).toString()),
    EASE_HISTORY_DATA -> Document(valueKey -> false),
    IMPORT_GPS -> Document(valueKey -> false)
  )

  override def getSpectrumLastParseTime(): Future[Instant] = getInstant(SpectrumLastParseTime)()

  init

  override def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult] = setInstant(SpectrumLastParseTime)(dt)

  override def getWeatherLastParseTime(): Future[Instant] = getInstant(WeatherLastParseTime)()

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

  override def getWeatherSkipLine(): Future[Int] = get(WeatherSkipLine).map(_.asNumber().intValue())

  override def setWeatherSkipLine(v: Int): Future[UpdateResult] = set(WeatherSkipLine, BsonNumber(v))

  override def getEffectiveRatio(): Future[Double] = get(EffectiveRatio).map(_.asNumber().doubleValue())

  override def setEffectiveRation(v: Double): Future[UpdateResult] = set(EffectiveRatio, BsonNumber(v))

  private def set(_id: String, v: BsonValue) = upsert(_id, Document(valueKey -> v))

  private def upsert(_id: String, doc: Document): Future[UpdateResult] = {
    val uo = new ReplaceOptions().upsert(true)
    val f = collection.replaceOne(Filters.equal("_id", _id), doc, uo).toFuture()
    f.onFailure(errorHandler)
    f
  }

  override def getAlertEmailTarget(): Future[Seq[String]] =
    for (v <- get(AlertEmailTaget)) yield
      v.asArray().toSeq.map(_.asString().getValue)

  override def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult] = set(AlertEmailTaget, emails)

  private def set(_id: String, v: Seq[String]) = upsert(_id, Document(valueKey -> v))

  override def getCdxConfig(): Future[CdxConfig] = get(CDX_CONFIG).map {
    value =>
      Json.parse(value.asString().getValue).validate[CdxConfig].asOpt.getOrElse(CdxUploader.defaultConfig)
  }

  private def get(_id: String): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id)).headOption()
    f.onFailure(errorHandler)
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
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }
  }

  override def getEaseHistoryData(): Future[Boolean] = get(EASE_HISTORY_DATA).map(_.asBoolean().getValue)

  override def setEaseHistoryData(v: Boolean): Future[UpdateResult] = set(EASE_HISTORY_DATA, BsonBoolean(v))

  override def getImportGPS(): Future[Boolean] = get(IMPORT_GPS).map(_.asBoolean().getValue)

  override def setImportGPS(v: Boolean): Future[UpdateResult] = set(IMPORT_GPS, BsonBoolean(v))
}
