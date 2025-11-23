package distsort.checkpoint

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await
import java.nio.file.{Files, Paths}
import distsort.proto.distsort.WorkerPhase

class CheckpointManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val testWorkerId = "test-worker"
  private val testDir = s"/tmp/test-checkpoints-${System.currentTimeMillis()}"
  private var checkpointManager: CheckpointManager = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    Files.createDirectories(Paths.get(testDir))
    checkpointManager = new CheckpointManager(testWorkerId, testDir)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    // Clean up test directory
    try {
      import java.io.File
      def deleteRecursively(file: File): Unit = {
        if (file.isDirectory) {
          file.listFiles.foreach(deleteRecursively)
        }
        file.delete()
      }
      deleteRecursively(new java.io.File(testDir))
    } catch {
      case _: Exception => // Ignore cleanup errors
    }
  }

  "CheckpointManager" should "save and load checkpoints correctly" in {
    val testState = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 1000L,
      partitionBoundaries = Seq(Array[Byte](1, 2, 3), Array[Byte](4, 5, 6)),
      shuffleMap = Map(0 -> 1, 1 -> 2, 2 -> 0),
      completedPartitions = Set(0, 2, 4),
      currentFiles = Seq("/tmp/file1.txt", "/tmp/file2.txt"),
      phaseMetadata = Map("test" -> "value")
    )

    // Save checkpoint
    val checkpointId = Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SORTING, testState, 0.5),
      5.seconds
    )

    checkpointId should not be empty

    // Load checkpoint
    val loadedCheckpoint = checkpointManager.loadLatestCheckpoint()

    loadedCheckpoint should be(defined)
    loadedCheckpoint.get.workerId should be(testWorkerId)
    loadedCheckpoint.get.phase should be(WorkerPhase.PHASE_SORTING.toString)
    loadedCheckpoint.get.progress should be(0.5)

    // Convert back to Scala collections for assertions
    val (_, processedRecords, boundaries, shuffleMap, completedPartitions, currentFiles, metadata) =
      WorkerState.toScala(loadedCheckpoint.get.state)

    processedRecords should be(1000L)
    shuffleMap should be(Map(0 -> 1, 1 -> 2, 2 -> 0))
    completedPartitions should be(Set(0, 2, 4))
  }

  it should "validate checkpoints correctly" in {
    val testState = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 500L,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty
    )

    val checkpointId = Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SAMPLING, testState, 0.25),
      5.seconds
    )

    // Valid checkpoint should pass validation
    checkpointManager.validateCheckpoint(checkpointId) should be(true)

    // Non-existent checkpoint should fail validation
    checkpointManager.validateCheckpoint("non-existent-checkpoint") should be(false)
  }

  it should "load the most recent checkpoint when multiple exist" in {
    val states = Seq(
      WorkerState.fromScala(0, 100L, Seq.empty, Map.empty, Set.empty, Seq.empty, Map.empty),
      WorkerState.fromScala(0, 200L, Seq.empty, Map.empty, Set.empty, Seq.empty, Map.empty),
      WorkerState.fromScala(0, 300L, Seq.empty, Map.empty, Set.empty, Seq.empty, Map.empty)
    )

    // Save multiple checkpoints
    for ((state, i) <- states.zipWithIndex) {
      Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, i * 0.33),
        5.seconds
      )
      Thread.sleep(100) // Ensure different timestamps
    }

    // Should load the most recent one
    val loadedCheckpoint = checkpointManager.loadLatestCheckpoint()
    loadedCheckpoint should be(defined)
    loadedCheckpoint.get.state.processedRecords should be(300L)
  }

  it should "clean old checkpoints keeping only specified number" in {
    // Save 5 checkpoints
    for (i <- 1 to 5) {
      val state = WorkerState.fromScala(
        0,  // workerIndex
        i * 100L,  // processedRecords
        Seq.empty,  // partitionBoundaries
        Map.empty,  // shuffleMap
        Set.empty,  // completedPartitions
        Seq.empty,  // currentFiles
        Map.empty   // phaseMetadata
      )
      Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, i * 0.2),
        5.seconds
      )
      Thread.sleep(100)
    }

    // Clean old checkpoints, keeping only 2
    checkpointManager.cleanOldCheckpoints(2)

    // Check checkpoint count
    val (count, _) = checkpointManager.getCheckpointStats()
    count should be <= 2
  }

  it should "delete all checkpoints when requested" in {
    // Save some checkpoints
    for (i <- 1 to 3) {
      val state = WorkerState.fromScala(
        0,  // workerIndex
        i * 100L,  // processedRecords
        Seq.empty,  // partitionBoundaries
        Map.empty,  // shuffleMap
        Set.empty,  // completedPartitions
        Seq.empty,  // currentFiles
        Map.empty   // phaseMetadata
      )
      Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SORTING, state, i * 0.33),
        5.seconds
      )
    }

    // Delete all checkpoints
    checkpointManager.deleteAllCheckpoints()

    // No checkpoint should be available
    checkpointManager.loadLatestCheckpoint() should be(None)

    val (count, size) = checkpointManager.getCheckpointStats()
    count should be(0)
    size should be(0L)
  }

  it should "handle corrupted checkpoint files gracefully" in {
    // Create a corrupted checkpoint file
    val corruptedFile = Paths.get(testDir, testWorkerId, "checkpoint_corrupted.json")
    Files.createDirectories(corruptedFile.getParent)
    Files.write(corruptedFile, "{ this is not valid json }".getBytes)

    // Should return None instead of throwing exception
    val loadedCheckpoint = checkpointManager.loadLatestCheckpoint()
    loadedCheckpoint should be(None)
  }

  it should "provide accurate checkpoint statistics" in {
    // Save multiple checkpoints
    for (i <- 1 to 3) {
      val state = WorkerState.fromScala(
        0,  // workerIndex
        i * 100L,
        Seq.empty,
        Map(i -> i),
        Set(i),
        Seq(s"file$i.txt"),
        Map.empty  // phaseMetadata
      )
      Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_SHUFFLING, state, i * 0.33),
        5.seconds
      )
    }

    val (count, size) = checkpointManager.getCheckpointStats()
    count should be(3)
    size should be > 0L
  }

  it should "handle progress values correctly" in {
    val testState = WorkerState.fromScala(
      workerIndex = 0,
      processedRecords = 100L,
      partitionBoundaries = Seq.empty,
      shuffleMap = Map.empty,
      completedPartitions = Set.empty,
      currentFiles = Seq.empty,
      phaseMetadata = Map.empty
    )

    // Test various progress values
    val progressValues = Seq(0.0, 0.25, 0.5, 0.75, 1.0)

    for (progress <- progressValues) {
      val checkpointId = Await.result(
        checkpointManager.saveCheckpoint(WorkerPhase.PHASE_MERGING, testState, progress),
        5.seconds
      )

      checkpointManager.validateCheckpoint(checkpointId) should be(true)
    }

    // Progress outside valid range should still save but validation might handle it
    val invalidProgressId = Await.result(
      checkpointManager.saveCheckpoint(WorkerPhase.PHASE_MERGING, testState, 1.5),
      5.seconds
    )

    // The checkpoint saves but validation should handle invalid progress
    checkpointManager.validateCheckpoint(invalidProgressId) should be(false)
  }
}