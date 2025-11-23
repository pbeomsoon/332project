package distsort.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * K-way merger for combining multiple sorted chunks
 *
 * Based on docs/6-parallelization.md:
 * - Uses min-heap for efficient merging
 * - Supports streaming to avoid loading everything in memory
 * - Maintains sorted order across all chunks
 *
 * @param sortedChunks List of sorted chunk files to merge
 */
class KWayMerger(val sortedChunks: Seq[File]) {

  /**
   * Record with source information for heap
   */
  private case class HeapEntry(record: Record, sourceIndex: Int) extends Ordered[HeapEntry] {
    // Reverse comparison for min-heap (PriorityQueue is max-heap by default)
    override def compare(that: HeapEntry): Int = {
      -this.record.compareTo(that.record)
    }
  }

  /**
   * Merge all chunks and return as a sequence
   *
   * Note: This loads all records into memory, use streaming methods for large datasets
   *
   * @return All records in sorted order
   */
  def mergeAll(): Seq[Record] = {
    val result = ArrayBuffer[Record]()
    mergeWithCallback { record =>
      result += record
    }
    result.toSeq
  }

  /**
   * Merge chunks using a callback function
   *
   * Memory efficient - processes one record at a time
   *
   * @param callback Function to process each record in sorted order
   */
  def mergeWithCallback(callback: Record => Unit): Unit = {
    val readers = sortedChunks.map(RecordReader.create)

    try {
      // Initialize heap with first record from each chunk
      val heap = mutable.PriorityQueue[HeapEntry]()

      readers.zipWithIndex.foreach { case (reader, index) =>
        reader.readRecord() match {
          case Some(record) => heap.enqueue(HeapEntry(record, index))
          case None => // Empty chunk, skip
        }
      }

      // Process records in sorted order
      while (heap.nonEmpty) {
        val entry = heap.dequeue()
        callback(entry.record)

        // Read next record from the same source
        readers(entry.sourceIndex).readRecord() match {
          case Some(nextRecord) =>
            heap.enqueue(HeapEntry(nextRecord, entry.sourceIndex))
          case None =>
            // Source exhausted, continue with remaining sources
        }
      }
    } finally {
      readers.foreach(_.close())
    }
  }

  /**
   * Get an iterator for streaming merge
   *
   * @return Iterator that yields records in sorted order
   */
  def mergeIterator: Iterator[Record] = {
    new Iterator[Record] {
      private val readers = sortedChunks.map(RecordReader.create)
      private val heap = mutable.PriorityQueue[HeapEntry]()
      private var closed = false

      // Initialize heap
      readers.zipWithIndex.foreach { case (reader, index) =>
        reader.readRecord() match {
          case Some(record) => heap.enqueue(HeapEntry(record, index))
          case None => // Empty chunk
        }
      }

      override def hasNext: Boolean = {
        if (!closed && heap.isEmpty) {
          closeReaders()
        }
        heap.nonEmpty
      }

      override def next(): Record = {
        if (!hasNext) {
          throw new NoSuchElementException("No more records")
        }

        val entry = heap.dequeue()
        val record = entry.record

        // Read next record from the same source
        readers(entry.sourceIndex).readRecord() match {
          case Some(nextRecord) =>
            heap.enqueue(HeapEntry(nextRecord, entry.sourceIndex))
          case None =>
            // Source exhausted
            if (heap.isEmpty) {
              closeReaders()
            }
        }

        record
      }

      private def closeReaders(): Unit = {
        if (!closed) {
          readers.foreach(_.close())
          closed = true
        }
      }

      // Ensure cleanup on garbage collection
      override def finalize(): Unit = {
        closeReaders()
        super.finalize()
      }
    }
  }

  /**
   * Merge all chunks and write to output file
   *
   * @param outputFile Target file for merged output
   */
  def mergeToFile(outputFile: File): Unit = {
    val out = new BufferedOutputStream(new FileOutputStream(outputFile), 1024 * 1024)
    try {
      mergeWithCallback { record =>
        out.write(record.toBytes)
      }
    } finally {
      out.close()
    }
  }

  /**
   * Merge chunks and partition simultaneously
   *
   * @param partitioner Partitioner to use for assigning records
   * @return Map from partition ID to records
   */
  def mergeAndPartition(partitioner: Partitioner): Map[Int, Seq[Record]] = {
    val partitionBuffers = mutable.Map[Int, ArrayBuffer[Record]]()

    // Initialize buffers
    (0 until partitioner.numPartitions).foreach { id =>
      partitionBuffers(id) = ArrayBuffer[Record]()
    }

    // Merge and partition
    mergeWithCallback { record =>
      val partitionId = partitioner.getPartition(record.key)
      partitionBuffers(partitionId) += record
    }

    // Convert to immutable
    partitionBuffers.map { case (id, buffer) =>
      id -> buffer.toSeq
    }.toMap
  }

  /**
   * Merge chunks and write to partition files
   *
   * @param partitioner Partitioner with file layout
   * @return List of partition files created
   */
  def mergeToPartitionFiles(partitioner: Partitioner): Seq[File] = {
    // Create writers for each partition
    val writers = mutable.Map[Int, BufferedOutputStream]()
    val files = mutable.Map[Int, File]()

    try {
      // Merge and write to partitions
      mergeWithCallback { record =>
        val partitionId = partitioner.getPartition(record.key)

        // Lazy initialization of writers
        if (!writers.contains(partitionId)) {
          val file = new File(s"partition-$partitionId.dat")  // Temp name, should use FileLayout
          files(partitionId) = file
          writers(partitionId) = new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024)
        }

        writers(partitionId).write(record.toBytes)
      }

      // Return files in order
      (0 until partitioner.numPartitions).map { id =>
        files.getOrElse(id, {
          // Create empty file for partitions with no records
          val emptyFile = new File(s"partition-$id.dat")
          emptyFile.createNewFile()
          emptyFile
        })
      }
    } finally {
      // Close all writers
      writers.values.foreach(_.close())
    }
  }

  /**
   * Get total number of records across all chunks
   *
   * @return Total record count
   */
  def getTotalRecordCount: Long = {
    sortedChunks.map { file =>
      val fileSize = file.length()
      fileSize / 100  // Each record is 100 bytes
    }.sum
  }

  /**
   * Estimate memory required for full merge
   *
   * @return Estimated memory in bytes
   */
  def estimateMemoryRequired: Long = {
    getTotalRecordCount * 100  // 100 bytes per record
  }
}

/**
 * Companion object with factory methods
 */
object KWayMerger {

  /**
   * Create a merger from file paths
   *
   * @param paths Paths to sorted chunk files
   * @return KWayMerger instance
   */
  def fromPaths(paths: Seq[String]): KWayMerger = {
    new KWayMerger(paths.map(new File(_)))
  }

  /**
   * Merge files directly to output
   *
   * @param inputFiles Sorted input files
   * @param outputFile Target output file
   */
  def mergeFiles(inputFiles: Seq[File], outputFile: File): Unit = {
    val merger = new KWayMerger(inputFiles)
    merger.mergeToFile(outputFile)
  }

  /**
   * Check if files are sorted (for validation)
   *
   * @param file File to check
   * @return True if file is sorted
   */
  def isSorted(file: File): Boolean = {
    val reader = RecordReader.create(file)
    try {
      var prevRecord: Option[Record] = None

      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .foreach { record =>
          prevRecord match {
            case Some(prev) if prev.compareTo(record) > 0 =>
              return false
            case _ =>
          }
          prevRecord = Some(record)
        }

      true
    } finally {
      reader.close()
    }
  }
}