package models

import com.github.nscala_time.time.Imports._
import controllers.Assets.BadRequest
import play.api._
import play.api.data.validation.ValidationError
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.Future
import scala.language.implicitConversions

/**
 * @author user
 */

object ModelHelper {
  implicit def getSqlTimestamp(t: DateTime) = {
    new java.sql.Timestamp(t.getMillis)
  }

  implicit def getDateTime(st: java.sql.Timestamp) = {
    new DateTime(st)
  }

  import org.mongodb.scala.bson.BsonDateTime

  implicit def toDateTime(time: BsonDateTime) = new DateTime(time.getValue)

  implicit def toBsonDateTime(jdtime: DateTime) = new BsonDateTime(jdtime.getMillis)

  def main(args: Array[String]) {
    val timestamp = DateTime.parse("2015-04-01")
    println(timestamp.toString())
  }

  def logException(ex: Throwable) = {
    Logger.error(ex.getMessage, ex)
  }

  def logInstrumentError(id: String, msg: String, ex: Throwable) = {
    Logger.error(msg, ex)
    //log(instStr(id), Level.ERR, msg)
  }

  def logInstrumentInfo(id: String, msg: String) = {
    Logger.info(msg)
    //log(instStr(id), Level.INFO, msg)
  }

  def errorHandler: PartialFunction[Throwable, Any] = {
    case ex: Throwable =>
      Logger.error("Error=>", ex)
      throw ex
  }

  def errorHandler(prompt: String = "Error=>"): PartialFunction[Throwable, Any] = {
    case ex: Throwable =>
      Logger.error(prompt, ex)
      throw ex
  }

  def handleJsonValidateError(error: Seq[(JsPath, Seq[ValidationError])]): Result = {
    Logger.error(JsError.toJson(error).toString(), new Exception("Json validate error"))
    BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
  }

  def handleJsonValidateErrorFuture(error: Seq[(JsPath, Seq[ValidationError])]): Future[Result] = {
    Future.successful(handleJsonValidateError(error))
  }

  def directionAvg(sum_sin: Double, sum_cos: Double): Double = {
    val degree = Math.toDegrees(Math.atan2(sum_sin, sum_cos))
    if (degree >= 0)
      degree
    else
      degree + 360
  }

  private def getSinCosSum(speedList: Seq[Double], directionList: Seq[Double]): Option[(Double, Double)] = {
    if (speedList.length != directionList.length)
      Logger.error(s"speed #=${speedList.length} dir #=${directionList.length}")

    val speedDirections = speedList.zip(directionList)
    if (speedDirections.nonEmpty) {
      val sinSum = speedDirections.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
      val cosSum = speedDirections.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
      Some((sinSum, cosSum))
    } else
      None
  }

  def directionAvg(speedList: Seq[Double], directionList: Seq[Double]): Option[Double] =
    for((sinSum, cosSum) <- getSinCosSum(speedList, directionList)) yield
      directionAvg(sinSum, cosSum)

  def speedAvg(speedList: List[Double], directionList: List[Double]): Option[Double] =
    for ((sinSum, cosSum) <- getSinCosSum(speedList, directionList)) yield
      Math.sqrt(sinSum*sinSum + cosSum*cosSum)

  def speedDirectionAvg(speedList: List[Double], directionList: List[Double]): Option[(Double, Double)] = {
    if (speedList.length != directionList.length)
      Logger.error(s"speed #=${speedList.length} dir #=${directionList.length}")

    val speedDirections = speedList.zip(directionList)
    if (speedDirections.nonEmpty) {
      val sinSum = speedDirections.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
      val cosSum = speedDirections.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
      Some((directionAvg(sinSum, cosSum), Math.sqrt(sinSum*sinSum + cosSum*cosSum)))
    } else
      None
  }

  def getPeriods(start: DateTime, endTime: DateTime, d: Period): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current < endTime) {
      buf.append(current)
      current += d
    }

    buf.toList
  }

  def getHourBetween(start: DateTime, end: DateTime): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current <= end) {
      if (current.getMinuteOfHour == 0)
        buf.append(current)

      current = current.plusMinutes(1)
    }
    buf.toList
  }

  import scala.concurrent._

  def waitReadyResult[T](f: Future[T]) = {
    import scala.concurrent.duration._
    import scala.util._

    val ret = Await.ready(f, Duration.Inf).value.get

    ret match {
      case Success(t) =>
        t
      case Failure(ex) =>
        Logger.error(ex.getMessage, ex)
        throw ex
    }
  }
}

object EnumUtils {
  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) => {
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException => JsError(s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'")
        }
      }
      case _ => JsError("String value expected")
    }
  }

  implicit def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
    def writes(v: E#Value): JsValue = JsString(v.toString)
  }

  implicit def enumFormat[E <: Enumeration](enum: E): Format[E#Value] = {
    Format(enumReads(enum), enumWrites)
  }
}
