package distsort.core

import java.io._
import com.typesafe.scalalogging.LazyLogging

/**
 * Abstract trait for writing records to files
 */
trait RecordWriter {
  def writeRecord(record: Record): Unit
  def flush(): Unit
  def close(): Unit
  def getFile: File
}

/**
 * Binary format record writer
 */
class BinaryRecordWriter(file: File) extends RecordWriter with LazyLogging {
  // Create parent directories if needed
  Option(file.getParentFile).foreach(_.mkdirs())

  private val outputStream = new BufferedOutputStream(
    new FileOutputStream(file),
    1024 * 1024 // 1MB buffer
  )

  override def writeRecord(record: Record): Unit = {
    outputStream.write(record.toBytes)
  }

  override def flush(): Unit = outputStream.flush()

  override def close(): Unit = {
    flush()
    outputStream.close()
    logger.debug(s"Closed BinaryRecordWriter for ${file.getName}")
  }

  override def getFile: File = file
}

/**
 * ASCII format record writer (gensort -a format)
 */
class AsciiRecordWriter(file: File) extends RecordWriter with LazyLogging {
  // Create parent directories if needed
  Option(file.getParentFile).foreach(_.mkdirs())

  private val writer = new BufferedWriter(
    new FileWriter(file),
    1024 * 1024 // 1MB buffer
  )

  override def writeRecord(record: Record): Unit = {
    val keyHex = record.key.map("%02X".format(_)).mkString
    val valueHex = record.value.map("%02X".format(_)).mkString
    writer.write(s"$keyHex $valueHex\n")
  }

  override def flush(): Unit = writer.flush()

  override def close(): Unit = {
    flush()
    writer.close()
    logger.debug(s"Closed AsciiRecordWriter for ${file.getName}")
  }

  override def getFile: File = file
}

/**
 * Factory for creating record writers
 */
object RecordWriter extends LazyLogging {

  /**
   * Create a writer for the specified format
   */
  def create(file: File, format: DataFormat): RecordWriter = {
    format match {
      case DataFormat.Binary =>
        logger.debug(s"Creating BinaryRecordWriter for ${file.getName}")
        new BinaryRecordWriter(file)

      case DataFormat.Ascii =>
        logger.debug(s"Creating AsciiRecordWriter for ${file.getName}")
        new AsciiRecordWriter(file)
    }
  }

  /**
   * Create a writer with auto-detection based on file extension
   */
  def create(file: File): RecordWriter = {
    val format = if (file.getName.endsWith(".txt") || file.getName.endsWith(".ascii")) {
      DataFormat.Ascii
    } else {
      DataFormat.Binary
    }
    create(file, format)
  }
}