package models.sql

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
class RecordOp @Inject()(sqlServer: SqlServer) extends RecordDB {
  val logger: Logger = Logger(this.getClass)
  private var mtList = List.empty[String]

  init()

  override def insertManyRecord(colName: String)(docs: Seq[RecordList]): Future[InsertManyResult] = Future {
    docs.foreach(recordList => {
      insert(colName, recordList)
    })
    InsertManyResult.unacknowledged()
  }

  override def upsertRecord(colName: String)(doc: RecordList): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)
    val fields = SQLSyntax.createUnsafely(doc.mtDataList.map(record => s"[${record.mtName}],[${record.mtName}_raw],[${record.mtName}_s]").mkString(","))

    def toStr(v: Option[Double]) = {
      if (v.isDefined)
        v.get.toString
      else
        "NULL"
    }

    val values = SQLSyntax.createUnsafely(doc.mtDataList.map(record => s"${toStr(record.value)}, ${toStr(record.rawValue)}, '${record.status}'").mkString(","))

    val ret =
      if (doc.mtDataList.isEmpty) {
        sql"""
           MERGE INTO $tab AS target
                USING (SELECT ${doc._id.monitor} AS monitor, ${doc._id.time} AS time) AS source
                ON (target.monitor = source.monitor AND target.time = source.time)
                WHEN NOT MATCHED THEN
                  INSERT
                  ([monitor], [time])
                  VALUES
                  (${doc._id.monitor}, ${doc._id.time});
           """.update().apply()
      } else {
        val setCause = SQLSyntax.createUnsafely(doc.mtDataList.map(record => s"[${record.mtName}] = ${toStr(record.value)}, [${record.mtName}_s] = '${record.status}'").mkString(","))
        sql"""
         MERGE INTO $tab AS target
                USING (SELECT ${doc._id.monitor} AS monitor, ${doc._id.time} AS time) AS source
                ON (target.monitor = source.monitor AND target.time = source.time)
                WHEN MATCHED THEN
                    UPDATE SET $setCause
                WHEN NOT MATCHED THEN
                  INSERT
                  ([monitor], [time], $fields)
                  VALUES
                  (${doc._id.monitor}, ${doc._id.time}, $values);
         """.update().apply()
      }

    UpdateResult.acknowledged(ret, ret, null)
  }


  private def insert(colName: String, doc: RecordList): Int = {
    implicit val session: DBSession = AutoSession
    val tab: SQLSyntax = getTab(colName)

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

  override def updateRecordStatus(colName: String)(dt: Long, mt: String, status: String, monitor: String): Future[UpdateResult] = Future {
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
                                  startTime: DateTime, endTime: DateTime, includeRaw: Boolean): Future[Map[String, Seq[Record]]] =
    Future {
      implicit val session: DBSession = ReadOnlyAutoSession
      val tab: SQLSyntax = getTab(colName)
      val recordLists =
        sql"""
           Select *
           From $tab
           Where [time] >= ${startTime} and [time] < ${endTime} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()


      getRecordMapFromRecordList(mtList, recordLists, includeRaw)

    }

  override def getRecordListFuture(colName: String)
                                  (startTime: DateTime, endTime: DateTime, monitors: Seq[String]): Future[Seq[RecordList]] =
    Future {
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

      rawRecords
    }

  private def getTab(tabName: String) = SQLSyntax.createUnsafely(s"[dbo].[$tabName]")

  private def mapper(rs: WrappedResultSet): RecordList = {
    val id = RecordListID(rs.timestamp("time"), rs.string("monitor"))
    val mtDataOptList =
      for (mt <- mtList) yield {
        for (status <- rs.stringOpt(s"${mt}_s")) yield
          MtRecord(mt, rs.doubleOpt(s"$mt"), status, rs.doubleOpt(s"${mt}_raw"))
      }
    RecordList(_id = id, mtDataList = mtDataOptList.flatten)
  }

  override def getRecordWithLimitFuture(colName: String)
                                       (startTime: DateTime, endTime: DateTime, limit: Int, monitor: String): Future[Seq[RecordList]] =
    Future {
      implicit val session: DBSession = AutoSession
      val tab: SQLSyntax = getTab(colName)
      val rawRecords =
        sql"""
           Select Top 60 *
           From $tab
           Where [time] >= ${startTime.toDate} and [time] < ${endTime.toDate} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()
      rawRecords
    }

  override def getRecordValueSeqFuture(colName: String)
                                      (mtList: Seq[String], startTime: DateTime, endTime: DateTime, monitor: String): Future[Seq[Seq[MtRecord]]] =
    Future {
      implicit val session: DBSession = AutoSession
      val tab: SQLSyntax = getTab(colName)
      val rawRecords =
        sql"""
           Select *
           From $tab
           Where [time] >= ${startTime.toDate} and [time] < ${endTime.toDate} and [monitor] = $monitor
           Order by [time]
           """.map(mapper).list().apply()


      for {
        doc <- rawRecords
        mtMap = doc.mtMap if mtList.forall(doc.mtMap.contains(_))
      } yield
        mtList map mtMap
    }

  override def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult] = {
    val futures = records.map(recordList => upsertRecord(colName)(recordList))
    for (_ <- Future.sequence(futures)) yield
      BulkWriteResult.unacknowledged()
  }

  override def ensureMonitorType(mt: String): Unit = {
    synchronized {
      if (!mtList.contains(mt)) {
        logger.info(s"alter record table by adding $mt")
        val tabList =
          Seq(HourCollection, MinCollection, SecCollection)
        tabList.foreach(tab => {
          addMonitorType(tab, mt)
        })

        mtList = mtList :+ mt
      }
    }
  }

  private def addMonitorType(tabName: String, mt: String)(implicit session: DBSession = AutoSession): Unit = {
    val tab = getTab(tabName)
    val mtColumn = SQLSyntax.createUnsafely(s"[$mt]")
    val mtStatusColumn = SQLSyntax.createUnsafely(s"[${mt}_s]")
    val mtRawColumn = SQLSyntax.createUnsafely(s"[${mt}_raw]")
    sql"""
          Alter Table $tab
          Add $mtColumn float;
         """.execute().apply()

    sql"""
          Alter Table $tab
          Add $mtRawColumn float;
         """.execute().apply()

    sql"""
         Alter Table $tab
         Add $mtStatusColumn [nvarchar](5);
         """.execute().apply()


  }


  private def init()(implicit DBSession: DBSession = AutoSession): Unit = {
    val tabList =
      Seq(HourCollection, MinCollection, SecCollection)
    tabList.foreach(tab => {
      if (!sqlServer.getTables().contains(tab))
        createTab(tab)
    })

    mtList = sqlServer.getColumnNames(HourCollection).filter(col => {
      col.endsWith("_s")
    }).map(_.reverse.drop(2).reverse)

    //Init raw columns
    tabList.foreach(tabName => {
      val columnName = sqlServer.getColumnNames(tabName)
      mtList.foreach(mt => if (!columnName.contains(s"${mt}_raw")) {
        val tab = getTab(tabName)
        val mtRawColumn = SQLSyntax.createUnsafely(s"[${mt}_raw]")
        sql"""
              Alter Table $tab
              Add $mtRawColumn float;
             """.execute().apply()
      })
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

  override def moveRecordToYearTable(colName: String)(year: Int): Future[Boolean] = {
    val tab = getTab(colName)
    val yearTabName = s"${colName}_$year"
    if (sqlServer.getTables().contains(yearTabName))
      Future.successful(false)
    else
      Future {
        val yearTab = getTab(yearTabName)
        implicit val session: DBSession = AutoSession
        sql"""
        Select * into $yearTab
        FROM $tab WHERE YEAR([time]) <= $year;

        Delete From $tab
        Where Year([time]) <= $year;
         """.execute().apply()
        true
      }
  }

  override def getHourCollectionList(): Future[Seq[String]] = Future {
    sqlServer.getTables().filter(tab => tab.startsWith(HourCollection))

  }

  override def getMinCollectionList(): Future[Seq[String]] = Future {
    sqlServer.getTables().filter(tab => tab.startsWith(MinCollection))
  }
}
