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
 * Worker identity based on host only (not port).
 * Used to recognize restarted workers with the same parameters.
 *
 * Slide 13: "A new worker starts on the same node (using the same parameters)"
 * → Same node = same host (port may differ due to auto-assign)
 * → Same identity = same worker index
 *
 * Key insight: When a worker restarts, it gets a new port (auto-assigned),
 * but it's still the same logical worker if it's on the same host.
 */
case class WorkerIdentity(
  host: String
) {
  override def toString: String = {
    s"WorkerIdentity($host)"
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
  initialNumPartitions: Int = 9  // Initial estimate, will be recalculated dynamically
)(implicit ec: ExecutionContext) extends MasterServiceGrpc.MasterService with LazyLogging {

  // Dynamic partitioning configuration
  // ⭐ 파티션 크기를 작게 하여 더 많은 파티션 생성 (병렬성 향상)
  private val TARGET_PARTITION_SIZE_MB: Long = 32   // Target 32MB per partition (더 작은 조각)
  private val MIN_PARTITIONS_PER_WORKER: Int = 3    // 최소 worker당 3개 파티션 (항상 N*3 보장)
  private val MAX_PARTITIONS_PER_WORKER: Int = 10   // Maximum partitions per worker

  // Dynamic partition count (will be calculated after all workers register)
  @volatile private var numPartitions: Int = initialNumPartitions
  @volatile private var dynamicPartitioningEnabled: Boolean = true

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

  // Current workflow phase (for recovery workers)
  @volatile private var currentWorkflowPhase: WorkerPhase = WorkerPhase.PHASE_INITIALIZING

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
  // ⭐ 빠른 crash 감지를 위해 주기와 threshold 축소
  // - 기존: 5초 × 3회 = 15초 (너무 느림)
  // - 변경: 2초 × 2회 = 4초 (빠른 감지)
  private val workerLastHeartbeat = new ConcurrentHashMap[String, Instant]()
  private val workerMissedHeartbeats = new ConcurrentHashMap[String, Int]()  // 연속 미수신 횟수
  private val heartbeatCheckInterval = 2.seconds  // 체크 주기 (2초)
  private val MISSED_HEARTBEAT_THRESHOLD = 2  // 2회 연속 미수신 시 FAILED
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
    totalInputBytes: Long = 0,  // Input size for dynamic partitioning
    registrationTime: Long = System.currentTimeMillis()
  )

  /**
   * Calculate dynamic partition count based on total input size
   * Uses Spark-like heuristic: totalSize / TARGET_PARTITION_SIZE
   *
   * ⭐ IMPORTANT: 파티션 수는 항상 worker 수의 배수!
   * - 3 workers → 3, 6, 9, 12, ... 파티션
   * - 이렇게 해야 각 worker가 동일한 수의 파티션을 담당
   *
   * Constraints:
   * - Minimum: expectedWorkers * MIN_PARTITIONS_PER_WORKER (최소 worker당 3개)
   * - Maximum: expectedWorkers * MAX_PARTITIONS_PER_WORKER
   * - Always multiple of expectedWorkers
   */
  private def calculateDynamicPartitionCount(): Int = {
    if (!dynamicPartitioningEnabled) {
      logger.info(s"Dynamic partitioning disabled, using fixed count: $numPartitions")
      return numPartitions
    }

    val totalInputBytes = registeredWorkers.values.map(_.totalInputBytes).sum
    val totalInputMB = totalInputBytes / (1024 * 1024)

    if (totalInputBytes == 0) {
      // No input size reported - fall back to default (workers * 3)
      val defaultCount = expectedWorkers * MIN_PARTITIONS_PER_WORKER
      logger.info(s"No input size reported, using default partition count: $defaultCount")
      return defaultCount
    }

    // Calculate ideal partition count based on target size
    val idealPartitions = math.max(1, (totalInputMB / TARGET_PARTITION_SIZE_MB).toInt)

    // Apply constraints
    val minPartitions = expectedWorkers * MIN_PARTITIONS_PER_WORKER
    val maxPartitions = expectedWorkers * MAX_PARTITIONS_PER_WORKER

    // Constrain to range first
    val constrainedPartitions = math.max(minPartitions, math.min(maxPartitions, idealPartitions))

    // ⭐ Round UP to nearest multiple of expectedWorkers
    // This ensures even distribution: 4 → 6 (for 3 workers), 7 → 9, etc.
    val finalPartitions = ((constrainedPartitions + expectedWorkers - 1) / expectedWorkers) * expectedWorkers

    logger.info(s"⭐ Dynamic Partitioning: totalInput=${totalInputMB}MB, " +
      s"target=${TARGET_PARTITION_SIZE_MB}MB/partition, " +
      s"ideal=$idealPartitions, constrained=$constrainedPartitions, " +
      s"final=$finalPartitions (multiple of $expectedWorkers workers)")

    finalPartitions
  }

  /**
   * Create canonical worker identity from registration request
   * Identity is based on host only (Slide 13: "same node, same parameters")
   * Port is excluded because restarted workers get new auto-assigned ports.
   */
  private def createWorkerIdentity(request: RegisterWorkerRequest): WorkerIdentity = {
    WorkerIdentity(host = request.host)
  }

  /**
   * Handle worker registration (including re-registration)
   *
   * Slide 13 Support: "A new worker starts on the same node (using the same parameters)"
   * Supports both:
   * - Multiple workers on same host (during initial registration)
   * - Fault tolerance / recovery (when capacity is full, dead worker on same host)
   */
  override def registerWorker(request: RegisterWorkerRequest): Future[RegisterWorkerResponse] = {
    Future {
      synchronized {
        val workerId = request.workerId
        val host = request.host

        // Check 1: Is this exact workerId already registered? (same worker re-registering)
        if (registeredWorkers.contains(workerId)) {
          val existingWorker = registeredWorkers(workerId)
          workerLastHeartbeat.put(workerId, Instant.now())
          logger.info(s"Worker $workerId re-registering (already has index ${existingWorker.workerIndex})")

          return Future.successful(RegisterWorkerResponse(
            success = true,
            message = s"Worker already registered (index: ${existingWorker.workerIndex})",
            assignedWorkerIndex = existingWorker.workerIndex,
            currentPhase = currentWorkflowPhase,
            isRecovery = false
          ))
        }

        // Check 2: Is capacity full?
        if (registeredWorkers.size >= expectedWorkers) {
          // Capacity full - try to find a dead worker to take over
          findDeadWorkerOnHost(host) match {
            case Some((deadWorkerId, deadWorkerInfo)) =>
              // Take over the dead worker's slot (RECOVERY)
              val recoveredIndex = deadWorkerInfo.workerIndex

              // ⭐ RECOVERY 로그 출력
              System.err.println()
              System.err.println("=" * 60)
              System.err.println(s"[Master] ✅ WORKER RECOVERED!")
              System.err.println(s"[Master]    Dead Worker: $deadWorkerId (index $recoveredIndex)")
              System.err.println(s"[Master]    New Worker:  $workerId")
              System.err.println(s"[Master]    Host: $host")
              System.err.println(s"[Master]    Resuming from phase: $currentWorkflowPhase")
              System.err.println("=" * 60)
              System.err.println()

              logger.info(s"RECOVERY: Worker $deadWorkerId is dead - new worker $workerId taking over index $recoveredIndex")

              // Remove old worker (including missed heartbeat count)
              registeredWorkers.remove(deadWorkerId)
              workerStatus.remove(deadWorkerId)
              workerLastHeartbeat.remove(deadWorkerId)
              workerMissedHeartbeats.remove(deadWorkerId)

              // Register new worker with same index
              val newWorkerInfo = WorkerInfo(
                workerId = workerId,
                host = request.host,
                port = request.port,
                workerIndex = recoveredIndex,
                numCores = request.numCores,
                availableMemory = request.availableMemory,
                totalInputBytes = request.totalInputBytes  // Store input size (for logging)
              )

              registeredWorkers.put(workerId, newWorkerInfo)
              workerIdentities.put(WorkerIdentity(host), workerId)
              workerStatus.put(workerId, WorkerStatus.RECOVERED)
              workerLastHeartbeat.put(workerId, Instant.now())
              workerMissedHeartbeats.put(workerId, 0)  // 미수신 횟수 초기화

              RegisterWorkerResponse(
                success = true,
                message = s"Recovered dead worker's slot (index: $recoveredIndex, phase: $currentWorkflowPhase)",
                assignedWorkerIndex = recoveredIndex,
                currentPhase = currentWorkflowPhase,
                isRecovery = true
              )

            case None =>
              // No dead worker to take over - reject
              logger.warn(s"Rejecting worker $workerId - capacity full ($expectedWorkers workers) and no dead worker on host $host")
              RegisterWorkerResponse(
                success = false,
                message = s"Maximum workers ($expectedWorkers) registered, no dead worker to replace on this host",
                assignedWorkerIndex = -1
              )
          }
        } else {
          // Capacity not full - new registration
          val workerIndex = workerIndexCounter.getAndIncrement()
          val workerInfo = WorkerInfo(
            workerId = workerId,
            host = request.host,
            port = request.port,
            workerIndex = workerIndex,
            numCores = request.numCores,
            availableMemory = request.availableMemory,
            totalInputBytes = request.totalInputBytes  // Store input size for dynamic partitioning
          )

          registeredWorkers.put(workerId, workerInfo)
          workerIdentities.put(WorkerIdentity(host), workerId)
          workerStatus.put(workerId, WorkerStatus.ALIVE)
          workerLastHeartbeat.put(workerId, Instant.now())
          workerMissedHeartbeats.put(workerId, 0)  // 미수신 횟수 초기화
          registrationLatch.countDown()

          val inputMB = request.totalInputBytes / 1024 / 1024
          logger.info(s"NEW WORKER: $workerId registered (index: $workerIndex, input: ${inputMB}MB, ${registeredWorkers.size}/$expectedWorkers)")

          RegisterWorkerResponse(
            success = true,
            message = s"Worker registered as index $workerIndex",
            assignedWorkerIndex = workerIndex,
            currentPhase = currentWorkflowPhase,
            isRecovery = false
          )
        }
      }
    }
  }

  /**
   * Find a dead worker on the specified host
   * A worker is considered dead if:
   * - Status is FAILED, or
   * - 연속 미수신 횟수 >= MISSED_HEARTBEAT_THRESHOLD
   *
   * 연속 미수신 횟수 기반으로 판단하므로 false positive 최소화
   */
  private def findDeadWorkerOnHost(host: String): Option[(String, WorkerInfo)] = {
    registeredWorkers.find { case (wid, info) =>
      if (info.host == host) {
        val status = workerStatus.getOrDefault(wid, WorkerStatus.ALIVE)
        if (status == WorkerStatus.FAILED) {
          logger.info(s"Found FAILED worker $wid on host $host")
          true
        } else {
          // 연속 미수신 횟수 기반 판단
          val missedCount = workerMissedHeartbeats.getOrDefault(wid, 0)
          if (missedCount >= MISSED_HEARTBEAT_THRESHOLD) {
            logger.info(s"Found worker $wid on host $host with $missedCount consecutive missed heartbeats")
            true
          } else {
            // 등록 후 heartbeat가 전혀 없고 등록된지 오래된 경우
            val lastHeartbeat = workerLastHeartbeat.get(wid)
            if (lastHeartbeat == null) {
              val workerInfo = registeredWorkers.get(wid)
              val registrationAge = workerInfo.map(w =>
                (System.currentTimeMillis() - w.registrationTime) / 1000
              ).getOrElse(Long.MaxValue)

              // 등록 후 (MISSED_HEARTBEAT_THRESHOLD * heartbeatCheckInterval) 초 이상 heartbeat 없음
              val deadThresholdSeconds = MISSED_HEARTBEAT_THRESHOLD * heartbeatCheckInterval.toSeconds
              if (registrationAge > deadThresholdSeconds) {
                logger.info(s"Found worker $wid on host $host with no heartbeat (registered ${registrationAge}s ago, threshold: ${deadThresholdSeconds}s)")
                true
              } else {
                logger.debug(s"Worker $wid on host $host just registered (${registrationAge}s ago), not marking as dead")
                false
              }
            } else {
              false  // Heartbeat 있고 미수신 횟수 threshold 미만
            }
          }
        }
      } else {
        false
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
            logger.info(s"All expected workers completed $phase ($completedCount/$expectedWorkers)")
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
   * ⭐ Dynamic Partitioning: Calculates optimal partition count based on total input size
   */
  private def computePartitionBoundaries(): Unit = {
    logger.info(s"Computing partition boundaries from ${collectedSamples.size()} workers' samples")

    // ⭐ Dynamic Partitioning: Calculate optimal partition count based on total input size
    numPartitions = calculateDynamicPartitionCount()
    logger.info(s"Using $numPartitions partitions (dynamic calculation)")

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

    // Ensure we have enough samples for partitioning
    if (sortedSamples.length < numPartitions) {
      logger.warn(s"Not enough samples (${sortedSamples.length}) for $numPartitions partitions, adjusting to ${sortedSamples.length}")
      numPartitions = math.max(1, sortedSamples.length)
    }

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
   * 매 heartbeatCheckInterval 마다 모든 Worker의 heartbeat 수신 여부 확인
   */
  private def startHeartbeatMonitoring(): Unit = {
    heartbeatCheckScheduler.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = checkWorkerHealth()
      },
      heartbeatCheckInterval.toSeconds,
      heartbeatCheckInterval.toSeconds,
      TimeUnit.SECONDS
    )
    logger.info(s"Started heartbeat monitoring (interval: ${heartbeatCheckInterval.toSeconds}s, threshold: $MISSED_HEARTBEAT_THRESHOLD misses)")
  }

  /**
   * Check worker health based on consecutive missed heartbeats
   *
   * 연속 미수신 횟수 기반 검출 (시간 기반보다 견고함):
   * - 매 체크 주기마다 heartbeat 수신 여부 확인
   * - 수신: 미수신 횟수 초기화 (0)
   * - 미수신: 미수신 횟수 +1
   * - 미수신 횟수 >= MISSED_HEARTBEAT_THRESHOLD: FAILED 처리
   *
   * 장점:
   * - 네트워크 일시적 지연에 강함 (1-2회 누락은 허용)
   * - 시간 기준이 아닌 "횟수" 기준이므로 더 예측 가능
   */
  private def checkWorkerHealth(): Unit = {
    val now = Instant.now()
    val failedWorkers = scala.collection.mutable.ListBuffer[String]()

    // 등록된 모든 Worker 확인
    registeredWorkers.keys.foreach { workerId =>
      // 이미 FAILED 상태인 Worker는 스킵
      val currentStatus = workerStatus.getOrDefault(workerId, WorkerStatus.ALIVE)
      if (currentStatus != WorkerStatus.FAILED) {
        val lastHeartbeat = workerLastHeartbeat.get(workerId)
        if (lastHeartbeat != null) {
          val secondsSinceLastHeartbeat = java.time.Duration.between(lastHeartbeat, now).getSeconds

          // 체크 주기 내에 heartbeat를 받았으면 미수신 횟수 초기화
          if (secondsSinceLastHeartbeat <= heartbeatCheckInterval.toSeconds + 2) {
            // Heartbeat 정상 수신 - 미수신 횟수 초기화
            val prevMissed = Option(workerMissedHeartbeats.put(workerId, 0))
            if (prevMissed.exists(_ > 0)) {
              logger.info(s"Worker $workerId heartbeat recovered (was ${prevMissed.get} misses)")
            }
          } else {
            // Heartbeat 미수신 - 카운트 증가
            val currentMissed = workerMissedHeartbeats.getOrDefault(workerId, 0)
            val missedCount = currentMissed + 1
            workerMissedHeartbeats.put(workerId, missedCount)

            logger.warn(s"Worker $workerId missed heartbeat #$missedCount (${secondsSinceLastHeartbeat}s since last)")

            // MISSED_HEARTBEAT_THRESHOLD 회 연속 미수신 시 FAILED 처리
            if (missedCount >= MISSED_HEARTBEAT_THRESHOLD) {
              logger.error(s"Worker $workerId FAILED - $missedCount consecutive missed heartbeats")
              failedWorkers += workerId
            }
          }
        }
      }
    }

    // Process failures one at a time, checking threshold after each
    for (workerId <- failedWorkers) {
      val currentAliveCount = registeredWorkers.count { case (id, _) =>
        workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
      }

      val countAfterFailure = currentAliveCount - 1
      if (countAfterFailure < (expectedWorkers * MIN_WORKER_THRESHOLD)) {
        logger.warn(s"Skipping failure processing for $workerId - would drop below threshold " +
          s"(current: $currentAliveCount/$expectedWorkers, after: $countAfterFailure/$expectedWorkers)")
      } else {
        logger.info(s"Processing failure for $workerId (current alive: $currentAliveCount/$expectedWorkers)")
        handleWorkerFailure(workerId)
      }
    }
  }

  /**
   * Handle worker failure based on current phase
   *
   * Slide 13 지원:
   * - Worker를 FAILED로 표시 (registry에서 제거하지 않음)
   * - Latch를 조정하지 않음 → 새 Worker가 등록될 때까지 대기
   * - 새 Worker가 같은 host에서 등록하면 같은 index 물려받음
   */
  private def handleWorkerFailure(workerId: String): Unit = synchronized {
    logger.error(s"WORKER FAILURE DETECTED: $workerId")

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

    // Worker 정보 가져오기
    val workerInfo = registeredWorkers.get(workerId)
    val workerIndex = workerInfo.map(_.workerIndex).getOrElse(-1)
    val workerHost = workerInfo.map(_.host).getOrElse("unknown")

    // FAILED로 표시 (registry에서 제거하지 않음 - 재등록용)
    workerStatus.put(workerId, WorkerStatus.FAILED)

    // Heartbeat 추적 중단
    workerLastHeartbeat.remove(workerId)
    workerMissedHeartbeats.remove(workerId)

    // Alive workers 수 계산
    val aliveWorkers = registeredWorkers.count { case (id, _) =>
      workerStatus.getOrDefault(id, WorkerStatus.ALIVE) != WorkerStatus.FAILED
    }
    val failedWorkers = expectedWorkers - aliveWorkers

    // ⭐ CRASH 로그 출력 (콘솔 + 로거)
    System.err.println()
    System.err.println("=" * 60)
    System.err.println(s"[Master] ❌ WORKER CRASHED!")
    System.err.println(s"[Master]    Worker: $workerId (index $workerIndex)")
    System.err.println(s"[Master]    Host: $workerHost")
    System.err.println(s"[Master]    Phase: $currentWorkflowPhase")
    System.err.println(s"[Master]    Alive: $aliveWorkers/$expectedWorkers")
    System.err.println()
    System.err.println(s"[Master] ⏳ WAITING for recovery worker...")
    System.err.println(s"[Master]    Run on $workerHost: ./worker <master_ip>:<port> -I <input> -O <output>")
    System.err.println("=" * 60)
    System.err.println()

    logger.info(s"=" * 60)
    logger.info(s"WORKER $workerId (index $workerIndex) FAILED")
    logger.info(s"Host: $workerHost")
    logger.info(s"Current phase: $currentWorkflowPhase")
    logger.info(s"Alive workers: $aliveWorkers/$expectedWorkers, Failed: $failedWorkers")
    logger.info(s"")
    logger.info(s">>> WAITING for replacement worker on host $workerHost <<<")
    logger.info(s">>> Run: ./worker $workerHost:<master_port> -I <input_dirs> <<<")
    logger.info(s"=" * 60)

    // ⭐ 핵심: Latch를 조정하지 않음!
    // 새 Worker가 등록되어 phase를 완료해야만 진행됨
    // adjustLatchesForAliveWorkers() 호출하지 않음
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
      logger.info(s"Computing partition boundaries with ${collectedSamples.size()} samples from $expectedWorkers workers")
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
   * ⭐ FIXED: Derive assignments directly from shuffleMap for consistency
   *
   * The shuffleMap determines where data is sent during shuffle.
   * The partition assignments must match shuffleMap exactly, otherwise:
   * - Worker A sends partition X data to Worker B (per shuffleMap)
   * - But Worker C expects to create partition X output (per assignments)
   * - Result: partition X output is never created!
   *
   * This fix ensures workerPartitionAssignments is always consistent with shuffleMap.
   */
  private def getWorkerPartitionAssignments(workerIndex: Int): Seq[Int] = synchronized {
    val aliveWorkerIndices = getAliveWorkerIndices()

    // If no alive workers or worker not in alive list, return empty
    if (aliveWorkerIndices.isEmpty || !aliveWorkerIndices.contains(workerIndex)) {
      logger.warn(s"Worker $workerIndex is not alive or no alive workers exist")
      return Seq.empty
    }

    // ⭐ FIXED: Derive assignments directly from shuffleMap
    // This ensures consistency: whatever shuffleMap says goes to worker X,
    // worker X will create output files for those partitions.
    val assignments = shuffleMap
      .filter { case (_, targetWorker) => targetWorker == workerIndex }
      .keys
      .toSeq
      .sorted

    val aliveWorkerPosition = aliveWorkerIndices.indexOf(workerIndex)
    logger.info(s"Worker $workerIndex (position $aliveWorkerPosition/${aliveWorkerIndices.size} alive): " +
      s"partitions ${assignments.mkString(",")} (derived from shuffleMap)")

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

    // Update current workflow phase (for recovery workers)
    currentWorkflowPhase = phase

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

  /**
   * Shutdown the MasterService
   * Stops heartbeat monitoring and cleans up resources
   */
  def shutdown(): Unit = {
    logger.info("Shutting down MasterService...")

    // Stop heartbeat monitoring
    try {
      heartbeatCheckScheduler.shutdown()
      if (!heartbeatCheckScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
        heartbeatCheckScheduler.shutdownNow()
      }
      logger.info("Heartbeat scheduler stopped")
    } catch {
      case ex: Exception =>
        logger.warn(s"Error stopping heartbeat scheduler: ${ex.getMessage}")
        try { heartbeatCheckScheduler.shutdownNow() } catch { case _: Exception => }
    }

    logger.info("MasterService shutdown complete")
  }
}