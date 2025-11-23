package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.FileOutputStream

class BinaryRecordReaderSpec extends AnyFlatSpec with Matchers {

  /**
   * Helper method to create a temporary file with records
   */
  def createTempFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-input", ".dat")
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

  "BinaryRecordReader" should "read records from file" in {
    // Given: File with 5 records
    val originalRecords = (1 to 5).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val inputFile = createTempFile(originalRecords)

    // When: Read records
    val reader = new BinaryRecordReader(inputFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: All records read correctly
    readRecords should have length 5
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
    val inputFile = createTempFile(records)
    val reader = new BinaryRecordReader(inputFile.toFile)

    // When/Then: First read succeeds, second returns None
    reader.readRecord() shouldBe defined
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle incomplete records gracefully" in {
    // Given: File with only 50 bytes (incomplete record)
    val tempFile = Files.createTempFile("incomplete", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](50)(1))
    fos.close()

    // When: Try to read
    val reader = new BinaryRecordReader(tempFile.toFile)
    val result = reader.readRecord()

    // Then: Should return None
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle empty file" in {
    // Given: Empty file
    val tempFile = Files.createTempFile("empty", ".dat")
    val reader = new BinaryRecordReader(tempFile.toFile)

    // When: Try to read
    val result = reader.readRecord()

    // Then: Should return None
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "read multiple records sequentially" in {
    // Given: File with 10 records with different values
    val originalRecords = (0 until 10).map { i =>
      val key = Array.fill[Byte](10)(0)
      key(9) = i.toByte
      Record(key, Array.fill[Byte](90)(i.toByte))
    }
    val inputFile = createTempFile(originalRecords)

    // When: Read all records
    val reader = new BinaryRecordReader(inputFile.toFile)
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

  it should "handle large files efficiently" in {
    // Given: File with 1000 records
    val numRecords = 1000
    val originalRecords = (0 until numRecords).map { i =>
      Record(
        key = Array.fill[Byte](10)((i % 256).toByte),
        value = Array.fill[Byte](90)(((i * 2) % 256).toByte)
      )
    }
    val inputFile = createTempFile(originalRecords)

    // When: Read all records
    val startTime = System.currentTimeMillis()
    val reader = new BinaryRecordReader(inputFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq
    val duration = System.currentTimeMillis() - startTime

    // Then: All records read correctly
    readRecords should have length numRecords

    // And: Should be reasonably fast (< 1 second for 1000 records)
    duration should be < 1000L

    reader.close()
    Files.delete(inputFile)
  }
}
