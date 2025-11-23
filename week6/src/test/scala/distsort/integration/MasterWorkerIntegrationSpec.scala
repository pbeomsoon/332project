package distsort.integration

import distsort.master.Master
import distsort.worker.Worker
import distsort.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import java.io.File
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.util.Random

/**
 * Integration test for Master-Worker gRPC communication
 */
class MasterWorkerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var master: Master = _
  private var workers: Seq[Worker] = _
  private val tempDir = Files.createTempDirectory("distsort-test").toFile
  private val inputDir = new File(tempDir, "input")
  private val outputDir = new File(tempDir, "output")

  override def beforeAll(): Unit = {
    // Create directories
    inputDir.mkdirs()
    outputDir.mkdirs()

    // Create test input files
    createTestFiles()
  }

  override def afterAll(): Unit = {
    // Stop workers and master
    if (workers != null) {
      workers.foreach(_.stop())
    }
    if (master != null) {
      master.stop()
    }

    // Cleanup temp directory
    deleteRecursively(tempDir)
  }

  "Master-Worker Integration" should "successfully register workers" in {
    // Start master
    master = new Master(
      port = 0, // Auto-assign port
      expectedWorkers = 2,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 4
    )
    master.start()
    val masterPort = master.getPort

    // Start master workflow in separate thread
    new Thread(() => {
      Thread.sleep(5000) // Wait for workers to register
      master.run()
    }).start()

    // Start workers
    workers = (1 to 2).map { i =>
      new Worker(
        workerId = s"worker-$i",
        masterHost = "localhost",
        masterPort = masterPort,
        workerPort = 0,
        inputDirs = Seq(inputDir.getAbsolutePath),
        outputDir = outputDir.getAbsolutePath
      )
    }

    // Start workers in separate threads
    val workerThreads = workers.map { worker =>
      new Thread(() => {
        worker.start()
        Thread.sleep(1000) // Let registration complete
        worker.run() // Actually start the worker workflow
      })
    }
    workerThreads.foreach(_.start())

    // Wait for registration
    Thread.sleep(3000)

    // Check that workers are registered
    master.getService.getRegisteredWorkerCount shouldBe 2
  }

  it should "complete sampling phase" in {
    // Workers should automatically start sampling after registration
    Thread.sleep(2000)

    // Check that master has received samples
    val masterService = master.getService
    masterService.waitForAllSamples(10) shouldBe true
    masterService.getBoundaries should not be empty
  }

  it should "distribute partition configuration" in {
    // Wait for partition config distribution
    Thread.sleep(2000)

    // Check that boundaries were computed
    val boundaries = master.getService.getBoundaries
    boundaries.length shouldBe 3 // numPartitions - 1
  }

  // Helper methods

  private def createTestFiles(): Unit = {
    // Create small binary test files
    val numRecords = 100
    val numFiles = 2

    (1 to numFiles).foreach { fileNum =>
      val file = new File(inputDir, s"test-$fileNum.dat")
      val writer = RecordWriter.create(file, DataFormat.Binary)

      (1 to numRecords).foreach { i =>
        val key = Array.fill[Byte](10)((i % 256).toByte)
        val value = Array.fill[Byte](90)(Random.nextInt(256).toByte)
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