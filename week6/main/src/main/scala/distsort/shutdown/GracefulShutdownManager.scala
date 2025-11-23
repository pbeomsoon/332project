package distsort.shutdown

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.slf4j.LoggerFactory

/**
 * Shutdown hook configuration
 */
case class ShutdownConfig(
  gracePeriod: Duration = 30.seconds,
  saveCheckpoint: Boolean = true,
  waitForCurrentPhase: Boolean = true,
  forceKillTimeout: Duration = 60.seconds
)

/**
 * Represents a component that can be shutdown gracefully
 */
trait ShutdownAware {
  /**
   * Initiate graceful shutdown
   * @return Future that completes when shutdown is done
   */
  def gracefulShutdown(): Future[Unit]

  /**
   * Check if component is ready for shutdown
   */
  def isReadyForShutdown: Boolean

  /**
   * Force immediate shutdown
   */
  def forceShutdown(): Unit
}

/**
 * Manages graceful shutdown of system components
 */
class GracefulShutdownManager(
  config: ShutdownConfig = ShutdownConfig()
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val shutdownInitiated = new AtomicBoolean(false)
  private val componentsToShutdown = scala.collection.mutable.ListBuffer.empty[ShutdownAware]
  private val shutdownLatch = new CountDownLatch(1)
  private val activeOperations = new AtomicInteger(0)
  private val shutdownPromise = Promise[Unit]()

  /**
   * Register a component for graceful shutdown
   */
  def register(component: ShutdownAware): Unit = {
    componentsToShutdown.synchronized {
      componentsToShutdown += component
      logger.debug(s"Registered component for graceful shutdown: ${component.getClass.getSimpleName}")
    }
  }

  /**
   * Unregister a component
   */
  def unregister(component: ShutdownAware): Unit = {
    componentsToShutdown.synchronized {
      componentsToShutdown -= component
      logger.debug(s"Unregistered component: ${component.getClass.getSimpleName}")
    }
  }

  /**
   * Track an active operation
   */
  def trackOperation[T](operation: => Future[T]): Future[T] = {
    activeOperations.incrementAndGet()
    try {
      operation.andThen { case _ =>
        activeOperations.decrementAndGet()
      }
    } catch {
      case _: java.util.concurrent.RejectedExecutionException =>
        // ExecutionContext is shutting down, just decrement and return original future
        activeOperations.decrementAndGet()
        operation
    }
  }

  /**
   * Initiate graceful shutdown
   */
  def initiateShutdown(): Future[Unit] = {
    if (shutdownInitiated.compareAndSet(false, true)) {
      logger.info("Initiating graceful shutdown...")

      // Start shutdown sequence
      val shutdownFuture = performShutdown()

      // Schedule forced shutdown as fallback
      scheduleForceShutdown()

      shutdownFuture
    } else {
      logger.warn("Shutdown already initiated")
      shutdownPromise.future
    }
  }

  /**
   * Perform the actual shutdown sequence
   */
  private def performShutdown(): Future[Unit] = {
    val shutdownSteps = for {
      // Step 1: Stop accepting new work
      _ <- Future {
        logger.info("Step 1: Stopping new work acceptance")
        componentsToShutdown.foreach { component =>
          Try(component.isReadyForShutdown)
        }
      }

      // Step 2: Wait for active operations to complete
      _ <- waitForActiveOperations()

      // Step 3: Shutdown components in reverse registration order
      _ <- shutdownComponents()

      // Step 4: Cleanup resources
      _ <- cleanupResources()

    } yield {
      logger.info("Graceful shutdown completed successfully")
      shutdownLatch.countDown()
    }

    shutdownSteps.onComplete {
      case Success(_) =>
        shutdownPromise.success(())
        logger.info("All components shut down gracefully")
      case Failure(ex) =>
        shutdownPromise.failure(ex)
        logger.error(s"Error during graceful shutdown: ${ex.getMessage}", ex)
        // Force shutdown on error
        forceShutdownAll()
    }

    shutdownPromise.future
  }

  /**
   * Wait for active operations to complete
   */
  private def waitForActiveOperations(): Future[Unit] = Future {
    val startTime = System.currentTimeMillis()
    val timeoutMillis = config.gracePeriod.toMillis

    while (activeOperations.get() > 0 &&
           (System.currentTimeMillis() - startTime) < timeoutMillis) {
      logger.info(s"Waiting for ${activeOperations.get()} active operations to complete...")
      Thread.sleep(1000)
    }

    if (activeOperations.get() > 0) {
      logger.warn(s"Timeout waiting for active operations. ${activeOperations.get()} operations still running.")
    } else {
      logger.info("All active operations completed")
    }
  }

  /**
   * Shutdown all registered components
   */
  private def shutdownComponents(): Future[Unit] = {
    val components = componentsToShutdown.synchronized {
      componentsToShutdown.reverse.toList // Shutdown in reverse order
    }

    val shutdownFutures = components.map { component =>
      val componentName = component.getClass.getSimpleName
      logger.info(s"Shutting down component: $componentName")

      val shutdownFuture = component.gracefulShutdown()

      // Add timeout to component shutdown
      val timeoutFuture = Future {
        Thread.sleep(config.gracePeriod.toMillis)
        throw new Exception(s"Component $componentName shutdown timeout")
      }

      try {
        Future.firstCompletedOf(Seq(shutdownFuture, timeoutFuture)).recover {
          case ex =>
            logger.error(s"Error shutting down $componentName: ${ex.getMessage}")
            Try(component.forceShutdown())
        }
      } catch {
        case _: java.util.concurrent.RejectedExecutionException =>
          logger.warn(s"ExecutionContext shutdown during $componentName cleanup")
          Future.successful(())
      }
    }

    try {
      Future.sequence(shutdownFutures).map(_ => ())
    } catch {
      case _: java.util.concurrent.RejectedExecutionException =>
        logger.warn("ExecutionContext shutdown during final cleanup")
        Future.successful(())
    }
  }

  /**
   * Cleanup any remaining resources
   */
  private def cleanupResources(): Future[Unit] = Future {
    logger.info("Cleaning up resources...")

    // Clear component list
    componentsToShutdown.clear()

    // Reset counters
    activeOperations.set(0)

    logger.info("Resource cleanup completed")
  }

  /**
   * Schedule forced shutdown as a fallback
   */
  private def scheduleForceShutdown(): Unit = {
    val forceShutdownThread = new Thread(() => {
      Thread.sleep(config.forceKillTimeout.toMillis)
      if (!shutdownPromise.isCompleted) {
        logger.error(s"Forced shutdown after ${config.forceKillTimeout}")
        forceShutdownAll()
      }
    })

    forceShutdownThread.setDaemon(true)
    forceShutdownThread.setName("force-shutdown-timer")
    forceShutdownThread.start()
  }

  /**
   * Force immediate shutdown of all components
   */
  private def forceShutdownAll(): Unit = {
    logger.warn("Forcing immediate shutdown of all components")

    componentsToShutdown.foreach { component =>
      Try(component.forceShutdown()).recover {
        case ex =>
          logger.error(s"Error during forced shutdown: ${ex.getMessage}")
      }
    }

    if (!shutdownPromise.isCompleted) {
      shutdownPromise.failure(new Exception("Forced shutdown"))
    }

    shutdownLatch.countDown()
  }

  /**
   * Wait for shutdown to complete
   */
  def awaitShutdown(timeout: Duration = Duration.Inf): Boolean = {
    if (timeout.isFinite) {
      shutdownLatch.await(timeout.toMillis, TimeUnit.MILLISECONDS)
    } else {
      shutdownLatch.await()
      true
    }
  }

  /**
   * Check if shutdown is in progress
   */
  def isShuttingDown: Boolean = shutdownInitiated.get()

  /**
   * Register JVM shutdown hook
   */
  def registerShutdownHook(): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        if (!isShuttingDown) {
          logger.info("JVM shutdown hook triggered")
          Try(initiateShutdown())
          awaitShutdown(config.forceKillTimeout)
        }
      }
    })
    logger.info("JVM shutdown hook registered")
  }
}

/**
 * Companion object
 */
object GracefulShutdownManager {

  /**
   * Create a shutdown manager with default configuration
   */
  def apply()(implicit ec: ExecutionContext): GracefulShutdownManager = {
    new GracefulShutdownManager()
  }

  /**
   * Create a shutdown manager with custom configuration
   */
  def apply(config: ShutdownConfig)(implicit ec: ExecutionContext): GracefulShutdownManager = {
    new GracefulShutdownManager(config)
  }
}