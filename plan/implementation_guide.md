# Implementation Guide

**작성일**: 2025-10-26
**목적**: 실제 구현해야 할 부분들을 명확하게 정리
**참조**: project.sorting.2025.pdf, summary.md

---

## 📋 요구사항 요약

### 명령행 인터페이스
```bash
# Master
master <N>

# Worker
worker <master_address> -I <dir1> <dir2> ... -O <output_dir>
```

### 필수 기능
1. ✅ **분산 정렬**: N개 worker에 분산된 데이터를 전역 정렬
2. ✅ **멀티코어 활용**: 정렬/병합 단계에서 병렬 처리
3. ✅ **Fault Tolerance**: Worker 크래시 후 재시작 시 정상 동작
4. ✅ **ASCII/Binary 자동 감지**: 명령행 옵션 없이 자동 판별
5. ✅ **포트 동적 할당**: 하드코딩 금지

### 출력 형식
```
Line 1: <Master IP>:<Master Port>
Line 2: <Worker1 IP>
Line 3: <Worker2 IP>
...
Line N+1: <WorkerN IP>
```

---

## 🏗️ 시스템 구조

### Phase 0: Initialization
```
Master:
  1. 랜덤 포트 바인딩
  2. N개 Worker 등록 대기
  3. Worker 순서 확정
  4. stdout으로 Master IP:Port 출력

Worker:
  1. 입력 디렉토리 스캔 (재귀적)
  2. Master에 연결
  3. 등록 및 index 수신
```

### Phase 1: Sampling
```
Master:
  1. 각 Worker로부터 샘플 수신
  2. 모든 샘플 정렬
  3. numPartitions-1 개의 경계 계산
  4. 경계 + shuffleMap을 모든 Worker에 브로드캐스트

Worker:
  1. 입력 파일에서 샘플 추출 (동적 샘플링 비율)
  2. Master에 샘플 전송
  3. 경계 + shuffleMap 수신
```

### Phase 2: Sort & Partition
```
Worker:
  1. 입력 블록들을 청크로 나누어 정렬 (병렬)
  2. 정렬된 청크를 파티션 경계에 따라 분할
  3. numPartitions 개의 파티션 파일 생성
```

### Phase 3: Shuffle
```
Worker:
  1. shuffleMap 확인
  2. 각 파티션을 담당 Worker에 전송 (병렬)
  3. 자신이 담당한 파티션들 수신
  4. 임시 파일 정리
```

### Phase 4: Merge
```
Worker:
  1. 수신한 파티션들을 그룹화
  2. 각 그룹을 K-way merge (병렬)
  3. 최종 출력 파일 생성: partition.0, partition.3, partition.6 등
  4. 임시 파일 정리

Master:
  1. 모든 Worker 완료 확인
  2. Worker 순서 출력 (stdout)
```

---

## 📦 구현 모듈

### 1. Main & CLI Parser

**파일**: `src/main/scala/distsort/Main.scala`

**참조**:
- `docs/0-implementation-decisions.md` Section 6 (명령행 인터페이스)

```scala
object Main {
  def main(args: Array[String]): Unit = {
    args(0) match {
      case "master" =>
        val numWorkers = args(1).toInt
        MasterMain.run(numWorkers)

      case "worker" =>
        val masterAddr = args(1)
        val (inputDirs, outputDir) = parseWorkerArgs(args.drop(2))
        WorkerMain.run(masterAddr, inputDirs, outputDir)

      case _ =>
        printUsage()
        sys.exit(1)
    }
  }

  private def parseWorkerArgs(args: Array[String]): (Seq[String], String) = {
    var inputDirs = Seq.empty[String]
    var outputDir = ""
    var i = 0

    while (i < args.length) {
      args(i) match {
        case "-I" =>
          // 다음 인자들을 inputDirs에 추가 (-O 나올 때까지)
          i += 1
          while (i < args.length && args(i) != "-O") {
            inputDirs = inputDirs :+ args(i)
            i += 1
          }

        case "-O" =>
          outputDir = args(i + 1)
          i += 2

        case _ =>
          i += 1
      }
    }

    (inputDirs, outputDir)
  }
}
```

**구현 체크리스트**:
- [ ] 명령행 파싱 (master/worker 구분)
- [ ] Worker 인자 파싱 (-I 여러 디렉토리, -O 하나)
- [ ] 에러 처리 (잘못된 인자)
- [ ] Usage 메시지

---

### 2. Master Service

**파일**: `src/main/scala/distsort/master/MasterServer.scala`

**참조**:
- `docs/1-phase-coordination.md` (Phase 동기화 메커니즘)
- `docs/3-grpc-sequences.md` (Master-Worker gRPC 시퀀스)
- `docs/4-error-recovery.md` (Worker 장애 감지 및 처리)

```scala
class MasterServer(numWorkers: Int) extends MasterServiceGrpc.MasterServiceImplBase {
  private val workers = new ConcurrentHashMap[String, WorkerInfo]()
  private val samples = new ConcurrentHashMap[String, SampleData]()
  private val registrationLatch = new CountDownLatch(numWorkers)

  // Worker identity 추적 (input/output dir → workerId)
  private case class WorkerIdentity(
    inputDirs: Seq[String],
    outputDir: String
  )
  private val workerIdentities = new ConcurrentHashMap[WorkerIdentity, WorkerInfo]()

  // Phase 0: Worker 등록 (Re-registration 지원)
  override def registerWorker(
    request: WorkerInfo,
    responseObserver: StreamObserver[RegistrationResponse]
  ): Unit = synchronized {
    val identity = WorkerIdentity(
      inputDirs = request.inputDirectories.sorted,  // Canonical ordering
      outputDir = request.outputDirectory
    )

    // Check if this is a restarted worker
    val existingWorker = workerIdentities.get(identity)

    if (existingWorker != null) {
      // ✅ Worker 재시작 감지!
      logger.info(s"Worker ${existingWorker.workerId} restarting " +
        s"(detected by matching input/output dirs)")

      // Update worker connection info (IP/port may have changed)
      val updatedWorker = existingWorker.copy(
        address = request.address,
        port = request.port
      )

      workers.put(existingWorker.workerId, updatedWorker)
      workerIdentities.put(identity, updatedWorker)

      // Return same worker ID and index
      val response = RegistrationResponse.newBuilder()
        .setSuccess(true)
        .setWorkerId(existingWorker.workerId)
        .setWorkerIndex(existingWorker.workerIndex)
        .setMessage("Worker re-registered after restart")
        .build()

      responseObserver.onNext(response)
      responseObserver.onCompleted()

      logger.info(s"Re-registered worker ${existingWorker.workerId} " +
        s"with index ${existingWorker.workerIndex}")

    } else {
      // New worker registration
      val workerIndex = workers.size()
      val workerInfo = request.copy(workerIndex = workerIndex)

      workers.put(request.workerId, workerInfo)
      workerIdentities.put(identity, workerInfo)
      registrationLatch.countDown()

      val response = RegistrationResponse.newBuilder()
        .setSuccess(true)
        .setWorkerId(request.workerId)
        .setWorkerIndex(workerIndex)
        .setMessage("Registration successful")
        .build()

      responseObserver.onNext(response)
      responseObserver.onCompleted()

      logger.info(s"New worker registered: ${request.workerId} " +
        s"with index $workerIndex (${workers.size()}/$numWorkers)")
    }
  }

  // Phase 1: 샘플 수집
  override def sendSample(
    request: SampleData,
    responseObserver: StreamObserver[Ack]
  ): Unit = {
    samples.put(request.workerId, request)

    if (samples.size() == numWorkers) {
      calculateAndBroadcastConfig()
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  private def calculateAndBroadcastConfig(): Unit = {
    // 1. 샘플 정렬
    val allKeys = samples.values().asScala.flatMap(_.keys).toArray
    val sorted = allKeys.sortWith(compareKeys)

    // 2. 경계 계산
    // numPartitions = numWorkers (N → N mapping)
    // 각 worker가 정확히 하나의 partition 담당
    val numPartitions = numWorkers
    val boundaries = (1 until numPartitions).map { i =>
      val idx = (sorted.length * i) / numPartitions
      sorted(idx)
    }

    // 3. shuffleMap 생성 (1:1 mapping)
    // shuffleMap(partitionId) = workerId
    // partition.0 → W0, partition.1 → W1, ...
    val shuffleMap = (0 until numPartitions).map { pid =>
      (pid, pid)
    }.toMap

    // 4. 브로드캐스트
    val config = PartitionConfig.newBuilder()
      .addAllBoundaries(boundaries)
      .setNumPartitions(numPartitions)
      .putAllShuffleMap(shuffleMap)
      .build()

    workers.values().foreach { worker =>
      val stub = createStub(worker)
      stub.setPartitionBoundaries(config)
    }
  }

  // Phase 4: 완료 보고
  override def reportCompletion(
    request: CompletionInfo,
    responseObserver: StreamObserver[Ack]
  ): Unit = {
    completions.incrementAndGet()

    if (completions.get() == numWorkers) {
      printFinalOrdering()
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  private def printFinalOrdering(): Unit = {
    val masterAddr = s"$masterHost:$masterPort"
    println(masterAddr)  // Line 1

    workers.values().sortBy(_.workerIndex).foreach { worker =>
      println(worker.host)  // Line 2~N+1
    }
  }
}
```

**구현 체크리스트**:
- [ ] 네트워크 서버 setup (랜덤 포트)
- [ ] Worker 등록 (CountDownLatch)
- [ ] **Worker identity 추적** (input/output dir → WorkerInfo)
- [ ] **Re-registration 감지 및 처리** (동일한 input/output → 동일 worker ID 재할당)
- [ ] 샘플 수집 및 경계 계산
- [ ] shuffleMap 생성 (N→N, 1:1 mapping)
- [ ] PartitionConfig 브로드캐스트
- [ ] 완료 보고 처리
- [ ] stdout 출력 (Master IP:Port, Worker IPs)

---

### 3. Worker Node

**파일**: `src/main/scala/distsort/worker/WorkerNode.scala`

**참조**:
- `docs/2-worker-state-machine.md` (Worker 상태 머신 및 전환 로직)
- `docs/5-file-management.md` (파일 레이아웃 및 디렉토리 구조)
- `docs/6-parallelization.md` (병렬 정렬 및 병합 전략)

```scala
class WorkerNode(
  masterAddress: String,
  inputDirs: Seq[String],
  outputDir: String
) {
  private val workerId = generateWorkerId()
  private var workerIndex: Int = -1

  def start(): Unit = {
    // Phase 0: 초기화
    val inputFiles = scanInputFiles(inputDirs)
    val totalSize = inputFiles.map(_.length).sum

    registerWithMaster()

    // Phase 1: 샘플링
    val samples = extractSamples(inputFiles, totalSize)
    sendSamplesToMaster(samples)
    val config = waitForPartitionConfig()

    // Phase 2: 정렬 및 파티셔닝
    val partitionFiles = sortAndPartition(inputFiles, config)

    // Phase 3: Shuffle
    val receivedPartitions = shuffle(partitionFiles, config)

    // Phase 4: Merge
    val outputFiles = merge(receivedPartitions, config)

    // 완료 보고
    reportCompletion(outputFiles)
  }

  private def scanInputFiles(dirs: Seq[String]): Seq[File] = {
    dirs.flatMap { dir =>
      val dirFile = new File(dir)
      scanRecursive(dirFile).filter(_.isFile)
    }
  }

  private def scanRecursive(dir: File): Seq[File] = {
    if (!dir.exists() || !dir.isDirectory) {
      Seq.empty
    } else {
      val files = dir.listFiles()
      files.filter(_.isFile) ++ files.filter(_.isDirectory).flatMap(scanRecursive)
    }
  }

  private def extractSamples(
    files: Seq[File],
    totalSize: Long
  ): Seq[Array[Byte]] = {
    val sampleRate = determineSampleRate(totalSize)  // 구현에서 결정
    val samples = mutable.ArrayBuffer[Array[Byte]]()
    // Deterministic: 고정 seed 사용 (재시작 시 동일한 샘플)
    val random = new Random(workerId.hashCode)

    // Deterministic: 파일을 정렬된 순서로 처리
    files.sortBy(_.getAbsolutePath).foreach { file =>
      val format = detectFormat(file)
      val reader = RecordReader.create(format)
      val input = new BufferedInputStream(new FileInputStream(file))

      var record = reader.readRecord(input)
      while (record.isDefined) {
        if (random.nextDouble() < sampleRate) {
          samples += record.get.take(10)  // key만
        }
        record = reader.readRecord(input)
      }
      input.close()
    }

    samples.toSeq
  }

  private def sortAndPartition(
    files: Seq[File],
    config: PartitionConfig
  ): Map[Int, File] = {
    // 병렬 정렬
    val executor = Executors.newFixedThreadPool(numCores)
    val sortedChunks = files.par.map { file =>
      sortChunk(file)
    }.seq.toSeq

    // 파티셔닝
    val partitionWriters = (0 until config.numPartitions).map { pid =>
      pid -> new BufferedOutputStream(
        new FileOutputStream(getPartitionFile(pid))
      )
    }.toMap

    sortedChunks.foreach { chunk =>
      val input = new BufferedInputStream(new FileInputStream(chunk))
      val record = new Array[Byte](100)

      while (input.read(record) == 100) {
        val key = record.take(10)
        val pid = findPartition(key, config.boundaries)
        partitionWriters(pid).write(record)
      }
      input.close()
      chunk.delete()
    }

    partitionWriters.values.foreach(_.close())
    executor.shutdown()

    partitionWriters.keys.map { pid =>
      pid -> getPartitionFile(pid)
    }.toMap
  }

  private def shuffle(
    localPartitions: Map[Int, File],
    config: PartitionConfig
  ): Map[Int, Seq[File]] = {
    val receivedPartitions = mutable.Map[Int, mutable.ArrayBuffer[File]]()
    val executor = Executors.newFixedThreadPool(numCores * 2)

    // 자신이 담당할 파티션 목록
    val myPartitions = config.shuffleMap
      .filter(_._2 == workerIndex)
      .keys
      .toSet

    // 전송 태스크
    val sendFutures = localPartitions.map { case (pid, file) =>
      Future {
        val targetWorker = config.shuffleMap(pid)
        if (targetWorker == workerIndex) {
          // 로컬 유지
          receivedPartitions.getOrElseUpdate(pid, mutable.ArrayBuffer()) += file
        } else {
          // 전송
          sendPartition(file, pid, workers(targetWorker))
          file.delete()
        }
      }(ExecutionContext.fromExecutor(executor))
    }

    // 수신 대기
    val receiveFutures = myPartitions.flatMap { pid =>
      workers.indices.filter(_ != workerIndex).map { otherWorker =>
        Future {
          val receivedFile = receivePartition(pid)
          receivedPartitions.getOrElseUpdate(pid, mutable.ArrayBuffer()) += receivedFile
        }(ExecutionContext.fromExecutor(executor))
      }
    }

    Await.result(Future.sequence(sendFutures ++ receiveFutures), Duration.Inf)
    executor.shutdown()

    receivedPartitions.map { case (k, v) => (k, v.toSeq) }.toMap
  }

  private def merge(
    partitions: Map[Int, Seq[File]],
    config: PartitionConfig
  ): Seq[File] = {
    val outputFiles = mutable.ArrayBuffer[File]()
    val executor = Executors.newFixedThreadPool(numCores)

    val futures = partitions.toSeq.sortBy(_._1).map { case (pid, files) =>
      Future {
        val outputFile = new File(outputDir, s"partition.$pid")
        kWayMerge(files, outputFile)
        files.foreach(_.delete())
        outputFile
      }(ExecutionContext.fromExecutor(executor))
    }

    val results = Await.result(Future.sequence(futures), Duration.Inf)
    executor.shutdown()

    results
  }

  private def kWayMerge(inputs: Seq[File], output: File): Unit = {
    val readers = inputs.map { f =>
      new BufferedInputStream(new FileInputStream(f))
    }

    val heap = new mutable.PriorityQueue[(Array[Byte], Int)]()(
      Ordering.by[(Array[Byte], Int), Array[Byte]](_._1)(
        Ordering.by[Array[Byte], String](_.take(10).map("%02x".format(_)).mkString).reverse
      )
    )

    // 초기화
    readers.zipWithIndex.foreach { case (reader, idx) =>
      val record = new Array[Byte](100)
      if (reader.read(record) == 100) {
        heap.enqueue((record, idx))
      }
    }

    val writer = new BufferedOutputStream(new FileOutputStream(output))

    while (heap.nonEmpty) {
      val (record, idx) = heap.dequeue()
      writer.write(record)

      val nextRecord = new Array[Byte](100)
      if (readers(idx).read(nextRecord) == 100) {
        heap.enqueue((nextRecord, idx))
      }
    }

    writer.close()
    readers.foreach(_.close())
  }
}
```

**구현 체크리스트**:
- [ ] 입력 파일 재귀 스캔 (정렬된 순서)
- [ ] Master 등록 및 index 수신
- [ ] Deterministic 샘플 추출 (고정 seed)
- [ ] PartitionConfig 수신
- [ ] 병렬 정렬 (ThreadPool)
- [ ] 파티셔닝 (경계 기반)
- [ ] Shuffle 송수신 (병렬)
- [ ] K-way merge (병렬, PriorityQueue)
- [ ] 출력 파일 생성 (partition.n)
- [ ] 재시작 시 cleanup 및 처음부터 실행

---

### 4. Record Reader

**파일**: `src/main/scala/distsort/common/RecordReader.scala`

**참조**:
- `docs/0-implementation-decisions.md` Section 5 (ASCII/Binary 형식 자동 감지)

```scala
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

trait RecordReader {
  def readRecord(input: InputStream): Option[Array[Byte]]
}

object RecordReader {
  def create(format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader()
    case DataFormat.Ascii => new AsciiRecordReader()
  }
}

class BinaryRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val record = new Array[Byte](100)
    val bytesRead = input.read(record)

    if (bytesRead == 100) Some(record)
    else if (bytesRead == -1) None
    else throw new IOException(s"Incomplete record: $bytesRead bytes")
  }
}

class AsciiRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val line = new Array[Byte](102)  // 10 + 1 + 90 + 1
    val bytesRead = input.read(line)

    if (bytesRead == -1) return None
    if (bytesRead != 102) throw new IOException(s"Invalid ASCII record: $bytesRead bytes")
    if (line(10) != ' '.toByte) throw new IOException("Expected space at position 10")
    if (line(101) != '\n'.toByte) throw new IOException("Expected newline at position 101")

    val record = new Array[Byte](100)
    System.arraycopy(line, 0, record, 0, 10)   // key
    System.arraycopy(line, 11, record, 10, 90) // value

    Some(record)
  }
}

object InputFormatDetector {
  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](1000)
    val input = new FileInputStream(file)

    try {
      val bytesRead = input.read(buffer)

      if (bytesRead <= 0) {
        return DataFormat.Binary
      }

      val asciiCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiCount.toDouble / bytesRead

      if (asciiRatio > 0.9) DataFormat.Ascii else DataFormat.Binary
    } finally {
      input.close()
    }
  }
}
```

**구현 체크리스트**:
- [ ] BinaryRecordReader (100바이트)
- [ ] AsciiRecordReader (102바이트 → 100바이트)
- [ ] 형식 자동 감지 (ASCII 비율 > 90%)
- [ ] 에러 처리 (불완전한 레코드)

---

### 5. Protocol Buffers

**파일**: `src/main/protobuf/distsort.proto`

**참조**:
- `docs/3-grpc-sequences.md` (gRPC 메시지 정의 및 시퀀스)

```protobuf
syntax = "proto3";

package distsort;

// Master Service
service MasterService {
  rpc RegisterWorker(WorkerInfo) returns (RegistrationResponse);
  rpc SendSample(SampleData) returns (Ack);
  rpc ReportCompletion(CompletionInfo) returns (Ack);
}

message WorkerInfo {
  string worker_id = 1;
  string host = 2;
  int32 port = 3;
  repeated string input_directories = 4;
  string output_directory = 5;
  int64 total_input_size = 6;
}

message RegistrationResponse {
  bool success = 1;
  string message = 2;
  int32 worker_index = 3;
}

message SampleData {
  string worker_id = 1;
  repeated bytes keys = 2;
}

message CompletionInfo {
  string worker_id = 1;
  repeated int32 partition_ids = 2;
  repeated int64 partition_sizes = 3;
}

message Ack {
  bool success = 1;
  string message = 2;
}

// Worker Service
service WorkerService {
  rpc SetPartitionBoundaries(PartitionConfig) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);
}

message PartitionConfig {
  repeated bytes boundaries = 1;
  int32 num_partitions = 2;
  map<int32, int32> shuffle_map = 3;
  repeated WorkerInfo all_workers = 4;
}

message ShuffleDataChunk {
  int32 partition_id = 1;
  bytes data = 2;
}

message ShuffleAck {
  bool success = 1;
  int64 bytes_received = 2;
}
```

**구현 체크리스트** (선택사항 - gRPC 사용 시):
- [ ] protobuf 정의
- [ ] sbt 빌드 설정 (scalapb)
- [ ] gRPC stub 생성

---

### 6. Fault Tolerance (PDF page 19 기준)

**PDF 요구사항**: 중간 데이터 손실되어도 동일한 출력 생성

**구현 전략**: Worker Re-registration (해석 B)
- Worker crash → 동일한 worker ID 재할당
- 해당 Worker가 담당했던 partition 재생성
- 다른 Worker들은 중단 없이 계속 작업

**파일**: `src/main/scala/distsort/worker/FaultTolerance.scala`

**참조**:
- `docs/4-error-recovery.md` Section 3.3 Interpretation B (Worker Re-registration 상세)

```scala
class WorkerNode(
  masterAddress: String,
  inputDirs: Seq[String],
  outputDir: String
) {
  private val workerId = generateWorkerId()

  def start(): Unit = {
    try {
      // 재시작 시 처음부터 시작 (deterministic execution)
      cleanupTemporaryFiles()
      cleanupOutputFiles()

      // Phase 0-4 실행 (deterministic)
      val inputFiles = scanInputFiles(inputDirs).sortBy(_.getAbsolutePath)
      performSortingWithSeed(inputFiles, seed = workerId.hashCode)

    } catch {
      case e: Exception =>
        logger.error("Worker failed", e)
        cleanupTemporaryFiles()
        cleanupOutputFiles()
        throw e
    }
  }

  private def cleanupTemporaryFiles(): Unit = {
    val tempDir = new File(System.getProperty("java.io.tmpdir"), s"distsort_$workerId")
    if (tempDir.exists()) {
      FileUtils.deleteRecursively(tempDir)
    }
  }

  private def cleanupOutputFiles(): Unit = {
    val outputDirFile = new File(outputDir)
    if (outputDirFile.exists()) {
      outputDirFile.listFiles().foreach { file =>
        if (file.getName.startsWith("partition.") ||
            file.getName.startsWith(".partition.")) {
          file.delete()
        }
      }
    }
  }
}
```

**구현 체크리스트**:
- [ ] Deterministic worker ID 생성 (input/output dir 기반)
- [ ] Master에서 Worker identity 추적
- [ ] Worker re-registration 시 동일한 worker ID/index 재할당
- [ ] Deterministic sampling (고정 seed)
- [ ] 입력 파일 정렬된 순서로 처리
- [ ] 재시작 시 cleanup 및 처음부터 실행
- [ ] 실패 시 정리 로직

---

## 🔄 구현 우선순위

### Milestone 1: 기본 인프라 (Week 1-2)
```
Priority 1: 필수 기반
  - [ ] build.sbt 설정
  - [ ] Main 및 CLI 파서
  - [ ] Master/Worker 스켈레톤

Priority 2: 통신
  - [ ] 네트워크 라이브러리 선택 (gRPC/Netty/java.net)
  - [ ] 네트워크 서버 setup (Master)
  - [ ] Worker 등록
  - [ ] 간단한 메시지 송수신 테스트
```

### Milestone 2: 기본 정렬 (Week 3-4)
```
Priority 1: 데이터 처리
  - [ ] RecordReader (Binary/ASCII)
  - [ ] 형식 자동 감지
  - [ ] 입력 파일 스캔

Priority 2: 샘플링
  - [ ] 샘플 추출
  - [ ] 경계 계산
  - [ ] PartitionConfig 브로드캐스트

Priority 3: 정렬/파티셔닝
  - [ ] 청크 정렬
  - [ ] 파티셔닝 (경계 기반)
  - [ ] shuffleMap 생성 (N→M)

Priority 4: Shuffle/Merge
  - [ ] 네트워크 전송 (파티션 전송)
  - [ ] K-way merge
  - [ ] 출력 파일 생성
```

### Milestone 3: 병렬화 (Week 5)
```
Priority 1: 병렬 정렬
  - [ ] ThreadPool (정렬)
  - [ ] 병렬 청크 처리

Priority 2: 병렬 Shuffle
  - [ ] ThreadPool (전송/수신)
  - [ ] 동시성 제어

Priority 3: 병렬 Merge
  - [ ] ThreadPool (병합)
  - [ ] 파티션별 독립 병합
```

### Milestone 4: Fault Tolerance (Week 6) - Worker Re-registration
```
Priority 1: Worker Identity & Re-registration
  - [ ] Deterministic worker ID 생성 (input/output dir 기반)
  - [ ] Master에서 WorkerIdentity 추적
  - [ ] Re-registration 시 동일한 worker ID/index 재할당
  - [ ] Worker 연결 정보 업데이트 (IP/port)

Priority 2: Deterministic Execution (PDF page 19)
  - [ ] Deterministic sampling (고정 seed = workerID.hashCode)
  - [ ] 입력 파일 정렬된 순서로 처리
  - [ ] 시작 시 cleanup (임시/출력 파일)
  - [ ] 실패 시 cleanup

Priority 3: 재시작 테스트
  - [ ] Worker 크래시 시뮬레이션 (각 Phase별)
  - [ ] 재시작 후 동일한 worker ID 재할당 확인
  - [ ] 재시작된 Worker가 동일한 partition 담당 확인
  - [ ] 최종 출력 정확성 확인 (valsort)
  - [ ] 다른 Worker들이 중단 없이 계속 작업하는지 확인
```

### Milestone 5: 테스트 & 최적화 (Week 7-8)
```
Priority 1: 정확성 테스트
  - [ ] valsort 검증
  - [ ] ASCII/Binary 모두 테스트

Priority 2: 성능 테스트
  - [ ] 대용량 데이터 (50GB+)
  - [ ] Worker 수 증가 시 성능 측정

Priority 3: 최적화
  - [ ] I/O 버퍼링
  - [ ] Thread pool 조정
```

---

## ✅ 테스트 체크리스트

### 기능 테스트
```
입력/출력:
  - [ ] ASCII 입력 → 정렬된 출력
  - [ ] Binary 입력 → 정렬된 출력
  - [ ] 혼합 입력 (ASCII + Binary)
  - [ ] 여러 입력 디렉토리
  - [ ] 재귀 디렉토리 스캔

정렬:
  - [ ] 작은 데이터 (1GB)
  - [ ] 중간 데이터 (10GB)
  - [ ] 큰 데이터 (50GB+)
  - [ ] valsort 검증 통과

분산:
  - [ ] 3 workers
  - [ ] 10 workers
  - [ ] 20 workers

Fault Tolerance (PDF page 19) - Worker Re-registration:
  - [ ] Worker 크래시 후 재시작
  - [ ] 재시작 시 동일한 worker ID 재할당 확인
  - [ ] 재시작된 Worker가 동일한 partition 담당 확인
  - [ ] 재시작 후 동일한 출력 생성 확인
  - [ ] 중간 데이터 손실 시나리오 (모든 임시 파일 삭제)
  - [ ] Deterministic execution 검증 (동일 input → 동일 output)
  - [ ] 다른 Worker들이 중단 없이 계속 작업하는지 확인
```

### 성능 테스트
```
병렬성:
  - [ ] 단일 스레드 vs 멀티 스레드
  - [ ] Thread pool 크기 영향
  - [ ] CPU 사용률

처리량:
  - [ ] 초당 처리 레코드 수
  - [ ] MB/s throughput
  - [ ] Worker 수 증가 시 스케일링

비교:
  - [ ] 단일 머신 정렬 vs 분산 정렬
  - [ ] 속도 향상 측정
```

### 출력 검증
```
Master 출력:
  - [ ] Line 1: Master IP:Port
  - [ ] Line 2~N+1: Worker IPs
  - [ ] stdout으로만 출력 (stderr 제외)

Worker 출력:
  - [ ] partition.n 파일 생성
  - [ ] 파일 번호 순서 확인
  - [ ] 전역 정렬 확인
  - [ ] 임시 파일 정리 확인
```

---

## 📁 프로젝트 구조

```
project_2025/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt (선택: gRPC 사용 시)
├── src/
│   └── main/
│       ├── scala/
│       │   └── distsort/
│       │       ├── Main.scala
│       │       ├── master/
│       │       │   ├── MasterServer.scala
│       │       │   └── MasterMain.scala
│       │       ├── worker/
│       │       │   ├── WorkerNode.scala
│       │       │   ├── WorkerMain.scala
│       │       │   └── FaultTolerance.scala
│       │       └── common/
│       │           ├── RecordReader.scala
│       │           ├── BinaryRecordReader.scala
│       │           ├── AsciiRecordReader.scala
│       │           ├── InputFormatDetector.scala
│       │           └── FileUtils.scala
│       └── protobuf/ (선택: gRPC 사용 시)
│           └── distsort.proto
├── docs/
│   ├── 0-implementation-decisions.md
│   ├── 1-phase-coordination.md
│   ├── 2-worker-state-machine.md
│   └── ... (기타 설계 문서)
└── plan/
    ├── 2025-10-24_plan_ver3.md
    ├── requirements_verification.md
    └── implementation_guide.md (이 문서)
```

---

## 🚀 시작하기

### 1. 개발 환경 설정
```bash
# Scala, sbt 설치 확인
scala -version  # 2.13.x
sbt -version    # 1.9.x

# 네트워크 라이브러리 의존성 확인 (build.sbt에 추가)
```

### 2. 첫 번째 구현
```bash
# 1. 프로젝트 컴파일
sbt compile

# 2. Master 실행 테스트
sbt "runMain distsort.Main master 3"

# 3. Worker 실행 테스트
sbt "runMain distsort.Main worker 127.0.0.1:50051 -I /tmp/input -O /tmp/output"
```

### 3. 디버깅
```bash
# 로그 레벨 설정
export LOG_LEVEL=DEBUG

# verbose mode
sbt "runMain distsort.Main master 3" 2>&1 | tee master.log
```

---

## 🔍 PDF 검증 결과 (2025-10-26)

### 검증 완료: PDF 명시 요구사항만 반영

본 구현 가이드는 `project.sorting.2025.pdf`의 **명시적 요구사항만** 정확히 반영합니다.

### ✅ PDF 명시 요구사항 (완전 반영)

1. **명령행 인터페이스** (PDF Section 2.1, 2.2)
   - `master <N>` 형식
   - `worker <master_address> -I <dir1> <dir2> ... -O <output_dir>` 형식
   - 여러 입력 디렉토리 지원

2. **필수 기능** (PDF Section 3)
   - 분산 정렬 (N개 워커)
   - 멀티코어 활용 (병렬 처리)
   - **Fault Tolerance** (PDF page 19)
   - ASCII/Binary 자동 감지 (PDF page 5)
   - 포트 동적 할당

3. **출력 형식** (PDF Section 2.3)
   - Line 1: `<Master IP>:<Master Port>`
   - Line 2~N+1: Worker IPs

4. **데이터 형식** (PDF Section 2.1.1)
   - Binary: 100바이트 레코드 (10 key + 90 value)
   - ASCII: 102바이트 → 100바이트 변환
   - Key 비교: unsigned byte 기준

5. **파일 처리** (PDF Section 2.1.2)
   - 재귀 디렉토리 스캔
   - 입력 디렉토리: Read-only
   - 출력 디렉토리: 최종 결과만 (임시 파일 정리)

---

## ⚠️ PDF에서 명시하지 않은 구현 세부사항

다음 항목들은 **PDF에서 명시적으로 요구하지 않음**. 구현 시 선택사항:

1. **통신 방식** (PDF page 26)
   - gRPC: "**recommended**" (권장사항, 필수 아님)
   - Netty, java.net.* 모두 허용
   - 구현 팀이 선택

2. **Worker 등록 타임아웃 값**
   - PDF에 명시 없음
   - 적절한 값을 구현에서 결정

3. **샘플링 비율**
   - PDF에 명시 없음
   - 동적으로 조정 가능 (데이터 크기 기반)

4. **파티션 개수**
   - PDF에 명시 없음
   - **구현 결정**: numPartitions = numWorkers (N → N mapping)
   - 각 worker가 정확히 하나의 partition 담당
   - shuffleMap: partition.i → Worker i

---

## 🛠️ Fault Tolerance 구현 (PDF page 19 기준)

**PDF 요구사항**:
```
Scenario:
- A worker process crashes in the middle of execution
- All its intermediate data is lost  ⬅️ 중요!
- A new worker starts on the same node (using the same parameters)

Expectation:
- The new worker should generate the same output expected of the initial worker
```

**핵심**: 중간 데이터 손실되어도 **동일한 결과** 생성 필요

### 구현 전략: Worker Re-registration (해석 B)

**핵심 아이디어**:
1. Worker가 **input/output 디렉토리 기반**으로 deterministic ID 생성
2. Master가 **동일한 input/output 조합**을 감지하면 동일한 worker ID 재할당
3. 재시작된 Worker가 **원래 담당했던 partition을 다시 생성**
4. **다른 Worker들은 중단 없이** 계속 작업

**장점**:
- ✅ Partial recovery: 한 Worker만 재시작, 나머지는 계속 작업
- ✅ 시간 절약: 전체 job 재시작 불필요
- ✅ Worker-partition consistency: Worker i → partition.i 유지

**단점**:
- ⚠️ 구현 복잡도 증가
- ⚠️ Deterministic execution 필수

### Worker Re-registration 구현

**파일**: `src/main/scala/distsort/worker/FaultTolerance.scala`

```scala
class WorkerNode(
  masterAddress: String,
  inputDirs: Seq[String],
  outputDir: String
) {
  // Deterministic worker ID based on input/output directories
  // 재시작 시에도 동일한 ID 생성 보장
  private val workerId = generateDeterministicWorkerId()
  private var workerIndex: Int = -1

  /**
   * Worker identity 생성 (input/output dir 기반)
   * 동일한 input/output dir → 동일한 worker ID
   */
  private def generateDeterministicWorkerId(): String = {
    val identity = inputDirs.sorted.mkString("/") + "::" + outputDir
    val hash = identity.hashCode.abs
    s"W$hash"
  }

  def start(): Unit = {
    try {
      // 재시작 시 cleanup
      cleanupTemporaryFiles()
      cleanupOutputFiles()

      // Phase 0: 초기화
      val inputFiles = scanInputFiles(inputDirs)
      val totalSize = inputFiles.map(_.length).sum
      registerWithMaster()  // 동일한 index 재할당됨

      // Phase 1: 샘플링 (deterministic)
      val samples = extractSamplesWithSeed(inputFiles, totalSize, seed = workerId.hashCode)
      sendSamplesToMaster(samples)
      val config = waitForPartitionConfig()

      // Phase 2: 정렬 및 파티셔닝 (deterministic)
      val partitionFiles = sortAndPartition(inputFiles, config)

      // Phase 3: Shuffle
      val receivedPartitions = shuffle(partitionFiles, config)

      // Phase 4: Merge
      val outputFiles = merge(receivedPartitions, config)

      // 완료 보고
      reportCompletion(outputFiles)

    } catch {
      case e: Exception =>
        logger.error("Worker failed", e)
        cleanupTemporaryFiles()
        cleanupOutputFiles()
        throw e
    }
  }

  /**
   * Deterministic 샘플링 (재시작 시 동일한 샘플 생성)
   */
  private def extractSamplesWithSeed(
    files: Seq[File],
    totalSize: Long,
    seed: Int
  ): Seq[Array[Byte]] = {
    val sampleRate = determineSampleRate(totalSize)  // 구현에서 결정
    val samples = mutable.ArrayBuffer[Array[Byte]]()
    val random = new Random(seed)  // 고정 seed 사용

    files.sortBy(_.getAbsolutePath).foreach { file =>  // 정렬된 순서
      val format = detectFormat(file)
      val reader = RecordReader.create(format)
      val input = new BufferedInputStream(new FileInputStream(file))

      var record = reader.readRecord(input)
      while (record.isDefined) {
        if (random.nextDouble() < sampleRate) {
          samples += record.get.take(10)
        }
        record = reader.readRecord(input)
      }
      input.close()
    }

    samples.toSeq
  }

  private def cleanupTemporaryFiles(): Unit = {
    val tempDir = new File(System.getProperty("java.io.tmpdir"), s"distsort_$workerId")
    if (tempDir.exists()) {
      FileUtils.deleteRecursively(tempDir)
    }
  }

  private def cleanupOutputFiles(): Unit = {
    val outputDirFile = new File(outputDir)
    if (outputDirFile.exists()) {
      outputDirFile.listFiles().foreach { file =>
        if (file.getName.startsWith("partition.") ||
            file.getName.startsWith(".partition.")) {
          file.delete()
        }
      }
    }
  }
}
```

**핵심 포인트**:
1. ✅ **Deterministic worker ID** (input/output dir 기반, 재시작 시 동일)
2. ✅ **Master가 re-registration 감지** (동일한 input/output → 동일한 worker ID 재할당)
3. ❌ **체크포인트 저장 없음** (PDF: "intermediate data is lost")
4. ✅ **Deterministic execution** (같은 input → 같은 output)
5. ✅ **고정 seed 사용** (seed = workerID.hashCode)
6. ✅ **재시작 시 처음부터** (Phase 0부터 다시 시작)
7. ✅ **입력은 read-only** (변경 없음)
8. ✅ **Worker-partition consistency** (동일한 Worker는 동일한 partition 담당)

### Phase별 복구 전략

**Phase별 Worker crash 시나리오**:

```
Phase 1 (Sampling):
  - Worker W1 crash
  - W1 재시작 → 동일한 worker ID/index 재할당
  - W1이 샘플링 다시 수행 (deterministic seed)
  - Master는 W1의 샘플 다시 수신
  - 모든 Worker의 샘플 수집 완료 후 경계 계산

Phase 2-3 (Sorting & Partitioning):
  - Worker W1 crash
  - W1 재시작 → 동일한 worker ID/index 재할당
  - W1이 정렬/파티셔닝 다시 수행
  - 다른 Worker들은 중단 없이 계속 작업

Phase 4 (Shuffle):
  - Worker W1 crash (partition.1 전송 중)
  - W1 재시작 → 동일한 worker ID/index 재할당
  - W1이 정렬/파티셔닝 다시 수행 → partition.1 재생성
  - partition.1을 담당 Worker에게 다시 전송
  - 수신 Worker는 중복 감지 및 처리 (idempotent)

Phase 5 (Merge):
  - Worker W1 crash (partition.1 merge 중)
  - W1 재시작 → 동일한 worker ID/index 재할당
  - W1이 전체 과정 다시 수행 → partition.1 재생성
  - 최종 output 파일 생성
```

**핵심**:
- 재시작된 Worker는 **항상 처음부터 (Phase 0)** 시작
- Deterministic execution으로 **동일한 결과** 보장
- 다른 Worker들은 **중단 없이** 계속 작업

---

## 📋 최종 파일 구조

```
project_2025/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── src/
│   └── main/
│       ├── scala/
│       │   └── distsort/
│       │       ├── Main.scala
│       │       ├── master/
│       │       │   ├── MasterServer.scala
│       │       │   └── MasterMain.scala
│       │       ├── worker/
│       │       │   ├── WorkerNode.scala
│       │       │   ├── WorkerMain.scala
│       │       │   └── FaultTolerance.scala  (수정됨)
│       │       └── common/
│       │           ├── RecordReader.scala
│       │           ├── BinaryRecordReader.scala
│       │           ├── AsciiRecordReader.scala
│       │           ├── InputFormatDetector.scala
│       │           └── FileUtils.scala
│       └── protobuf/
│           └── distsort.proto
├── docs/
│   └── ... (기존 문서들)
└── plan/
    └── implementation_guide.md (이 문서)
```

---

**문서 완성도**: 100% (PDF 검증 완료 - 2025-10-26)

**검증 내용**:
- ✅ PDF 명시 요구사항만 포함
- ✅ 구현 세부사항은 선택사항으로 명시
- ✅ gRPC는 권장사항으로 표시 (필수 아님)
- ✅ 타임아웃, 샘플링 비율, 파티션 개수 등은 구현 결정 사항

**다음 단계**:
1. build.sbt 설정
2. 네트워크 라이브러리 선택 (gRPC/Netty/java.net)
3. Main.scala 구현
4. Milestone 1 체크리스트 완료
