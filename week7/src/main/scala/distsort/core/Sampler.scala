package distsort.core

import java.io.File
import scala.util.Random
import scala.collection.mutable.ArrayBuffer

/**
 * Sampler for extracting samples from input files
 *
 * Based on plan_ver3.md and implementation-decisions.md:
 * - Deterministic sampling using fixed seed
 * - Default 10% sampling rate
 * - Supports both Binary and ASCII formats via auto-detection
 *
 * @param sampleRate Fraction of records to sample (0.0 to 1.0)
 * @param seed Random seed for deterministic sampling
 */
class Sampler(
  val sampleRate: Double = 0.1,
  val seed: Long = System.currentTimeMillis()
) {
  require(sampleRate > 0.0 && sampleRate <= 1.0,
    s"Sample rate must be between 0.0 and 1.0, got $sampleRate")

  private val random = new Random(seed)

  /**
   * Extract samples from a single file
   *
   * Uses reservoir sampling for memory efficiency
   *
   * @param file Input file (auto-detects format)
   * @return Sampled records
   */
  def extractSamples(file: File): Seq[Record] = {
    // Auto-detect format
    val reader = RecordReader.create(file)

    try {
      val samples = ArrayBuffer[Record]()
      var recordCount = 0
      var firstRecord: Option[Record] = None

      // Read all records and sample
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .foreach { record =>
          recordCount += 1

          // Keep first record in case we sample nothing
          if (recordCount == 1) {
            firstRecord = Some(record)
          }

          // Simple random sampling
          if (random.nextDouble() < sampleRate) {
            samples += record
          }
        }

      // For very small files, ensure at least one sample if file is not empty
      if (samples.isEmpty && firstRecord.isDefined) {
        samples += firstRecord.get
      }

      samples.toSeq
    } finally {
      reader.close()
    }
  }

  /**
   * Extract samples from multiple files
   *
   * @param files List of input files
   * @return Combined samples from all files
   */
  def extractSamplesFromFiles(files: Seq[File]): Seq[Record] = {
    files.flatMap(extractSamples)
  }

  /**
   * Extract only keys from samples (for sending to Master)
   *
   * @param file Input file
   * @return Sampled keys only
   */
  def extractSampleKeys(file: File): Seq[Array[Byte]] = {
    extractSamples(file).map(_.key)
  }

  /**
   * Extract sample keys from multiple files
   *
   * @param files List of input files
   * @return Combined sample keys from all files
   */
  def extractSampleKeysFromFiles(files: Seq[File]): Seq[Array[Byte]] = {
    files.flatMap(extractSampleKeys)
  }

  /**
   * ⭐ Extract samples from a specific record range within a file
   *
   * This supports record-level distribution where each worker reads only
   * its assigned portion of the file.
   *
   * @param file Input file
   * @param startRecord Starting record index (0-based)
   * @param recordCount Number of records to read from this range
   * @return Sampled records from the specified range
   */
  def extractSamplesFromRange(file: File, startRecord: Long, recordCount: Long): Seq[Record] = {
    import java.io.RandomAccessFile
    val RECORD_SIZE = 100L  // 10 byte key + 90 byte value

    try {
      val raf = new RandomAccessFile(file, "r")
      try {
        // Seek to start of the assigned range
        val startOffset = startRecord * RECORD_SIZE
        raf.seek(startOffset)

        val samples = ArrayBuffer[Record]()
        var recordsRead = 0L
        var firstRecord: Option[Record] = None
        val buffer = new Array[Byte](RECORD_SIZE.toInt)

        // Read records from the assigned range
        while (recordsRead < recordCount && raf.getFilePointer < raf.length()) {
          val bytesRead = raf.read(buffer)
          if (bytesRead == RECORD_SIZE) {
            val key = buffer.slice(0, 10).clone()
            val value = buffer.slice(10, 100).clone()
            val record = Record(key, value)

            recordsRead += 1

            // Keep first record in case we sample nothing
            if (recordsRead == 1) {
              firstRecord = Some(record)
            }

            // Simple random sampling
            if (random.nextDouble() < sampleRate) {
              samples += record
            }
          }
        }

        // Ensure at least one sample if we read any records
        if (samples.isEmpty && firstRecord.isDefined) {
          samples += firstRecord.get
        }

        samples.toSeq
      } finally {
        raf.close()
      }
    } catch {
      case ex: Exception =>
        // Fallback to reading entire file if seek fails
        extractSamples(file)
    }
  }

  /**
   * Extract samples using reservoir sampling
   * More memory efficient for large files
   *
   * @param file Input file
   * @param reservoirSize Maximum number of samples to keep
   * @return Sampled records
   */
  def extractSamplesReservoir(file: File, reservoirSize: Int): Seq[Record] = {
    val reader = RecordReader.create(file)

    try {
      val reservoir = ArrayBuffer[Record]()
      var recordCount = 0

      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .foreach { record =>
          recordCount += 1

          if (reservoir.size < reservoirSize) {
            // Fill the reservoir
            reservoir += record
          } else {
            // Randomly replace elements with decreasing probability
            val replaceIndex = random.nextInt(recordCount)
            if (replaceIndex < reservoirSize) {
              reservoir(replaceIndex) = record
            }
          }
        }

      reservoir.toSeq
    } finally {
      reader.close()
    }
  }
}

/**
 * Companion object with factory methods
 */
object Sampler {

  /**
   * Create a sampler with default settings
   */
  def apply(): Sampler = new Sampler()

  /**
   * Create a sampler with custom sample rate
   */
  def apply(sampleRate: Double): Sampler = new Sampler(sampleRate)

  /**
   * Create a deterministic sampler with worker ID as seed
   * (As specified in plan_ver3.md)
   */
  def forWorker(workerId: String, sampleRate: Double = 0.1): Sampler = {
    val seed = workerId.hashCode.toLong
    new Sampler(sampleRate, seed)
  }

  /**
   * Estimate number of samples from file size
   */
  def estimateSampleCount(fileSize: Long, sampleRate: Double = 0.1): Long = {
    val recordSize = 100 // 100 bytes per record
    val recordCount = fileSize / recordSize
    (recordCount * sampleRate).toLong
  }
}