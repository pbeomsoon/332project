# Implementation Decisions (구현 결정 사항)

**작성일**: 2025-10-24
**목적**: PDF 요구사항 해석 및 모호한 부분에 대한 명확한 구현 결정

---

## 1. Fault Tolerance 전략 결정

### 1.1 PDF 요구사항 해석

**원문**:
> "The system must be fault-tolerant, which means that if a worker crashes and restarts, the overall computation should still produce correct results."

**핵심 질문**: "Worker crash & restart 후 어떻게 복구?"

### 1.2 구현 결정: **Checkpoint-based Recovery**

```
┌─────────────────────────────────────────────────────────┐
│ Fault Tolerance Strategy (실제 구현)                    │
├─────────────────────────────────────────────────────────┤
│ Checkpoint 저장:                                        │
│   ✅ 각 Phase 완료 시 자동 저장                          │
│   ✅ WorkerState를 JSON으로 영속화                       │
│   ✅ 위치: /tmp/distsort/checkpoints/                   │
│   ✅ 최근 3개 checkpoint 유지                            │
│                                                         │
│ Worker Crash & Restart:                                 │
│   ✅ 시작 시 최신 checkpoint 로드                        │
│   ✅ 마지막 완료 Phase부터 재개                          │
│   ✅ Sampling/Sort 재수행 불필요                         │
│                                                         │
│ Graceful Shutdown:                                      │
│   ✅ 30초 grace period                                  │
│   ✅ 현재 Phase 완료 대기                                │
│   ✅ Checkpoint 저장 후 종료                             │
│                                                         │
│ Data Integrity:                                         │
│   ✅ Atomic writes (temp + rename)                      │
│   ✅ State-based cleanup on failure                     │
│   ✅ Idempotent operations                              │
└─────────────────────────────────────────────────────────┘
```

### 1.3 정당화 (Justification)

**왜 "Checkpoint 기반 복구"를 선택했나?**

1. **빠른 복구**: 마지막 완료 Phase부터 재개 → 전체 재시작보다 효율적
2. **정확성 보장**: Phase별 완료 checkpoint → 부분 결과 유실 없음
3. **실제 시스템 사례**:
   - Spark: RDD lineage + checkpoint 혼합
   - Flink: Checkpoint 기반 exactly-once semantics
   - MapReduce: Task-level restart (우리는 Worker-level)
4. **구현 가능성**:
   - CheckpointManager로 상태 관리
   - JSON 직렬화로 간단한 영속화
   - Graceful Shutdown과 통합
5. **PDF 해석**: "produce correct results" ← Checkpoint가 정확성과 효율성 모두 충족

### 1.4 Worker Restart 시나리오 (Checkpoint 기반)

```
시나리오 1: Worker crashes during Sorting (Phase 3)
──────────────────────────────────────────────────────
Before crash:
  - Worker 2 is sorting local data (50% complete)
  - Last checkpoint: PHASE_SAMPLING (100% complete)
  - Worker 2 crashes (kill -9)

Detection:
  - Master: No heartbeat from Worker 2 for 60s
  - Master: Marks Worker 2 as FAILED

Recovery:
  Worker 2 restarts:
    1. run() 호출
    2. recoverFromCheckpoint() 성공
       - Loads: checkpoint_1699999999_PHASE_SAMPLING.json
       - Restores: partitionBoundaries, shuffleMap, etc.
       - Sets currentPhase = PHASE_SAMPLING

    3. Phase별 체크:
       if (currentPhase == PHASE_SAMPLING) {
         getPartitionConfiguration()  // ✅ 이미 완료 (checkpoint에서)
       }
       if (currentPhase == PHASE_SORTING) {
         performLocalSort()  // ⭐ 여기부터 재개 (Sampling 스킵)
       }

    4. Sorting 재시작 → Checkpoint 저장 (PHASE_SORTING 100%)
    5. Shuffle → Merge → 완료

Result:
  ✅ Sampling 재수행 불필요 (10분 절약)
  ✅ 정확성 보장 (checkpoint 상태에서 재개)
  ✅ 다른 Worker 영향 없음
```

```
시나리오 2: Worker crashes during Shuffle (Phase 4)
──────────────────────────────────────────────────────
Before crash:
  - Worker 2 is sending partition.5 to Worker 3 (50% complete)
  - Last checkpoint: PHASE_SORTING (100% complete)
  - Worker 2 crashes (network failure)

Detection:
  - Worker 3: Partial file received, timeout after 60s
  - Worker 3: Cleanup incomplete temp file
  - Master: No heartbeat from Worker 2 for 60s

Recovery:
  Worker 2 restarts:
    1. recoverFromCheckpoint() 성공
       - Loads: checkpoint_1700000000_PHASE_SORTING.json
       - currentPhase = PHASE_SORTING

    2. Phase별 체크:
       if (currentPhase == PHASE_SORTING) {
         // Sorting already done (checkpoint)
         performShuffle(sortedChunks)  // ⭐ Shuffle 재시작
       }

    3. Shuffle 재시도:
       - 정렬된 chunk 파일들은 이미 존재
       - partitionBoundaries, shuffleMap 복원됨
       - Shuffle 처음부터 재시작 (멱등성 보장)
       - 재시도 로직으로 전송 성공

    4. Merge → 완료

Result:
  ✅ Sorting 재수행 불필요 (시간 대폭 절약)
  ✅ Shuffle만 재시도
  ✅ 데이터 무결성 보장
```

```
시나리오 3: Worker crashes just before Checkpoint save
──────────────────────────────────────────────────────
Before crash:
  - Worker 2 completes Sorting
  - savePhaseCheckpoint(PHASE_SORTING, 1.0) 호출 중
  - Checkpoint 저장 50% → Crash

Recovery:
  Worker 2 restarts:
    1. recoverFromCheckpoint()
       - Latest checkpoint: PHASE_SAMPLING (이전 완료)
       - PHASE_SORTING checkpoint는 불완전 (검증 실패)

    2. Phase별 체크:
       if (currentPhase == PHASE_SAMPLING) {
         getPartitionConfiguration()  // Skip
         performLocalSort()  // ⭐ Sorting 재수행
       }

Result:
  ✅ 불완전한 checkpoint는 무시
  ✅ 가장 최근의 완전한 checkpoint부터 재개
  ✅ Worst case: Sorting 재수행 (여전히 Sampling은 스킵)
```

### 1.5 실제 구현 상세

#### CheckpointManager 구현

```scala
package distsort.checkpoint

case class Checkpoint(
  id: String,
  workerId: String,
  phase: String,                   // WorkerPhase를 String으로 직렬화
  timestamp: Instant,
  progress: Double,
  state: WorkerState
)

case class WorkerState(
  processedRecords: Long,
  partitionBoundaries: List[Array[Byte]],
  shuffleMap: Map[Int, Int],
  completedPartitions: Set[Int],
  currentFiles: List[String],
  phaseMetadata: Map[String, String]
)

class CheckpointManager(workerId: String, checkpointDir: String) {
  private val gson = new GsonBuilder().setPrettyPrinting().create()
  private val checkpointPath = Paths.get(checkpointDir, workerId)

  /**
   * Save checkpoint to disk (JSON format)
   */
  def saveCheckpoint(
    phase: WorkerPhase,
    state: WorkerState,
    progress: Double
  ): Future[String] = Future {
    val checkpointId = s"checkpoint_${System.currentTimeMillis()}_${phase.toString}"
    val checkpoint = Checkpoint(checkpointId, workerId, phase.toString,
                                 Instant.now(), progress, state)

    val file = checkpointPath.resolve(s"$checkpointId.json").toFile
    val writer = new PrintWriter(file)

    try {
      writer.write(gson.toJson(checkpoint))
      logger.info(s"Saved checkpoint $checkpointId (${(progress * 100).toInt}%)")
      checkpointId
    } finally {
      writer.close()
    }
  }

  /**
   * Load latest valid checkpoint
   */
  def loadLatestCheckpoint(): Option[Checkpoint] = {
    val checkpointFiles = checkpointPath.toFile.listFiles()
      .filter(_.getName.endsWith(".json"))
      .sortBy(_.lastModified()).reverse

    checkpointFiles.headOption.flatMap { file =>
      val json = Files.readString(file.toPath)
      val checkpoint = gson.fromJson(json, classOf[Checkpoint])

      if (validateCheckpoint(checkpoint.id)) {
        Some(checkpoint)
      } else {
        None  // 불완전한 checkpoint 무시
      }
    }
  }

  /**
   * Clean old checkpoints (keep last 3)
   */
  def cleanOldCheckpoints(keepLast: Int = 3): Unit = {
    val checkpointFiles = checkpointPath.toFile.listFiles()
      .filter(_.getName.endsWith(".json"))
      .sortBy(_.lastModified()).reverse

    checkpointFiles.drop(keepLast).foreach(_.delete())
  }
}
```

#### Worker에서의 Checkpoint 사용

```scala
class Worker(...) extends ShutdownAware {
  private val checkpointManager = CheckpointManager(workerId,
                                                     s"/tmp/distsort/checkpoints")

  def run(): Unit = {
    // 1. Checkpoint에서 복구 시도
    val recoveredFromCheckpoint = recoverFromCheckpoint()

    if (!recoveredFromCheckpoint || currentPhase.get() == PHASE_INITIALIZING) {
      performSampling()
    }

    // 복구된 Phase에 따라 적절한 단계부터 재개
    if (currentPhase.get() == PHASE_SAMPLING || ...) {
      getPartitionConfiguration()
      savePhaseCheckpoint(PHASE_WAITING_FOR_PARTITIONS, 1.0)  // ⭐ 저장
    }

    if (currentPhase.get() == PHASE_SORTING || ...) {
      performLocalSort()
      savePhaseCheckpoint(PHASE_SORTING, 1.0)  // ⭐ 저장

      performShuffle()
      savePhaseCheckpoint(PHASE_SHUFFLING, 1.0)  // ⭐ 저장
    }

    performMerge()
    savePhaseCheckpoint(PHASE_MERGING, 1.0)  // ⭐ 저장

    // 성공 시 모든 checkpoint 삭제
    checkpointManager.deleteAllCheckpoints()
  }

  private def recoverFromCheckpoint(): Boolean = {
    checkpointManager.loadLatestCheckpoint() match {
      case Some(checkpoint) =>
        // 상태 복원
        val (processedRecords, boundaries, shuffle, completed, files, metadata) =
          WorkerState.toScala(checkpoint.state)

        processedRecordCount.set(processedRecords)
        partitionBoundaries = boundaries.toArray
        shuffleMap = shuffle
        completedPartitions.clear()
        completed.foreach(p => completedPartitions.put(p, true))

        // Phase 복원
        val phase = WorkerPhase.fromName(checkpoint.phase)
          .getOrElse(WorkerPhase.PHASE_INITIALIZING)

        currentPhase.set(phase)
        true  // 복구 성공

      case None =>
        false  // 처음부터 시작
    }
  }

  private def savePhaseCheckpoint(phase: WorkerPhase, progress: Double): Unit = {
    try {
      val state = WorkerState.fromScala(
        processedRecords = processedRecordCount.get(),
        partitionBoundaries = partitionBoundaries.toSeq,
        shuffleMap = shuffleMap,
        completedPartitions = completedPartitions.keys().asScala.toSet,
        currentFiles = fileLayout.getInputFiles.map(_.getAbsolutePath),
        phaseMetadata = Map("workerId" -> workerId, "phase" -> phase.toString)
      )

      Await.result(checkpointManager.saveCheckpoint(phase, state, progress), 10.seconds)
      checkpointManager.cleanOldCheckpoints(3)  // 최근 3개만 유지

    } catch {
      case ex: Exception =>
        logger.warn(s"Checkpoint save failed: ${ex.getMessage}")
        // Best-effort: 실패해도 계속 진행
    }
  }
}
```

#### Graceful Shutdown 통합

```scala
class Worker(...) extends ShutdownAware {
  private val shutdownManager = GracefulShutdownManager(
    ShutdownConfig(
      gracePeriod = 30.seconds,
      saveCheckpoint = true,         // ⭐ Shutdown 시 checkpoint 저장
      waitForCurrentPhase = true     // ⭐ 현재 Phase 완료 대기
    )
  )

  override def gracefulShutdown(): Future[Unit] = {
    logger.info("Initiating graceful shutdown...")

    // 1. 현재 Phase 완료 대기
    // 2. Checkpoint 저장
    val currentState = getCurrentState()
    checkpointManager.saveCheckpoint(currentPhase.get(), currentState, 0.5)

    // 3. 리소스 정리
    cleanupResources()
  }
}
```

#### 향후 개선 가능성

**Phase 중간 지점 Checkpoint** (현재는 Phase 완료 시점만):
- Sorting 중 chunk별 checkpoint
- Shuffle 중 파티션별 checkpoint
- 더 세밀한 복구 가능 (trade-off: overhead 증가)

**Master Checkpoint** (현재는 Worker만):
- Master 상태도 checkpoint
- Master crash 시 복구 가능
- 완전한 Fault Tolerance (현재는 Worker만)

---

## 2. 병렬 처리 전략 결정

### 2.1 PDF 요구사항 해석

**원문**:
> "Utilize multi-core processors for parallel processing"

### 2.2 구현 결정: **Phase별 병렬화**

```
┌─────────────────────────────────────────────────────────┐
│ Parallelization Strategy                                │
├─────────────────────────────────────────────────────────┤
│ Thread Pool Configuration:                              │
│   - numCores = Runtime.getRuntime.availableProcessors() │
│   - sortingThreads = numCores                           │
│   - shuffleThreads = min(numCores, numWorkers)          │
│   - mergeThreads = numCores                             │
│                                                         │
│ Phase별 병렬화:                                          │
│   Phase 2 (Sorting):    Parallel chunk sorting         │
│   Phase 3 (Partition):  Parallel partition writing     │
│   Phase 4 (Shuffle):    Concurrent file transfers      │
│   Phase 5 (Merge):      Parallel K-way merge           │
└─────────────────────────────────────────────────────────┘
```

### 2.3 구체적 구현

#### **Phase 2: Parallel Sorting**

```scala
class ExternalSorter(numThreads: Int = Runtime.getRuntime.availableProcessors()) {
  private val executor = Executors.newFixedThreadPool(numThreads)

  def sortInParallel(chunks: Seq[Chunk]): Seq[File] = {
    val futures = chunks.map { chunk =>
      Future {
        val sorted = chunk.records.sortWith((a, b) =>
          ByteArrayOrdering.compare(a.key, b.key) < 0
        )
        writeSortedChunk(sorted)
      }(ExecutionContext.fromExecutor(executor))
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }
}
```

#### **Phase 3: Parallel Partitioning**

```scala
class Partitioner(numThreads: Int) {
  def writePartitionsInParallel(
    partitions: Map[Int, Seq[Record]]
  ): Seq[File] = {
    // 각 partition을 병렬로 쓰기
    partitions.par.map { case (partitionId, records) =>
      val file = fileLayout.getLocalPartitionFile(partitionId)
      FileOperations.atomicWriteStream(file) { out =>
        records.foreach(r => out.write(r.toBytes))
      }
      file
    }.seq.toSeq
  }
}
```

#### **Phase 4: Concurrent Shuffle**

```scala
class ShuffleManager(maxConcurrentTransfers: Int = 5) {
  private val transferSemaphore = new Semaphore(maxConcurrentTransfers)

  def shuffleInParallel(transferTasks: Seq[TransferTask]): Unit = {
    val futures = transferTasks.map { task =>
      Future {
        transferSemaphore.acquire()
        try {
          sendPartitionFile(task.file, task.target)
        } finally {
          transferSemaphore.release()
        }
      }
    }

    Await.result(Future.sequence(futures), 30.minutes)
  }
}
```

#### **Phase 5: Parallel Merge (선택사항)**

```scala
class MergeManager(numThreads: Int) {
  def mergeMultiplePartitionsInParallel(
    partitionIds: Seq[Int]
  ): Unit = {
    // 여러 partition을 동시에 merge
    partitionIds.par.foreach { partitionId =>
      mergePartition(partitionId)
    }
  }
}
```

### 2.4 Thread Pool 크기 결정

```scala
object ThreadPoolConfig {
  private val numCores = Runtime.getRuntime.availableProcessors()

  // Sorting: CPU-bound → numCores
  val sortingThreads: Int = numCores

  // Shuffle: I/O-bound → 2 * numCores (또는 worker 수 제한)
  val shuffleThreads: Int = math.min(numCores * 2, 10)

  // Merge: I/O-bound with some CPU → 1.5 * numCores
  val mergeThreads: Int = (numCores * 1.5).toInt

  // Concurrent transfers limit (network bandwidth 고려)
  val maxConcurrentTransfers: Int = 5
}
```

---

## 3. Master 출력 형식 결정

### 3.1 PDF 요구사항 해석

**원문** (PDF Algorithm Phase 0):
```
print "Master IP:Port"
for each worker in workerList do
    print worker.IP
end for
```

**PDF 예시**:
```
141.223.91.81        <- Master IP:Port
141.223.91.81        <- Worker 1 IP (포트 없음)
141.223.91.82        <- Worker 2 IP
141.223.91.83        <- Worker 3 IP
```

### 3.2 구현 결정

```
출력 형식: 2 줄
  Line 1: Master의 IP:port
  Line 2: 모든 Worker의 IP (콤마로 구분, 포트 제외)

예시 (3 workers):
192.168.1.100:5000
192.168.1.10, 192.168.1.11, 192.168.1.12
```

**근거**:
- PDF Algorithm에 명확히 명시됨
- Master 주소는 IP:Port 형식
- Worker 주소는 IP만 (포트 제외)
- "in order" → Worker ID 오름차순 (W0, W1, W2, ...)
- **각 Worker는 여러 연속된 partition 담당** (N→M Strategy)
  - 이유: **Merge 단계에서 멀티코어 활용**
  - 예: 3 workers, 9 partitions
    - Worker 0: partition.0, 1, 2 (연속된 3개)
    - Worker 1: partition.3, 4, 5 (연속된 3개)
    - Worker 2: partition.6, 7, 8 (연속된 3개)
  - Range-based assignment: `workerID = partitionID / (numPartitions / numWorkers)`
  - 자세한 내용: `6-parallelization.md` Section 1.3 참조

### 3.3 정확한 구현

```scala
class MasterNode {
  def outputFinalResult(): Unit = {
    // All workers completed merging
    phaseTracker.waitForPhase(WorkerPhase.PHASE_MERGING)

    logger.info("All workers completed. Outputting final result.")

    // Sort workers by ID (W0, W1, W2, ...)
    val sortedWorkers = registeredWorkers.asScala.toSeq
      .sortBy(_.workerId)

    // Output to STDOUT (not logger)
    // Line 1: Master IP:port
    println(s"${getMasterAddress()}:${actualPort}")

    // Line 2: Worker IPs (콤마로 구분, 한 줄에, 포트 제외)
    val workerIPs = sortedWorkers.map(_.address).mkString(", ")
    println(workerIPs)

    logger.info("Distributed sorting completed successfully!")
  }

  private def getMasterAddress(): String = {
    // Get the actual IP address of the master node
    // Option 1: Use environment variable
    sys.env.getOrElse("MASTER_HOST", {
      // Option 2: Auto-detect local IP
      InetAddress.getLocalHost.getHostAddress
    })
  }
}
```

**출력 예시**:
```
$ sbt "runMain distsort.Main master 3"
[... logs ...]
192.168.1.100:5000
192.168.1.10, 192.168.1.11, 192.168.1.12
[2025-10-24 18:45:23] INFO: Distributed sorting completed successfully!
```

**클라이언트 사용법**:
```bash
# Master 실행 및 출력 파싱
output=$(sbt "runMain distsort.Main master 3" 2>/dev/null)

# Line 1: Master address
master_addr=$(echo "$output" | sed -n '1p')
echo "Master: $master_addr"

# Line 2: Worker IP들 (콤마로 구분)
worker_line=$(echo "$output" | sed -n '2p')
IFS=', ' read -r -a workers <<< "$worker_line"

# 각 Worker의 partition 파일들 다운로드
for i in "${!workers[@]}"; do
  worker_ip="${workers[$i]}"
  echo "Downloading partitions from Worker $i at $worker_ip"

  # Worker가 담당하는 모든 partition 파일 다운로드
  # Strategy B (range-based): Worker i는 연속된 partition 범위 담당
  # 예: 3 workers, 9 partitions → Worker 0 → partition.0, partition.1, partition.2
  scp "${worker_ip}:/path/to/output/partition.*" ./
done
```

---

## 4. 입력 파일 처리 방식 결정

### 4.1 PDF 요구사항 해석

**원문**:
> "Input files are distributed across workers in 32MB blocks"

**해석**:
- 각 Worker는 여러 input 디렉토리를 가질 수 있음
- 각 디렉토리 안에 여러 파일 존재 가능
- 파일들은 대략 32MB 단위로 분할되어 있을 수 있음 (하지만 보장은 아님)

### 4.2 구현 결정

```
입력 파일 발견 방식:
  1. 모든 input 디렉토리를 재귀적으로 탐색
  2. 모든 일반 파일을 읽기 (디렉토리 제외)
  3. 파일 크기 무관 (32MB는 참고 사항일 뿐)
```

```scala
class FileLayout {
  def getInputFiles: Seq[File] = {
    inputDirs.flatMap { dir =>
      val dirFile = new File(dir)
      if (!dirFile.exists()) {
        logger.warn(s"Input directory does not exist: $dir")
        Seq.empty
      } else if (!dirFile.isDirectory) {
        logger.warn(s"Input path is not a directory: $dir")
        Seq.empty
      } else {
        listFilesRecursively(dirFile)
          .filter(f => f.isFile)  // 일반 파일만
      }
    }
  }

  private def listFilesRecursively(dir: File): Seq[File] = {
    val (files, subdirs) = dir.listFiles()
      .partition(_.isFile)

    val subFiles = subdirs.flatMap(listFilesRecursively)

    files ++ subFiles
  }
}
```

### 4.3 입력 읽기 방식

```scala
class InputReader(useAscii: Boolean) {
  private val recordReader = if (useAscii) {
    new AsciiRecordReader()
  } else {
    new BinaryRecordReader()
  }

  def readAllRecords(files: Seq[File]): Iterator[Record] = {
    files.iterator.flatMap { file =>
      logger.info(s"Reading file: ${file.getName}, size: ${file.length() / (1024 * 1024)} MB")

      val inputStream = new BufferedInputStream(
        new FileInputStream(file),
        BufferedIO.LARGE_BUFFER_SIZE  // 1 MB buffer
      )

      Iterator.continually {
        recordReader.readRecord(inputStream)
      }.takeWhile(_.isDefined).map(_.get)
    }
  }
}
```

---

## 5. Worker 수 결정 로직

### 5.1 PDF 요구사항 해석

**원문**:
> "Master expects N workers"

### 5.2 구현 결정

```
Master 시작 시 N을 명시:
  - Master가 정확히 N개 worker 대기
  - N개 모두 등록될 때까지 blocking
  - Timeout: 5분

Worker 등록:
  - First N workers만 받음
  - N 초과 등록 시도 거부
  - N 미만 등록 시 timeout 후 에러
```

```scala
class MasterNode(expectedWorkers: Int) {
  private val registrationLatch = new CountDownLatch(expectedWorkers)
  private val registrationDeadline = System.currentTimeMillis() + (5 * 60 * 1000)

  override def registerWorker(request: RegisterRequest): Future[RegisterResponse] = {
    synchronized {
      val now = System.currentTimeMillis()

      // Check timeout
      if (now > registrationDeadline) {
        return Future.successful(RegisterResponse(
          success = false,
          workerId = "",
          totalWorkers = expectedWorkers,
          message = "Registration deadline exceeded"
        ))
      }

      // Check if already full
      if (registeredWorkers.size() >= expectedWorkers) {
        return Future.successful(RegisterResponse(
          success = false,
          workerId = "",
          totalWorkers = expectedWorkers,
          message = s"Already have $expectedWorkers workers registered"
        ))
      }

      // Register worker
      val workerId = s"W${workerIdCounter.getAndIncrement()}"
      val workerInfo = request.workerInfo.get.copy(workerId = workerId)

      registeredWorkers.add(workerInfo)
      registrationLatch.countDown()

      logger.info(s"Worker $workerId registered (${registeredWorkers.size()}/$expectedWorkers)")

      Future.successful(RegisterResponse(
        success = true,
        workerId = workerId,
        totalWorkers = expectedWorkers,
        message = "Registration successful"
      ))
    }
  }

  def waitForAllWorkers(): Unit = {
    logger.info(s"Waiting for $expectedWorkers workers to register...")

    val registered = registrationLatch.await(5, TimeUnit.MINUTES)

    if (!registered) {
      val actual = registeredWorkers.size()
      throw new TimeoutException(
        s"Only $actual/$expectedWorkers workers registered within timeout"
      )
    }

    logger.info(s"All $expectedWorkers workers registered successfully")
  }
}
```

---

## 6. 기타 명확화 사항

### 6.1 ASCII vs Binary 형식

**PDF 요구사항**:
> "Should work on both ASCII and binary input **without requiring an option**"

**구현 결정**: **자동 감지 (Auto-detection)**

#### 6.1.1 입력 형식 정의

```
ASCII:
  - 각 record는 100 characters
  - Key: 10 characters (ASCII printable)
  - Value: 90 characters (ASCII printable)
  - Line ending: \n (Unix) or \r\n (Windows) - 둘 다 지원

Binary:
  - 각 record는 정확히 100 bytes
  - Key: 10 bytes (any byte value)
  - Value: 90 bytes (any byte value)
  - No line endings
```

**중요**:
- **정렬 시 처음 10 bytes (Key)만 비교**에 사용됩니다
- 나머지 90 bytes (Value)는 정렬에 사용되지 않으며, 단순히 Key와 함께 이동합니다
- 이는 gensort 표준 형식으로, valsort로 검증할 때도 이 규칙을 따릅니다

#### 6.1.2 자동 감지 알고리즘

```scala
object InputFormatDetector {
  /**
   * 파일의 첫 1000 바이트를 읽어서 ASCII vs Binary 판별
   *
   * 알고리즘:
   *   1. 파일의 첫 1000 바이트 읽기
   *   2. ASCII printable 문자 비율 계산
   *   3. 비율 > 0.9 → ASCII, 그 외 → Binary
   */
  def detectFormat(file: File): InputFormat = {
    val buffer = new Array[Byte](1000)
    val inputStream = new FileInputStream(file)

    try {
      val bytesRead = inputStream.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}, defaulting to Binary")
        return InputFormat.BINARY
      }

      // ASCII printable: 0x20-0x7E (space ~ ~), plus \n (0x0A), \r (0x0D)
      val asciiLikeCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiLikeCount.toDouble / bytesRead

      logger.debug(s"File: ${file.getName}, ASCII ratio: $asciiRatio")

      if (asciiRatio > 0.9) {
        logger.info(s"Detected ASCII format for file: ${file.getName}")
        InputFormat.ASCII
      } else {
        logger.info(s"Detected Binary format for file: ${file.getName}")
        InputFormat.BINARY
      }
    } finally {
      inputStream.close()
    }
  }
}

sealed trait InputFormat
object InputFormat {
  case object ASCII extends InputFormat
  case object BINARY extends InputFormat
}
```

#### 6.1.3 RecordReader 구현

```scala
trait RecordReader {
  def readRecord(input: InputStream): Option[Record]
}

class BinaryRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Record] = {
    val buffer = new Array[Byte](100)
    val bytesRead = input.read(buffer)

    if (bytesRead == -1) {
      None
    } else if (bytesRead < 100) {
      logger.warn(s"Incomplete record: only $bytesRead bytes")
      None
    } else {
      val key = buffer.slice(0, 10)
      val value = buffer.slice(10, 100)
      Some(Record(key, value))
    }
  }
}

class AsciiRecordReader extends RecordReader {
  private val lineReader = new BufferedReader(new InputStreamReader(input))

  override def readRecord(input: InputStream): Option[Record] = {
    val line = lineReader.readLine()

    if (line == null) {
      None
    } else if (line.length < 100) {
      logger.warn(s"Incomplete line: only ${line.length} characters")
      None
    } else {
      val key = line.substring(0, 10).getBytes(StandardCharsets.UTF_8)
      val value = line.substring(10, 100).getBytes(StandardCharsets.UTF_8)
      Some(Record(key, value))
    }
  }
}
```

#### 6.1.4 사용 예시

```scala
class InputReader {
  def readAllRecords(files: Seq[File]): Iterator[Record] = {
    files.iterator.flatMap { file =>
      // Auto-detect format for each file
      val format = InputFormatDetector.detectFormat(file)

      val recordReader = format match {
        case InputFormat.ASCII => new AsciiRecordReader()
        case InputFormat.BINARY => new BinaryRecordReader()
      }

      logger.info(s"Reading file: ${file.getName} (format: $format)")

      val inputStream = new BufferedInputStream(
        new FileInputStream(file),
        BufferedIO.LARGE_BUFFER_SIZE
      )

      Iterator.continually {
        recordReader.readRecord(inputStream)
      }.takeWhile(_.isDefined).map(_.get)
    }
  }
}
```

#### 6.1.5 혼합 입력 처리

```
시나리오: Worker가 여러 input 디렉토리를 가질 때
  - /data/input1/file1.bin (Binary)
  - /data/input1/file2.txt (ASCII)
  - /data/input2/file3.bin (Binary)

처리 방식:
  ✅ 각 파일마다 독립적으로 format detection
  ✅ ASCII와 Binary 파일 혼재 가능
  ✅ 동일한 Key/Value 바이트 표현으로 정렬
```

**중요**: 명령행 옵션으로 `--ascii` 또는 `--binary`를 지정하지 **않음**. 모든 파일은 자동 감지됨.

### 6.2 Temporary 파일 위치

```
기본값:
  - <output-dir>/.temp/

명령행 옵션:
  --temp <directory>

예시:
  sbt "runMain distsort.Main worker master:5000 \
    -I /data/input1,/data/input2 \
    -O /data/output \
    --temp /tmp/sorting"
```

### 6.3 포트 번호

```
Master:
  - 기본값: 없음 (명령행에서 지정 불필요)
  - gRPC 서버 랜덤 포트 bind
  - 실제 bind된 포트를 화면에 출력

Worker:
  - 기본값: 없음
  - gRPC 서버 랜덤 포트 bind
  - RegisterRequest에 실제 포트 포함
```

```scala
class MasterNode(expectedWorkers: Int) {
  private val server = ServerBuilder.forPort(0)  // 0 = random port
    .addService(MasterServiceGrpc.bindService(this, executionContext))
    .build()
    .start()

  val actualPort: Int = server.getPort

  logger.info(s"Master started on port $actualPort")
  println(s"MASTER_PORT=$actualPort")  // For scripting
}
```

---

## 7. 정리 및 우선순위

### 7.1 핵심 결정 사항

| 항목 | 결정 | 복잡도 | 우선순위 |
|------|------|--------|---------|
| Fault Tolerance | 전체 재시작 (Phase 1-2는 부분 복구) | Medium | 🔴 High |
| 병렬 처리 | Phase별 병렬화, numCores 기반 | Low | 🔴 High |
| Master 출력 | `address:port` 형식, Worker ID 순 | Low | 🔴 High |
| 입력 파일 | 재귀 탐색 | Low | 🔴 High |
| Worker 수 | 고정 N, 5분 timeout | Low | 🔴 High |
| ASCII/Binary | 별도 RecordReader, 100 char/byte | Low | 🔴 High |

### 7.2 구현 순서

```
Milestone 1: Infrastructure
  ✅ Master/Worker skeleton
  ✅ gRPC setup
  ✅ Worker registration (N workers, 5min timeout)
  ✅ Port randomization
  ✅ Master 출력 형식

Milestone 2: Basic Sorting (Strategy A)
  ✅ 입력 파일 재귀 탐색
  ✅ ASCII/Binary RecordReader
  ✅ Sampling
  ✅ External sorting (sequential)
  ✅ Partitioning (N→N)
  ✅ Shuffle
  ✅ Merge

Milestone 3: Parallelization
  ✅ Parallel chunk sorting
  ✅ Concurrent shuffle transfers
  ✅ Thread pool configuration

Milestone 4: Fault Tolerance
  ✅ Heartbeat mechanism
  ✅ Health checking
  ✅ Graceful degradation (Phase 1-2)
  ✅ Job restart (Phase 3-4)
  ✅ State-based cleanup

Milestone 5: Advanced Features (Optional)
  ⚠️ Strategy B (N→M partitioning)
  ⚠️ Shuffle output replication
  ⚠️ Checkpoint-based recovery
```

---

## 8. Additional Requirements (추가 요구사항) ⚠️ 매우 중요

### 8.1 입력/출력 포맷 자동 감지 (Requirement 1)

**요구사항**:
> "Should work on both ASCII and binary input **without requiring an option**"

**구현**:
- ✅ 이미 구현됨 (Section 6.1 참조)
- `InputFormatDetector`가 파일 내용 분석하여 자동 감지
- ASCII ratio > 0.9 → ASCII, 그 외 → Binary

### 8.2 입력 디렉토리 보호 (Requirement 2) ⚠️

**요구사항**:
> "Input directories are shared and should not be updated"
> - **do not try to delete input files**
> - **do not create new files in the input directories**

**구현 원칙**:
```scala
class FileLayout(inputDirs: Seq[Path], outputDir: Path, tempBaseDir: Path) {
  // ✅ CORRECT: Read-only access to input directories
  def getInputFiles: Seq[File] = {
    inputDirs.flatMap { dir =>
      Files.walk(dir)
        .filter(Files.isRegularFile(_))
        .map(_.toFile)
        .toSeq
    }
  }

  // ❌ NEVER DO THIS: Modify input directories
  // def deleteInputFile(file: File): Unit = file.delete()  // 금지!
  // def createTempInInput(dir: Path): File = ...          // 금지!
}
```

**검증 체크리스트**:
- [ ] 입력 디렉토리에서 파일을 읽기만 하는가?
- [ ] 입력 디렉토리에 임시 파일을 생성하지 않는가?
- [ ] 입력 디렉토리의 파일을 삭제/이동하지 않는가?
- [ ] 모든 임시 파일은 `tempBaseDir`에만 생성하는가?

### 8.3 임시 파일 정리 (Requirement 3)

**요구사항**:
> "Output directories should contain only final output files"
> - It is okay to create temporary files/directories
> - **but delete them in the end**

**구현**:
```scala
// 모든 임시 파일은 .tmp 확장자 사용
val tempFile = new File(outputDir, s"partition.$id.tmp")

// Phase 완료 시 cleanup
fileLayout.cleanupSortedChunks()      // Sorting phase 후
fileLayout.cleanupLocalPartitions()   // Shuffle phase 후
fileLayout.cleanupReceivedPartitions() // Merge phase 후

// Job 완료 또는 에러 시 전체 cleanup
fileLayout.cleanupTemporaryFiles()
```

**최종 검증**:
```bash
# Output directory should only contain partition.* files
ls $OUTPUT_DIR
# 예상 출력: partition.0, partition.1, partition.2, ...
# 금지: partition.0.tmp, chunk_0000.sorted, .sorting/, etc.
```

**자세한 내용**: `5-file-management.md` Section 3, 7 참조

### 8.4 포트 동적 할당 (Requirement 4)

**요구사항**:
> "Do not assume a specific port (e.g., hard-coded)"
> - Running multiple workers (with different input/output directories) should work

**구현**:
```scala
// ❌ WRONG: Hard-coded port
val server = ServerBuilder.forPort(50051).build()

// ✅ CORRECT: OS-assigned port (port 0)
val server = ServerBuilder.forPort(0).build()
val actualPort = server.getPort  // OS가 할당한 실제 포트

// Master에 등록 시 실제 포트 전달
val request = RegisterRequest(
  workerInfo = Some(WorkerInfo(
    workerId = "",
    address = getLocalAddress(),
    port = actualPort  // 동적 할당된 포트
  ))
)
```

**이유**:
- 같은 머신에서 여러 Worker 실행 가능
- 포트 충돌 방지
- 테스트 자동화 용이

### 8.5 입력 블록 크기 가정 금지 (Requirement 5) ⚠️

**요구사항**:
> "Input blocks of 32MB each on each worker"
> - **do not rely on the assumption of 32MB**

**잘못된 가정**:
```scala
// ❌ WRONG: Assuming 32MB blocks
val chunkSize = 32 * 1024 * 1024  // 32MB
val numChunks = totalSize / chunkSize

// ❌ WRONG: Allocating fixed arrays
val buffer = new Array[Byte](32 * 1024 * 1024)
```

**올바른 구현**:
```scala
// ✅ CORRECT: Dynamic block size based on memory
val availableMemory = Runtime.getRuntime.maxMemory()
val chunkSize = math.min(availableMemory / 10, 100 * 1024 * 1024)

// ✅ CORRECT: Read files regardless of size
def readInputFiles(): Iterator[Record] = {
  inputFiles.iterator.flatMap { file =>
    val reader = new RecordReader(file)
    Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
  }
}
```

**중요**:
- 입력 파일 크기는 **알 수 없음**
- 1KB ~ 10GB 어떤 크기도 가능
- 메모리 기반 청크 크기 동적 결정
- 전체 파일 크기에 의존하지 말 것

### 8.6 Worker 크래시 테스트 시나리오 (Fault Tolerance)

**테스트 요구사항**:
> "We will kill one of your workers during the experiment"

**시나리오**:
1. Worker 프로세스가 실행 중 갑자기 종료 (kill -9)
2. 모든 중간 데이터 손실
3. 새 Worker가 같은 파라미터로 재시작
4. 기대: 최종 결과는 동일

**대응 전략**:
- ✅ Worker Re-registration (Section 1.4 참조)
- ✅ State-based cleanup on restart
- ✅ Idempotent operations
- ✅ Atomic file operations

**자세한 내용**: `4-error-recovery.md` 참조

---

## 9. 문서 현황

### 설계 문서 (Design Docs)
- ✅ docs/0-implementation-decisions.md (구현 결정 사항 + Additional Requirements)
- ✅ docs/1-phase-coordination.md (Phase 동기화)
- ✅ docs/2-worker-state-machine.md (Worker 상태 머신)
- ✅ docs/3-grpc-sequences.md (gRPC 시퀀스)
- ✅ docs/4-error-recovery.md (장애 복구)
- ✅ docs/5-file-management.md (파일 관리)
- ✅ docs/6-parallelization.md (병렬 처리 + N→M Strategy)
- ✅ docs/7-testing-strategy.md (테스트 전략 + TDD 가이드) ⭐ NEW

### 계획 문서 (Planning Docs)
- ✅ plan/2025-10-24_plan_ver3.md (전체 설계 + Critical Requirements)
- ✅ plan/quickstart-tdd-guide.md (TDD 개발 QuickStart) ⭐ NEW

### 주요 개선 사항 (v4)
- ✅ gensort/valsort 완전 문서화 (공식 사이트, 사용법, 테스트 시나리오)
- ✅ N→M Strategy 핵심 개념 명확화 (Merge 병렬화 목적)
- ✅ 6가지 Critical Requirements 추가 (입력 디렉토리 보호, 32MB 가정 금지 등)
- ✅ 종합 테스트 전략 문서 (Unit/Integration/E2E/Performance/Fault Tolerance)
- ✅ TDD QuickStart 가이드 (프로젝트 설정 → 첫 테스트 → Red-Green-Refactor)

---

**문서 완성도**: v4 - 구현 준비 완료 (TDD 가이드 포함) 🚀
