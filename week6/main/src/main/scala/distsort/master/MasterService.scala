package distsort.master

import distsort.proto.distsort._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.stub.StreamObserver

import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Phase-specific timeout configuration
 *
 * Timeouts are tuned based on expected workload characteristics:
 * - Sampling: Fast (2 min) - only reads samples
 * - Sorting: Moderate (10 min) - CPU-intensive, scales with data size
 * - Shuffling: Long (15 min) - Network-bound, depends on bandwidth
 * - Merging: Moderate (10 min) - I/O-intensive, k-way merge
 */
object PhaseTimeouts {
  val PHASE_SAMPLING: Int = 120      // 2 minutes
  val PHASE_SORTING: Int = 600       // 10 minutes
  val PHASE_SHUFFLING: Int = 900     // 15 minutes
  val PHASE_MERGING: Int = 600       // 10 minutes
  val DEFAULT: Int = 300             // 5 minutes (fallback)

  /**
   * Get timeout for a specific phase
   * @param phase The worker phase
   * @return Timeout in seconds
   */
  def getTimeout(phase: WorkerPhase): Int = phase match {
    case WorkerPhase.PHASE_SAMPLING => PHASE_SAMPLING
    case WorkerPhase.PHASE_SORTING => PHASE_SORTING
    case WorkerPhase.PHASE_SHUFFLING => PHASE_SHUFFLING
    case WorkerPhase.PHASE_MERGING => PHASE_MERGING
    case _ => DEFAULT
  }
}

/**
 * Worker identity based on host and port.
 * Used to recognize restarted workers with the same parameters.
 *
 * Slide 13: "A new worker starts on the same node (using the same parameters)"
 * → Same node = same host, same parameters = same port (same process location)
 * → Same identity = same worker index
 */
case class WorkerIdentity(
  host: String,
  port: Int
) {
  override def toString: String = {
    s"WorkerIdentity($host:$port)"
  }
}

/**
 * Worker status for tracking lifecycle
 */
object WorkerStatus extends Enumeration {
  type WorkerStatus = Value
  val ALIVE = Value("ALIVE")           // Worker is active and healthy
  val FAILED = Value("FAILED")         // Worker failed (heartbeat timeout)
  val RECOVERED = Value("RECOVERED")   // Worker re-registered after failure
}

/**
 * gRPC service implementation for the Master node.
 * Manages worker registration, sample collection, partition configuration,
 * and phase synchronization.
 *
 * Enhanced with worker re-registration support for fault tolerance.
 */
class MasterService(
  expectedWorkers: Int,
  numPartitions: Int = 9  // Default to 3 partitions per worker
)(implicit ec: ExecutionContext) extends MasterServiceGrpc.MasterService with LazyLogging {

  // Worker management
  private val registeredWorkers = new TrieMap[String, WorkerInfo]()
  private val workerIndexCounter = new AtomicInteger(0)
  private val registrationLatch = new CountDownLatch(expectedWorkers)

  // Worker re-registration support (Fault Tolerance - Slide 13)
  private val workerIdentities = new ConcurrentHashMap[WorkerIdentity, String]()  // identity → workerId
  private val workerStatus = new ConcurrentHashMap[String, WorkerStatus.WorkerStatus]()  // workerId → status

  // Sample collection
  private val collectedSamples = new ConcurrentHashMap[String, Seq[Array[Byte]]]()
  private val samplesLatch = new CountDownLatch(expectedWorkers)

  // Partition boundaries (computed after sampling)
  @volatile private var partitionBoundaries: Seq[Array[Byte]] = Seq.empty

  // Workflow completion flag for graceful shutdown coordination
  @volatile private var workflowCompleted: Boolean = false

  // Shuffle map (partition ID -> worker index)
  @volatile private var shuffleMap: Map[Int, Int] = Map.empty

  // Phase tracking
  private val phaseCompletions = new ConcurrentHashMap[WorkerPhase, ConcurrentHashMap[String, Boolean]]()
  private val phaseLatches = new ConcurrentHashMap[WorkerPhase, CountDownLatch]()
  WorkerPhase.values.foreach { phase =>
    if (phase != WorkerPhase.PHASE_UNKNOWN) {
      phaseCompletions.put(phase, new ConcurrentHashMap[String, Boolean]())
    }
  }

  // Heartbeat tracking
  private val workerLastHeartbeat = new ConcurrentHashMap[String, Instant]()
  private val heartbeatTimeout = 6.seconds  // Fast failure detection for tests
  private val MIN_WORKER_THRESHOLD = 0.5
  private val heartbeatCheckScheduler = Executors.newScheduledThreadPool(1)

  // Start heartbeat monitoring
  startHeartbeatMonitoring()

  // Worker information
  case class WorkerInfo(
    workerId: String,
    host: String,
    port: Int,
    workerIndex: Int,
    numCores: Int,
    availableMemory: Long,
    registrationTime: Long = System.currentTimeMillis()
  )

  /**
   * Create canonical worker identity from registration request
   * Identity is based on host and port (Slide 13: "same node, same parameters")
   */
  private def createWorkerIdentity(request: RegisterWorkerRequest): WorkerIdentity = {
    WorkerIdentity(
      host = request.host,
      port = request.port
    )
  }

  /**
   * Handle worker registration (including re-registration)
   *
   * Slide 13 Support: "A new worker starts on the same node (using the same parameters)"
   * → Same parameters (input/output dirs) = Same worker identity = Same worker index
   */
  override def registerWorker(request: RegisterWorkerRequest): Future[RegisterWorkerResponse] = {
    Future {
      synchronized {
        val workerId = request.workerId
        val identity = createWorkerIdentity(request)

        // Check 1: Is this a known worker identity? (re-registration after crash)
        val existingWorkerId = workerIdentities.get(identity)

        val response = if (existingWorkerId != null) {
          // This identity was seen before - check worker status
          val status = workerStatus.getOrDefault(existingWorkerId, WorkerStatus.ALIVE)

          status match {
            case WorkerStatus.FAILED =>
              // Worker is re-registering after failure - RECOVERY!
              logger.info(s"⭐ WORKER RECOVERY: $existingWorkerId re-registering after failure (identity: $identity)")

              val existingWorker = registeredWorkers.get(existingWorkerId)
              if (existingWorker.isDefined) {
                // Update status and heartbeat
                workerStatus.put(existingWorkerId, WorkerStatus.RECOVERED)
                workerLastHeartbeat.put(existingWorkerId, Instant.now())

                val msg = s"Worker recovered from failure (same index: ${existingWorker.get.workerIndex})"
                logger.info(s"✅ $msg")

                Some(RegisterWorkerResponse(
                  success = true,
                  message = msg,
                  assignedWorkerIndex = existingWorker.get.workerIndex
                ))
              } else {
                logger.warn(s"Worker $existingWorkerId marked as FAILED but not in registry")
                // Fall through to new registration
                None
              }

            case WorkerStatus.ALIVE | WorkerStatus.RECOVERED =>
              // Worker already registered and alive - duplicate or heartbeat update
              logger.warn(s"Duplicate registration from $existingWorkerId (status: $status)")

              val existingWorker = registeredWorkers.get(existingWorkerId)
              if (existingWorker.isDefined) {
                // Update heartbeat
                workerLastHeartbeat.put(existingWorkerId, Instant.now())

                Some(RegisterWorkerResponse(
                  success = true,
                  message = s"Worker already registered (index: ${existingWorker.get.workerIndex})",
                  assignedWorkerIndex = existingWorker.get.workerIndex
                ))
              } else {
                None
              }
          }
        } else {
          None
        }

        // If we got a response from the existing worker check, return it
        response.getOrElse {
          // Check 2: Have we reached capacity?
          if (registeredWorkers.size >= expectedWorkers) {
            logger.warn(s"Rejecting worker $workerId - capacity reached ($expectedWorkers workers)")
            RegisterWorkerResponse(
              success = false,
              message = s"Maximum number of workers ($expectedWorkers) already registered",
              assignedWorkerIndex = -1
            )
          } else {
            // New worker registration
            val workerIndex = workerIndexCounter.getAndIncrement()
            val workerInfo = WorkerInfo(
              workerId = workerId,
              host = request.host,
              port = request.port,
              workerIndex = workerIndex,
              numCores = request.numCores,
              availableMemory = request.availableMemory
            )

            registeredWorkers.put(workerId, workerInfo)
            workerIdentities.put(identity, workerId)  // Track identity
            workerStatus.put(workerId, WorkerStatus.ALIVE)  // Initial status
            workerLastHeartbeat.put(workerId, Instant.now())
            registrationLatch.countDown()

            logger.info(s"✅ NEW WORKER: $workerId registered (index: $workerIndex, " +
              s"${registeredWorkers.size}/$expectedWorkers, identity: $identity)")

            RegisterWorkerResponse(
              success = true,
              message = s"Worker registered as index $workerIndex",
              assignedWorkerIndex = workerIndex
            )
          }
        }
      }
    }
  }

  /**
   * Collect sample keys from workers
   */
  override def submitSampleKeys(request: SampleKeysRequest): Future[SampleKeysResponse] = {
    Future {
      val workerId = request.workerId

      if (!registeredWorkers.contains(workerId)) {
        logger.error(s"Samples received from unregistered worker: $workerId")
        SampleKeysResponse(acknowledged = false)
      } else {
        val keys = request.keys.map(_.toByteArray)
        collectedSamples.put(workerId, keys)
        samplesLatch.countDown()

        logger.info(s"Received ${keys.length} sample keys from worker $workerId " +
          s"(${collectedSamples.size()}/$expectedWorkers collected)")

        // If all samples collected, compute boundaries
        if (collectedSamples.size() == expectedWorkers) {
          computePartitionBoundaries()
        }

        SampleKeysResponse(acknowledged = true)
      }
    }
  }

  /**
   * Get partition configuration
   */
  override def getPartitionConfig(request: PartitionConfigRequest): Future[PartitionConfigResponse] = {
    Future {
      val workerId = request.workerId

      if (!registeredWorkers.contains(workerId)) {
        logger.error(s"Partition config requested by unregistered worker: $workerId")
        PartitionConfigResponse(
          boundaries = Seq.empty,
          numPartitions = 0,
          workerPartitionAssignments = Seq.empty
        )
      } else {
        // Wait for boundaries to be computed
        waitForBoundaries()

        val workerInfo = registeredWorkers(workerId)
        val assignments = getWorkerPartitionAssignments(workerInfo.workerIndex)

        logger.info(s"Sending partition config to worker $workerId: " +
          s"${partitionBoundaries.size} boundaries, partitions ${assignments.mkString(",")}")

        // ⭐ FIX: Only return ALIVE workers (exclude failed workers)
        val aliveWorkers = registeredWorkers.filter { case (id, _) =>
          workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
        }.values.toSeq.map { wi =>
          distsort.proto.distsort.WorkerInfo(wi.workerId, wi.host, wi.port, wi.workerIndex)
        }

        val aliveWorkerIndices = getAliveWorkerIndices()
        val aliveWorkerPosition = aliveWorkerIndices.indexOf(workerInfo.workerIndex)
        logger.info(s"Worker $workerInfo.workerIndex (position $aliveWorkerPosition/${aliveWorkerIndices.size} alive): partitions ${assignments.mkString(",")}")

        PartitionConfigResponse(
          boundaries = partitionBoundaries.map(com.google.protobuf.ByteString.copyFrom),
          numPartitions = numPartitions,
          workerPartitionAssignments = assignments,
          allWorkers = aliveWorkers,
          shuffleMap = shuffleMap
        )
      }
    }
  }

  /**
   * Handle phase completion reports
   */
  override def reportPhaseComplete(request: PhaseCompleteRequest): Future[PhaseCompleteResponse] = {
    Future {
      val workerId = request.workerId
      val phase = request.phase

      if (!registeredWorkers.contains(workerId)) {
        logger.error(s"Phase completion from unregistered worker: $workerId")
        PhaseCompleteResponse(
          proceedToNext = false,
          nextPhase = WorkerPhase.PHASE_FAILED,
          message = "Unregistered worker"
        )
      } else {
        // Record completion
        val phaseMap = phaseCompletions.get(phase)
        if (phaseMap != null) {
          // ⭐ FIX: Check if this is a NEW completion (not a retry)
          val isNewCompletion = !phaseMap.containsKey(workerId)

          phaseMap.put(workerId, true)

          val completedCount = phaseMap.size()

          logger.info(s"Worker $workerId completed $phase ($completedCount/$expectedWorkers expected)${if (!isNewCompletion) " [RETRY]" else ""}")

          // Count down the latch ONLY for new completions (not retries)
          if (isNewCompletion) {
            val latch = phaseLatches.get(phase)
            if (latch != null) {
              latch.countDown()
            }
          }

          // ⭐ FIX: Check if all EXPECTED workers completed (wait for all to join)
          val allComplete = completedCount >= expectedWorkers

          if (allComplete) {
            logger.info(s"✅ All expected workers completed $phase ($completedCount/$expectedWorkers)")
            val nextPhase = getNextPhase(phase)

            PhaseCompleteResponse(
              proceedToNext = true,
              nextPhase = nextPhase,
              message = s"All workers ready for $nextPhase"
            )
          } else {
            PhaseCompleteResponse(
              proceedToNext = false,
              nextPhase = phase,
              message = s"Waiting for ${expectedWorkers - completedCount} more workers (${completedCount}/${expectedWorkers} done)"
            )
          }
        } else {
          // Phase map doesn't exist - Master already moved to next phase
          logger.warn(s"Worker $workerId reporting $phase but phase map doesn't exist (already progressed)")
          val nextPhase = getNextPhase(phase)
          PhaseCompleteResponse(
            proceedToNext = true,
            nextPhase = nextPhase,
            message = s"Phase already completed, proceed to $nextPhase"
          )
        }
      }
    }
  }

  /**
   * Handle heartbeat from workers
   */
  override def heartbeat(request: HeartbeatRequest): Future[HeartbeatResponse] = {
    Future {
      val workerId = request.workerId

      if (!registeredWorkers.contains(workerId)) {
        logger.warn(s"Heartbeat from unregistered worker: $workerId")
        HeartbeatResponse(
          acknowledged = false,
          shouldAbort = true,
          message = "Unregistered worker"
        )
      } else {
        // Update heartbeat timestamp
        workerLastHeartbeat.put(workerId, Instant.now())

        logger.debug(s"Heartbeat received from worker $workerId at phase ${request.currentPhase}, " +
          s"progress: ${request.progressPercentage}%")

        // Signal workers to gracefully shut down if workflow is completed
        if (workflowCompleted) {
          logger.info(s"Workflow completed - signaling Worker $workerId to shut down gracefully")
          HeartbeatResponse(
            acknowledged = true,
            shouldAbort = true,
            message = "Workflow completed - please shut down"
          )
        } else {
          HeartbeatResponse(
            acknowledged = true,
            shouldAbort = false,
            message = "Healthy"
          )
        }
      }
    }
  }

  /**
   * Handle error reports
   */
  override def reportError(request: ReportErrorRequest): Future[ReportErrorResponse] = {
    Future {
      val workerId = request.workerId
      val errorType = request.errorType
      val phase = request.phaseWhenErrorOccurred

      logger.error(s"Worker $workerId reported error: $errorType at phase $phase - ${request.errorMessage}")

      // Simple error handling strategy
      val response = phase match {
        case WorkerPhase.PHASE_SAMPLING | WorkerPhase.PHASE_SORTING =>
          // Can continue with degraded performance
          if (registeredWorkers.size > expectedWorkers / 2) {
            ReportErrorResponse(
              shouldRetry = false,
              shouldAbort = false,
              instructions = "Continue with reduced worker count"
            )
          } else {
            ReportErrorResponse(
              shouldRetry = false,
              shouldAbort = true,
              instructions = "Too many workers failed - aborting job"
            )
          }

        case WorkerPhase.PHASE_SHUFFLING | WorkerPhase.PHASE_MERGING =>
          // Critical phases - cannot continue
          ReportErrorResponse(
            shouldRetry = true,
            shouldAbort = false,
            instructions = "Retry the operation"
          )

        case _ =>
          ReportErrorResponse(
            shouldRetry = false,
            shouldAbort = true,
            instructions = "Unknown error - aborting"
          )
      }

      response
    }
  }

  // Helper methods

  /**
   * Create shuffle map that assigns partitions to workers
   * This is the core logic for partition distribution
   *
   * ⭐ FIX: Now uses alive worker indices instead of assuming 0,1,2...
   *
   * @param workerIndices Actual worker indices to use (alive workers)
   * @param numPartitions Total number of partitions
   * @return Map from partition ID to worker index
   */
  def createShuffleMap(workerIndices: Seq[Int], numPartitions: Int): Map[Int, Int] = {
    if (workerIndices.isEmpty) {
      logger.warn("createShuffleMap called with no worker indices!")
      return Map.empty
    }

    val shuffleMap = scala.collection.mutable.Map[Int, Int]()
    val numWorkers = workerIndices.size
    val partitionsPerWorker = numPartitions / numWorkers

    for (partitionID <- 0 until numPartitions) {
      val workerPosition = partitionID / partitionsPerWorker
      val finalPosition = if (workerPosition >= numWorkers) numWorkers - 1 else workerPosition
      // Use actual worker index (not position)
      shuffleMap(partitionID) = workerIndices(finalPosition)
    }

    logger.debug(s"Created shuffle map for ${workerIndices.size} workers (indices: ${workerIndices.mkString(",")})")
    shuffleMap.toMap
  }

  /**
   * Compute partition boundaries from collected samples
   * ⭐ FIX: Now uses alive workers for shuffle map creation
   */
  private def computePartitionBoundaries(): Unit = {
    logger.info(s"Computing partition boundaries from ${collectedSamples.size()} workers' samples")

    // Collect all samples
    val allSamples = collectedSamples.values().asScala.flatten.toSeq

    // Sort samples using ByteArrayOrdering
    implicit val byteArrayOrdering: Ordering[Array[Byte]] = new Ordering[Array[Byte]] {
      override def compare(x: Array[Byte], y: Array[Byte]): Int = {
        var i = 0
        while (i < x.length && i < y.length) {
          val cmp = java.lang.Integer.compareUnsigned(x(i) & 0xFF, y(i) & 0xFF)
          if (cmp != 0) return cmp
          i += 1
        }
        x.length - y.length
      }
    }
    val sortedSamples = allSamples.sorted

    // Select boundaries (evenly spaced)
    val step = sortedSamples.length / numPartitions
    partitionBoundaries = (1 until numPartitions).map { i =>
      sortedSamples(i * step)
    }

    // ⭐ FIX: Create shuffle map using alive worker indices
    val aliveWorkerIndices = getAliveWorkerIndices()
    shuffleMap = createShuffleMap(aliveWorkerIndices, numPartitions)

    logger.info(s"Computed ${partitionBoundaries.size} partition boundaries and shuffle map for ${aliveWorkerIndices.size} alive workers (indices: ${aliveWorkerIndices.mkString(",")})")
  }

  /**
   * Wait for partition boundaries to be computed
   */
  private def waitForBoundaries(): Unit = {
    val maxWaitMs = 60000 // 1 minute
    val startTime = System.currentTimeMillis()

    while (partitionBoundaries.isEmpty &&
           System.currentTimeMillis() - startTime < maxWaitMs) {
      Thread.sleep(100)
    }

    if (partitionBoundaries.isEmpty) {
      throw new RuntimeException("Timeout waiting for partition boundaries")
    }
  }

  /**
   * Start heartbeat monitoring thread
   */
  private def startHeartbeatMonitoring(): Unit = {
    heartbeatCheckScheduler.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = checkWorkerHealth()
      },
      heartbeatTimeout.toSeconds,
      heartbeatTimeout.toSeconds / 2,
      TimeUnit.SECONDS
    )
    logger.info("Started heartbeat monitoring")
  }

  /**
   * Check worker health based on heartbeat timeouts
   * ⭐ KEY FIX: Process failures one at a time, checking threshold after each
   */
  private def checkWorkerHealth(): Unit = {
    val now = Instant.now()
    val failedWorkers = scala.collection.mutable.ListBuffer[String]()

    workerLastHeartbeat.asScala.foreach { case (workerId, lastTime) =>
      val secondsSinceLastHeartbeat = java.time.Duration.between(lastTime, now).getSeconds
      if (secondsSinceLastHeartbeat > heartbeatTimeout.toSeconds) {
        logger.warn(s"Worker $workerId missed heartbeat (${secondsSinceLastHeartbeat}s since last)")
        failedWorkers += workerId
      }
    }

    // ⭐ KEY FIX: Process failures one at a time, checking threshold after each
    // This prevents marking all workers as FAILED before checking if we can continue
    for (workerId <- failedWorkers) {
      // Check if we still have enough workers before processing this failure
      val currentAliveCount = registeredWorkers.count { case (id, _) =>
        workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
      }

      // If processing this failure would put us below threshold, skip this worker
      val countAfterFailure = currentAliveCount - 1
      if (countAfterFailure < (expectedWorkers * MIN_WORKER_THRESHOLD)) {
        logger.warn(s"⚠️  Skipping failure processing for $workerId - would drop below threshold " +
          s"(current: $currentAliveCount/$expectedWorkers, after: $countAfterFailure/$expectedWorkers)")
        // ⭐ FIX: Don't return! Continue checking other workers
      } else {
        // Process this failure
        logger.info(s"📉 Processing failure for $workerId (current alive: $currentAliveCount/$expectedWorkers)")
        handleWorkerFailure(workerId)
      }
    }
  }

  /**
   * Handle worker failure based on current phase
   *
   * Key change for Slide 13 support:
   * - DO NOT remove worker from registry (keep for re-registration)
   * - Mark as FAILED instead
   * - Worker can re-register with same identity → same index
   */
  private def handleWorkerFailure(workerId: String): Unit = synchronized {
    logger.error(s"❌ WORKER FAILURE DETECTED: $workerId")

    // Check if worker exists
    if (!registeredWorkers.contains(workerId)) {
      logger.warn(s"Worker $workerId not in registry, skipping failure handling")
      return
    }

    // Check current status
    val currentStatus = workerStatus.getOrDefault(workerId, WorkerStatus.ALIVE)
    if (currentStatus == WorkerStatus.FAILED) {
      logger.warn(s"Worker $workerId already marked as FAILED, skipping")
      return
    }

    // ⭐ KEY CHANGE: Mark as FAILED instead of removing
    workerStatus.put(workerId, WorkerStatus.FAILED)
    // DO NOT remove from registeredWorkers! (needed for re-registration)
    // registeredWorkers.remove(workerId)  // ← OLD CODE (removed)

    // Remove from heartbeat tracking (no longer expecting heartbeats)
    workerLastHeartbeat.remove(workerId)

    // Count alive workers (not FAILED)
    val aliveWorkers = registeredWorkers.count { case (id, _) =>
      workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
    }

    val thresholdMet = aliveWorkers >= (expectedWorkers * MIN_WORKER_THRESHOLD)

    logger.info(s"Alive workers: $aliveWorkers/$expectedWorkers (threshold: 50%)")

    if (thresholdMet) {
      logger.info(s"✅ CONTINUING with $aliveWorkers workers (above threshold)")
      logger.info(s"⏳ Waiting for $workerId to re-register...")

      // Adjust latches to unblock alive workers
      adjustLatchesForAliveWorkers(aliveWorkers)

    } else {
      logger.error(s"❌ ABORTING: Too few workers ($aliveWorkers/$expectedWorkers, below 50% threshold)")
      // TODO: Send abort signal to all workers
    }
  }

  /**
   * Adjust all latches to match alive worker count
   * Allows alive workers to proceed even if some workers failed
   */
  private def adjustLatchesForAliveWorkers(aliveWorkers: Int): Unit = {
    // Adjust registration latch
    val registrationCount = registrationLatch.getCount
    if (registrationCount > 0) {
      logger.info(s"Adjusting registration latch: $registrationCount → 0 (unblocking)")
      while (registrationLatch.getCount > 0) {
        registrationLatch.countDown()
      }
    }

    // Adjust phase latches
    phaseLatches.asScala.foreach { case (phase, latch) =>
      val currentCount = latch.getCount
      if (currentCount > aliveWorkers) {
        logger.info(s"Adjusting $phase latch: $currentCount → $aliveWorkers")
        while (latch.getCount > aliveWorkers) {
          latch.countDown()
        }
      }
    }

    // Adjust samples latch
    // ⭐ FIX: When a worker fails, we need to unblock waiting workers
    // by counting down to 0 (not to aliveWorkers, which doesn't make sense for this latch)
    val samplesCount = samplesLatch.getCount
    if (samplesCount > 0) {
      logger.info(s"Adjusting samples latch: $samplesCount → 0 (unblocking all waiting workers)")
      while (samplesLatch.getCount > 0) {
        samplesLatch.countDown()
      }
    }

    // ⭐⭐ CRITICAL FIX: Wait for ALL expected workers to submit samples before computing partitions
    // This prevents timing issues where some workers register late
    if (collectedSamples.size() >= expectedWorkers && partitionBoundaries.isEmpty) {
      logger.info(s"⚡ Computing partition boundaries with ${collectedSamples.size()} samples from $expectedWorkers workers")
      computePartitionBoundaries()
    }
  }

  /**
   * Count alive (non-failed) workers
   */
  private def countAliveWorkers(): Int = synchronized {
    registeredWorkers.count { case (id, _) =>
      workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
    }
  }

  /**
   * Get sorted list of alive worker indices
   */
  private def getAliveWorkerIndices(): Seq[Int] = synchronized {
    registeredWorkers
      .filter { case (id, _) =>
        workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
      }
      .values
      .map(_.workerIndex)
      .toSeq
      .sorted
  }

  /**
   * Get partition assignments for a worker (N->M strategy)
   * ⭐ FIXED: Use alive workers instead of expectedWorkers
   *
   * When workers fail, partitions are redistributed among alive workers:
   * - Example: 6 partitions, 3 workers → 2 partitions each
   * - Worker 2 fails → 2 alive workers → 3 partitions each
   * - Worker 0 (alive index 0): partitions 0, 1, 2
   * - Worker 1 (alive index 1): partitions 3, 4, 5
   */
  private def getWorkerPartitionAssignments(workerIndex: Int): Seq[Int] = synchronized {
    val aliveWorkerIndices = getAliveWorkerIndices()
    val aliveWorkers = aliveWorkerIndices.size

    // If no alive workers or worker not in alive list, return empty
    if (aliveWorkers == 0 || !aliveWorkerIndices.contains(workerIndex)) {
      logger.warn(s"Worker $workerIndex is not alive or no alive workers exist")
      return Seq.empty
    }

    // Find this worker's position in the alive workers list
    val aliveWorkerPosition = aliveWorkerIndices.indexOf(workerIndex)

    // Calculate partitions based on alive worker count
    val partitionsPerWorker = numPartitions / aliveWorkers
    val remainder = numPartitions % aliveWorkers

    // Distribute remainder partitions to first workers
    val startPartition = aliveWorkerPosition * partitionsPerWorker + math.min(aliveWorkerPosition, remainder)
    val extraPartition = if (aliveWorkerPosition < remainder) 1 else 0
    val endPartition = startPartition + partitionsPerWorker + extraPartition

    val assignments = (startPartition until endPartition).toSeq

    logger.info(s"Worker $workerIndex (position $aliveWorkerPosition/$aliveWorkers alive): " +
      s"partitions ${assignments.mkString(",")}")

    assignments
  }

  /**
   * Determine next phase in workflow
   */
  private def getNextPhase(currentPhase: WorkerPhase): WorkerPhase = {
    currentPhase match {
      case WorkerPhase.PHASE_INITIALIZING => WorkerPhase.PHASE_SAMPLING
      case WorkerPhase.PHASE_SAMPLING => WorkerPhase.PHASE_WAITING_FOR_PARTITIONS
      case WorkerPhase.PHASE_WAITING_FOR_PARTITIONS => WorkerPhase.PHASE_SORTING
      case WorkerPhase.PHASE_SORTING => WorkerPhase.PHASE_SHUFFLING
      case WorkerPhase.PHASE_SHUFFLING => WorkerPhase.PHASE_MERGING
      case WorkerPhase.PHASE_MERGING => WorkerPhase.PHASE_COMPLETED
      case _ => WorkerPhase.PHASE_FAILED
    }
  }

  // Public methods for testing

  def getRegisteredWorkerCount: Int = registeredWorkers.size

  def getRegisteredWorkers: Seq[WorkerInfo] = {
    registeredWorkers.values.toSeq.sortBy(_.workerIndex)
  }

  def waitForAllWorkers(timeoutSeconds: Int = 60): Boolean = {
    registrationLatch.await(timeoutSeconds, TimeUnit.SECONDS)
  }

  def waitForAllSamples(timeoutSeconds: Int = 60): Boolean = {
    samplesLatch.await(timeoutSeconds, TimeUnit.SECONDS)
  }

  def getBoundaries: Seq[Array[Byte]] = partitionBoundaries

  /**
   * Initialize phase tracking for a specific phase
   * Creates a new CountDownLatch to wait for all workers to complete
   *
   * @param phase The phase to initialize tracking for
   */
  def initPhaseTracking(phase: WorkerPhase): Unit = {
    // Use expected workers count (not registered count which may be 0 at start)
    // This ensures phase tracking is ready before workers start their workflow
    logger.info(s"Initializing phase tracking for $phase (expecting $expectedWorkers workers)")

    // Clear previous completions
    val phaseMap = phaseCompletions.get(phase)
    if (phaseMap != null) {
      phaseMap.clear()
    }

    // Create new latch with expected workers count
    phaseLatches.put(phase, new CountDownLatch(expectedWorkers))
  }

  /**
   * Wait for all workers to complete a phase with default timeout
   * Uses phase-specific timeout from PhaseTimeouts configuration
   *
   * @param phase The phase to wait for
   * @return true if all workers completed, false if timeout
   */
  def waitForPhaseCompletion(phase: WorkerPhase): Boolean = {
    val timeoutSeconds = PhaseTimeouts.getTimeout(phase)
    waitForPhaseCompletion(phase, timeoutSeconds)
  }

  /**
   * Wait for all workers to complete a phase with explicit timeout
   * Blocks until all workers report completion or timeout is reached
   *
   * @param phase The phase to wait for
   * @param timeoutSeconds Maximum time to wait in seconds (overrides default)
   * @return true if all workers completed, false if timeout
   */
  def waitForPhaseCompletion(phase: WorkerPhase, timeoutSeconds: Int): Boolean = {
    val latch = phaseLatches.get(phase)

    if (latch == null) {
      logger.warn(s"Phase $phase not initialized for tracking - call initPhaseTracking() first")
      return false
    }

    logger.info(s"Waiting for all $expectedWorkers workers to complete $phase (timeout: ${timeoutSeconds}s)")
    val startTime = System.currentTimeMillis()

    val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
    val duration = (System.currentTimeMillis() - startTime) / 1000.0

    if (completed) {
      val count = phaseCompletions.get(phase).size()
      logger.info(f"All workers completed $phase in ${duration}%.2fs ($count/$expectedWorkers)")
    } else {
      val count = phaseCompletions.get(phase).size()
      logger.error(f"Timeout waiting for $phase completion after ${duration}%.2fs ($count/$expectedWorkers)")
    }

    completed
  }

  /**
   * Reset phase tracking for a specific phase
   * Useful for retrying a phase or running multiple iterations
   *
   * @param phase The phase to reset
   */
  def resetPhaseTracking(phase: WorkerPhase): Unit = {
    logger.info(s"Resetting phase tracking for $phase")

    val phaseMap = phaseCompletions.get(phase)
    if (phaseMap != null) {
      phaseMap.clear()
    }

    phaseLatches.put(phase, new CountDownLatch(expectedWorkers))
  }

  /**
   * Get the number of workers that have completed a phase
   *
   * @param phase The phase to check
   * @return Number of workers that completed this phase
   */
  def getPhaseCompletionCount(phase: WorkerPhase): Int = {
    val phaseMap = phaseCompletions.get(phase)
    if (phaseMap != null) {
      phaseMap.size()
    } else {
      0
    }
  }

  /**
   * Signal that the workflow has completed successfully.
   * Workers will be instructed to shut down gracefully on their next heartbeat.
   */
  def signalWorkflowComplete(): Unit = {
    logger.info("Signaling workflow completion to all workers")
    workflowCompleted = true
  }
}