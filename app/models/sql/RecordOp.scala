package models.sql

import com.github.nscala_time.time.Imports
import models.{MtRecord, Record, RecordDB, RecordList}
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
@Singleton
class RecordOp @Inject()() extends RecordDB{
  override def insertManyRecord(docs: Seq[RecordList])(colName: String): Future[InsertManyResult] = ???

  override def replaceRecord(doc: RecordList)(colName: String): Future[UpdateResult] = ???

  override def upsertRecord(doc: RecordList)(colName: String): Future[UpdateResult] = ???

  override def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String)(colName: String): Future[UpdateResult] = ???

  override def getRecordMapFuture(colName: String)(monitor: String, mtList: Seq[String], startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Map[String, Seq[Record]]] = ???

  override def getRecordListFuture(colName: String)(startTime: Imports.DateTime, endTime: Imports.DateTime, monitors: Seq[String]): Future[Seq[RecordList]] = ???

  override def getRecordWithLimitFuture(colName: String)(startTime: Imports.DateTime, endTime: Imports.DateTime, limit: Int, monitor: String): Future[Seq[RecordList]] = ???

  override def getLatestRecordFuture(colName: String)(monitor: String): Future[Seq[RecordList]] = ???

  override def getRecordValueSeqFuture(colName: String)(mtList: Seq[String], startTime: Imports.DateTime, endTime: Imports.DateTime, monitor: String): Future[Seq[Seq[MtRecord]]] = ???

  override def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult] = ???
}
