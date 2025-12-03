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
    import java.io.{BufferedInputStream, FileInputStream}
    val RECORD_SIZE = 100
    val BUFFER_SIZE = 8 * 1024 * 1024  // 8MB buffer for fast NFS reads

    System.err.println(s"[ExternalSorter] sortRecordRanges: ${recordRanges.size} ranges (parallel)")

    // ⭐ PARALLEL file reading using thread pool
    import scala.concurrent.{Future, Await}
    import scala.concurrent.duration._

    val futures = recordRanges.map { case (file, startRecord, recordCount) =>
      Future {
        try {
          val fis = new FileInputStream(file)
          val bis = new BufferedInputStream(fis, BUFFER_SIZE)

          try {
            // Skip to start position
            val skipBytes = startRecord * RECORD_SIZE
            if (skipBytes > 0) {
              bis.skip(skipBytes)
            }

            // ⭐ Pre-allocate ArrayBuffer to avoid reallocation
            val records = new ArrayBuffer[Record](recordCount.toInt)

            // ⭐ Bulk read: read multiple records at once (10000 records = 1MB)
            val BULK_SIZE = 10000
            val bulkBuffer = new Array[Byte](RECORD_SIZE * BULK_SIZE)
            var totalRead = 0L

            while (totalRead < recordCount) {
              val toRead = math.min(BULK_SIZE, (recordCount - totalRead).toInt)
              val bytesToRead = toRead * RECORD_SIZE
              val bytesRead = bis.read(bulkBuffer, 0, bytesToRead)

              if (bytesRead <= 0) {
                totalRead = recordCount  // EOF
              } else {
                val recordsInBatch = bytesRead / RECORD_SIZE
                var i = 0
                while (i < recordsInBatch) {
                  val offset = i * RECORD_SIZE
                  // ⭐ Use System.arraycopy instead of slice().clone()
                  val key = new Array[Byte](10)
                  val value = new Array[Byte](90)
                  System.arraycopy(bulkBuffer, offset, key, 0, 10)
                  System.arraycopy(bulkBuffer, offset + 10, value, 0, 90)
                  records += Record(key, value)
                  i += 1
                }
                totalRead += recordsInBatch
              }
            }

            System.err.println(s"[ExternalSorter] ✅ ${file.getName}: ${records.size} records")
            records.toSeq
          } finally {
            bis.close()
            fis.close()
          }
        } catch {
          case ex: Exception =>
            System.err.println(s"[ExternalSorter] ❌ ${file.getName}: ${ex.getMessage}")
            Seq.empty
        }
      }(ec)
    }

    // Wait for all files to be read (parallel)
    val allRecords = Await.result(Future.sequence(futures), 30.minutes).flatten

    System.err.println(s"[ExternalSorter] Total records read: ${allRecords.size}")

    // Create sorted chunks
    if (allRecords.isEmpty) {
      System.err.println(s"[ExternalSorter] WARNING: No records to sort!")
      Seq.empty
    } else {
      // ⭐ Use Arrays.parallelSort for multi-core sorting (much faster than .sorted)
      val startSort = System.currentTimeMillis()
      val recordArray = allRecords.toArray
      java.util.Arrays.parallelSort(recordArray, Record.comparator)
      val sortTime = System.currentTimeMillis() - startSort
      System.err.println(s"[ExternalSorter] ⚡ parallelSort completed: ${recordArray.length} records in ${sortTime}ms")

      // Write sorted records to chunk files
      val chunks = writeChunksParallel(recordArray)
      System.err.println(s"[ExternalSorter] Created ${chunks.size} sorted chunks")
      chunks
    }
  }

  /**
   * Write pre-sorted records to chunk files (sequential, bulk write)
   * ⭐ 순차 처리 + bulk write로 안정성과 성능 확보
   */
  private def writeChunksParallel(sortedRecords: Array[Record]): Seq[File] = {
    val startWrite = System.currentTimeMillis()
    System.err.println(s"[ExternalSorter] Writing ${sortedRecords.length} records to chunks...")

    val chunkFiles = ArrayBuffer[File]()
    var offset = 0
    var chunkIndex = 0

    while (offset < sortedRecords.length) {
      val end = math.min(offset + recordsPerChunk, sortedRecords.length)
      val chunkFile = fileLayout.getSortedChunkFile(chunkIndex)

      // ⭐ Bulk write: 배열 슬라이스를 한번에 쓰기
      writeChunkBulk(sortedRecords, offset, end - offset, chunkFile)

      chunkFiles += chunkFile
      offset = end
      chunkIndex += 1
    }

    val writeTime = System.currentTimeMillis() - startWrite
    System.err.println(s"[ExternalSorter] 📝 Written ${chunkFiles.size} chunks in ${writeTime}ms")
    chunkFiles.toSeq
  }

  /**
   * Bulk write records to file (no per-record allocation)
   * ⭐ 10000 레코드(1MB)씩 버퍼에 모아서 한번에 write
   */
  private def writeChunkBulk(records: Array[Record], offset: Int, count: Int, file: File): Unit = {
    val BULK_SIZE = 10000  // 10000 records = 1MB
    val buffer = new Array[Byte](BULK_SIZE * recordSize)
    val out = new BufferedOutputStream(new FileOutputStream(file), 8 * 1024 * 1024)  // 8MB buffer

    try {
      var i = 0
      while (i < count) {
        val batchEnd = math.min(i + BULK_SIZE, count)
        val batchSize = batchEnd - i

        // Fill buffer with records
        var j = 0
        while (j < batchSize) {
          val record = records(offset + i + j)
          System.arraycopy(record.key, 0, buffer, j * recordSize, 10)
          System.arraycopy(record.value, 0, buffer, j * recordSize + 10, 90)
          j += 1
        }

        // Single write for entire batch
        out.write(buffer, 0, batchSize * recordSize)
        i += batchSize
      }
    } finally {
      out.close()
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