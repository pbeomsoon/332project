package distsort.shuffle

import distsort.core._
import distsort.replication._
import org.slf4j.LoggerFactory

import java.io.File
import java.util.concurrent.{Semaphore, TimeUnit}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Manages the shuffle phase of distributed sorting
 * Responsible for:
 * - Partitioning sorted chunks according to boundaries
 * - Sending partitions to appropriate workers
 * - Receiving partitions from other workers
 * - Managing network transfers with backpressure
 *
 * Based on docs/3-grpc-sequences.md Phase 6
 */
class ShuffleManager(
    val workerId: String,
    val workerIndex: Int,
    val fileLayout: FileLayout,
    val partitioner: Partitioner,
    val maxConcurrentTransfers: Int = 5,
    val maxRetries: Int = 120,  // 120회 × 5초 = 10분 대기 (복구 Worker 대기)
    val retryIntervalMs: Long = 5000,  // 5초 고정 간격
    val progressTracker: Option[ShuffleProgressTracker] = None,
    val replicationEnabled: Boolean = false,
    val replicationFactor: Int = 2,
    val refreshClientsCallback: Option[() => Map[Int, WorkerClient]] = None  // Worker 정보 갱신 콜백
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(classOf[ShuffleManager])
  private val transferSemaphore = new Semaphore(maxConcurrentTransfers)
  @volatile private var workerClients: Map[Int, WorkerClient] = Map.empty  // ⭐ @volatile for thread visibility
  private var replicationManager: Option[ReplicationManager] = None

  /**
   * Set worker clients for communication
   */
  def setWorkerClients(clients: Map[Int, WorkerClient]): Unit = {
    workerClients = clients

    // Initialize replication manager if enabled
    if (replicationEnabled && clients.nonEmpty) {
      replicationManager = Some(new ReplicationManager(replicationFactor, clients))
      logger.info(s"Initialized ReplicationManager with factor $replicationFactor")
    }
  }

  /**
   * Main shuffle method - orchestrates the entire shuffle phase
   * ⭐ FIX: Uses streaming approach to avoid loading all data into memory
   *
   * @param sortedChunks Files containing sorted records
   * @param assignedPartitions Map of partition ID to worker index
   * @return Future indicating completion
   */
  def shuffleToWorkers(
      sortedChunks: Seq[File],
      assignedPartitions: Map[Int, Int]
  ): Future[Unit] = {
    logger.info(s"Worker $workerId starting shuffle of ${sortedChunks.size} chunks")

    val shuffleFuture = for {
      // Step 1: Partition sorted chunks to temporary files (streaming, low memory)
      partitionFiles <- Future {
        partitionSortedChunksToFiles(sortedChunks, assignedPartitions)
      }

      // Step 2: Send partition files to appropriate workers
      _ <- sendPartitionFilesToWorkers(partitionFiles, assignedPartitions)

    } yield {
      progressTracker.foreach(_.markComplete())
      logger.info(s"Worker $workerId completed shuffle phase")
    }

    shuffleFuture.recover {
      case ex: Exception =>
        logger.error(s"Shuffle failed for worker $workerId", ex)
        throw ex
    }
  }

  /**
   * Process local partitions without network transfer
   */
  def processLocalPartitions(
      sortedChunks: Seq[File],
      assignedPartitions: Map[Int, Int]
  ): Map[Int, Seq[File]] = {
    val localPartitions = assignedPartitions.filter(_._2 == workerIndex).keys.toSet
    val result = mutable.Map[Int, mutable.ArrayBuffer[File]]()

    // Process all chunks
    sortedChunks.foreach { chunk =>
      val reader = RecordReader.create(chunk)
      val records = Iterator.continually(reader.readRecord()).takeWhile(_.isDefined).map(_.get)

      val partitioned = partitionRecords(records.toSeq)

      partitioned.foreach { case (partitionId, partRecords) =>
        if (localPartitions.contains(partitionId)) {
          val file = fileLayout.getLocalPartitionFile(partitionId)
          val writer = RecordWriter.create(file, DataFormat.Binary)
          partRecords.foreach(writer.writeRecord)
          writer.close()

          result.getOrElseUpdate(partitionId, mutable.ArrayBuffer.empty) += file
        }
      }
      reader.close()
    }

    // Ensure all local partitions have at least an empty file
    localPartitions.foreach { partitionId =>
      if (!result.contains(partitionId)) {
        val file = fileLayout.getLocalPartitionFile(partitionId)
        val writer = RecordWriter.create(file, DataFormat.Binary)
        writer.close() // Create empty file
        result.getOrElseUpdate(partitionId, mutable.ArrayBuffer.empty) += file
      }
    }

    result.map { case (k, v) => k -> v.toSeq }.toMap
  }

  /**
   * Partition records using the partitioner
   */
  def partitionRecords(records: Seq[Record]): Map[Int, Seq[Record]] = {
    partitioner.partitionRecords(records)
  }

  /**
   * Receive partitions from other workers
   */
  def receivePartitions(partitionData: Map[Int, Seq[Record]]): Map[Int, File] = {
    partitionData.map { case (partitionId, records) =>
      val file = fileLayout.getReceivedPartitionFile(partitionId, Some(workerId))
      val writer = RecordWriter.create(file, DataFormat.Binary)
      records.foreach(writer.writeRecord)
      writer.close()

      logger.debug(s"Received partition $partitionId with ${records.size} records")
      progressTracker.foreach(_.recordBytesSent(records.size * 100))

      partitionId -> file
    }
  }

  // Private helper methods

  /**
   * ⭐ FIX: Streaming partition - writes to files instead of holding in memory
   * Processes each chunk one at a time and writes partitioned data to temp files
   */
  private def partitionSortedChunksToFiles(
      chunks: Seq[File],
      assignedPartitions: Map[Int, Int]
  ): Map[Int, File] = {
    import java.io.BufferedOutputStream

    // Create temp files and writers for each partition
    val partitionWriters = mutable.Map[Int, (File, RecordWriter)]()

    def getWriter(partitionId: Int): RecordWriter = {
      partitionWriters.getOrElseUpdate(partitionId, {
        val file = new File(fileLayout.getTempBasePath.toFile, s"shuffle-partition-$partitionId.tmp")
        val writer = RecordWriter.create(file, DataFormat.Binary)
        (file, writer)
      })._2
    }

    try {
      // Stream through each chunk and partition records
      chunks.foreach { chunk =>
        val reader = RecordReader.create(chunk)
        try {
          var record = reader.readRecord()
          while (record.isDefined) {
            val r = record.get
            val partitionId = partitioner.getPartition(r.key)
            getWriter(partitionId).writeRecord(r)
            record = reader.readRecord()
          }
        } finally {
          reader.close()
        }
      }

      // Close all writers
      partitionWriters.values.foreach { case (_, writer) =>
        writer.close()
      }

      // Return partition ID -> file mapping
      partitionWriters.map { case (pid, (file, _)) => pid -> file }.toMap

    } catch {
      case ex: Exception =>
        // Clean up on error
        partitionWriters.values.foreach { case (_, writer) =>
          try { writer.close() } catch { case _: Exception => }
        }
        throw ex
    }
  }

  /**
   * ⭐ FIX: Send partition files instead of in-memory data
   * Reads from files and streams to target workers
   */
  private def sendPartitionFilesToWorkers(
      partitionFiles: Map[Int, File],
      assignedPartitions: Map[Int, Int]
  ): Future[Unit] = {
    val allPartitionIds = assignedPartitions.keys.toSet

    val sendFutures = allPartitionIds.flatMap { partitionId =>
      val targetWorker = assignedPartitions.getOrElse(partitionId, workerIndex)
      val partitionFile = partitionFiles.get(partitionId)

      if (targetWorker == workerIndex) {
        // Local partition - move/copy file to local partition location
        partitionFile.foreach { srcFile =>
          val destFile = fileLayout.getLocalPartitionFile(partitionId)
          if (srcFile.exists()) {
            java.nio.file.Files.copy(
              srcFile.toPath,
              destFile.toPath,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            srcFile.delete() // Clean up temp file
            val size = destFile.length()
            logger.debug(s"Kept partition $partitionId locally ($size bytes)")
            progressTracker.foreach(_.recordBytesSent(size))
          }
        }
        None
      } else {
        // Remote partition - read from file and send
        partitionFile match {
          case Some(file) if file.exists() =>
            // Read records from file for sending
            val reader = RecordReader.create(file)
            val records = Iterator.continually(reader.readRecord())
              .takeWhile(_.isDefined)
              .map(_.get)
              .toSeq
            reader.close()
            file.delete() // Clean up temp file

            Some(sendPartitionWithRetry(partitionId, records, targetWorker))

          case _ =>
            // Empty partition
            Some(sendPartitionWithRetry(partitionId, Seq.empty, targetWorker))
        }
      }
    }

    Future.sequence(sendFutures).map(_ => ())
  }

  // Legacy methods kept for compatibility

  private def partitionSortedChunks(chunks: Seq[File]): Map[Int, Seq[Record]] = {
    val allPartitioned = mutable.Map[Int, mutable.ArrayBuffer[Record]]()

    chunks.foreach { chunk =>
      val reader = RecordReader.create(chunk)
      val records = Iterator.continually(reader.readRecord()).takeWhile(_.isDefined).map(_.get)

      val partitioned = partitionRecords(records.toSeq)
      partitioned.foreach { case (partitionId, partRecords) =>
        allPartitioned.getOrElseUpdate(partitionId, mutable.ArrayBuffer.empty) ++= partRecords
      }

      reader.close()
    }

    allPartitioned.map { case (k, v) => k -> v.toSeq }.toMap
  }

  private def sendPartitionsToWorkers(
      partitionedData: Map[Int, Seq[Record]],
      assignedPartitions: Map[Int, Int]
  ): Future[Unit] = {
    // Create partitions for all assigned partition IDs, even if empty
    val allPartitionIds = assignedPartitions.keys.toSet

    val sendFutures = allPartitionIds.flatMap { partitionId =>
      val records = partitionedData.getOrElse(partitionId, Seq.empty)
      val targetWorker = assignedPartitions.getOrElse(partitionId, workerIndex)

      if (targetWorker == workerIndex) {
        // Local partition - write directly
        val file = fileLayout.getLocalPartitionFile(partitionId)
        val writer = RecordWriter.create(file, DataFormat.Binary)
        records.foreach(writer.writeRecord)
        writer.close()
        logger.debug(s"Kept partition $partitionId locally with ${records.size} records")
        progressTracker.foreach { tracker =>
          tracker.recordBytesSent(records.size * 100L)
        }
        None
      } else {
        // Remote partition - send to target worker
        Some(sendPartitionWithRetry(partitionId, records, targetWorker))
      }
    }

    Future.sequence(sendFutures).map(_ => ())
  }

  /**
   * Refresh worker clients from callback (for fault tolerance)
   * Called before retry to get updated worker endpoints when a worker recovers
   *
   * ⭐ IMPORTANT: This is called when sendPartition fails, to check if a recovery worker joined
   * ⭐ FIX: Close old clients before replacing to prevent gRPC channel leaks
   */
  private def refreshWorkerClients(): Unit = {
    refreshClientsCallback.foreach { callback =>
      try {
        val oldClientKeys = workerClients.keySet
        val oldClients = workerClients
        val newClients = callback()

        if (newClients.nonEmpty) {
          // ⭐ FIX: Close old clients to prevent channel leaks
          oldClients.values.foreach { client =>
            try {
              client.close()
            } catch {
              case ex: Exception =>
                logger.debug(s"Error closing old client: ${ex.getMessage}")
            }
          }

          // Update to new clients
          workerClients = newClients

          // Log if client set changed (for debugging)
          if (oldClientKeys != newClients.keySet) {
            System.err.println(s"[Worker-$workerIndex] ⚠️ Worker set changed: ${oldClientKeys.mkString(",")} → ${newClients.keySet.mkString(",")}")
          }

          logger.debug(s"Refreshed worker clients: ${newClients.keys.mkString(", ")}")
        }
      } catch {
        case ex: Exception =>
          logger.warn(s"Failed to refresh worker clients: ${ex.getMessage}")
          System.err.println(s"[Worker-$workerIndex] ⚠️ Failed to refresh worker info: ${ex.getMessage}")
      }
    }
  }

  private def sendPartitionWithRetry(
      partitionId: Int,
      records: Seq[Record],
      targetWorker: Int,
      attempt: Int = 1
  ): Future[Unit] = {
    // ⭐ FIX: Check for client BEFORE creating Future
    // If client is missing (worker crashed), refresh and retry without blocking semaphore
    val clientOpt = workerClients.get(targetWorker)

    if (clientOpt.isEmpty) {
      if (attempt < maxRetries) {
        logger.warn(s"No client for worker $targetWorker (attempt $attempt/$maxRetries), " +
          s"refreshing and retrying in ${retryIntervalMs}ms - Worker may have crashed, waiting for recovery...")
        Thread.sleep(retryIntervalMs)
        refreshWorkerClients()
        return sendPartitionWithRetry(partitionId, records, targetWorker, attempt + 1)
      } else {
        return Future.failed(new IllegalStateException(s"No client for worker $targetWorker after $maxRetries attempts"))
      }
    }

    val client = clientOpt.get
    val promise = Promise[Unit]()

    // Acquire semaphore before creating Future (for proper backpressure)
    transferSemaphore.acquire()

    Future {
      try {
        logger.debug(s"Sending partition $partitionId to worker $targetWorker (attempt $attempt)")

        client.sendPartition(partitionId, records.iterator).onComplete {
          case Success(true) =>
            logger.info(s"Successfully sent partition $partitionId to worker $targetWorker")
            progressTracker.foreach { tracker =>
              tracker.recordPartitionSent()
              tracker.recordBytesSent(records.size * 100)
            }

            // Replicate if enabled
            replicationManager match {
              case Some(manager) =>
                val replicas = manager.selectReplicas(partitionId, targetWorker)
                if (replicas.nonEmpty) {
                  logger.info(s"Replicating partition $partitionId to ${replicas.size} backup workers")
                  val partitionData = PartitionData(
                    id = partitionId,
                    records = records,
                    checksum = calculateChecksum(records),
                    metadata = Map("primary" -> targetWorker.toString)
                  )
                  manager.replicatePartition(partitionData, replicas, targetWorker)
                    .onComplete {
                      case Success(_) =>
                        logger.debug(s"Partition $partitionId replicated successfully")
                      case Failure(ex) =>
                        logger.warn(s"Failed to replicate partition $partitionId: ${ex.getMessage}")
                    }
                }

              case None => // No replication
            }

            promise.success(())

          case Success(false) if attempt < maxRetries =>
            logger.warn(s"Partition $partitionId rejected by worker $targetWorker, " +
              s"waiting ${retryIntervalMs}ms before retry (attempt $attempt/$maxRetries)")
            Thread.sleep(retryIntervalMs)
            transferSemaphore.release()
            // Refresh worker clients before retry (복구된 Worker 정보 갱신)
            refreshWorkerClients()
            sendPartitionWithRetry(partitionId, records, targetWorker, attempt + 1)
              .onComplete(promise.complete)

          case Failure(ex) if attempt < maxRetries =>
            logger.warn(s"Failed to send partition $partitionId to worker $targetWorker: ${ex.getMessage}, " +
              s"waiting ${retryIntervalMs}ms before retry (attempt $attempt/$maxRetries) - Worker may have crashed, waiting for recovery...")
            Thread.sleep(retryIntervalMs)
            transferSemaphore.release()
            // Refresh worker clients before retry (복구된 Worker 정보 갱신)
            refreshWorkerClients()
            sendPartitionWithRetry(partitionId, records, targetWorker, attempt + 1)
              .onComplete(promise.complete)

          case Success(false) =>
            val ex = new RuntimeException(s"Worker rejected partition $partitionId")
            logger.error(s"Failed to send partition $partitionId after $maxRetries attempts (${maxRetries * retryIntervalMs / 1000}s)", ex)
            promise.failure(ex)

          case Failure(ex) =>
            logger.error(s"Failed to send partition $partitionId after $maxRetries attempts (${maxRetries * retryIntervalMs / 1000}s)", ex)
            promise.failure(ex)
        }
      } finally {
        transferSemaphore.release()
      }
    }

    promise.future
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
   * Get replication status
   */
  def getReplicationStatus(partitionId: Int): Option[ReplicationMetadata] = {
    replicationManager.flatMap(_.getReplicationStatus(partitionId))
  }

  /**
   * Recover partition from backup
   */
  def recoverPartition(partitionId: Int, failedWorker: Int): Future[Option[PartitionData]] = {
    replicationManager match {
      case Some(manager) =>
        manager.recoverFromBackup(partitionId, failedWorker).map(Some(_))
      case None =>
        Future.successful(None)
    }
  }

  /**
   * Cleanup resources - close all worker client connections
   */
  def cleanup(): Unit = {
    logger.info(s"Cleaning up ShuffleManager resources (${workerClients.size} worker clients)...")

    // Close all worker clients in parallel with timeout
    val closeStartTime = System.currentTimeMillis()

    workerClients.values.foreach { client =>
      try {
        // Check if we've been cleaning up for too long
        val elapsed = System.currentTimeMillis() - closeStartTime
        if (elapsed > 5000) { // 5 second total timeout
          logger.warn(s"Cleanup taking too long (${elapsed}ms), skipping remaining clients")
          return
        }

        client.close()
      } catch {
        case ex: Exception =>
          logger.debug(s"Error closing worker client: ${ex.getMessage}")
      }
    }

    workerClients = Map.empty
    logger.info("ShuffleManager cleanup complete")
  }

}

/**
 * Tracks shuffle progress
 */
class ShuffleProgressTracker {
  private val bytesSent = new java.util.concurrent.atomic.AtomicLong(0)
  private val partitionsSent = new java.util.concurrent.atomic.AtomicInteger(0)
  @volatile private var complete: Boolean = false

  def recordBytesSent(bytes: Long): Unit = {
    bytesSent.addAndGet(bytes)
  }

  def recordPartitionSent(): Unit = {
    partitionsSent.incrementAndGet()
  }

  def markComplete(): Unit = {
    complete = true
  }

  def getTotalBytesSent: Long = bytesSent.get()
  def getPartitionsSent: Int = partitionsSent.get()
  def isComplete: Boolean = complete
}