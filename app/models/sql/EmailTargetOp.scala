package models.sql

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{EmailTarget, EmailTargetDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class EmailTargetOp @Inject()(sqlServer: SqlServer) extends EmailTargetDB {
  private val tabName = "emailTarget"

  override def get(_id: String): Future[EmailTarget] = {
    implicit val session: DBSession = AutoSession
    Future {
      sql"""
          SELECT *
          FROM [dbo].[emailTarget]
          Where [id] = ${_id}
           """.map(mapper).first().apply().get
    }
  }

  init()

  override def upsertMany(etList: Seq[EmailTarget]): Future[BulkWriteResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      etList.foreach(et => upsert(et))
      BulkWriteResult.unacknowledged()
    }
  }

  override def upsert(et: EmailTarget): Future[UpdateResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      val topics = et.topic.mkString(",")
      val ret =
        sql"""
            UPDATE [dbo].[emailTarget]
            SET [topics] = ${topics}
            WHERE id=${et._id}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[emailTarget]
              ([id],[topics])
            VALUES
              (${et._id}, ${topics})
            END
          """.update().apply()
      UpdateResult.acknowledged(ret, ret, null)
    }
  }

  override def getList(): Future[Seq[EmailTarget]] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    Future {
      sql"""
          SELECT *
          FROM [dbo].[emailTarget]
           """.map(mapper).list().apply()
    }
  }

  private def mapper(rs: WrappedResultSet) =
    EmailTarget(rs.string("id"), rs.string("topics").split(",").filter(_.nonEmpty))

  override def delete(_id: String): Future[DeleteResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      val ret =
        sql"""
                  DELETE FROM [dbo].[emailTarget]
                  WHERE [id] = ${_id}
                  """.update().apply()
      DeleteResult.acknowledged(ret)
    }
  }

  override def deleteAll(): Future[DeleteResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      val ret =
        sql"""
                  DELETE FROM [dbo].[emailTarget]
                  """.update().apply()
      DeleteResult.acknowledged(ret)
    }
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[emailTarget](
	          [id] [nvarchar](50) NOT NULL,
	          [topics] [nvarchar](50) NOT NULL,
            CONSTRAINT [PK_emailTarget] PRIMARY KEY CLUSTERED
            (
	            [id] ASC
            )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
  }
}
