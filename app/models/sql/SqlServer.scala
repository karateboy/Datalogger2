package models.sql

import javax.inject.{Inject, Singleton}
import scalikejdbc._
@Singleton
class SqlServer @Inject() (){
  def getTables()(implicit session: DBSession = AutoSession): List[String] = {
      sql"""
          SELECT TABLE_NAME
          FROM INFORMATION_SCHEMA.TABLES
         """.map { rs => rs.string(1) }.list().apply()
  }
}
