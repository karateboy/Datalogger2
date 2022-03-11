package models

import models.Protocol.ProtocolParam
import org.bson.codecs.Codec
import org.bson.codecs.configuration.CodecRegistries.fromCodecs
import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import play.api.libs.json._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success
case class InstrumentInfo(_id: String, instType: String, state: String,
                          protocol: String, protocolParam: String, monitorTypes: String,
                          calibrationTime: Option[String], inst:Instrument, group: Option[String])

case class InstrumentStatusType(key:String, addr:Int, desc:String, unit:String)

case class Instrument(_id: String, instType: String,
                      protocol: ProtocolParam, var param: String, active: Boolean,
                      state: String,
                      statusType:Option[List[InstrumentStatusType]],
                      group: Option[String]
                     ) {
}

import models.ModelHelper._
import org.mongodb.scala._

@Singleton
class InstrumentOp @Inject() (mongoDB: MongoDB) {
  implicit val ipRead = Json.reads[InstrumentStatusType]
  implicit val reader = Json.reads[Instrument]
  implicit val ipWrite = Json.writes[InstrumentStatusType]
  implicit val writer = Json.writes[Instrument]
  implicit val infoWrites = Json.writes[InstrumentInfo]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model._

  val codecRegistry = fromRegistries(fromProviders(classOf[Instrument], classOf[InstrumentStatusType],
    classOf[ProtocolParam]),
    fromCodecs(Protocol.CODEC2, Protocol.CODEC3, Protocol.CODEC4), DEFAULT_CODEC_REGISTRY)
  val colName = "instruments"
  val collection = mongoDB.database.getCollection[Instrument](colName).withCodecRegistry(codecRegistry)



  def init() {
    for(colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongoDB.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
      }
    }
  }
  init

  import org.mongodb.scala.model.Filters._
  def upsertInstrument(inst: Instrument): Boolean = {
    import org.mongodb.scala.model.ReplaceOptions
    val f = collection.replaceOne(equal("_id", inst._id), inst, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  def getInstrumentList(): Seq[Instrument] = {
    val f = collection.find().toFuture()

    waitReadyResult(f)
  }

  def getDoInstrumentList() = {
    val f = collection.find(Filters.in("instType", InstrumentType.DoInstruments)).toFuture()
    f onFailure(errorHandler)
    f
  }

  def getGroupDoInstrumentList(groupID: String) = {
    val filter = Filters.and(Filters.equal("group", groupID), Filters.in("instType", InstrumentType.DoInstruments:_*))
    val f = collection.find(filter).toFuture()
    f onFailure(errorHandler)
    f
  }
  def getInstrument(id: String) = {
    val f = collection.find(equal("_id", id)).toFuture()
    waitReadyResult(f)
  }

  def getInstrumentFuture(id: String):Future[Instrument] = {
    val f = collection.find(equal("_id", id)).first().toFuture()
    f onFailure(errorHandler())
    f
  }

  def getAllInstrumentFuture = {
    val f = collection.find().toFuture()
    f onFailure(errorHandler())
    f
  }

  def delete(id: String) = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    val ret = waitReadyResult(f)
    ret.getDeletedCount != 0
  }

  def activate(id: String) = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", true)).toFuture()
    f.onFailure({
      case ex:Exception=>
        ModelHelper.logException(ex)
    })
    f
  }

  def deactivate(id: String) = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", false)).toFuture()
    f.onFailure({
      case ex:Exception=>
        ModelHelper.logException(ex)
    })
    f
  }

  def setState(id:String, state:String) = {
    import org.mongodb.scala.model.Updates._    
    val f = collection.updateOne(equal("_id", id), set("state", state)).toFuture()
    f.onFailure({
      case ex:Exception=>
        ModelHelper.logException(ex)
    })
    f
  }
  
  def updateStatusType(id:String, status:List[InstrumentStatusType]) = {
    import org.mongodb.scala.bson.BsonArray
    import org.mongodb.scala.model.Updates._
    val bArray = new BsonArray
    
    val statusDoc = status.map{ s => bArray.add(Document(Json.toJson(s).toString).toBsonDocument)}
    
    val f = collection.updateOne(equal("_id", id), set("statusType", bArray)).toFuture()
    f.onFailure({
      case ex:Exception=>
        ModelHelper.logException(ex)
    })
    f
  }
  
  def getStatusTypeMap(id:String) = {
    val instList = getInstrument(id)
    if(instList.length != 1)
      throw new Exception("no such Instrument")
    
    val inst = instList(0)
    
    val statusTypeOpt = inst.statusType
    if(statusTypeOpt.isEmpty)
      Map.empty[String, String]
    else{
      val statusType = statusTypeOpt.get 
      val kv =
        for(kv <- statusType)
          yield
          kv.key -> kv.desc
      
      Map(kv:_*)
    }
  }
}