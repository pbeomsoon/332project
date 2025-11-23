package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.PrintWriter

class AsciiRecordReaderSpec extends AnyFlatSpec with Matchers {

  /**
   * Helper: Convert bytes to hex string
   */
  def bytesToHex(bytes: Array[Byte]): String = {
    bytes.map("%02X".format(_)).mkString
  }

  /**
   * Helper: Create ASCII file with records
   *
   * ASCII format: [20 hex chars (key)] [1 space] [180 hex chars (value)] [\n]
   */
  def createAsciiFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-ascii", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    try {
      records.foreach { record =>
        val keyHex = bytesToHex(record.key)
        val valueHex = bytesToHex(record.value)
        writer.println(s"$keyHex $valueHex")
      }
    } finally {
      writer.close()
    }
    tempFile
  }

  "AsciiRecordReader" should "read valid ASCII records" in {
    // Given: ASCII file with 3 records
    val originalRecords = (1 to 3).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val inputFile = createAsciiFile(originalRecords)

    // When: Read records
    val reader = new AsciiRecordReader(inputFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: Records match
    readRecords should have length 3
    readRecords.zip(originalRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    reader.close()
    Files.delete(inputFile)
  }

  it should "return None at EOF" in {
    // Given: File with 1 record
    val records = Seq(Record(
      key = Array.fill[Byte](10)(1),
      value = Array.fill[Byte](90)(2)
    ))
    val inputFile = createAsciiFile(records)
    val reader = new AsciiRecordReader(inputFile.toFile)

    // When/Then: First read succeeds, second returns None
    reader.readRecord() shouldBe defined
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle empty file" in {
    // Given: Empty file
    val tempFile = Files.createTempFile("empty-ascii", ".txt")
    val reader = new AsciiRecordReader(tempFile.toFile)

    // When: Try to read
    val result = reader.readRecord()

    // Then: Should return None
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle lowercase hex characters" in {
    // Given: ASCII file with lowercase hex
    val tempFile = Files.createTempFile("lowercase-hex", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    // Key: "48656c6c6f576f726c64" (lowercase) = "HelloWorld" in hex
    // Value: 180 'a' characters
    writer.println("48656c6c6f576f726c64 " + "aa" * 90)
    writer.close()

    // When: Read record
    val reader = new AsciiRecordReader(tempFile.toFile)
    val record = reader.readRecord()

    // Then: Should parse correctly
    record shouldBe defined
    record.get.key shouldBe Array[Byte](0x48, 0x65, 0x6c, 0x6c, 0x6f,
                                         0x57, 0x6f, 0x72, 0x6c, 0x64)
    record.get.value.forall(_ == 0xAA.toByte) shouldBe true

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject invalid line length" in {
    // Given: Line with wrong length
    val tempFile = Files.createTempFile("invalid-length", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("TOOSHORT")  // Only 8 chars
    writer.close()

    // When: Try to read
    val reader = new AsciiRecordReader(tempFile.toFile)
    val result = reader.readRecord()

    // Then: Should return None (invalid format)
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject invalid hex characters" in {
    // Given: Line with non-hex characters
    val tempFile = Files.createTempFile("invalid-hex", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    // 'Z' is not a valid hex character
    writer.println("ZZZZZZZZZZZZZZZZZZZZ " + "AA" * 90)
    writer.close()

    // When: Try to read
    val reader = new AsciiRecordReader(tempFile.toFile)
    val result = reader.readRecord()

    // Then: Should return None (invalid hex)
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle multiple records sequentially" in {
    // Given: File with 10 records with different keys
    val originalRecords = (0 until 10).map { i =>
      val key = Array.fill[Byte](10)(0)
      key(9) = i.toByte
      Record(key, Array.fill[Byte](90)(i.toByte))
    }
    val inputFile = createAsciiFile(originalRecords)

    // When: Read all records
    val reader = new AsciiRecordReader(inputFile.toFile)
    val readRecords = (0 until 10).flatMap(_ => reader.readRecord())

    // Then: Should read all 10 correctly
    readRecords should have length 10
    readRecords.zip(originalRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    // And: Next read should be None
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle mixed case hex characters" in {
    // Given: ASCII file with mixed case hex (exactly 20 chars for key)
    val tempFile = Files.createTempFile("mixed-case", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("AaBbCcDdEeFf00112233 " + "Ff" * 90)  // Exactly 20 chars
    writer.close()

    // When: Read record
    val reader = new AsciiRecordReader(tempFile.toFile)
    val record = reader.readRecord()

    // Then: Should parse correctly (case insensitive)
    record shouldBe defined
    record.get.key shouldBe Array[Byte](0xAA.toByte, 0xBB.toByte, 0xCC.toByte,
                                         0xDD.toByte, 0xEE.toByte, 0xFF.toByte,
                                         0x00, 0x11, 0x22, 0x33)

    reader.close()
    Files.delete(tempFile)
  }
}
