package controllers
import play.api._
import play.api.mvc._
import play.api.Logger
import models._
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import play.api.Play.current

object Highchart {
  case class XAxis(categories: Option[Seq[String]], gridLineWidth: Option[Int]=None, tickInterval:Option[Int]=None)
  case class AxisLineLabel(align: String, text: String)
  case class AxisLine(color: String, width: Int, value: Double, label: Option[AxisLineLabel])
  case class AxisTitle(text: Option[Option[String]])
  case class Tooltip(valueDecimals:Int)
  case class YAxis(labels: Option[String], title: AxisTitle, plotLines: Option[Seq[AxisLine]], opposite:Boolean=false, 
      floor:Option[Int]=None, ceiling:Option[Int]=None, min:Option[Int]=None, max:Option[Int]=None, tickInterval:Option[Int]=None, 
      gridLineWidth:Option[Int]=None, gridLineColor:Option[String]=None)
      
  case class seqData(name: String, data: Seq[(Long, Option[Double])], yAxis:Int=0,
                     chartType:Option[String]=None, tooltip: Tooltip= Tooltip(2), statusList: Seq[Option[String]] =Seq.empty[Option[String]])
  case class HighchartData(chart: Map[String, String],
                           title: Map[String, String],
                           xAxis: XAxis,
                           yAxis: Seq[YAxis],
                           series: Seq[seqData],
                           downloadFileName: Option[String]=None)

  case class FrequencyTab(header:Seq[String], body:Seq[Seq[String]], footer:Seq[String])                         
  case class WindRoseReport(chart:HighchartData, table:FrequencyTab)
  implicit val xaWrite = Json.writes[XAxis]
  implicit val axisLineLabelWrite = Json.writes[AxisLineLabel]
  implicit val axisLineWrite = Json.writes[AxisLine]
  implicit val axisTitleWrite = Json.writes[AxisTitle]
  implicit val tooltipWrite = Json.writes[Tooltip]
  implicit val yaWrite = Json.writes[YAxis]
  type LOD = (Long, Option[Double])

  implicit val lof:Writes[LOD] = new Writes[(Long, Option[Double])] {
    override def writes(o: (Long, Option[Double])): JsValue = {
      try{
        if(o._2.nonEmpty) {
          if(o._2.get.isNaN)
            JsArray(Seq(JsNumber(o._1), JsNumber(0)))
          else
            JsArray(Seq(JsNumber(o._1), JsNumber(o._2.get)))
        } else
          JsArray(Seq(JsNumber(o._1), JsNull))
      }catch{
        case  ex: java.lang.NumberFormatException =>
          Logger.error(s"(${o._1}, ${o._2} ")
          throw ex
      }
    }
  }

  implicit val seqDataWrite:Writes[seqData] = (
    (__ \ "name").write[String] and
    (__ \ "data").write[Seq[(Long, Option[Double])]] and
    (__ \ "yAxis").write[Int] and
    (__ \ "type").write[Option[String]] and
      (__ \ "tooltip").write[Tooltip] and
      (__ \ "statusList").write[Seq[Option[String]]]
  )(unlift(seqData.unapply))
  implicit val hcWrite = Json.writes[HighchartData]
  implicit val feqWrite = Json.writes[FrequencyTab]
  implicit val wrWrite = Json.writes[WindRoseReport]

  case class Title(enabled:Boolean, text:String)
  case class ScatterAxis(title:Title, plotLines: Option[Seq[AxisLine]], startOnTick:Boolean = true, endOnTick:Boolean = true, showLastLabel:Boolean = true)
  case class ScatterSeries(name:String, data:Seq[Seq[Double]])
  case class ScatterChart(chart: Map[String, String],
                          title: Map[String, String],
                          xAxis: ScatterAxis,
                          yAxis: ScatterAxis,
                          series: Seq[ScatterSeries],
                          downloadFileName: Option[String]=None)
  implicit val titleWrite = Json.writes[Title]
  implicit val scatterAxisWrite = Json.writes[ScatterAxis]
  implicit val scatterSeriesWrite = Json.writes[ScatterSeries]
  implicit val scatterChartWrite = Json.writes[ScatterChart]
}