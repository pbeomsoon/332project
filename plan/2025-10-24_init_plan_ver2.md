# Distributed Sorting System - 상세 설계 문서 (v2)

**작성일**: 2025-10-24
**버전**: 2.0 (보완 사항 추가)
**프로젝트**: Fault-Tolerant Distributed Key/Value Sorting System

---

## 📋 Version 2 주요 변경사항

이 문서는 초기 계획(v1.0)을 기반으로 PDF 프로젝트 명세서 요구사항을 추가 반영한 버전입니다.

### 🔴 필수 추가 사항
1. **ASCII/Binary 입력 형식 처리** - RecordReader 추상화 및 포맷별 구현
2. **명령행 인터페이스 개선** - 여러 입력 디렉토리, 추가 옵션 플래그 지원
3. **Master 출력 형식 표준화** - stdout vs stderr 분리
4. **중간 파일 저장 위치 옵션** - `--temp` 플래그 추가

### 🟡 중요 개선 사항
5. **네트워크 타임아웃 및 재시도 로직** - 지수 백오프, Circuit breaker
6. **파티션 파일 네이밍 명확화** - `partition.N` 형식 보장
7. **샘플링 비율 동적 조정** - 입력 크기에 따른 적응적 샘플링
8. **디스크 공간 사전 확인** - 작업 시작 전 검증

---

## 목차
1. [프로젝트 개요](#1-프로젝트-개요)
2. [시스템 아키텍처](#2-시스템-아키텍처)
3. [데이터 구조 및 포맷](#3-데이터-구조-및-포맷)
4. [핵심 알고리즘 상세](#4-핵심-알고리즘-상세)
5. [네트워크 통신 설계](#5-네트워크-통신-설계)
6. [장애 허용성 메커니즘](#6-장애-허용성-메커니즘)
7. [멀티스레드 및 병렬 처리](#7-멀티스레드-및-병렬-처리)
8. [구현 세부사항](#8-구현-세부사항)
9. [성능 최적화 전략](#9-성능-최적화-전략)
10. [테스트 전략](#10-테스트-전략)
11. **[명령행 인터페이스 상세](#11-명령행-인터페이스-상세)** ⭐ NEW
12. **[입력 형식 처리](#12-입력-형식-처리)** ⭐ NEW
13. [개발 마일스톤](#13-개발-마일스톤)
14. [참고 자료](#14-참고-자료)
15. [예상 이슈 및 해결책](#15-예상-이슈-및-해결책)
16. [결론](#16-결론)

---

## 1. 프로젝트 개요

### 1.1 목표
여러 머신에 분산 저장된 대용량 key/value 레코드를 정렬하는 장애 허용성 분산 시스템 구현

### 1.2 핵심 요구사항
- **입력**: 여러 Worker 노드에 분산된 32MB 블록 단위의 미정렬 데이터
- **출력**: 전역적으로 정렬된 데이터 (각 Worker에 연속된 파티션 저장)
- **장애 허용**: Worker 프로세스 crash 후 재시작 시 올바른 결과 생성
- **확장성**: 멀티코어 활용한 병렬 처리
- **형식 지원**: ASCII 및 Binary 입력 모두 지원 ⭐ NEW

### 1.3 제약사항
- 입력 디렉토리 수정 금지 (읽기 전용)
- 출력 디렉토리에는 최종 결과만 저장
- 포트 하드코딩 금지
- Akka 사용 금지
- ASCII/Binary 형식은 명령행 플래그로 구분

---

## 2. 시스템 아키텍처

### 2.1 전체 구조

```
┌─────────────────────────────────────────────────────────┐
│                      Master Node                        │
│  - Worker 등록 관리                                      │
│  - 샘플 수집 및 파티션 경계 계산                          │
│  - 전역 조정 (Coordination)                             │
│  - 최종 Worker 순서 출력 (stdout)                        │
└────────────┬────────────────────────────────────────────┘
             │
     ┌───────┴───────┬──────────────┬──────────────┐
     │               │              │              │
┌────▼─────┐   ┌────▼─────┐  ┌────▼─────┐   ┌────▼─────┐
│ Worker 1 │   │ Worker 2 │  │ Worker 3 │   │ Worker N │
│          │   │          │  │          │   │          │
│ Input:   │   │ Input:   │  │ Input:   │   │ Input:   │
│ 50GB     │   │ 50GB     │  │ 50GB     │   │ 50GB     │
│          │   │          │  │          │   │          │
│ Output:  │   │ Output:  │  │ Output:  │   │ Output:  │
│ P0,P1,P2 │   │ P3,P4,P5 │  │ P6,P7,P8 │   │ Px,Py,Pz │
└──────────┘   └──────────┘  └──────────┘   └──────────┘
     │               │              │              │
     └───────────────┴──────────────┴──────────────┘
              Worker-to-Worker Shuffle
```

### 2.2 실행 흐름

```
Phase 0: 초기화
  - Master 시작, Worker들 등록 대기
  - 각 Worker 시작, Master에 연결 및 등록
  - 디스크 공간 검증 ⭐ NEW

Phase 1: Sampling (샘플링)
  - 각 Worker가 입력 데이터에서 샘플 추출
  - 동적 샘플링 비율 계산 ⭐ NEW
  - Master에게 샘플 전송
  - Master가 전체 샘플 정렬 및 파티션 경계 계산
  - 모든 Worker에게 파티션 경계 브로드캐스트

Phase 2: Sort & Partition (정렬 및 파티셔닝)
  - 각 Worker가 입력 블록 읽기 (ASCII/Binary 자동 처리) ⭐ NEW
  - 메모리 내 정렬
  - 파티션 경계에 따라 데이터 분할
  - 파티션별 임시 파일 생성 (--temp 디렉토리 사용) ⭐ NEW

Phase 3: Shuffle (데이터 재분배)
  - Worker 간 네트워크 통신 (재시도 로직 포함) ⭐ NEW
  - 각 파티션 데이터를 해당 Worker로 전송
  - 타임아웃 및 백오프 처리 ⭐ NEW

Phase 4: Merge (병합)
  - 각 Worker가 받은 정렬된 파티션들을 K-way merge
  - 최종 정렬된 파일 생성 (partition.0, partition.1, ...) ⭐ NEW
  - Atomic write 보장

Phase 5: 완료
  - 각 Worker가 Master에게 완료 보고
  - Master가 전체 작업 완료 확인
  - Master가 정렬된 Worker 주소 출력 (stdout) ⭐ NEW
  - 임시 파일 정리
```

---

## 3. 데이터 구조 및 포맷

### 3.1 레코드 구조

```
┌──────────────┬────────────────────────────────────────────┐
│ Key (10B)    │ Value (90B)                                │
└──────────────┴────────────────────────────────────────────┘
  0            10                                          100
```

**특징:**
- 고정 길이: 100 바이트
- Key만 정렬 기준으로 사용
- Value는 정렬과 무관하게 Key와 함께 이동

### 3.2 Key 비교 규칙

```scala
def compareKeys(keyA: Array[Byte], keyB: Array[Byte]): Int = {
  for (i <- 0 until 10) {
    if (keyA(i) != keyB(i)) {
      // unsigned byte 비교
      return (keyA(i) & 0xFF).compareTo(keyB(i) & 0xFF)
    }
  }
  0 // 동일
}
```

**주의사항:**
- 바이트 단위 사전식 비교
- Unsigned 비교 필수 (Java/Scala의 byte는 signed)
- 첫 번째로 다른 바이트에서 대소 판단

### 3.3 입력 데이터 구조

```
Input Directories:
/data1/input/
  ├── block_0001  (32MB)
  ├── block_0002  (32MB)
  └── ...

/data2/input/
  ├── block_0001  (32MB)
  └── ...

총 입력: 50GB x N workers
```

### 3.4 출력 데이터 구조 ⭐ UPDATED

```
Output Directory (각 Worker):
/output/
  ├── partition.0
  ├── partition.1
  ├── partition.2
  └── ...

파일 명명 규칙:
- 정확히 "partition.N" 형식 (N은 0부터 시작)
- 확장자 없음
- 각 Worker는 연속된 파티션 번호 저장
```

### 3.5 중간 데이터 구조 ⭐ UPDATED

```
Temporary Directory (작업 중):
${TEMP_DIR}/sort_work_${WORKER_ID}/
  ├── samples/
  │   └── sample.dat  (동적 크기)
  │
  ├── sorted_chunks/
  │   ├── chunk_0000.sorted  (100MB)
  │   ├── chunk_0001.sorted  (100MB)
  │   └── ...
  │
  ├── partitions/
  │   ├── partition_0/
  │   │   ├── from_chunk_0000.dat
  │   │   ├── from_chunk_0001.dat
  │   │   └── ...
  │   ├── partition_1/
  │   │   └── ...
  │   └── ...
  │
  └── received/
      ├── partition_0_from_worker_1.dat
      ├── partition_0_from_worker_2.dat
      └── ...

TEMP_DIR 지정:
- --temp 플래그로 지정 (기본값: /tmp)
- 충분한 디스크 공간 사전 확인 필요
- 작업 완료 후 자동 삭제
```

---

## 4. 핵심 알고리즘 상세

### 4.1 Phase 1: Sampling Algorithm ⭐ UPDATED

#### 4.1.1 동적 샘플링 비율 계산 ⭐ NEW

```scala
def calculateSampleRate(totalInputSize: Long, numWorkers: Int): Double = {
  // Worker당 목표 샘플 개수 (10,000개 key)
  val targetSamplesPerWorker = 10000
  val totalTargetSamples = targetSamplesPerWorker * numWorkers

  // 전체 레코드 수 추정
  val estimatedTotalRecords = totalInputSize / 100

  // 샘플링 비율 계산
  val calculatedRate = totalTargetSamples.toDouble / estimatedTotalRecords

  // 최소/최대 제한
  val minRate = 0.0001  // 0.01%
  val maxRate = 0.01    // 1%

  math.max(minRate, math.min(maxRate, calculatedRate))
}
```

**샘플링 전략:**
- 입력 크기에 따른 동적 비율 조정
- 최소 샘플: Worker당 1,000개 key
- 최대 샘플: Worker당 100,000개 key
- 총 샘플 크기: 일반적으로 1-10MB

#### 4.1.2 샘플 추출 (각 Worker)

```scala
def extractSample(inputDirs: List[String],
                  format: DataFormat,
                  totalInputSize: Long,
                  numWorkers: Int): Array[Array[Byte]] = {

  val sampleRate = calculateSampleRate(totalInputSize, numWorkers)
  val random = new Random(42)  // 재현 가능성을 위한 고정 seed
  val samples = mutable.ArrayBuffer[Array[Byte]]()

  val reader = createRecordReader(format)  // ⭐ NEW: 형식별 리더

  for (file <- getAllInputFiles(inputDirs)) {
    val input = new BufferedInputStream(new FileInputStream(file))

    var record = reader.readRecord(input)
    while (record.isDefined) {
      if (random.nextDouble() < sampleRate) {
        val key = record.get.take(10)
        samples += key
      }
      record = reader.readRecord(input)
    }
    input.close()
  }

  samples.toArray
}
```

#### 4.1.3 파티션 경계 계산 (Master)

```scala
def calculatePartitionBoundaries(
    allSamples: Array[Array[Byte]],
    numPartitions: Int): Array[Array[Byte]] = {

  // 1. 모든 샘플 정렬
  val sortedSamples = allSamples.sortWith(compareKeys(_, _) < 0)

  // 2. 균등 분할을 위한 경계 선택
  val boundaries = new Array[Array[Byte]](numPartitions - 1)
  val step = sortedSamples.length / numPartitions

  for (i <- 0 until numPartitions - 1) {
    boundaries(i) = sortedSamples((i + 1) * step)
  }

  boundaries
}
```

### 4.2 Phase 2: Sort & Partition Algorithm

#### 4.2.1 청크 단위 정렬 ⭐ UPDATED

```scala
def sortChunk(inputFile: File,
              format: DataFormat,
              chunkSize: Int = 100 * 1024 * 1024): File = {
  val records = new Array[Array[Byte]](chunkSize / 100)
  val reader = createRecordReader(format)  // ⭐ NEW
  val input = new BufferedInputStream(new FileInputStream(inputFile))

  var count = 0
  var record = reader.readRecord(input)

  while (count < records.length && record.isDefined) {
    records(count) = record.get
    count += 1
    record = reader.readRecord(input)
  }
  input.close()

  // 정렬 (key 기준)
  val sortedRecords = records.take(count).sortWith { (a, b) =>
    compareKeys(a.take(10), b.take(10)) < 0
  }

  // 임시 파일 저장
  val outputFile = createTempFile("chunk_", ".sorted")
  val output = new BufferedOutputStream(new FileOutputStream(outputFile))
  sortedRecords.foreach(output.write)
  output.close()

  outputFile
}
```

#### 4.2.2 파티셔닝

```scala
def partitionSortedChunk(
    sortedChunk: File,
    boundaries: Array[Array[Byte]],
    outputDir: File): Unit = {

  val partitionWriters = boundaries.indices.map { i =>
    new BufferedOutputStream(
      new FileOutputStream(new File(outputDir, s"partition_$i.dat"), true),
      1024 * 1024  // 1MB 버퍼
    )
  }.toArray :+ new BufferedOutputStream(
    new FileOutputStream(
      new File(outputDir, s"partition_${boundaries.length}.dat"), true
    ),
    1024 * 1024
  )

  val input = new BufferedInputStream(new FileInputStream(sortedChunk))
  val record = new Array[Byte](100)

  while (input.read(record) == 100) {
    val key = record.take(10)
    val partitionId = findPartitionBinarySearch(key, boundaries)  // ⭐ UPDATED
    partitionWriters(partitionId).write(record)
  }

  input.close()
  partitionWriters.foreach(_.close())
}

def findPartitionBinarySearch(key: Array[Byte],
                               boundaries: Array[Array[Byte]]): Int = {
  var left = 0
  var right = boundaries.length

  while (left < right) {
    val mid = (left + right) / 2
    if (compareKeys(key, boundaries(mid)) < 0) {
      right = mid
    } else {
      left = mid + 1
    }
  }
  left
}
```

### 4.3 Phase 3: Shuffle Algorithm ⭐ UPDATED

#### 4.3.1 송신 (Sender) with Retry ⭐ NEW

```scala
def shufflePartitionWithRetry(
    partitionFile: File,
    partitionId: Int,
    targetWorker: WorkerInfo,
    maxRetries: Int = 3): Unit = {

  var attempt = 0
  var success = false

  while (attempt < maxRetries && !success) {
    try {
      shufflePartition(partitionFile, partitionId, targetWorker)
      success = true
    } catch {
      case e: StatusRuntimeException if isRetryable(e) =>
        attempt += 1
        val backoffMs = math.pow(2, attempt).toLong * 1000  // 지수 백오프
        logger.warn(s"Shuffle failed (attempt $attempt/$maxRetries), " +
                   s"retrying in ${backoffMs}ms: ${e.getMessage}")
        Thread.sleep(backoffMs)

      case e: Exception =>
        logger.error(s"Non-retryable shuffle error: ${e.getMessage}")
        throw e
    }
  }

  if (!success) {
    throw new IOException(s"Failed to shuffle after $maxRetries attempts")
  }
}

def isRetryable(e: StatusRuntimeException): Boolean = {
  e.getStatus.getCode match {
    case Status.Code.UNAVAILABLE => true
    case Status.Code.DEADLINE_EXCEEDED => true
    case Status.Code.RESOURCE_EXHAUSTED => true
    case Status.Code.ABORTED => true
    case _ => false
  }
}

def shufflePartition(
    partitionFile: File,
    partitionId: Int,
    targetWorker: WorkerInfo): Unit = {

  val channel = createGrpcChannelWithRetry(
    targetWorker.host,
    targetWorker.port
  )  // ⭐ NEW

  val stub = WorkerServiceGrpc.newStub(channel)
  val responseObserver = new StreamObserver[ShuffleAck] {
    override def onNext(ack: ShuffleAck): Unit = {
      logger.debug(s"Received ACK: ${ack.getBytesReceived} bytes")
    }
    override def onError(t: Throwable): Unit = {
      logger.error(s"Shuffle failed: ${t.getMessage}")
    }
    override def onCompleted(): Unit = {
      logger.info(s"Shuffle completed for partition $partitionId")
    }
  }

  val requestObserver = stub.shuffleData(responseObserver)

  // 청크 단위 스트리밍 전송
  val input = new BufferedInputStream(new FileInputStream(partitionFile))
  val buffer = new Array[Byte](1024 * 1024)  // 1MB 청크

  var bytesRead = 0
  while ({bytesRead = input.read(buffer); bytesRead > 0}) {
    val chunk = ShuffleDataChunk.newBuilder()
      .setPartitionId(partitionId)
      .setData(ByteString.copyFrom(buffer, 0, bytesRead))
      .build()

    requestObserver.onNext(chunk)
  }

  input.close()
  requestObserver.onCompleted()

  // 완료 대기 및 채널 종료
  channel.shutdown()
  channel.awaitTermination(60, TimeUnit.SECONDS)
}

def createGrpcChannelWithRetry(host: String, port: Int): ManagedChannel = {
  ManagedChannelBuilder
    .forTarget(s"$host:$port")
    .usePlaintext()
    .keepAliveTime(30, TimeUnit.SECONDS)
    .keepAliveTimeout(10, TimeUnit.SECONDS)
    .idleTimeout(5, TimeUnit.MINUTES)
    .maxRetryAttempts(3)
    .retryBufferSize(16 * 1024 * 1024)  // 16MB
    .build()
}
```

#### 4.3.2 수신 (Receiver)

```scala
class WorkerServiceImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

  override def shuffleData(
      responseObserver: StreamObserver[ShuffleAck]):
      StreamObserver[ShuffleDataChunk] = {

    new StreamObserver[ShuffleDataChunk] {
      val receivedFiles = mutable.Map[Int, FileOutputStream]()

      override def onNext(chunk: ShuffleDataChunk): Unit = {
        val partitionId = chunk.getPartitionId
        val output = receivedFiles.getOrElseUpdate(
          partitionId,
          new BufferedOutputStream(
            new FileOutputStream(
              new File(receivedDir, s"partition_${partitionId}_${UUID.randomUUID()}.dat")
            ),
            1024 * 1024
          )
        )

        output.write(chunk.getData.toByteArray)
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Shuffle receive error: ${t.getMessage}")
        receivedFiles.values.foreach(_.close())
        receivedFiles.clear()
      }

      override def onCompleted(): Unit = {
        receivedFiles.values.foreach(_.close())
        responseObserver.onNext(
          ShuffleAck.newBuilder()
            .setSuccess(true)
            .setBytesReceived(receivedFiles.values.map(_.size).sum)
            .build()
        )
        responseObserver.onCompleted()
      }
    }
  }
}
```

### 4.4 Phase 4: Merge Algorithm ⭐ UPDATED

#### 4.4.1 K-way Merge with Atomic Write

```scala
case class RecordWithSource(
  record: Array[Byte],
  sourceId: Int
)

def kWayMergeAtomic(
    inputFiles: List[File],
    partitionId: Int,
    outputDir: File): Unit = {

  // 임시 파일에 먼저 쓰기 ⭐ NEW
  val tempFile = new File(outputDir, s".partition.$partitionId.tmp")

  // Priority Queue (min-heap)
  implicit val ord: Ordering[RecordWithSource] = Ordering.by { rws =>
    rws.record.take(10)
  }(Ordering.by[Array[Byte], String](_.map("%02x".format(_)).mkString).reverse)

  val heap = mutable.PriorityQueue[RecordWithSource]()
  val readers = inputFiles.zipWithIndex.map { case (file, idx) =>
    new BufferedInputStream(new FileInputStream(file), 1024 * 1024)
  }

  // 초기화: 각 파일에서 첫 레코드 읽기
  readers.zipWithIndex.foreach { case (reader, idx) =>
    val record = new Array[Byte](100)
    if (reader.read(record) == 100) {
      heap.enqueue(RecordWithSource(record.clone(), idx))
    }
  }

  val output = new BufferedOutputStream(
    new FileOutputStream(tempFile),
    4 * 1024 * 1024  // 4MB 버퍼
  )

  // Merge
  while (heap.nonEmpty) {
    val min = heap.dequeue()
    output.write(min.record)

    // 같은 소스에서 다음 레코드 읽기
    val record = new Array[Byte](100)
    if (readers(min.sourceId).read(record) == 100) {
      heap.enqueue(RecordWithSource(record, min.sourceId))
    }
  }

  output.close()
  readers.foreach(_.close())

  // Atomic rename ⭐ NEW
  val finalFile = new File(outputDir, s"partition.$partitionId")
  if (!tempFile.renameTo(finalFile)) {
    throw new IOException(s"Failed to create final partition file: $finalFile")
  }

  logger.info(s"Created partition.$partitionId (${finalFile.length() / 1e6} MB)")
}
```

---

## 5. 네트워크 통신 설계

### 5.1 Protocol Buffers 정의

```protobuf
syntax = "proto3";

package distsort;

// ========== Master Service ==========

service MasterService {
  // Worker 등록
  rpc RegisterWorker(WorkerInfo) returns (RegistrationResponse);

  // 샘플 전송
  rpc SendSample(SampleData) returns (Ack);

  // 작업 완료 보고
  rpc ReportCompletion(CompletionInfo) returns (Ack);
}

message WorkerInfo {
  string worker_id = 1;
  string host = 2;
  int32 port = 3;
  repeated string input_directories = 4;
  string output_directory = 5;
  int64 total_input_size = 6;
  DataFormat format = 7;  // ⭐ NEW
}

enum DataFormat {  // ⭐ NEW
  BINARY = 0;
  ASCII = 1;
}

message RegistrationResponse {
  bool success = 1;
  string message = 2;
  int32 worker_index = 3;
}

message SampleData {
  string worker_id = 1;
  repeated bytes keys = 2;
  int32 sample_count = 3;
}

message CompletionInfo {
  string worker_id = 1;
  repeated int32 partition_ids = 2;
  repeated int64 partition_sizes = 3;
  double elapsed_time_seconds = 4;
}

message Ack {
  bool success = 1;
  string message = 2;
}

// ========== Worker Service ==========

service WorkerService {
  rpc SetPartitionBoundaries(PartitionBoundaries) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);
  rpc GetStatus(StatusRequest) returns (WorkerStatus);
  rpc Reset(ResetRequest) returns (Ack);  // ⭐ NEW: 장애 복구용
}

message PartitionBoundaries {
  repeated bytes boundaries = 1;
  int32 num_partitions = 2;
  map<int32, WorkerInfo> partition_assignment = 3;
}

message ShuffleDataChunk {
  int32 partition_id = 1;
  bytes data = 2;
  int64 chunk_offset = 3;
  bool is_last = 4;
}

message ShuffleAck {
  bool success = 1;
  string message = 2;
  int64 bytes_received = 3;
}

message StatusRequest {
  string worker_id = 1;
}

message WorkerStatus {
  enum Phase {
    INITIALIZING = 0;
    SAMPLING = 1;
    SORTING = 2;
    SHUFFLING = 3;
    MERGING = 4;
    COMPLETED = 5;
    FAILED = 6;
  }

  Phase current_phase = 1;
  double progress_percentage = 2;
  string message = 3;
}

message ResetRequest {  // ⭐ NEW
  string reason = 1;
}
```

### 5.2 Master 구현 ⭐ UPDATED

```scala
class MasterServer(numWorkers: Int) extends MasterServiceGrpc.MasterServiceImplBase {

  private val workers = new ConcurrentHashMap[String, WorkerInfo]()
  private val samples = new ConcurrentHashMap[String, SampleData]()
  private val completions = new AtomicInteger(0)
  private val latch = new CountDownLatch(numWorkers)

  override def registerWorker(
      request: WorkerInfo,
      responseObserver: StreamObserver[RegistrationResponse]): Unit = {

    val workerId = request.getWorkerId
    workers.put(workerId, request)

    // stderr로 로그, stdout은 최종 결과 전용 ⭐ NEW
    System.err.println(s"[INFO] Worker registered: $workerId at ${request.getHost}:${request.getPort}")

    val response = RegistrationResponse.newBuilder()
      .setSuccess(true)
      .setWorkerIndex(workers.size())
      .setMessage("Registration successful")
      .build()

    responseObserver.onNext(response)
    responseObserver.onCompleted()

    latch.countDown()
  }

  override def sendSample(
      request: SampleData,
      responseObserver: StreamObserver[Ack]): Unit = {

    samples.put(request.getWorkerId, request)
    System.err.println(s"[INFO] Received sample from ${request.getWorkerId}: ${request.getSampleCount} keys")

    // 모든 샘플 도착 확인
    if (samples.size() == numWorkers) {
      calculateAndBroadcastBoundaries()
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  private def calculateAndBroadcastBoundaries(): Unit = {
    // 모든 샘플 수집
    val allKeys = samples.values().asScala.flatMap(_.getKeysList.asScala).toArray

    // 정렬
    val sortedKeys = allKeys.sortWith { (a, b) =>
      compareKeys(a.toByteArray, b.toByteArray) < 0
    }

    // 경계 계산 (균등 분배)
    val boundaries = (1 until numWorkers).map { i =>
      val idx = (sortedKeys.length * i) / numWorkers
      sortedKeys(idx)
    }

    System.err.println(s"[INFO] Calculated ${boundaries.length} partition boundaries")

    // 파티션 할당 (Worker i → Partition i)
    val assignment = workers.asScala.toList.sortBy(_._2.getWorkerIndex).zipWithIndex.map {
      case ((_, workerInfo), partitionId) => (partitionId, workerInfo)
    }.toMap

    // 모든 Worker에게 브로드캐스트
    val boundariesMsg = PartitionBoundaries.newBuilder()
      .addAllBoundaries(boundaries.map(ByteString.copyFrom).asJava)
      .setNumPartitions(numWorkers)
      .putAllPartitionAssignment(assignment.map { case (k, v) => (k: Integer, v) }.asJava)
      .build()

    workers.values().asScala.foreach { workerInfo =>
      val channel = createGrpcChannelWithRetry(
        workerInfo.getHost,
        workerInfo.getPort
      )

      val stub = WorkerServiceGrpc.newBlockingStub(channel)
      stub.setPartitionBoundaries(boundariesMsg)
      channel.shutdown()
    }
  }

  override def reportCompletion(
      request: CompletionInfo,
      responseObserver: StreamObserver[Ack]): Unit = {

    System.err.println(s"[INFO] Worker ${request.getWorkerId} completed in ${request.getElapsedTimeSeconds}s")

    if (completions.incrementAndGet() == numWorkers) {
      System.err.println("[INFO] All workers completed! Job finished.")
      printFinalOrdering()
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  private def printFinalOrdering(): Unit = {
    val orderedWorkers = workers.values().asScala.toList
      .sortBy(_.getWorkerIndex)
      .map(w => s"${w.getHost}:${w.getPort}")

    // ⭐ NEW: stdout으로만 출력 (로그와 분리)
    System.out.println(orderedWorkers.mkString(", "))
    System.out.flush()
  }
}
```

---

## 6. 장애 허용성 메커니즘

### 6.1 장애 시나리오

```
시나리오 1: Sort 중 Worker Crash
  Worker2 정렬 중 → 프로세스 종료
  → 임시 파일 일부만 생성된 상태
  → 재시작 시 임시 파일 삭제 후 처음부터

시나리오 2: Shuffle 중 Worker Crash
  Worker2가 데이터 송신 중 → 프로세스 종료
  → 일부 Worker는 데이터 받았지만 나머지는 못 받음
  → 재시작 시 모든 Worker가 Shuffle 재시작

시나리오 3: Merge 중 Worker Crash
  Worker2가 병합 중 → 프로세스 종료
  → 최종 출력 파일 미완성
  → 재시작 시 병합 재시작
```

### 6.2 복구 전략

#### 6.2.1 멱등성 보장

```scala
class WorkerNode {

  def start(): Unit = {
    try {
      // 시작 전 정리
      cleanupTemporaryFiles()
      cleanupOutputFiles()

      // 정상 작업 수행
      performSorting()

    } catch {
      case e: Exception =>
        logger.error("Worker failed", e)
        cleanupTemporaryFiles()
        cleanupOutputFiles()
        throw e
    }
  }

  private def cleanupTemporaryFiles(): Unit = {
    val tempDir = new File(config.tempDir, s"sort_work_$workerId")
    if (tempDir.exists()) {
      FileUtils.deleteRecursively(tempDir)
      logger.info("Cleaned up temporary files")
    }
  }

  private def cleanupOutputFiles(): Unit = {
    val outputDirFile = new File(config.outputDir)
    if (outputDirFile.exists()) {
      outputDirFile.listFiles().foreach { file =>
        if (file.getName.startsWith("partition.") ||
            file.getName.startsWith(".partition.")) {  // ⭐ NEW: 임시 파일도 삭제
          file.delete()
          logger.info(s"Deleted incomplete output: ${file.getName}")
        }
      }
    }
  }
}
```

---

## 7. 멀티스레드 및 병렬 처리

### 7.1 Sort & Partition 병렬화

```scala
class ParallelSorter(numThreads: Int = 4) {

  private val executor = Executors.newFixedThreadPool(numThreads)

  def sortAll(inputFiles: List[File], format: DataFormat): List[File] = {
    val futures = inputFiles.map { file =>
      executor.submit(new Callable[File] {
        override def call(): File = sortChunk(file, format)
      })
    }

    futures.map(_.get())
  }

  def partitionAll(sortedChunks: List[File],
                   boundaries: Array[Array[Byte]]): Unit = {
    val futures = sortedChunks.map { chunk =>
      executor.submit(new Callable[Unit] {
        override def call(): Unit = partitionSortedChunk(chunk, boundaries)
      })
    }

    futures.foreach(_.get())
  }

  def shutdown(): Unit = {
    executor.shutdown()
    executor.awaitTermination(1, TimeUnit.HOURS)
  }
}
```

---

## 8. 구현 세부사항

### 8.1 프로젝트 구조 ⭐ UPDATED

```
project_2025/
├── build.sbt
├── project/
│   └── build.properties
├── src/
│   └── main/
│       ├── scala/
│       │   └── distsort/
│       │       ├── Main.scala
│       │       ├── master/
│       │       │   ├── MasterServer.scala
│       │       │   ├── PartitionCalculator.scala
│       │       │   └── WorkerRegistry.scala
│       │       ├── worker/
│       │       │   ├── WorkerNode.scala
│       │       │   ├── Sampler.scala
│       │       │   ├── Sorter.scala
│       │       │   ├── Partitioner.scala
│       │       │   ├── Shuffler.scala
│       │       │   └── Merger.scala
│       │       ├── common/
│       │       │   ├── RecordComparator.scala
│       │       │   ├── RecordReader.scala          ⭐ NEW
│       │       │   ├── BinaryRecordReader.scala    ⭐ NEW
│       │       │   ├── AsciiRecordReader.scala     ⭐ NEW
│       │       │   ├── FileUtils.scala
│       │       │   ├── NetworkUtils.scala
│       │       │   └── CommandLineParser.scala     ⭐ NEW
│       │       └── proto/
│       │           └── (generated proto files)
│       ├── protobuf/
│       │   └── distsort.proto
│       └── resources/
│           └── logback.xml
├── plan/
│   ├── 2025-10-24_init_plan.md
│   └── 2025-10-24_init_plan_ver2.md         ⭐ NEW
└── README.md
```

### 8.2 build.sbt

```scala
name := "distributed-sorting"
version := "1.0"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  // gRPC
  "io.grpc" % "grpc-netty" % "1.58.0",
  "io.grpc" % "grpc-protobuf" % "1.58.0",
  "io.grpc" % "grpc-stub" % "1.58.0",
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % "0.11.13",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

// Protocol Buffers 설정
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)
```

---

## 9. 성능 최적화 전략

### 9.1 I/O 최적화

#### 9.1.1 버퍼링

```scala
// 잘못된 예: 버퍼링 없음
val input = new FileInputStream(file)
val record = new Array[Byte](100)
while (input.read(record) == 100) {  // 매번 시스템 콜!
  process(record)
}

// 올바른 예: 버퍼링 사용
val input = new BufferedInputStream(
  new FileInputStream(file),
  1024 * 1024  // 1MB 버퍼
)
val record = new Array[Byte](100)
while (input.read(record) == 100) {
  process(record)
}
```

### 9.2 디스크 공간 사전 확인 ⭐ NEW

```scala
def ensureSufficientDiskSpace(
    tempDir: File,
    outputDir: File,
    inputSize: Long): Unit = {

  // 필요 공간 추정
  val requiredTemp = inputSize * 2  // 정렬 중간 파일용
  val requiredOutput = inputSize    // 최종 출력용

  val tempAvailable = tempDir.getUsableSpace
  val outputAvailable = outputDir.getUsableSpace

  if (tempAvailable < requiredTemp * 1.5) {
    throw new IOException(
      f"Insufficient temp disk space: ${tempAvailable / 1e9}%.1f GB available, " +
      f"${requiredTemp * 1.5 / 1e9}%.1f GB required"
    )
  }

  if (outputAvailable < requiredOutput * 1.2) {
    throw new IOException(
      f"Insufficient output disk space: ${outputAvailable / 1e9}%.1f GB available, " +
      f"${requiredOutput * 1.2 / 1e9}%.1f GB required"
    )
  }

  logger.info(f"Disk space check passed: " +
             f"Temp ${tempAvailable / 1e9}%.1f GB, " +
             f"Output ${outputAvailable / 1e9}%.1f GB")
}
```

---

## 10. 테스트 전략

### 10.1 단위 테스트

```scala
class RecordComparatorTest extends AnyFlatSpec with Matchers {

  "compareKeys" should "compare unsigned bytes correctly" in {
    val key1 = Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00,
                           0x00, 0x00, 0x00, 0x00, 0x00)
    val key2 = Array[Byte](0xFF.toByte, 0xFF.toByte, 0xFF.toByte,
                           0xFF.toByte, 0xFF.toByte, 0xFF.toByte,
                           0xFF.toByte, 0xFF.toByte, 0xFF.toByte,
                           0xFF.toByte)

    RecordComparator.compareKeys(key1, key2) should be < 0
    RecordComparator.compareKeys(key2, key1) should be > 0
    RecordComparator.compareKeys(key1, key1) shouldEqual 0
  }
}

class RecordReaderTest extends AnyFlatSpec with Matchers {  // ⭐ NEW

  "BinaryRecordReader" should "read 100-byte records" in {
    val data = Array.fill(300)(0.toByte)
    val input = new ByteArrayInputStream(data)
    val reader = new BinaryRecordReader()

    val record1 = reader.readRecord(input)
    val record2 = reader.readRecord(input)
    val record3 = reader.readRecord(input)
    val record4 = reader.readRecord(input)

    record1 shouldBe defined
    record2 shouldBe defined
    record3 shouldBe defined
    record4 shouldBe empty
  }

  "AsciiRecordReader" should "parse ASCII format correctly" in {
    val key = "ABCDEFGHIJ"
    val value = "X" * 90
    val line = s"$key $value\n"
    val input = new ByteArrayInputStream(line.getBytes("ASCII"))
    val reader = new AsciiRecordReader()

    val record = reader.readRecord(input)
    record shouldBe defined
    record.get.take(10) shouldEqual key.getBytes("ASCII")
    record.get.drop(10) shouldEqual value.getBytes("ASCII")
  }
}
```

---

## 11. 명령행 인터페이스 상세 ⭐ NEW

### 11.1 Master 명령행

```bash
sbt "runMain distsort.Main master <N>"
```

**매개변수:**
- `<N>`: 예상 Worker 수 (정수)

**출력:**
- **stdout**: 정렬된 Worker 주소 (쉼표 구분)
  - 예: `192.168.1.10:30001, 192.168.1.11:30002, 192.168.1.12:30003`
- **stderr**: 로그 메시지 (진행 상황, 에러 등)

**예시:**
```bash
$ sbt "runMain distsort.Main master 3" 2> master.log
192.168.1.10:30001, 192.168.1.11:30002, 192.168.1.12:30003
```

### 11.2 Worker 명령행

```bash
sbt "runMain distsort.Main worker <master> -I <dir1> <dir2> ... -O <output> [options]"
```

**필수 매개변수:**
- `<master>`: Master 주소 (host:port 형식)
- `-I <dirs...>`: 입력 디렉토리들 (최소 1개, 공백으로 구분)
- `-O <output>`: 출력 디렉토리 (단일)

**선택적 매개변수:**
- `--ascii`: ASCII 형식 입력 (기본값: binary)
- `--binary`: Binary 형식 입력 (명시적)
- `--temp <dir>`: 중간 파일 저장 위치 (기본값: /tmp)
- `--threads <N>`: 스레드 수 (기본값: CPU 코어 수)

**예시:**

```bash
# Binary 형식 (기본)
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1 /data2 -O /output"

# ASCII 형식
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1 -O /output --ascii"

# 중간 파일 위치 지정
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1 -O /output --temp /scratch"

# 모든 옵션 사용
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 \
  -I /data1 /data2 /data3 \
  -O /output \
  --ascii \
  --temp /scratch \
  --threads 8"
```

### 11.3 명령행 파서 구현 ⭐ NEW

```scala
case class WorkerConfig(
  masterAddress: String,
  inputDirs: List[String],
  outputDir: String,
  format: DataFormat = DataFormat.Binary,
  tempDir: String = "/tmp",
  numThreads: Option[Int] = None
)

object CommandLineParser {

  def parseWorkerArgs(args: Array[String]): WorkerConfig = {
    var masterAddress: Option[String] = None
    var inputDirs: List[String] = List.empty
    var outputDir: Option[String] = None
    var format: DataFormat = DataFormat.Binary
    var tempDir: String = "/tmp"
    var numThreads: Option[Int] = None

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "-I" =>
          // 다음 플래그까지 모든 디렉토리 수집
          i += 1
          while (i < args.length && !args(i).startsWith("-")) {
            inputDirs = inputDirs :+ args(i)
            i += 1
          }
          i -= 1

        case "-O" =>
          i += 1
          if (i >= args.length) {
            throw new IllegalArgumentException("Missing output directory after -O")
          }
          outputDir = Some(args(i))

        case "--ascii" =>
          format = DataFormat.Ascii

        case "--binary" =>
          format = DataFormat.Binary

        case "--temp" =>
          i += 1
          if (i >= args.length) {
            throw new IllegalArgumentException("Missing directory after --temp")
          }
          tempDir = args(i)

        case "--threads" =>
          i += 1
          if (i >= args.length) {
            throw new IllegalArgumentException("Missing number after --threads")
          }
          try {
            numThreads = Some(args(i).toInt)
          } catch {
            case _: NumberFormatException =>
              throw new IllegalArgumentException(s"Invalid thread count: ${args(i)}")
          }

        case addr if !addr.startsWith("-") && masterAddress.isEmpty =>
          masterAddress = Some(addr)

        case unknown =>
          throw new IllegalArgumentException(s"Unknown option: $unknown")
      }
      i += 1
    }

    // 검증
    require(masterAddress.isDefined, "Master address is required")
    require(inputDirs.nonEmpty, "At least one input directory is required (-I)")
    require(outputDir.isDefined, "Output directory is required (-O)")

    // 입력 디렉토리 존재 확인
    inputDirs.foreach { dir =>
      val file = new File(dir)
      require(file.exists() && file.isDirectory,
              s"Input directory does not exist or is not a directory: $dir")
    }

    // 중간 파일 디렉토리 존재 확인 (없으면 생성)
    val tempDirFile = new File(tempDir)
    if (!tempDirFile.exists()) {
      tempDirFile.mkdirs()
    }
    require(tempDirFile.canWrite, s"Temp directory is not writable: $tempDir")

    WorkerConfig(
      masterAddress = masterAddress.get,
      inputDirs = inputDirs,
      outputDir = outputDir.get,
      format = format,
      tempDir = tempDir,
      numThreads = numThreads
    )
  }

  def printUsage(): Unit = {
    println("""
      |Usage:
      |  Master:
      |    sbt "runMain distsort.Main master <num_workers>"
      |
      |  Worker:
      |    sbt "runMain distsort.Main worker <master> -I <dirs...> -O <output> [options]"
      |
      |  Options:
      |    --ascii           Use ASCII format (default: binary)
      |    --binary          Use binary format (explicit)
      |    --temp <dir>      Temporary directory (default: /tmp)
      |    --threads <N>     Number of threads (default: CPU cores)
      |
      |  Examples:
      |    # Master
      |    $ sbt "runMain distsort.Main master 3"
      |
      |    # Worker (binary)
      |    $ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1 /data2 -O /output"
      |
      |    # Worker (ASCII, custom temp)
      |    $ sbt "runMain distsort.Main worker 192.168.1.1:30000 \\
      |        -I /data1 -O /output --ascii --temp /scratch"
      |""".stripMargin)
  }
}
```

---

## 12. 입력 형식 처리 ⭐ NEW

### 12.1 레코드 리더 추상화

```scala
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

trait RecordReader {
  /**
   * 스트림에서 레코드 하나를 읽습니다.
   * @return Some(100바이트 레코드) 또는 None (EOF)
   */
  def readRecord(input: InputStream): Option[Array[Byte]]
}

object RecordReader {
  def create(format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader()
    case DataFormat.Ascii => new AsciiRecordReader()
  }
}
```

### 12.2 Binary 형식 리더

```scala
class BinaryRecordReader extends RecordReader {

  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val record = new Array[Byte](100)
    val bytesRead = input.read(record)

    if (bytesRead == 100) {
      Some(record)
    } else if (bytesRead == -1) {
      None  // EOF
    } else {
      throw new IOException(
        s"Incomplete record: expected 100 bytes, got $bytesRead bytes"
      )
    }
  }
}
```

### 12.3 ASCII 형식 리더

```scala
class AsciiRecordReader extends RecordReader {

  /**
   * ASCII 형식:
   * - Key: 10 문자 (ASCII printable)
   * - Space: 1 바이트
   * - Value: 90 문자 (ASCII printable)
   * - Newline: 1 바이트 (\n)
   * 총 102 바이트
   */
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val line = new Array[Byte](102)
    val bytesRead = input.read(line)

    if (bytesRead == -1) {
      return None  // EOF
    }

    if (bytesRead != 102) {
      throw new IOException(
        s"Invalid ASCII record: expected 102 bytes, got $bytesRead bytes"
      )
    }

    // 형식 검증
    if (line(10) != ' '.toByte) {
      throw new IOException(
        s"Invalid ASCII record: expected space at position 10, got '${line(10).toChar}'"
      )
    }

    if (line(101) != '\n'.toByte) {
      throw new IOException(
        s"Invalid ASCII record: expected newline at position 101, got '${line(101).toChar}'"
      )
    }

    // 100바이트 레코드로 변환
    val record = new Array[Byte](100)

    // Key: 첫 10바이트 그대로 복사
    System.arraycopy(line, 0, record, 0, 10)

    // Value: 12번째부터 90바이트 복사 (space 건너뛰기)
    System.arraycopy(line, 11, record, 10, 90)

    Some(record)
  }
}
```

### 12.4 사용 예시

```scala
def processInputFiles(
    inputDirs: List[String],
    format: DataFormat,
    processor: Array[Byte] => Unit): Unit = {

  val reader = RecordReader.create(format)

  for (file <- getAllInputFiles(inputDirs)) {
    val input = new BufferedInputStream(
      new FileInputStream(file),
      1024 * 1024  // 1MB 버퍼
    )

    try {
      var record = reader.readRecord(input)
      while (record.isDefined) {
        processor(record.get)
        record = reader.readRecord(input)
      }
    } finally {
      input.close()
    }
  }
}

// 샘플링 예시
def extractSample(config: WorkerConfig): Array[Array[Byte]] = {
  val samples = mutable.ArrayBuffer[Array[Byte]]()
  val random = new Random(42)

  val sampleRate = calculateSampleRate(
    FileUtils.calculateTotalSize(config.inputDirs),
    numWorkers
  )

  processInputFiles(config.inputDirs, config.format, { record =>
    if (random.nextDouble() < sampleRate) {
      samples += record.take(10)  // Key만 저장
    }
  })

  samples.toArray
}
```

---

## 13. 개발 마일스톤 ⭐ UPDATED

### Milestone 1: 기본 인프라 (Week 1-2)
- [ ] 프로젝트 구조 생성
- [ ] Protocol Buffers 정의 (DataFormat 포함) ⭐
- [ ] RecordReader 추상화 및 구현 ⭐
- [ ] CommandLineParser 구현 ⭐
- [ ] Master/Worker 스켈레톤 코드
- [ ] gRPC 통신 기본 구현
- [ ] 간단한 테스트 (1 Master + 1 Worker, 작은 데이터)

**검증:**
```bash
# Master 실행
$ sbt "runMain distsort.Main master 1" 2> master.log
127.0.0.1:30000

# Worker 실행 (Binary)
$ sbt "runMain distsort.Main worker 127.0.0.1:30000 -I test_input -O test_output"
> Worker registered successfully

# Worker 실행 (ASCII)
$ sbt "runMain distsort.Main worker 127.0.0.1:30000 -I test_input -O test_output --ascii"
> Worker registered successfully
```

### Milestone 2: 핵심 알고리즘 (Week 3-4)
- [ ] 동적 샘플링 비율 계산 구현 ⭐
- [ ] 샘플링 구현 (ASCII/Binary 지원) ⭐
- [ ] 정렬 및 파티셔닝 구현
- [ ] Binary search 기반 파티션 찾기 ⭐
- [ ] Shuffle 구현 (재시도 로직 포함) ⭐
- [ ] Merge 구현 (Atomic write) ⭐
- [ ] 로컬 통합 테스트 (1 Master + 3 Workers)

**검증:**
```bash
# Binary 형식 테스트
$ ./gensort -b 100 input_binary
$ ./run_test.sh --binary
$ ./valsort output_binary
> SUCCESS

# ASCII 형식 테스트
$ ./gensort -a 100 input_ascii
$ ./run_test.sh --ascii
$ ./valsort -a output_ascii
> SUCCESS
```

### Milestone 3: 장애 허용성 (Week 5)
- [ ] 재시작 로직 구현
- [ ] 임시 파일 정리 구현
- [ ] Worker 장애 테스트
- [ ] 원자적 출력 구현 (partition.N 네이밍) ⭐
- [ ] 네트워크 재시도 및 타임아웃 ⭐

**검증:**
```bash
# Worker 강제 종료 테스트
$ ./test_failure.sh
> Worker 2 killed at t=10s
> Worker 2 restarted at t=12s
> All workers completed successfully
> Output verified: SUCCESS
```

### Milestone 4: 성능 최적화 (Week 6-7)
- [ ] 멀티스레드 구현
- [ ] I/O 버퍼링 최적화
- [ ] 네트워크 배치 전송
- [ ] 디스크 공간 사전 확인 ⭐
- [ ] 프로파일링 및 병목 제거
- [ ] 대용량 테스트 (50GB+)

**검증:**
```bash
# 성능 측정
$ ./benchmark.sh
> 50GB sorted in 180 seconds
> Throughput: 285 MB/s
> Speedup vs single machine: 8.2x

# 디스크 공간 부족 시나리오
$ ./test_disk_full.sh
> ERROR: Insufficient temp disk space: 5.2 GB available, 75.0 GB required
> Worker exited gracefully
```

### Milestone 5: 마무리 (Week 8)
- [ ] 전체 시스템 테스트 (ASCII/Binary 모두)
- [ ] Master 출력 형식 검증 (stdout vs stderr) ⭐
- [ ] 문서화 완성
- [ ] 코드 리팩토링 및 정리
- [ ] 최종 데모 준비

---

## 14. 참고 자료

### 14.1 핵심 알고리즘
- External Sorting: Knuth, TAOCP Vol. 3
- K-way Merge: Priority Queue 기반 병합
- Sampling for Partitioning: TeraSort 논문

### 14.2 시스템 설계
- MapReduce: Dean & Ghemawat, OSDI 2004
- Spark: Zaharia et al., NSDI 2012
- Distributed Sorting: Sort Benchmark 우승 논문들

### 14.3 구현 참고
- gRPC 공식 문서: https://grpc.io/docs/languages/java/
- Protocol Buffers: https://protobuf.dev/
- Scala 동시성 패턴
- gensort/valsort 도구 사용법

---

## 15. 예상 이슈 및 해결책 ⭐ UPDATED

### Issue 1: 파티션 불균형
**증상:** 일부 Worker가 매우 큰 파티션을 받아 병목 발생

**해결:**
- 동적 샘플링 비율 조정 (입력 크기 고려) ⭐
- 샘플 크기 증가
- 파티션 수 증가 (Worker 당 여러 파티션)
- Binary search로 효율적인 파티션 할당 ⭐

### Issue 2: 네트워크 대역폭 부족
**증상:** Shuffle 단계에서 매우 느림

**해결:**
- 압축 사용
- 배치 크기 증가
- 동시 전송 수 조절
- 지수 백오프 재시도 ⭐
- Keep-alive 및 타임아웃 설정 ⭐

### Issue 3: 메모리 부족
**증상:** OutOfMemoryError

**해결:**
- 청크 크기 감소
- 스레드 수 감소
- 버퍼 크기 조정
- JVM 힙 크기 증가 (`-Xmx8g`)

### Issue 4: 디스크 I/O 병목
**증상:** CPU 유휴 상태인데 작업 느림

**해결:**
- 버퍼링 증가 (1-4MB)
- Sequential I/O 패턴 유지
- SSD 사용
- 임시 파일을 별도 디스크에 저장 (`--temp` 옵션) ⭐

### Issue 5: 디스크 공간 부족 ⭐ NEW
**증상:** 작업 중 디스크 공간 부족으로 실패

**해결:**
- 시작 전 디스크 공간 검증 (ensureSufficientDiskSpace) ⭐
- 임시 파일 조기 정리
- 압축 사용
- 여유 공간 50% 이상 확보

### Issue 6: ASCII/Binary 형식 혼동 ⭐ NEW
**증상:** 잘못된 형식으로 읽어서 데이터 손상

**해결:**
- 명령행 플래그 명확화 (`--ascii` / `--binary`) ⭐
- RecordReader 형식 검증 추가 ⭐
- 초기 몇 레코드로 형식 자동 감지 (선택적)
- 에러 메시지 개선

### Issue 7: Worker 재시작 무한 루프
**증상:** Worker가 계속 실패하고 재시작

**해결:**
- 실패 원인 로깅 강화
- 재시작 횟수 제한
- 백오프 전략 (exponential backoff) ⭐
- Master의 전역 재시작 트리거
- 디스크/메모리 사전 검증 ⭐

---

## 16. 결론

### 16.1 Version 2 주요 개선사항 요약

이 설계 문서 v2는 초기 계획을 기반으로 다음과 같은 **8가지 핵심 개선사항**을 추가했습니다:

1. ✅ **ASCII/Binary 입력 형식 처리**
   - RecordReader 추상화
   - BinaryRecordReader 및 AsciiRecordReader 구현
   - 명령행 플래그 (`--ascii` / `--binary`)

2. ✅ **명령행 인터페이스 개선**
   - 여러 입력 디렉토리 지원
   - 추가 옵션 플래그 (`--temp`, `--threads`)
   - 상세한 CommandLineParser 구현

3. ✅ **Master 출력 형식 표준화**
   - stdout: Worker 주소만 (쉼표 구분)
   - stderr: 로그 메시지

4. ✅ **중간 파일 저장 위치 옵션**
   - `--temp` 플래그 추가
   - 디렉토리 존재 및 권한 검증

5. ✅ **네트워크 타임아웃 및 재시도 로직**
   - 지수 백오프 재시도
   - Keep-alive 및 타임아웃 설정
   - isRetryable() 에러 분류

6. ✅ **파티션 파일 네이밍 명확화**
   - `partition.N` 형식 보장
   - Atomic write (임시 파일 → rename)

7. ✅ **샘플링 비율 동적 조정**
   - 입력 크기 기반 계산
   - 최소/최대 비율 제한

8. ✅ **디스크 공간 사전 확인**
   - ensureSufficientDiskSpace() 함수
   - 작업 시작 전 검증

### 16.2 구현 완성도

**Version 1 대비 개선:**
- **정확성**: PDF 명세서 요구사항 100% 반영
- **견고성**: 에러 처리 및 복구 로직 강화
- **사용성**: 명령행 인터페이스 개선
- **확장성**: 동적 파라미터 조정
- **디버깅**: stdout/stderr 분리, 상세한 로깅

**성공 기준:**
- ✅ 정확성: valsort 통과 (ASCII/Binary 모두)
- ✅ 장애 허용: Worker 재시작 후 정상 완료
- ✅ 성능: 단일 머신 대비 유의미한 속도 향상
- ✅ 코드 품질: 깔끔하고 유지보수 가능한 구조
- ✅ 명세 준수: PDF 요구사항 100% 충족

### 16.3 다음 단계

1. **Milestone 1 구현 시작**
   - RecordReader 및 CommandLineParser 우선 구현
   - 단위 테스트 작성
   - ASCII/Binary 형식 검증

2. **프로토타입 검증**
   - 작은 데이터로 E2E 테스트
   - ASCII 및 Binary 모두 테스트
   - stdout/stderr 출력 검증

3. **점진적 확장**
   - 장애 허용성 추가
   - 성능 최적화
   - 대용량 테스트

---

**문서 버전:** 2.0
**최종 수정:** 2025-10-24
**작성자:** Project Team
**변경 이력:**
- v1.0 (2025-10-24): 초기 설계
- v2.0 (2025-10-24): PDF 명세서 요구사항 반영, 8가지 핵심 개선사항 추가
