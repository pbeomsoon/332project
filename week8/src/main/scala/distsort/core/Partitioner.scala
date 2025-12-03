package distsort.core

import java.io.{BufferedOutputStream, File, FileOutputStream}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Partitioner that assigns records to partitions using binary search
 *
 * Based on docs/6-parallelization.md and docs/3-grpc-sequences.md:
 * - Binary search in boundaries for partition assignment
 * - Supports N→M partition strategy (multiple partitions per worker)
 * - Boundaries define the split points between partitions
 *
 * @param boundaries Sorted array of boundary keys (length = numPartitions - 1)
 * @param numPartitions Total number of partitions
 */
class Partitioner(
  val boundaries: Seq[Array[Byte]],
  val numPartitions: Int
) {
  require(boundaries.length == numPartitions - 1,
    s"Number of boundaries (${boundaries.length}) must be numPartitions - 1 ($numPartitions - 1)")

  private var fileLayout: Option[FileLayout] = None

  /**
   * Set the file layout for creating partition files
   */
  def setFileLayout(layout: FileLayout): Unit = {
    fileLayout = Some(layout)
  }

  /**
   * Get partition ID for a given key using binary search
   *
   * Algorithm:
   * - If key < boundaries(0), partition = 0
   * - If boundaries(i-1) <= key < boundaries(i), partition = i
   * - If key >= boundaries(last), partition = numPartitions - 1
   *
   * @param key Record key to partition
   * @return Partition ID (0 to numPartitions-1)
   */
  def getPartition(key: Array[Byte]): Int = {
    if (boundaries.isEmpty) {
      return 0  // Single partition case
    }

    // Binary search for the first boundary > key
    var left = 0
    var right = boundaries.length

    while (left < right) {
      val mid = (left + right) / 2
      val cmp = ByteArrayOrdering.compare(key, boundaries(mid))

      if (cmp < 0) {
        right = mid
      } else {
        left = mid + 1
      }
    }

    left
  }

  /**
   * Partition a sequence of records into groups
   *
   * @param records Records to partition
   * @return Map from partition ID to records
   */
  def partitionRecords(records: Seq[Record]): Map[Int, Seq[Record]] = {
    val partitionBuffers = mutable.Map[Int, ArrayBuffer[Record]]()

    // Initialize buffers for all partitions
    (0 until numPartitions).foreach { id =>
      partitionBuffers(id) = ArrayBuffer[Record]()
    }

    // Assign each record to its partition
    records.foreach { record =>
      val partitionId = getPartition(record.key)
      partitionBuffers(partitionId) += record
    }

    // Convert to immutable map
    partitionBuffers.map { case (id, buffer) =>
      id -> buffer.toSeq
    }.toMap
  }

  /**
   * Partition records using streaming (memory efficient)
   *
   * @param records Iterator of records
   * @return Map from partition ID to records
   */
  def partitionRecordsStreaming(records: Iterator[Record]): Map[Int, Seq[Record]] = {
    val partitionBuffers = mutable.Map[Int, ArrayBuffer[Record]]()

    // Initialize buffers
    (0 until numPartitions).foreach { id =>
      partitionBuffers(id) = ArrayBuffer[Record]()
    }

    // Stream through records
    records.foreach { record =>
      val partitionId = getPartition(record.key)
      partitionBuffers(partitionId) += record
    }

    // Convert to immutable
    partitionBuffers.map { case (id, buffer) =>
      id -> buffer.toSeq
    }.toMap
  }

  /**
   * Create partition files from records
   *
   * @param records Records to partition and write
   * @return List of partition files (indexed by partition ID)
   */
  def createPartitionFiles(records: Seq[Record]): Seq[File] = {
    require(fileLayout.isDefined, "FileLayout must be set before creating partition files")

    val layout = fileLayout.get
    val partitionedRecords = partitionRecords(records)

    // Write each partition to file
    (0 until numPartitions).map { partitionId =>
      val file = layout.getLocalPartitionFile(partitionId)
      val recordsForPartition = partitionedRecords.getOrElse(partitionId, Seq.empty)

      writePartitionToFile(recordsForPartition, file)
      file
    }
  }

  /**
   * Create partition files from sorted chunks using K-way merge
   * (Integration with K-way merger will be added later)
   *
   * @param sortedChunks Sorted chunk files
   * @return List of partition files
   */
  def createPartitionsFromChunks(sortedChunks: Seq[File]): Seq[File] = {
    require(fileLayout.isDefined, "FileLayout must be set before creating partition files")

    val layout = fileLayout.get

    // For now, read all records and partition them
    // (K-way merger integration will optimize this)
    val allRecords = sortedChunks.flatMap { file =>
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

    createPartitionFiles(allRecords.sorted)
  }

  /**
   * Write records to a partition file
   */
  private def writePartitionToFile(records: Seq[Record], file: File): Unit = {
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
   * Get partition assignment for a worker
   * In N→M strategy, each worker gets multiple partitions
   *
   * @param workerId Worker ID
   * @param numWorkers Total number of workers
   * @return List of partition IDs assigned to this worker
   */
  def getWorkerPartitions(workerId: Int, numWorkers: Int): Seq[Int] = {
    require(workerId >= 0 && workerId < numWorkers,
      s"Worker ID $workerId must be in range [0, $numWorkers)")

    // Distribute partitions evenly among workers
    val partitionsPerWorker = numPartitions / numWorkers
    val extraPartitions = numPartitions % numWorkers

    val startPartition = workerId * partitionsPerWorker + Math.min(workerId, extraPartitions)
    val endPartition = startPartition + partitionsPerWorker + (if (workerId < extraPartitions) 1 else 0)

    (startPartition until endPartition)
  }
}

/**
 * Companion object with factory methods
 */
object Partitioner {

  /**
   * Create a partitioner from sorted sample keys
   *
   * @param sampleKeys Sorted sample keys from all workers
   * @param numPartitions Desired number of partitions
   * @return Partitioner with calculated boundaries
   */
  def fromSamples(sampleKeys: Seq[Array[Byte]], numPartitions: Int): Partitioner = {
    require(sampleKeys.nonEmpty, "Sample keys cannot be empty")
    require(numPartitions > 0, "Number of partitions must be positive")

    if (numPartitions == 1) {
      // Single partition, no boundaries needed
      new Partitioner(Seq.empty, 1)
    } else {
      // Calculate boundaries from samples
      val sortedSamples = sampleKeys.sorted(ByteArrayOrdering)
      val boundaries = calculateBoundaries(sortedSamples, numPartitions)
      new Partitioner(boundaries, numPartitions)
    }
  }

  /**
   * Calculate partition boundaries from sorted samples
   *
   * @param sortedSamples Sorted sample keys
   * @param numPartitions Number of partitions
   * @return Boundary keys
   */
  private def calculateBoundaries(sortedSamples: Seq[Array[Byte]], numPartitions: Int): Seq[Array[Byte]] = {
    val sampleCount = sortedSamples.length
    val boundaries = ArrayBuffer[Array[Byte]]()

    // Calculate boundary positions
    for (i <- 1 until numPartitions) {
      val boundaryIndex = (sampleCount * i) / numPartitions
      boundaries += sortedSamples(boundaryIndex)
    }

    boundaries.toSeq
  }

  /**
   * Create a range partitioner (for testing)
   *
   * @param minKey Minimum key value
   * @param maxKey Maximum key value
   * @param numPartitions Number of partitions
   * @return Partitioner with even ranges
   */
  def createRangePartitioner(minKey: String, maxKey: String, numPartitions: Int): Partitioner = {
    if (numPartitions == 1) {
      new Partitioner(Seq.empty, 1)
    } else {
      val minVal = minKey.toInt
      val maxVal = maxKey.toInt
      val range = (maxVal - minVal) / numPartitions

      val boundaries = (1 until numPartitions).map { i =>
        val boundaryVal = minVal + (range * i)
        val key = new Array[Byte](10)
        val bytes = boundaryVal.toString.getBytes("UTF-8")
        System.arraycopy(bytes, 0, key, 0, Math.min(bytes.length, 10))
        key
      }

      new Partitioner(boundaries, numPartitions)
    }
  }
}

/**
 * Byte array ordering for sorting and comparison
 */
object ByteArrayOrdering extends Ordering[Array[Byte]] {
  def compare(x: Array[Byte], y: Array[Byte]): Int = {
    val minLen = Math.min(x.length, y.length)
    var i = 0
    while (i < minLen) {
      val cmp = (x(i) & 0xFF) - (y(i) & 0xFF)
      if (cmp != 0) return cmp
      i += 1
    }
    x.length - y.length
  }
}