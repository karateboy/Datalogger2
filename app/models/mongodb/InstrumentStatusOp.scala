package models.mongodb

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.InstrumentStatusDB
import models.ModelHelper.{errorHandler, waitReadyResult}
import play.api.libs.json._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions
@Singleton
class InstrumentStatusOp @Inject()(mongodb: MongoDB) extends InstrumentStatusDB {
  import org.mongodb.scala._
  lazy private val collectionName = "instrumentStatus"
  lazy private val collection = mongodb.database.getCollection(collectionName)

  private def init() {
    import org.mongodb.scala.model.Indexes._
    for(colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(ascending("time", "instID"))
        })
      }
    }
  }
  init

  private def toDocument(is: InstrumentStatus) = {
    import org.mongodb.scala.bson._
    val jsonStr = Json.toJson(is).toString()
    Document(jsonStr) ++ Document("time" -> is.time.toDate)
  }

  private def toInstrumentStatus(doc: Document) = {
    //Workaround time bug
    val time = new DateTime(doc.get("time").get.asDateTime().getValue)
    val instID = doc.get("instID").get.asString().getValue
    val statusList = doc.get("statusList").get.asArray()
    val it = statusList.iterator()
    import scala.collection.mutable.ListBuffer
    val lb = ListBuffer.empty[Status]
    while (it.hasNext()) {
      val statusDoc = it.next().asDocument()
      val key = statusDoc.get("key").asString().getValue
      val value = statusDoc.get("value").asNumber().doubleValue()
      lb.append(Status(key, value))
    }

    InstrumentStatus(time, instID, lb.toList)
  }

  override def log(is: InstrumentStatus): Unit = {
    //None blocking...
    val f = collection.insertOne(toDocument(is)).toFuture()
  }

  override def query(id: String, start: DateTime, end: DateTime): Seq[InstrumentStatus] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(equal("instID", id), gte("time", start.toDate()), lt("time", end.toDate()))).sort(ascending("time")).toFuture()
    waitReadyResult(f).map { toInstrumentStatus }
  }

  override def queryFuture(start: DateTime, end: DateTime): Future[Seq[InstrumentStatus]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val recordFuture = collection.find(and(gte("time", start.toDate()), lt("time", end.toDate()))).sort(ascending("time")).toFuture()
    for (f <- recordFuture)
      yield f.map { toInstrumentStatus }
  }

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[Imports.DateTime]] = ???
}