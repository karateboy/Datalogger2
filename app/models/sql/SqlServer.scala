package models.sql

import play.api.Logger
import play.api.inject.ApplicationLifecycle
import scalikejdbc._
import scalikejdbc.config.DBs

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
}
