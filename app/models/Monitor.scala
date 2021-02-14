package models

import models.ModelHelper._
import org.mongodb.scala.model._
import play.api._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global

case class Monitor(_id: String, desc: String)

import javax.inject._

@Singleton
class MonitorOp @Inject()(mongoDB: MongoDB) {
  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "monitors"
  val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)

  def init(colNames: Seq[String]) = {
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)

      waitReadyResult(f)
    }
    refresh
  }

  def newMonitor(m: Monitor) = {
    Logger.debug(s"Create monitor value ${m._id}!")
    map = map + (m._id -> m)

    val f = collection.insertOne(m).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _: Seq[t] =>
    })
    m._id
  }

  private def mList: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("_id")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  def refresh  {
    //map = Map.empty[String, Monitor]
    val pairs =
    for (m <- mList) yield {
      m._id -> m
    }
    map = pairs.toMap

  }

  var map: Map[String, Monitor] = Map.empty[String, Monitor]

  def mvList = mList.map(_._id)


  def getMonitorValueByName(_id: String) = {
    try {
      map(_id)
    } catch {
      case _: NoSuchElementException =>
        newMonitor(Monitor(_id, _id))
    }
  }

  def format(v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  def upsert(m: Monitor) = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }
}