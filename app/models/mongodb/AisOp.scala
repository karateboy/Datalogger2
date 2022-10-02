package models.mongodb

import models.ModelHelper.errorHandler
import models.{AisDB, AisData}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala.model._

@Singleton
class AisOp @Inject()(mongodb: MongoDB) extends AisDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "aisData"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[AisData]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[AisData](colName).withCodecRegistry(codecRegistry)

  private def init() {
    import org.mongodb.scala.model.Indexes._
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(ascending("monitor", "time"))
        })
      }
    }
  }

  init

  override def getAisData(monitor: String, start: Date, end: Date): Future[Seq[AisData]] = {
    import org.mongodb.scala.model.Filters._
    val filter = Filters.and(equal("monitor", monitor), gte("time", start), lt("time", end))
    val f = collection.find(filter).sort(Sorts.ascending("time")).toFuture()
    f onFailure errorHandler
    f
  }

  override def insertAisData(aisData: AisData): Future[InsertOneResult] = {
    val f = collection.insertOne(aisData).toFuture()
    f onFailure errorHandler
    f
  }

  override def getLatestData(monitor: String): Future[Option[AisData]] = {
    val f = collection.find(Filters.equal("monitor", monitor)).sort(Sorts.descending("time"))
      .limit(1).toFuture()
    f onFailure errorHandler
    f map (ret => {
      if (ret.isEmpty)
        None
      else
        Some(ret(0))
    })
  }

  override def getNearestAisDataInThePast(monitor: String, respType: String, start: Date): Future[Option[AisData]] = {
    val filter = Filters.and(Filters.equal("monitor", monitor), Filters.lt("time", start),
      Filters.equal("respType", respType))
    val f = collection.find(filter).sort(Sorts.descending("time"))
      .limit(1).toFuture()
    f onFailure errorHandler
    f map (ret => {
      if (ret.isEmpty)
        None
      else
        Some(ret(0))
    })
  }
}
