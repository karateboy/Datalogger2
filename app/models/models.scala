package models

import com.github.nscala_time.time.Imports._
import play.api._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.sql.Timestamp
import scala.concurrent.Future
import scala.language.implicitConversions

/**
 * @author user
 */

object ModelHelper {
  val logger: Logger = Logger(this.getClass)

  implicit def getSqlTimestamp(t: DateTime): Timestamp = {
    new java.sql.Timestamp(t.getMillis)
  }

  implicit def getDateTime(st: java.sql.Timestamp): DateTime = {
    new DateTime(st)
  }

  import org.mongodb.scala.bson.BsonDateTime

  implicit def toDateTime(time: BsonDateTime): DateTime = new DateTime(time.getValue)

  implicit def toBsonDateTime(jdtime: DateTime): BsonDateTime = new BsonDateTime(jdtime.getMillis)

  def main(args: Array[String]) {
    val timestamp = DateTime.parse("2015-04-01")
    println(timestamp.toString())
  }

  def logException(ex: Throwable): Unit = {
    logger.error(ex.getMessage, ex)
  }

  def logInstrumentError(id: String, msg: String, ex: Throwable): Unit = {
    logger.error(msg, ex)
    //log(instStr(id), Level.ERR, msg)
  }

  def logInstrumentInfo(id: String, msg: String) = {
    logger.info(msg)
    //log(instStr(id), Level.INFO, msg)
  }

  def errorHandler: PartialFunction[Throwable, Any] = {
    case ex: Throwable =>
      logger.error("Error=>", ex)
      throw ex
  }

  def errorHandler(prompt: String = "Error=>"): PartialFunction[Throwable, Any] = {
    case ex: Throwable =>
      logger.error(prompt, ex)
      throw ex
  }

  def handleJsonValidateError(error: Seq[(JsPath, Seq[JsonValidationError])]): Result = {
    logger.error(JsError.toJson(error).toString(), new Exception("Json validate error"))
    Results.BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
  }

  def handleJsonValidateErrorFuture(error: Seq[(JsPath, Seq[JsonValidationError])]): Future[Result] = {
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
      logger.error(s"speed #=${speedList.length} dir #=${directionList.length}")

    val speedDirections = speedList.zip(directionList)
    if (speedDirections.nonEmpty) {
      val sinSum = speedDirections.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
      val cosSum = speedDirections.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
      Some((sinSum, cosSum))
    } else
      None
  }

  def directionAvg(speedList: Seq[Double], directionList: Seq[Double]): Option[Double] =
    for ((sinSum, cosSum) <- getSinCosSum(speedList, directionList)) yield
      directionAvg(sinSum, cosSum)

  private def getValidSpeedDir(speedList: Seq[Option[Double]], directionList: Seq[Option[Double]]) = {
    val zip = speedList.zip(directionList).flatMap(pair => {
      for (x <- pair._1; y <- pair._2) yield
        (x, y)
    })
    (zip.map(_._1), zip.map(_._2))
  }

  def directionOptAvg(speedList: Seq[Option[Double]], directionList: Seq[Option[Double]]): Option[Double] = {
    val (speed, dir) = getValidSpeedDir(speedList, directionList)
    directionAvg(speed, dir)
  }

  def speedAvg(speedList: Seq[Double], directionList: Seq[Double]): Option[Double] =
    for ((sinSum, cosSum) <- getSinCosSum(speedList, directionList)) yield
      Math.sqrt(sinSum * sinSum + cosSum * cosSum) / Math.min(speedList.length, directionList.length)

  def speedOptAvg(speedList: Seq[Option[Double]], directionList: Seq[Option[Double]]): Option[Double] = {
    val (speed, dir) = getValidSpeedDir(speedList, directionList)
    speedAvg(speed, dir)
  }

  def speedDirectionAvg(speedList: List[Double], directionList: List[Double]): Option[(Double, Double)] = {
    if (speedList.length != directionList.length)
      logger.error(s"speed #=${speedList.length} dir #=${directionList.length}")

    val speedDirections = speedList.zip(directionList)
    if (speedDirections.nonEmpty) {
      val sinSum = speedDirections.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
      val cosSum = speedDirections.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
      Some((directionAvg(sinSum, cosSum), Math.sqrt(sinSum * sinSum + cosSum * cosSum)))
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

  def getNextTime(period: Int): DateTime = {
    val now = DateTime.now()
    val nextMin = (now.getMinuteOfHour / period + 1) * period
    val hour = (now.getHourOfDay + (nextMin / 60)) % 24
    val nextDay = (now.getHourOfDay + (nextMin / 60)) / 24

    now.withHourOfDay(hour).withMinuteOfHour(nextMin % 60).withSecondOfMinute(0).withMillisOfSecond(0) + nextDay.day
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

  def waitReadyResult[T](f: Future[T]): T = {
    import scala.concurrent.duration._
    import scala.util._

    val ret = Await.ready(f, Duration.Inf).value.get

    ret match {
      case Success(t) =>
        t
      case Failure(ex) =>
        logger.error(ex.getMessage, ex)
        throw ex
    }
  }

  implicit val localTimeReads: Reads[LocalTime] = new Reads[LocalTime] {
    override def reads(json: JsValue): JsResult[LocalTime] = {
      val str = json.as[String]
      val time = LocalTime.parse(str)
      JsSuccess(time)
    }
  }

  implicit val localTimeWrites: Writes[LocalTime] = new Writes[LocalTime] {
    override def writes(o: LocalTime): JsValue = JsString(o.toString())
  }
}

object EnumUtils {
  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) =>
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException => JsError(s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'")
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
