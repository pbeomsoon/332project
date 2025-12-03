package distsort.worker

import distsort.proto.distsort._
import distsort.core.Record
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{ConcurrentHashMap, Semaphore}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable

/**
 * gRPC service implementation for Worker nodes.
 * Handles status reporting, phase transitions, and shuffle data reception.
 */
class WorkerService(
  workerId: String,
  outputDir: String,
  maxShuffleBufferMB: Int = 256
)(implicit ec: ExecutionContext) extends WorkerServiceGrpc.WorkerService with LazyLogging {

  // State management
  private val currentPhase = new AtomicReference[WorkerPhase](WorkerPhase.PHASE_INITIALIZING)
  private val phaseStartTime = new AtomicReference[Long](System.currentTimeMillis())

  // Statistics
  private val recordsProcessed = new AtomicInteger(0)
  private val partitionsReceived = new AtomicInteger(0)
  private val partitionsSent = new AtomicInteger(0)

  // Shuffle data management
  private val shuffleBuffers = new ConcurrentHashMap[Int, ShuffleBuffer]()
  private val shuffleBufferSemaphore = new Semaphore(maxShuffleBufferMB) // MB-based flow control
  private val receivedDir = Paths.get(outputDir, ".received", workerId)

  // Health monitoring
  @volatile private var lastHealthCheckTime = System.currentTimeMillis()
  private val healthCheckTimeoutMs = 30000 // 30 seconds

  // Initialize directories
  Files.createDirectories(receivedDir)

  /**
   * Shuffle buffer for receiving partition data
   */
  class ShuffleBuffer(val partitionId: Int) {
    private val chunks = mutable.ListBuffer[Array[Byte]]()
    private var totalBytes = 0
    private var sequenceNumber = 0
    @volatile private var isComplete = false

    def addChunk(data: Array[Byte], seqNum: Int, isLast: Boolean): Boolean = {
      synchronized {
        if (seqNum != sequenceNumber) {
          logger.error(s"Sequence number mismatch for partition $partitionId: " +
            s"expected $sequenceNumber, got $seqNum")
          return false
        }

        chunks += data
        totalBytes += data.length
        sequenceNumber += 1

        if (isLast) {
          isComplete = true
          writeToFile()
        }

        true
      }
    }

    def writeToFile(): Unit = {
      val file = receivedDir.resolve(s"partition.$partitionId.received").toFile
      val outputStream = new BufferedOutputStream(new FileOutputStream(file))

      try {
        chunks.foreach(outputStream.write)
        outputStream.flush()
        logger.info(s"Written partition $partitionId to file: ${file.getAbsolutePath} " +
          s"(${totalBytes / 1024 / 1024} MB)")
      } finally {
        outputStream.close()
        // Release buffer memory
        chunks.clear()
        shuffleBufferSemaphore.release(totalBytes / 1024 / 1024)
      }
    }

    def getTotalBytes: Int = totalBytes
    def isCompleted: Boolean = isComplete
  }

  /**
   * Get current worker status
   */
  override def getWorkerStatus(request: WorkerStatusRequest): Future[WorkerStatusResponse] = {
    Future {
      updateHealthCheck()

      WorkerStatusResponse(
        workerId = workerId,
        currentPhase = currentPhase.get(),
        recordsProcessed = recordsProcessed.get(),
        partitionsReceived = partitionsReceived.get(),
        partitionsSent = partitionsSent.get(),
        isHealthy = isHealthy
      )
    }
  }

  /**
   * Start a new phase
   */
  override def startPhase(request: StartPhaseRequest): Future[StartPhaseResponse] = {
    Future {
      val requestedPhase = request.phase
      val currentPhaseValue = currentPhase.get()

      logger.info(s"Worker $workerId: Start phase request - $requestedPhase (current: $currentPhaseValue)")

      // Validate phase transition
      if (!isValidTransition(currentPhaseValue, requestedPhase)) {
        logger.error(s"Invalid phase transition: $currentPhaseValue -> $requestedPhase")
        StartPhaseResponse(
          started = false,
          message = s"Cannot transition from $currentPhaseValue to $requestedPhase"
        )
      } else {
        // Perform phase-specific initialization
        initializePhase(requestedPhase)

        // Update phase
        currentPhase.set(requestedPhase)
        phaseStartTime.set(System.currentTimeMillis())

        logger.info(s"Worker $workerId entered phase: $requestedPhase")

        StartPhaseResponse(
          started = true,
          message = s"Started phase $requestedPhase"
        )
      }
    }
  }

  /**
   * Receive shuffle data stream
   */
  override def receiveShuffleData(
    responseObserver: StreamObserver[ShuffleResponse]
  ): StreamObserver[ShuffleRequest] = {

    new StreamObserver[ShuffleRequest] {
      private val startTime = System.currentTimeMillis()
      private var totalBytesReceived = 0
      private var chunksReceived = 0

      override def onNext(request: ShuffleRequest): Unit = {
        try {
          val partitionId = request.partitionId
          val data = request.data.toByteArray
          val seqNum = request.sequenceNumber
          val isLast = request.isLastChunk

          logger.debug(s"Receiving shuffle data for partition $partitionId: " +
            s"seq=$seqNum, size=${data.length}, isLast=$isLast")

          // Apply backpressure if needed
          val requiredMB = data.length / 1024 / 1024
          if (requiredMB > 0) {
            shuffleBufferSemaphore.acquire(requiredMB)
          }

          // Get or create buffer for this partition
          val buffer = shuffleBuffers.computeIfAbsent(partitionId, _ => new ShuffleBuffer(partitionId))

          // Add chunk to buffer
          val success = buffer.addChunk(data, seqNum, isLast)

          if (success) {
            totalBytesReceived += data.length
            chunksReceived += 1

            if (isLast) {
              partitionsReceived.incrementAndGet()
              shuffleBuffers.remove(partitionId)
              logger.info(s"Completed receiving partition $partitionId")
            }

            // Send acknowledgment with buffer capacity
            responseObserver.onNext(ShuffleResponse(
              acknowledged = true,
              bufferCapacity = shuffleBufferSemaphore.availablePermits()
            ))
          } else {
            responseObserver.onNext(ShuffleResponse(
              acknowledged = false,
              bufferCapacity = 0
            ))
          }

        } catch {
          case ex: Exception =>
            logger.error(s"Error receiving shuffle data: ${ex.getMessage}", ex)
            responseObserver.onError(ex)
        }
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Shuffle stream error: ${t.getMessage}", t)
        // Clean up partial buffers
        shuffleBuffers.clear()
      }

      override def onCompleted(): Unit = {
        val duration = System.currentTimeMillis() - startTime
        val throughputMBps = if (duration > 0) {
          (totalBytesReceived / 1024.0 / 1024.0) / (duration / 1000.0)
        } else 0

        logger.info(s"Shuffle stream completed: received $chunksReceived chunks, " +
          s"${totalBytesReceived / 1024 / 1024} MB in ${duration}ms " +
          s"(${throughputMBps.formatted("%.2f")} MB/s)")

        responseObserver.onCompleted()
      }
    }
  }

  /**
   * New streaming shuffle data receiver using ShuffleDataChunk
   * ⭐ FIX: Each sender's data is stored in a SEPARATE file to preserve sort order
   * ⭐ FIX: Stream directly to file instead of buffering in memory (prevents OOM)
   */
  override def shuffleData(responseObserver: StreamObserver[ShuffleAck]): StreamObserver[ShuffleDataChunk] = {
    // ⭐ Generate unique ID per stream (per sender) to avoid mixing sorted data
    val streamId = java.util.UUID.randomUUID().toString.take(8)

    new StreamObserver[ShuffleDataChunk] {
      private var currentPartitionId: Int = -1
      private var currentOutputStream: java.io.OutputStream = _
      private var currentFile: File = _
      private var totalBytesReceived: Long = 0
      private var chunksReceived: Int = 0
      private val startTime = System.currentTimeMillis()

      override def onNext(chunk: ShuffleDataChunk): Unit = {
        try {
          chunksReceived += 1

          if (chunk.partitionId != currentPartitionId) {
            // New partition starting - close previous file if any
            closeCurrentFile()

            // Open new file for this partition
            currentPartitionId = chunk.partitionId
            currentFile = new File(receivedDir.toFile, s"partition-$currentPartitionId-$streamId.received")
            currentOutputStream = new BufferedOutputStream(new FileOutputStream(currentFile), 4 * 1024 * 1024)  // 4MB buffer
          }

          // ⭐ FIX: Write directly to file (no memory buffering)
          if (chunk.data.size() > 0) {
            currentOutputStream.write(chunk.data.toByteArray)
            totalBytesReceived += chunk.data.size()
          }

          if (chunk.isLast) {
            // Last chunk for this partition
            closeCurrentFile()
            partitionsReceived.incrementAndGet()

            // Send acknowledgment
            responseObserver.onNext(ShuffleAck(
              success = true,
              message = s"Successfully received partition $currentPartitionId",
              bytesReceived = totalBytesReceived
            ))

            logger.info(s"Completed receiving partition $currentPartitionId: " +
                       s"${totalBytesReceived} bytes in $chunksReceived chunks")
          }
        } catch {
          case ex: Exception =>
            logger.error(s"Error processing shuffle chunk: ${ex.getMessage}", ex)
            closeCurrentFile()
            responseObserver.onNext(ShuffleAck(
              success = false,
              message = ex.getMessage,
              bytesReceived = 0
            ))
        }
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Shuffle data stream error: ${t.getMessage}", t)
        closeCurrentFile()
        responseObserver.onError(t)
      }

      override def onCompleted(): Unit = {
        closeCurrentFile()
        val duration = System.currentTimeMillis() - startTime
        logger.info(s"Shuffle data stream completed: received $chunksReceived chunks, " +
                   s"${totalBytesReceived} bytes in ${duration}ms")
        responseObserver.onCompleted()
      }

      private def closeCurrentFile(): Unit = {
        if (currentOutputStream != null) {
          try {
            currentOutputStream.close()
          } catch {
            case ex: Exception =>
              logger.warn(s"Error closing file: ${ex.getMessage}")
          }
          currentOutputStream = null
        }
      }
    }
  }

  // Helper methods

  /**
   * Check if phase transition is valid
   */
  private def isValidTransition(from: WorkerPhase, to: WorkerPhase): Boolean = {
    (from, to) match {
      case (WorkerPhase.PHASE_INITIALIZING, WorkerPhase.PHASE_SAMPLING) => true
      case (WorkerPhase.PHASE_SAMPLING, WorkerPhase.PHASE_WAITING_FOR_PARTITIONS) => true
      case (WorkerPhase.PHASE_WAITING_FOR_PARTITIONS, WorkerPhase.PHASE_SORTING) => true
      case (WorkerPhase.PHASE_SORTING, WorkerPhase.PHASE_SHUFFLING) => true
      case (WorkerPhase.PHASE_SHUFFLING, WorkerPhase.PHASE_MERGING) => true
      case (WorkerPhase.PHASE_MERGING, WorkerPhase.PHASE_COMPLETED) => true
      case (_, WorkerPhase.PHASE_FAILED) => true // Can fail from any state
      case (WorkerPhase.PHASE_FAILED, WorkerPhase.PHASE_INITIALIZING) => true // Can restart
      case _ => false
    }
  }

  /**
   * Initialize phase-specific resources
   */
  private def initializePhase(phase: WorkerPhase): Unit = {
    phase match {
      case WorkerPhase.PHASE_SAMPLING =>
        logger.info(s"Initializing sampling phase for worker $workerId")
        recordsProcessed.set(0)

      case WorkerPhase.PHASE_SORTING =>
        logger.info(s"Initializing sorting phase for worker $workerId")

      case WorkerPhase.PHASE_SHUFFLING =>
        logger.info(s"Initializing shuffle phase for worker $workerId")
        partitionsReceived.set(0)
        partitionsSent.set(0)
        shuffleBuffers.clear()

      case WorkerPhase.PHASE_MERGING =>
        logger.info(s"Initializing merge phase for worker $workerId")

      case _ =>
        logger.info(s"No special initialization for phase $phase")
    }
  }

  /**
   * Update health check timestamp
   */
  private def updateHealthCheck(): Unit = {
    lastHealthCheckTime = System.currentTimeMillis()
  }

  /**
   * Check if worker is healthy
   */
  private def isHealthy: Boolean = {
    val timeSinceLastCheck = System.currentTimeMillis() - lastHealthCheckTime
    timeSinceLastCheck < healthCheckTimeoutMs
  }

  // Public methods for integration

  def getCurrentPhase: WorkerPhase = currentPhase.get()

  def setPhase(phase: WorkerPhase): Unit = {
    currentPhase.set(phase)
    phaseStartTime.set(System.currentTimeMillis())
  }

  def incrementRecordsProcessed(count: Int = 1): Unit = {
    recordsProcessed.addAndGet(count)
  }

  def incrementPartitionsSent(count: Int = 1): Unit = {
    partitionsSent.addAndGet(count)
  }

  def getRecordsProcessed: Int = recordsProcessed.get()

  def getPartitionsReceived: Int = partitionsReceived.get()

  def getPartitionsSent: Int = partitionsSent.get()

  def getReceivedFiles: Seq[File] = {
    if (Files.exists(receivedDir)) {
      Files.list(receivedDir)
        .iterator()
        .asScala
        .map(_.toFile)
        .filter(_.getName.endsWith(".received"))
        .toSeq
    } else {
      Seq.empty
    }
  }

  // ========== Recovery Worker Support (MERGING phase crash) ==========

  /**
   * Callback for handling shuffle data requests from recovery workers.
   * Set by Worker class to provide access to input files and partitioner.
   *
   * Parameters: (request, requesterWorkerIndex) => recordsSent
   */
  private var onShuffleDataRequest: Option[(RequestShuffleDataRequest) => Int] = None

  /**
   * Set the handler for shuffle data requests.
   * Called by Worker during initialization.
   */
  def setShuffleDataRequestHandler(handler: (RequestShuffleDataRequest) => Int): Unit = {
    onShuffleDataRequest = Some(handler)
    logger.info("Shuffle data request handler registered")
  }

  /**
   * Handle shuffle data request from recovery worker.
   * Recovery worker calls this when it needs partition data after MERGING crash.
   *
   * Flow:
   * 1. Recovery worker sends RequestShuffleDataRequest with partition IDs it needs
   * 2. This worker re-reads its input files
   * 3. This worker partitions records and sends requested partitions to recovery worker
   * 4. Returns success/failure
   */
  override def requestShuffleData(request: RequestShuffleDataRequest): Future[RequestShuffleDataResponse] = {
    Future {
      logger.info(s"Received shuffle data request from recovery worker ${request.requesterWorkerId} " +
        s"(index ${request.requesterWorkerIndex}) for partitions: ${request.partitionIds.mkString(", ")}")

      onShuffleDataRequest match {
        case Some(handler) =>
          try {
            val recordsSent = handler(request)
            logger.info(s"Successfully sent $recordsSent records to recovery worker ${request.requesterWorkerId}")
            RequestShuffleDataResponse(
              success = true,
              message = s"Sent $recordsSent records for partitions ${request.partitionIds.mkString(", ")}",
              recordsSent = recordsSent
            )
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to handle shuffle data request: ${ex.getMessage}", ex)
              RequestShuffleDataResponse(
                success = false,
                message = s"Error: ${ex.getMessage}",
                recordsSent = 0
              )
          }

        case None =>
          logger.error("Shuffle data request handler not set!")
          RequestShuffleDataResponse(
            success = false,
            message = "Handler not configured - worker may not be fully initialized",
            recordsSent = 0
          )
      }
    }
  }
}