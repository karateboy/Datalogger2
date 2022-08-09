package models

import com.serotonin.modbus4j.serial.SerialPortWrapper
import jssc.SerialPort
import play.api._

import java.io.{InputStream, OutputStream}

case class SerialComm(port: SerialPort, is: SerialInputStream, os: SerialOutputStream) {
  var clearBuffer: Boolean = false
  var readBuffer = Array.empty[Byte]

  def getLineWithTimeout(timeout: Int): List[String] = handleWithTimeout(getLine)(timeout)

  def handleWithTimeout(readFunction: () => List[String])(timeout: Int): List[String] = {
    import com.github.nscala_time.time.Imports._
    var strList: List[String] = readFunction()
    val startTime = DateTime.now
    while (strList.length == 0) {
      Thread.sleep(100)
      val elapsedTime = new Duration(startTime, DateTime.now)
      if (elapsedTime.getStandardSeconds > timeout) {
        clearBuffer = true
        throw new Exception("Read timeout!")
      }
      strList = readFunction()
    }
    strList
  }

  private def getLine(): List[String] = {
    def splitLine(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\n'.toByte)
      if (idx == -1) {
        val cr_idx = buf.indexOf('\r'.toByte)
        if (cr_idx == -1) {
          Nil
        } else {
          val (a, rest) = buf.splitAt(cr_idx + 1)
          readBuffer = rest
          new String(a).trim() :: splitLine(rest)
        }
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        readBuffer = rest
        new String(a).trim() :: splitLine(rest)
      }
    }

    readBuffer = readBuffer ++ readPort
    splitLine(readBuffer)
  }

  def getLine3WithTime(timeout: Int): List[String] = handleWithTimeout(getLine3)(timeout)

  def getLine3(): List[String] = {
    def splitLine(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\r'.toByte)
      if (idx == -1) {
        Nil
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        readBuffer = rest
        new String(a) :: splitLine(rest)
      }
    }

    readBuffer = readBuffer ++ readPort

    splitLine(readBuffer)
  }

  def getMessageByCrWithTimeout(timeout: Int): List[String] = handleWithTimeout(getMessageUntilCR)(timeout)

  def getMessageByLfWithTimeout(timeout: Int): List[String] = handleWithTimeout(getMessageUntilLF)(timeout)

  private def getMessageUntilLF() = {
    def splitMessage(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\n')
      if (idx == -1) {
        Nil
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        readBuffer = rest
        new String(a) :: splitMessage(rest)
      }
    }

    readBuffer = readBuffer ++ readPort

    splitMessage(readBuffer)
  }

  def getAkResponse(timeout: Int = 2): String = {
    import com.github.nscala_time.time.Imports._
    def readCom() = {
      readBuffer = readBuffer ++ readPort
      readBuffer.indexOf('\n') >= 0;
    }

    val startTime = DateTime.now
    while (!readCom()) {
      Thread.sleep(100)
      val elapsedTime = new Duration(startTime, DateTime.now)
      if (elapsedTime.getStandardSeconds > timeout) {
        clearBuffer = true
        throw new Exception("Read timeout!")
      }
    }

    val idx = readBuffer.indexOf('\n'.toByte)
    val (a, rest) = readBuffer.splitAt(idx + 1)
    readBuffer = rest
    new String(a)
  }

  def getMessageUntilCrWithTimeout(timeout: Int): List[String] = handleWithTimeout(getMessageUntilCR)(timeout)

  private def getMessageUntilCR() = {
    def splitMessage(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\r')
      if (idx == -1) {
        Nil
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        readBuffer = rest
        new String(a) :: splitMessage(rest)
      }
    }

    readBuffer = readBuffer ++ readPort

    splitMessage(readBuffer)
  }

  def close = {
    Logger.info(s"port is closed")
    is.close
    os.close
    port.closePort()
    readBuffer = Array.empty[Byte]
  }

  def purgeBuffer() = {
    port.purgePort(SerialPort.PURGE_RXCLEAR)
    readBuffer = Array.empty[Byte]
  }

  private def getMessageUntilEtx(): List[String] = {
    def splitMessage(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf(3.toByte)
      if (idx == -1) {
        Nil
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        readBuffer = rest
        new String(a) :: splitMessage(rest)
      }
    }

    readBuffer = readBuffer ++ readPort

    splitMessage(readBuffer)
  }

  def readPort: Array[Byte] = {
    if (clearBuffer) {
      port.readBytes()
      readBuffer = Array.empty[Byte]
      clearBuffer = false
      Array.empty[Byte]
    } else {
      val ret = port.readBytes()
      if (ret != null)
        ret
      else
        Array.empty[Byte]
    }
  }
}

object SerialComm {
  def open(n: Int): SerialComm = open(n, SerialPort.BAUDRATE_9600)

  def open(n: Int, baudRate: Int): SerialComm = {
    val port = new SerialPort(s"COM${n}")
    var success = false
    var errorCount = 0
    do {
      try {
        if (!port.openPort())
          throw new Exception(s"Failed to open COM$n")
        success = true
      } catch {
        case ex: Throwable =>
          errorCount = errorCount + 1
          Thread.sleep(100)
          if (errorCount >= 5)
            throw ex
      }
    } while (!success && errorCount < 5)


    port.setParams(baudRate,
      SerialPort.DATABITS_8,
      SerialPort.STOPBITS_1,
      SerialPort.PARITY_NONE); //Set params. Also you can set params by this string: serialPort.setParams(9600, 8, 1, 0);

    port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE)

    val is = new SerialInputStream(port)
    val os = new SerialOutputStream(port)
    SerialComm(port, is, os)
  }

  def open(n: Int, baudRate: Int,
           dataBit: Int = SerialPort.DATABITS_8,
           stopBits: Int = SerialPort.STOPBITS_1,
           parity: Int = SerialPort.PARITY_NONE): SerialComm = {
    val port = new SerialPort(s"COM${n}")
    var success = false
    var errorCount = 0
    do {
      try {
        if (!port.openPort())
          throw new Exception(s"Failed to open COM$n")
        success = true
      } catch {
        case ex: Throwable =>
          errorCount = errorCount + 1
          Thread.sleep(100)
          if (errorCount >= 5)
            throw ex
      }
    } while (!success && errorCount < 5)


    port.setParams(baudRate,
      dataBit,
      stopBits,
      parity); //Set params. Also you can set params by this string: serialPort.setParams(9600, 8, 1, 0);

    port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE)

    val is = new SerialInputStream(port)
    val os = new SerialOutputStream(port)
    SerialComm(port, is, os)
  }

  def close(sc: SerialComm) {
    sc.close
  }
}

class SerialOutputStream(port: SerialPort) extends OutputStream {
  override def write(b: Int) = {
    port.writeByte(b.toByte)
  }

  override def write(b: Array[Byte]): Unit = {
    write(b, 0, b.length)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    val actual = b.drop(off).take(len)
    port.writeBytes(actual)
  }
}

class SerialInputStream(serialPort: jssc.SerialPort) extends InputStream {
  override def read() = {
    val retArray = serialPort.readBytes(1)
    if (retArray.length == 0)
      -1
    else
      retArray(0)
  }

  override def read(b: Array[Byte]): Int = {
    read(b, 0, b.length)
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    var actualLen = if (b.length < off + len)
      b.length - off
    else
      len

    if (actualLen > serialPort.getInputBufferBytesCount)
      actualLen = serialPort.getInputBufferBytesCount

    val ret = serialPort.readBytes(actualLen)
    ret.copyToArray(b, off)

    actualLen
  }

  override def available(): Int = {
    val ret = serialPort.getInputBufferBytesCount
    ret
  }
}

class SerialRTU(n: Int, baudRate: Int) extends SerialPortWrapper {

  var serialCommOpt: Option[SerialComm] = None

  override def close(): Unit = {
    Logger.info(s"SerialRTU COM${n} close")

    for (serialComm <- serialCommOpt)
      serialComm.close
  }

  override def open(): Unit = {
    Logger.info(s"SerialRTU COM${n} open")
    serialCommOpt = Some(SerialComm.open(n, baudRate))
  }

  override def getInputStream: InputStream = serialCommOpt.get.is

  override def getOutputStream: OutputStream = serialCommOpt.get.os

  override def getBaudRate: Int = baudRate

  override def getFlowControlIn: Int = 0

  override def getFlowControlOut: Int = 0

  override def getDataBits: Int = 8

  override def getStopBits: Int = 1

  override def getParity: Int = 0
}
