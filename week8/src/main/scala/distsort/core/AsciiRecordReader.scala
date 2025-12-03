package distsort.core

import java.io.{BufferedReader, File, FileReader}

/**
 * ASCII format reader for hex-encoded 100-byte records
 *
 * This reader is designed for the gensort ASCII format (gensort -a):
 * - Each line represents one record
 * - Format: [20 hex chars (key)] [1 space] [180 hex chars (value)] [\n]
 * - Total: 201 characters per line (+ newline)
 * - Hex characters can be uppercase or lowercase
 *
 * Example line:
 * {{{
 *   48656C6C6F576F726C6400 AABBCCDDEEFF...
 *   |<----- 20 chars ----->| |<-- 180 chars -->|
 *   = 10 bytes key          = 90 bytes value
 * }}}
 *
 * Usage:
 * {{{
 *   val reader = new AsciiRecordReader(inputFile)
 *   Iterator.continually(reader.readRecord())
 *     .takeWhile(_.isDefined)
 *     .map(_.get)
 *     .foreach { record =>
 *       // Process record
 *     }
 *   reader.close()
 * }}}
 *
 * @param file Input file containing ASCII records
 */
class AsciiRecordReader(file: File) extends RecordReader {
  require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
  require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

  /**
   * Buffered reader for line-based reading
   */
  private val bufferedReader = new BufferedReader(
    new FileReader(file),
    1024 * 1024 // 1MB buffer
  )

  /**
   * Read next record from file
   *
   * Returns:
   * - Some(Record) if a valid ASCII record is parsed
   * - None if EOF is reached
   * - None if an invalid line is found
   *
   * Validation:
   * - Line must be exactly 201 characters (20 + 1 + 180)
   * - Character at position 20 must be a space
   * - All other characters must be valid hex [0-9A-Fa-f]
   *
   * @return Option[Record] - Some(record) if valid, None if EOF or invalid
   */
  override def readRecord(): Option[Record] = {
    val line = bufferedReader.readLine()

    if (line == null) {
      // EOF reached
      None
    } else if (line.length == 201 && line.charAt(20) == ' ') {
      // Hex-encoded ASCII format: 20 hex + 1 space + 180 hex = 201 chars
      val keyHex = line.substring(0, 20)
      val valueHex = line.substring(21, 201)

      try {
        val key = hexToBytes(keyHex)
        val value = hexToBytes(valueHex)

        if (key.length != 10 || value.length != 90) {
          None
        } else {
          Some(Record(key, value))
        }
      } catch {
        case _: Exception => None
      }
    } else if (line.length >= 98 && line.length <= 100) {
      // Raw ASCII format (gensort default): 10 key + 2 spaces + 86 data = 98-100 chars
      // This format is commonly used in cluster environments
      try {
        val bytes = line.getBytes("ISO-8859-1")
        val key = bytes.slice(0, 10)
        // Pad value to 90 bytes if needed (line may be 98-100 chars)
        val valueBytes = bytes.slice(10, math.min(100, bytes.length))
        val value = if (valueBytes.length < 90) {
          valueBytes ++ Array.fill(90 - valueBytes.length)(0.toByte)
        } else {
          valueBytes.slice(0, 90)
        }
        Some(Record(key, value))
      } catch {
        case _: Exception => None
      }
    } else {
      // Unknown format
      None
    }
  }

  /**
   * Convert hex string to byte array
   *
   * @param hex Hex string (even length)
   * @return Byte array
   * @throws NumberFormatException if invalid hex
   */
  private def hexToBytes(hex: String): Array[Byte] = {
    require(hex.length % 2 == 0, "Hex string must have even length")

    val bytes = new Array[Byte](hex.length / 2)
    var i = 0
    while (i < hex.length) {
      val highNibble = hexCharToInt(hex.charAt(i))
      val lowNibble = hexCharToInt(hex.charAt(i + 1))
      bytes(i / 2) = ((highNibble << 4) | lowNibble).toByte
      i += 2
    }
    bytes
  }

  /**
   * Convert hex character to integer
   *
   * @param c Hex character [0-9A-Fa-f]
   * @return Integer value [0-15]
   * @throws NumberFormatException if invalid hex character
   */
  private def hexCharToInt(c: Char): Int = c match {
    case c if c >= '0' && c <= '9' => c - '0'
    case c if c >= 'A' && c <= 'F' => c - 'A' + 10
    case c if c >= 'a' && c <= 'f' => c - 'a' + 10
    case _ => throw new NumberFormatException(s"Invalid hex character: $c")
  }

  /**
   * Close the reader and release resources
   *
   * IMPORTANT: Always call this method when done!
   */
  override def close(): Unit = {
    bufferedReader.close()
  }

  /**
   * Get the file being read
   */
  override def getFile: File = file

  /**
   * Check if the file can be read
   */
  override def canRead: Boolean = file.canRead
}

object AsciiRecordReader {
  /**
   * Read all records from an ASCII file
   *
   * @param file Input file
   * @return Sequence of all records
   */
  def readAll(file: File): Seq[Record] = {
    val reader = new AsciiRecordReader(file)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
    } finally {
      reader.close()
    }
  }

  /**
   * Count records in an ASCII file
   *
   * @param file Input file
   * @return Number of valid records
   */
  def countRecords(file: File): Long = {
    val reader = new AsciiRecordReader(file)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .length
    } finally {
      reader.close()
    }
  }
}
