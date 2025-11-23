package distsort.core

import java.io.{BufferedInputStream, File, FileInputStream}

/**
 * Binary format reader for 100-byte records
 *
 * This reader is designed for the gensort binary format:
 * - Each record is exactly 100 bytes
 * - No line endings or separators
 * - Records are read sequentially
 *
 * Usage:
 * {{{
 *   val reader = new BinaryRecordReader(inputFile)
 *   Iterator.continually(reader.readRecord())
 *     .takeWhile(_.isDefined)
 *     .map(_.get)
 *     .foreach { record =>
 *       // Process record
 *     }
 *   reader.close()
 * }}}
 *
 * @param file Input file containing binary records
 */
class BinaryRecordReader(file: File) extends RecordReader {
  require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
  require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

  /**
   * Buffered input stream for efficient reading
   * Buffer size: 1MB (can read ~10,000 records at once)
   */
  private val inputStream = new BufferedInputStream(
    new FileInputStream(file),
    1024 * 1024 // 1MB buffer
  )

  /**
   * Reusable buffer for reading 100-byte records
   * This avoids allocating a new array for each record
   */
  private val buffer = new Array[Byte](100)

  /**
   * Read next record from file
   *
   * Returns:
   * - Some(Record) if a complete 100-byte record is available
   * - None if EOF is reached
   * - None if an incomplete record is found (< 100 bytes remaining)
   *
   * Note: The method reads exactly 100 bytes. If the file contains
   * less than 100 bytes, it's considered invalid/incomplete.
   *
   * @return Option[Record] - Some(record) if available, None if EOF or incomplete
   */
  def readRecord(): Option[Record] = {
    val bytesRead = inputStream.read(buffer)

    if (bytesRead == -1) {
      // EOF reached
      None
    } else if (bytesRead < 100) {
      // Incomplete record (file is corrupted or truncated)
      None
    } else {
      // Complete record read
      // IMPORTANT: Must clone buffer because Record stores the array
      // If we don't clone, all records will share the same array!
      Some(Record.fromBytes(buffer.clone()))
    }
  }

  /**
   * Close the input stream and release resources
   *
   * IMPORTANT: Always call this method when done reading!
   * Best practice: Use try-finally or try-with-resources pattern
   *
   * Example:
   * {{{
   *   val reader = new BinaryRecordReader(file)
   *   try {
   *     // Read records...
   *   } finally {
   *     reader.close()
   *   }
   * }}}
   */
  def close(): Unit = {
    inputStream.close()
  }

  /**
   * Get the file being read
   * Useful for debugging and logging
   */
  def getFile: File = file

  /**
   * Check if the file can be read
   * Returns true if file exists and is readable
   */
  def canRead: Boolean = file.canRead
}

object BinaryRecordReader {
  /**
   * Read all records from a file
   *
   * Convenience method that reads all records and closes the reader.
   * Use this for small files that fit in memory.
   *
   * For large files, use the reader directly with Iterator.
   *
   * @param file Input file
   * @return Sequence of all records in the file
   */
  def readAll(file: File): Seq[Record] = {
    val reader = new BinaryRecordReader(file)
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
   * Count records in a file without loading them into memory
   *
   * This is more efficient than readAll().length for large files.
   *
   * @param file Input file
   * @return Number of complete records in the file
   */
  def countRecords(file: File): Long = {
    val reader = new BinaryRecordReader(file)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .length
    } finally {
      reader.close()
    }
  }
}
