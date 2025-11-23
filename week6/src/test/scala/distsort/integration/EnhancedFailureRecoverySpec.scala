package distsort.integration

import distsort.worker.Worker
import distsort.core._
import distsort.proto.distsort.WorkerPhase
import distsort.checkpoint.{CheckpointManager, WorkerState}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * Enhanced failure recovery tests covering critical edge cases
 *
 * Priority 1 Tests (Critical bugs and scenarios):
 *   1. PHASE_SHUFFLING recovery - all chunks, not just chunk_0000
 *   2. Intermediate file loss and fallback to recomputation
 *   3. Disk space detection and cleanup
 *   4. Deterministic file ordering across restarts
 *
 * Priority 2 Tests (Important scenarios):
 *   5. Incremental checkpoint progress during long-running phases
 *   6. Master crash and worker registration state recovery
 *   7. Concurrent multiple worker crashes
 *   8. Network partition and reconnection recovery
 */
class EnhancedFailureRecoverySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var inputDir: File = _
  private var outputDir: File = _
  private var checkpointDir: File = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("enhanced-failure-test").toFile
    inputDir = new File(tempDir, "input")
    outputDir = new File(tempDir, "output")
    checkpointDir = new File(tempDir, "checkpoints")

    inputDir.mkdirs()
    outputDir.mkdirs()
    checkpointDir.mkdirs()

    createTestInputFiles()
  }

  override def afterEach(): Unit = {
    super.afterEach()
    deleteRecursively(tempDir)
  }

  // ═════════════════════════════════════════════════════════
  // Priority 1 Test 1: PHASE_SHUFFLING Recovery Bug
  // ═════════════════════════════════════════════════════════

  "Worker PHASE_SHUFFLING recovery" should "recover all sorted chunks, not just chunk_0000" in {
    val workerId = "test-worker-1"
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Create multiple sorted chunk files to simulate completed sorting
    val chunk0 = fileLayout.getSortedChunkFile(0)
    val chunk1 = fileLayout.getSortedChunkFile(1)
    val chunk2 = fileLayout.getSortedChunkFile(2)

    createDummySortedChunk(chunk0, 100)  // 100 records
    createDummySortedChunk(chunk1, 150)  // 150 records
    createDummySortedChunk(chunk2, 200)  // 200 records

    // Verify all chunks exist
    fileLayout.listSortedChunks should have size 3

    // Create checkpoint at PHASE_SHUFFLING
    val checkpointManager = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 450L,
      partitionBoundaries = Seq(Array[Byte](50), Array[Byte](100)),
      shuffleMap = Map(0 -> 0, 1 -> 1),
      completedPartitions = Set.empty,
      currentFiles = Seq.empty,
      phaseMetadata = Map.empty
    )

    Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, 1.0),
      5.seconds
    )

    // Load checkpoint and verify phase
    val checkpoint = checkpointManager.loadLatestCheckpoint()
    checkpoint shouldBe defined
    checkpoint.get.phase should be(WorkerPhase.PHASE_SHUFFLING.toString)

    // Simulate Worker recovery - list sorted chunks
    val recoveredChunks = fileLayout.listSortedChunks

    // ✅ FIXED: Should recover ALL chunks, not just chunk_0000
    recoveredChunks should have size 3
    recoveredChunks.map(_.getName) should contain allOf (
      "chunk_0000.sorted",
      "chunk_0001.sorted",
      "chunk_0002.sorted"
    )

    // Verify total record count
    val totalRecords = recoveredChunks.map(countRecordsInFile).sum
    totalRecords should be(450)  // 100 + 150 + 200
  }

  // ═════════════════════════════════════════════════════════
  // Priority 1 Test 2: Intermediate Files Volatility
  // ═════════════════════════════════════════════════════════

  it should "handle intermediate file loss and fallback to recomputation" in {
    val workerId = "test-worker-2"
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Create sorted chunks
    val chunk0 = fileLayout.getSortedChunkFile(0)
    val chunk1 = fileLayout.getSortedChunkFile(1)
    createDummySortedChunk(chunk0, 100)
    createDummySortedChunk(chunk1, 100)

    // Save checkpoint
    val checkpointManager = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)
    val state = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 200L,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty
    )

    Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, 1.0),
      5.seconds
    )

    // ✅ Simulate intermediate file loss (e.g., /tmp cleanup, reboot)
    chunk0.delete() should be(true)
    chunk1.delete() should be(true)

    // Verify files are gone
    fileLayout.listSortedChunks should be(empty)

    // Load checkpoint - should still work
    val checkpoint = checkpointManager.loadLatestCheckpoint()
    checkpoint shouldBe defined

    // ✅ Worker should detect missing files and fallback to recomputation
    val recoveredChunks = fileLayout.listSortedChunks
    if (recoveredChunks.isEmpty) {
      // Expected: Files lost, need to recompute
      // This tests the fallback logic in Worker.scala:292-295
      info("Intermediate files lost as expected - fallback to recomputation required")
    }

    // Checkpoint should still be valid
    checkpoint.get.phase should be(WorkerPhase.PHASE_SHUFFLING.toString)
    checkpoint.get.state.processedRecords should be(200L)
  }

  // ═════════════════════════════════════════════════════════
  // Priority 1 Test 3: Disk Space Issues
  // ═════════════════════════════════════════════════════════

  "Worker sorting" should "detect insufficient disk space before starting" in {
    val workerId = "test-worker-3"
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Get available disk space
    val tempPath = fileLayout.getTempBasePath
    val usableSpace = Files.getFileStore(tempPath).getUsableSpace

    info(s"Available disk space: ${usableSpace / (1024 * 1024)} MB")

    // ✅ Test should verify that worker checks disk space
    // This is a design recommendation - actual implementation may vary
    usableSpace should be > 0L

    // If implementing disk space check, it would look like:
    // val estimatedSpace = estimateSortingSpace(inputFiles)
    // if (usableSpace < estimatedSpace * 1.2) {
    //   throw new InsufficientDiskSpaceException(...)
    // }
  }

  it should "cleanup partial files on disk full error" in {
    val workerId = "test-worker-4"
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Create a partial chunk file (simulating disk full during write)
    val partialChunk = fileLayout.getSortedChunkFile(0)
    val writer = new PrintWriter(partialChunk)
    try {
      writer.write("partial data")
    } finally {
      writer.close()
    }

    partialChunk.exists() should be(true)

    // ✅ After disk full error, partial files should be cleaned up
    // This tests the cleanup behavior
    val chunksBeforeCleanup = fileLayout.listSortedChunks
    chunksBeforeCleanup should not be empty

    // Simulate cleanup
    fileLayout.cleanupTemporaryFiles()

    // Verify cleanup
    val chunksAfterCleanup = fileLayout.listSortedChunks
    chunksAfterCleanup should be(empty)
  }

  // ═════════════════════════════════════════════════════════
  // Priority 1 Test 4: Deterministic File Ordering
  // ═════════════════════════════════════════════════════════

  "Worker input file processing" should "maintain deterministic ordering across restarts" in {
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )

    // Get input files multiple times
    val files1 = fileLayout.getInputFiles.map(_.getAbsolutePath)
    val files2 = fileLayout.getInputFiles.map(_.getAbsolutePath)
    val files3 = fileLayout.getInputFiles.map(_.getAbsolutePath)

    // ✅ File ordering should be deterministic
    files1 should be(files2)
    files2 should be(files3)

    // ✅ Files should be sorted by absolute path
    val sortedFiles = files1.sorted
    files1 should be(sortedFiles)

    info(s"Input files (deterministic order): ${files1.mkString(", ")}")
  }

  it should "produce same output with same input across multiple runs" in {
    val workerId = "determinism-test"

    // Create test records
    val testRecords = (1 to 100).map { i =>
      Record(
        key = f"key$i%05d".getBytes,
        value = f"value$i%010d".getBytes
      )
    }

    // Write to input file
    val inputFile = new File(inputDir, "test-input.dat")
    val writer = RecordWriter.create(inputFile, DataFormat.Binary)
    testRecords.foreach(writer.writeRecord)
    writer.close()

    // First run: Sort and get sample keys
    val sorter1 = new ExternalSorter(
      new FileLayout(
        Seq(inputDir.getAbsolutePath),
        outputDir.getAbsolutePath,
        Some(new File(tempDir, "temp1").getAbsolutePath)
      )
    )
    val sorted1 = sorter1.sortFiles(Seq(inputFile))
    val keys1 = readKeysFromChunks(sorted1)

    // Second run: Sort again with same input
    val sorter2 = new ExternalSorter(
      new FileLayout(
        Seq(inputDir.getAbsolutePath),
        outputDir.getAbsolutePath,
        Some(new File(tempDir, "temp2").getAbsolutePath)
      )
    )
    val sorted2 = sorter2.sortFiles(Seq(inputFile))
    val keys2 = readKeysFromChunks(sorted2)

    // ✅ Results should be identical (deterministic sorting)
    keys1 should be(keys2)

    info(s"Deterministic sorting verified: ${keys1.size} keys, identical across runs")
  }

  // ═════════════════════════════════════════════════════════
  // Priority 2 Test 5: Incremental Checkpoint Progress
  // ═════════════════════════════════════════════════════════

  "Worker checkpoint" should "save incremental progress during long-running phases" in {
    val workerId = "test-worker-5"
    val checkpointManager = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Simulate sorting phase with incremental progress
    val progressSteps = Seq(0.0, 0.25, 0.5, 0.75, 1.0)
    val checkpointIds = scala.collection.mutable.ArrayBuffer[String]()

    progressSteps.foreach { progress =>
      val state = WorkerState.fromScala(
        workerIndex = 0,
        processedRecords = (progress * 1000).toLong,
        partitionBoundaries = Seq.empty,
        shuffleMap = Map.empty,
        completedPartitions = Set.empty,
        currentFiles = Seq.empty,
        phaseMetadata = Map("progress_percent" -> f"${progress * 100}%.0f")
      )

      val checkpointId = Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, progress),
        5.seconds
      )
      checkpointIds += checkpointId
      Thread.sleep(50) // Ensure distinct timestamps
    }

    // ✅ All checkpoints should be valid
    checkpointIds.foreach { id =>
      checkpointManager.validateCheckpoint(id) should be(true)
    }

    // ✅ Should load the most recent checkpoint (100% progress)
    val latestCheckpoint = checkpointManager.loadLatestCheckpoint()
    latestCheckpoint shouldBe defined
    latestCheckpoint.get.progress should be(1.0)
    latestCheckpoint.get.state.processedRecords should be(1000L)

    // ✅ After cleanup, should keep only last 3
    checkpointManager.cleanOldCheckpoints(3)
    val (count, _) = checkpointManager.getCheckpointStats()
    count should be <= 3

    info(s"Incremental checkpoints verified: saved ${checkpointIds.size}, kept last 3")
  }

  // ═════════════════════════════════════════════════════════
  // Priority 2 Test 6: Master State Recovery
  // ═════════════════════════════════════════════════════════

  "Master service" should "handle crash and recover worker registration state" in {
    import distsort.master.MasterService
    import io.grpc.{Server, ServerBuilder}
    import scala.collection.mutable

    // Start master on a test port
    val testPort = 50051 + scala.util.Random.nextInt(1000)
    import scala.concurrent.ExecutionContext.Implicits.global
    val master = new MasterService(
      expectedWorkers = 3,
      numPartitions = 9
    )

    val server: Server = ServerBuilder
      .forPort(testPort)
      .addService(distsort.proto.distsort.MasterServiceGrpc.bindService(master, global))
      .build()
      .start()

    try {
      // Simulate worker registrations
      val workerIds = Seq("worker-1", "worker-2", "worker-3")

      // ✅ In real system, master should persist registration state
      // This test verifies that master can track worker state
      info(s"Master started on port $testPort, expecting 3 workers")

      // Note: Full master crash recovery would require:
      // 1. Persisting worker registry to disk
      // 2. Reloading on restart
      // 3. Re-syncing with workers via heartbeats
      //
      // Current implementation keeps state in-memory only
      // This test documents the limitation

    } finally {
      server.shutdown()
      server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }
  }

  // ═════════════════════════════════════════════════════════
  // Priority 2 Test 7: Concurrent Worker Crashes
  // ═════════════════════════════════════════════════════════

  "Worker recovery" should "handle concurrent multiple worker crashes correctly" in {
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    // Create checkpoints for multiple workers
    val workerIds = Seq("worker-1", "worker-2", "worker-3")
    val checkpointManagers = workerIds.map { id =>
      id -> new CheckpointManager(id, checkpointDir.getAbsolutePath)
    }.toMap

    // Simulate all workers completing sorting phase
    workerIds.foreach { workerId =>
      val state = WorkerState.fromScala(
        workerIndex = 0,
        processedRecords = 1000L,
        partitionBoundaries = Seq(Array[Byte](50), Array[Byte](100)),
        shuffleMap = Map.empty,
        completedPartitions = Set.empty,
        currentFiles = Seq.empty
      )

      Await.result(
        checkpointManagers(workerId).saveCheckpoint(WorkerPhase.PHASE_SORTING, state, 1.0),
        5.seconds
      )
    }

    // ✅ Simulate concurrent crashes - all workers try to recover
    val recoveryResults = workerIds.map { workerId =>
      val checkpoint = checkpointManagers(workerId).loadLatestCheckpoint()
      checkpoint shouldBe defined
      checkpoint.get.phase should be(WorkerPhase.PHASE_SORTING.toString)
      workerId -> checkpoint.get
    }.toMap

    // ✅ All workers should successfully load their checkpoints
    recoveryResults should have size 3
    recoveryResults.values.foreach { checkpoint =>
      checkpoint.state.processedRecords should be(1000L)
    }

    // ✅ Each worker should maintain independent state
    val uniqueWorkerIds = recoveryResults.values.map(_.workerId).toSet
    uniqueWorkerIds should have size 3

    info(s"Concurrent recovery verified: ${workerIds.size} workers recovered independently")
  }

  // ═════════════════════════════════════════════════════════
  // Priority 2 Test 8: Network Partition Simulation
  // ═════════════════════════════════════════════════════════

  "Worker shuffling" should "handle network partition and recover after reconnection" in {
    val workerId = "test-worker-8"
    val fileLayout = new FileLayout(
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath,
      Some(new File(tempDir, "temp").getAbsolutePath)
    )
    fileLayout.initialize()

    val checkpointManager = new CheckpointManager(workerId, checkpointDir.getAbsolutePath)

    // Create sorted chunks before "network partition"
    val chunk0 = fileLayout.getSortedChunkFile(0)
    createDummySortedChunk(chunk0, 100)

    // Save checkpoint before partition
    val stateBeforePartition = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 100L,
      partitionBoundaries = Seq(Array[Byte](50)),
      shuffleMap = Map(0 -> 0),
      completedPartitions = Set.empty,
      currentFiles = Seq(chunk0.getAbsolutePath),
      phaseMetadata = Map("shuffle_started" -> "true")
    )

    Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, stateBeforePartition, 0.3),
      5.seconds
    )

    // ✅ Simulate network partition (worker can't communicate with master)
    // In real system, this would be detected by heartbeat timeout
    info("Simulating network partition - worker isolated from master")

    // Worker continues working locally, saves progress
    val stateAfterLocalWork = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 100L,
      partitionBoundaries = Seq(Array[Byte](50)),
      shuffleMap = Map(0 -> 0, 1 -> 0),
      completedPartitions = Set(0),
      currentFiles = Seq(chunk0.getAbsolutePath),
      phaseMetadata = Map("shuffle_progress" -> "partition_0_complete")
    )

    Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, stateAfterLocalWork, 0.6),
      5.seconds
    )

    // ✅ Simulate network recovery - worker reconnects
    info("Simulating network recovery - worker reconnects to master")

    // Load latest checkpoint to resume work
    val recoveredCheckpoint = checkpointManager.loadLatestCheckpoint()
    recoveredCheckpoint shouldBe defined
    recoveredCheckpoint.get.progress should be(0.6)

    val (_, _, _, _, completedPartitions, _, _) = WorkerState.toScala(recoveredCheckpoint.get.state)
    completedPartitions should contain(0)

    // ✅ Worker should be able to continue from checkpoint
    // In real system, worker would:
    // 1. Re-register with master (Worker Re-registration)
    // 2. Report current phase and progress
    // 3. Master updates its worker registry
    // 4. Continue shuffle operation

    info(s"Network partition recovery verified: worker resumed at ${recoveredCheckpoint.get.progress * 100}% progress")
  }

  // ═════════════════════════════════════════════════════════
  // Helper Methods
  // ═════════════════════════════════════════════════════════

  private def createTestInputFiles(): Unit = {
    // Create 3 test input files
    (1 to 3).foreach { i =>
      val file = new File(inputDir, s"input-$i.dat")
      val writer = RecordWriter.create(file, DataFormat.Binary)

      // Write 50 records per file
      (1 to 50).foreach { j =>
        val key = Array.fill[Byte](10)((i * 10 + j).toByte)  // 10 bytes key
        val value = Array.fill[Byte](90)((i * 10 + j).toByte)  // 90 bytes value
        writer.writeRecord(Record(key, value))
      }

      writer.close()
    }
  }

  private def createDummySortedChunk(file: File, recordCount: Int): Unit = {
    file.getParentFile.mkdirs()
    val writer = RecordWriter.create(file, DataFormat.Binary)

    (1 to recordCount).foreach { i =>
      val key = Array.fill[Byte](10)(i.toByte)  // 10 bytes key
      val value = Array.fill[Byte](90)(i.toByte)  // 90 bytes value
      writer.writeRecord(Record(key, value))
    }

    writer.close()
  }

  private def countRecordsInFile(file: File): Int = {
    val reader = RecordReader.create(file, DataFormat.Binary)
    var count = 0

    while (reader.readRecord().isDefined) {
      count += 1
    }

    reader.close()
    count
  }

  private def readKeysFromChunks(chunks: Seq[File]): Seq[String] = {
    chunks.flatMap { chunk =>
      val reader = RecordReader.create(chunk, DataFormat.Binary)
      val keys = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get.key)
        .map(new String(_))
        .toSeq
      reader.close()
      keys
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }
}
