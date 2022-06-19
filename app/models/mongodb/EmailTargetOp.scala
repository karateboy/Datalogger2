package models.mongodb

import models.ModelHelper.{errorHandler, waitReadyResult}
import models.{EmailTarget, EmailTargetDB, SysConfigDB}
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.model.{Filters, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class EmailTargetOp @Inject()(mongodb: MongoDB, sysConfig: SysConfigDB) extends EmailTargetDB {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "emailTargets"

  lazy private val codecRegistry = fromRegistries(fromProviders(classOf[EmailTarget]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[EmailTarget](colName).withCodecRegistry(codecRegistry)

  private def init(): Unit = {
    val colNames = waitReadyResult(mongodb.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongodb.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      for(_ <-f){
        importFromSysConfig()
      }
    }
  }

  private def importFromSysConfig(): Unit ={
    for(targets <- sysConfig.getAlertEmailTarget()){
      val emailTargets = targets.map(email=>{
        EmailTarget(email, Seq.empty)
      })
      upsertMany(emailTargets)
    }
  }

  init

  override def upsert(et: EmailTarget): Future[UpdateResult] = {
    val f = collection.replaceOne(Filters.equal("_id", et._id), et, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  override def get(_id:String): Future[EmailTarget] = {
    val f = collection.find(Filters.equal("_id", _id)).first().toFuture()
    f.onFailure(errorHandler())
    f
  }

  override def upsertMany(etList:Seq[EmailTarget]): Future[BulkWriteResult] ={
    val updateModels: Seq[ReplaceOneModel[EmailTarget]] = etList map {
      et =>
        ReplaceOptions().upsert(true)
        ReplaceOneModel(Filters.equal("_id", et._id), et, ReplaceOptions().upsert(true))
    }
    val f = collection.bulkWrite(updateModels).toFuture()
    f onFailure (errorHandler)
    f
  }

  override def getList(): Future[Seq[EmailTarget]] = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  override def delete(_id: String): Future[DeleteResult] = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
  override def deleteAll(): Future[DeleteResult] = {
    val f = collection.deleteMany(Filters.exists("_id")).toFuture()
    f
  }
}