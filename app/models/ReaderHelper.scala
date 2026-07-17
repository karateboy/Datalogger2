package models

import play.api.Logger

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.collection.JavaConverters.asScalaBufferConverter
object ReaderHelper {
  val logger: Logger = Logger(this.getClass)
  def getParsedFileList(dir: String, archiveFile: String = "parsed.list"): Seq[String] = {
    val parsedFileName = s"$dir/$archiveFile"
    getParsedFileList(Paths.get(parsedFileName))
  }

  def getParsedFileList(archiveFile: Path): Seq[String] = {
    try {
      Files.readAllLines(archiveFile, StandardCharsets.UTF_8).asScala
    } catch {
      case ex: Throwable =>
        logger.info(s"Cannot open $archiveFile")
        Seq.empty[String]
    }
  }

  def removeParsedFileList(dir: String, filename: String = "parsed.list"): Unit = {
    val parsedFileName = s"$dir/$filename"
    try {
      Files.delete(Paths.get(parsedFileName))
    } catch {
      case ex: Throwable =>
        logger.error(s"Cannot delete $parsedFileName", ex)
    }
  }

  def removeParsedFileList(archive:Path): Unit = {
    try {
      Files.delete(archive)
    } catch {
      case ex: Throwable =>
        logger.error(s"Cannot delete $archive", ex)
    }
  }

  def appendToParsedFileList(dir: String, filePath: String, archiveFile: String = "parsed.list"): Unit = {
    appendToParsedFileList(filePath, Paths.get(s"$dir/$archiveFile"))
  }

  def appendToParsedFileList(parsedPath: String, archive: Path): Unit = {
    try {
      Files.write(archive, (parsedPath + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        logger.warn(ex.getMessage)
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
