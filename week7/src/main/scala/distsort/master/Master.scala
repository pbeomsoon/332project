package distsort.master

import distsort.proto.distsort._
import distsort.shutdown.{GracefulShutdownManager, ShutdownAware, ShutdownConfig}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{Server, ServerBuilder}

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Master node that coordinates the distributed sorting process.
 *
 * Responsibilities:
 * - Accept worker registrations
 * - Collect samples and compute partition boundaries
 * - Coordinate phase transitions
 * - Monitor worker health
 * - Handle failures
 */
class Master(
  port: Int,
  expectedWorkers: Int,
  outputDir: String,
  numPartitions: Int,
  strategy: String = "B"  // "A" = 1:1, "B" = N:M
) extends LazyLogging with ShutdownAware {

  // Thread pool for async operations
  private val executorService = Executors.newFixedThreadPool(
    Runtime.getRuntime.availableProcessors() * 2
  )
  private implicit val executionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(executorService)

  // gRPC components
  val masterService = new MasterService(expectedWorkers, numPartitions)
  private var server: Server = _
  private var actualPort: Int = _

  // Graceful shutdown manager
  private val shutdownManager = GracefulShutdownManager(
    ShutdownConfig(
      gracePeriod = 30.seconds,
      saveCheckpoint = false,  // Master doesn't need checkpointing
      waitForCurrentPhase = true
    )
  )
  @volatile private var isShuttingDown = false
  @volatile private var isRunning = false

  // Shutdown latch
  private val shutdownLatch = new CountDownLatch(1)

  /**
   * Start the master node
   */
  def start(): Unit = {
    logger.info(s"Starting Master node with $expectedWorkers workers, $numPartitions partitions, Strategy $strategy")

    // Build and start gRPC server
    server = ServerBuilder
      .forPort(port)
      .addService(MasterServiceGrpc.bindService(masterService, executionContext))
      .build()

    Try {
      server.start()
      actualPort = server.getPort
      logger.info(s"Master gRPC server started on port $actualPort")
    } match {
      case Success(_) =>
        // Register with shutdown manager
        shutdownManager.register(this)
        shutdownManager.registerShutdownHook()
        logger.info("Master registered with graceful shutdown manager")

      case Failure(ex) =>
        logger.error(s"Failed to start Master server: ${ex.getMessage}", ex)
        throw ex
    }
  }

  /**
   * Run the complete distributed sorting workflow
   */
  def run(): Unit = {
    try {
      isRunning = true
      logger.info("Starting distributed sorting workflow")

      // Initialize phase tracking BEFORE workers register (they start workflow after 2s)
      masterService.initPhaseTracking(WorkerPhase.PHASE_SAMPLING)

      // Phase 1: Wait for worker registrations
      logger.info(s"Phase 1: Waiting for $expectedWorkers workers to register...")
      System.err.println(s"[Master] Waiting for $expectedWorkers workers to register...")
      if (!masterService.waitForAllWorkers(300)) { // 5 minutes timeout
        throw new RuntimeException(s"Timeout waiting for $expectedWorkers workers to register")
      }

      // Output Worker IPs to STDOUT immediately after registration (Slide specification: Line 2)
      val workerIPs = masterService.getRegisteredWorkers
        .map(w => w.host)
        .mkString(", ")
      println(workerIPs)

      logger.info(s"All $expectedWorkers workers registered successfully")
      System.err.println(s"[Master] All $expectedWorkers workers registered")

      // Phase 2: Sampling
      logger.info("Phase 2: Workers performing sampling...")
      System.err.println("[Master] Phase 1/4: Sampling...")
      if (!masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SAMPLING)) {
        throw new RuntimeException("Timeout waiting for sampling phase")
      }
      System.err.println("[Master] Sampling complete")

      // Phase 3: Collect samples and compute boundaries
      logger.info("Phase 3: Collecting samples from workers...")
      if (!masterService.waitForAllSamples(120)) { // 2 minutes timeout
        throw new RuntimeException("Timeout waiting for worker samples")
      }

      val boundaries = masterService.getBoundaries
      logger.info(s"Computed ${boundaries.length} partition boundaries")
      System.err.println(s"[Master] Computed ${boundaries.length} partition boundaries")

      // Phase 4: Sorting
      logger.info("Phase 4: Workers performing local sort...")
      System.err.println("[Master] Phase 2/4: Sorting...")
      masterService.initPhaseTracking(WorkerPhase.PHASE_SORTING)
      if (!masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SORTING)) {
        throw new RuntimeException("Timeout waiting for sorting phase")
      }
      System.err.println("[Master] Sorting complete")

      // Phase 5: Shuffling
      logger.info("Phase 5: Workers performing shuffle...")
      System.err.println("[Master] Phase 3/4: Shuffling...")
      masterService.initPhaseTracking(WorkerPhase.PHASE_SHUFFLING)
      if (!masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SHUFFLING)) {
        throw new RuntimeException("Timeout waiting for shuffle phase")
      }
      System.err.println("[Master] Shuffling complete")

      // Phase 6: Merging
      logger.info("Phase 6: Workers performing final merge...")
      System.err.println("[Master] Phase 4/4: Merging...")
      masterService.initPhaseTracking(WorkerPhase.PHASE_MERGING)
      if (!masterService.waitForPhaseCompletion(WorkerPhase.PHASE_MERGING)) {
        throw new RuntimeException("Timeout waiting for merge phase")
      }
      System.err.println("[Master] Merging complete")

      logger.info("Distributed sorting workflow completed successfully!")
      System.err.println("[Master] Workflow completed successfully!")

      // Output final result to STDOUT (worker ordering debug info)
      outputFinalResult()

      isRunning = false

      // Signal workers to gracefully shut down
      logger.info("Signaling workers to shut down gracefully...")
      masterService.signalWorkflowComplete()

      // Wait for workers to receive shutdown signal via heartbeat
      // Workers send heartbeat every 5 seconds, so 10 seconds ensures they get the signal
      logger.info("Waiting 10 seconds for workers to receive shutdown signal...")
      Thread.sleep(10000)

      // Auto-shutdown after successful completion
      logger.info("Grace period complete, shutting down Master...")
      stop()

    } catch {
      case ex: Exception =>
        logger.error(s"Master workflow failed: ${ex.getMessage}", ex)
        isRunning = false
        throw ex
    }
  }

  /**
   * Log worker ordering information (for debugging)
   * Actual output to STDOUT is done in main()
   */
  private def outputFinalResult(): Unit = {
    logger.info("Workflow completed - logging worker ordering")

    // Get all registered workers sorted by index
    val sortedWorkers = masterService.getRegisteredWorkers

    // Log worker details for debugging
    logger.info("=== Worker Ordering ===")
    sortedWorkers.foreach { worker =>
      logger.info(s"Worker ${worker.workerIndex}: ${worker.workerId} at ${worker.host}:${worker.port}")
    }

    logger.info("Master/Worker information logged")
  }

  /**
   * Wait for completion
   */
  def awaitTermination(): Unit = {
    logger.info("Master waiting for termination signal...")
    shutdownLatch.await()
  }

  // ===== ShutdownAware trait implementation =====

  /**
   * Initiate graceful shutdown
   * @return Future that completes when shutdown is done
   */
  override def gracefulShutdown(): Future[Unit] = {
    logger.info("Graceful shutdown initiated for Master")
    isShuttingDown = true

    Future {
      try {
        // 1. Stop accepting new worker registrations
        logger.info("Master: Stopping new worker registrations")

        // 2. Wait for current workflow to complete if running
        if (isRunning) {
          logger.info("Master: Waiting for current workflow to complete")
          var waitTime = 0
          while (isRunning && waitTime < 30) {
            Thread.sleep(1000)
            waitTime += 1
          }

          if (isRunning) {
            logger.warn("Master: Workflow still running after 30s, proceeding with shutdown")
          }
        }

        // 3. Cleanup resources
        logger.info("Master: Cleaning up resources")
        stop()

        logger.info("Master: Graceful shutdown completed")
      } catch {
        case ex: Exception =>
          logger.error(s"Error during graceful shutdown: ${ex.getMessage}", ex)
          throw ex
      }
    }
  }

  /**
   * Check if master is ready for shutdown
   * @return true if no active workflow is running
   */
  override def isReadyForShutdown: Boolean = {
    val ready = !isRunning

    if (!ready) {
      logger.debug("Master not ready for shutdown, workflow still running")
    }

    ready
  }

  /**
   * Force immediate shutdown
   */
  override def forceShutdown(): Unit = {
    logger.warn("Force shutdown for Master")
    isShuttingDown = true
    isRunning = false

    // Start a watchdog thread to force exit if stop() hangs
    val watchdog = new Thread(() => {
      Thread.sleep(5000) // 5 seconds max for force shutdown
      System.err.println("[Master] Force shutdown timeout - halting immediately")
      Runtime.getRuntime.halt(1)
    }, "master-force-shutdown-watchdog")
    watchdog.setDaemon(true)
    watchdog.start()

    try {
      stop()
    } catch {
      case ex: Exception =>
        logger.error(s"Error during force shutdown: ${ex.getMessage}")
        Runtime.getRuntime.halt(1)
    }
  }

  /**
   * Stop the master node
   */
  def stop(): Unit = {
    logger.info("Stopping Master node...")

    // Stop MasterService first (stops heartbeat monitoring)
    try {
      masterService.shutdown()
    } catch {
      case ex: Exception =>
        logger.warn(s"Error shutting down MasterService: ${ex.getMessage}")
    }

    // Shutdown gRPC server
    if (server != null) {
      server.shutdown()
      try {
        if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
          logger.warn("Server didn't terminate in 10 seconds, forcing shutdown")
          server.shutdownNow()
          server.awaitTermination(5, TimeUnit.SECONDS)
        }
      } catch {
        case ex: InterruptedException =>
          logger.error("Interrupted while shutting down server", ex)
          server.shutdownNow()
      }
    }

    // Shutdown executor service
    executorService.shutdown()
    try {
      if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
        executorService.shutdownNow()
      }
    } catch {
      case ex: InterruptedException =>
        executorService.shutdownNow()
    }

    shutdownLatch.countDown()
    logger.info("Master node stopped")

    // Force clean exit after successful shutdown
    // Use Runtime.halt(0) to skip shutdown hooks and exit immediately
    Thread.sleep(500) // Brief delay to ensure logs are flushed
    Runtime.getRuntime.halt(0)
  }

  /**
   * Get the actual port the server is running on
   */
  def getPort: Int = actualPort

  /**
   * Get the master service (for testing)
   */
  def getService: MasterService = masterService
}

/**
 * Master application entry point
 */
object Master extends LazyLogging {

  def main(args: Array[String]): Unit = {
    // Force IPv4 to avoid IPv6 issues
    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")

    // Parse command line arguments according to slide specification:
    // master <# of workers>
    // Optional environment variables for advanced configuration

    if (args.isEmpty) {
      println("Usage: master <# of workers>")
      println("Example: master 3")
      sys.exit(1)
    }

    // First argument: number of workers (required)
    val numWorkers = args.headOption.map(_.toInt)
      .orElse(sys.env.get("DISTSORT_NUM_WORKERS").map(_.toInt))
      .getOrElse {
        logger.error("Number of workers not specified")
        println("Usage: master <# of workers>")
        sys.exit(1)
        3  // Never reached
      }

    // Port: use environment variable or default (0 = auto-assign)
    val port = sys.env.get("DISTSORT_MASTER_PORT").map(_.toInt).getOrElse(0)

    // Output directory: use environment variable or default
    val outputDir = sys.env.get("DISTSORT_OUTPUT_DIR").getOrElse("/tmp/distsort/output")

    // Strategy selection: A = 1:1 (N workers, N partitions), B = N:M (N workers, M partitions)
    val strategy = sys.env.get("DISTSORT_STRATEGY").getOrElse("B").toUpperCase

    // For Strategy A, force numPartitions = numWorkers
    val numPartitions = if (strategy == "A") {
      logger.info(s"Strategy A selected: forcing 1:1 mapping ($numWorkers partitions)")
      numWorkers
    } else {
      sys.env.get("DISTSORT_NUM_PARTITIONS").map(_.toInt).getOrElse(numWorkers * 3)
    }

    logger.info(s"Starting Master with configuration:")
    logger.info(s"  Port: $port")
    logger.info(s"  Expected workers: $numWorkers")
    logger.info(s"  Output directory: $outputDir")
    logger.info(s"  Strategy: $strategy")
    logger.info(s"  Number of partitions: $numPartitions")

    // Validate strategy
    if (strategy == "A" && numPartitions != numWorkers) {
      logger.error(s"Strategy A requires N=M (workers=partitions). Got $numWorkers workers, $numPartitions partitions")
      sys.exit(1)
    }

    // Create and start master
    val master = new Master(port, numWorkers, outputDir, numPartitions, strategy)

    try {
      master.start()

      // Slide specification output: Line 1 - Master IP:port (IPv4 only)
      val masterHost = try {
        import java.net._
        val interfaces = NetworkInterface.getNetworkInterfaces
        scala.collection.JavaConverters.enumerationAsScalaIterator(interfaces)
          .flatMap(iface => scala.collection.JavaConverters.enumerationAsScalaIterator(iface.getInetAddresses))
          .find(addr => addr.isInstanceOf[Inet4Address] && !addr.isLoopbackAddress)
          .map(_.getHostAddress)
          .getOrElse("127.0.0.1")
      } catch {
        case _: Exception => "127.0.0.1"
      }
      println(s"$masterHost:${master.getPort}")

      // Run the workflow in a separate thread
      new Thread(() => {
        try {
          Thread.sleep(1000) // Give server time to fully start
          master.run()
          // Worker IPs are now printed inside master.run() before stop()

        } catch {
          case ex: Exception =>
            logger.error(s"Master workflow failed: ${ex.getMessage}", ex)
            master.stop()
        }
      }).start()

      // Wait for termination
      master.awaitTermination()

    } catch {
      case ex: Exception =>
        logger.error(s"Failed to start Master: ${ex.getMessage}", ex)
        sys.exit(1)
    }
  }
}