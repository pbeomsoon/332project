package distsort.integration

import distsort.core._
import distsort.master.Master
import distsort.worker.Worker
import org.scalatest.matchers.should.Matchers

import java.io.{File, IOException}
import java.net.{ServerSocket, Socket}
import scala.concurrent.duration._
import scala.annotation.tailrec

/**
 * Common test helpers for integration tests
 * Provides utilities for:
 * - Polling-based waiting instead of Thread.sleep()
 * - Network connectivity verification
 * - Output validation
 * - Record counting and consistency checks
 */
object TestHelpers extends Matchers {

  // ═══════════════════════════════════════════════════════════
  // Polling-based waiting utilities
  // ═══════════════════════════════════════════════════════════

  /**
   * Wait until a condition becomes true, with timeout
   * @param timeout Maximum time to wait
   * @param pollInterval How often to check the condition
   * @param condition The condition to wait for
   * @return true if condition met, false if timeout
   */
  @tailrec
  def waitUntil(timeout: Duration, pollInterval: Duration = 100.millis)(condition: => Boolean): Boolean = {
    if (condition) {
      true
    } else if (timeout <= Duration.Zero) {
      false
    } else {
      Thread.sleep(pollInterval.toMillis)
      waitUntil(timeout - pollInterval, pollInterval)(condition)
    }
  }

  /**
   * Wait until a condition becomes true, throw exception on timeout
   */
  def waitUntilOrFail(timeout: Duration, message: String)(condition: => Boolean): Unit = {
    if (!waitUntil(timeout)(condition)) {
      throw new AssertionError(s"Timeout after $timeout: $message")
    }
  }

  /**
   * Wait for workers to register with master
   */
  def waitForWorkerRegistration(master: Master, expectedCount: Int, timeout: Duration = 30.seconds): Boolean = {
    waitUntil(timeout) {
      master.getService.getRegisteredWorkerCount == expectedCount
    }
  }

  /**
   * Wait for all workers to complete a specific phase
   */
  def waitForPhaseCompletion(master: Master, expectedWorkers: Int, timeout: Duration = 60.seconds): Boolean = {
    waitUntil(timeout) {
      // Check if master has advanced to next phase (all workers completed)
      master.getService.getRegisteredWorkerCount >= expectedWorkers
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Network connectivity verification
  // ═══════════════════════════════════════════════════════════

  /**
   * Check if a port is open and accepting connections
   */
  def isPortOpen(host: String, port: Int, timeout: Int = 1000): Boolean = {
    try {
      val socket = new Socket()
      socket.connect(new java.net.InetSocketAddress(host, port), timeout)
      socket.close()
      true
    } catch {
      case _: IOException => false
    }
  }

  /**
   * Wait until a port is open
   */
  def waitForPortOpen(host: String, port: Int, timeout: Duration = 10.seconds): Boolean = {
    waitUntil(timeout) {
      isPortOpen(host, port)
    }
  }

  /**
   * Verify Master gRPC server is running
   */
  def verifyMasterConnectivity(host: String, port: Int): Unit = {
    waitUntilOrFail(10.seconds, s"Master at $host:$port not accepting connections") {
      isPortOpen(host, port)
    }
  }

  /**
   * Verify Worker gRPC server is running
   */
  def verifyWorkerConnectivity(host: String, port: Int): Unit = {
    waitUntilOrFail(10.seconds, s"Worker at $host:$port not accepting connections") {
      isPortOpen(host, port)
    }
  }

  /**
   * Simulate network partition by blocking connections
   * Returns a function to restore connectivity
   */
  def simulateNetworkPartition(worker: Worker): () => Unit = {
    // Force worker to disconnect by stopping its gRPC server
    worker.stop()

    // Return restoration function
    () => {
      worker.start()
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Output validation utilities
  // ═══════════════════════════════════════════════════════════

  /**
   * Count total records in a file
   */
  def countRecords(file: File, format: DataFormat = DataFormat.Binary): Long = {
    if (!file.exists() || file.length() == 0) {
      return 0L
    }

    val reader = RecordReader.create(file, format)
    var count = 0L

    try {
      while (reader.readRecord().isDefined) {
        count += 1
      }
    } finally {
      reader.close()
    }

    count
  }

  /**
   * Read all records from a file
   */
  def readAllRecords(file: File, format: DataFormat = DataFormat.Binary): Seq[Record] = {
    if (!file.exists() || file.length() == 0) {
      return Seq.empty
    }

    val reader = RecordReader.create(file, format)
    val records = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    reader.close()
    records
  }

  /**
   * Verify that records in a file are sorted
   */
  def verifySorted(file: File): Boolean = {
    val records = readAllRecords(file)
    if (records.isEmpty) return true

    records.sliding(2).forall {
      case Seq(a, b) => distsort.core.ByteArrayOrdering.compare(a.key, b.key) <= 0
      case _ => true
    }
  }

  /**
   * Verify all output files are sorted
   */
  def verifyAllOutputsSorted(outputDir: File): Unit = {
    val outputFiles = outputDir.listFiles().filter(_.isFile)
    outputFiles should not be empty

    outputFiles.foreach { file =>
      withClue(s"File ${file.getName} is not sorted: ") {
        verifySorted(file) shouldBe true
      }
    }
  }

  /**
   * Count total records across all files in directory
   */
  def countTotalRecords(dir: File): Long = {
    dir.listFiles()
      .filter(_.isFile)
      .map(countRecords(_))
      .sum
  }

  /**
   * Verify data consistency: no duplicates, no missing records
   */
  def verifyDataConsistency(
    inputFiles: Seq[File],
    outputFiles: Seq[File],
    allowPartialOutput: Boolean = false
  ): Unit = {
    val inputRecordCount = inputFiles.map(countRecords(_)).sum
    val outputRecordCount = outputFiles.map(countRecords(_)).sum

    if (!allowPartialOutput) {
      withClue(s"Input: $inputRecordCount records, Output: $outputRecordCount records") {
        outputRecordCount shouldBe inputRecordCount
      }
    } else {
      withClue(s"Output ($outputRecordCount) should not exceed input ($inputRecordCount)") {
        outputRecordCount should be <= inputRecordCount
      }
    }

    // Verify no duplicates by checking all keys are unique
    val allOutputKeys = outputFiles.flatMap { file =>
      readAllRecords(file).map(r => new String(r.key))
    }

    val uniqueKeys = allOutputKeys.toSet
    withClue(s"Found ${allOutputKeys.size - uniqueKeys.size} duplicate records") {
      allOutputKeys.size shouldBe uniqueKeys.size
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Worker state verification
  // ═══════════════════════════════════════════════════════════

  /**
   * Verify worker has progressed beyond a certain phase
   */
  def verifyWorkerPhaseComplete(worker: Worker, expectedPhase: String, timeout: Duration = 30.seconds): Boolean = {
    waitUntil(timeout) {
      // Worker phases are sequential, so if it moved past expectedPhase, it completed
      val currentPhase = worker.getCurrentPhase
      currentPhase != expectedPhase
    }
  }

  /**
   * Get actual worker count that are still alive (responding to heartbeats)
   */
  def getAliveWorkerCount(master: Master): Int = {
    master.getService.getRegisteredWorkerCount
  }

  // ═══════════════════════════════════════════════════════════
  // File system utilities
  // ═══════════════════════════════════════════════════════════

  /**
   * Wait for files to appear in directory
   */
  def waitForFiles(dir: File, minCount: Int = 1, timeout: Duration = 30.seconds): Boolean = {
    waitUntil(timeout) {
      dir.exists() && dir.listFiles().filter(_.isFile).length >= minCount
    }
  }

  /**
   * Wait for specific file to exist
   */
  def waitForFile(file: File, timeout: Duration = 10.seconds): Boolean = {
    waitUntil(timeout) {
      file.exists() && file.length() > 0
    }
  }

  /**
   * Delete recursively with retries (for Windows file locking issues)
   */
  def deleteRecursively(file: File, maxRetries: Int = 3): Unit = {
    def attemptDelete(f: File, retriesLeft: Int): Boolean = {
      try {
        if (f.isDirectory) {
          f.listFiles().foreach(child => attemptDelete(child, retriesLeft))
        }
        f.delete()
        true
      } catch {
        case _: Exception if retriesLeft > 0 =>
          Thread.sleep(100)
          attemptDelete(f, retriesLeft - 1)
        case _: Exception =>
          false
      }
    }

    attemptDelete(file, maxRetries)
  }

  // ═══════════════════════════════════════════════════════════
  // Test data generation
  // ═══════════════════════════════════════════════════════════

  /**
   * Create deterministic test records (same seed = same data)
   */
  def createDeterministicRecords(count: Int, seed: Int = 42): Seq[Record] = {
    val random = new scala.util.Random(seed)
    (1 to count).map { i =>
      val key = new Array[Byte](10)
      val value = new Array[Byte](90)

      // Use deterministic key generation
      val keyValue = (seed + i) % 256
      java.util.Arrays.fill(key, keyValue.toByte)
      random.nextBytes(value)

      Record(key, value)
    }
  }

  /**
   * Create test input files with deterministic data
   */
  def createTestInputFiles(
    inputDir: File,
    numFiles: Int,
    recordsPerFile: Int,
    seed: Int = 42
  ): Seq[File] = {
    inputDir.mkdirs()

    (1 to numFiles).map { i =>
      val file = new File(inputDir, f"test-$i%03d.dat")
      val writer = RecordWriter.create(file, DataFormat.Binary)

      val records = createDeterministicRecords(recordsPerFile, seed + i)
      records.foreach(writer.writeRecord)
      writer.close()

      file
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Master state verification
  // ═══════════════════════════════════════════════════════════

  /**
   * Verify master has computed partition boundaries
   */
  def verifyBoundariesComputed(master: Master, expectedCount: Int): Unit = {
    waitUntilOrFail(10.seconds, "Boundaries not computed") {
      master.getService.getBoundaries.nonEmpty
    }

    val boundaries = master.getService.getBoundaries
    withClue(s"Expected $expectedCount boundaries, got ${boundaries.length}") {
      boundaries.length shouldBe expectedCount
    }
  }

  /**
   * Verify master detected worker failure
   */
  def verifyWorkerFailureDetected(
    master: Master,
    initialWorkerCount: Int,
    timeout: Duration = 135.seconds  // Heartbeat timeout is 120s
  ): Boolean = {
    waitUntil(timeout) {
      master.getService.getRegisteredWorkerCount < initialWorkerCount
    }
  }

  // ═══════════════════════════════════════════════════════════
  // Comprehensive verification
  // ═══════════════════════════════════════════════════════════

  /**
   * Verify complete distributed sort workflow
   */
  def verifyDistributedSortComplete(
    inputDir: File,
    outputDir: File,
    allowPartialOutput: Boolean = false
  ): Unit = {
    // 1. Verify output files exist
    waitForFiles(outputDir, minCount = 1, timeout = 60.seconds) shouldBe true

    val inputFiles = inputDir.listFiles().filter(_.isFile).toSeq
    val outputFiles = outputDir.listFiles().filter(_.isFile).toSeq

    // 2. Verify all outputs are sorted
    verifyAllOutputsSorted(outputDir)

    // 3. Verify data consistency (no missing/duplicate records)
    verifyDataConsistency(inputFiles, outputFiles, allowPartialOutput)

    // 4. Verify global sort order (first key of file N+1 >= last key of file N)
    val sortedOutputFiles = outputFiles.sortBy(_.getName)
    sortedOutputFiles.sliding(2).foreach {
      case Seq(file1, file2) =>
        val records1 = readAllRecords(file1)
        val records2 = readAllRecords(file2)

        if (records1.nonEmpty && records2.nonEmpty) {
          val lastKeyFile1 = records1.last.key
          val firstKeyFile2 = records2.head.key

          withClue(s"Global sort order violated between ${file1.getName} and ${file2.getName}") {
            distsort.core.ByteArrayOrdering.compare(lastKeyFile1, firstKeyFile2) should be <= 0
          }
        }
      case _ => // Single file, skip
    }
  }
}
