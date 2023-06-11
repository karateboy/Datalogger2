package models

import play.api.Logger

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.collection.JavaConverters.asScalaBufferConverter
object ReaderHelper {
  def getParsedFileList(dir: String, filename: String = "parsed.list"): Seq[String] = {
    val parsedFileName = s"$dir/$filename"
    try {
      Files.readAllLines(Paths.get(parsedFileName), StandardCharsets.UTF_8).asScala
    } catch {
      case ex: Throwable =>
        Logger.info(s"Cannot open $parsedFileName")
        Seq.empty[String]
    }
  }

  def removeParsedFileList(dir: String, filename: String = "parsed.list"): Unit = {
    val parsedFileName = s"$dir/$filename"
    try {
      Files.delete(Paths.get(parsedFileName))
    } catch {
      case ex: Throwable =>
        Logger.error(s"Cannot delete $parsedFileName", ex)
    }
  }

  def appendToParsedFileList(dir: String, filePath: String, filename: String = "parsed.list"): Unit = {
    try {
      Files.write(Paths.get(s"$dir/$filename"), (filePath + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        Logger.warn(ex.getMessage)
    }
  }

  def setArchive(f: File): Unit = {
    import java.nio.file._
    import java.nio.file.attribute.DosFileAttributeView

    val path = Paths.get(f.getAbsolutePath)
    val dfav = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
    dfav.setArchive(true)
  }

  def isArchive(f: File): Boolean = {
    import java.nio.file._
    import java.nio.file.attribute.DosFileAttributes

    val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
    dfa.isArchive
  }
}
