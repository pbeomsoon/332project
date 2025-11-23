package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.{FileOutputStream, PrintWriter}

class InputFormatDetectorSpec extends AnyFlatSpec with Matchers {

  /**
   * Helper: Create binary file
   */
  def createBinaryFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-binary", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      records.foreach { record =>
        fos.write(record.toBytes)
      }
    } finally {
      fos.close()
    }
    tempFile
  }

  /**
   * Helper: Create ASCII file
   */
  def createAsciiFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-ascii", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    try {
      records.foreach { record =>
        val keyHex = record.key.map("%02X".format(_)).mkString
        val valueHex = record.value.map("%02X".format(_)).mkString
        writer.println(s"$keyHex $valueHex")
      }
    } finally {
      writer.close()
    }
    tempFile
  }

  "InputFormatDetector" should "detect Binary format" in {
    // Given: Binary file with random bytes
    val records = (1 to 10).map { i =>
      Record(
        key = Array.fill[Byte](10)((i * 3).toByte),
        value = Array.fill[Byte](90)((i * 7).toByte)
      )
    }
    val binaryFile = createBinaryFile(records)

    // When: Detect format
    val format = InputFormatDetector.detectFormat(binaryFile.toFile)

    // Then: Should be Binary
    format shouldBe DataFormat.Binary

    Files.delete(binaryFile)
  }

  it should "detect ASCII format" in {
    // Given: ASCII file with hex-encoded records
    val records = (1 to 10).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val asciiFile = createAsciiFile(records)

    // When: Detect format
    val format = InputFormatDetector.detectFormat(asciiFile.toFile)

    // Then: Should be ASCII
    format shouldBe DataFormat.Ascii

    Files.delete(asciiFile)
  }

  it should "default to Binary for empty file" in {
    // Given: Empty file
    val emptyFile = Files.createTempFile("empty", ".dat")

    // When: Detect format
    val format = InputFormatDetector.detectFormat(emptyFile.toFile)

    // Then: Should default to Binary
    format shouldBe DataFormat.Binary

    Files.delete(emptyFile)
  }

  it should "handle small files (< 1000 bytes)" in {
    // Given: Small binary file (1 record = 100 bytes)
    val records = Seq(Record(
      key = Array.fill[Byte](10)(0xFF.toByte),
      value = Array.fill[Byte](90)(0x00.toByte)
    ))
    val smallFile = createBinaryFile(records)

    // When: Detect format
    val format = InputFormatDetector.detectFormat(smallFile.toFile)

    // Then: Should detect Binary
    format shouldBe DataFormat.Binary

    Files.delete(smallFile)
  }

  it should "use majority rule for mixed content" in {
    // Given: File with mostly ASCII printable chars but some binary
    val tempFile = Files.createTempFile("mixed", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      // 950 ASCII chars + 50 binary chars = 95% ASCII
      val asciiChars = "A" * 950
      val binaryChars = Array.fill[Byte](50)(0xFF.toByte)
      fos.write(asciiChars.getBytes)
      fos.write(binaryChars)
    } finally {
      fos.close()
    }

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should detect ASCII (> 90% threshold)
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "detect Binary for low ASCII ratio" in {
    // Given: File with mostly binary data
    val tempFile = Files.createTempFile("mostly-binary", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      // 100 ASCII chars + 900 binary chars = 10% ASCII
      val asciiChars = "A" * 100
      val binaryChars = Array.fill[Byte](900)(0xFF.toByte)
      fos.write(asciiChars.getBytes)
      fos.write(binaryChars)
    } finally {
      fos.close()
    }

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should detect Binary (< 90% threshold)
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }

  it should "handle files with only newlines and spaces" in {
    // Given: File with ASCII whitespace
    val tempFile = Files.createTempFile("whitespace", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    try {
      writer.println("                    ") // spaces
      writer.println() // newline
      writer.println("          ") // more spaces
    } finally {
      writer.close()
    }

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should detect ASCII (whitespace is ASCII)
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "correctly identify gensort binary output" in {
    // Given: Typical gensort binary file (non-printable bytes)
    val records = (0 until 10).map { i =>
      Record(
        key = Array[Byte](0x00, 0x01, 0x02, 0x03, 0x04,
                          0x05, 0x06, 0x07, 0x08, i.toByte),
        value = Array.fill[Byte](90)(0xAA.toByte)
      )
    }
    val binaryFile = createBinaryFile(records)

    // When: Detect format
    val format = InputFormatDetector.detectFormat(binaryFile.toFile)

    // Then: Should be Binary
    format shouldBe DataFormat.Binary

    Files.delete(binaryFile)
  }

  it should "correctly identify gensort ASCII output" in {
    // Given: Typical gensort ASCII file (hex chars + spaces + newlines)
    val records = (0 until 10).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 3).toByte)
      )
    }
    val asciiFile = createAsciiFile(records)

    // When: Detect format
    val format = InputFormatDetector.detectFormat(asciiFile.toFile)

    // Then: Should be ASCII
    format shouldBe DataFormat.Ascii

    Files.delete(asciiFile)
  }
}
