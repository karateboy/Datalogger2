package models.sql

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.result.{InsertManyResult, UpdateResult}
import models._
import play.api.Logger
import scalikejdbc._

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class RecordOp @Inject()(sqlServer: SqlServer, calibrationOp: CalibrationOp, monitorTypeOp: MonitorTypeOp) extends RecordDB {
  private var mtList = List.empty[String]

  init()

  override def insertManyRecord(docs: Seq[RecordList])(colName: String): Future[InsertManyResult] = Future {
    docs.foreach(recordList => {
      insert(colName, recordList, false)
    })
    InsertManyResult.unacknowledged()
  }

  override def upsertRecord(doc: RecordList)(colName: String): Future[UpdateResult] = replaceRecord(doc)(colName)

  override def replaceRecord(doc: RecordList)(colName: String): Future[UpdateResult] = Future {
    val ret = insert(colName, doc, true)
    UpdateResult.acknowledged(ret, ret, null)
  }

  private def insert(colName: String, doc: RecordList, deleteFirst: Boolean): Int = {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    if (deleteFirst)
      delete(tab, doc._id)

    if (doc.mtDataList.isEmpty) {
      sql"""
           INSERT INTO $tab
           ([monitor], [time])
           VALUES
           (${doc._id.monitor}, ${doc._id.time})
           """.update().apply()
    } else {
      val fields = SQLSyntax.createUnsafely(doc.mtDataList.map(record => s"[${record.mtName}],[${record.mtName}_s]").mkString(","))

      def toStr(v: Option[Double]) = {
        if (v.isDefined)
          v.get.toString
        else
          "NULL"
      }

      val values = SQLSyntax.createUnsafely(doc.mtDataList.map(record => s"${toStr(record.value)}, '${record.status}'").mkString(","))
      sql"""
           INSERT INTO $tab
           ([monitor], [time], $fields)
           VALUES
           (${doc._id.monitor}, ${doc._id.time}, $values)
           """.update().apply()
    }
  }

  private def delete(tab: SQLSyntax, _id: RecordListID)(implicit session: DBSession = AutoSession) {
    sql"""
         DELETE FROM ${tab}
         WHERE [monitor] = ${_id.monitor} and [time] = ${_id.time}
         """.update().apply()
  }

  override def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String)(colName: String): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    val mtStatusCol = SQLSyntax.createUnsafely(s"${mt}_s")
    val time = new Date(dt)
    val ret =
      sql"""
         Update $tab
         Set $mtStatusCol = $status
         Where [time] = $time and [monitor] = $monitor
         """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def getRecordMapFuture(colName: String)
                                 (monitor: String, mtList: Seq[String],
                                  startTime: DateTime, endTime: DateTime): Future[Map[String, Seq[Record]]] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    val tab: SQLSyntax = getTab(colName)
    val rawRecords =
      sql"""
           Select *
           From $tab
           Where [time] >= ${startTime} and [time] < ${endTime} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()

    val recordF = calibrateHelper(rawRecords, startTime, endTime)

    for (records <- recordF) yield {
      val pairs =
        for (mt <- mtList) yield {
          val list =
            for {
              doc <- records
              time = doc._id.time
              mtMap = doc.mtMap if mtMap.contains(mt) && mtMap(mt).value.isDefined
            } yield {
              Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
            }
          mt -> list
        }
      pairs.toMap
    }
  }

  override def getRecordListFuture(colName: String)
                                  (startTime: DateTime, endTime: DateTime, monitors: Seq[String]): Future[Seq[RecordList]] = {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    val monitorIn = SQLSyntax.in(SQLSyntax.createUnsafely("[monitor]"), monitors)
    val rawRecords =
      sql"""
           Select *
           From $tab
           Where [time] >= ${startTime} and [time] < ${endTime} and $monitorIn
           Order by [time]
           """.map(mapper).list().apply()

    calibrateHelper(rawRecords, startTime, endTime)
  }

  private def getTab(tabName: String) = SQLSyntax.createUnsafely(s"[dbo].[$tabName]")

  private def mapper(rs: WrappedResultSet): RecordList = {
    val id = RecordListID(rs.jodaDateTime("time").toDate, rs.string("monitor"))
    val mtDataOptList =
      for (mt <- mtList) yield {
        for (status <- rs.stringOpt(s"${mt}_s")) yield
          MtRecord(mt, rs.doubleOpt(s"$mt"), status)
      }
    RecordList(_id = id, mtDataList = mtDataOptList.flatten)
  }

  private def calibrateHelper(rawRecords: List[RecordList], startTime: DateTime, endTime: DateTime): Future[List[RecordList]] = {
    val needCalibration = mtList.map { mt => monitorTypeOp.map(mt).calibrate.getOrElse(false) }.exists(p => p)

    if (needCalibration) {
      for (calibrationMap <- calibrationOp.getCalibrationMap(startTime, endTime)(monitorTypeOp)) yield {
        rawRecords.foreach(_.doCalibrate(monitorTypeOp, calibrationMap))
        rawRecords
      }
    } else
      Future.successful(rawRecords)
  }

  override def getRecordWithLimitFuture(colName: String)
                                       (startTime: DateTime, endTime: DateTime, limit: Int, monitor: String): Future[Seq[RecordList]] = {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    val rawRecords =
      sql"""
           Select Top 60 *
           From $tab
           Where [time] >= ${startTime.toDate} and [time] < ${endTime.toDate} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()
    calibrateHelper(rawRecords, startTime, endTime)
  }

  override def getRecordValueSeqFuture(colName: String)
                                      (mtList: Seq[String], startTime: DateTime, endTime: DateTime, monitor: String): Future[Seq[Seq[MtRecord]]] = {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    val rawRecords =
      sql"""
           Select *
           From $tab
           Where [time] >= ${startTime.toDate} and [time] < ${endTime.toDate} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()

    val recordF = calibrateHelper(rawRecords, startTime, endTime)
    for (docs <- recordF) yield
      for {
        doc <- docs
        mtMap = doc.mtMap if mtList.forall(doc.mtMap.contains(_))
      } yield
        mtList map mtMap
  }

  override def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult] = Future {
    records.foreach(recordList => {
      insert(colName, recordList, true)
    })
    BulkWriteResult.unacknowledged()
  }

  override def ensureMonitorType(mt: String): Unit = {
    synchronized {
      if (!mtList.contains(mt)) {
        Logger.info(s"alter record table by adding $mt")
        val tabList =
          Seq(HourCollection, MinCollection, SecCollection)
        tabList.foreach(tab => {
          addMonitorType(tab, mt)
        })

        mtList = mtList:+ mt
      }
    }
  }

  private def addMonitorType(tabName: String, mt: String)(implicit session: DBSession = AutoSession): Unit = {
    val tab = getTab(tabName)
    val mtColumn = SQLSyntax.createUnsafely(s"[$mt]")
    val mtStatusColumn = SQLSyntax.createUnsafely(s"[${mt}_s]")
    sql"""
          Alter Table $tab
          Add $mtColumn float;
         """.execute().apply()

    sql"""
         Alter Table $tab
         Add $mtStatusColumn [nvarchar](5);
         """.execute().apply()
  }

  private def init(): Unit = {
    val tabList =
      Seq(HourCollection, MinCollection, SecCollection)
    tabList.foreach(tab => {
      if (!sqlServer.getTables().contains(tab))
        createTab(tab)
    })

    mtList = sqlServer.getColumnNames(HourCollection).filter(col => {
      !col.endsWith("_s") && col != "monitor" && col != "time"
    })
  }

  private def createTab(tabName: String)(implicit session: DBSession = AutoSession): Unit = {
    val tab = getTab(tabName)
    val pk_tab = SQLSyntax.createUnsafely(s"[PK_$tabName]")
    sql"""
      CREATE TABLE $tab (
	        [monitor] [nvarchar](50) NOT NULL,
	        [time] [datetime2](7) NOT NULL,
          CONSTRAINT $pk_tab PRIMARY KEY CLUSTERED
          (
	          [monitor] ASC,
	          [time] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
      ) ON [PRIMARY]
      """.execute().apply()
  }

  override def getLatestMonitorRecordTimeAsync(colName: String)(monitor: String): Future[Option[Imports.DateTime]] = {
    Future {
      implicit val session: DBSession = ReadOnlyAutoSession
      val tab: SQLSyntax = getTab(colName)
      sql"""
              SELECT TOP 1 time
              FROM $tab
              WHERE [monitor] = $monitor
              Order by time desc
             """.map(rs => rs.jodaDateTime(1)).first().apply()
    }
  }

  override def getLatestMonitorRecordAsync(colName: String)(monitor: String): Future[Option[RecordList]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    val tab: SQLSyntax = getTab(colName)
    sql"""
            SELECT TOP 1 *
            FROM $tab
            WHERE [monitor] = $monitor
            Order by time desc
           """.map(mapper).first().apply()
  }
}
