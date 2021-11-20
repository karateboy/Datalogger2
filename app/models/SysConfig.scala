package models
import play.api.libs.json._
import models.ModelHelper._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import org.mongodb.scala.model._
import org.mongodb.scala.bson._
import org.mongodb.scala.result.UpdateResult
import play.api.Logger

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.concurrent.Future

case class LogoImage(filename:String, image:Array[Byte])
@Singleton
class SysConfig @Inject()(mongoDB: MongoDB){
  val ColName = "sysConfig"
  val collection = mongoDB.database.getCollection(ColName)

  val valueKey = "value"
  val MonitorTypeVer = "Version"
  val Logo = "Logo"
  val SpectrumLastParseTime = "SpectrumLastParseTime"

  val defaultConfig = Map(
    MonitorTypeVer -> Document(valueKey -> 1),
    Logo -> Document(valueKey->Array.empty[Byte], "filename"->""),
    SpectrumLastParseTime -> Document(valueKey->new Date(0)))

  def init() {
    for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(ColName)) {
        val f = mongoDB.database.createCollection(ColName).toFuture()
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }
  }
  init

  def upsert(_id: String, doc: Document) = {
    val uo = new ReplaceOptions().upsert(true)
    val f = collection.replaceOne(Filters.equal("_id", _id), doc, uo).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def get(_id: String): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id)).headOption()
    f.onFailure(errorHandler)
    for (ret <- f) yield {
      val doc = ret.getOrElse(defaultConfig(_id))
      doc(valueKey)
    }
  }

  def set(_id: String, v: BsonValue) = upsert(_id, Document(valueKey -> v))

  def getLogo = {
    val f = collection.find(Filters.equal("_id", Logo)).headOption()
    f onFailure(errorHandler())
    for(ret<-f) yield {
      val doc = ret.getOrElse(defaultConfig(Logo))
      val image = doc(valueKey).asBinary().getData
      val filename = doc("filename").asString().getValue
      LogoImage(filename, image)
    }
  }
  def setLogo(logo:LogoImage) = {
    val doc = Document(valueKey->logo.image, "filename"->logo.filename)
    val f = collection.replaceOne(Filters.equal("_id", Logo), doc).toFuture()
    f onFailure(errorHandler)
    f
  }

  def getSpectrumLastParseTime(): Future[Instant] = get(SpectrumLastParseTime).map({
    v=>
      Instant.ofEpochMilli(v.asDateTime().getValue)
  })
  def setSpectrumLastParseTime(dt:Instant): Future[UpdateResult] = set(SpectrumLastParseTime, BsonDateTime(Date.from(dt)))
}