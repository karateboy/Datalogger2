package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{LogoImage, SysConfigDB}
import org.mongodb.scala.bson.{BsonDateTime, BsonNumber, BsonValue, Document}
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.mongodb.scala.result.UpdateResult

import java.time.Instant
import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class SysConfig @Inject()(mongodb: MongoDB) extends SysConfigDB {
  private val ColName = "sysConfig"
  private val collection = mongodb.database.getCollection(ColName)

  private val valueKey = "value"

  private val defaultConfig = Map(
    Logo -> Document(valueKey -> Array.empty[Byte], "filename" -> ""),
    SpectrumLastParseTime -> Document(valueKey -> new Date(0)),
    WeatherLastParseTime -> Document(valueKey -> new Date(0)),
    WeatherSkipLine -> Document(valueKey -> 0),
    AlertEmailTaget -> Document(valueKey -> Seq("karateboy.tw@gmail.com")),
    EffectiveRatio -> Document(valueKey -> 0.75)
  )

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongodb.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }
  }

  init

  private def upsert(_id: String, doc: Document): Future[UpdateResult] = {
    val uo = new ReplaceOptions().upsert(true)
    val f = collection.replaceOne(Filters.equal("_id", _id), doc, uo).toFuture()
    f.onFailure(errorHandler)
    f
  }

  private def get(_id: String): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id)).headOption()
    f.onFailure(errorHandler)
    for (ret <- f) yield {
      val doc = ret.getOrElse(defaultConfig(_id))
      doc(valueKey)
    }
  }

  private def set(_id: String, v: BsonValue) = upsert(_id, Document(valueKey -> v))

  private def set(_id: String, v: Seq[String]) = upsert(_id, Document(valueKey -> v))

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

  private def getInstant(tag: String)(): Future[Instant] =
    get(tag).map(
      v =>
        Instant.ofEpochMilli(v.asDateTime().getValue)
    )

  private def setInstant(tag: String)(dt: Instant): Future[UpdateResult] = set(tag, BsonDateTime(Date.from(dt)))

  override def getSpectrumLastParseTime(): Future[Instant] = getInstant(SpectrumLastParseTime)()

  override def setSpectrumLastParseTime(dt: Instant): Future[UpdateResult] = setInstant(SpectrumLastParseTime)(dt)

  override def getWeatherLastParseTime(): Future[Instant] = getInstant(WeatherLastParseTime)()

  override def setWeatherLastParseTime(dt: Instant): Future[UpdateResult] = setInstant(WeatherLastParseTime)(dt)

  override def getWeatherSkipLine(): Future[Int] = get(WeatherSkipLine).map(_.asNumber().intValue())

  override def setWeatherSkipLine(v: Int): Future[UpdateResult] = set(WeatherSkipLine, BsonNumber(v))

  override def getEffectiveRatio(): Future[Double] = get(EffectiveRatio).map(_.asNumber().doubleValue())

  override def setEffectiveRation(v: Double): Future[UpdateResult] = set(EffectiveRatio, BsonNumber(v))

  override def getAlertEmailTarget(): Future[Seq[String]] =
    for (v <- get(AlertEmailTaget)) yield
      v.asArray().toSeq.map(_.asString().getValue)

  override def setAlertEmailTarget(emails: Seq[String]): Future[UpdateResult] = set(AlertEmailTaget, emails)

}
