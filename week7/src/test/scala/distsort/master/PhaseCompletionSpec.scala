package distsort.master

import distsort.proto.distsort._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * TDD Test for Phase Completion Tracking
 *
 * RED -> GREEN -> REFACTOR cycle
 *
 * This test verifies that:
 * 1. MasterService can track phase completions from multiple workers
 * 2. waitForPhaseCompletion() blocks until all workers report completion
 * 3. Timeout works correctly when workers don't report in time
 */
class PhaseCompletionSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds))

  behavior of "MasterService Phase Completion Tracking"

  it should "wait for all workers to complete a phase" in {
    // Given: MasterService expecting 3 workers
    val masterService = new MasterService(expectedWorkers = 3, initialNumPartitions = 3)

    // Initialize phase tracking for SORTING phase
    masterService.initPhaseTracking(WorkerPhase.PHASE_SORTING)

    // When: Simulating workers reporting completion in separate threads
    val workers = (0 until 3).map(i => s"worker-$i")
    workers.foreach { workerId =>
      // First, register the worker
      val registerRequest = RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + workerId.split("-")(1).toInt,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      Await.result(masterService.registerWorker(registerRequest), 5.seconds)
    }

    // Start a thread to simulate workers completing their work
    new Thread(() => {
      Thread.sleep(1000) // Simulate work taking 1 second

      workers.foreach { workerId =>
        val request = PhaseCompleteRequest(
          workerId = workerId,
          phase = WorkerPhase.PHASE_SORTING,
          details = "Sorting completed"
        )
        Await.result(masterService.reportPhaseComplete(request), 5.seconds)
        Thread.sleep(100) // Stagger completions slightly
      }
    }).start()

    // Then: waitForPhaseCompletion should block until all workers complete
    val startTime = System.currentTimeMillis()
    val completed = masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SORTING, timeoutSeconds = 10)
    val duration = System.currentTimeMillis() - startTime

    // Assertions
    completed shouldBe true
    duration should be >= 1000L // Should wait at least 1 second
    duration should be < 5000L  // Should complete well before timeout
  }

  it should "timeout when workers don't complete in time" in {
    // Given: MasterService expecting 3 workers
    val masterService = new MasterService(expectedWorkers = 3, initialNumPartitions = 3)

    // Register workers
    (0 until 3).foreach { i =>
      val workerId = s"worker-$i"
      val registerRequest = RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + i,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      Await.result(masterService.registerWorker(registerRequest), 5.seconds)
    }

    masterService.initPhaseTracking(WorkerPhase.PHASE_SHUFFLING)

    // When: Only 2 workers complete (one is stuck)
    new Thread(() => {
      Thread.sleep(500)
      (0 until 2).foreach { i =>
        val request = PhaseCompleteRequest(
          workerId = s"worker-$i",
          phase = WorkerPhase.PHASE_SHUFFLING,
          details = "Shuffling completed"
        )
        Await.result(masterService.reportPhaseComplete(request), 5.seconds)
      }
      // worker-2 never reports!
    }).start()

    // Then: Should timeout after specified duration
    val startTime = System.currentTimeMillis()
    val completed = masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SHUFFLING, timeoutSeconds = 2)
    val duration = System.currentTimeMillis() - startTime

    // Assertions
    completed shouldBe false
    duration should be >= 2000L // Should wait full timeout
    duration should be < 3000L  // Should not wait much longer
  }

  it should "track multiple phases independently" in {
    // Given: MasterService with 3 workers
    val masterService = new MasterService(expectedWorkers = 3, initialNumPartitions = 3)

    // Register workers
    val workers = (0 until 3).map { i =>
      val workerId = s"worker-$i"
      val registerRequest = RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + i,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      Await.result(masterService.registerWorker(registerRequest), 5.seconds)
      workerId
    }

    // Initialize multiple phases
    masterService.initPhaseTracking(WorkerPhase.PHASE_SORTING)
    masterService.initPhaseTracking(WorkerPhase.PHASE_SHUFFLING)

    // When: Workers complete SORTING phase
    workers.foreach { workerId =>
      val request = PhaseCompleteRequest(
        workerId = workerId,
        phase = WorkerPhase.PHASE_SORTING,
        details = "Sorting done"
      )
      Await.result(masterService.reportPhaseComplete(request), 5.seconds)
    }

    // Then: SORTING should be complete, but SHUFFLING should not
    masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SORTING, timeoutSeconds = 1) shouldBe true
    masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SHUFFLING, timeoutSeconds = 1) shouldBe false
  }

  it should "reset phase tracking correctly" in {
    // Given: MasterService with completed phase
    val masterService = new MasterService(expectedWorkers = 2, initialNumPartitions = 2)

    // Register workers
    val workers = (0 until 2).map { i =>
      val workerId = s"worker-$i"
      val registerRequest = RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + i,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      Await.result(masterService.registerWorker(registerRequest), 5.seconds)
      workerId
    }

    masterService.initPhaseTracking(WorkerPhase.PHASE_SORTING)

    // Complete the phase
    workers.foreach { workerId =>
      val request = PhaseCompleteRequest(
        workerId = workerId,
        phase = WorkerPhase.PHASE_SORTING,
        details = "Done"
      )
      Await.result(masterService.reportPhaseComplete(request), 5.seconds)
    }

    masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SORTING, timeoutSeconds = 1) shouldBe true

    // When: Reset the phase
    masterService.resetPhaseTracking(WorkerPhase.PHASE_SORTING)

    // Then: Should need to wait again
    masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SORTING, timeoutSeconds = 1) shouldBe false
  }

  it should "get completion count correctly" in {
    // Given: MasterService with partial completions
    val masterService = new MasterService(expectedWorkers = 3, initialNumPartitions = 3)

    // Register workers
    (0 until 3).foreach { i =>
      val workerId = s"worker-$i"
      val registerRequest = RegisterWorkerRequest(
        workerId = workerId,
        host = "localhost",
        port = 5000 + i,
        numCores = 4,
        availableMemory = 8L * 1024 * 1024 * 1024
      )
      Await.result(masterService.registerWorker(registerRequest), 5.seconds)
    }

    masterService.initPhaseTracking(WorkerPhase.PHASE_MERGING)

    // When: 2 out of 3 workers complete
    (0 until 2).foreach { i =>
      val request = PhaseCompleteRequest(
        workerId = s"worker-$i",
        phase = WorkerPhase.PHASE_MERGING,
        details = "Merging done"
      )
      Await.result(masterService.reportPhaseComplete(request), 5.seconds)
    }

    // Then: Completion count should be 2
    masterService.getPhaseCompletionCount(WorkerPhase.PHASE_MERGING) shouldBe 2
  }
}
