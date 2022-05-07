package models.sql

import models.{Instrument, InstrumentDB, InstrumentStatusType}
import org.mongodb.scala.result.UpdateResult

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
@Singleton
class InstrumentOp @Inject()()extends InstrumentDB{
  override def upsertInstrument(inst: Instrument): Boolean = ???

  override def getInstrumentList(): Seq[Instrument] = ???

  override def getInstrumentMap(): Future[Map[String, Instrument]] = ???

  override def getInstrumentFuture(id: String): Future[Instrument] = ???

  override def getAllInstrumentFuture: Future[Seq[Instrument]] = ???

  override def delete(id: String): Boolean = ???

  override def activate(id: String): Future[UpdateResult] = ???

  override def deactivate(id: String): Future[UpdateResult] = ???

  override def setState(id: String, state: String): Future[UpdateResult] = ???

  override def updateStatusType(id: String, statusList: List[InstrumentStatusType]): Future[UpdateResult] = ???

  override def getStatusTypeMap(id: String): Map[String, InstrumentStatusType] = ???

  override def getInstrument(id: String): Seq[Instrument] = ???
}
