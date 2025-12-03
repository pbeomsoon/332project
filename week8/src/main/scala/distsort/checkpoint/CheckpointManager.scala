package distsort.checkpoint

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}
import com.google.gson.{Gson, GsonBuilder}
import org.slf4j.LoggerFactory
import distsort.proto.distsort.WorkerPhase

/**
 * Checkpoint data structure containing worker state
 */
case class Checkpoint(
  id: String,
  workerId: String,
  phase: String, // Store as string for serialization
  timestamp: Instant,
  progress: Double,
  state: WorkerState
)

/**
 * Worker state information for checkpointing
 * Uses Java collections for proper JSON serialization
 */
case class WorkerState(
  workerIndex: Int,  // ⭐ Added for worker re-registration (Phase 2)
  processedRecords: Long,
  partitionBoundaries: java.util.List[Array[Byte]],
  shuffleMap: java.util.Map[Integer, Integer],
  completedPartitions: java.util.Set[Integer],
  currentFiles: java.util.List[String],
  phaseMetadata: java.util.Map[String, String]
)

object WorkerState {
  /**
   * Create WorkerState from Scala collections
   */
  def fromScala(
    workerIndex: Int,  // ⭐ Added for Phase 2
    processedRecords: Long,
    partitionBoundaries: Seq[Array[Byte]],
    shuffleMap: Map[Int, Int],
    completedPartitions: Set[Int],
    currentFiles: Seq[String],
    phaseMetadata: Map[String, String] = Map.empty
  ): WorkerState = {
    import scala.jdk.CollectionConverters._
    WorkerState(
      workerIndex,  // ⭐ Added
      processedRecords,
      partitionBoundaries.asJava,
      shuffleMap.map { case (k, v) => (Int.box(k), Int.box(v)) }.asJava,
      completedPartitions.map(Int.box).asJava,
      currentFiles.asJava,
      phaseMetadata.asJava
    )
  }

  /**
   * Convert to Scala collections
   */
  def toScala(state: WorkerState): (Int, Long, Seq[Array[Byte]], Map[Int, Int], Set[Int], Seq[String], Map[String, String]) = {
    import scala.jdk.CollectionConverters._
    (
      state.workerIndex,  // ⭐ Added
      state.processedRecords,
      state.partitionBoundaries.asScala.toSeq,
      state.shuffleMap.asScala.map { case (k, v) => (k.toInt, v.toInt) }.toMap,
      state.completedPartitions.asScala.map(_.toInt).toSet,
      state.currentFiles.asScala.toSeq,
      state.phaseMetadata.asScala.toMap
    )
  }
}

/**
 * Manager for handling checkpoint operations
 * Provides save, load, and cleanup functionality for worker checkpoints
 */
class CheckpointManager(workerId: String, checkpointDir: String)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val gson = new GsonBuilder()
    .setPrettyPrinting()
    .create()

  private val checkpointPath = Paths.get(checkpointDir, workerId)

  // Ensure checkpoint directory exists
  Try(Files.createDirectories(checkpointPath))

  /**
   * Save a checkpoint to disk
   * @param phase Current worker phase
   * @param state Current worker state
   * @param progress Completion progress (0.0 to 1.0)
   * @return Future containing the checkpoint ID
   */
  def saveCheckpoint(
    phase: WorkerPhase,
    state: WorkerState,
    progress: Double
  ): Future[String] = Future {
    val checkpointId = s"checkpoint_${System.currentTimeMillis()}_${phase.toString}"
    val checkpoint = Checkpoint(
      id = checkpointId,
      workerId = workerId,
      phase = phase.toString,  // Convert to string for serialization
      timestamp = Instant.now(),
      progress = progress,
      state = state
    )

    val file = checkpointPath.resolve(s"$checkpointId.json").toFile
    val writer = new PrintWriter(file)

    try {
      writer.write(gson.toJson(checkpoint))
      logger.info(s"Saved checkpoint $checkpointId for worker $workerId at phase $phase (${(progress * 100).toInt}% complete)")
      checkpointId
    } finally {
      writer.close()
    }
  }

  /**
   * Load the most recent valid checkpoint
   * @return Optional checkpoint if found and valid
   */
  def loadLatestCheckpoint(): Option[Checkpoint] = {
    Try {
      val checkpointFiles = checkpointPath.toFile.listFiles()
        .filter(_.getName.endsWith(".json"))
        .sortBy(_.lastModified()).reverse

      checkpointFiles.headOption.flatMap { file =>
        val reader = new BufferedReader(new FileReader(file))
        try {
          val json = Stream.continually(reader.readLine())
            .takeWhile(_ != null)
            .mkString("\n")

          val checkpoint = gson.fromJson(json, classOf[Checkpoint])

          if (validateCheckpoint(checkpoint.id)) {
            logger.info(s"Loaded checkpoint ${checkpoint.id} for worker $workerId from phase ${checkpoint.phase}")
            Some(checkpoint)
          } else {
            logger.warn(s"Checkpoint ${checkpoint.id} failed validation")
            None
          }
        } finally {
          reader.close()
        }
      }
    } match {
      case Success(checkpoint) => checkpoint
      case Failure(e) =>
        logger.error(s"Failed to load checkpoint: ${e.getMessage}")
        None
    }
  }

  /**
   * Delete old checkpoints, keeping only the most recent ones
   * @param keepLast Number of checkpoints to keep
   */
  def cleanOldCheckpoints(keepLast: Int = 3): Unit = {
    Try {
      val checkpointFiles = checkpointPath.toFile.listFiles()
        .filter(_.getName.endsWith(".json"))
        .sortBy(_.lastModified()).reverse

      checkpointFiles.drop(keepLast).foreach { file =>
        if (file.delete()) {
          logger.debug(s"Deleted old checkpoint: ${file.getName}")
        }
      }
    } match {
      case Success(_) =>
        logger.info(s"Cleaned old checkpoints, kept last $keepLast")
      case Failure(e) =>
        logger.warn(s"Failed to clean old checkpoints: ${e.getMessage}")
    }
  }

  /**
   * Validate a checkpoint by checking its integrity
   * @param checkpointId The checkpoint ID to validate
   * @return true if checkpoint is valid, false otherwise
   */
  def validateCheckpoint(checkpointId: String): Boolean = {
    Try {
      val file = checkpointPath.resolve(s"$checkpointId.json").toFile

      if (!file.exists() || !file.canRead()) {
        false
      } else {
        val reader = new BufferedReader(new FileReader(file))
        try {
          val json = Stream.continually(reader.readLine())
            .takeWhile(_ != null)
            .mkString("\n")

          val checkpoint = gson.fromJson(json, classOf[Checkpoint])

          // Validate checkpoint integrity
          checkpoint.id == checkpointId &&
            checkpoint.workerId == workerId &&
            checkpoint.progress >= 0.0 && checkpoint.progress <= 1.0
        } finally {
          reader.close()
        }
      }
    } match {
      case Success(valid) => valid
      case Failure(e) =>
        logger.error(s"Checkpoint validation failed: ${e.getMessage}")
        false
    }
  }

  /**
   * Delete old checkpoints (older than maxAgeSeconds)
   * Useful for cleaning up stale checkpoints from previous test runs
   */
  def deleteOldCheckpoints(maxAgeSeconds: Int = 60): Unit = {
    Try {
      val now = Instant.now()
      val checkpointFiles = checkpointPath.toFile.listFiles()
        .filter(_.getName.endsWith(".json"))

      val gson = new GsonBuilder().create()

      checkpointFiles.foreach { file =>
        Try {
          val reader = new FileReader(file)
          val checkpoint = gson.fromJson(reader, classOf[Checkpoint])
          reader.close()

          val ageSeconds = now.getEpochSecond - checkpoint.timestamp.getEpochSecond
          if (ageSeconds > maxAgeSeconds) {
            if (file.delete()) {
              logger.info(s"Deleted old checkpoint: ${file.getName} (age: ${ageSeconds}s)")
            }
          }
        } match {
          case Failure(e) =>
            logger.warn(s"Failed to check age of ${file.getName}, deleting anyway: ${e.getMessage}")
            file.delete()
          case _ => // Success, already handled
        }
      }
    } match {
      case Failure(e) =>
        logger.error(s"Failed to delete old checkpoints: ${e.getMessage}")
      case _ => // Success
    }
  }

  /**
   * Delete all checkpoints for this worker
   */
  def deleteAllCheckpoints(): Unit = {
    Try {
      val checkpointFiles = checkpointPath.toFile.listFiles()
        .filter(_.getName.endsWith(".json"))

      checkpointFiles.foreach { file =>
        if (file.delete()) {
          logger.debug(s"Deleted checkpoint: ${file.getName}")
        }
      }

      logger.info(s"Deleted all checkpoints for worker $workerId")
    } match {
      case Failure(e) =>
        logger.error(s"Failed to delete checkpoints: ${e.getMessage}")
      case _ =>
    }
  }

  /**
   * Get checkpoint statistics
   * @return Number of checkpoints and total size
   */
  def getCheckpointStats(): (Int, Long) = {
    Try {
      val checkpointFiles = checkpointPath.toFile.listFiles()
        .filter(_.getName.endsWith(".json"))

      val count = checkpointFiles.length
      val totalSize = checkpointFiles.map(_.length()).sum

      (count, totalSize)
    } match {
      case Success(stats) => stats
      case Failure(_) => (0, 0L)
    }
  }
}

/**
 * Companion object for CheckpointManager
 */
object CheckpointManager {

  /**
   * Create a CheckpointManager with default settings
   */
  def apply(workerId: String)(implicit ec: ExecutionContext): CheckpointManager = {
    new CheckpointManager(workerId, "/tmp/distsort/checkpoints")
  }

  /**
   * Create a CheckpointManager with custom checkpoint directory
   */
  def apply(workerId: String, checkpointDir: String)(implicit ec: ExecutionContext): CheckpointManager = {
    new CheckpointManager(workerId, checkpointDir)
  }
}