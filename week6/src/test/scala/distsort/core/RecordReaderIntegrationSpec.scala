package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.{FileOutputStream, PrintWriter}

/**
 * Integration tests for RecordReader with auto-detection
 *
 * These tests verify that:
 * 1. InputFormatDetector correctly identifies formats
 * 2. RecordReader.create() uses the right reader
 * 3. Mixed ASCII/Binary files can be processed
 */
class RecordReaderIntegrationSpec extends AnyFlatSpec with Matchers {

  /**
   * Helper: Create binary file
   */
  def createBinaryFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("integration-binary", ".dat")
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
    val tempFile = Files.createTempFile("integration-ascii", ".txt")
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

  "RecordReader.create with auto-detection" should "read Binary files" in {
    // Given: Binary file
    val records = (1 to 5).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val binaryFile = createBinaryFile(records)

    // When: Create reader with auto-detection
    val reader = RecordReader.create(binaryFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: Records match
    readRecords should have length 5
    readRecords.zip(records).foreach { case (read, original) =>
      read shouldBe original
    }

    reader.close()
    Files.delete(binaryFile)
  }

  it should "read ASCII files" in {
    // Given: ASCII file
    val records = (1 to 5).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val asciiFile = createAsciiFile(records)

    // When: Create reader with auto-detection
    val reader = RecordReader.create(asciiFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: Records match
    readRecords should have length 5
    readRecords.zip(records).foreach { case (read, original) =>
      read shouldBe original
    }

    reader.close()
    Files.delete(asciiFile)
  }

  "RecordReader.readAll" should "handle Binary files" in {
    // Given: Binary file
    val records = (1 to 10).map { i =>
      Record(
        key = Array.fill[Byte](10)((i % 256).toByte),
        value = Array.fill[Byte](90)(((i * 3) % 256).toByte)
      )
    }
    val binaryFile = createBinaryFile(records)

    // When: Read all with auto-detection
    val readRecords = RecordReader.readAll(binaryFile.toFile)

    // Then: All records read correctly
    readRecords should have length 10
    readRecords.zip(records).foreach { case (read, original) =>
      read shouldBe original
    }

    Files.delete(binaryFile)
  }

  it should "handle ASCII files" in {
    // Given: ASCII file
    val records = (1 to 10).map { i =>
      Record(
        key = Array.fill[Byte](10)((i % 256).toByte),
        value = Array.fill[Byte](90)(((i * 3) % 256).toByte)
      )
    }
    val asciiFile = createAsciiFile(records)

    // When: Read all with auto-detection
    val readRecords = RecordReader.readAll(asciiFile.toFile)

    // Then: All records read correctly
    readRecords should have length 10
    readRecords.zip(records).foreach { case (read, original) =>
      read shouldBe original
    }

    Files.delete(asciiFile)
  }

  "Mixed format scenario" should "process both Binary and ASCII files" in {
    // Given: One Binary file and one ASCII file
    val binaryRecords = (1 to 5).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val asciiRecords = (6 to 10).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }

    val binaryFile = createBinaryFile(binaryRecords)
    val asciiFile = createAsciiFile(asciiRecords)

    // When: Read both files
    val allRecords = RecordReader.readAll(binaryFile.toFile) ++
                     RecordReader.readAll(asciiFile.toFile)

    // Then: Should have all 10 records
    allRecords should have length 10

    // Verify first 5 are from binary file
    allRecords.take(5).zip(binaryRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    // Verify last 5 are from ASCII file
    allRecords.drop(5).zip(asciiRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    Files.delete(binaryFile)
    Files.delete(asciiFile)
  }

  "RecordReader" should "handle large files efficiently" in {
    // Given: Large binary file (10,000 records = 1MB)
    val numRecords = 10000
    val records = (0 until numRecords).map { i =>
      Record(
        key = Array.fill[Byte](10)((i % 256).toByte),
        value = Array.fill[Byte](90)(((i * 7) % 256).toByte)
      )
    }
    val largeFile = createBinaryFile(records)

    // When: Read all records
    val startTime = System.currentTimeMillis()
    val readRecords = RecordReader.readAll(largeFile.toFile)
    val duration = System.currentTimeMillis() - startTime

    // Then: All records read correctly
    readRecords should have length numRecords

    // And: Should be reasonably fast (< 2 seconds for 1MB)
    duration should be < 2000L

    Files.delete(largeFile)
  }

  "Format detection" should "work correctly for gensort output" in {
    // Given: Records that simulate gensort output
    val records = (0 until 100).map { i =>
      val key = new Array[Byte](10)
      // Simulate gensort key pattern
      key(0) = ((i >> 8) & 0xFF).toByte
      key(1) = (i & 0xFF).toByte
      Record(key, Array.fill[Byte](90)(0xAA.toByte))
    }

    // Test Binary format
    val binaryFile = createBinaryFile(records)
    val binaryFormat = InputFormatDetector.detectFormat(binaryFile.toFile)
    binaryFormat shouldBe DataFormat.Binary
    Files.delete(binaryFile)

    // Test ASCII format
    val asciiFile = createAsciiFile(records)
    val asciiFormat = InputFormatDetector.detectFormat(asciiFile.toFile)
    asciiFormat shouldBe DataFormat.Ascii
    Files.delete(asciiFile)
  }

  "RecordReader instances" should "implement trait correctly" in {
    // Given: Files of both formats
    val record = Record(
      key = Array.fill[Byte](10)(42),
      value = Array.fill[Byte](90)(99)
    )
    val binaryFile = createBinaryFile(Seq(record))
    val asciiFile = createAsciiFile(Seq(record))

    // When: Create readers
    val binaryReader = RecordReader.create(binaryFile.toFile, DataFormat.Binary)
    val asciiReader = RecordReader.create(asciiFile.toFile, DataFormat.Ascii)

    // Then: Both should be RecordReader instances
    binaryReader shouldBe a[RecordReader]
    asciiReader shouldBe a[RecordReader]

    // And: Both should have working methods
    binaryReader.getFile shouldBe binaryFile.toFile
    asciiReader.getFile shouldBe asciiFile.toFile

    binaryReader.canRead shouldBe true
    asciiReader.canRead shouldBe true

    binaryReader.close()
    asciiReader.close()
    Files.delete(binaryFile)
    Files.delete(asciiFile)
  }
}
