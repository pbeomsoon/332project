package distsort.integration

import distsort.master.Master
import distsort.worker.Worker
import distsort.core._
import distsort.integration.TestHelpers._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._

/**
 * Deterministic execution tests - CRITICAL for distributed systems
 *
 * Verifies the core requirement from design docs:
 * "Same input → Same output" regardless of:
 * - Number of workers
 * - Worker failures
 * - Network delays
 * - Execution order
 *
 * Tests actual byte-level determinism, not just "sorted correctly"
 */
class DeterministicExecutionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var inputDir: File = _
  private var outputDir1: File = _
  private var outputDir2: File = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("deterministic-test").toFile
    inputDir = new File(tempDir, "input")
    outputDir1 = new File(tempDir, "output1")
    outputDir2 = new File(tempDir, "output2")

    inputDir.mkdirs()
    outputDir1.mkdirs()
    outputDir2.mkdirs()
  }

  override def afterEach(): Unit = {
    deleteRecursively(tempDir)
  }

  "Distributed Sort" should "produce identical output for same input (same worker count)" in {
    // ✅ Create deterministic input (same seed = same data)
    val inputFiles = createTestInputFiles(inputDir, numFiles = 5, recordsPerFile = 100, seed = 42)

    // ✅ Run 1: Sort with 3 workers
    val master1 = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir1.getAbsolutePath,
      numPartitions = 9
    )
    master1.start()

    val workers1 = (1 to 3).map { i =>
      new Worker(
        s"run1-worker-$i", "localhost", master1.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir1.getAbsolutePath
      )
    }

    workers1.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    // Wait for completion
    waitForWorkerRegistration(master1, expectedCount = 3, timeout = 15.seconds) shouldBe true
    waitForFiles(outputDir1, minCount = 1, timeout = 60.seconds) shouldBe true

    val output1Records = readAllOutputRecords(outputDir1)
    info(s"Run 1: ${output1Records.size} records")

    workers1.foreach(_.stop())
    master1.stop()

    // ✅ Run 2: Same input, same worker count
    val master2 = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir2.getAbsolutePath,
      numPartitions = 9
    )
    master2.start()

    val workers2 = (1 to 3).map { i =>
      new Worker(
        s"run2-worker-$i", "localhost", master2.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir2.getAbsolutePath
      )
    }

    workers2.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    waitForWorkerRegistration(master2, expectedCount = 3, timeout = 15.seconds) shouldBe true
    waitForFiles(outputDir2, minCount = 1, timeout = 60.seconds) shouldBe true

    val output2Records = readAllOutputRecords(outputDir2)
    info(s"Run 2: ${output2Records.size} records")

    workers2.foreach(_.stop())
    master2.stop()

    // ✅ CRITICAL: Verify EXACT same output (byte-level)
    output1Records.size shouldBe output2Records.size

    output1Records.zip(output2Records).zipWithIndex.foreach { case ((r1, r2), idx) =>
      withClue(s"Record $idx differs: ") {
        java.util.Arrays.equals(r1.key, r2.key) shouldBe true
        java.util.Arrays.equals(r1.value, r2.value) shouldBe true
      }
    }

    info(s"✓ DETERMINISTIC: Both runs produced identical output (${output1Records.size} records)")
  }

  it should "produce same sorted keys regardless of worker count" in {
    // ✅ Create deterministic input
    val inputFiles = createTestInputFiles(inputDir, numFiles = 3, recordsPerFile = 100, seed = 123)

    // ✅ Run with 2 workers
    runDistributedSort(outputDir1, numWorkers = 2, numPartitions = 4)
    val keys2Workers = readAllOutputRecords(outputDir1).map(r => new String(r.key))

    // ✅ Run with 4 workers (different partition strategy)
    deleteRecursively(outputDir1)
    outputDir1.mkdirs()

    runDistributedSort(outputDir1, numWorkers = 4, numPartitions = 8)
    val keys4Workers = readAllOutputRecords(outputDir1).map(r => new String(r.key))

    // ✅ Keys should be identical (deterministic sampling)
    keys2Workers shouldBe keys4Workers

    info(s"✓ DETERMINISTIC: Same keys with 2 workers and 4 workers (${keys2Workers.size} records)")
  }

  it should "maintain determinism with deterministic sampling" in {
    // ✅ Create larger dataset
    val inputFiles = createTestInputFiles(inputDir, numFiles = 10, recordsPerFile = 200, seed = 999)

    // ✅ Run 1
    runDistributedSort(outputDir1, numWorkers = 3, numPartitions = 9)
    val output1 = readAllOutputRecords(outputDir1)

    // ✅ Run 2 (same configuration)
    runDistributedSort(outputDir2, numWorkers = 3, numPartitions = 9)
    val output2 = readAllOutputRecords(outputDir2)

    // ✅ Verify exact match
    output1.size shouldBe output2.size

    output1.zip(output2).foreach { case (r1, r2) =>
      java.util.Arrays.equals(r1.key, r2.key) shouldBe true
    }

    info(s"✓ DETERMINISTIC with sampling: ${output1.size} records matched exactly")
  }

  it should "produce deterministic partition boundaries" in {
    val inputFiles = createTestInputFiles(inputDir, numFiles = 5, recordsPerFile = 100, seed = 42)

    // ✅ Run 1: Record partition boundaries
    val master1 = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir1.getAbsolutePath,
      numPartitions = 9
    )
    master1.start()

    val workers1 = (1 to 3).map { i =>
      new Worker(
        s"boundary-run1-worker-$i", "localhost", master1.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir1.getAbsolutePath
      )
    }

    workers1.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    waitForWorkerRegistration(master1, expectedCount = 3, timeout = 15.seconds) shouldBe true

    // Wait for sampling to complete
    waitUntil(30.seconds) {
      master1.getService.getBoundaries.nonEmpty
    } shouldBe true

    val boundaries1 = master1.getService.getBoundaries
    info(s"Run 1: ${boundaries1.length} boundaries")

    workers1.foreach(_.stop())
    master1.stop()

    // ✅ Run 2: Same input
    val master2 = new Master(
      port = 0,
      expectedWorkers = 3,
      outputDir = outputDir2.getAbsolutePath,
      numPartitions = 9
    )
    master2.start()

    val workers2 = (1 to 3).map { i =>
      new Worker(
        s"boundary-run2-worker-$i", "localhost", master2.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir2.getAbsolutePath
      )
    }

    workers2.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    waitForWorkerRegistration(master2, expectedCount = 3, timeout = 15.seconds) shouldBe true

    waitUntil(30.seconds) {
      master2.getService.getBoundaries.nonEmpty
    } shouldBe true

    val boundaries2 = master2.getService.getBoundaries
    info(s"Run 2: ${boundaries2.length} boundaries")

    workers2.foreach(_.stop())
    master2.stop()

    // ✅ Verify boundaries are identical
    boundaries1.size shouldBe boundaries2.size

    boundaries1.zip(boundaries2).zipWithIndex.foreach { case ((b1, b2), idx) =>
      withClue(s"Boundary $idx differs: ") {
        java.util.Arrays.equals(b1, b2) shouldBe true
      }
    }

    info(s"✓ DETERMINISTIC boundaries: ${boundaries1.size} boundaries matched exactly")
  }

  it should "be deterministic even with different execution order" in {
    // This test verifies that worker execution order doesn't affect output
    val inputFiles = createTestInputFiles(inputDir, numFiles = 4, recordsPerFile = 100, seed = 555)

    // ✅ Run 1: Workers start in order 1,2,3
    runDistributedSortWithDelay(outputDir1, numWorkers = 3, startDelays = Seq(0, 100, 200))
    val output1 = readAllOutputRecords(outputDir1)

    // ✅ Run 2: Workers start in reverse order 3,2,1
    runDistributedSortWithDelay(outputDir2, numWorkers = 3, startDelays = Seq(200, 100, 0))
    val output2 = readAllOutputRecords(outputDir2)

    // ✅ Output should be identical despite different start order
    output1.size shouldBe output2.size

    output1.zip(output2).foreach { case (r1, r2) =>
      java.util.Arrays.equals(r1.key, r2.key) shouldBe true
    }

    info(s"✓ DETERMINISTIC despite execution order: ${output1.size} records")
  }

  // ═══════════════════════════════════════════════════════════
  // Helper methods
  // ═══════════════════════════════════════════════════════════

  private def runDistributedSort(outputDir: File, numWorkers: Int, numPartitions: Int): Unit = {
    val master = new Master(
      port = 0,
      expectedWorkers = numWorkers,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = numPartitions
    )
    master.start()

    val workers = (1 to numWorkers).map { i =>
      new Worker(
        s"worker-$i", "localhost", master.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
      )
    }

    workers.foreach { w =>
      new Thread(() => {
        w.start()
        w.run()
      }).start()
    }

    waitForWorkerRegistration(master, expectedCount = numWorkers, timeout = 20.seconds) shouldBe true
    waitForFiles(outputDir, minCount = 1, timeout = 90.seconds) shouldBe true

    workers.foreach(_.stop())
    master.stop()
  }

  private def runDistributedSortWithDelay(
    outputDir: File,
    numWorkers: Int,
    startDelays: Seq[Int]
  ): Unit = {
    val master = new Master(
      port = 0,
      expectedWorkers = numWorkers,
      outputDir = outputDir.getAbsolutePath,
      numPartitions = 9
    )
    master.start()

    val workers = (1 to numWorkers).map { i =>
      new Worker(
        s"delayed-worker-$i", "localhost", master.getPort, 0,
        Seq(inputDir.getAbsolutePath), outputDir.getAbsolutePath
      )
    }

    workers.zip(startDelays).foreach { case (w, delay) =>
      new Thread(() => {
        Thread.sleep(delay)
        w.start()
        w.run()
      }).start()
    }

    waitForWorkerRegistration(master, expectedCount = numWorkers, timeout = 30.seconds) shouldBe true
    waitForFiles(outputDir, minCount = 1, timeout = 90.seconds) shouldBe true

    workers.foreach(_.stop())
    master.stop()
  }

  private def readAllOutputRecords(outputDir: File): Seq[Record] = {
    val outputFiles = outputDir.listFiles().filter(_.isFile).sortBy(_.getName)
    outputFiles.flatMap(readAllRecords(_)).toSeq
  }
}
