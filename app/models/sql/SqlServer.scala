package models.sql

import org.joda.time.DateTime
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import scalikejdbc._
import scalikejdbc.config.DBs

import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class SqlServer @Inject()(lifecycle: ApplicationLifecycle) {
  Logger.info("sql server ready")
  DBs.setupAll()

  lifecycle.addStopHook { () =>
    Future.successful(DBs.closeAll())
  }

  def getTables()(implicit session: DBSession = ReadOnlyAutoSession): List[String] = {
    sql"""
          SELECT TABLE_NAME
          FROM INFORMATION_SCHEMA.TABLES
         """.map { rs => rs.string(1) }.list().apply()
  }

  def getColumnNames(tabName: String)(implicit session: DBSession = ReadOnlyAutoSession): List[String] = {
    sql"""
          SELECT COLUMN_NAME
          FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_NAME = $tabName
         """.map(rs => rs.string(1)).list().apply()
  }

  def addMonitorIfNotExist(tabName: String)(implicit session: DBSession = AutoSession): Boolean = {
    if (getColumnNames(tabName).contains("monitor"))
      true
    else {
      val tab = SQLSyntax.createUnsafely(tabName)
      sql"""
        Alter Table $tab
        Add [monitor] [nvarchar](50);
       """.execute().apply()
    }
  }

  def getLatestMonitorRecordTimeAsync(tabName: String, monitor: String, fieldName:String)
                                     (implicit session: DBSession = ReadOnlyAutoSession): Future[Option[DateTime]] =
    Future {
      val tab = SQLSyntax.createUnsafely(tabName)
      val time = SQLSyntax.createUnsafely(fieldName)
      sql"""
              SELECT TOP 1 $time
              FROM $tab
              WHERE [monitor] = $monitor
              Order by $time desc
             """.map(rs => rs.jodaDateTime(1)).first().apply()
    }
}
