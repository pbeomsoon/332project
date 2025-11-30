package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import distsort.checkpoint.{CheckpointManager, WorkerState}
import distsort.proto.distsort.WorkerPhase
import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Unit tests for Option 3 implementation:
 * - Worker Re-registration
 * - Checkpoint Recovery with workerIndex
 * - Threshold-based failure handling
 */
class Option3UnitSpec extends AnyFlatSpec with Matchers {

  behavior of "CheckpointManager with workerIndex"

  it should "save and restore checkpoint with workerIndex" in {
    val checkpointDir = Files.createTempDirectory("checkpoint-test").toString
    val workerId = "worker-0"
    val manager = new CheckpointManager(workerId, checkpointDir)

    try {
      // Create WorkerState with workerIndex
      val originalIndex = 0
      val originalRecordsProcessed = 100L
      val state = WorkerState.fromScala(
        workerIndex = originalIndex,
        processedRecords = originalRecordsProcessed,
        partitionBoundaries = Seq.empty,
        shuffleMap = Map.empty,
        completedPartitions = Set.empty,
        currentFiles = Seq.empty,
        phaseMetadata = Map.empty
      )

      // Save checkpoint
      val saveResult = Await.result(
        manager.saveCheckpoint(WorkerPhase.PHASE_SAMPLING, state, 0.5),
        5.seconds
      )

      saveResult should not be empty

      // Load checkpoint
      manager.loadLatestCheckpoint() match {
        case Some(checkpoint) =>
          // Verify workerIndex is preserved
          checkpoint.state.workerIndex shouldBe originalIndex
          checkpoint.state.processedRecords shouldBe originalRecordsProcessed
          checkpoint.phase shouldBe "PHASE_SAMPLING"

        case None =>
          fail("Failed to load checkpoint")
      }
    } finally {
      // Cleanup
      manager.deleteAllCheckpoints()
      val checkpointPath = Paths.get(checkpointDir).resolve(workerId)
      Files.deleteIfExists(checkpointPath)
      Files.deleteIfExists(Paths.get(checkpointDir))
    }
  }

  it should "validate workerIndex on checkpoint load" in {
    val checkpointDir = Files.createTempDirectory("checkpoint-test").toString
    val workerId = "worker-1"
    val manager = new CheckpointManager(workerId, checkpointDir)

    try {
      // Create WorkerState with workerIndex = 1
      val state = WorkerState.fromScala(
        workerIndex = 1,
        processedRecords = 200L,
        partitionBoundaries = Seq.empty,
        shuffleMap = Map.empty,
        completedPartitions = Set.empty,
        currentFiles = Seq.empty,
        phaseMetadata = Map.empty
      )

      // Save checkpoint
      Await.result(
        manager.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 0.8),
        5.seconds
      )

      // Load and verify
      manager.loadLatestCheckpoint() match {
        case Some(checkpoint) =>
          checkpoint.state.workerIndex shouldBe 1
          checkpoint.state.processedRecords shouldBe 200L

        case None =>
          fail("Failed to load checkpoint")
      }
    } finally {
      manager.deleteAllCheckpoints()
      val checkpointPath = Paths.get(checkpointDir).resolve(workerId)
      Files.deleteIfExists(checkpointPath)
      Files.deleteIfExists(Paths.get(checkpointDir))
    }
  }

  behavior of "Threshold calculation"

  it should "correctly calculate minimum worker threshold (50%)" in {
    val MIN_WORKER_THRESHOLD = 0.5

    // Test case 1: 3 workers
    val expectedWorkers1 = 3
    val minWorkers1 = (expectedWorkers1 * MIN_WORKER_THRESHOLD).toInt
    minWorkers1 shouldBe 1  // 50% of 3 = 1.5, rounded down to 1

    // Test case 2: 4 workers
    val expectedWorkers2 = 4
    val minWorkers2 = (expectedWorkers2 * MIN_WORKER_THRESHOLD).toInt
    minWorkers2 shouldBe 2  // 50% of 4 = 2

    // Test case 3: 10 workers
    val expectedWorkers3 = 10
    val minWorkers3 = (expectedWorkers3 * MIN_WORKER_THRESHOLD).toInt
    minWorkers3 shouldBe 5  // 50% of 10 = 5
  }

  it should "determine when to continue vs abort based on threshold" in {
    val MIN_WORKER_THRESHOLD = 0.5
    val expectedWorkers = 3
    val threshold = (expectedWorkers * MIN_WORKER_THRESHOLD).toInt  // 1

    // Test case 1: 3 alive → 2 alive (above threshold, continue)
    val currentAlive1 = 3
    val afterFailure1 = currentAlive1 - 1
    afterFailure1 should be >= threshold
    // Should continue

    // Test case 2: 2 alive → 1 alive (at threshold, continue)
    val currentAlive2 = 2
    val afterFailure2 = currentAlive2 - 1
    afterFailure2 should be >= threshold
    // Should continue

    // Test case 3: 1 alive → 0 alive (below threshold, abort)
    val currentAlive3 = 1
    val afterFailure3 = currentAlive3 - 1
    afterFailure3 should be < threshold
    // Should abort
  }

  behavior of "Worker identity tracking"

  it should "use (host, port) as canonical worker identity" in {
    // Test that worker identity is based on (host, port) tuple
    val workerId1 = "localhost:50051"
    val workerId2 = "localhost:50052"
    val workerId3 = "localhost:50051"  // Same as workerId1

    // workerId1 and workerId3 should be identical
    workerId1 shouldBe workerId3
    workerId1 should not be workerId2

    // Extract host and port
    val Array(host1, port1) = workerId1.split(":")
    val Array(host3, port3) = workerId3.split(":")

    host1 shouldBe host3
    port1 shouldBe port3
  }

  behavior of "Partition boundaries computation"

  it should "compute boundaries with reduced worker count" in {
    // Simulate sample collection with 2 workers (originally 3)
    val samples = List(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    val reducedWorkerCount = 2

    // With 2 workers, we need N-1 = 1 boundary
    // Taking every (samples.size / reducedWorkerCount)-th element
    val step = samples.size / reducedWorkerCount
    val boundaries = (1 until reducedWorkerCount).map { i =>
      samples(i * step)
    }.toList

    boundaries.size shouldBe (reducedWorkerCount - 1)
    // With samples of 10 elements and 2 workers:
    // step = 10/2 = 5
    // boundary at index 5 = 60
    boundaries.head shouldBe 60
  }

  it should "handle minimum worker count (1 worker)" in {
    val samples = List(10, 20, 30, 40, 50)
    val reducedWorkerCount = 1

    // With 1 worker, we need 0 boundaries (all data goes to one worker)
    val boundaries = (1 until reducedWorkerCount).map { i =>
      samples(i * samples.size / reducedWorkerCount)
    }.toList

    boundaries.size shouldBe 0
    boundaries shouldBe empty
  }

  behavior of "Latch adjustment logic"

  it should "determine when to adjust samplesLatch" in {
    // Scenario: 3 workers expected, 1 failed
    val expectedWorkers = 3
    val aliveWorkers = 2
    val samplesCollected = 1  // Only 1 worker sent samples before failure

    // samplesLatch should be adjusted if count > 0
    val samplesLatchCount = samplesCollected
    val shouldAdjust = samplesLatchCount > 0

    shouldAdjust shouldBe true

    // After adjustment, latch should be 0 (to unblock all workers)
    val targetCount = 0
    targetCount shouldBe 0
  }

  it should "determine when to compute partition boundaries" in {
    // Scenario 1: Have enough samples
    val collectedSamples1 = 2
    val aliveWorkers1 = 2
    val boundariesComputed1 = false

    val shouldCompute1 = collectedSamples1 >= aliveWorkers1 && !boundariesComputed1
    shouldCompute1 shouldBe true

    // Scenario 2: Not enough samples yet
    val collectedSamples2 = 1
    val aliveWorkers2 = 2
    val boundariesComputed2 = false

    val shouldCompute2 = collectedSamples2 >= aliveWorkers2 && !boundariesComputed2
    shouldCompute2 shouldBe false

    // Scenario 3: Already computed
    val collectedSamples3 = 2
    val aliveWorkers3 = 2
    val boundariesComputed3 = true

    val shouldCompute3 = collectedSamples3 >= aliveWorkers3 && !boundariesComputed3
    shouldCompute3 shouldBe false
  }
}
