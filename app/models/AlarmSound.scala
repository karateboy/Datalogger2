package models

import play.api.{Environment, Logger}

import java.nio.file
import javax.inject.{Inject, Singleton}
import javax.sound.sampled._

@Singleton
class AlarmSound @Inject()(environment: Environment) extends LineListener {
  override def update(event: LineEvent): Unit = {
    if (LineEvent.Type.START eq event.getType)
      logger.info("Playback started.")
    else if (LineEvent.Type.STOP eq event.getType) {
      audioClip.close()
      audioStream.close()
      logger.info("Playback completed.")
    }
  }

  val logger = Logger(getClass)

  private def getAudioClip: (Clip, AudioInputStream) = {
    try {
      val soundFile: file.Path = environment.rootPath.toPath.resolve("report_template").resolve("alert.wav")
      val audioStream = AudioSystem.getAudioInputStream(soundFile.toFile)
      val audioClip = AudioSystem.getClip()
      audioClip.open(audioStream)
      audioClip.loop(Clip.LOOP_CONTINUOUSLY)
      audioClip.addLineListener(this)
      (audioClip, audioStream)
    } catch {
      case ex: Throwable =>
        logger.error("failed to init", ex)
        throw ex
    }
  }

  private var audioClip: Clip = _
  private var audioStream: AudioInputStream = _

  logger.info("AlarmSound init()")

  def play(): Unit = {
    val (clip, stream) = getAudioClip
    audioClip = clip
    audioStream = stream
    audioClip.start()
  }

  def stop(): Unit = {
    audioClip.stop()
  }

  override def finalize(): Unit = {
    audioClip.close()
    audioStream.close()
    super.finalize()
  }
}
