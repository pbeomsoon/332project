# Implementation Decisions (구현 결정 사항)

**작성일**: 2025-10-24
**목적**: PDF 요구사항 해석 및 모호한 부분에 대한 명확한 구현 결정

---

## 1. Fault Tolerance 전략 결정

### 1.1 PDF 요구사항 해석

**원문**:
> "The system must be fault-tolerant, which means that if a worker crashes and restarts, the overall computation should still produce correct results."

**핵심 질문**: "Worker crash & restart 후 어떻게 복구?"

### 1.2 구현 결정: **Graceful Degradation with Job Restart**

```
┌─────────────────────────────────────────────────────────┐
│ Fault Tolerance Strategy                                │
├─────────────────────────────────────────────────────────┤
│ Phase 1-2 (Sampling/Sorting):                          │
│   ✅ Continue with N-1 workers if >50% alive           │
│   ✅ Recompute boundaries with remaining samples        │
│                                                         │
│ Phase 3-4 (Shuffle/Merge):                             │
│   ❌ Cannot continue - restart entire job               │
│   ✅ Worker restarts and re-registers from beginning    │
│                                                         │
│ Data Integrity:                                         │
│   ✅ Atomic writes (temp + rename)                      │
│   ✅ State-based cleanup on failure                     │
│   ✅ Idempotent operations                              │
└─────────────────────────────────────────────────────────┘
```

### 1.3 정당화 (Justification)

**왜 "전체 재시작"을 선택했나?**

1. **구현 복잡도**: Partial recovery는 Lineage tracking + Checkpoint 필요 → 프로젝트 범위 초과
2. **정확성 우선**: 부분 복구는 corner case가 많음 → 전체 재시작이 더 안전
3. **실제 시스템 사례**:
   - Hadoop 초기 버전도 JobTracker SPOF였음
   - 많은 batch processing 시스템이 job-level restart 사용
4. **PDF 해석**: "produce correct results" ← 성능보다 정확성 강조

### 1.4 Worker Restart 시나리오

```
시나리오 1: Worker crashes during Sampling (Phase 2)
──────────────────────────────────────────────────────
Before crash:
  - 5 workers registered
  - 3 workers submitted samples
  - Worker 2 crashes

Detection:
  - Master: No heartbeat from Worker 2 for 60s
  - Master: Sampling phase timeout (10 min)

Recovery:
  - Wait for remaining 4 workers to complete sampling
  - If 4 ≥ 5 * 0.5 (YES):
      → Continue with 4 workers
      → Recompute boundaries with 4 samples
      → Set numPartitions = 4
  - Else:
      → Abort job

Worker 2 restarts:
  - Tries to re-register
  - Master rejects: "Registration closed, current job in progress"
  - Worker 2 waits for next job
```

```
시나리오 2: Worker crashes during Shuffle (Phase 4)
──────────────────────────────────────────────────────
Before crash:
  - Worker 2 is sending partition.5 to Worker 3
  - Transfer is 50% complete
  - Worker 2 crashes

Detection:
  - Worker 3: Partial file received, no completion signal
  - Worker 3: Timeout after 60s, cleanup temp file
  - Master: No heartbeat from Worker 2 for 60s

Recovery:
  ❌ Cannot continue:
      - Worker 2 was responsible for partition.2
      - partition.2 data is lost (only in Worker 2's temp dir)
      - Other workers are waiting for partition.2

Action:
  - Master sends AbortRequest to all workers
  - All workers cleanup temporary files
  - Job restarts from Phase 1

Worker 2 restarts:
  - Re-registers for new job
  - Starts from Phase 1 with all other workers
```

### 1.5 개선 가능성 (향후)

**Milestone 4-5에서 선택적 구현**:

```scala
// Option A: Shuffle Output Replication (간단)
class ShuffleManager {
  def sendPartitionWithBackup(partition: File): Unit = {
    val primary = getPrimaryWorker(partitionId)
    val backup = getBackupWorker(partitionId)

    // Send to both
    Future.sequence(Seq(
      sendTo(primary, partition),
      sendTo(backup, partition)
    ))
  }
}

// Primary 실패 시 backup에서 복구 가능
```

```scala
// Option B: Checkpoint-based Recovery (복잡)
class MasterNode {
  def checkpoint(): Unit = {
    val state = CheckpointState(
      phase = currentPhase,
      workers = registeredWorkers,
      partitionConfig = config,
      completions = phaseTracker.getAll
    )
    saveToFile(state)
  }

  def recover(): Unit = {
    val state = loadCheckpoint()
    // Resume from last completed phase
    resumeFromPhase(state.phase)
  }
}
```

**결정**: Milestone 1-3에서는 "전체 재시작", Milestone 4-5에서 선택적 개선

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
출력 형식: N+1 줄 (N = Worker 수)
  Line 1: Master의 IP:port
  Line 2~N+1: 각 Worker의 IP (한 줄에 하나씩, 포트 제외)

예시 (3 workers):
192.168.1.100:5000
192.168.1.10
192.168.1.11
192.168.1.12
```

**근거**:
- PDF Algorithm에 명확히 명시됨
- Master 주소는 IP:Port 형식
- Worker 주소는 IP만 (포트 제외)
- "in order" → Worker ID 오름차순 (W0, W1, W2, ...)
- 각 Worker는 여러 partition 담당 가능 (Strategy B)
- Worker N은 partition.N, partition.N+M, partition.N+2M, ... 담당

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

    // Line 2~N+1: Worker IP (한 줄에 하나씩, 포트 제외)
    sortedWorkers.foreach { worker =>
      println(worker.address)  // IP만, 포트 제외
    }

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
192.168.1.10
192.168.1.11
192.168.1.12
[2025-10-24 18:45:23] INFO: Distributed sorting completed successfully!
```

**클라이언트 사용법**:
```bash
# Master 실행 및 출력 파싱
output=$(sbt "runMain distsort.Main master 3" 2>/dev/null)

# Line 1: Master address
master_addr=$(echo "$output" | sed -n '1p')
echo "Master: $master_addr"

# Line 2 이후: Worker IP들 (한 줄에 하나씩)
workers=($(echo "$output" | tail -n +2))

# 각 Worker의 partition 파일들 다운로드
for i in "${!workers[@]}"; do
  worker_ip="${workers[$i]}"
  echo "Downloading partitions from Worker $i at $worker_ip"

  # Worker가 담당하는 모든 partition 파일 다운로드
  # Strategy B: Worker i는 partition.i, partition.(i+N), partition.(i+2N), ... 담당
  # 예: Worker 0 → partition.0, partition.3, partition.6
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

## 8. 문서 현황

### 완료된 문서
- ✅ docs/0-implementation-decisions.md (구현 결정 사항)
- ✅ docs/1-phase-coordination.md (Phase 동기화)
- ✅ docs/2-worker-state-machine.md (Worker 상태 머신)
- ✅ docs/3-grpc-sequences.md (gRPC 시퀀스)
- ✅ docs/4-error-recovery.md (장애 복구)
- ✅ docs/5-file-management.md (파일 관리)
- ✅ docs/6-parallelization.md (병렬 처리)

### 계획 문서
- ✅ plan/2025-10-24_plan_ver3.md (전체 설계)

---

**문서 완성도**: v3 - 핵심 기능 설계 완료
