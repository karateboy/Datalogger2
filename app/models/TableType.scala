package models

import models.ModelHelper.waitReadyResult
import play.api.Logger

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class TableType @Inject()(recordDB: RecordDB) extends Enumeration {
  val logger: Logger = play.api.Logger(this.getClass)
  val second: Value = Value
  val hour: Value = Value
  val min: Value = Value
  var tableList: Seq[String] = getTableList

  private def getTableList: Seq[String] = {
    val f = Future.sequence(Seq(recordDB.getHourCollectionList, recordDB.getMinCollectionList))
    waitReadyResult(f).flatten.sorted
  }

  val mapCollection: Map[TableType#Value, String] =
    tableList.map { table =>
      val tableType = table match {
        case "hour_data" => hour
        case "min_data" => min
        case "sec_data" => second
        case other => try {
          withName(other)
        } catch {
          case _: NoSuchElementException =>
            Value(other)
        }
      }
      tableType -> table
    }.toMap

  def refresh(): Unit = {
    tableList = getTableList
  }
}