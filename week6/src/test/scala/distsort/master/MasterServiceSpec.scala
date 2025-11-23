package distsort.master

import distsort.proto.distsort._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.protobuf.ByteString

/**
 * Unit tests for MasterService
 * Each test creates its own isolated service instance to prevent interference
 */
class MasterServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  // Use unique IDs for each test to avoid conflicts
  private var testId = 0

  override def beforeEach(): Unit = {
    testId = System.currentTimeMillis().toInt % 100000
    Thread.sleep(50)  // Small delay to ensure timestamp uniqueness
  }

  // Clean up any lingering state between tests
  override def afterEach(): Unit = {
    // Give async operations time to complete
    Thread.sleep(300)  // Increased cleanup time
  }

  // Helper to create unique worker IDs for each test
  private def workerId(base: String): String = s"${base}-test${testId}-${System.nanoTime() % 1000}"

  "MasterService" should "handle worker registration correctly" in {
    val service = new MasterService(expectedWorkers = 3, numPartitions = 9)

    // First registration should succeed
    val response1 = Await.result(service.registerWorker(
      RegisterWorkerRequest(workerId("worker-1"), "localhost", 5001, 4, 1024L)
    ), 5.seconds)

    response1.success shouldBe true
    response1.assignedWorkerIndex shouldBe 0
    service.getRegisteredWorkerCount shouldBe 1

    // Second registration should succeed
    val response2 = Await.result(service.registerWorker(
      RegisterWorkerRequest(workerId("worker-2"), "localhost", 5002, 4, 1024L)
    ), 5.seconds)

    response2.success shouldBe true
    response2.assignedWorkerIndex shouldBe 1
    service.getRegisteredWorkerCount shouldBe 2
  }

  it should "allow worker re-registration" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 4)

    // First registration
    val response1 = Await.result(service.registerWorker(
      RegisterWorkerRequest("worker-1", "localhost", 5001, 4, 1024L)
    ), 5.seconds)
    response1.success shouldBe true
    val originalIndex = response1.assignedWorkerIndex

    // Re-registration should succeed with same index
    val response2 = Await.result(service.registerWorker(
      RegisterWorkerRequest("worker-1", "localhost", 5001, 4, 1024L)
    ), 5.seconds)
    response2.success shouldBe true
    response2.assignedWorkerIndex shouldBe originalIndex
    response2.message should include("already registered")
  }

  it should "reject registration when max workers reached" in {
    val service = new MasterService(expectedWorkers = 1, numPartitions = 3)

    // First registration succeeds
    val response1 = Await.result(service.registerWorker(
      RegisterWorkerRequest("worker-1", "localhost", 5001, 4, 1024L)
    ), 5.seconds)
    response1.success shouldBe true

    // Second registration should fail
    val response2 = Await.result(service.registerWorker(
      RegisterWorkerRequest("worker-2", "localhost", 5002, 4, 1024L)
    ), 5.seconds)
    response2.success shouldBe false
    response2.message should include("Maximum number of workers")
  }

  it should "collect sample keys from all workers" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 4)

    // Register workers with unique IDs
    val w1Id = workerId("worker-1")
    val w2Id = workerId("worker-2")
    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)), 5.seconds)

    // Submit samples from worker-1
    val samples1 = (1 to 10).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))
    val sampleResponse1 = Await.result(service.submitSampleKeys(
      SampleKeysRequest(w1Id, samples1, 100)
    ), 5.seconds)
    sampleResponse1.acknowledged shouldBe true

    // Submit samples from worker-2
    val samples2 = (11 to 20).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))
    val sampleResponse2 = Await.result(service.submitSampleKeys(
      SampleKeysRequest(w2Id, samples2, 100)
    ), 5.seconds)
    sampleResponse2.acknowledged shouldBe true

    // Wait for boundary computation
    service.waitForAllSamples(5) shouldBe true
    service.getBoundaries should not be empty
  }

  it should "reject samples from unregistered workers" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 4)

    val samples = Seq(ByteString.copyFrom(Array[Byte](1, 2, 3)))
    val response = Await.result(service.submitSampleKeys(
      SampleKeysRequest("unknown-worker", samples, 10)
    ), 5.seconds)

    response.acknowledged shouldBe false
  }

  it should "compute correct number of partition boundaries" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 6)

    // Register and submit samples with unique IDs
    val w1Id = workerId("w1")
    val w2Id = workerId("w2")
    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)), 5.seconds)

    val samples1 = (0 to 9).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))
    val samples2 = (10 to 19).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))

    Await.result(service.submitSampleKeys(SampleKeysRequest(w1Id, samples1, 100)), 5.seconds)
    Await.result(service.submitSampleKeys(SampleKeysRequest(w2Id, samples2, 100)), 5.seconds)

    service.waitForAllSamples(5) shouldBe true

    // Wait a bit for boundary computation
    Thread.sleep(500)

    service.getBoundaries.length shouldBe 5 // numPartitions - 1
  }

  it should "distribute partition configuration correctly" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 6)

    // Setup - register workers and get their indices with unique IDs
    val w1Id = workerId("w1")
    val w2Id = workerId("w2")
    val reg1 = Await.result(service.registerWorker(
      RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)
    ), 5.seconds)
    val reg2 = Await.result(service.registerWorker(
      RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)
    ), 5.seconds)

    val samples = (0 to 19).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))
    Await.result(service.submitSampleKeys(SampleKeysRequest(w1Id, samples.take(10), 100)), 5.seconds)
    Await.result(service.submitSampleKeys(SampleKeysRequest(w2Id, samples.drop(10), 100)), 5.seconds)
    service.waitForAllSamples(5)

    // Get config for workers
    val config1 = Await.result(service.getPartitionConfig(
      PartitionConfigRequest(w1Id)
    ), 5.seconds)

    config1.numPartitions shouldBe 6
    config1.boundaries.length shouldBe 5

    // Check partition assignments based on actual worker index
    val worker1Index = reg1.assignedWorkerIndex
    val expectedPartitions1 = (worker1Index * 3 until (worker1Index + 1) * 3).toSeq
    config1.workerPartitionAssignments shouldBe expectedPartitions1

    val config2 = Await.result(service.getPartitionConfig(
      PartitionConfigRequest(w2Id)
    ), 5.seconds)

    val worker2Index = reg2.assignedWorkerIndex
    val expectedPartitions2 = (worker2Index * 3 until (worker2Index + 1) * 3).toSeq
    config2.workerPartitionAssignments shouldBe expectedPartitions2
  }

  it should "track phase completions correctly" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 4)

    // Register workers with unique IDs to avoid conflicts
    val w1Id = workerId("w1")
    val w2Id = workerId("w2")

    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)), 5.seconds)

    // First worker completes sampling
    val response1 = Await.result(service.reportPhaseComplete(
      PhaseCompleteRequest(w1Id, WorkerPhase.PHASE_SAMPLING, "Sampling done")
    ), 5.seconds)

    response1.proceedToNext shouldBe false // Waiting for other worker
    response1.message should include("Waiting for")
    response1.message should include("more alive workers")

    // Second worker completes sampling
    val response2 = Await.result(service.reportPhaseComplete(
      PhaseCompleteRequest(w2Id, WorkerPhase.PHASE_SAMPLING, "Sampling done")
    ), 5.seconds)

    response2.proceedToNext shouldBe true // All workers done
    response2.nextPhase shouldBe WorkerPhase.PHASE_WAITING_FOR_PARTITIONS
  }

  it should "handle error reports appropriately" in {
    val service = new MasterService(expectedWorkers = 3, numPartitions = 9)

    // Register workers with unique IDs
    val w1Id = workerId("w1")
    val w2Id = workerId("w2")
    val w3Id = workerId("w3")
    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w3Id, "localhost", 5003, 4, 1024L)), 5.seconds)

    // Error during sampling phase (can continue)
    val response1 = Await.result(service.reportError(
      ReportErrorRequest(w1Id, "NetworkError", "Connection lost", WorkerPhase.PHASE_SAMPLING)
    ), 5.seconds)

    response1.shouldAbort shouldBe false
    response1.instructions should include("Continue with reduced worker count")

    // Error during critical phase (shuffle)
    val response2 = Await.result(service.reportError(
      ReportErrorRequest(w2Id, "DataCorruption", "Checksum failed", WorkerPhase.PHASE_SHUFFLING)
    ), 5.seconds)

    response2.shouldRetry shouldBe true
  }

  it should "handle concurrent worker registrations" in {
    val service = new MasterService(expectedWorkers = 10, numPartitions = 30)

    // Register 10 workers concurrently with unique IDs
    val futures = (1 to 10).map { i =>
      service.registerWorker(
        RegisterWorkerRequest(workerId(s"worker-$i"), "localhost", 5000 + i, 4, 1024L)
      )
    }

    val responses = futures.map(f => Await.result(f, 5.seconds))

    // All should succeed
    responses.foreach(_.success shouldBe true)

    // Worker indices should be unique
    val indices = responses.map(_.assignedWorkerIndex)
    indices.distinct.length shouldBe 10
    indices.min shouldBe 0
    indices.max shouldBe 9

    service.getRegisteredWorkerCount shouldBe 10
  }

  it should "timeout waiting for samples if not all workers submit" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 4)

    val w1Id = workerId("w1")
    val w2Id = workerId("w2")
    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)
    Await.result(service.registerWorker(RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)), 5.seconds)

    // Only one worker submits samples
    val samples = Seq(ByteString.copyFrom(Array[Byte](1, 2, 3)))
    Await.result(service.submitSampleKeys(SampleKeysRequest(w1Id, samples, 10)), 5.seconds)

    // Should timeout waiting for all samples
    service.waitForAllSamples(1) shouldBe false
  }

  it should "determine correct next phase in workflow" in {
    val service = new MasterService(expectedWorkers = 1, numPartitions = 3)

    val w1Id = workerId("w1")
    Await.result(service.registerWorker(RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)), 5.seconds)

    // Test phase transitions
    val phases = Seq(
      (WorkerPhase.PHASE_INITIALIZING, WorkerPhase.PHASE_SAMPLING),
      (WorkerPhase.PHASE_SAMPLING, WorkerPhase.PHASE_WAITING_FOR_PARTITIONS),
      (WorkerPhase.PHASE_WAITING_FOR_PARTITIONS, WorkerPhase.PHASE_SORTING),
      (WorkerPhase.PHASE_SORTING, WorkerPhase.PHASE_SHUFFLING),
      (WorkerPhase.PHASE_SHUFFLING, WorkerPhase.PHASE_MERGING),
      (WorkerPhase.PHASE_MERGING, WorkerPhase.PHASE_COMPLETED)
    )

    // Need to submit samples first for some phases
    val samples = Seq(ByteString.copyFrom(Array[Byte](1, 2, 3)))
    Await.result(service.submitSampleKeys(SampleKeysRequest(w1Id, samples, 10)), 5.seconds)

    phases.foreach { case (currentPhase, expectedNext) =>
      val response = Await.result(service.reportPhaseComplete(
        PhaseCompleteRequest(w1Id, currentPhase, s"$currentPhase done")
      ), 5.seconds)

      if (response.proceedToNext) {
        response.nextPhase shouldBe expectedNext
      }
    }
  }

  // ShuffleMap tests
  "MasterService.createShuffleMap" should "create 1:1 mapping for Strategy A" in {
    val service = new MasterService(expectedWorkers = 3, numPartitions = 3)

    val shuffleMap = service.createShuffleMap(Seq(0, 1, 2), 3)

    shuffleMap.size shouldBe 3
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 1
    shuffleMap(2) shouldBe 2
  }

  it should "create N:M mapping for Strategy B (3 workers, 9 partitions)" in {
    val service = new MasterService(expectedWorkers = 3, numPartitions = 9)

    val shuffleMap = service.createShuffleMap(Seq(0, 1, 2), 9)

    shuffleMap.size shouldBe 9
    // First 3 partitions to worker 0
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 0
    shuffleMap(2) shouldBe 0
    // Next 3 partitions to worker 1
    shuffleMap(3) shouldBe 1
    shuffleMap(4) shouldBe 1
    shuffleMap(5) shouldBe 1
    // Last 3 partitions to worker 2
    shuffleMap(6) shouldBe 2
    shuffleMap(7) shouldBe 2
    shuffleMap(8) shouldBe 2
  }

  it should "handle uneven partition distribution" in {
    val service = new MasterService(expectedWorkers = 3, numPartitions = 10)

    val shuffleMap = service.createShuffleMap(Seq(0, 1, 2), 10)

    shuffleMap.size shouldBe 10
    // partitionsPerWorker = 10 / 3 = 3
    // Worker 0: partitions 0, 1, 2
    shuffleMap(0) shouldBe 0
    shuffleMap(1) shouldBe 0
    shuffleMap(2) shouldBe 0
    // Worker 1: partitions 3, 4, 5
    shuffleMap(3) shouldBe 1
    shuffleMap(4) shouldBe 1
    shuffleMap(5) shouldBe 1
    // Worker 2: partitions 6, 7, 8, 9 (gets extra)
    shuffleMap(6) shouldBe 2
    shuffleMap(7) shouldBe 2
    shuffleMap(8) shouldBe 2
    shuffleMap(9) shouldBe 2
  }

  it should "handle single worker case" in {
    val service = new MasterService(expectedWorkers = 1, numPartitions = 5)

    val shuffleMap = service.createShuffleMap(Seq(0), 5)

    shuffleMap.size shouldBe 5
    (0 to 4).foreach { partitionId =>
      shuffleMap(partitionId) shouldBe 0
    }
  }

  it should "include shuffleMap in partition configuration response" in {
    val service = new MasterService(expectedWorkers = 2, numPartitions = 6)

    // Register workers with unique IDs
    val w1Id = workerId("w1")
    val w2Id = workerId("w2")
    Await.result(service.registerWorker(
      RegisterWorkerRequest(w1Id, "localhost", 5001, 4, 1024L)
    ), 5.seconds)
    Await.result(service.registerWorker(
      RegisterWorkerRequest(w2Id, "localhost", 5002, 4, 1024L)
    ), 5.seconds)

    // Submit samples
    val samples = (0 to 19).map(i => ByteString.copyFrom(Array.fill[Byte](10)(i.toByte)))
    Await.result(service.submitSampleKeys(SampleKeysRequest(w1Id, samples.take(10), 100)), 5.seconds)
    Await.result(service.submitSampleKeys(SampleKeysRequest(w2Id, samples.drop(10), 100)), 5.seconds)
    service.waitForAllSamples(5)

    // Get config and check shuffleMap
    val config = Await.result(service.getPartitionConfig(
      PartitionConfigRequest(w1Id)
    ), 5.seconds)

    config.shuffleMap should not be empty
    config.shuffleMap.size shouldBe 6
    // Verify all partition IDs are mapped
    (0 to 5).foreach { partitionId =>
      config.shuffleMap.contains(partitionId) shouldBe true
      config.shuffleMap(partitionId) should be >= 0
      config.shuffleMap(partitionId) should be < 2
    }
  }

  it should "assign all partitions to workers via shuffleMap" in {
    val service = new MasterService(expectedWorkers = 4, numPartitions = 12)

    val shuffleMap = service.createShuffleMap(Seq(0, 1, 2, 3), 12)

    // Verify all partitions are assigned
    (0 until 12).foreach { partitionId =>
      shuffleMap.contains(partitionId) shouldBe true
    }

    // Verify worker IDs are in valid range
    shuffleMap.values.foreach { workerId =>
      workerId should be >= 0
      workerId should be < 4
    }

    // Verify load balancing (each worker should get 3 partitions)
    val partitionsPerWorker = shuffleMap.groupBy(_._2).mapValues(_.size)
    partitionsPerWorker.size shouldBe 4
    partitionsPerWorker.values.foreach(_ shouldBe 3)
  }
}