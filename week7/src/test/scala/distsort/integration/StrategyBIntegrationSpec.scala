package distsort.integration

import distsort.core.Record
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.io.{File, RandomAccessFile}
import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Random}

/**
 * TDD Integration Test for Strategy B (N→M partition mapping)
 *
 * Strategy B allows:
 * - N workers → M partitions (where M > N)
 * - Example: 3 workers, 9 partitions
 * - Each worker handles multiple partitions for parallel merge
 *
 * Test Objectives:
 * 1. Verify shuffleMap creates correct N:M mapping
 * 2. Verify workers receive correct partition assignments
 * 3. Verify output files are created in correct locations
 * 4. Verify global sorting across all partitions
 */
class StrategyBIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  val testDir = "test-strategy-b"

  override def beforeEach(): Unit = {
    // Clean up test directory
    Try {
      deleteDirectory(new File(testDir))
    }
    new File(testDir).mkdirs()
  }

  override def afterEach(): Unit = {
    // Cleanup
    Try {
      deleteDirectory(new File(testDir))
    }
  }

  behavior of "Strategy B (3 workers, 9 partitions)"

  it should "create correct shuffleMap for 3 workers and 9 partitions" in {
    // Given: MasterService configured for Strategy B
    val masterService = new distsort.master.MasterService(
      expectedWorkers = 3,
      initialNumPartitions = 9
    )

    // When: Creating shuffle map
    val shuffleMap = masterService.createShuffleMap(workerIndices = Seq(0, 1, 2), numPartitions = 9)

    // Then: Verify partition distribution
    // Worker 0: partitions 0, 1, 2
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 0
    shuffleMap(2) shouldBe 0

    // Worker 1: partitions 3, 4, 5
    shuffleMap(3) shouldBe 1
    shuffleMap(4) shouldBe 1
    shuffleMap(5) shouldBe 1

    // Worker 2: partitions 6, 7, 8
    shuffleMap(6) shouldBe 2
    shuffleMap(7) shouldBe 2
    shuffleMap(8) shouldBe 2

    // Verify all partitions are assigned
    shuffleMap.size shouldBe 9

    // Verify even distribution
    val workerLoads = shuffleMap.values.groupBy(identity).mapValues(_.size)
    workerLoads(0) shouldBe 3
    workerLoads(1) shouldBe 3
    workerLoads(2) shouldBe 3
  }

  it should "create correct shuffleMap for non-divisible partitions" in {
    // Given: 3 workers, 10 partitions (not evenly divisible)
    val masterService = new distsort.master.MasterService(
      expectedWorkers = 3,
      initialNumPartitions = 10
    )

    // When: Creating shuffle map
    val shuffleMap = masterService.createShuffleMap(workerIndices = Seq(0, 1, 2), numPartitions = 10)

    // Then: Verify distribution
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 0
    shuffleMap(2) shouldBe 0

    shuffleMap(3) shouldBe 1
    shuffleMap(4) shouldBe 1
    shuffleMap(5) shouldBe 1

    shuffleMap(6) shouldBe 2
    shuffleMap(7) shouldBe 2
    shuffleMap(8) shouldBe 2
    shuffleMap(9) shouldBe 2  // Last partition goes to last worker

    // Verify load is relatively balanced
    val workerLoads = shuffleMap.values.groupBy(identity).mapValues(_.size)
    workerLoads.values.max - workerLoads.values.min should be <= 1
  }

  it should "handle single worker with multiple partitions" in {
    // Given: 1 worker, 5 partitions
    val masterService = new distsort.master.MasterService(
      expectedWorkers = 1,
      initialNumPartitions = 5
    )

    // When: Creating shuffle map
    val shuffleMap = masterService.createShuffleMap(workerIndices = Seq(0), numPartitions = 5)

    // Then: All partitions should go to worker 0
    shuffleMap.values.toSet shouldBe Set(0)
    shuffleMap.size shouldBe 5
  }

  it should "handle Strategy A (1:1 mapping)" in {
    // Given: 3 workers, 3 partitions (Strategy A)
    val masterService = new distsort.master.MasterService(
      expectedWorkers = 3,
      initialNumPartitions = 3
    )

    // When: Creating shuffle map
    val shuffleMap = masterService.createShuffleMap(workerIndices = Seq(0, 1, 2), numPartitions = 3)

    // Then: One partition per worker
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 1
    shuffleMap(2) shouldBe 2

    // Each worker gets exactly 1 partition
    val workerLoads = shuffleMap.values.groupBy(identity).mapValues(_.size)
    workerLoads.values.forall(_ == 1) shouldBe true
  }

  it should "validate partition assignments in PartitionConfigResponse" in {
    // Given: MasterService with 3 workers, 9 partitions
    val masterService = new distsort.master.MasterService(
      expectedWorkers = 3,
      initialNumPartitions = 9
    )

    // Simulate worker registration
    val workerIds = (0 until 3).map(i => s"worker-$i")
    workerIds.zipWithIndex.foreach { case (workerId, index) =>
      val request = distsort.proto.distsort.RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + index,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      scala.concurrent.Await.result(
        masterService.registerWorker(request),
        scala.concurrent.duration.Duration.Inf
      )
    }

    // Collect samples to trigger boundary computation
    workerIds.foreach { workerId =>
      val sampleKeys = generateRandomKeys(100)
      val request = distsort.proto.distsort.SampleKeysRequest(
        workerId = workerId,
        keys = sampleKeys.map(com.google.protobuf.ByteString.copyFrom),
        totalRecordsProcessed = 1000
      )
      scala.concurrent.Await.result(
        masterService.submitSampleKeys(request),
        scala.concurrent.duration.Duration.Inf
      )
    }

    // Wait for boundaries to be computed
    Thread.sleep(1000)

    // When: Workers request partition configuration
    val configResponses = workerIds.zipWithIndex.map { case (workerId, index) =>
      val request = distsort.proto.distsort.PartitionConfigRequest(workerId = workerId)
      val response = scala.concurrent.Await.result(
        masterService.getPartitionConfig(request),
        scala.concurrent.duration.Duration.Inf
      )
      (index, response)
    }

    // Then: Verify each worker gets correct partition assignments
    configResponses.foreach { case (workerIndex, response) =>
      val expectedPartitions = workerIndex match {
        case 0 => Seq(0, 1, 2)
        case 1 => Seq(3, 4, 5)
        case 2 => Seq(6, 7, 8)
      }

      response.workerPartitionAssignments shouldBe expectedPartitions
      response.numPartitions shouldBe 9

      // Verify shuffleMap is included
      response.shuffleMap.size shouldBe 9
    }
  }

  // Helper methods

  private def generateRandomKeys(count: Int): Seq[Array[Byte]] = {
    val random = new Random()
    (0 until count).map { _ =>
      val key = new Array[Byte](10)
      random.nextBytes(key)
      key
    }
  }

  private def generateTestData(file: File, numRecords: Int): Unit = {
    file.getParentFile.mkdirs()
    val raf = new RandomAccessFile(file, "rw")
    val random = new Random()

    try {
      (0 until numRecords).foreach { _ =>
        val record = new Array[Byte](100)
        random.nextBytes(record)
        raf.write(record)
      }
    } finally {
      raf.close()
    }
  }

  private def deleteDirectory(dir: File): Unit = {
    if (dir.exists()) {
      if (dir.isDirectory) {
        dir.listFiles().foreach(deleteDirectory)
      }
      dir.delete()
    }
  }

  private def readRecords(file: File): Seq[Array[Byte]] = {
    val bytes = Files.readAllBytes(file.toPath)
    val recordSize = 100
    val numRecords = bytes.length / recordSize

    (0 until numRecords).map { i =>
      bytes.slice(i * recordSize, (i + 1) * recordSize)
    }
  }

  private def verifyGlobalSort(partitions: Seq[File]): Boolean = {
    var prevKey: Array[Byte] = Array.fill(10)(0.toByte)

    for (partitionFile <- partitions) {
      if (partitionFile.exists()) {
        val records = readRecords(partitionFile)
        for (recordBytes <- records) {
          val key = recordBytes.take(10)
          if (compareKeys(prevKey, key) > 0) {
            return false // Not sorted
          }
          prevKey = key
        }
      }
    }
    true
  }

  private def compareKeys(a: Array[Byte], b: Array[Byte]): Int = {
    var i = 0
    while (i < a.length && i < b.length) {
      val cmp = java.lang.Integer.compareUnsigned(a(i) & 0xFF, b(i) & 0xFF)
      if (cmp != 0) return cmp
      i += 1
    }
    a.length - b.length
  }
}
