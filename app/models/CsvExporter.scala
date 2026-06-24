package models

import com.github.nscala_time.time.Imports.DateTime
import play.api.{Configuration, Environment, Logger}

import java.io.FileWriter
import java.nio.file.Paths
import scala.io.Source


object CsvExporter {
  val logger = Logger(getClass)
  case class CsvExporterConfig(enable: Boolean, dir: String, mtList: Seq[String], filename: String, test:Boolean = false)

  def getConfig(configuration: Configuration): Option[CsvExporterConfig] = {
    for {config <- configuration.getOptional[Configuration]("csvExporter")
         enable <- config.getOptional[Boolean]("enable")
         dir <- config.getOptional[String]("dir")
         mtList <- config.getOptional[Seq[String]]("mtList")
         filename <- config.getOptional[String]("filename")
         test <- config.getOptional[Boolean]("test")
         } yield
      CsvExporterConfig(enable, dir, mtList, filename, test)
  }


  def exportCsv(now:DateTime, recordList:RecordList, config:CsvExporterConfig, environment: Environment): Unit = {

    try{
      val templateFile = environment.rootPath.toPath.resolve("report_template").resolve("exportCsv.csv")
      val src = Source.fromFile(templateFile.toFile)
      var content = src.mkString
      src.close()
      content = content.replace("[dateTime]", now.toString("YYYY-MM-dd HH:mm:ss.SSS"))
      val mtMap = recordList.mtMap
      config.mtList.foreach(mt=>{
        val mtValue = mtMap.get(mt).flatMap(_.value).getOrElse(0d)
        val valueStr = mtValue.toString
        content = content.replace(s"[${mt}_value]", valueStr)
      })
      val filename = s"${config.filename}${now.toString("YYYY_MM_dd_HH_mm_ss")}.csv"
      val output = Paths.get(config.dir).resolve(filename)
      val fileWriter = new FileWriter(output.toFile)
      fileWriter.write(content)
      fileWriter.close()
    } catch{
      case ex:Throwable=>
        logger.error("Failed to export CSV", ex)
    }

  }
}
