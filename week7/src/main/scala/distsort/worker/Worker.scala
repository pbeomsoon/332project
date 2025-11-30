package distsort.worker

import distsort.core._
import distsort.proto.distsort._
import distsort.checkpoint.{CheckpointManager, WorkerState}
import distsort.shutdown.{GracefulShutdownManager, ShutdownAware, ShutdownConfig}
import scala.jdk.CollectionConverters._
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Server, ServerBuilder}
import com.google.protobuf.ByteString

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Await}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Worker node that performs distributed sorting operations.
 *
 * Responsibilities:
 * - Register with master
 * - Perform sampling on input data
 * - Sort local data
 * - Shuffle data to appropriate workers
 * - Merge received partitions
 */
class Worker(
  workerId: String,
  masterHost: String,
  masterPort: Int,
  workerPort: Int = 0, // 0 = auto-assign port
  inputDirs: Seq[String],
  outputDir: String
) extends LazyLogging with ShutdownAware {

  // Thread pool for async operations
  private val executorService = Executors.newFixedThreadPool(
    Runtime.getRuntime.availableProcessors() * 2
  )
  private implicit val executionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(executorService)

  // File management
  private val fileLayout = new FileLayout(inputDirs, outputDir, Some(s"/tmp/distsort/$workerId"))

  // Core components
  private val sampler = Sampler.forWorker(workerId)
  private val sorter = new ExternalSorter(fileLayout)
  @volatile private var partitioner: Option[Partitioner] = None
  @volatile private var shuffleManager: distsort.shuffle.ShuffleManager = _

  // Checkpoint manager for fault tolerance
  private val checkpointManager = CheckpointManager(workerId, s"/tmp/distsort/checkpoints")

  // Graceful shutdown manager
  private val shutdownManager = GracefulShutdownManager(
    ShutdownConfig(
      gracePeriod = 30.seconds,
      saveCheckpoint = true,
      waitForCurrentPhase = true
    )
  )
  @volatile private var isShuttingDown = false

  // gRPC components
  private val workerService = new WorkerService(workerId, outputDir)
  private var server: Server = _
  private var actualPort: Int = _
  private var myHost: String = "localhost"  // Will be detected when connecting to master
  private var masterChannel: ManagedChannel = _
  private var masterClient: MasterServiceGrpc.MasterServiceStub = _

  // Worker metadata
  private var workerIndex: Int = -1
  private var partitionBoundaries: Array[Array[Byte]] = _
  private var numPartitions: Int = _
  private var assignedPartitions: Map[Int, Int] = Map.empty
  private var assignedPartitionsList: Seq[Int] = Seq.empty
  private var workerConfiguration: Option[PartitionConfigResponse] = None
  private var shuffleMap: Map[Int, Int] = Map.empty

  // ⭐ Record-level distribution: assigned record ranges for this worker
  // Each record range specifies: (file, start_record, record_count)
  case class RecordRangeLocal(file: File, startRecord: Long, recordCount: Long)
  @volatile private var assignedRecordRanges: Seq[RecordRangeLocal] = Seq.empty
  @volatile private var fileAssignmentReceived: Boolean = false

  // Recovery state (Slide 13: Worker crash recovery)
  @volatile private var isRecovery: Boolean = false
  @volatile private var recoveryStartPhase: WorkerPhase = WorkerPhase.PHASE_INITIALIZING

  // Progress tracking for checkpointing
  private val processedRecordCount = new java.util.concurrent.atomic.AtomicLong(0)
  private val completedPartitions = new java.util.concurrent.ConcurrentHashMap[Int, Boolean]()
  private val currentPhase = new java.util.concurrent.atomic.AtomicReference[WorkerPhase](WorkerPhase.PHASE_INITIALIZING)

  // Workflow state flag - true after run() is called
  @volatile private var workflowStarted: Boolean = false

  // Heartbeat mechanism
  // ⭐ 빠른 crash 감지를 위해 주기 축소 (5초 → 2초)
  private val heartbeatInterval = 2.seconds  // Send heartbeat every 2 seconds
  private val heartbeatScheduler = Executors.newScheduledThreadPool(1)
  @volatile private var heartbeatRunning = false

  // Shutdown latch
  private val shutdownLatch = new CountDownLatch(1)

  /**
   * Start the worker node
   */
  def start(): Unit = {
    logger.info(s"Starting Worker $workerId")

    // Initialize file layout
    fileLayout.initialize()

    // Start gRPC server for receiving shuffle data
    server = ServerBuilder
      .forPort(workerPort)
      .addService(WorkerServiceGrpc.bindService(workerService, executionContext))
      .maxInboundMessageSize(100 * 1024 * 1024) // 100MB max message
      .build()

    Try {
      server.start()
      actualPort = server.getPort
      logger.info(s"Worker $workerId gRPC server started on port $actualPort")
    } match {
      case Success(_) =>
        // Register with shutdown manager
        shutdownManager.register(this)
        shutdownManager.registerShutdownHook()
        logger.info(s"Worker $workerId registered with graceful shutdown manager")

      case Failure(ex) =>
        logger.error(s"Failed to start Worker server: ${ex.getMessage}", ex)
        throw ex
    }

    // Connect to master
    connectToMaster()
  }

  /**
   * Connect to master and register
   */
  private def connectToMaster(): Unit = {
    logger.info(s"Connecting to Master at $masterHost:$masterPort")

    // Always use the provided master address for gRPC connection
    // (Don't convert to 127.0.0.1 even if local - we need consistent addressing)
    logger.info(s"Creating gRPC channel to $masterHost:$masterPort")

    masterChannel = ManagedChannelBuilder
      .forAddress(masterHost, masterPort)
      .usePlaintext()
      .maxInboundMessageSize(100 * 1024 * 1024)
      .build()

    masterClient = MasterServiceGrpc.stub(masterChannel)

    // Detect my IP address by connecting to master's ACTUAL address (not localhost)
    // This ensures we get the correct network interface IP for shuffle communication
    myHost = detectMyIPAddress(masterHost, masterPort)
    logger.info(s"Detected my IP address: $myHost")

    // Register with master with retry
    registerWithMaster()
  }

  /**
   * Detect this worker's IP address by connecting to the master.
   * This ensures we get the correct IP that can reach the master.
   */
  private def detectMyIPAddress(targetHost: String, targetPort: Int): String = {
    import java.net.{Socket, InetSocketAddress}
    try {
      val socket = new Socket()
      socket.connect(new InetSocketAddress(targetHost, targetPort), 5000)
      val myIP = socket.getLocalAddress.getHostAddress
      socket.close()
      logger.info(s"Detected local IP: $myIP (connected to $targetHost:$targetPort)")
      myIP
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to detect IP address, falling back to localhost: ${e.getMessage}")
        "localhost"
    }
  }

  /**
   * Calculate total input file size for dynamic partitioning
   */
  private def calculateTotalInputSize(): Long = {
    val inputFiles = fileLayout.getInputFiles
    val totalSize = inputFiles.map(_.length()).sum
    logger.info(s"Calculated total input size: ${totalSize / 1024 / 1024} MB from ${inputFiles.length} files")
    totalSize
  }

  /**
   * ⭐ Get record range assignment from Master
   * Called after registration but before sampling to get assigned record ranges.
   * This ensures each RECORD is processed by exactly one worker (no duplicates).
   */
  private def getFileAssignment(): Unit = {
    logger.info(s"Worker $workerId: Getting record range assignment from Master")

    val maxRetries = 30  // 30 retries × 1 second = 30 seconds max wait
    var retries = 0
    var success = false

    while (!success && retries < maxRetries) {
      try {
        val request = GetFileAssignmentRequest(workerId)
        val responseFuture = masterClient.getFileAssignment(request)
        val response = Await.result(responseFuture, 30.seconds)

        if (response.ready) {
          // ⭐ Parse record ranges (new format)
          if (response.recordRanges.nonEmpty) {
            assignedRecordRanges = response.recordRanges.map { range =>
              RecordRangeLocal(
                file = new File(range.filePath),
                startRecord = range.startRecord,
                recordCount = range.recordCount
              )
            }
            val totalRecords = assignedRecordRanges.map(_.recordCount).sum
            val uniqueFiles = assignedRecordRanges.map(_.file.getName).distinct
            logger.info(s"⭐ Worker $workerId assigned $totalRecords records from ${assignedRecordRanges.size} range(s)")
            System.err.println(s"[Worker-$workerIndex] ⭐ Assigned $totalRecords records from ${uniqueFiles.size} file(s)")
          } else {
            // Legacy fallback: use file paths if no record ranges
            assignedRecordRanges = response.assignedFiles.map { path =>
              val file = new File(path)
              RecordRangeLocal(file, 0, file.length() / 100)  // All records in file
            }
            logger.info(s"⭐ Worker $workerId assigned ${response.assignedFiles.size} files (legacy mode)")
            System.err.println(s"[Worker-$workerIndex] ⭐ Assigned ${response.assignedFiles.size} files (legacy)")
          }
          fileAssignmentReceived = true
          success = true
        } else {
          // Record distribution not done yet - wait and retry
          logger.info(s"Record distribution not ready, waiting... (${response.message})")
          Thread.sleep(1000)
          retries += 1
        }
      } catch {
        case ex: Exception =>
          logger.warn(s"Failed to get record assignment (attempt ${retries + 1}): ${ex.getMessage}")
          Thread.sleep(1000)
          retries += 1
      }
    }

    if (!success) {
      // Fallback: use all input files (for backward compatibility or single-worker mode)
      logger.warn(s"Could not get record assignment after $maxRetries attempts, using all input files")
      assignedRecordRanges = fileLayout.getInputFiles.map { file =>
        RecordRangeLocal(file, 0, file.length() / 100)  // All records
      }
      fileAssignmentReceived = true
      System.err.println(s"[Worker-$workerIndex] ⚠️ Using all input files (no distribution)")
    }
  }

  /**
   * Get assigned record ranges (for use in sampling/sorting)
   * Falls back to all input files if assignment not received
   */
  private def getAssignedRecordRanges: Seq[RecordRangeLocal] = {
    if (fileAssignmentReceived && assignedRecordRanges.nonEmpty) {
      assignedRecordRanges
    } else {
      // Fallback for backward compatibility - all records from all files
      fileLayout.getInputFiles.map { file =>
        RecordRangeLocal(file, 0, file.length() / 100)
      }
    }
  }

  /**
   * Get assigned input files (legacy - returns unique files from record ranges)
   */
  private def getAssignedInputFiles: Seq[File] = {
    getAssignedRecordRanges.map(_.file).distinct
  }

  /**
   * Register with master with exponential backoff retry
   */
  private def registerWithMaster(retries: Int = 5, delayMs: Long = 1000): Unit = {
    try {
      // Calculate input size for dynamic partitioning
      val totalInputBytes = calculateTotalInputSize()

      // ⭐ Collect input file info WITH sizes (for record-level distribution)
      val inputFiles = fileLayout.getInputFiles
      val inputFilePaths = inputFiles.map(_.getAbsolutePath)
      val inputFileInfos = inputFiles.map { file =>
        InputFileInfo(filePath = file.getAbsolutePath, fileSize = file.length())
      }
      logger.info(s"Reporting ${inputFilePaths.size} input files (${totalInputBytes / 1024 / 1024}MB) to Master for distribution")

      val registerRequest = RegisterWorkerRequest(
        workerId,
        myHost,  // Use detected IP address
        actualPort,
        Runtime.getRuntime.availableProcessors(),
        Runtime.getRuntime.maxMemory(),
        totalInputBytes,  // Send input size for dynamic partitioning
        inputFilePaths,   // Legacy: file paths only
        inputFileInfos    // ⭐ NEW: file paths WITH sizes for record-level distribution
      )

      val responseFuture = masterClient.registerWorker(registerRequest)
      val response = Await.result(responseFuture, 30.seconds)

      if (response.success) {
        workerIndex = response.assignedWorkerIndex
        isRecovery = response.isRecovery
        recoveryStartPhase = response.currentPhase

        if (isRecovery) {
          logger.info(s"Worker $workerId RECOVERED as index $workerIndex, starting from phase $recoveryStartPhase")
          System.err.println(s"[Worker-$workerIndex] RECOVERED - starting from phase $recoveryStartPhase")
        } else {
          logger.info(s"Worker $workerId registered successfully as index $workerIndex")
        }

        // Start heartbeat after successful registration
        startHeartbeat()
      } else {
        if (retries > 0) {
          logger.warn(s"Registration failed: ${response.message}, retrying in ${delayMs}ms")
          Thread.sleep(delayMs)
          registerWithMaster(retries - 1, delayMs * 2)
        } else {
          throw new RuntimeException(s"Failed to register after max retries: ${response.message}")
        }
      }
    } catch {
      case ex: io.grpc.StatusRuntimeException if retries > 0 =>
        ex.getStatus.getCode match {
          case io.grpc.Status.Code.UNAVAILABLE | io.grpc.Status.Code.DEADLINE_EXCEEDED =>
            logger.warn(s"Registration RPC failed (${ex.getStatus.getCode}), " +
              s"retrying in ${delayMs}ms ($retries retries left)")
            Thread.sleep(delayMs)
            registerWithMaster(retries - 1, delayMs * 2)
          case _ =>
            logger.error(s"Non-retryable registration error: ${ex.getStatus.getCode}")
            throw ex
        }
      case ex: java.util.concurrent.TimeoutException if retries > 0 =>
        logger.warn(s"Registration timeout, retrying in ${delayMs}ms ($retries retries left)")
        Thread.sleep(delayMs)
        registerWithMaster(retries - 1, delayMs * 2)
    }
  }

  /**
   * Start sending periodic heartbeats to master
   *
   * IMPORTANT: Initial delay of 5 seconds to allow:
   * 1. Registration to fully propagate in Master
   * 2. Workflow thread to start
   * 3. Avoid race condition where heartbeat is sent before run() starts
   */
  private def startHeartbeat(): Unit = {
    heartbeatRunning = true
    val initialDelaySeconds = 5L  // Wait before first heartbeat
    heartbeatScheduler.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = sendHeartbeat()
      },
      initialDelaySeconds,
      heartbeatInterval.toSeconds,
      TimeUnit.SECONDS
    )
    logger.info(s"Worker $workerId started heartbeat (first in ${initialDelaySeconds}s, then every ${heartbeatInterval.toSeconds}s)")
  }

  /**
   * Send heartbeat to master
   */
  private def sendHeartbeat(): Unit = {
    if (!heartbeatRunning) return

    try {
      val currentPhaseValue = workerService.getCurrentPhase
      val request = HeartbeatRequest(
        workerId = workerId,
        currentPhase = currentPhaseValue,
        progressPercentage = 0.0, // TODO: Implement actual progress tracking
        timestamp = System.currentTimeMillis()
      )

      val responseFuture = masterClient.heartbeat(request)
      val response = Await.result(responseFuture, 10.seconds)

      if (!response.acknowledged) {
        logger.warn(s"Heartbeat not acknowledged: ${response.message}")
        System.err.println(s"[Worker-$workerIndex] Heartbeat not acknowledged: ${response.message}")
      }

      if (response.shouldAbort) {
        // Only honor shutdown signal if workflow has completed (or was never started properly)
        // ⭐ FIX: Use workerService phase (consistent with heartbeat report to Master)
        val phaseValue = workerService.getCurrentPhase
        val isWorkflowDone = phaseValue == WorkerPhase.PHASE_COMPLETED

        if (!workflowStarted) {
          // Workflow hasn't started yet - ignore shutdown signal (likely stale Master state)
          logger.warn(s"Ignoring shutdown signal before workflow started: ${response.message}")
          System.err.println(s"[Worker-$workerIndex] Ignoring early shutdown (workflow not started): ${response.message}")
        } else if (isWorkflowDone) {
          // Workflow completed - honor shutdown signal
          logger.info(s"Received shutdown signal from Master (workflow done): ${response.message}")
          System.err.println(s"[Worker-$workerIndex] Shutdown signal (workflow done): ${response.message}")

          // Start a watchdog thread that will force exit after timeout
          val watchdog = new Thread(() => {
            Thread.sleep(15000) // 15 second absolute timeout
            System.err.println(s"[Worker-$workerIndex] Shutdown timeout - forcing exit")
            Runtime.getRuntime.halt(1) // Skip shutdown hooks, immediate termination
          }, "shutdown-watchdog")
          watchdog.setDaemon(true)
          watchdog.start()

          stop()
        } else {
          // Workflow in progress - log but don't shutdown
          logger.warn(s"Shutdown signal while workflow in progress (phase=$phaseValue): ${response.message}")
          System.err.println(s"[Worker-$workerIndex] Warning: shutdown signal at phase $phaseValue (ignoring)")
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to send heartbeat: ${ex.getMessage}")
    }
  }

  /**
   * Run the worker workflow
   */
  def run(): Unit = {
    try {
      workflowStarted = true  // Mark workflow as started
      logger.info(s"Worker $workerId starting workflow")
      System.err.println(s"[Worker-$workerIndex] Workflow starting (workflowStarted=$workflowStarted, phase=${currentPhase.get()})")

      // Clean up old checkpoints from previous test runs (older than 10 seconds)
      // This prevents interference from stale checkpoints when tests restart quickly
      // Real fault tolerance scenarios (worker crash/restart) happen within seconds,
      // so 10s is safe while still cleaning up test artifacts
      checkpointManager.deleteOldCheckpoints(maxAgeSeconds = 10)

      // ⭐ Phase 2: Try to recover from checkpoint and verify worker index
      val (recoveredFromCheckpoint, checkpointWorkerIndex) = recoverFromCheckpoint()

      // Verify if this is a re-registration (same workerIndex)
      val isReRegistration = checkpointWorkerIndex match {
        case Some(savedIndex) if savedIndex == workerIndex =>
          logger.info(s"WORKER RE-REGISTRATION: workerIndex matches ($workerIndex) - continuing from checkpoint")
          true
        case Some(savedIndex) =>
          logger.warn(s"DIFFERENT WORKER INDEX: checkpoint has $savedIndex but master assigned $workerIndex")
          logger.warn(s"This is a FRESH START - discarding checkpoint and starting from beginning")
          logger.info(s"Deleting stale checkpoints from previous run...")
          checkpointManager.deleteAllCheckpoints()
          false
        case None =>
          logger.info(s"No checkpoint found - starting fresh")
          false
      }

      // ⭐ Get file assignment from Master before any processing
      // This ensures each file is processed by exactly one worker (no duplicates)
      // For recovery workers, they get the crashed worker's assigned files
      getFileAssignment()

      // ⭐ RECOVERY LOGIC (Slide 13: Worker crash recovery)
      // Recovery worker behavior depends on which phase Master is currently in:
      // - SAMPLING: Must do sampling (boundaries not yet computed)
      // - After SAMPLING: Skip sampling (boundaries already computed), do remaining phases
      if (isRecovery) {
        logger.info(s"RECOVERY MODE: Master is at phase $recoveryStartPhase")
        System.err.println(s"[Worker-$workerIndex] RECOVERY: Master at phase $recoveryStartPhase")

        if (recoveryStartPhase == WorkerPhase.PHASE_SAMPLING ||
            recoveryStartPhase == WorkerPhase.PHASE_INITIALIZING) {
          // Crash during SAMPLING - must do sampling (boundaries not computed yet)
          logger.info("RECOVERY: Crash during SAMPLING - performing sampling")
          performSampling()
          currentPhase.set(WorkerPhase.PHASE_SAMPLING)
        } else {
          // Crash after SAMPLING - skip sampling (boundaries already computed)
          logger.info(s"RECOVERY: Skipping sampling, starting from $recoveryStartPhase")
          currentPhase.set(recoveryStartPhase)
        }

      } else if (!recoveredFromCheckpoint || currentPhase.get() == WorkerPhase.PHASE_INITIALIZING || !isReRegistration) {
        // Fresh start: Phase 1 - Sampling
        performSampling()
        currentPhase.set(WorkerPhase.PHASE_SAMPLING)  // Sync Worker.currentPhase with WorkerService
      } else {
        logger.info(s"RECOVERING FROM CHECKPOINT at phase ${currentPhase.get()}")
      }

      // ⭐ RECOVERY: If recovering, we need to do all phases from partition config onwards
      // because intermediate data was lost. Only sampling is skipped (boundaries already computed).
      val needsFullRecovery = isRecovery

      if (needsFullRecovery ||
          currentPhase.get() == WorkerPhase.PHASE_SAMPLING ||
          currentPhase.get() == WorkerPhase.PHASE_WAITING_FOR_PARTITIONS ||
          currentPhase.get() == WorkerPhase.PHASE_INITIALIZING) {
        // Phase 2: Get partition configuration
        getPartitionConfiguration()
        currentPhase.set(WorkerPhase.PHASE_WAITING_FOR_PARTITIONS)  // Sync Worker.currentPhase
        savePhaseCheckpoint(WorkerPhase.PHASE_WAITING_FOR_PARTITIONS, 1.0)
      }

      if (needsFullRecovery ||
          currentPhase.get() == WorkerPhase.PHASE_WAITING_FOR_PARTITIONS ||
          currentPhase.get() == WorkerPhase.PHASE_SORTING) {
        // Phase 3: Local sort
        val sortedChunks = performLocalSort()
        currentPhase.set(WorkerPhase.PHASE_SORTING)  // Sync Worker.currentPhase
        savePhaseCheckpoint(WorkerPhase.PHASE_SORTING, 1.0)

        // Phase 4: Shuffle
        performShuffle(sortedChunks)
        currentPhase.set(WorkerPhase.PHASE_SHUFFLING)  // Sync Worker.currentPhase
        savePhaseCheckpoint(WorkerPhase.PHASE_SHUFFLING, 1.0)
      } else if (currentPhase.get() == WorkerPhase.PHASE_SHUFFLING) {
        // Continue from shuffle if recovered from checkpoint
        logger.info("Recovering from PHASE_SHUFFLING - checking for sorted chunks")

        // Try to reuse existing sorted chunks
        val sortedChunks = fileLayout.listSortedChunks

        if (sortedChunks.nonEmpty) {
          // Sorted chunks available - reuse them
          logger.info(s"Found ${sortedChunks.size} sorted chunks, reusing for shuffle")
          performShuffle(sortedChunks)
        } else {
          // Sorted chunks lost - recompute
          logger.warn("Sorted chunks not found, re-sorting from input files")
          val newSortedChunks = performLocalSort()
          performShuffle(newSortedChunks)
        }

        currentPhase.set(WorkerPhase.PHASE_SHUFFLING)  // Sync Worker.currentPhase
        savePhaseCheckpoint(WorkerPhase.PHASE_SHUFFLING, 1.0)
      }

      if (needsFullRecovery ||
          currentPhase.get() == WorkerPhase.PHASE_SHUFFLING ||
          currentPhase.get() == WorkerPhase.PHASE_MERGING) {
        // Phase 5: Merge
        performMerge()
        currentPhase.set(WorkerPhase.PHASE_MERGING)  // Sync Worker.currentPhase
        savePhaseCheckpoint(WorkerPhase.PHASE_MERGING, 1.0)
      }

      // Mark workflow as completed
      currentPhase.set(WorkerPhase.PHASE_COMPLETED)

      logger.info(s"Worker $workerId completed workflow successfully")
      logger.info(s"Worker $workerId waiting for shutdown signal from Master via heartbeat...")

      // Clean up checkpoints on successful completion
      checkpointManager.deleteAllCheckpoints()

      // DO NOT auto-shutdown - wait for Master to signal shutdown via heartbeat
      // The heartbeat will receive shouldAbort=true when Master completes workflow

    } catch {
      case ex: Exception =>
        logger.error(s"Worker $workerId workflow failed: ${ex.getMessage}", ex)
        reportError(ex)
        throw ex
    }
  }

  /**
   * Phase 1: Perform sampling on input data
   */
  private def performSampling(): Unit = {
    logger.info(s"Worker $workerId: Starting sampling phase")
    System.err.println(s"[Worker-$workerIndex] Phase 1/4: Sampling...")
    workerService.setPhase(WorkerPhase.PHASE_SAMPLING)

    // ⭐ Use assigned RECORD RANGES (distributed by Master) for sampling
    val recordRanges = getAssignedRecordRanges
    val totalRecords = recordRanges.map(_.recordCount).sum
    logger.info(s"Sampling from $totalRecords assigned records in ${recordRanges.size} range(s)")

    // Extract samples from each record range
    val allSamples = recordRanges.flatMap { range =>
      sampler.extractSamplesFromRange(range.file, range.startRecord, range.recordCount)
    }

    logger.info(s"Extracted ${allSamples.length} samples")

    // Send samples to master
    val sampleKeys = allSamples.map(_.key).map(ByteString.copyFrom)
    val request = SampleKeysRequest(
      workerId,
      sampleKeys,
      allSamples.length * 10 // Assuming 10% sampling
    )

    val responseFuture = masterClient.submitSampleKeys(request)
    val response = Await.result(responseFuture, 30.seconds)

    if (response.acknowledged) {
      logger.info(s"Samples sent to master successfully")
    } else {
      throw new RuntimeException(s"Master did not acknowledge samples - Worker may be connected to wrong Master or Master restarted. " +
        s"WorkerId: $workerId, Master: $masterHost:$masterPort")
    }

    // Save checkpoint after sampling phase
    savePhaseCheckpoint(WorkerPhase.PHASE_SAMPLING, 1.0)

    // Report phase complete
    System.err.println(s"[Worker-$workerIndex] Sampling complete")
    reportPhaseComplete(WorkerPhase.PHASE_SAMPLING)
  }

  /**
   * Phase 2: Get partition configuration from master
   */
  private def getPartitionConfiguration(): Unit = {
    logger.info(s"Worker $workerId: Getting partition configuration")
    workerService.setPhase(WorkerPhase.PHASE_WAITING_FOR_PARTITIONS)

    val request = PartitionConfigRequest(workerId)
    val responseFuture = masterClient.getPartitionConfig(request)
    val response = Await.result(responseFuture, 60.seconds)

    // Store configuration
    workerConfiguration = Some(response)
    partitionBoundaries = response.boundaries.map(_.toByteArray).toArray
    numPartitions = response.numPartitions

    // Store shuffle map from response
    shuffleMap = response.shuffleMap.toMap
    logger.info(s"Received shuffle map with ${shuffleMap.size} partition mappings")

    // Build assignedPartitions map from response
    assignedPartitionsList = response.workerPartitionAssignments
    assignedPartitions = assignedPartitionsList.map(pid => pid -> workerIndex).toMap

    partitioner = Some(new Partitioner(partitionBoundaries.toSeq, numPartitions))

    logger.info(s"Received ${partitionBoundaries.length} boundaries, " +
      s"assigned partitions: ${assignedPartitionsList.mkString(",")}, " +
      s"shuffle map size: ${shuffleMap.size}")

    // Setup shuffle data request handler for MERGING crash recovery support
    setupShuffleDataRequestHandler()
  }

  /**
   * Setup handler for shuffle data requests from recovery workers.
   * When a recovery worker joins during MERGING phase, it needs shuffle data
   * from other workers who have already completed SHUFFLING.
   */
  private def setupShuffleDataRequestHandler(): Unit = {
    workerService.setShuffleDataRequestHandler { request =>
      logger.info(s"Handling shuffle data request for partitions: ${request.partitionIds.mkString(", ")}")

      val requestedPartitions = request.partitionIds.toSet
      val requesterIndex = request.requesterWorkerIndex

      // Get requester's worker info for sending data
      val requesterInfo = workerConfiguration.flatMap { config =>
        config.allWorkers.find(_.workerIndex == requesterIndex)
      }.getOrElse {
        throw new RuntimeException(s"Cannot find worker info for index $requesterIndex")
      }

      // ⭐ Read ASSIGNED RECORD RANGES (distributed by Master) and partition records
      val recordRanges = getAssignedRecordRanges
      val thePartitioner = partitioner.getOrElse {
        throw new RuntimeException("Partitioner not initialized")
      }

      var totalRecordsSent = 0

      // Create gRPC client to send data to requester
      import scala.concurrent.ExecutionContext.Implicits.global
      val requesterClient = new distsort.shuffle.GrpcWorkerClient(
        requesterInfo.host, requesterInfo.port
      )

      try {
        // ⭐ Read only assigned record ranges (not entire files)
        recordRanges.foreach { range =>
          import java.io.RandomAccessFile
          val RECORD_SIZE = 100
          val raf = new RandomAccessFile(range.file, "r")
          val records = scala.collection.mutable.ArrayBuffer[distsort.core.Record]()
          try {
            raf.seek(range.startRecord * RECORD_SIZE)
            val buffer = new Array[Byte](RECORD_SIZE)
            var recordsRead = 0L
            while (recordsRead < range.recordCount && raf.getFilePointer < raf.length()) {
              val bytesRead = raf.read(buffer)
              if (bytesRead == RECORD_SIZE) {
                val key = buffer.slice(0, 10).clone()
                val value = buffer.slice(10, 100).clone()
                records += distsort.core.Record(key, value)
                recordsRead += 1
              }
            }
          } finally {
            raf.close()
          }

          // Partition records
          val partitionedRecords = thePartitioner.partitionRecords(records.toSeq)

          // Send only requested partitions
          partitionedRecords.foreach { case (partitionId, partRecords) =>
            if (requestedPartitions.contains(partitionId) && partRecords.nonEmpty) {
              logger.info(s"Sending ${partRecords.size} records for partition $partitionId to recovery worker")

              val sendResult = Await.result(
                requesterClient.sendPartition(partitionId, partRecords.iterator),
                60.seconds
              )

              if (sendResult) {
                totalRecordsSent += partRecords.size
              } else {
                logger.warn(s"Failed to send partition $partitionId to recovery worker")
              }
            }
          }
        }

        logger.info(s"Completed sending $totalRecordsSent records to recovery worker ${request.requesterWorkerId}")
        totalRecordsSent
      } finally {
        requesterClient.close()
      }
    }
    logger.info("Shuffle data request handler setup complete")
  }

  /**
   * Request shuffle data from other workers (for MERGING crash recovery).
   * When recovery worker joins during MERGING, other workers have already completed shuffle.
   * This method explicitly requests them to re-send the partition data.
   */
  private def requestShuffleDataFromOtherWorkers(): Unit = {
    val myPartitions = assignedPartitionsList.toSeq
    logger.info(s"Requesting shuffle data for partitions: ${myPartitions.mkString(", ")}")

    val otherWorkers = workerConfiguration.map(_.allWorkers.filter(_.workerIndex != workerIndex)).getOrElse(Seq.empty)

    if (otherWorkers.isEmpty) {
      logger.warn("No other workers found to request shuffle data from")
      return
    }

    logger.info(s"Requesting shuffle data from ${otherWorkers.size} other workers")

    otherWorkers.foreach { otherWorker =>
      try {
        logger.info(s"Requesting shuffle data from Worker ${otherWorker.workerIndex} at ${otherWorker.host}:${otherWorker.port}")

        // Create gRPC channel to other worker
        val channel = ManagedChannelBuilder
          .forAddress(otherWorker.host, otherWorker.port)
          .usePlaintext()
          .maxInboundMessageSize(100 * 1024 * 1024)
          .build()

        try {
          val workerClient = WorkerServiceGrpc.blockingStub(channel)

          val request = RequestShuffleDataRequest(
            requesterWorkerId = workerId,
            requesterWorkerIndex = workerIndex,
            partitionIds = myPartitions
          )

          val response = workerClient.requestShuffleData(request)

          if (response.success) {
            logger.info(s"Successfully received ${response.recordsSent} records from Worker ${otherWorker.workerIndex}")
          } else {
            logger.warn(s"Failed to get shuffle data from Worker ${otherWorker.workerIndex}: ${response.message}")
          }
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
      } catch {
        case ex: Exception =>
          logger.error(s"Error requesting shuffle data from Worker ${otherWorker.workerIndex}: ${ex.getMessage}", ex)
      }
    }

    logger.info("Completed requesting shuffle data from other workers")
  }

  /**
   * Phase 3: Perform local sort on input data
   * ⭐ Uses record-level distribution: reads only assigned record ranges
   */
  private def performLocalSort(): Seq[File] = {
    logger.info(s"Worker $workerId: Starting local sort phase")
    System.err.println(s"[Worker-$workerIndex] Phase 2/4: Sorting...")
    workerService.setPhase(WorkerPhase.PHASE_SORTING)

    // ⭐ Use assigned RECORD RANGES (distributed by Master) for sorting
    val recordRanges = getAssignedRecordRanges
    val totalRecords = recordRanges.map(_.recordCount).sum
    logger.info(s"Sorting $totalRecords assigned records from ${recordRanges.size} range(s)")

    // Convert to tuple format for sorter
    val ranges = recordRanges.map(r => (r.file, r.startRecord, r.recordCount))
    val sortedChunks = sorter.sortRecordRanges(ranges)

    logger.info(s"Sorted $totalRecords records into ${sortedChunks.length} chunks")

    System.err.println(s"[Worker-$workerIndex] Sorting complete (${sortedChunks.length} chunks)")
    reportPhaseComplete(WorkerPhase.PHASE_SORTING)
    sortedChunks
  }

  /**
   * Phase 4: Shuffle data to appropriate workers
   */
  private def performShuffle(sortedChunks: Seq[File]): Unit = {
    logger.info(s"Worker $workerId: Starting shuffle phase")
    System.err.println(s"[Worker-$workerIndex] Phase 3/4: Shuffling...")
    workerService.setPhase(WorkerPhase.PHASE_SHUFFLING)

    // ⭐ FIX: Refresh partition configuration before shuffle
    // This ensures we have the latest shuffleMap after any worker failures
    logger.info(s"Worker $workerId: Refreshing partition configuration before shuffle")
    try {
      val request = PartitionConfigRequest(workerId)
      val responseFuture = masterClient.getPartitionConfig(request)
      val response = Await.result(responseFuture, 30.seconds)

      // Update configuration
      workerConfiguration = Some(response)
      shuffleMap = response.shuffleMap.toMap
      logger.info(s"Refreshed shuffle map with ${shuffleMap.size} partition mappings")
    } catch {
      case ex: Exception =>
        logger.warn(s"Failed to refresh partition config, using cached config: ${ex.getMessage}")
        // Continue with cached config
    }

    // Callback to refresh worker clients from Master (for fault tolerance)
    // When a worker crashes and recovers, this callback fetches updated endpoint info
    val refreshClientsCallback: () => Map[Int, distsort.shuffle.WorkerClient] = () => {
      try {
        logger.info("Refreshing worker clients from Master (recovery worker may have joined)...")
        val request = PartitionConfigRequest(workerId)
        val responseFuture = masterClient.getPartitionConfig(request)
        val response = Await.result(responseFuture, 30.seconds)

        // Update local configuration
        workerConfiguration = Some(response)
        shuffleMap = response.shuffleMap.toMap

        // Create new clients with updated worker info
        val workers = response.allWorkers
        val newClients = distsort.shuffle.GrpcWorkerClient.createClients(workers, workerIndex)
        logger.info(s"Refreshed clients for workers: ${workers.map(w => s"${w.workerIndex}@${w.host}:${w.port}").mkString(", ")}")
        newClients
      } catch {
        case ex: Exception =>
          logger.warn(s"Failed to refresh worker clients: ${ex.getMessage}")
          Map.empty[Int, distsort.shuffle.WorkerClient]
      }
    }

    // Create ShuffleManager with partitioner and refresh callback
    import scala.concurrent.ExecutionContext.Implicits.global
    shuffleManager = new distsort.shuffle.ShuffleManager(
      workerId = workerId,
      workerIndex = workerIndex,
      fileLayout = fileLayout,
      partitioner = partitioner.getOrElse(
        throw new IllegalStateException("Partitioner not initialized")
      ),
      refreshClientsCallback = Some(refreshClientsCallback)  // 복구 Worker 정보 갱신용
    )

    // Get worker information from configuration
    val workersInfo = workerConfiguration.map { config =>
      val workers = config.allWorkers
      val workerClients = distsort.shuffle.GrpcWorkerClient.createClients(workers, workerIndex)
      shuffleManager.setWorkerClients(workerClients)
      workers
    }

    // Log which partitions this worker is responsible for
    val myPartitions = shuffleMap.filter(_._2 == workerIndex).keys.toSet
    logger.info(s"Worker $workerId is responsible for receiving partitions: ${myPartitions.mkString(", ")}")
    logger.info(s"Worker $workerId will use full shuffleMap with ${shuffleMap.size} mappings for shuffle distribution")

    // Perform the actual shuffle using full shuffleMap
    // (Full map is needed to determine where to send each partition to other workers)
    // Timeout increased to 15 minutes to allow for worker recovery (10 min retry + buffer)
    import scala.concurrent.duration._
    val shuffleFuture = shuffleManager.shuffleToWorkers(
      sortedChunks = sortedChunks,
      assignedPartitions = shuffleMap  // Full map needed for distribution logic
    )

    // ⭐ MERGING Recovery: If recovering from MERGING crash, request shuffle data from other workers
    // Other workers have already completed shuffle and won't send data automatically
    if (isRecovery && recoveryStartPhase == WorkerPhase.PHASE_MERGING) {
      logger.info("MERGING RECOVERY: Requesting shuffle data from other workers")
      System.err.println(s"[Worker-$workerIndex] MERGING RECOVERY: Requesting shuffle data from other workers...")
      requestShuffleDataFromOtherWorkers()
    }

    // Wait for shuffle to complete (15 minutes to allow recovery worker join)
    Try(Await.result(shuffleFuture, 15.minutes)) match {
      case Success(_) =>
        logger.info(s"Worker $workerId completed shuffle phase successfully")
      case Failure(ex) =>
        logger.error(s"Worker $workerId failed during shuffle phase", ex)
        throw ex
    }

    System.err.println(s"[Worker-$workerIndex] Shuffling complete")
    reportPhaseComplete(WorkerPhase.PHASE_SHUFFLING)
  }

  /**
   * Phase 5: Merge received partitions
   */
  private def performMerge(): Unit = {
    logger.info(s"Worker $workerId: Starting merge phase")
    System.err.println(s"[Worker-$workerIndex] Phase 4/4: Merging...")
    workerService.setPhase(WorkerPhase.PHASE_MERGING)

    // Get received files from shuffle
    val receivedFiles = workerService.getReceivedFiles

    // Also include local partition files for assigned partitions
    val localPartitionFiles = assignedPartitionsList.map { partitionId =>
      fileLayout.getLocalPartitionFile(partitionId)
    }.filter(_.exists())

    val allFiles = receivedFiles ++ localPartitionFiles
    logger.info(s"Merging ${allFiles.length} files (${receivedFiles.length} received, " +
      s"${localPartitionFiles.length} local)")

    if (allFiles.nonEmpty) {
      // Merge using KWayMerger
      assignedPartitionsList.foreach { partitionId =>
        // Filter files for this partition
        // ⭐ FIX: Match both local files and received files (with unique sender IDs)
        // Local: local-partition.0
        // Received: partition-0-abc12345.received (unique per sender)
        val partitionFiles = allFiles.filter { file =>
          val name = file.getName
          name.contains(s".$partitionId") ||                     // local-partition.0
          name.matches(s"partition-$partitionId-.*\\.received")  // partition-0-UUID.received
        }

        if (partitionFiles.nonEmpty) {
          val outputFile = fileLayout.getOutputPartitionFile(partitionId)
          val merger = new KWayMerger(partitionFiles)
          val writer = RecordWriter.create(outputFile, DataFormat.Binary)

          merger.mergeWithCallback { record =>
            writer.writeRecord(record)
          }

          writer.close()
          logger.info(s"Created output partition: ${outputFile.getAbsolutePath}")
        }
      }
    }

    System.err.println(s"[Worker-$workerIndex] Merging complete")
    reportPhaseComplete(WorkerPhase.PHASE_MERGING)
    workerService.setPhase(WorkerPhase.PHASE_COMPLETED)
    System.err.println(s"[Worker-$workerIndex] All phases complete!")
  }

  /**
   * Report phase completion to master with retry
   *
   * Optimized: retries=7, delay=500ms to balance worker sync and test timeout
   * Max wait time: 0.5 + 1 + 2 + 4 + 8 + 16 + 32 = 63.5 seconds
   * - Allows slow workers to catch up (prevents "Max retries exceeded")
   * - Stays within test timeout (120 seconds)
   * - Previous: retries=10, delay=1000ms → 1023s (too long, causes timeout)
   */
  private def reportPhaseComplete(phase: WorkerPhase): Unit = {
    // ⭐ FIX: Increased retries for fault tolerance
    // Recovery worker needs time to: register → sort → shuffle → complete
    // Old: 7 retries × exponential backoff = ~63 seconds (too short for recovery)
    // New: 15 retries × 5 second fixed interval = ~75 seconds for recovery
    // Plus exponential backoff for initial attempts
    reportPhaseCompleteWithRetry(phase, retries = 15, delayMs = 5000)
  }

  /**
   * Report phase completion with fixed interval retry
   * ⭐ Changed from exponential backoff to fixed interval for fault tolerance
   * - Allows sufficient time for recovery workers to complete their work
   */
  private def reportPhaseCompleteWithRetry(
    phase: WorkerPhase,
    retries: Int,
    delayMs: Long
  ): Unit = {
    try {
      val request = PhaseCompleteRequest(
        workerId,
        phase,
        s"Worker $workerId completed $phase"
      )

      val responseFuture = masterClient.reportPhaseComplete(request)
      val response = Await.result(responseFuture, 30.seconds)

      if (response.proceedToNext) {
        logger.info(s"Master acknowledged phase completion, next phase: ${response.nextPhase}")
      } else {
        if (retries > 0) {
          logger.info(s"Waiting for other workers: ${response.message} (retrying in ${delayMs}ms, $retries left)")
          Thread.sleep(delayMs)
          // ⭐ FIX: Use fixed interval instead of exponential backoff
          // This gives recovery workers more time to complete their work
          reportPhaseCompleteWithRetry(phase, retries - 1, delayMs) // Fixed interval
        } else {
          throw new RuntimeException(s"Max retries exceeded waiting for phase $phase")
        }
      }
    } catch {
      case ex: io.grpc.StatusRuntimeException if retries > 0 =>
        ex.getStatus.getCode match {
          case io.grpc.Status.Code.UNAVAILABLE | io.grpc.Status.Code.DEADLINE_EXCEEDED =>
            logger.warn(s"RPC failed (${ex.getStatus.getCode}), retrying in ${delayMs}ms " +
              s"($retries retries left)")
            Thread.sleep(delayMs)
            reportPhaseCompleteWithRetry(phase, retries - 1, delayMs)
          case _ =>
            logger.error(s"Non-retryable RPC error: ${ex.getStatus.getCode}")
            throw ex
        }
      case ex: io.grpc.StatusRuntimeException if retries == 0 && phase == WorkerPhase.PHASE_MERGING =>
        // ⭐ FIX: For MERGING phase, if Master is unavailable after retries,
        // assume Master has gracefully shut down (workflow complete)
        logger.warn(s"Master unavailable after retries for $phase - assuming workflow completed successfully")
        System.err.println(s"[Worker-$workerIndex] Master unavailable - workflow likely completed")
        // Don't throw - treat as success since merging work is done
      case ex: java.util.concurrent.TimeoutException if retries > 0 =>
        logger.warn(s"RPC timeout, retrying in ${delayMs}ms ($retries retries left)")
        Thread.sleep(delayMs)
        reportPhaseCompleteWithRetry(phase, retries - 1, delayMs)
    }
  }

  /**
   * Report error to master
   */
  private def reportError(error: Throwable): Unit = {
    val request = ReportErrorRequest(
      workerId,
      error.getClass.getSimpleName,
      error.getMessage,
      workerService.getCurrentPhase
    )

    val responseFuture = masterClient.reportError(request)
    val response = Await.result(responseFuture, 10.seconds)

    logger.info(s"Master error response: retry=${response.shouldRetry}, " +
      s"abort=${response.shouldAbort}, instructions=${response.instructions}")
  }

  /**
   * Save checkpoint after phase completion
   * @param phase Current phase
   * @param progress Completion progress (0.0 to 1.0)
   */
  private def savePhaseCheckpoint(phase: WorkerPhase, progress: Double): Unit = {
    try {
      val state = WorkerState.fromScala(
        workerIndex = workerIndex,  // ⭐ Phase 2: Save workerIndex for re-registration verification
        processedRecords = processedRecordCount.get(),
        partitionBoundaries = partitionBoundaries.toSeq,
        shuffleMap = shuffleMap,
        completedPartitions = completedPartitions.keys().asScala.map(_.toInt).toSet,
        currentFiles = fileLayout.getInputFiles.map(_.getAbsolutePath),
        phaseMetadata = Map(
          "phase" -> phase.toString,
          "workerId" -> workerId
        )
      )

      val checkpointFuture = checkpointManager.saveCheckpoint(phase, state, progress)
      val checkpointId = Await.result(checkpointFuture, 10.seconds)
      logger.info(s"Saved checkpoint $checkpointId for phase $phase")

      // Clean old checkpoints to save space
      checkpointManager.cleanOldCheckpoints(3)
    } catch {
      case ex: Exception =>
        logger.warn(s"Failed to save checkpoint: ${ex.getMessage}")
        // Continue execution even if checkpoint fails
    }
  }

  /**
   * Recover from checkpoint on restart
   * @return (recovered: Boolean, checkpointWorkerIndex: Option[Int])
   */
  private def recoverFromCheckpoint(): (Boolean, Option[Int]) = {
    checkpointManager.loadLatestCheckpoint() match {
      case Some(checkpoint) =>
        logger.info(s"Recovering from checkpoint ${checkpoint.id} at phase ${checkpoint.phase}")

        // ⭐ Phase 2: Restore state including workerIndex
        val (checkpointWorkerIndex, processedRecords, boundaries, shuffle, completed, files, metadata) = WorkerState.toScala(checkpoint.state)

        logger.info(s"Checkpoint contains workerIndex=$checkpointWorkerIndex (current workerIndex=$workerIndex)")

        processedRecordCount.set(processedRecords)
        partitionBoundaries = boundaries.toArray
        shuffleMap = shuffle
        completedPartitions.clear()
        completed.foreach(p => completedPartitions.put(p, true))

        // Parse phase from string
        val phase = WorkerPhase.fromName(checkpoint.phase).getOrElse(WorkerPhase.PHASE_INITIALIZING)

        // Set phase
        workerService.setPhase(phase)
        currentPhase.set(phase)

        logger.info(s"Recovered from checkpoint: phase=${checkpoint.phase}, " +
          s"workerIndex=$checkpointWorkerIndex, " +
          s"processedRecords=${checkpoint.state.processedRecords}, " +
          s"completedPartitions=${checkpoint.state.completedPartitions.size}")

        (true, Some(checkpointWorkerIndex))  // ⭐ Return checkpoint workerIndex

      case None =>
        logger.info("No valid checkpoint found, starting fresh")
        (false, None)
    }
  }

  /**
   * Get current worker state
   * @return Current WorkerState
   */
  private def getCurrentState(): WorkerState = {
    WorkerState.fromScala(
      workerIndex = workerIndex,  // ⭐ Phase 2: Include workerIndex
      processedRecords = processedRecordCount.get(),
      partitionBoundaries = partitionBoundaries.toSeq,
      shuffleMap = shuffleMap,
      completedPartitions = completedPartitions.keys().asScala.map(_.toInt).toSet,
      currentFiles = fileLayout.getInputFiles.map(_.getAbsolutePath),
      phaseMetadata = Map(
        "phase" -> currentPhase.get().toString,
        "workerId" -> workerId
      )
    )
  }

  /**
   * Get current phase
   */
  def getCurrentPhase: WorkerPhase = {
    workerService.getCurrentPhase
  }

  /**
   * Get actual port
   */
  def getPort: Int = {
    actualPort
  }

  /**
   * Wait for termination
   */
  def awaitTermination(): Unit = {
    logger.info(s"Worker $workerId waiting for termination signal...")
    shutdownLatch.await()
  }

  // ===== ShutdownAware trait implementation =====

  /**
   * Initiate graceful shutdown
   * @return Future that completes when shutdown is done
   */
  override def gracefulShutdown(): Future[Unit] = {
    logger.info(s"Graceful shutdown initiated for Worker $workerId")
    isShuttingDown = true

    Future {
      try {
        // 1. Stop accepting new work (stop heartbeat)
        heartbeatRunning = false
        logger.info(s"Worker $workerId: Stopped accepting new work")

        // 2. Wait for current phase to complete if enabled
        if (currentPhase.get() != WorkerPhase.PHASE_UNKNOWN &&
            currentPhase.get() != WorkerPhase.PHASE_COMPLETED) {
          logger.info(s"Worker $workerId: Waiting for current phase ${currentPhase.get()} to complete")

          // Save checkpoint before shutdown
          try {
            savePhaseCheckpoint(currentPhase.get(), 1.0)
            logger.info(s"Worker $workerId: Checkpoint saved before shutdown")
          } catch {
            case ex: Exception =>
              logger.warn(s"Failed to save checkpoint during shutdown: ${ex.getMessage}")
          }
        }

        // 3. Cleanup resources
        logger.info(s"Worker $workerId: Cleaning up resources")
        stop()

        logger.info(s"Worker $workerId: Graceful shutdown completed")
      } catch {
        case ex: Exception =>
          logger.error(s"Error during graceful shutdown: ${ex.getMessage}", ex)
          throw ex
      }
    }
  }

  /**
   * Check if worker is ready for shutdown
   * @return true if no active phase is running
   */
  override def isReadyForShutdown: Boolean = {
    val phase = currentPhase.get()
    val ready = phase == WorkerPhase.PHASE_UNKNOWN ||
                phase == WorkerPhase.PHASE_INITIALIZING ||
                phase == WorkerPhase.PHASE_COMPLETED ||
                phase == WorkerPhase.PHASE_WAITING_FOR_PARTITIONS

    if (!ready) {
      logger.debug(s"Worker $workerId not ready for shutdown, current phase: $phase")
    }

    ready
  }

  /**
   * Force immediate shutdown
   */
  override def forceShutdown(): Unit = {
    logger.warn(s"Force shutdown for Worker $workerId")
    isShuttingDown = true

    // Start a watchdog thread to force exit if stop() hangs
    val watchdog = new Thread(() => {
      Thread.sleep(5000) // 5 seconds max for force shutdown
      System.err.println(s"[Worker-$workerIndex] Force shutdown timeout - halting immediately")
      Runtime.getRuntime.halt(1)
    }, "worker-force-shutdown-watchdog")
    watchdog.setDaemon(true)
    watchdog.start()

    // Try to save checkpoint even during force shutdown
    Try {
      savePhaseCheckpoint(currentPhase.get(), 0.5)
    }

    try {
      stop()
    } catch {
      case ex: Exception =>
        logger.error(s"Error during force shutdown: ${ex.getMessage}")
        Runtime.getRuntime.halt(1)
    }
  }

  /**
   * Stop the worker node
   */
  def stop(): Unit = {
    logger.info(s"Stopping Worker $workerId...")
    System.err.println(s"[Worker-$workerIndex] Shutting down...")

    // Stop heartbeat
    heartbeatRunning = false
    if (heartbeatScheduler != null) {
      heartbeatScheduler.shutdown()
      try {
        if (!heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          heartbeatScheduler.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          heartbeatScheduler.shutdownNow()
      }
    }

    // Shutdown gRPC server
    if (server != null) {
      server.shutdown()
      try {
        if (!server.awaitTermination(3, TimeUnit.SECONDS)) {
          logger.warn("gRPC server didn't terminate, forcing shutdown")
          server.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          server.shutdownNow()
      }
    }

    // Shutdown master channel
    if (masterChannel != null) {
      masterChannel.shutdown()
      try {
        if (!masterChannel.awaitTermination(2, TimeUnit.SECONDS)) {
          logger.warn("Master channel didn't terminate, forcing shutdown")
          masterChannel.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          masterChannel.shutdownNow()
      }
    }

    // Cleanup shuffle manager resources (worker client connections)
    if (shuffleManager != null) {
      try {
        shuffleManager.cleanup()
      } catch {
        case ex: Exception =>
          logger.warn(s"Error during shuffle manager cleanup: ${ex.getMessage}", ex)
      }
    }

    // Cleanup temporary files
    try {
      fileLayout.cleanupTemporaryFiles()
    } catch {
      case ex: Exception =>
        logger.warn(s"Error cleaning temporary files: ${ex.getMessage}", ex)
    }

    // Shutdown executor with force
    executorService.shutdown()
    try {
      if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
        logger.warn("Executor didn't terminate, forcing shutdown")
        executorService.shutdownNow()
        executorService.awaitTermination(2, TimeUnit.SECONDS)
      }
    } catch {
      case _: InterruptedException =>
        executorService.shutdownNow()
    }

    shutdownLatch.countDown()
    logger.info(s"Worker $workerId stopped")
    System.err.println(s"[Worker-$workerIndex] Shutdown complete")

    // Force clean exit after successful shutdown
    // Use Runtime.halt(0) to skip shutdown hooks and exit immediately
    // This prevents GracefulShutdownManager from trying to use terminated executor
    Thread.sleep(500) // Brief delay to ensure logs are flushed
    Runtime.getRuntime.halt(0)
  }
}

/**
 * Worker application entry point
 */
object Worker extends LazyLogging {

  def main(args: Array[String]): Unit = {
    // Force IPv4 to avoid IPv6 issues
    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")

    // Parse command line arguments according to slide specification:
    // worker <master IP:port> -I <input1> <input2> ... -O <output>
    // Optional: -id <workerId> -p <workerPort>

    if (args.isEmpty) {
      println("Usage: worker <master IP:port> -I <input1> <input2> ... -O <output> [-id <workerId>] [-p <port>]")
      println("Example: worker 192.168.1.100:50051 -I /input1 /input2 -O /output")
      sys.exit(1)
    }

    // Parse master IP:port (first argument)
    val masterAddress = args(0)
    val (masterHost, masterPort) = masterAddress.split(":") match {
      case Array(host, port) => (host, port.toInt)
      case Array(host) => (host, 50051) // Default port if not specified
      case _ =>
        println(s"Error: Invalid master address format: $masterAddress")
        println("Expected format: <IP:port> or <hostname:port>")
        sys.exit(1)
        ("", 0) // Never reached
    }

    // Parse flags
    var i = 1
    var inputDirs = Seq.empty[String]
    var outputDir = ""
    var workerId = s"worker-${UUID.randomUUID().toString.take(8)}" // Auto-generated by default
    var workerPort = 0 // Auto-assign by default

    while (i < args.length) {
      args(i) match {
        case "-I" | "-i" =>
          // Collect all input directories until next flag
          i += 1
          while (i < args.length && !args(i).startsWith("-")) {
            inputDirs = inputDirs :+ args(i)
            i += 1
          }

        case "-O" | "-o" =>
          // Next argument is output directory
          i += 1
          if (i < args.length) {
            outputDir = args(i)
            i += 1
          } else {
            println("Error: -O flag requires an output directory")
            sys.exit(1)
          }

        case "-id" =>
          // Optional worker ID
          i += 1
          if (i < args.length) {
            workerId = args(i)
            i += 1
          } else {
            println("Error: -id flag requires a worker ID")
            sys.exit(1)
          }

        case "-p" | "-port" =>
          // Optional worker port
          i += 1
          if (i < args.length) {
            workerPort = args(i).toInt
            i += 1
          } else {
            println("Error: -p flag requires a port number")
            sys.exit(1)
          }

        case unknown =>
          println(s"Error: Unknown flag: $unknown")
          sys.exit(1)
      }
    }

    // Validate required arguments
    if (inputDirs.isEmpty) {
      println("Error: At least one input directory required (-I flag)")
      sys.exit(1)
    }
    if (outputDir.isEmpty) {
      println("Error: Output directory required (-O flag)")
      sys.exit(1)
    }

    logger.info(s"Starting Worker with configuration:")
    logger.info(s"  Worker ID: $workerId")
    logger.info(s"  Master: $masterHost:$masterPort")
    logger.info(s"  Input directories: ${inputDirs.mkString(", ")}")
    logger.info(s"  Output directory: $outputDir")
    logger.info(s"  Worker port: $workerPort")

    // Create and start worker
    val worker = new Worker(
      workerId = workerId,
      masterHost = masterHost,
      masterPort = masterPort,
      workerPort = workerPort,
      inputDirs = inputDirs,
      outputDir = outputDir
    )

    try {
      worker.start()
      logger.info(s"Worker $workerId started successfully")

      // Run the workflow in a separate thread
      new Thread(() => {
        try {
          Thread.sleep(2000) // Give server time to fully start
          worker.run()
        } catch {
          case ex: Exception =>
            System.err.println(s"[Worker] WORKFLOW FAILED: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
            ex.printStackTrace(System.err)
            logger.error(s"Worker workflow failed: ${ex.getMessage}", ex)
            worker.stop()
        }
      }).start()

      // Wait for termination
      worker.awaitTermination()

    } catch {
      case ex: Exception =>
        logger.error(s"Failed to start Worker: ${ex.getMessage}", ex)
        sys.exit(1)
    }
  }
}