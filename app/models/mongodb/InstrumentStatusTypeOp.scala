package models.mongodb

import com.mongodb.client.result.UpdateResult
import models.ModelHelper.errorHandler
import models.{InstrumentStatusType, InstrumentStatusTypeDB, InstrumentStatusTypeMap}
import org.mongodb.scala.result.UpdateResult

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import org.mongodb.scala.model._

import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class InstrumentStatusTypeOp @Inject()(mongodb: MongoDB) extends InstrumentStatusTypeDB{

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "instrumentStatusTypes"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[InstrumentStatusTypeMap], classOf[InstrumentStatusType]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[InstrumentStatusTypeMap](colName).withCodecRegistry(codecRegistry)

  override def getAllInstrumentStatusTypeListAsync(monitor: String): Future[Seq[InstrumentStatusTypeMap]] = {
    val f = collection.find(Filters.equal("monitor", monitor)).toFuture()
    f onFailure errorHandler
    f
  }

  override def upsertInstrumentStatusTypeMapAsync(monitor: String, maps: Seq[InstrumentStatusTypeMap]): Future[UpdateResult] = {
    val allF = maps.map(deviceMap=>{
      collection.replaceOne(Filters.and(Filters.equal("monitor", monitor), Filters.equal("instrumentId", deviceMap.instrumentId)),
        deviceMap).toFuture()
    })
    Future.sequence(allF).map(ret =>
      UpdateResult.acknowledged(ret.map(_.getMatchedCount).sum, ret.map(_.getModifiedCount).sum, null) )
  }
}
