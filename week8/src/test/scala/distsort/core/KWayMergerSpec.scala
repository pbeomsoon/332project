package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.{BufferedOutputStream, FileOutputStream}

class KWayMergerSpec extends AnyFlatSpec with Matchers {

  def createTestRecord(keyStr: String, valueStr: String = "value"): Record = {
    val key = new Array[Byte](10)
    val value = new Array[Byte](90)
    val keyBytes = keyStr.getBytes("UTF-8")
    val valueBytes = valueStr.getBytes("UTF-8")
    System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, 10))
    System.arraycopy(valueBytes, 0, value, 0, Math.min(valueBytes.length, 90))
    Record(key, value)
  }

  def createSortedChunkFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("chunk", ".dat")
    val out = new BufferedOutputStream(new FileOutputStream(tempFile.toFile))
    try {
      records.sorted.foreach { record =>
        out.write(record.toBytes)
      }
    } finally {
      out.close()
    }
    tempFile
  }

  "KWayMerger" should "merge two sorted chunks" in {
    // Given: Two sorted chunks
    val chunk1 = createSortedChunkFile(Seq(
      createTestRecord("100"),
      createTestRecord("300"),
      createTestRecord("500")
    ))

    val chunk2 = createSortedChunkFile(Seq(
      createTestRecord("200"),
      createTestRecord("400"),
      createTestRecord("600")
    ))

    // When: Merge chunks
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile))
    val merged = merger.mergeAll()

    // Then: Should be sorted
    merged.map(r => new String(r.key).trim) shouldBe Seq(
      "100", "200", "300", "400", "500", "600"
    )

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
  }

  it should "merge multiple sorted chunks" in {
    // Given: Three sorted chunks
    val chunk1 = createSortedChunkFile(Seq(
      createTestRecord("111"),
      createTestRecord("444"),
      createTestRecord("777")
    ))

    val chunk2 = createSortedChunkFile(Seq(
      createTestRecord("222"),
      createTestRecord("555"),
      createTestRecord("888")
    ))

    val chunk3 = createSortedChunkFile(Seq(
      createTestRecord("333"),
      createTestRecord("666"),
      createTestRecord("999")
    ))

    // When: Merge all chunks
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile, chunk3.toFile))
    val merged = merger.mergeAll()

    // Then: Should be fully sorted
    merged.map(r => new String(r.key).trim) shouldBe Seq(
      "111", "222", "333", "444", "555", "666", "777", "888", "999"
    )

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
    Files.delete(chunk3)
  }

  it should "handle empty chunks" in {
    // Given: One empty and one non-empty chunk
    val emptyChunk = Files.createTempFile("empty", ".dat")

    val nonEmptyChunk = createSortedChunkFile(Seq(
      createTestRecord("100"),
      createTestRecord("200")
    ))

    // When: Merge with empty chunk
    val merger = new KWayMerger(Seq(emptyChunk.toFile, nonEmptyChunk.toFile))
    val merged = merger.mergeAll()

    // Then: Should get records from non-empty chunk
    merged.size shouldBe 2
    merged.map(r => new String(r.key).trim) shouldBe Seq("100", "200")

    // Cleanup
    Files.delete(emptyChunk)
    Files.delete(nonEmptyChunk)
  }

  it should "handle single chunk" in {
    // Given: Single sorted chunk
    val chunk = createSortedChunkFile(Seq(
      createTestRecord("100"),
      createTestRecord("200"),
      createTestRecord("300")
    ))

    // When: Merge single chunk
    val merger = new KWayMerger(Seq(chunk.toFile))
    val merged = merger.mergeAll()

    // Then: Should return same records
    merged.size shouldBe 3
    merged.map(r => new String(r.key).trim) shouldBe Seq("100", "200", "300")

    // Cleanup
    Files.delete(chunk)
  }

  it should "handle duplicate keys across chunks" in {
    // Given: Chunks with duplicate keys
    val chunk1 = createSortedChunkFile(Seq(
      createTestRecord("100", "value1"),
      createTestRecord("300", "value1")
    ))

    val chunk2 = createSortedChunkFile(Seq(
      createTestRecord("100", "value2"),
      createTestRecord("300", "value2")
    ))

    // When: Merge chunks
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile))
    val merged = merger.mergeAll()

    // Then: Should preserve all records with duplicate keys
    merged.size shouldBe 4
    merged.map(r => new String(r.key).trim) shouldBe Seq("100", "100", "300", "300")

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
  }

  it should "merge using streaming iterator" in {
    // Given: Large chunks
    val chunk1Records = (1 to 100 by 2).map(i => createTestRecord(f"$i%03d"))
    val chunk2Records = (2 to 100 by 2).map(i => createTestRecord(f"$i%03d"))

    val chunk1 = createSortedChunkFile(chunk1Records)
    val chunk2 = createSortedChunkFile(chunk2Records)

    // When: Stream merge
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile))
    var count = 0
    merger.mergeIterator.foreach { record =>
      count += 1
    }

    // Then: Should process all records
    count shouldBe 100

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
  }

  it should "merge with callback function" in {
    // Given: Sorted chunks
    val chunk1 = createSortedChunkFile(Seq(
      createTestRecord("100"),
      createTestRecord("300")
    ))

    val chunk2 = createSortedChunkFile(Seq(
      createTestRecord("200"),
      createTestRecord("400")
    ))

    // When: Merge with callback
    val processedRecords = scala.collection.mutable.ArrayBuffer[String]()
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile))
    merger.mergeWithCallback { record =>
      processedRecords += new String(record.key).trim
    }

    // Then: Callback should process in order
    processedRecords.toSeq shouldBe Seq("100", "200", "300", "400")

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
  }

  it should "write merged output to file" in {
    // Given: Sorted chunks
    val chunk1 = createSortedChunkFile(Seq(
      createTestRecord("100"),
      createTestRecord("300")
    ))

    val chunk2 = createSortedChunkFile(Seq(
      createTestRecord("200"),
      createTestRecord("400")
    ))

    val outputFile = Files.createTempFile("output", ".dat")

    // When: Merge to file
    val merger = new KWayMerger(Seq(chunk1.toFile, chunk2.toFile))
    merger.mergeToFile(outputFile.toFile)

    // Then: Output file should contain merged records
    val reader = RecordReader.create(outputFile.toFile)
    val outputRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq
    reader.close()

    outputRecords.map(r => new String(r.key).trim) shouldBe Seq("100", "200", "300", "400")

    // Cleanup
    Files.delete(chunk1)
    Files.delete(chunk2)
    Files.delete(outputFile)
  }

  it should "handle many chunks efficiently" in {
    // Given: Many small sorted chunks
    val chunks = (0 until 10).map { chunkId =>
      val records = (0 until 10).map { i =>
        createTestRecord(f"${chunkId * 100 + i * 10}%04d")
      }
      createSortedChunkFile(records)
    }

    // When: Merge all chunks
    val merger = new KWayMerger(chunks.map(_.toFile))
    val merged = merger.mergeAll()

    // Then: Should be sorted
    merged.size shouldBe 100
    val keys = merged.map(r => new String(r.key).trim.toInt)
    keys shouldBe keys.sorted

    // Cleanup
    chunks.foreach(Files.delete)
  }
}