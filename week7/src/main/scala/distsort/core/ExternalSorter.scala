package distsort.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import java.util.concurrent.{Executors, ExecutorService}

/**
 * External sorting with parallelization support
 *
 * Based on docs/6-parallelization.md
 * - Creates memory-limited chunks
 * - Sorts chunks in parallel using thread pool
 * - Supports both in-memory and file-based sorting
 *
 * @param fileLayout File layout manager
 * @param memoryLimit Maximum memory per sorting operation (bytes)
 * @param numThreads Number of threads for parallel sorting
 */
class ExternalSorter(
  val fileLayout: FileLayout,
  val memoryLimit: Long = 512 * 1024 * 1024,  // 512 MB default
  val numThreads: Int = Runtime.getRuntime.availableProcessors()
) {

  private val recordSize = 100  // Each record is 100 bytes
  private val recordsPerChunk = (memoryLimit / recordSize).toInt

  // Thread pool for parallel sorting
  private val executor: ExecutorService = Executors.newFixedThreadPool(numThreads)
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  /**
   * Sort records (in-memory if possible, external sort if needed)
   */
  def sortRecords(records: Seq[Record]): Seq[Record] = {
    if (records.isEmpty) {
      return Seq.empty
    }

    val totalSize = records.length.toLong * recordSize

    if (totalSize <= memoryLimit) {
      // Can sort in memory
      records.sorted
    } else {
      // Need external sort
      val chunkFiles = createSortedChunks(records)
      // For now, just read and combine (K-way merge will be implemented separately)
      readAllChunks(chunkFiles).sorted
    }
  }

  /**
   * Create sorted chunks from records
   */
  def createSortedChunks(records: Seq[Record]): Seq[File] = {
    val chunks = records.grouped(recordsPerChunk).toSeq

    chunks.zipWithIndex.map { case (chunk, index) =>
      val sorted = chunk.sorted
      val chunkFile = fileLayout.getSortedChunkFile(index)
      writeChunkToFile(sorted, chunkFile)
      chunkFile
    }
  }

  /**
   * Create sorted chunks in parallel
   */
  def createSortedChunksParallel(records: Seq[Record]): Seq[File] = {
    val chunks = records.grouped(recordsPerChunk).toSeq

    val futures = chunks.zipWithIndex.map { case (chunk, index) =>
      Future {
        val sorted = chunk.sorted
        val chunkFile = fileLayout.getSortedChunkFile(index)
        writeChunkToFile(sorted, chunkFile)
        chunkFile
      }
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }

  /**
   * Sort files directly
   */
  def sortFiles(inputFiles: Seq[File]): Seq[File] = {
    // Read all records from files
    val allRecords = inputFiles.flatMap { file =>
      val reader = RecordReader.create(file)
      try {
        Iterator.continually(reader.readRecord())
          .takeWhile(_.isDefined)
          .map(_.get)
          .toSeq
      } finally {
        reader.close()
      }
    }

    // Create sorted chunks
    if (allRecords.isEmpty) {
      Seq.empty
    } else {
      createSortedChunksParallel(allRecords)
    }
  }

  /**
   * ⭐ Sort records from specific ranges within files
   *
   * This supports record-level distribution where each worker reads only
   * its assigned portion of each file.
   *
   * @param recordRanges Sequence of (file, startRecord, recordCount) tuples
   * @return Sorted chunk files
   */
  def sortRecordRanges(recordRanges: Seq[(File, Long, Long)]): Seq[File] = {
    import java.io.RandomAccessFile
    val RECORD_SIZE = 100

    // Read all records from the assigned ranges
    val allRecords = recordRanges.flatMap { case (file, startRecord, recordCount) =>
      try {
        val raf = new RandomAccessFile(file, "r")
        try {
          val startOffset = startRecord * RECORD_SIZE
          raf.seek(startOffset)

          val records = ArrayBuffer[Record]()
          val buffer = new Array[Byte](RECORD_SIZE)
          var recordsRead = 0L

          while (recordsRead < recordCount && raf.getFilePointer < raf.length()) {
            val bytesRead = raf.read(buffer)
            if (bytesRead == RECORD_SIZE) {
              val key = buffer.slice(0, 10).clone()
              val value = buffer.slice(10, 100).clone()
              records += Record(key, value)
              recordsRead += 1
            }
          }

          records.toSeq
        } finally {
          raf.close()
        }
      } catch {
        case ex: Exception =>
          // Fallback: read entire file if seek fails
          val reader = RecordReader.create(file)
          try {
            Iterator.continually(reader.readRecord())
              .takeWhile(_.isDefined)
              .map(_.get)
              .toSeq
          } finally {
            reader.close()
          }
      }
    }

    // Create sorted chunks
    if (allRecords.isEmpty) {
      Seq.empty
    } else {
      createSortedChunksParallel(allRecords)
    }
  }

  /**
   * Sort a single chunk
   */
  private def sortChunk(records: Seq[Record], chunkIndex: Int): File = {
    val sorted = records.sorted
    val chunkFile = fileLayout.getSortedChunkFile(chunkIndex)
    writeChunkToFile(sorted, chunkFile)
    chunkFile
  }

  /**
   * Write sorted records to file
   */
  private def writeChunkToFile(records: Seq[Record], file: File): Unit = {
    val out = new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024)
    try {
      records.foreach { record =>
        out.write(record.toBytes)
      }
    } finally {
      out.close()
    }
  }

  /**
   * Read all chunks back into memory (for testing)
   */
  private def readAllChunks(chunkFiles: Seq[File]): Seq[Record] = {
    chunkFiles.flatMap { file =>
      val reader = RecordReader.create(file)
      try {
        Iterator.continually(reader.readRecord())
          .takeWhile(_.isDefined)
          .map(_.get)
          .toSeq
      } finally {
        reader.close()
      }
    }
  }

  /**
   * Shutdown the thread pool
   */
  def shutdown(): Unit = {
    executor.shutdown()
    if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
      executor.shutdownNow()
    }
  }
}

/**
 * Companion object with factory methods
 */
object ExternalSorter {

  /**
   * Create a sorter with default settings
   */
  def apply(fileLayout: FileLayout): ExternalSorter = {
    new ExternalSorter(fileLayout)
  }

  /**
   * Create a sorter with custom memory limit
   */
  def apply(fileLayout: FileLayout, memoryLimit: Long): ExternalSorter = {
    new ExternalSorter(fileLayout, memoryLimit)
  }
}