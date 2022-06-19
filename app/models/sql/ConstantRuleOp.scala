package models.sql

import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{ConstantRule, ConstantRuleDB, ConstantRuleID}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class ConstantRuleOp @Inject()(sqlServer: SqlServer) extends ConstantRuleDB {
  private val tabName = "constantRule"

  override def getRules(): Future[Seq[ConstantRule]] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    Future {
      sql"""
         Select *
         From constantRule
         """.map(mapper).list().apply()
    }
  }

  init()

  private def mapper(rs: WrappedResultSet) =
    ConstantRule(ConstantRuleID(rs.string("monitor"), rs.string("monitorType")),
      rs.boolean("enable"),
      rs.int("count")
    )

  override def upsert(rule: ConstantRule): Future[UpdateResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      val ret =
        sql"""
            UPDATE [dbo].[constantRule]
            SET [enable] = ${rule.enable}
                ,[count] = ${rule.count}
            WHERE monitorType=${rule._id.monitorType} and monitor=${rule._id.monitor}
            IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[constantRule]
              ([monitor]
              ,[monitorType]
              ,[enable]
              ,[count])
            VALUES
              (${rule._id.monitor}, ${rule._id.monitorType}, ${rule.enable}, ${rule.count})
            END
          """.update().apply()
      UpdateResult.acknowledged(ret, ret, null)
    }
  }

  override def delete(_id: ConstantRuleID): Future[DeleteResult] = {
    implicit val session: DBSession = AutoSession
    Future {
      val ret =
        sql"""
            DELETE FROM [dbo].[constantRule]
            WHERE monitorType=${_id.monitorType} and monitor=${_id.monitor}
          """.update().apply()

      DeleteResult.acknowledged(ret)
    }
  }

  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
           CREATE TABLE [dbo].[constantRule](
	          [monitor] [nvarchar](50) NOT NULL,
	          [monitorType] [nvarchar](50) NOT NULL,
	          [enable] [bit] NOT NULL,
	          [count] [int] NOT NULL,
          CONSTRAINT [PK_constantRule] PRIMARY KEY CLUSTERED
          (
	          [monitor] ASC,
	          [monitorType] ASC
          )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
          ) ON [PRIMARY]
           """.execute().apply()
    }
  }
}
