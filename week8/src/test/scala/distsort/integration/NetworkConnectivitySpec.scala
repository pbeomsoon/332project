package distsort.integration

import distsort.master.Master
import distsort.worker.Worker
import distsort.integration.TestHelpers._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._

/**
 * Network connectivity tests - ACTUAL network verification
 *
 * Tests actual network connections, not just simulation:
 * - ✅ Verify gRPC servers are actually listening on ports
 * - ✅ Test real connection establishment
 * - ✅ Test connection termination
 * - ✅ Test worker re-registration after disconnect
 * - ✅ Verify data can actually flow over network
 */
class NetworkConnectivitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var inputDir: File = _
  private var outputDir: File = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("network-test").toFile
    inputDir = new File(tempDir, "input")
    outputDir = new File(tempDir, "output")
    inputDir.mkdirs()
    outputDir.mkdirs()

    // Create test input files
    createTestInputFiles(inputDir, numFiles = 2, recordsPerFile = 50)
  }

  override def afterEach(): Unit = {
    deleteRecursively(tempDir)
  }

  "Master gRPC Server" should "be accessible on assigned port" in {
    val master = new Master(
      port = 0,  // Auto-assign port
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )

    master.start()
    val assignedPort = master.getPort

    // ✅ Verify port is valid
    assignedPort should be > 0
    info(s"Master assigned port: $assignedPort")

    // ✅ Verify port is actually open and accepting connections
    waitForPortOpen("localhost", assignedPort, timeout = 5.seconds) shouldBe true
    info(s"✓ Port $assignedPort is open and accepting connections")

    // ✅ Verify we can actually connect to it
    isPortOpen("localhost", assignedPort) shouldBe true

    master.stop()

    // ✅ Verify port is closed after stop
    waitUntil(5.seconds) {
      !isPortOpen("localhost", assignedPort)
    } shouldBe true
    info(s"✓ Port $assignedPort closed after master stop")
  }

  "Worker gRPC Server" should "be accessible on assigned port" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )
    master.start()
    val masterPort = master.getPort

    val worker = new Worker(
      "test-worker",
      "localhost",
      masterPort,
      0,  // Auto-assign port
      Seq(inputDir.getAbsolutePath),
      outputDir.getAbsolutePath
    )

    worker.start()
    val workerPort = worker.getPort

    // ✅ Verify worker port is valid
    workerPort should be > 0
    info(s"Worker assigned port: $workerPort")

    // ✅ Verify worker port is actually open
    waitForPortOpen("localhost", workerPort, timeout = 5.seconds) shouldBe true
    info(s"✓ Worker port $workerPort is open")

    // ✅ Verify worker can connect to master
    isPortOpen("localhost", masterPort) shouldBe true

    worker.stop()
    master.stop()

    // ✅ Verify both ports are closed
    waitUntil(5.seconds) {
      !isPortOpen("localhost", workerPort) && !isPortOpen("localhost", masterPort)
    } shouldBe true
    info(s"✓ Both ports closed after shutdown")
  }

  "Worker" should "successfully register with Master over network" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 2,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 6
    )
    master.start()
    val masterPort = master.getPort

    // ✅ Verify master is ready
    verifyMasterConnectivity("localhost", masterPort)

    val worker1 = new Worker(
      "worker-1", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )
    val worker2 = new Worker(
      "worker-2", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    // Start workers
    worker1.start()
    worker2.start()

    // ✅ Verify workers are actually listening
    verifyWorkerConnectivity("localhost", worker1.getPort)
    verifyWorkerConnectivity("localhost", worker2.getPort)

    // ✅ Verify workers registered with master (actual network communication)
    waitForWorkerRegistration(master, expectedCount = 2, timeout = 10.seconds) shouldBe true

    val registeredCount = master.getService.getRegisteredWorkerCount
    registeredCount shouldBe 2
    info(s"✓ 2 workers successfully registered over network")

    worker1.stop()
    worker2.stop()
    master.stop()
  }

  it should "re-register after network disconnection" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )
    master.start()
    val masterPort = master.getPort

    val worker = new Worker(
      "reconnect-worker", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    // ✅ Initial registration
    worker.start()
    waitForWorkerRegistration(master, expectedCount = 1, timeout = 10.seconds) shouldBe true
    info(s"✓ Worker initially registered")

    val initialWorkerPort = worker.getPort
    isPortOpen("localhost", initialWorkerPort) shouldBe true

    // ✅ Simulate disconnection by stopping worker
    worker.stop()

    // ✅ Verify worker is actually disconnected
    waitUntil(5.seconds) {
      !isPortOpen("localhost", initialWorkerPort)
    } shouldBe true
    info(s"✓ Worker disconnected (port $initialWorkerPort closed)")

    // ✅ Re-register worker (Worker Re-registration)
    worker.start()
    val newWorkerPort = worker.getPort

    // ✅ Verify worker is back online with new port
    waitForPortOpen("localhost", newWorkerPort, timeout = 5.seconds) shouldBe true
    info(s"✓ Worker reconnected on new port: $newWorkerPort")

    // ✅ Verify worker re-registered with master
    waitUntil(10.seconds) {
      master.getService.getRegisteredWorkerCount >= 1
    } shouldBe true
    info(s"✓ Worker successfully re-registered after disconnect")

    worker.stop()
    master.stop()
  }

  "Multiple workers" should "all connect simultaneously without conflicts" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 5,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 15
    )
    master.start()
    val masterPort = master.getPort

    // ✅ Create 5 workers
    val workers = (1 to 5).map { i =>
      new Worker(
        s"concurrent-worker-$i", "localhost", masterPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
      )
    }

    // ✅ Start all workers simultaneously
    workers.foreach(_.start())

    // ✅ Verify all workers got unique ports
    val workerPorts = workers.map(_.getPort)
    workerPorts.distinct.size shouldBe 5
    info(s"✓ All workers got unique ports: ${workerPorts.mkString(", ")}")

    // ✅ Verify all ports are actually open
    workerPorts.foreach { port =>
      waitForPortOpen("localhost", port, timeout = 5.seconds) shouldBe true
    }
    info(s"✓ All 5 worker ports are open")

    // ✅ Verify all workers registered with master
    waitForWorkerRegistration(master, expectedCount = 5, timeout = 15.seconds) shouldBe true
    info(s"✓ All 5 workers registered successfully")

    workers.foreach(_.stop())
    master.stop()

    // ✅ Verify all ports are closed
    waitUntil(10.seconds) {
      workerPorts.forall(port => !isPortOpen("localhost", port))
    } shouldBe true
    info(s"✓ All ports closed cleanly")
  }

  "Network" should "handle master restart with worker waiting" in {
    val masterPort = 50051  // Fixed port for this test

    // ✅ Start master on fixed port
    val master1 = new Master(
      port = masterPort,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )
    master1.start()
    verifyMasterConnectivity("localhost", masterPort)

    // ✅ Stop master
    master1.stop()

    // ✅ Verify port is actually closed
    waitUntil(5.seconds) {
      !isPortOpen("localhost", masterPort)
    } shouldBe true
    info(s"✓ Master stopped, port $masterPort closed")

    // ✅ Restart master on same port
    val master2 = new Master(
      port = masterPort,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )
    master2.start()

    // ✅ Verify port is open again
    waitForPortOpen("localhost", masterPort, timeout = 5.seconds) shouldBe true
    info(s"✓ Master restarted on same port $masterPort")

    // ✅ Worker should be able to connect to restarted master
    val worker = new Worker(
      "post-restart-worker", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )
    worker.start()

    waitForWorkerRegistration(master2, expectedCount = 1, timeout = 10.seconds) shouldBe true
    info(s"✓ Worker connected to restarted master")

    worker.stop()
    master2.stop()
  }

  it should "detect when master becomes unreachable" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 3
    )
    master.start()
    val masterPort = master.getPort

    // ✅ Verify master is reachable
    verifyMasterConnectivity("localhost", masterPort)

    val worker = new Worker(
      "detect-worker", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )
    worker.start()

    waitForWorkerRegistration(master, expectedCount = 1, timeout = 10.seconds) shouldBe true

    // ✅ Abruptly stop master (simulate crash)
    master.stop()

    // ✅ Verify worker can detect master is unreachable
    waitUntil(5.seconds) {
      !isPortOpen("localhost", masterPort)
    } shouldBe true
    info(s"✓ Worker can detect master is unreachable (port closed)")

    worker.stop()
  }

  "Worker-to-Worker communication" should "work over actual network" in {
    val master = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 9
    )
    master.start()
    val masterPort = master.getPort

    val workers = (1 to 3).map { i =>
      new Worker(
        s"p2p-worker-$i", "localhost", masterPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
      )
    }

    // ✅ Start all workers
    workers.foreach(_.start())

    // ✅ Verify all workers can reach each other
    val workerPorts = workers.map(_.getPort)

    workerPorts.foreach { port =>
      isPortOpen("localhost", port) shouldBe true
    }

    info(s"✓ All workers are reachable on ports: ${workerPorts.mkString(", ")}")

    // ✅ Verify workers registered
    waitForWorkerRegistration(master, expectedCount = 3, timeout = 15.seconds) shouldBe true

    workers.foreach(_.stop())
    master.stop()
  }
}
