# Parallelization Strategy (병렬 처리 전략)

**문서 목적**: Multi-core 프로세서를 활용한 병렬 처리 전략 및 구현 상세

**참조 문서**:
- `0-implementation-decisions.md` - 병렬 처리 결정 사항
- `2025-10-24_plan_ver3.md` - 전체 시스템 설계
- `3-grpc-sequences.md` - Phase별 워크플로우

---

## 1. 병렬 처리 개요

### 1.1 PDF 요구사항

> "Utilize multi-core processors for parallel processing"

### 1.2 병렬화 전략

```
┌────────────────────────────────────────────────────────┐
│ Parallelization Points                                 │
├────────────────────────────────────────────────────────┤
│ Phase 1 (Registration):   Sequential (coordination)    │
│ Phase 2 (Sampling):       Parallel file reading       │
│ Phase 3 (Sorting):        ✅ Parallel chunk sorting    │
│ Phase 4 (Partitioning):   ✅ Parallel partition write  │
│ Phase 5 (Shuffle):        ✅ Concurrent transfers      │
│ Phase 6 (Merge):          ✅ Parallel K-way merge      │
└────────────────────────────────────────────────────────┘
```

### 1.3 핵심 설계 결정: N→M Strategy for Merge Parallelization

**문제 상황**:
- 만약 각 Worker가 1개 파티션만 담당 (N workers → N partitions):
  - Merge 단계에서 각 Worker는 1개 파티션만 병합
  - **단일 코어만 사용** (멀티코어 활용 불가)
  - 병목 현상 발생

**해결 방법**:
- **각 Worker에 여러 개의 연속된 파티션 할당** (N workers → M partitions, M > N):
  - 예: 3 workers, 9 partitions → 각 Worker가 3개 연속 파티션 담당
  - Worker 0: partition.0, 1, 2
  - Worker 1: partition.3, 4, 5
  - Worker 2: partition.6, 7, 8
  - **Merge 단계에서 3개 파티션을 병렬로 처리** (멀티코어 활용)

**이점**:
- ✅ Merge 단계 멀티코어 활용 → 성능 향상
- ✅ 로드 밸런싱 개선
- ✅ 파티션 크기 불균형 완화

**구현**:
- Range-based partition assignment (plan_ver3.md 참조)
- `workerID = partitionID / (numPartitions / numWorkers)`
- 자동으로 연속된 파티션 번호가 같은 Worker에 할당됨

---

## 2. Thread Pool Configuration

### 2.1 기본 설정

```scala
package distsort.config

import java.util.concurrent.{Executors, ExecutorService, ThreadPoolExecutor}
import scala.concurrent.ExecutionContext

object ThreadPoolConfig {
  // System information
  private val runtime = Runtime.getRuntime
  val numCores: Int = runtime.availableProcessors()
  val maxMemory: Long = runtime.maxMemory()

  logger.info(s"System: $numCores cores, ${maxMemory / (1024 * 1024 * 1024)}GB max memory")

  // Thread pool sizes
  val sortingThreads: Int = numCores
  val shuffleThreads: Int = math.min(numCores * 2, 10)  // I/O-bound
  val mergeThreads: Int = (numCores * 1.5).toInt         // Mixed workload

  // Concurrent operation limits
  val maxConcurrentTransfers: Int = 5  // Network bandwidth 고려
  val maxConcurrentSorts: Int = numCores
  val maxConcurrentMerges: Int = math.min(numCores, 4)

  // Create thread pools
  def createSortingExecutor(): ExecutorService = {
    Executors.newFixedThreadPool(
      sortingThreads,
      new ThreadFactoryBuilder()
        .setNameFormat("sorting-thread-%d")
        .setDaemon(false)
        .build()
    )
  }

  def createShuffleExecutor(): ExecutorService = {
    Executors.newFixedThreadPool(
      shuffleThreads,
      new ThreadFactoryBuilder()
        .setNameFormat("shuffle-thread-%d")
        .setDaemon(false)
        .build()
    )
  }

  def createMergeExecutor(): ExecutorService = {
    Executors.newFixedThreadPool(
      mergeThreads,
      new ThreadFactoryBuilder()
        .setNameFormat("merge-thread-%d")
        .setDaemon(false)
        .build()
    )
  }
}
```

### 2.2 동적 조정

```scala
class AdaptiveThreadPoolManager {
  private var currentSortingThreads = ThreadPoolConfig.sortingThreads

  def adjustThreadPoolSize(phase: WorkerPhase, cpuUsage: Double, memoryUsage: Double): Unit = {
    phase match {
      case WorkerPhase.PHASE_SORTING =>
        if (memoryUsage > 0.8) {
          // 메모리 부족 → thread 수 줄이기
          currentSortingThreads = math.max(1, currentSortingThreads / 2)
          logger.warn(s"Reduced sorting threads to $currentSortingThreads due to high memory usage")
        } else if (cpuUsage < 0.5 && memoryUsage < 0.6) {
          // 여유 있음 → thread 수 늘리기
          currentSortingThreads = math.min(ThreadPoolConfig.numCores, currentSortingThreads * 2)
          logger.info(s"Increased sorting threads to $currentSortingThreads")
        }

      case _ =>
        // Other phases: static configuration
    }
  }
}
```

---

## 3. Phase 3: Parallel Sorting

### 3.1 Chunk 생성 및 병렬 정렬

```scala
class ExternalSorter(
  tempDir: File,
  memoryLimit: Long = 512 * 1024 * 1024  // 512 MB default
) {
  private val executor = ThreadPoolConfig.createSortingExecutor()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  // Records per chunk (동적 계산)
  private val recordSize = 100
  private val recordsPerChunk = (memoryLimit / recordSize / ThreadPoolConfig.sortingThreads).toInt

  def sortInParallel(inputFiles: Seq[File]): Seq[File] = {
    logger.info(s"Starting parallel external sort with ${ThreadPoolConfig.sortingThreads} threads")
    logger.info(s"Records per chunk: $recordsPerChunk")

    // Step 1: Read and create chunks
    val chunks = createChunks(inputFiles)
    logger.info(s"Created ${chunks.size} chunks")

    // Step 2: Sort chunks in parallel
    val sortedChunkFutures = chunks.zipWithIndex.map { case (chunk, index) =>
      Future {
        sortChunk(chunk, index)
      }
    }

    // Wait for all sorting to complete
    val sortedChunks = Await.result(Future.sequence(sortedChunkFutures), Duration.Inf)

    logger.info(s"Parallel sorting completed: ${sortedChunks.size} sorted chunks")

    sortedChunks
  }

  private def createChunks(inputFiles: Seq[File]): Seq[Chunk] = {
    val reader = new InputReader(useAscii)
    val allRecords = reader.readAllRecords(inputFiles)

    allRecords.grouped(recordsPerChunk).zipWithIndex.map { case (records, index) =>
      Chunk(index, records.toSeq)
    }.toSeq
  }

  private def sortChunk(chunk: Chunk, chunkIndex: Int): File = {
    val startTime = System.currentTimeMillis()

    // Sort records in memory
    val sorted = chunk.records.sortWith { (a, b) =>
      ByteArrayOrdering.compare(a.key, b.key) < 0
    }

    // Write to file
    val chunkFile = fileLayout.getSortedChunkFile(chunkIndex)
    FileOperations.atomicWriteStream(chunkFile) { outputStream =>
      sorted.foreach { record =>
        outputStream.write(record.toBytes)
      }
    }

    val duration = System.currentTimeMillis() - startTime
    logger.info(s"Sorted chunk $chunkIndex: ${chunk.records.size} records in ${duration}ms")

    chunkFile
  }

  def shutdown(): Unit = {
    executor.shutdown()
    executor.awaitTermination(1, TimeUnit.MINUTES)
  }
}

case class Chunk(index: Int, records: Seq[Record])
```

### 3.2 성능 최적화

```scala
class ParallelSorter {
  // Scala parallel collections 사용 (간단한 버전)
  def sortChunksSimple(chunks: Seq[Chunk]): Seq[File] = {
    chunks.par.map { chunk =>
      sortChunk(chunk)
    }.seq.toSeq
  }

  // Java 8 Parallel Streams 사용
  def sortChunksJava(chunks: Seq[Chunk]): Seq[File] = {
    import scala.collection.JavaConverters._

    chunks.asJava.parallelStream()
      .map(chunk => sortChunk(chunk))
      .collect(Collectors.toList())
      .asScala
      .toSeq
  }
}
```

---

## 4. Phase 4: Parallel Partitioning

### 4.1 병렬 Partition 파일 쓰기

```scala
class Partitioner(boundaries: Seq[Array[Byte]], numPartitions: Int) {
  def createPartitionsInParallel(sortedChunks: Seq[File]): Seq[File] = {
    logger.info(s"Creating $numPartitions partitions in parallel")

    // Step 1: K-way merge to get sorted stream
    val merger = new KWayMerger(sortedChunks)

    // Step 2: Distribute records to partitions
    val partitionBuffers = Array.fill(numPartitions)(new ArrayBuffer[Record]())

    merger.mergeAll { record =>
      val partitionId = getPartition(record.key)
      partitionBuffers(partitionId) += record
    }

    // Step 3: Write partitions in parallel
    val writeFutures = (0 until numPartitions).map { partitionId =>
      Future {
        writePartition(partitionId, partitionBuffers(partitionId).toSeq)
      }(ExecutionContext.fromExecutor(ThreadPoolConfig.createSortingExecutor()))
    }

    Await.result(Future.sequence(writeFutures), Duration.Inf)
  }

  private def writePartition(partitionId: Int, records: Seq[Record]): File = {
    val startTime = System.currentTimeMillis()
    val file = fileLayout.getLocalPartitionFile(partitionId)

    FileOperations.atomicWriteStream(file) { outputStream =>
      records.foreach { record =>
        outputStream.write(record.toBytes)
      }
    }

    val duration = System.currentTimeMillis() - startTime
    logger.info(s"Wrote partition $partitionId: ${records.size} records in ${duration}ms, " +
      s"${file.length() / (1024 * 1024)}MB")

    file
  }

  private def getPartition(key: Array[Byte]): Int = {
    // Binary search
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
}
```

### 4.2 메모리 효율적 버전 (Streaming)

```scala
class StreamingPartitioner(boundaries: Seq[Array[Byte]], numPartitions: Int) {
  def createPartitionsStreaming(sortedChunks: Seq[File]): Seq[File] = {
    // Partition writers (병렬로 쓰기 가능)
    val writers = (0 until numPartitions).map { partitionId =>
      val file = fileLayout.getLocalPartitionTempFile(partitionId)
      partitionId -> new BufferedOutputStream(new FileOutputStream(file))
    }.toMap

    try {
      // K-way merge while partitioning
      val merger = new KWayMerger(sortedChunks)

      merger.mergeAll { record =>
        val partitionId = getPartition(record.key)
        writers(partitionId).write(record.toBytes)
      }

      writers.values.foreach(_.close())

      // Atomic rename all at once (in parallel)
      (0 until numPartitions).par.foreach { partitionId =>
        val tempFile = fileLayout.getLocalPartitionTempFile(partitionId)
        val finalFile = fileLayout.getLocalPartitionFile(partitionId)
        Files.move(tempFile.toPath, finalFile.toPath, StandardCopyOption.ATOMIC_MOVE)
      }

      (0 until numPartitions).map(fileLayout.getLocalPartitionFile).toSeq

    } catch {
      case ex: Exception =>
        writers.values.foreach(w => try { w.close() } catch { case _: Exception => })
        (0 until numPartitions).foreach { partitionId =>
          FileOperations.safeDelete(fileLayout.getLocalPartitionTempFile(partitionId))
        }
        throw ex
    }
  }
}
```

---

## 5. Phase 5: Concurrent Shuffle

### 5.1 병렬 Partition 전송

```scala
class ShuffleManager(
  maxConcurrentTransfers: Int = ThreadPoolConfig.maxConcurrentTransfers
) {
  private val executor = ThreadPoolConfig.createShuffleExecutor()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  // Semaphore for flow control
  private val transferSemaphore = new Semaphore(maxConcurrentTransfers)

  def shuffleInParallel(transferTasks: Seq[TransferTask]): Unit = {
    logger.info(s"Starting parallel shuffle: ${transferTasks.size} transfers, " +
      s"max concurrent: $maxConcurrentTransfers")

    val futures = transferTasks.map { task =>
      Future {
        // Acquire semaphore (block if too many concurrent transfers)
        transferSemaphore.acquire()

        try {
          sendPartitionFile(task)
        } finally {
          transferSemaphore.release()
        }
      }
    }

    // Wait for all transfers to complete
    val results = Await.result(Future.sequence(futures), 30.minutes)

    val successCount = results.count(_ == true)
    logger.info(s"Shuffle completed: $successCount/${transferTasks.size} successful")

    if (successCount < transferTasks.size) {
      throw new IOException(s"Shuffle failed: only $successCount/${transferTasks.size} transfers succeeded")
    }
  }

  private def sendPartitionFile(task: TransferTask): Boolean = {
    val startTime = System.currentTimeMillis()

    try {
      val success = sendPartitionFileStreaming(task.file, task.target)

      val duration = System.currentTimeMillis() - startTime
      val throughput = task.file.length().toDouble / duration * 1000 / (1024 * 1024)

      logger.info(s"Transfer completed: ${task.file.getName} to ${task.target.workerId}, " +
        s"${task.file.length() / (1024 * 1024)}MB in ${duration}ms, " +
        f"$throughput%.2f MB/s")

      success

    } catch {
      case ex: Exception =>
        logger.error(s"Transfer failed: ${task.file.getName} to ${task.target.workerId}: $ex")
        false
    }
  }

  def shutdown(): Unit = {
    executor.shutdown()
    executor.awaitTermination(5, TimeUnit.MINUTES)
  }
}

case class TransferTask(file: File, target: WorkerInfo)
```

### 5.2 동적 동시성 조정

```scala
class AdaptiveShuffleManager extends ShuffleManager {
  private var currentConcurrency = maxConcurrentTransfers

  def adjustConcurrency(networkThroughput: Double): Unit = {
    // Throughput in MB/s
    if (networkThroughput > 100) {
      // High throughput → increase concurrency
      currentConcurrency = math.min(maxConcurrentTransfers * 2, 10)
      logger.info(s"Increased shuffle concurrency to $currentConcurrency")

    } else if (networkThroughput < 10) {
      // Low throughput → decrease concurrency
      currentConcurrency = math.max(1, currentConcurrency / 2)
      logger.info(s"Decreased shuffle concurrency to $currentConcurrency")
    }

    // Update semaphore
    transferSemaphore.drainPermits()
    transferSemaphore.release(currentConcurrency)
  }
}
```

---

## 6. Phase 6: Parallel Merge

### 6.1 여러 Partition 동시 Merge

```scala
class MergeManager(
  maxConcurrentMerges: Int = ThreadPoolConfig.maxConcurrentMerges
) {
  private val executor = ThreadPoolConfig.createMergeExecutor()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  def mergeMultiplePartitionsInParallel(partitionIds: Seq[Int]): Unit = {
    logger.info(s"Merging ${partitionIds.size} partitions with max $maxConcurrentMerges concurrent merges")

    // Group partitions into batches
    val batches = partitionIds.grouped(maxConcurrentMerges).toSeq

    batches.foreach { batch =>
      logger.info(s"Merging batch: ${batch.mkString(", ")}")

      val futures = batch.map { partitionId =>
        Future {
          mergePartition(partitionId)
        }
      }

      // Wait for current batch to complete before starting next batch
      Await.result(Future.sequence(futures), Duration.Inf)
    }

    logger.info(s"All ${partitionIds.size} partitions merged successfully")
  }

  private def mergePartition(partitionId: Int): Unit = {
    val startTime = System.currentTimeMillis()

    val receivedFiles = fileLayout.listReceivedPartitions(partitionId)
    if (receivedFiles.isEmpty) {
      logger.warn(s"No files found for partition $partitionId")
      return
    }

    logger.info(s"Merging partition $partitionId from ${receivedFiles.size} files")

    val outputFile = fileLayout.getOutputPartitionFile(partitionId)
    val merger = new KWayMerger(receivedFiles)

    var recordCount = 0L

    FileOperations.atomicWriteStream(outputFile) { outputStream =>
      merger.mergeAll { record =>
        outputStream.write(record.toBytes)
        recordCount += 1
      }
    }

    val duration = System.currentTimeMillis() - startTime
    logger.info(s"Completed merging partition $partitionId: $recordCount records, " +
      s"${outputFile.length() / (1024 * 1024)}MB in ${duration}ms")

    // Cleanup source files
    FileOperations.safeDeleteAll(receivedFiles)
  }

  def shutdown(): Unit = {
    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.MINUTES)
  }
}
```

### 6.2 K-way Merge 최적화

```scala
class ParallelKWayMerger(files: Seq[File], numThreads: Int = ThreadPoolConfig.numCores) {
  /**
   * Parallel K-way merge using tournament tree
   * - 여러 파일을 병렬로 읽기
   * - Priority queue로 merge
   */
  def mergeAllParallel(consumer: Record => Unit): Unit = {
    // Step 1: Open all files in parallel
    val readers = files.par.map { file =>
      new RecordReader(file, bufferSize = BufferedIO.LARGE_BUFFER_SIZE)
    }.seq.toSeq

    try {
      // Step 2: Initialize heap with first record from each file
      val heap = new mutable.PriorityQueue[HeapEntry]()(Ordering.by(-_.record.key))

      readers.zipWithIndex.foreach { case (reader, index) =>
        reader.nextRecord().foreach { record =>
          heap.enqueue(HeapEntry(record, index))
        }
      }

      // Step 3: K-way merge
      while (heap.nonEmpty) {
        val HeapEntry(record, fileIndex) = heap.dequeue()
        consumer(record)

        // Read next record from same file
        readers(fileIndex).nextRecord().foreach { nextRecord =>
          heap.enqueue(HeapEntry(nextRecord, fileIndex))
        }
      }

    } finally {
      readers.foreach(_.close())
    }
  }

  case class HeapEntry(record: Record, fileIndex: Int)
}
```

---

## 7. 동시성 제어 및 동기화

### 7.1 Thread-safe 자료구조

```scala
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, ConcurrentSkipListMap}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

class ConcurrentDataStructures {
  // Atomic counters
  val recordsProcessed = new AtomicLong(0)
  val chunksCompleted = new AtomicInteger(0)

  // Thread-safe collections
  val completedChunks = new ConcurrentLinkedQueue[File]()
  val partitionSizes = new ConcurrentHashMap[Int, Long]()

  // Lock-free updates
  def incrementRecordsProcessed(count: Long): Long = {
    recordsProcessed.addAndGet(count)
  }

  def recordChunkCompletion(chunkFile: File): Int = {
    completedChunks.add(chunkFile)
    chunksCompleted.incrementAndGet()
  }

  def updatePartitionSize(partitionId: Int, additionalBytes: Long): Unit = {
    partitionSizes.compute(partitionId, (_, currentSize) => {
      val current = if (currentSize == null) 0L else currentSize
      current + additionalBytes
    })
  }
}
```

### 7.2 Progress Tracking

```scala
class ProgressTracker(totalRecords: Long, numThreads: Int) {
  private val processedRecords = new AtomicLong(0)
  private val startTime = System.currentTimeMillis()

  def recordProgress(count: Long): Unit = {
    val processed = processedRecords.addAndGet(count)

    // Log every 10%
    if (processed % (totalRecords / 10) < count) {
      val progress = processed.toDouble / totalRecords * 100
      val elapsed = System.currentTimeMillis() - startTime
      val rate = processed.toDouble / elapsed * 1000  // records per second

      logger.info(f"Progress: $progress%.1f%% ($processed/$totalRecords records), " +
        f"rate: $rate%.0f rec/s, threads: $numThreads")
    }
  }

  def getStatistics: ProgressStats = {
    val processed = processedRecords.get()
    val elapsed = System.currentTimeMillis() - startTime

    ProgressStats(
      totalRecords = totalRecords,
      processedRecords = processed,
      elapsedMs = elapsed,
      recordsPerSecond = processed.toDouble / elapsed * 1000,
      remainingMs = if (processed > 0) (totalRecords - processed) * elapsed / processed else 0
    )
  }
}

case class ProgressStats(
  totalRecords: Long,
  processedRecords: Long,
  elapsedMs: Long,
  recordsPerSecond: Double,
  remainingMs: Long
) {
  override def toString: String = {
    f"Processed: $processedRecords/$totalRecords (${processedRecords.toDouble / totalRecords * 100}%.1f%%), " +
    f"Rate: $recordsPerSecond%.0f rec/s, " +
    f"Remaining: ${remainingMs / 1000}s"
  }
}
```

---

## 8. 성능 측정 및 튜닝

### 8.1 Phase별 성능 측정

```scala
class PerformanceMonitor {
  case class PhaseMetrics(
    phaseName: String,
    startTime: Long,
    endTime: Long,
    recordsProcessed: Long,
    bytesProcessed: Long,
    threadsUsed: Int
  ) {
    def durationMs: Long = endTime - startTime
    def recordsPerSecond: Double = recordsProcessed.toDouble / durationMs * 1000
    def throughputMBps: Double = bytesProcessed.toDouble / durationMs * 1000 / (1024 * 1024)

    override def toString: String = {
      f"$phaseName: ${durationMs / 1000}s, " +
      f"$recordsPerSecond%.0f rec/s, " +
      f"$throughputMBps%.2f MB/s, " +
      f"$threadsUsed threads"
    }
  }

  private val metrics = new ConcurrentLinkedQueue[PhaseMetrics]()

  def measurePhase[T](phaseName: String, threads: Int)(block: => T): T = {
    val startTime = System.currentTimeMillis()
    val startRecords = getRecordsProcessed()
    val startBytes = getBytesProcessed()

    val result = block

    val endTime = System.currentTimeMillis()
    val endRecords = getRecordsProcessed()
    val endBytes = getBytesProcessed()

    val metric = PhaseMetrics(
      phaseName = phaseName,
      startTime = startTime,
      endTime = endTime,
      recordsProcessed = endRecords - startRecords,
      bytesProcessed = endBytes - startBytes,
      threadsUsed = threads
    )

    metrics.add(metric)
    logger.info(s"Phase completed: $metric")

    result
  }

  def generateReport(): String = {
    val report = new StringBuilder
    report.append("=== Performance Report ===\n")

    metrics.asScala.foreach { metric =>
      report.append(s"$metric\n")
    }

    val totalDuration = metrics.asScala.map(_.durationMs).sum
    report.append(f"\nTotal time: ${totalDuration / 1000}s\n")

    report.toString()
  }
}
```

### 8.2 병렬화 효율 측정

```scala
class ParallelizationEfficiency {
  def measureSpeedup(sequential: Long, parallel: Long, numThreads: Int): SpeedupMetrics = {
    val speedup = sequential.toDouble / parallel
    val efficiency = speedup / numThreads

    SpeedupMetrics(
      sequentialTimeMs = sequential,
      parallelTimeMs = parallel,
      numThreads = numThreads,
      speedup = speedup,
      efficiency = efficiency
    )
  }
}

case class SpeedupMetrics(
  sequentialTimeMs: Long,
  parallelTimeMs: Long,
  numThreads: Int,
  speedup: Double,
  efficiency: Double
) {
  override def toString: String = {
    f"Speedup: ${speedup}x with $numThreads threads, " +
    f"Efficiency: ${efficiency * 100}%.1f%%"
  }
}
```

---

## 9. 구현 체크리스트

### Phase 3: Parallel Sorting
- [ ] ThreadPoolExecutor 생성
- [ ] Chunk 단위로 작업 분할
- [ ] Future.sequence로 모든 chunk 완료 대기
- [ ] Exception handling (일부 chunk 실패 시)
- [ ] Progress tracking

### Phase 4: Parallel Partitioning
- [ ] Partition writer들 병렬 실행
- [ ] Thread-safe partition size tracking
- [ ] Atomic rename (모든 partition 동시에)

### Phase 5: Concurrent Shuffle
- [ ] Semaphore로 동시 전송 개수 제한
- [ ] Throughput monitoring
- [ ] Network bandwidth 고려한 조정
- [ ] Transfer retry logic

### Phase 6: Parallel Merge
- [ ] Batch 단위로 merge
- [ ] K-way merge heap thread-safety
- [ ] Progress tracking per partition

### 공통
- [ ] Thread pool graceful shutdown
- [ ] Performance metrics 수집
- [ ] Resource cleanup on error
- [ ] Configurable thread counts

---

## 10. 성능 최적화 팁

### 10.1 메모리 vs 병렬성

```
더 많은 thread = 더 많은 메모리 사용

예시 (512MB 메모리 제한):
  - 1 thread: 512MB chunk size
  - 4 threads: 128MB chunk size
  - 8 threads: 64MB chunk size

Trade-off:
  - 큰 chunk: 적은 merge overhead, 느린 전체 속도
  - 작은 chunk: 많은 merge overhead, 빠른 병렬 처리
```

### 10.2 I/O Bound vs CPU Bound

```
CPU-bound (Sorting):
  threads = numCores

I/O-bound (Shuffle):
  threads = numCores * 2

Mixed (Merge):
  threads = numCores * 1.5
```

### 10.3 Profiling 지점

```scala
// JVM 옵션
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
-Xloggc:gc.log

// Thread dump
jstack <pid> > thread_dump.txt

// CPU profiling
-agentpath:/path/to/async-profiler
```

---

**문서 완성도**: 95%

**다음 단계**: 실제 구현 시 성능 측정하고 튜닝
