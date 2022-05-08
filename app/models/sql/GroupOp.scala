package models.sql

import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{Ability, Group, GroupDB}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scalikejdbc._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class GroupOp @Inject()(sqlServer: SqlServer)extends GroupDB{
  private val tabName = "group"
  private def init()(implicit session: DBSession = AutoSession): Unit ={
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[group](
	          [id] [nvarchar](50) NOT NULL,
	          [name] [nvarchar](50) NOT NULL,
	          [monitors] [nvarchar](1024) NOT NULL,
	          [monitorTypes] [nvarchar](1024) NOT NULL,
	          [admin] [bit] NOT NULL,
	          [abilities] [nvarchar](1024) NOT NULL,
	          [parent] [nvarchar](50) NULL,
          CONSTRAINT [PK_group] PRIMARY KEY CLUSTERED
          (
	          [id] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
      defaultGroup.foreach(upsert)
    }
  }
  init()
  private def mapper(rs: WrappedResultSet) = {
    val abilities = Json.parse(rs.string("abilities")).validate[Seq[Ability]].asOpt.getOrElse(Seq.empty[Ability])
    Group(rs.string("id"),
      name = rs.string("name"),
      monitors = rs.string("monitors").split(","),
      monitorTypes = rs.string("monitorTypes").split(","),
      admin = rs.boolean("admin"),
      abilities = abilities,
      parent = rs.stringOpt("parent")
    )
  }

  private def upsert(group: Group)= {
    implicit val session: DBSession = AutoSession

      val monitors = group.monitors.foldLeft("")((a, b) => s"$a,$b")
      val monitorTypes = group.monitorTypes.foldLeft("")((a, b) => s"$a,$b")
      val abilities = Json.toJson(group.abilities).toString()
      val ret =
        sql"""
            UPDATE [dbo].[group]
              SET [name] = ${group.name}
                  ,[monitors] = ${monitors}
                  ,[monitorTypes] = ${monitorTypes}
                  ,[admin] = ${group.admin}
                  ,[abilities] = ${abilities}
                  ,[parent] = ${group.parent}
              WHERE [id] = ${group._id}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[group]
              ([id]
                ,[name]
                ,[monitors]
                ,[monitorTypes]
                ,[admin]
                ,[abilities]
                ,[parent])
              VALUES
              (${group._id}
              ,${group.name}
              ,${monitors}
              ,${monitorTypes}
              ,${group.admin}
              ,${abilities}
              ,${group.parent})
            END
          """.update().apply()
      UpdateResult.acknowledged(0, ret, null)
    }

  override def newGroup(group: Group): Unit = upsert(group)

  override def deleteGroup(_id: String): DeleteResult = {
    implicit val session: DBSession = AutoSession
    val ret = sql"""
          DELETE FROM [dbo].[group]
          WHERE [id] = ${_id}
         """.update().apply()
    DeleteResult.acknowledged(ret)
  }

  override def updateGroup(group: Group): UpdateResult = upsert(group)


  override def getGroupByID(_id: String): Option[Group] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          From [dbo].[group]
          Where [id] = ${_id}
         """.map(mapper).first().apply()
  }

  override def getAllGroups(): Seq[Group] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          From [dbo].[group]
         """.map(mapper).list().apply()
  }

  override def addMonitor(_id: String, monitorID: String): Future[UpdateResult] = {
    Future{
      val groupOpt: Option[Group] = getGroupByID(_id)
      for(group<-groupOpt){
        val updateMonitors = Set(group.monitors + monitorID).toSeq
        val updateGroup = Group(group._id, group.name, updateMonitors, group.monitorTypes,
          group.admin, group.abilities, group.parent)
        upsert(updateGroup)
      }
      UpdateResult.unacknowledged()
    }
  }
}
