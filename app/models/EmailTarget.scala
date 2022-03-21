package models

import org.mongodb.scala.model.{ReplaceOneModel, ReplaceOptions}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global

case class EmailTarget(_id:String, topic:Seq[String])

object EmailTarget {
  implicit val reads = Json.reads[EmailTarget]
  implicit val writes = Json.writes[EmailTarget]
}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.{Filters, Indexes}

import javax.inject._

@Singleton
class EmailTargetOp @Inject()(mongoDB: MongoDB, sysConfig: SysConfig) {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "emailTargets"

  val codecRegistry = fromRegistries(fromProviders(classOf[EmailTarget]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[EmailTarget](colName).withCodecRegistry(codecRegistry)

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      for(_ <-f){
        importFromSysConfig()
      }
    }
  }

  def importFromSysConfig(): Unit ={
    for(targets <- sysConfig.getAlertEmailTarget()){
      val emailTargets = targets.map(email=>{
        EmailTarget(email, Seq.empty)
      })
      upsertMany(emailTargets)
    }
  }

  init

  def upsert(et: EmailTarget) = {
    val f = collection.replaceOne(Filters.equal("_id", et._id), et, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def get(_id:String) = {
    val f = collection.find(Filters.equal("_id", _id)).first().toFuture()
    f.onFailure(errorHandler())
    f
  }

  def upsertMany(etList:Seq[EmailTarget])={
    val updateModels: Seq[ReplaceOneModel[EmailTarget]] = etList map {
      et =>
        ReplaceOptions().upsert(true)
        ReplaceOneModel(Filters.equal("_id", et._id), et, ReplaceOptions().upsert(true))
    }
    val f = collection.bulkWrite(updateModels).toFuture()
    f onFailure (errorHandler)
    f
  }

  def getList() = {
    val f = collection.find(Filters.exists("_id")).toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(_id: String) = {
    val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
    f onFailure(errorHandler())
    f
  }
  def deleteAll = {
    val f = collection.deleteMany(Filters.exists("_id")).toFuture()
    f
  }
}