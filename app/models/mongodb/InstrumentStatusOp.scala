package models.mongodb

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.InstrumentStatusDB
import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.Filters.{and, equal, gte, lt}
import org.mongodb.scala.model.Sorts.ascending
import play.api.libs.json._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class InstrumentStatusOp @Inject()(mongodb: MongoDB) extends InstrumentStatusDB {

  import org.mongodb.scala.model._
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "instrumentStatus"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[InstrumentStatus], classOf[Status]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[InstrumentStatus](colName).withCodecRegistry(codecRegistry)

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(Indexes.ascending("time", "instID"))
        })
      }
    }
  }

  init


  override def log(is: InstrumentStatus): Unit = {
    collection.insertOne(is).toFuture()
  }

  override def queryAsync(id: String, start: DateTime, end: DateTime): Future[Seq[InstrumentStatus]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    collection.find(and(equal("instID", id), gte("time", start.toDate()), lt("time", end.toDate())))
      .sort(ascending("time")).toFuture()
  }

  override def queryFuture(start: DateTime, end: DateTime): Future[Seq[InstrumentStatus]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    collection.find(and(gte("time", start.toDate()), lt("time", end.toDate()))).sort(ascending("time")).toFuture()
  }

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[Imports.DateTime]] = {
    val f = collection.find(Filters.equal("monitor", monitor)).sort(Sorts.descending("time")).limit(1).toFuture()
    for (ret <- f) yield
      if (ret.isEmpty)
        None
      else
        Some(new DateTime(ret(0).time))
  }

  override def queryMonitorAsync(monitor: String, id: String, start: time.Imports.DateTime, end: time.Imports.DateTime):
  Future[Seq[InstrumentStatus]] =
    collection.find(and(equal("monitor", monitor),
      equal("instID", id),
      gte("time", start.toDate()), lt("time", end.toDate())))
      .sort(ascending("time")).toFuture()
}