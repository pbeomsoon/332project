package distsort.core

import java.io.File

/**
 * Data format types
 *
 * Binary: Raw 100-byte records (gensort -b format)
 * Ascii: Hex-encoded records (gensort -a format)
 */
sealed trait DataFormat

object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

/**
 * Abstract interface for reading records from different formats
 *
 * This trait provides a common interface for both Binary and ASCII record readers.
 * It's essential for the auto-detection mechanism in Phase 1-2 (Sampling/Sorting).
 *
 * Phase dependency:
 * - Phase 1 (Sampling): Uses InputFormatDetector to choose reader
 * - Phase 2 (Sort): Uses InputFormatDetector for each input file
 *
 * Implementations:
 * - BinaryRecordReader: Reads raw 100-byte records
 * - AsciiRecordReader: Reads hex-encoded records
 */
trait RecordReader {
  /**
   * Read next record from file
   *
   * @return Some(Record) if available, None if EOF or error
   */
  def readRecord(): Option[Record]

  /**
   * Close the reader and release resources
   *
   * IMPORTANT: Always call this method when done!
   * Best practice: Use try-finally block
   */
  def close(): Unit

  /**
   * Get the file being read
   * Useful for debugging and logging
   */
  def getFile: File

  /**
   * Check if the file can be read
   */
  def canRead: Boolean
}

/**
 * Factory for creating format-specific readers
 *
 * This factory is used by:
 * 1. InputFormatDetector: After detecting format, creates appropriate reader
 * 2. Manual creation: When format is known beforehand
 *
 * Example usage in Sampling phase:
 * {{{
 *   val format = InputFormatDetector.detectFormat(file)
 *   val reader = RecordReader.create(file, format)
 *   val samples = extractSamples(reader)
 *   reader.close()
 * }}}
 */
object RecordReader {
  /**
   * Create a reader for the given file and format
   *
   * @param file Input file
   * @param format Data format (Binary or Ascii)
   * @return Appropriate reader instance
   */
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii => new AsciiRecordReader(file)
  }

  /**
   * Create a reader with auto-detected format
   *
   * This is the primary method used in Phase 1-2.
   * It automatically detects the format and creates the appropriate reader.
   *
   * @param file Input file
   * @return Appropriate reader instance based on auto-detected format
   */
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    create(file, format)
  }

  /**
   * Read all records from a file (auto-detect format)
   *
   * Convenience method for small files.
   *
   * @param file Input file
   * @return Sequence of all records
   */
  def readAll(file: File): Seq[Record] = {
    val reader = create(file)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
    } finally {
      reader.close()
    }
  }
}
