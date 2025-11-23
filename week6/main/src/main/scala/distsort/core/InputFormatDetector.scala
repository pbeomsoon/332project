package distsort.core

import java.io.{File, FileInputStream}

/**
 * Automatic format detection for input files
 *
 * This detector analyzes file content to determine if it's ASCII or Binary format.
 * It's essential for meeting the PDF requirement:
 * "Should work on both ASCII and binary input WITHOUT requiring an option"
 *
 * Detection Algorithm:
 * 1. Read first 1000 bytes of file
 * 2. Count ASCII printable characters
 * 3. Calculate ASCII ratio
 * 4. If ratio > 0.9 (90%) → ASCII, otherwise → Binary
 *
 * ASCII printable characters:
 * - 0x20-0x7E (space through tilde)
 * - 0x0A (newline \n)
 * - 0x0D (carriage return \r)
 *
 * Usage:
 * {{{
 *   val format = InputFormatDetector.detectFormat(inputFile)
 *   val reader = RecordReader.create(inputFile, format)
 * }}}
 */
object InputFormatDetector {

  /**
   * Sample size for format detection
   * Reading first 1000 bytes is enough to determine format
   */
  private val SAMPLE_SIZE = 1000

  /**
   * ASCII ratio threshold
   * If > 90% of sampled bytes are ASCII printable → ASCII format
   */
  private val ASCII_THRESHOLD = 0.9

  /**
   * Detect format of input file
   *
   * @param file Input file
   * @return DataFormat.Binary or DataFormat.Ascii
   */
  def detectFormat(file: File): DataFormat = {
    require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
    require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

    if (file.length() == 0) {
      // Empty file defaults to Binary
      return DataFormat.Binary
    }

    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)

    val bytesRead = try {
      inputStream.read(buffer)
    } finally {
      inputStream.close()
    }

    if (bytesRead <= 0) {
      // Could not read any bytes (should not happen after length check)
      return DataFormat.Binary
    }

    // Count ASCII printable characters
    val asciiCount = buffer.take(bytesRead).count(isAsciiPrintable)
    val asciiRatio = asciiCount.toDouble / bytesRead

    if (asciiRatio > ASCII_THRESHOLD) {
      DataFormat.Ascii
    } else {
      DataFormat.Binary
    }
  }

  /**
   * Check if a byte is ASCII printable
   *
   * ASCII printable characters:
   * - 0x20-0x7E (space through tilde): ' ' to '~'
   * - 0x0A (newline): \n
   * - 0x0D (carriage return): \r
   *
   * @param b Byte to check
   * @return true if ASCII printable
   */
  private def isAsciiPrintable(b: Byte): Boolean = {
    (b >= 0x20 && b <= 0x7E) ||  // Printable ASCII
    b == 0x0A ||                  // Newline
    b == 0x0D                     // Carriage return
  }

  /**
   * Detect format and return debug info
   *
   * Useful for debugging format detection issues.
   *
   * @param file Input file
   * @return (format, asciiRatio, bytesRead)
   */
  def detectFormatWithDebug(file: File): (DataFormat, Double, Int) = {
    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)

    val bytesRead = try {
      inputStream.read(buffer)
    } finally {
      inputStream.close()
    }

    if (bytesRead <= 0) {
      return (DataFormat.Binary, 0.0, 0)
    }

    val asciiCount = buffer.take(bytesRead).count(isAsciiPrintable)
    val asciiRatio = asciiCount.toDouble / bytesRead
    val format = if (asciiRatio > ASCII_THRESHOLD) DataFormat.Ascii else DataFormat.Binary

    (format, asciiRatio, bytesRead)
  }
}
