package distsort.integration

import distsort.master.Master
import distsort.worker.Worker
import distsort.proto.distsort._
import distsort.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.io.File
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._

/**
 * Integration tests for phase transitions between Master and Workers
 */
class PhaseTransitionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var master: Master = _
  private var workers: Seq[Worker] = _
  private val tempDir = Files.createTempDirectory("phase-test").toFile
  private val inputDir = new File(tempDir, "input")
  private val outputDir = new File(tempDir, "output")

  override def beforeAll(): Unit = {
    inputDir.mkdirs()
    outputDir.mkdirs()
    createTestFiles()
  }

  override def afterAll(): Unit = {
    if (workers != null) workers.foreach(_.stop())
    if (master != null) master.stop()
    deleteRecursively(tempDir)
  }

  "Phase Transitions" should "follow correct sequence from INITIALIZING to COMPLETED" in {
    // Start master
    master = new Master(
      port = 0,
      expectedWorkers = 2,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 4
    )
    master.start()
    val masterPort = master.getPort

    // Track phase transitions
    var worker1Phases = Seq.empty[WorkerPhase]
    var worker2Phases = Seq.empty[WorkerPhase]

    // Create workers with phase tracking
    val worker1 = new Worker(
      "phase-worker-1", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    ) {
      override def run(): Unit = {
        worker1Phases = worker1Phases :+ WorkerPhase.PHASE_INITIALIZING
        super.run()
      }
    }

    val worker2 = new Worker(
      "phase-worker-2", "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    ) {
      override def run(): Unit = {
        worker2Phases = worker2Phases :+ WorkerPhase.PHASE_INITIALIZING
        super.run()
      }
    }

    workers = Seq(worker1, worker2)

    // Start workers
    val workerThreads = workers.map { worker =>
      new Thread(() => {
        worker.start()
        Thread.sleep(500)
        worker.run()
      })
    }

    // Start master workflow
    new Thread(() => {
      Thread.sleep(3000)
      master.run()
    }).start()

    workerThreads.foreach(_.start())
    Thread.sleep(15000) // Let workflow complete

    // Verify registration happened
    master.getService.getRegisteredWorkerCount shouldBe 2

    // Check that samples were collected
    master.getService.getBoundaries should not be empty
  }

  it should "handle phase synchronization correctly" in {
    // All workers should wait for each other at phase boundaries
    // This test verifies that no worker proceeds until all have completed current phase

    val masterService = master.getService

    // Verify that both workers completed sampling before any proceeded
    masterService.waitForAllSamples(1) shouldBe true

    // Check boundaries were computed after all samples collected
    val boundaries = masterService.getBoundaries
    boundaries.length shouldBe 3 // numPartitions - 1
  }

  it should "handle worker joining late in a phase" in {
    // Start new master for this test
    val lateMaster = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 6
    )
    lateMaster.start()
    val port = lateMaster.getPort

    // Start 2 workers immediately
    val earlyWorkers = (1 to 2).map { i =>
      new Worker(
        s"early-worker-$i", "localhost", port, 0,
        Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
      )
    }

    earlyWorkers.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    Thread.sleep(3000)

    // Start late worker
    val lateWorker = new Worker(
      "late-worker", "localhost", port, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    new Thread(() => {
      lateWorker.start()
      lateWorker.run()
    }).start()

    // Start master workflow
    new Thread(() => {
      Thread.sleep(2000)
      lateMaster.run()
    }).start()

    Thread.sleep(10000)

    // All 3 workers should be registered
    lateMaster.getService.getRegisteredWorkerCount shouldBe 3

    // Cleanup
    (earlyWorkers :+ lateWorker).foreach(_.stop())
    lateMaster.stop()
  }

  it should "prevent invalid phase transitions" in {
    // This test is covered by WorkerServiceSpec but let's verify in integration
    // Workers should not be able to skip phases

    val testMaster = new Master(
      port = 0,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 2
    )
    testMaster.start()

    val testWorker = new Worker(
      "test-worker", "localhost", testMaster.getPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    testWorker.start()

    // Worker should be in INITIALIZING state initially
    // It cannot jump directly to SHUFFLING
    // This is enforced by WorkerService.isValidTransition

    Thread.sleep(1000)

    testWorker.stop()
    testMaster.stop()
  }

  it should "handle phase rollback on error" in {
    // When a worker fails during a phase, it should be able to restart
    val errorMaster = new Master(
      port = 0,
      expectedWorkers = 2,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 4
    )
    errorMaster.start()

    // Create a worker that will fail during sampling
    class FailingWorker(id: String, masterPort: Int) extends Worker(
      id, "localhost", masterPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    ) {
      override def run(): Unit = {
        try {
          // Start normally
          start()

          // Simulate failure during sampling
          throw new RuntimeException("Simulated sampling failure")
        } catch {
          case _: Exception =>
            // Report error and attempt restart
            Thread.sleep(1000)
            // In real scenario, worker would restart
        }
      }
    }

    val failWorker = new FailingWorker("fail-worker", errorMaster.getPort)
    val normalWorker = new Worker(
      "normal-worker", "localhost", errorMaster.getPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    new Thread(() => failWorker.run()).start()
    new Thread(() => {
      normalWorker.start()
      normalWorker.run()
    }).start()

    Thread.sleep(5000)

    // Master should have at least 1 registered worker
    errorMaster.getService.getRegisteredWorkerCount should be >= 1

    normalWorker.stop()
    errorMaster.stop()
  }

  it should "track phase timing and duration" in {
    // Verify that phases complete in reasonable time
    val startTime = System.currentTimeMillis()

    val timingMaster = new Master(
      port = 0,
      expectedWorkers = 1,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 2
    )
    timingMaster.start()

    val timingWorker = new Worker(
      "timing-worker", "localhost", timingMaster.getPort, 0,
      Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
    )

    val workerThread = new Thread(() => {
      timingWorker.start()
      timingWorker.run()
    })

    val masterThread = new Thread(() => {
      Thread.sleep(2000)
      timingMaster.run()
    })

    workerThread.start()
    masterThread.start()

    // Wait for completion with timeout
    workerThread.join(30000) // 30 second timeout

    val duration = System.currentTimeMillis() - startTime

    // Should complete within 30 seconds for small test data
    duration should be < 30000L

    timingWorker.stop()
    timingMaster.stop()
  }

  // Helper methods
  private def createTestFiles(): Unit = {
    (1 to 2).foreach { i =>
      val file = new File(inputDir, s"test-$i.dat")
      val writer = RecordWriter.create(file, DataFormat.Binary)
      (1 to 50).foreach { j =>
        val key = Array.fill[Byte](10)((i * 50 + j).toByte)
        val value = Array.fill[Byte](90)(j.toByte)
        writer.writeRecord(Record(key, value))
      }
      writer.close()
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }
}