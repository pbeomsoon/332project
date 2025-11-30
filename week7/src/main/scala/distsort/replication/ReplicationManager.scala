package distsort.replication

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Random, Success, Failure}
import scala.collection.concurrent.TrieMap
import org.slf4j.LoggerFactory
import distsort.core.Record
import distsort.shuffle.WorkerClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Metadata for tracking replicated partitions
 */
case class ReplicationMetadata(
  partitionId: Int,
  primaryWorker: Int,
  backupWorkers: Seq[Int],
  version: Long,
  checksum: String,
  size: Long
)

/**
 * Data structure for partition data
 */
case class PartitionData(
  id: Int,
  records: Seq[Record],
  checksum: String,
  metadata: Map[String, String] = Map.empty
)

/**
 * Manages data replication for fault tolerance
 */
class ReplicationManager(
  replicationFactor: Int = 2,
  workerClients: Map[Int, WorkerClient]
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val replicationMetadata = TrieMap.empty[Int, ReplicationMetadata]
  private val replicationVersion = new AtomicInteger(0)

  /**
   * Select replica workers for a partition
   * @param partitionId The partition to replicate
   * @param excludeWorker Primary worker to exclude from replicas
   * @return List of selected replica workers
   */
  def selectReplicas(
    partitionId: Int,
    excludeWorker: Int
  ): Seq[Int] = {
    val availableWorkers = workerClients.keys.filterNot(_ == excludeWorker).toSeq

    if (availableWorkers.size < replicationFactor - 1) {
      logger.warn(s"Not enough workers for replication factor $replicationFactor. " +
        s"Available: ${availableWorkers.size}, needed: ${replicationFactor - 1}")
      availableWorkers
    } else {
      // Use consistent hashing for deterministic replica selection
      val random = new Random(partitionId)
      random.shuffle(availableWorkers).take(replicationFactor - 1)
    }
  }

  /**
   * Replicate partition data to backup workers
   * @param partitionData The partition data to replicate
   * @param replicas List of backup workers
   * @return Future indicating success
   */
  def replicatePartition(
    partitionData: PartitionData,
    replicas: Seq[Int],
    primaryWorker: Int
  ): Future[Unit] = {
    if (replicas.isEmpty) {
      logger.info(s"No replicas configured for partition ${partitionData.id}")
      return Future.successful(())
    }

    val version = replicationVersion.incrementAndGet()
    val metadata = ReplicationMetadata(
      partitionId = partitionData.id,
      primaryWorker = primaryWorker,
      backupWorkers = replicas,
      version = version,
      checksum = partitionData.checksum,
      size = partitionData.records.size
    )

    // Store metadata
    replicationMetadata.put(partitionData.id, metadata)

    // Send to replicas in parallel
    val replicationFutures = replicas.map { workerId =>
      sendToReplica(partitionData, workerId).map { success =>
        if (success) {
          logger.info(s"Successfully replicated partition ${partitionData.id} to worker $workerId")
          workerId
        } else {
          logger.warn(s"Failed to replicate partition ${partitionData.id} to worker $workerId")
          -1
        }
      }.recover {
        case ex =>
          logger.error(s"Error replicating partition ${partitionData.id} to worker $workerId: ${ex.getMessage}")
          -1
      }
    }

    Future.sequence(replicationFutures).map { results =>
      val successfulReplicas = results.filter(_ != -1)
      if (successfulReplicas.nonEmpty) {
        logger.info(s"Partition ${partitionData.id} replicated to ${successfulReplicas.size} workers: " +
          successfulReplicas.mkString(","))
      } else {
        logger.error(s"Failed to replicate partition ${partitionData.id} to any backup workers")
      }
    }
  }

  /**
   * Recover partition data from backup
   * @param partitionId The partition to recover
   * @param failedWorker The failed primary worker
   * @return Future with recovered partition data
   */
  def recoverFromBackup(
    partitionId: Int,
    failedWorker: Int
  ): Future[PartitionData] = {
    replicationMetadata.get(partitionId) match {
      case Some(metadata) if metadata.primaryWorker == failedWorker =>
        tryRecoverFromReplicas(partitionId, metadata.backupWorkers)

      case Some(metadata) =>
        // Primary is still alive, get from primary
        logger.info(s"Primary worker ${metadata.primaryWorker} is alive, not recovering from backup")
        Future.failed(new IllegalStateException(s"Primary worker ${metadata.primaryWorker} is still active"))

      case None =>
        logger.error(s"No replication metadata found for partition $partitionId")
        Future.failed(new NoSuchElementException(s"No replication metadata for partition $partitionId"))
    }
  }

  /**
   * Try to recover partition from available replicas
   */
  private def tryRecoverFromReplicas(
    partitionId: Int,
    replicas: Seq[Int],
    attemptIndex: Int = 0
  ): Future[PartitionData] = {
    if (attemptIndex >= replicas.size) {
      Future.failed(new RuntimeException(s"All replicas failed for partition $partitionId"))
    } else {
      val replicaWorker = replicas(attemptIndex)
      logger.info(s"Attempting to recover partition $partitionId from replica worker $replicaWorker")

      fetchFromReplica(partitionId, replicaWorker).recoverWith {
        case ex =>
          logger.warn(s"Failed to recover from replica $replicaWorker: ${ex.getMessage}")
          tryRecoverFromReplicas(partitionId, replicas, attemptIndex + 1)
      }
    }
  }

  /**
   * Send partition data to a replica worker
   */
  private def sendToReplica(
    partitionData: PartitionData,
    workerId: Int
  ): Future[Boolean] = {
    workerClients.get(workerId) match {
      case Some(client) =>
        // Mark as replica data
        val replicaData = partitionData.copy(
          metadata = partitionData.metadata + ("isReplica" -> "true")
        )
        client.storeReplica(partitionData.id, replicaData.records)

      case None =>
        logger.error(s"No client available for worker $workerId")
        Future.successful(false)
    }
  }

  /**
   * Fetch partition data from a replica worker
   */
  private def fetchFromReplica(
    partitionId: Int,
    workerId: Int
  ): Future[PartitionData] = {
    workerClients.get(workerId) match {
      case Some(client) =>
        client.getReplica(partitionId).map { records =>
          val checksum = calculateChecksum(records)

          // Verify checksum if metadata exists
          replicationMetadata.get(partitionId).foreach { metadata =>
            if (metadata.checksum != checksum) {
              logger.warn(s"Checksum mismatch for partition $partitionId from worker $workerId")
            }
          }

          PartitionData(
            id = partitionId,
            records = records,
            checksum = checksum,
            metadata = Map("recoveredFrom" -> workerId.toString)
          )
        }

      case None =>
        Future.failed(new IllegalStateException(s"No client for worker $workerId"))
    }
  }

  /**
   * Calculate checksum for records
   */
  private def calculateChecksum(records: Seq[Record]): String = {
    import java.security.MessageDigest
    val md = MessageDigest.getInstance("MD5")
    records.foreach { record =>
      md.update(record.key)
      md.update(record.value)
    }
    md.digest().map("%02x".format(_)).mkString
  }

  /**
   * Get replication status for a partition
   */
  def getReplicationStatus(partitionId: Int): Option[ReplicationMetadata] = {
    replicationMetadata.get(partitionId)
  }

  /**
   * Get all replication metadata
   */
  def getAllReplicationStatus: Map[Int, ReplicationMetadata] = {
    replicationMetadata.toMap
  }

  /**
   * Clear replication metadata for a partition
   */
  def clearReplication(partitionId: Int): Unit = {
    replicationMetadata.remove(partitionId)
    logger.info(s"Cleared replication metadata for partition $partitionId")
  }

  /**
   * Promote a backup to primary
   */
  def promoteBackupToPrimary(
    partitionId: Int,
    newPrimaryWorker: Int
  ): Boolean = {
    replicationMetadata.get(partitionId) match {
      case Some(metadata) if metadata.backupWorkers.contains(newPrimaryWorker) =>
        val updatedMetadata = metadata.copy(
          primaryWorker = newPrimaryWorker,
          backupWorkers = metadata.backupWorkers.filterNot(_ == newPrimaryWorker) :+ metadata.primaryWorker,
          version = metadata.version + 1
        )
        replicationMetadata.put(partitionId, updatedMetadata)
        logger.info(s"Promoted worker $newPrimaryWorker to primary for partition $partitionId")
        true

      case _ =>
        logger.error(s"Cannot promote worker $newPrimaryWorker for partition $partitionId")
        false
    }
  }
}


/**
 * Companion object
 */
object ReplicationManager {

  /**
   * Create a ReplicationManager with default settings
   */
  def apply(workerClients: Map[Int, WorkerClient])(implicit ec: ExecutionContext): ReplicationManager = {
    new ReplicationManager(2, workerClients)
  }

  /**
   * Create a ReplicationManager with custom replication factor
   */
  def apply(replicationFactor: Int, workerClients: Map[Int, WorkerClient])
           (implicit ec: ExecutionContext): ReplicationManager = {
    new ReplicationManager(replicationFactor, workerClients)
  }
}