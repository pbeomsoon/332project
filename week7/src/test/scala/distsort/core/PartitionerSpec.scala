package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class PartitionerSpec extends AnyFlatSpec with Matchers {

  def createTestKey(value: String): Array[Byte] = {
    val key = new Array[Byte](10)
    val bytes = value.getBytes("UTF-8")
    System.arraycopy(bytes, 0, key, 0, Math.min(bytes.length, 10))
    key
  }

  def createTestRecord(keyStr: String, valueStr: String = "value"): Record = {
    val key = createTestKey(keyStr)
    val value = new Array[Byte](90)
    val valueBytes = valueStr.getBytes("UTF-8")
    System.arraycopy(valueBytes, 0, value, 0, Math.min(valueBytes.length, 90))
    Record(key, value)
  }

  "Partitioner" should "assign partitions using binary search" in {
    // Given: Boundaries for 4 partitions
    val boundaries = Seq(
      createTestKey("333"),  // Boundary between partition 0 and 1
      createTestKey("666")   // Boundary between partition 1 and 2
    )
    val partitioner = new Partitioner(boundaries, numPartitions = 3)

    // When: Assign records to partitions
    partitioner.getPartition(createTestKey("111")) shouldBe 0  // < "333"
    partitioner.getPartition(createTestKey("444")) shouldBe 1  // >= "333" and < "666"
    partitioner.getPartition(createTestKey("777")) shouldBe 2  // >= "666"
  }

  it should "handle edge cases with boundaries" in {
    // Given: Boundaries
    val boundaries = Seq(
      createTestKey("500")
    )
    val partitioner = new Partitioner(boundaries, numPartitions = 2)

    // When: Keys equal to boundary
    partitioner.getPartition(createTestKey("500")) shouldBe 1  // Equal goes to next partition
    partitioner.getPartition(createTestKey("499")) shouldBe 0
    partitioner.getPartition(createTestKey("501")) shouldBe 1
  }

  it should "handle empty boundaries (single partition)" in {
    // Given: No boundaries (all records in one partition)
    val partitioner = new Partitioner(Seq.empty, numPartitions = 1)

    // When: Any key
    partitioner.getPartition(createTestKey("000")) shouldBe 0
    partitioner.getPartition(createTestKey("999")) shouldBe 0
  }

  it should "partition records correctly" in {
    // Given: Records and boundaries
    val records = Seq(
      createTestRecord("100"),
      createTestRecord("300"),
      createTestRecord("500"),
      createTestRecord("700"),
      createTestRecord("900")
    )

    val boundaries = Seq(
      createTestKey("400"),
      createTestKey("800")
    )
    val partitioner = new Partitioner(boundaries, numPartitions = 3)

    // When: Partition records
    val partitionedRecords = partitioner.partitionRecords(records)

    // Then: Records distributed correctly
    partitionedRecords.size shouldBe 3
    partitionedRecords(0) should contain allOf (records(0), records(1))  // "100", "300"
    partitionedRecords(1) should contain allOf (records(2), records(3))  // "500", "700"
    partitionedRecords(2) should contain only (records(4))               // "900"
  }

  it should "handle byte array comparison correctly" in {
    // Given: Binary keys
    val boundaries = Seq(
      Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 50),
      Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 100)
    )
    val partitioner = new Partitioner(boundaries, numPartitions = 3)

    // When: Binary comparison
    val key1 = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 25)
    val key2 = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 75)
    val key3 = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 125)

    partitioner.getPartition(key1) shouldBe 0
    partitioner.getPartition(key2) shouldBe 1
    partitioner.getPartition(key3) shouldBe 2
  }

  it should "work with uneven partition distribution" in {
    // Given: Many boundaries for more partitions
    val boundaries = (1 to 9).map(i => createTestKey((i * 100).toString))
    val partitioner = new Partitioner(boundaries, numPartitions = 10)

    // When: Assign many keys
    val keys = (0 to 999).map(i => createTestKey(i.toString.padTo(3, '0')))
    val assignments = keys.map(partitioner.getPartition)

    // Then: All partitions used
    assignments.distinct.sorted shouldBe (0 until 10)
  }

  it should "integrate with file partitioning" in {
    // Given: Temporary layout
    val tempRoot = Files.createTempDirectory("partitioner-test")
    val layout = new FileLayout(
      inputDirs = Seq.empty,
      outputDir = tempRoot.toString,
      tempDir = Some(tempRoot.resolve("temp").toString)
    )
    layout.initialize()

    val boundaries = Seq(createTestKey("500"))
    val partitioner = new Partitioner(boundaries, numPartitions = 2)
    partitioner.setFileLayout(layout)

    val records = Seq(
      createTestRecord("300"),
      createTestRecord("700")
    )

    // When: Create partition files
    val partitionFiles = partitioner.createPartitionFiles(records)

    // Then: Files created
    partitionFiles.size shouldBe 2
    partitionFiles.foreach(_.exists() shouldBe true)

    // Verify records in correct files
    val reader0 = RecordReader.create(partitionFiles(0))
    val partition0Records = Iterator.continually(reader0.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq
    reader0.close()
    partition0Records.size shouldBe 1

    val reader1 = RecordReader.create(partitionFiles(1))
    val partition1Records = Iterator.continually(reader1.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq
    reader1.close()
    partition1Records.size shouldBe 1

    // Cleanup
    layout.cleanupAll()
  }

  it should "handle streaming partitioning" in {
    // Given: Many records
    val records = (0 to 999).map(i => createTestRecord(f"$i%03d"))
    val boundaries = Seq(
      createTestKey("333"),
      createTestKey("666")
    )
    val partitioner = new Partitioner(boundaries, numPartitions = 3)

    // When: Streaming partition
    val partitions = partitioner.partitionRecordsStreaming(records.iterator)

    // Then: All partitions have records
    partitions.size shouldBe 3
    partitions.values.foreach(_.nonEmpty shouldBe true)

    // Total should match
    partitions.values.map(_.size).sum shouldBe records.size
  }
}