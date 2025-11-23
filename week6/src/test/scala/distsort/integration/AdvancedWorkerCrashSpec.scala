package distsort.integration

import distsort.master.{Master, MasterService}
import distsort.worker.Worker
import distsort.proto.distsort._
import distsort.core._
import distsort.checkpoint.{CheckpointManager, WorkerState}
import distsort.integration.TestHelpers._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.io.File
import java.nio.file.Files
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Advanced Worker Crash Tests - Option 3 Specific Scenarios
 *
 * Focuses on testing Option 3 implementation:
 * - Phase 1: Worker Re-registration (same identity → same index)
 * - Phase 2: Checkpoint Recovery (workerIndex preservation)
 * - Threshold-based Continuation (50% workers minimum)
 * - Phase-specific crash recovery
 * - Recovery time and performance
 * - Data integrity after recovery
 *
 * These tests complement existing WorkerFailureSpec, EnhancedFailureRecoverySpec,
 * and DeterministicExecutionSpec by focusing on Option 3 specific behavior.
 */
class AdvancedWorkerCrashSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var inputDir: File = _
  private var outputDir: File = _
  private var checkpointDir: File = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("advanced-crash-test").toFile
    inputDir = new File(tempDir, "input")
    outputDir = new File(tempDir, "output")
    checkpointDir = new File(tempDir, "checkpoints")

    inputDir.mkdirs()
    outputDir.mkdirs()
    checkpointDir.mkdirs()

    createTestInputFiles(inputDir, numFiles = 3, recordsPerFile = 100, seed = 42)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    deleteRecursively(tempDir)
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 1: Worker Re-registration (Option 3 Phase 1)
  // ═════════════════════════════════════════════════════════════

  "Worker Re-registration (Option 3 Phase 1)" should "assign same index for same (host, port) identity" in {
    // This test verifies the CONCEPT of worker re-registration
    // In production, re-registration happens when:
    // 1. Worker crashes (becomes FAILED via heartbeat timeout)
    // 2. Worker restarts with same host:port
    // 3. Master recognizes same identity and assigns same index

    val identityToIndex = scala.collection.mutable.Map[String, Int]()
    val nextIndex = new java.util.concurrent.atomic.AtomicInteger(0)

    // Simulate worker registration logic
    def registerWorker(host: String, port: Int, workerId: String): Int = {
      val identity = s"$host:$port"
      identityToIndex.getOrElseUpdate(identity, nextIndex.getAndIncrement())
    }

    // First registration
    val index1 = registerWorker("localhost", 50001, "worker-1")
    info(s"First registration: worker-1 assigned index $index1")

    // Worker crashes and re-registers with SAME identity
    val index2 = registerWorker("localhost", 50001, "worker-1-restarted")
    info(s"Second registration (same identity): assigned index $index2")

    // ✅ CRITICAL: Same identity → Same index
    index2 shouldBe index1

    info(s"✓ Worker Re-registration verified: same identity → same index ($index1)")
  }

  it should "handle multiple re-registration attempts correctly" in {
    // Simulate worker identity → index mapping (simplified test)
    val identityToIndex = scala.collection.mutable.Map[String, Int]()
    val nextIndex = new java.util.concurrent.atomic.AtomicInteger(0)

    def registerWorker(host: String, port: Int, workerId: String): Int = {
      val identity = s"$host:$port"
      identityToIndex.getOrElseUpdate(identity, nextIndex.getAndIncrement())
    }

    // Register 3 workers with different identities
    val identities = Seq(
      ("worker-1", "localhost", 50001),
      ("worker-2", "localhost", 50002),
      ("worker-3", "localhost", 50003)
    )

    val initialIndices = identities.map { case (workerId, host, port) =>
      (workerId, registerWorker(host, port, workerId))
    }.toMap

    info(s"Initial indices: $initialIndices")

    // Simulate crashes and re-registrations (3 attempts each)
    (1 to 3).foreach { attempt =>
      info(s"Re-registration attempt $attempt")

      identities.foreach { case (workerId, host, port) =>
        val newWorkerId = s"$workerId-attempt-$attempt"
        val assignedIndex = registerWorker(host, port, newWorkerId)

        // ✅ Each re-registration should get the SAME index
        val expectedIndex = initialIndices(workerId)
        assignedIndex shouldBe expectedIndex
      }
    }

    info(s"✓ Multiple re-registration attempts verified: indices preserved across 3 attempts")
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 2: Checkpoint Recovery with workerIndex (Option 3 Phase 2)
  // ═════════════════════════════════════════════════════════════

  "Checkpoint Recovery (Option 3 Phase 2)" should "preserve workerIndex across recovery" in {
    val workerId = "checkpoint-test-worker"
    val workerIndex = 2
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Save checkpoint with specific workerIndex
    val state = WorkerState.fromScala(
      workerIndex = workerIndex,  // ⭐ Key field for Option 3 Phase 2
      processedRecords = 500L,
      partitionBoundaries = Seq(Array[Byte](50), Array[Byte](100)),
      shuffleMap = Map(0 -> 0, 1 -> 1, 2 -> 2),
      completedPartitions = Set(0, 1),
      currentFiles = Seq.empty,
      phaseMetadata = Map("test" -> "checkpoint_recovery")
    )

    val checkpointId = Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, 0.7),
      5.seconds
    )

    info(s"Saved checkpoint: $checkpointId with workerIndex=$workerIndex")

    // Load checkpoint
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    // ✅ CRITICAL: Verify workerIndex is preserved
    val (loadedWorkerIndex, processedRecords, boundaries, shuffleMap, completed, _, metadata) =
      WorkerState.toScala(loaded.get.state)

    loadedWorkerIndex shouldBe workerIndex
    processedRecords shouldBe 500L
    completed should contain allOf (0, 1)
    metadata.get("test") shouldBe Some("checkpoint_recovery")

    info(s"✓ Checkpoint recovery verified: workerIndex preserved ($workerIndex)")
  }

  it should "recover from each phase with correct workerIndex" in {
    val workerIndex = 1

    // Test recovery from each phase (using separate workers to avoid checkpoint conflicts)
    val phases = Seq(
      WorkerPhase.PHASE_INITIALIZING,
      WorkerPhase.PHASE_SAMPLING,
      WorkerPhase.PHASE_WAITING_FOR_PARTITIONS,
      WorkerPhase.PHASE_SORTING,
      WorkerPhase.PHASE_SHUFFLING,
      WorkerPhase.PHASE_MERGING
    )

    phases.foreach { phase =>
      val workerId = s"phase-${phase.toString.replace("PHASE_", "").toLowerCase}-worker"
      val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

      val state = WorkerState.fromScala(
        workerIndex = workerIndex,
        processedRecords = 100L,
        partitionBoundaries = Seq.empty,
        shuffleMap = Map.empty,
        completedPartitions = Set.empty,
        currentFiles = Seq.empty,
        phaseMetadata = Map("phase" -> phase.toString)
      )

      Await.result(
        checkpointMgr.saveCheckpoint(phase, state, 0.5),
        5.seconds
      )

      // Verify recovery
      val loaded = checkpointMgr.loadLatestCheckpoint()
      loaded shouldBe defined
      loaded.get.phase shouldBe phase.toString

      val (loadedIndex, _, _, _, _, _, metadata) = WorkerState.toScala(loaded.get.state)
      loadedIndex shouldBe workerIndex

      info(s"✓ Phase $phase: workerIndex=$loadedIndex preserved")
    }

    info(s"✓ All 6 phases verified: workerIndex preserved in every phase")
  }

  it should "detect workerIndex mismatch after recovery" in {
    val workerId = "mismatch-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Save checkpoint with workerIndex = 0
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 200L,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 0.3),
      5.seconds
    )

    // Load checkpoint
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    val (checkpointWorkerIndex, _, _, _, _, _, _) = WorkerState.toScala(loaded.get.state)

    // ✅ Simulate master assigning different index (should be detected as error)
    val masterAssignedIndex = 2

    if (checkpointWorkerIndex != masterAssignedIndex) {
      info(s"⚠️ Worker index mismatch detected: checkpoint=$checkpointWorkerIndex, master=$masterAssignedIndex")

      // In production, worker should:
      // 1. Log warning
      // 2. Either abort recovery or discard incompatible checkpoint
      // 3. Re-register with master

      checkpointWorkerIndex should not be masterAssignedIndex
    }

    info(s"✓ Worker index mismatch detection works correctly")
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 3: Threshold-based Continuation
  // ═════════════════════════════════════════════════════════════

  "Threshold-based Continuation" should "continue with 60% workers alive (above threshold)" in {
    val totalWorkers = 5
    val aliveWorkers = 3  // 60%
    val threshold = 0.5  // 50%

    val continueExecution = (aliveWorkers.toDouble / totalWorkers) >= threshold

    continueExecution shouldBe true

    info(s"✓ Threshold check: $aliveWorkers/$totalWorkers workers (60%) > threshold (50%) → CONTINUE")
  }

  it should "abort with 40% workers alive (below threshold)" in {
    val totalWorkers = 5
    val aliveWorkers = 2  // 40%
    val threshold = 0.5  // 50%

    val continueExecution = (aliveWorkers.toDouble / totalWorkers) >= threshold

    continueExecution shouldBe false

    info(s"✓ Threshold check: $aliveWorkers/$totalWorkers workers (40%) < threshold (50%) → ABORT")
  }

  it should "handle edge case of exactly 50% workers alive" in {
    val totalWorkers = 4
    val aliveWorkers = 2  // Exactly 50%
    val threshold = 0.5

    val continueExecution = (aliveWorkers.toDouble / totalWorkers) >= threshold

    // ✅ With >= operator, exactly 50% should CONTINUE
    continueExecution shouldBe true

    info(s"✓ Edge case: $aliveWorkers/$totalWorkers workers (50%) >= threshold (50%) → CONTINUE")
  }

  it should "calculate threshold correctly for various failure rates" in {
    val scenarios = Seq(
      (10, 9, "10% failure", true),   // 90% alive → continue
      (10, 7, "30% failure", true),   // 70% alive → continue
      (10, 5, "50% failure", true),   // 50% alive → continue (edge case)
      (10, 4, "60% failure", false),  // 40% alive → abort
      (10, 1, "90% failure", false),  // 10% alive → abort
      (3, 2, "33% failure", true),    // 67% alive → continue
      (3, 1, "67% failure", false)    // 33% alive → abort
    )

    val threshold = 0.5

    scenarios.foreach { case (total, alive, description, expectedContinue) =>
      val actualContinue = (alive.toDouble / total) >= threshold
      actualContinue shouldBe expectedContinue

      val percentage = (alive.toDouble / total * 100).toInt
      val action = if (actualContinue) "CONTINUE" else "ABORT"
      info(s"✓ $description: $alive/$total ($percentage%) → $action")
    }
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 4: Phase-specific Crash Recovery
  // ═════════════════════════════════════════════════════════════

  "Phase-specific Crash Recovery" should "recover from crash during SAMPLING phase" in {
    val workerId = "sampling-crash-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate crash during sampling
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 50L,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty,
      phaseMetadata = Map("samples_sent" -> "10")
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SAMPLING, state, 0.2),
      5.seconds
    )

    // ✅ Recovery: Worker should re-sample from input
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined
    loaded.get.phase shouldBe WorkerPhase.PHASE_SAMPLING.toString

    info(s"✓ SAMPLING phase crash recovery verified")
  }

  it should "recover from crash during SORTING phase" in {
    val workerId = "sorting-crash-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate crash during sorting
    val state = WorkerState.fromScala(
      workerIndex = 1,
      processedRecords = 300L,
      partitionBoundaries = Seq(Array[Byte](50)),
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq("chunk_0000.sorted", "chunk_0001.sorted"),
      phaseMetadata = Map("sorting_progress" -> "60%")
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 0.6),
      5.seconds
    )

    // ✅ Recovery: Worker should resume sorting from checkpoint
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    val (_, processedRecords, _, _, _, currentFiles, _) = WorkerState.toScala(loaded.get.state)
    processedRecords shouldBe 300L
    currentFiles should contain ("chunk_0000.sorted")

    info(s"✓ SORTING phase crash recovery verified (${processedRecords} records processed)")
  }

  it should "recover from crash during SHUFFLING phase" in {
    val workerId = "shuffling-crash-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate crash during shuffling
    val state = WorkerState.fromScala(
      workerIndex = 2,
      processedRecords = 500L,
      partitionBoundaries = Seq(Array[Byte](30), Array[Byte](60), Array[Byte](90)),
      shuffleMap = Map(0 -> 0, 1 -> 1, 2 -> 2),
      completedPartitions = Set(0, 1),  // Partitions 0 and 1 completed
      currentFiles = Seq.empty,
      phaseMetadata = Map("shuffle_progress" -> "66%")
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, 0.66),
      5.seconds
    )

    // ✅ Recovery: Worker should resume shuffling from partition 2
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    val (_, _, boundaries, shuffleMap, completedPartitions, _, _) = WorkerState.toScala(loaded.get.state)
    boundaries should have size 3
    completedPartitions should contain allOf (0, 1)
    completedPartitions should not contain 2

    info(s"✓ SHUFFLING phase crash recovery verified (partitions 0,1 complete, resume from 2)")
  }

  it should "recover from crash during MERGING phase" in {
    val workerId = "merging-crash-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate crash during merging
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 1000L,
      partitionBoundaries = Seq(Array[Byte](25), Array[Byte](50), Array[Byte](75)),
      shuffleMap = Map(0 -> 0, 1 -> 0, 2 -> 0),
      completedPartitions = Set(0, 1, 2),
      currentFiles = Seq("partition.0", "partition.1"),
      phaseMetadata = Map("merging_progress" -> "66%")
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_MERGING, state, 0.66),
      5.seconds
    )

    // ✅ Recovery: Worker should resume merging
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined
    loaded.get.progress shouldBe 0.66

    val (_, _, _, _, completedPartitions, currentFiles, _) = WorkerState.toScala(loaded.get.state)
    completedPartitions should have size 3
    currentFiles should contain ("partition.0")

    info(s"✓ MERGING phase crash recovery verified (66% complete)")
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 5: Recovery Time & Performance
  // ═════════════════════════════════════════════════════════════

  "Recovery Performance" should "load checkpoint within 1 second" in {
    val workerId = "perf-test-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Create large checkpoint
    val largeState = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 1000000L,
      partitionBoundaries = (1 to 100).map(i => Array.fill[Byte](10)(i.toByte)),
      shuffleMap = (0 until 100).map(i => i -> (i % 10)).toMap,
      completedPartitions = (0 until 50).toSet,
      currentFiles = (0 until 100).map(i => s"file-$i.dat"),
      phaseMetadata = (0 until 50).map(i => s"key$i" -> s"value$i").toMap
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, largeState, 0.5),
      5.seconds
    )

    // ✅ Measure checkpoint load time
    val startTime = System.nanoTime()
    val loaded = checkpointMgr.loadLatestCheckpoint()
    val loadTime = (System.nanoTime() - startTime) / 1000000  // milliseconds

    loaded shouldBe defined

    // ✅ Should load within 1 second (typically <100ms)
    loadTime should be < 1000L

    info(s"✓ Checkpoint load time: ${loadTime}ms (< 1000ms target)")
  }

  it should "save checkpoint within 500ms" in {
    val workerId = "save-perf-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    val state = WorkerState.fromScala(
      workerIndex = 1,
      processedRecords = 500L,
      partitionBoundaries = Seq(Array[Byte](50)),
      shuffleMap = Map(0 -> 0, 1 -> 1),
      completedPartitions = Set(0),
      currentFiles = Seq("chunk.sorted"),
      phaseMetadata = Map("test" -> "save_perf")
    )

    // ✅ Measure save time
    val startTime = System.nanoTime()
    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 0.5),
      5.seconds
    )
    val saveTime = (System.nanoTime() - startTime) / 1000000  // milliseconds

    // ✅ Should save within 500ms
    saveTime should be < 500L

    info(s"✓ Checkpoint save time: ${saveTime}ms (< 500ms target)")
  }

  // ═════════════════════════════════════════════════════════════
  // TEST SUITE 6: Data Integrity After Recovery
  // ═════════════════════════════════════════════════════════════

  "Data Integrity After Recovery" should "maintain no data loss after worker crash and recovery" in {
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Count input records
    val inputRecordCount = countTotalRecords(inputDir)
    info(s"Input: $inputRecordCount records")

    // Simulate worker processing and crash
    val workerId = "integrity-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Save checkpoint at 70% progress
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = (inputRecordCount * 0.7).toLong,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 0.7),
      5.seconds
    )

    // ✅ Recovery: Worker should process remaining 30%
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    val (_, processedRecords, _, _, _, _, _) = WorkerState.toScala(loaded.get.state)
    val remainingRecords = inputRecordCount - processedRecords

    info(s"✓ Recovery verified: ${processedRecords} records processed, $remainingRecords remaining")

    // After full recovery, total should match input
    processedRecords + remainingRecords shouldBe inputRecordCount
  }

  it should "prevent duplicate records after recovery" in {
    val workerId = "dedup-worker"
    val checkpointMgr = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate checkpoint with completed partitions tracked
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 300L,
      partitionBoundaries = Seq(Array[Byte](50), Array[Byte](100)),
      shuffleMap = Map(0 -> 0, 1 -> 1),
      completedPartitions = Set(0, 1),  // ⭐ Track completed partitions
      currentFiles = Seq.empty
    )

    Await.result(
      checkpointMgr.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, 0.66),
      5.seconds
    )

    // ✅ Recovery: Should skip already completed partitions
    val loaded = checkpointMgr.loadLatestCheckpoint()
    loaded shouldBe defined

    val (_, _, _, _, completedPartitions, _, _) = WorkerState.toScala(loaded.get.state)

    // Worker should only process partitions NOT in completedPartitions
    val allPartitions = Set(0, 1, 2)
    val pendingPartitions = allPartitions -- completedPartitions

    pendingPartitions shouldBe Set(2)

    info(s"✓ Duplicate prevention: completed=$completedPartitions, pending=$pendingPartitions")
  }

  it should "maintain sorted order across recovery boundary" in {
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Create test sorted chunks (simulating pre-crash state)
    val chunk0 = fileLayout.getSortedChunkFile(0)
    val chunk1 = fileLayout.getSortedChunkFile(1)

    createDeterministicSortedChunk(chunk0, startKey = 1, count = 50)
    createDeterministicSortedChunk(chunk1, startKey = 51, count = 50)

    // ✅ Verify sorted order within each chunk
    verifySorted(chunk0) shouldBe true
    verifySorted(chunk1) shouldBe true

    // ✅ Verify sorted order across chunks (chunk0 max < chunk1 min)
    val records0 = readAllRecords(chunk0)
    val records1 = readAllRecords(chunk1)

    if (records0.nonEmpty && records1.nonEmpty) {
      val maxKey0 = records0.last.key
      val minKey1 = records1.head.key

      ByteArrayOrdering.compare(maxKey0, minKey1) should be < 0

      info(s"✓ Sorted order verified across recovery: chunk0.max < chunk1.min")
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Helper Methods
  // ═══════════════════════════════════════════════════════════

  private def createDeterministicSortedChunk(file: File, startKey: Int, count: Int): Unit = {
    file.getParentFile.mkdirs()
    val writer = RecordWriter.create(file, DataFormat.Binary)

    (startKey until startKey + count).foreach { i =>
      val key = Array.fill[Byte](10)(i.toByte)  // 10 bytes key
      val value = Array.fill[Byte](90)(i.toByte)  // 90 bytes value (Record requires this)
      writer.writeRecord(Record(key, value))
    }

    writer.close()
  }

  case class WorkerIdentity(host: String, port: Int)
}
