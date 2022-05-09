package models.sql

import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{GroupDB, MqttSensorDB, Sensor}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class MqttSensorOp @Inject()(sqlServer: SqlServer, groupOp: GroupDB) extends MqttSensorDB {
  private val tabName = "sensor"

  override def getAllSensorList: Future[Seq[Sensor]] = Future {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          FROM [dbo].[sensor]
         """.map(mapper).list().apply()
  }

  init()

  private def mapper(rs: WrappedResultSet): Sensor =
    Sensor(id = rs.string("id"),
      topic = rs.string("topic"),
      monitor = rs.string("monitor"),
      group = rs.string("group"))

  override def upsert(sensor: Sensor): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
         UPDATE [dbo].[sensor]
            SET [topic] = ${sensor.topic}
              ,[monitor] = ${sensor.monitor}
              ,[group] = ${sensor.group}
            WHERE [id] = ${sensor.id}
         IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[sensor]
              ([id]
              ,[topic]
              ,[monitor]
              ,[group])
            VALUES
            (${sensor.id}
              ,${sensor.topic}
              ,${sensor.monitor}
              ,${sensor.group})
            END
         """.update().apply()
    groupOp.addMonitor(sensor.group, sensor.monitor)
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def delete(id: String): Future[DeleteResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
          DELETE FROM [dbo].[sensor]
          WHERE [id] = $id
         """.update().apply()
    DeleteResult.acknowledged(ret)
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
          CREATE TABLE [dbo].[sensor](
	          [id] [nvarchar](100) NOT NULL,
	          [topic] [nvarchar](100) NOT NULL,
	          [monitor] [nvarchar](50) NOT NULL,
	          [group] [nvarchar](50) NOT NULL,
          CONSTRAINT [PK_sensor] PRIMARY KEY CLUSTERED
          (
	          [id] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
  }

}
