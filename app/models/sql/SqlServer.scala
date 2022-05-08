package models.sql

import play.api.Logger

import javax.inject.{Inject, Singleton}
import scalikejdbc._
@Singleton
class SqlServer @Inject() (){
  Logger.info("sql server ready")
  def getTables()(implicit session: DBSession = AutoSession): List[String] = {
      sql"""
          SELECT TABLE_NAME
          FROM INFORMATION_SCHEMA.TABLES
         """.map { rs => rs.string(1) }.list().apply()
  }
}
