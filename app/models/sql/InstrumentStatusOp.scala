package models.sql

import com.github.nscala_time.time.Imports
import models.InstrumentStatusDB

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class InstrumentStatusOp @Inject()() extends InstrumentStatusDB {
  override def log(is: InstrumentStatus): Unit = ???

  override def query(id: String, start: Imports.DateTime, end: Imports.DateTime): Seq[InstrumentStatus] = ???

  override def queryFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[InstrumentStatus]] = ???

  override def formatValue(v: Double, prec: Int): String = ???
}
