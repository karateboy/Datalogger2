package models

import com.github.nscala_time.time.Imports._
import controllers.DailyReport
import org.apache.poi.openxml4j.opc._
import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel._
import play.api.Logger

import java.io._
import java.nio.file.{Files, _}
import javax.inject._
import scala.math.BigDecimal.RoundingMode

@Singleton
class ExcelUtility @Inject()
(environment: play.api.Environment,monitorTypeOp: MonitorTypeOp, monitorStatusOp: MonitorStatusOp){
  val docRoot = environment.rootPath + "/report_template/"

  private def prepareTemplate(templateFile: String) = {
    val templatePath = Paths.get(docRoot + templateFile)
    val reportFilePath = Files.createTempFile("temp", ".xlsx");

    Files.copy(templatePath, reportFilePath, StandardCopyOption.REPLACE_EXISTING)

    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(reportFilePath.toAbsolutePath().toString()))
    val wb = new XSSFWorkbook(pkg);

    (reportFilePath, pkg, wb)
  }

  def finishExcel(reportFilePath: Path, pkg: OPCPackage, wb: XSSFWorkbook) = {
    val out = new FileOutputStream(reportFilePath.toAbsolutePath().toString());
    wb.write(out);
    out.close();
    pkg.close();

    new File(reportFilePath.toAbsolutePath().toString())
  }

  import controllers.Highchart._
  def exportChartData(chart: HighchartData, monitorTypes: Array[String], showSec:Boolean): File = {
    val precArray = monitorTypes.map { mt => monitorTypeOp.map(mt).prec }
    exportChartData(chart, precArray, showSec)
  }

  def exportChartData(chart: HighchartData, precArray: Array[Int], showSec:Boolean) = {
    val (reportFilePath, pkg, wb) = prepareTemplate("chart_export.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    val sheet = wb.getSheetAt(0)
    val headerRow = sheet.createRow(0)
    headerRow.createCell(0).setCellValue("時間")
    val calibrationStyle = wb.createCellStyle()
    calibrationStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex)
    calibrationStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    val manualStyle = wb.createCellStyle()
    manualStyle.setFillForegroundColor(IndexedColors.GOLD.getIndex)
    manualStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    val maintanceStyle = wb.createCellStyle()
    maintanceStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex)
    maintanceStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    var pos = 0
    for {
      col <- 1 to chart.series.length
      series = chart.series(col - 1)
    } {
      headerRow.createCell(pos+1).setCellValue(series.name)
      pos+=1
      if (series.statusList.nonEmpty) {
        headerRow.createCell(pos + 1).setCellValue("狀態碼")
        pos += 1
      }
    }

    val styles = precArray.map { prec =>
      val format_str:String = if(prec != 0)
        "0." + "0" * prec
      else
        "0"

      val style = wb.createCellStyle();
      style.setDataFormat(format.getFormat(format_str))
      style
    }

    // Categories data
    if (chart.xAxis.categories.isDefined) {
      val timeList = chart.xAxis.categories.get
      for (row <- timeList.zipWithIndex) {
        val rowNo = row._2 + 1
        val thisRow = sheet.createRow(rowNo)
        thisRow.createCell(0).setCellValue(row._1)

        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
        } {
          val cell = thisRow.createCell(col)
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(rowNo - 1)
          val statusOpt = series.statusList(rowNo -1)
          for (v <- pair._2 if !v.isNaN) {
            val d = BigDecimal(v).setScale(precArray(col - 1), RoundingMode.HALF_EVEN)
            cell.setCellValue(d.doubleValue())
          }
        }
      }
    } else {
      val rowMax = chart.series.map(s => s.data.length).max
      for (row <- 1 to rowMax) {
        val thisRow = sheet.createRow(row)
        val timeCell = thisRow.createCell(0)
        pos = 0
        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
         } {
          val cell = thisRow.createCell(pos +1)
          pos +=1
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(row - 1)
          if (col == 1) {
            val dt = new DateTime(pair._1)
            if(!showSec)
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm"))
            else
              timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm:ss"))
          }
          for (v <- pair._2 if !v.isNaN) {
            val d = BigDecimal(v).setScale(precArray(col - 1), RoundingMode.HALF_EVEN)
            cell.setCellValue(d.doubleValue())
            for(status <- series.statusList(row-1)){
              val tagInfo = MonitorStatus.getTagInfo(status)
              if(MonitorStatus.isCalbration(status)){
                cell.setCellStyle(calibrationStyle)
              }else if(tagInfo.statusType == StatusType.ManualValid ||
                tagInfo.statusType == StatusType.ManualInvalid) {
                cell.setCellStyle(manualStyle)
              } else if(MonitorStatus.isMaintenance(status))
                cell.setCellStyle(maintanceStyle)
            }
          }
          for(status <- series.statusList(row-1)){
            val statusCell = thisRow.createCell(pos + 1)
            pos += 1
            val monitorStatus = monitorStatusOp.map(status)
            statusCell.setCellValue(monitorStatus.desp)
            val tagInfo = MonitorStatus.getTagInfo(status)
            if(MonitorStatus.isCalbration(status)){
              statusCell.setCellStyle(calibrationStyle)
            }else if(tagInfo.statusType == StatusType.ManualValid ||
              tagInfo.statusType == StatusType.ManualInvalid) {
              statusCell.setCellStyle(manualStyle)
            } else if(MonitorStatus.isMaintenance(status))
              statusCell.setCellStyle(maintanceStyle)
          }
        }
      }
    }

    finishExcel(reportFilePath, pkg, wb)
  }

  def createStyle(prec:Int)(implicit wb: XSSFWorkbook) = {
    val format_str = if (prec != 0)
      "0." + "0" * prec
    else
      "0"

    val style = wb.createCellStyle();
    val format = wb.createDataFormat();
    val font = wb.createFont();
    font.setFontHeightInPoints(12);
    font.setFontName("正黑體");

    style.setFont(font)
    style.setDataFormat(format.getFormat(format_str))
    style.setBorderBottom(BorderStyle.THIN);
    style.setBottomBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderLeft(BorderStyle.THIN);
    style.setLeftBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderRight(BorderStyle.THIN);
    style.setRightBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderTop(BorderStyle.THIN);
    style.setTopBorderColor(IndexedColors.BLACK.getIndex());
    style
  }

  def getStyle(tag: String, normalStyle: XSSFCellStyle, abnormalStyles: Array[XSSFCellStyle]): XSSFCellStyle = {
    import MonitorStatus._
    MonitorStatus.getTagInfo(tag)
        if (isValid(tag))
          normalStyle
        else if (isCalbration(tag))
          abnormalStyles(0)
        else if (MonitorStatus.isMaintenance(tag))
          abnormalStyles(1)
        else if(MonitorStatus.isManual(tag))
          abnormalStyles(3)
        else
          abnormalStyles(2)
  }

/*
  def exportDailyReport(dateTime: DateTime, dailyReport: DailyReport) ={
    val (reportFilePath, pkg, wb) = prepareTemplate("dailyReport.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat()
    val sheet = wb.getSheetAt(0)
    val headerRow = sheet.createRow(0)
    for(i <- 0 to dailyReport.columnNames.size +1)
      headerRow.createCell(i)

    val fgColors = {
      val seqColors =
        for (col <- 1 to 5)
          yield sheet.getRow(1).getCell(col).getCellStyle.getFillForegroundXSSFColor
      seqColors.toArray
    }
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, dailyReport.columnNames.size))
    headerRow.getCell(0).setCellValue(s"監測日報 ${dateTime.toString("YYYY年MM月dd日")}")
    val row1 = sheet.getRow(1)
    for((mtName, idx) <- dailyReport.columnNames.zipWithIndex){
      row1.createCell(idx+1).setCellValue(mtName)
      for((value <- dailyReport.hourRows(idx).cellData(0).))
    }
    finishExcel(reportFilePath, pkg, wb)
  }

 */
}