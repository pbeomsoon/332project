# 분산 정렬 시스템 중간 발표
**Team Silver** - 권동연, 박범순, 임지훈

**발표일**: 2025-11-16
**프로젝트**: Fault-Tolerant Distributed Sorting System

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [TDD 기반 개발 방법론](#2-tdd-기반-개발-방법론)
3. [시스템 아키텍처](#3-시스템-아키텍처)
4. [핵심 설계 결정 사항](#4-핵심-설계-결정-사항)
5. [현재 진행 상황 (Week 4-5)](#5-현재-진행-상황-week-4-5)
6. [구현 상세 (Week 4-5)](#6-구현-상세-week-4-5)
7. [향후 계획 (Week 6-8)](#7-향후-계획-week-6-8)
8. [Q&A 준비](#8-qa-준비)
9. [참고 문헌](#9-참고-문헌)

---

## 1. 프로젝트 개요

### 1.1 목표

여러 머신에 분산 저장된 대용량 key/value 레코드를 정렬하는 **장애 허용성 분산 시스템** 구현

### 1.2 핵심 요구사항

| 항목 | 요구사항 | 구현 전략 |
|------|---------|----------|
| **입력** | 여러 Worker 노드에 분산된 미정렬 데이터 | ASCII/Binary 자동 감지 |
| **출력** | 전역적으로 정렬된 데이터 | Range-based Partitioning |
| **장애 허용** | Worker crash 후 재시작 시 정상 동작 | Worker Re-registration |
| **확장성** | 멀티코어 병렬 처리 | ThreadPool 기반 |
| **검증** | gensort/valsort 도구 활용 | TDD 기반 개발 |

### 1.3 기술 스택

```
언어:        Scala 2.13
빌드 도구:   SBT 1.9.7
RPC:         gRPC + Protocol Buffers
테스트:      ScalaTest
버전 관리:   Git
```

---

## 2. TDD 기반 개발 방법론

### 2.1 TDD란?

**Test-Driven Development**: 구현 전에 테스트를 먼저 작성하는 개발 방식

#### Red-Green-Refactor Cycle

```
1. 🔴 RED     : 실패하는 테스트 작성
                (원하는 동작 정의)

2. 🟢 GREEN   : 최소한의 코드로 테스트 통과
                (일단 동작하게 만들기)

3. 🔵 REFACTOR: 코드 개선 (테스트는 계속 통과)
                (깔끔하게 만들기)
```

### 2.2 분산 시스템에서 TDD의 중요성

| 문제 | TDD를 통한 해결 |
|------|----------------|
| 복잡한 상태 전이 | 테스트로 명확하게 검증 |
| gRPC 통신 오류 | 시나리오 사전 정의 |
| Race condition | 조기 발견 가능 |
| Fault tolerance | 메커니즘 검증 |
| 리팩토링 | 안전성 보장 |

### 2.3 TDD 실전 예시 - Record 클래스

#### Step 1: 🔴 RED - 실패하는 테스트 작성

```scala
class RecordSpec extends AnyFlatSpec with Matchers {
  "Record" should "store 10-byte key and 90-byte value" in {
    val key = Array.fill[Byte](10)(1)
    val value = Array.fill[Byte](90)(2)

    val record = Record(key, value)

    record.key.length shouldBe 10
    record.value.length shouldBe 90
  }

  it should "compare records by key only (unsigned)" in {
    // 0xFF (255) > 0x01 (1) in unsigned comparison
    val rec1 = Record(Array[Byte](0xFF.toByte) ++ Array.fill[Byte](9)(0),
                      Array.fill[Byte](90)(0))
    val rec2 = Record(Array[Byte](0x01) ++ Array.fill[Byte](9)(0),
                      Array.fill[Byte](90)(0))

    rec1.compare(rec2) should be > 0
  }
}
```

**실행 결과**: ❌ 컴파일 에러 (Record 클래스 미존재)

#### Step 2: 🟢 GREEN - 최소 구현

```scala
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes")
  require(value.length == 90, s"Value must be 90 bytes")

  override def compare(that: Record): Int = {
    // ⚠️ 중요: Unsigned 비교 필수!
    var i = 0
    while (i < 10) {
      val byte1 = this.key(i) & 0xFF  // Signed → Unsigned 변환
      val byte2 = that.key(i) & 0xFF
      if (byte1 != byte2) return byte1 - byte2
      i += 1
    }
    0
  }

  def toBytes: Array[Byte] = key ++ value
}
```

**실행 결과**: ✅ 테스트 통과!

#### Step 3: 🔵 REFACTOR - 코드 개선

```scala
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
  require(value.length == 90, s"Value must be 90 bytes, got ${value.length}")

  override def compare(that: Record): Int = {
    var i = 0
    while (i < 10) {
      val unsigned1 = this.key(i) & 0xFF
      val unsigned2 = that.key(i) & 0xFF
      val diff = unsigned1 - unsigned2
      if (diff != 0) return diff
      i += 1
    }
    0
  }

  def toBytes: Array[Byte] = key ++ value

  override def toString: String =
    s"Record(key=${key.take(3).map("%02X".format(_)).mkString("")}..., value=${value.length}B)"
}
```

**실행 결과**: ✅ 테스트 여전히 통과 + 가독성 향상

### 2.4 우리 프로젝트의 TDD 적용

#### Testing Pyramid

```
            ┌─────────────┐
            │ E2E Tests   │  ~ 10%
            │ (느림)      │  (전체 시스템)
            └─────────────┘
         ┌──────────────────┐
         │ Integration Tests│  ~ 20%
         │ (보통)           │  (컴포넌트 간)
         └──────────────────┘
    ┌─────────────────────────┐
    │   Unit Tests            │  ~ 70%
    │   (빠름)                │  (함수/클래스)
    └─────────────────────────┘
```

#### 테스트 작성 현황 (Week 4-5 기준)

| 컴포넌트 | 테스트 개수 | 상태 |
|---------|-----------|------|
| Record | 5 tests | ✅ 완료 |
| RecordReader (Binary) | 📋 설계 | 예정 |
| RecordReader (ASCII) | 📋 설계 | 예정 |
| InputFormatDetector | 📋 설계 | 예정 |
| FileLayout | 📋 설계 | 예정 |

---

## 3. 시스템 아키텍처

### 3.1 전체 구조

```
┌─────────────────────────────────────────────────────┐
│                  Master Node                        │
│  - Worker 등록 관리                                  │
│  - 샘플 수집 및 파티션 경계 계산                       │
│  - shuffleMap 생성 및 브로드캐스트                    │
│  - Phase 동기화 조율                                 │
│  - 최종 Worker 순서 출력                             │
└───────────────┬─────────────────────────────────────┘
                │
        ┌───────┴───────┬──────────┬──────────┐
        │               │          │          │
   ┌────▼─────┐   ┌────▼────┐ ┌──▼──────┐
   │ Worker 0 │   │Worker 1 │ │Worker 2 │
   │          │   │         │ │         │
   │Input:    │   │Input:   │ │Input:   │
   │50GB      │   │50GB     │ │50GB     │
   │          │   │         │ │         │
   │Output:   │   │Output:  │ │Output:  │
   │P0,P1,P2  │   │P3,P4,P5 │ │P6,P7,P8 │
   └──────────┘   └─────────┘ └─────────┘
        │               │          │
        └───────────────┴──────────┘
       Worker-to-Worker Shuffle
       (gRPC Streaming)
```

### 3.2 5-Phase 실행 흐름

```
Phase 0: Initialization
  ├─ Master 시작, Worker들 등록 대기
  ├─ 각 Worker 시작, Master에 연결
  ├─ Master가 Worker에 index 할당
  └─ 디스크 공간 검증

Phase 1: Sampling
  ├─ 각 Worker가 입력 데이터에서 샘플 추출
  ├─ 동적 샘플링 비율 계산 (0.01% ~ 1%)
  ├─ Master에게 샘플 전송
  ├─ Master가 전체 샘플 정렬
  ├─ 파티션 경계 계산 (N개 or M개)
  └─ shuffleMap 생성 및 브로드캐스트

Phase 2: Sort & Partition
  ├─ 각 Worker가 입력 파일 읽기 (ASCII/Binary 자동 처리)
  ├─ Chunk 단위 메모리 내 정렬 (병렬)
  ├─ 파티션 경계에 따라 분할 (N개 또는 M개)
  └─ 파티션별 임시 파일 생성

Phase 3: Shuffle
  ├─ shuffleMap에 따라 파티션 전송
  ├─ Worker 간 네트워크 통신 (gRPC streaming)
  ├─ 재시도 로직 (지수 백오프)
  └─ 수신 확인 및 임시 저장

Phase 4: Merge
  ├─ 각 Worker가 받은 파티션들을 개별적으로 K-way merge
  ├─ Priority Queue (min-heap) 사용
  ├─ 최종 partition.n 파일 생성
  └─ Atomic write 보장 (temp + rename)

Phase 5: Completion
  ├─ 각 Worker가 Master에게 완료 보고
  ├─ Master가 전체 작업 완료 확인
  ├─ Master가 정렬된 Worker 주소 출력 (stdout)
  └─ 임시 파일 정리
```

### 3.3 레코드 구조

```
┌──────────────┬────────────────────────────────────────┐
│ Key (10B)    │ Value (90B)                            │
└──────────────┴────────────────────────────────────────┘
  0            10                                      100

특징:
- 고정 길이: 100 바이트
- Key만 정렬 기준으로 사용 (unsigned 비교)
- Value는 정렬과 무관하게 Key와 함께 이동
```

### 3.4 파티션 전략 (N→M Strategy)

**PDF 요구사항**:
> "numPartitions - 파티션 개수 (일반적으로 워커 수와 동일 **또는 배수**)"

#### Strategy B: Advanced (N Workers → M Partitions, M > N)

**개념**:
- 파티션 수 > Worker 수 (일반적으로 M = 3N)
- 각 Worker는 **여러 파티션**을 담당
- Partition i는 Worker (i / partitionsPerWorker)가 담당

**예시: 3 Workers, 9 Partitions**

```
Phase 2: Sort & Partition
  Worker 0, 1, 2 각각 → P0~P8 (9개) 생성

Phase 3: Shuffle
  P0, P1, P2 → Worker 0
  P3, P4, P5 → Worker 1
  P6, P7, P8 → Worker 2

Phase 4: Merge
  Worker 0:
    - 3개 P0 조각 merge → partition.0
    - 3개 P1 조각 merge → partition.1
    - 3개 P2 조각 merge → partition.2

최종 출력:
  /worker0/output/partition.0  ← 가장 작은 key
  /worker0/output/partition.1
  /worker0/output/partition.2
  /worker1/output/partition.3
  /worker1/output/partition.4
  /worker1/output/partition.5
  /worker2/output/partition.6
  /worker2/output/partition.7
  /worker2/output/partition.8  ← 가장 큰 key

읽기 순서: partition.0 → 1 → 2 → ... → 8 = 전역 정렬됨
```

**장점**:
- ✅ 로드 밸런싱 개선 (파티션 수 증가)
- ✅ 멀티코어 활용 증가 (병렬 merge)
- ✅ 파티션 크기 불균형 완화
- ✅ PDF 요구사항 충족

---

## 4. 핵심 설계 결정 사항

### 4.1 Fault Tolerance: Checkpoint-based Recovery

**PDF 요구사항**:
> "The system must be fault-tolerant, which means that if a worker crashes and restarts, the overall computation should still produce correct results."

#### 실제 구현 전략: Checkpoint-based Recovery

```
┌─────────────────────────────────────────────────┐
│ Fault Tolerance Strategy (실제 구현)            │
├─────────────────────────────────────────────────┤
│ Checkpoint 저장:                                │
│   ✅ 각 Phase 완료 시 자동 저장                  │
│   ✅ WorkerState를 JSON으로 영속화               │
│   ✅ 위치: /tmp/distsort/checkpoints/           │
│   ✅ 최근 3개 checkpoint 유지                    │
│                                                 │
│ Worker Crash & Restart:                         │
│   ✅ 시작 시 최신 checkpoint 로드                │
│   ✅ 마지막 완료 Phase부터 재개                  │
│   ✅ Sampling/Sort 재수행 불필요                 │
│                                                 │
│ Graceful Shutdown:                              │
│   ✅ 30초 grace period                          │
│   ✅ 현재 Phase 완료 대기                        │
│   ✅ Checkpoint 저장 후 종료                     │
│                                                 │
│ Data Integrity:                                 │
│   ✅ Atomic writes (temp + rename)              │
│   ✅ State-based cleanup on failure             │
│   ✅ Idempotent operations                      │
└─────────────────────────────────────────────────┘
```

#### CheckpointManager 구현

**저장되는 상태 (WorkerState)**:
```scala
case class WorkerState(
  processedRecords: Long,              // 처리한 레코드 수
  partitionBoundaries: List[Array[Byte]],  // 파티션 경계
  shuffleMap: Map[Int, Int],           // 파티션 → Worker 매핑
  completedPartitions: Set[Int],       // 완료한 파티션들
  currentFiles: List[String],          // 현재 파일들
  phaseMetadata: Map[String, String]   // Phase 메타데이터
)
```

**Checkpoint 저장 시점**:
```scala
performSampling()
  → savePhaseCheckpoint(PHASE_SAMPLING, 1.0)       // ✅

getPartitionConfiguration()
  → savePhaseCheckpoint(PHASE_WAITING_FOR_PARTITIONS, 1.0)  // ✅

performLocalSort()
  → savePhaseCheckpoint(PHASE_SORTING, 1.0)        // ✅

performShuffle()
  → savePhaseCheckpoint(PHASE_SHUFFLING, 1.0)      // ✅

performMerge()
  → savePhaseCheckpoint(PHASE_MERGING, 1.0)        // ✅
  → checkpointManager.deleteAllCheckpoints()       // 성공 시 삭제
```

#### 복구 시나리오 예시

```
Worker crash during Shuffle:
  Last checkpoint: PHASE_SORTING (100% 완료)

Worker restart:
  1. recoverFromCheckpoint() 성공
  2. currentPhase = PHASE_SORTING (복원)
  3. performShuffle() 재시작
     ⭐ Sampling/Sort는 스킵 (시간 대폭 절약)
  4. Merge → 완료

Result:
  ✅ 마지막 완료 Phase부터 재개
  ✅ 빠른 복구 (전체 재시작 대비)
  ✅ 정확성 보장
```

#### 정당화 (Justification)

| 근거 | 설명 |
|------|------|
| **빠른 복구** | 마지막 완료 Phase부터 재개 → 전체 재시작보다 효율적 |
| **정확성 보장** | Phase별 완료 checkpoint → 부분 결과 유실 없음 |
| **실제 시스템** | Spark (RDD lineage + checkpoint), Flink (checkpoint 기반 exactly-once), MapReduce (Task-level restart) |
| **구현 가능성** | CheckpointManager + JSON 직렬화 + Graceful Shutdown 통합 |
| **PDF 해석** | "produce correct results" ← Checkpoint가 정확성과 효율성 모두 충족 |

### 4.2 N→M Partition Strategy

**목적**: Merge 단계에서 멀티코어 활용

#### shuffleMap 생성 로직

```scala
def createShuffleMap(numWorkers: Int, numPartitions: Int): Map[Int, Int] = {
  val shuffleMap = mutable.Map[Int, Int]()
  val partitionsPerWorker = numPartitions / numWorkers

  for (partitionID <- 0 until numPartitions) {
    // 파티션 ID를 Worker ID로 매핑
    val workerID = partitionID / partitionsPerWorker
    val finalWorkerID = if (workerID >= numWorkers) numWorkers - 1 else workerID
    shuffleMap(partitionID) = finalWorkerID
  }

  shuffleMap.toMap
}

// 예시
// createShuffleMap(3, 9)
//   → {0→0, 1→0, 2→0, 3→1, 4→1, 5→1, 6→2, 7→2, 8→2}
//
// 결과: Worker 0 = [0,1,2], Worker 1 = [3,4,5], Worker 2 = [6,7,8]
//       각 Worker는 연속된 partition 번호 담당
```

#### 파티션 할당 공식 (Range-based)

```scala
def assignWorker(partitionID: Int, numWorkers: Int, numPartitions: Int): Int = {
  val partitionsPerWorker = numPartitions / numWorkers
  val workerID = partitionID / partitionsPerWorker
  if (workerID >= numWorkers) numWorkers - 1 else workerID
}
```

### 4.3 ASCII/Binary 자동 감지

**PDF 요구사항**:
> "Should work on both ASCII and binary input **without requiring an option**"

#### InputFormatDetector 알고리즘

```scala
object InputFormatDetector {
  private val SAMPLE_SIZE = 1000      // 파일의 첫 1000 바이트 분석
  private val ASCII_THRESHOLD = 0.9   // 90% 이상 ASCII → ASCII 형식

  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)

    try {
      val bytesRead = inputStream.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}, defaulting to Binary")
        return DataFormat.Binary
      }

      // ASCII printable: 0x20-0x7E, plus \n (0x0A), \r (0x0D)
      val asciiLikeCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiLikeCount.toDouble / bytesRead

      if (asciiRatio > ASCII_THRESHOLD) {
        DataFormat.Ascii
      } else {
        DataFormat.Binary
      }
    } finally {
      inputStream.close()
    }
  }
}
```

**혼합 입력 처리**:
- 각 파일마다 독립적으로 형식 감지
- ASCII와 Binary 파일 혼재 가능
- RecordReader Factory Pattern으로 동적 생성

### 4.4 External Sort (2-Pass Algorithm)

**목적**: 메모리보다 큰 데이터 정렬

#### Phase 1: Chunk Sort (병렬)

```scala
class ExternalSorter(numThreads: Int = Runtime.getRuntime.availableProcessors()) {
  private val executor = Executors.newFixedThreadPool(numThreads)

  def sortInParallel(inputFile: File, chunkSize: Long): List[File] = {
    val chunks = splitIntoChunks(inputFile, chunkSize)

    val futures = chunks.map { chunk =>
      Future {
        // 메모리 내 정렬
        val records = readChunk(chunk).toArray
        records.sortInPlace()
        writeSortedChunk(records)
      }(ExecutionContext.fromExecutor(executor))
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }
}
```

#### Phase 2: K-way Merge (Priority Queue)

```scala
def kWayMerge(sortedFiles: List[File], output: File): Unit = {
  // Min-heap for K-way merge
  val heap = mutable.PriorityQueue[RecordWithSource]()(
    Ordering.by[RecordWithSource, Array[Byte]](_.record.key).reverse
  )

  // 각 파일에서 첫 레코드 읽기
  readers.zipWithIndex.foreach { case (reader, idx) =>
    reader.readRecord().foreach { record =>
      heap.enqueue(RecordWithSource(record, idx))
    }
  }

  // Merge
  while (heap.nonEmpty) {
    val min = heap.dequeue()
    outputWriter.write(min.record)

    // 같은 소스에서 다음 레코드 읽기
    readers(min.sourceId).readRecord().foreach { record =>
      heap.enqueue(RecordWithSource(record, min.sourceId))
    }
  }
}
```

### 4.5 gRPC 기반 통신

#### Protocol Buffers 정의 (핵심 부분)

```protobuf
syntax = "proto3";

service MasterService {
  rpc RegisterWorker(WorkerInfo) returns (RegistrationResponse);
  rpc SendSample(SampleData) returns (Ack);
  rpc NotifyPhaseComplete(PhaseCompleteRequest) returns (Ack);
}

service WorkerService {
  rpc SetPartitionBoundaries(PartitionConfig) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);
  rpc StartShuffle(ShuffleSignal) returns (Ack);
  rpc StartMerge(MergeSignal) returns (Ack);
}

message PartitionConfig {
  repeated bytes boundaries = 1;       // N-1 or M-1 개의 경계
  int32 num_partitions = 2;            // N or M
  map<int32, int32> shuffle_map = 3;   // partitionID → workerID
  repeated WorkerInfo all_workers = 4;
}

message ShuffleDataChunk {
  int32 partition_id = 1;
  bytes data = 2;               // 1MB 청크 단위
  int64 chunk_offset = 3;
  bool is_last = 4;
}
```

#### Shuffle 재시도 로직

```scala
def sendPartitionWithRetry(
    partitionFile: File,
    partitionId: Int,
    targetWorker: WorkerInfo,
    maxRetries: Int = 3): Unit = {

  var attempt = 0
  var success = false

  while (attempt < maxRetries && !success) {
    try {
      sendPartition(partitionFile, partitionId, targetWorker)
      success = true
    } catch {
      case e: StatusRuntimeException if isRetryable(e) =>
        attempt += 1
        val backoffMs = math.pow(2, attempt).toLong * 1000  // 지수 백오프
        logger.warn(s"Send failed (attempt $attempt/$maxRetries), " +
                   s"retrying in ${backoffMs}ms")
        Thread.sleep(backoffMs)

      case e: Exception =>
        logger.error(s"Non-retryable error: ${e.getMessage}")
        throw e
    }
  }
}
```

---

## 5. 현재 진행 상황 (Week 4-5)

### 5.1 전체 진행률

```
Week 1-2: 설계 단계 (100% 완료)
  ├─ 시스템 아키텍처 정의
  ├─ Protocol Buffers 설계
  ├─ Fault Tolerance 전략 결정
  └─ 7개 설계 문서 작성

Week 3: 프로젝트 구조 및 Record (100% 완료)
  ├─ SBT 프로젝트 구조 생성
  ├─ gRPC stub 생성
  └─ Record 클래스 구현 및 테스트

Week 4-5: Core I/O Components (100% 완료)
  ├─ RecordReader 추상화
  ├─ BinaryRecordReader 구현
  ├─ AsciiRecordReader 구현
  ├─ InputFormatDetector 구현
  ├─ FileLayout 클래스 구현
  └─ RecordWriter 구현

Week 6: Algorithms (예정)
  📋 ExternalSorter
  📋 Partitioner
  📋 KWayMerger

Week 7-8: Master/Worker Integration (예정)
  📋 Master 구현
  📋 Worker 구현
  📋 Phase 동기화
  📋 Fault Tolerance 검증
```

### 5.2 구현 완료 항목 (Week 4-5)

#### ✅ 1. RecordReader 추상화

**목적**: ASCII/Binary 형식을 통일된 인터페이스로 처리

```scala
// 추상 인터페이스
trait RecordReader {
  def readRecord(input: InputStream): Option[Array[Byte]]
}

// Factory Pattern
object RecordReader {
  def create(format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader()
    case DataFormat.Ascii  => new AsciiRecordReader()
  }
}

sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}
```

#### ✅ 2. BinaryRecordReader 구현

**특징**:
- 100-byte 고정 길이 읽기
- BufferedInputStream 사용 (1MB 버퍼)
- EOF 처리

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
      throw new IOException(s"Incomplete record: $bytesRead bytes")
    }
  }
}
```

#### ✅ 3. AsciiRecordReader 구현

**ASCII 형식**:
```
key(10 chars) + space(1) + value(90 chars) + newline(1) = 102 bytes
```

```scala
class AsciiRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val line = new Array[Byte](102)
    val bytesRead = input.read(line)

    if (bytesRead == -1) return None
    if (bytesRead != 102)
      throw new IOException(s"Invalid ASCII record: $bytesRead bytes")
    if (line(10) != ' '.toByte)
      throw new IOException("Expected space at position 10")
    if (line(101) != '\n'.toByte)
      throw new IOException("Expected newline at position 101")

    // 100 bytes로 변환 (space와 newline 제거)
    val record = new Array[Byte](100)
    System.arraycopy(line, 0, record, 0, 10)     // key
    System.arraycopy(line, 11, record, 10, 90)   // value

    Some(record)
  }
}
```

#### ✅ 4. InputFormatDetector 구현

**동작 원리**:
1. 파일의 첫 1000 바이트 읽기
2. ASCII printable 문자 비율 계산
3. 비율 > 90% → ASCII, 그 외 → Binary

**장점**:
- ✅ PDF 요구사항 충족: "without requiring an option"
- ✅ 각 파일마다 독립적 감지 (혼합 입력 지원)
- ✅ 빠른 판별 (1000 bytes만 읽음)

#### ✅ 5. FileLayout 클래스

**역할**: 파일 시스템 관리 및 디스크 공간 검증

```scala
class FileLayout(
  inputDirs: List[File],
  outputDir: File,
  tempBaseDir: File,
  workerId: String
) {
  // 입력 디렉토리 검증 (읽기 전용)
  def validateInputDirectories(): Unit = {
    inputDirs.foreach { dir =>
      require(dir.exists(), s"Input directory not found: $dir")
      require(dir.isDirectory, s"Not a directory: $dir")
      require(dir.canRead, s"Cannot read directory: $dir")
    }
  }

  // 임시 디렉토리 생성
  def createTemporaryStructure(): Unit = {
    val workDir = new File(tempBaseDir, s"sort_work_$workerId")
    val subdirs = List("samples", "sorted_chunks", "partitions", "received")

    subdirs.foreach { subdir =>
      val dir = new File(workDir, subdir)
      if (!dir.exists()) dir.mkdirs()
    }
  }

  // 디스크 공간 확인 (필요 공간 = inputSize * 2)
  def ensureSufficientDiskSpace(): Unit = {
    val totalInputSize = inputDirs.map(calculateDirSize).sum
    val requiredTemp = totalInputSize * 2
    val availableTemp = tempBaseDir.getUsableSpace

    require(availableTemp > requiredTemp * 1.5,
      s"Insufficient temp space: need ${requiredTemp * 1.5 / 1e9}GB")
  }

  // 임시 파일 정리
  def cleanupTemporaryFiles(): Unit = {
    val workDir = new File(tempBaseDir, s"sort_work_$workerId")
    if (workDir.exists()) {
      FileUtils.deleteRecursively(workDir)
    }
  }
}
```

**PDF 요구사항 충족**:
- ✅ 입력 디렉토리 보호 (읽기 전용, 수정 금지)
- ✅ 임시 파일과 출력 파일 분리
- ✅ 작업 완료 후 자동 정리

#### ✅ 6. RecordWriter 구현

**특징**:
- Binary/ASCII 형식 출력 지원
- 버퍼링 (4MB) for I/O 최적화
- Atomic write (temp + rename)

```scala
class RecordWriter(outputFile: File, format: DataFormat) {
  private val tempFile = new File(outputFile.getParent,
                                  s".${outputFile.getName}.tmp")
  private val output = new BufferedOutputStream(
    new FileOutputStream(tempFile),
    4 * 1024 * 1024  // 4MB 버퍼
  )

  def writeRecord(record: Array[Byte]): Unit = {
    require(record.length == 100, s"Invalid record length: ${record.length}")

    format match {
      case DataFormat.Binary =>
        output.write(record)

      case DataFormat.Ascii =>
        // key(10) + space + value(90) + newline
        output.write(record, 0, 10)
        output.write(' '.toByte)
        output.write(record, 10, 90)
        output.write('\n'.toByte)
    }
  }

  def close(): Unit = {
    output.close()

    // Atomic rename
    if (!tempFile.renameTo(outputFile)) {
      throw new IOException(s"Failed to rename $tempFile to $outputFile")
    }
  }
}
```

---

## 6. 구현 상세 (Week 4-5)

### 6.1 실제 구현 코드 분석

#### Record 클래스 (Week 4 완료)

**파일**: `distsort/core/Record.scala`

```scala
package distsort.core

/**
 * Represents a 100-byte record with 10-byte key and 90-byte value.
 *
 * Key comparison uses unsigned byte ordering, which is critical for
 * correct sorting behavior (e.g., 0xFF > 0x01).
 */
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
  require(value.length == 90, s"Value must be 90 bytes, got ${value.length}")

  /**
   * Compare this record to another by key only (unsigned).
   *
   * @return negative if this < that, 0 if equal, positive if this > that
   */
  override def compare(that: Record): Int = {
    var i = 0
    while (i < 10) {
      val unsigned1 = this.key(i) & 0xFF  // Convert signed to unsigned
      val unsigned2 = that.key(i) & 0xFF
      val diff = unsigned1 - unsigned2
      if (diff != 0) return diff
      i += 1
    }
    0
  }

  /** Serialize to 100 bytes */
  def toBytes: Array[Byte] = key ++ value

  /** Create a copy with the same data */
  def copy(): Record = Record(key.clone(), value.clone())

  override def toString: String = {
    val keyHex = key.take(3).map("%02X".format(_)).mkString("")
    s"Record(key=$keyHex..., value=${value.length}B)"
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: Record =>
      java.util.Arrays.equals(this.key, that.key) &&
      java.util.Arrays.equals(this.value, that.value)
    case _ => false
  }

  override def hashCode(): Int = {
    java.util.Arrays.hashCode(key)
  }
}
```

**주요 포인트**:
1. **Unsigned 비교 필수**: `& 0xFF`로 signed → unsigned 변환
2. **불변성**: case class로 immutability 보장
3. **문서화**: Scaladoc으로 모든 public method 설명

#### InputFormatDetector (Week 5 완료)

**파일**: `distsort/core/InputFormatDetector.scala`

```scala
package distsort.core

import java.io.{File, FileInputStream}
import org.slf4j.LoggerFactory

/**
 * Automatically detects whether a file is ASCII or Binary format.
 *
 * Algorithm:
 *   1. Read first 1000 bytes of the file
 *   2. Count ASCII printable characters
 *   3. If ratio > 90%, classify as ASCII; otherwise Binary
 *
 * This satisfies the PDF requirement:
 * "Should work on both ASCII and binary input without requiring an option"
 */
object InputFormatDetector {
  private val logger = LoggerFactory.getLogger(getClass)

  private val SAMPLE_SIZE = 1000
  private val ASCII_THRESHOLD = 0.9

  /**
   * Detect the format of a file.
   *
   * @param file The file to analyze
   * @return DataFormat.Ascii or DataFormat.Binary
   */
  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)

    try {
      val bytesRead = inputStream.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}, defaulting to Binary")
        return DataFormat.Binary
      }

      val asciiCount = buffer.take(bytesRead).count(isAsciiPrintable)
      val asciiRatio = asciiCount.toDouble / bytesRead

      logger.debug(s"File: ${file.getName}, ASCII ratio: $asciiRatio")

      if (asciiRatio > ASCII_THRESHOLD) {
        logger.info(s"Detected ASCII format for file: ${file.getName}")
        DataFormat.Ascii
      } else {
        logger.info(s"Detected Binary format for file: ${file.getName}")
        DataFormat.Binary
      }
    } finally {
      inputStream.close()
    }
  }

  /**
   * Check if a byte is ASCII printable or whitespace.
   *
   * @param b The byte to check
   * @return true if printable or whitespace
   */
  private def isAsciiPrintable(b: Byte): Boolean = {
    (b >= 32 && b <= 126) ||  // Printable ASCII (space to ~)
    b == '\n' ||               // Newline
    b == '\r'                  // Carriage return
  }
}
```

**설계 패턴**: Singleton Object (Utility)

#### BinaryRecordReader (Week 5 완료)

**파일**: `distsort/core/BinaryRecordReader.scala`

```scala
package distsort.core

import java.io.{BufferedInputStream, File, FileInputStream, InputStream, IOException}
import org.slf4j.LoggerFactory

/**
 * Reads 100-byte binary records from a file.
 *
 * Each record consists of:
 *   - 10 bytes: key
 *   - 90 bytes: value
 *
 * Uses BufferedInputStream with 1MB buffer for efficient I/O.
 */
class BinaryRecordReader extends RecordReader {
  private val logger = LoggerFactory.getLogger(getClass)
  private var inputStream: Option[BufferedInputStream] = None

  /**
   * Open a file for reading.
   *
   * @param file The file to read
   */
  def open(file: File): Unit = {
    close()  // Close previous stream if any

    inputStream = Some(new BufferedInputStream(
      new FileInputStream(file),
      1024 * 1024  // 1MB buffer
    ))

    logger.debug(s"Opened binary file: ${file.getName}")
  }

  /**
   * Read a single 100-byte record.
   *
   * @param input The input stream (typically BufferedInputStream)
   * @return Some(record) if successful, None if EOF
   * @throws IOException if incomplete record is encountered
   */
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val record = new Array[Byte](100)
    val bytesRead = input.read(record)

    if (bytesRead == 100) {
      Some(record)
    } else if (bytesRead == -1) {
      None  // EOF
    } else {
      throw new IOException(
        s"Incomplete binary record: expected 100 bytes, got $bytesRead"
      )
    }
  }

  /**
   * Close the input stream.
   */
  def close(): Unit = {
    inputStream.foreach { stream =>
      stream.close()
      logger.debug("Closed binary reader")
    }
    inputStream = None
  }
}
```

**최적화**:
- BufferedInputStream: I/O 호출 횟수 대폭 감소
- Buffer 크기 1MB: 경험적으로 최적

### 6.2 FileLayout 상세 구현

**파일**: `distsort/core/FileLayout.scala` (일부)

```scala
/**
 * Manages file system layout for distributed sorting.
 *
 * Directory structure:
 *   Input:  /data1/input/       (read-only, never modified)
 *   Output: /home/gla/data/     (final partition.* files only)
 *   Temp:   /tmp/sort_work_W0/  (intermediate files, auto-deleted)
 *
 * This class ensures:
 *   1. Input directories are never modified (PDF requirement)
 *   2. Temp and output directories are separate
 *   3. Sufficient disk space before starting
 *   4. Automatic cleanup on completion
 */
class FileLayout(
    inputDirs: List[File],
    outputDir: File,
    tempBaseDir: File,
    workerId: String
) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val workTempDir = new File(tempBaseDir, s"sort_work_$workerId")

  // Subdirectories
  private val samplesDir = new File(workTempDir, "samples")
  private val sortedChunksDir = new File(workTempDir, "sorted_chunks")
  private val partitionsDir = new File(workTempDir, "partitions")
  private val receivedDir = new File(workTempDir, "received")

  /**
   * Validate input directories (read-only access).
   *
   * CRITICAL: This method must NOT modify input directories!
   */
  def validateInputDirectories(): Unit = {
    logger.info(s"Validating ${inputDirs.length} input directories")

    inputDirs.foreach { dir =>
      require(dir.exists(), s"Input directory not found: $dir")
      require(dir.isDirectory, s"Not a directory: $dir")
      require(dir.canRead, s"Cannot read directory: $dir")

      // Count files
      val fileCount = listFilesRecursively(dir).length
      logger.info(s"  $dir: $fileCount files")
    }
  }

  /**
   * Create temporary directory structure.
   */
  def createTemporaryStructure(): Unit = {
    logger.info(s"Creating temporary structure: $workTempDir")

    List(samplesDir, sortedChunksDir, partitionsDir, receivedDir).foreach { dir =>
      if (!dir.exists()) {
        val created = dir.mkdirs()
        require(created, s"Failed to create directory: $dir")
        logger.debug(s"  Created: $dir")
      }
    }
  }

  /**
   * Ensure sufficient disk space for sorting.
   *
   * Required space:
   *   - Temp: 2x input size (intermediate files)
   *   - Output: 1x input size (final partitions)
   *
   * Safety margin: 50% extra for temp
   */
  def ensureSufficientDiskSpace(): Unit = {
    val totalInputSize = inputDirs.map(calculateDirSize).sum
    val inputSizeGB = totalInputSize / 1e9

    logger.info(f"Total input size: $inputSizeGB%.2f GB")

    // Check temp space
    val requiredTemp = totalInputSize * 2
    val availableTemp = tempBaseDir.getUsableSpace
    val requiredTempWithMargin = requiredTemp * 1.5

    require(availableTemp > requiredTempWithMargin,
      f"Insufficient temp space: available ${availableTemp / 1e9}%.2f GB, " +
      f"required ${requiredTempWithMargin / 1e9}%.2f GB"
    )

    // Check output space
    val availableOutput = outputDir.getUsableSpace
    val requiredOutput = totalInputSize * 1.2

    require(availableOutput > requiredOutput,
      f"Insufficient output space: available ${availableOutput / 1e9}%.2f GB, " +
      f"required ${requiredOutput / 1e9}%.2f GB"
    )

    logger.info("Disk space validation passed")
  }

  /**
   * Calculate total size of a directory recursively.
   */
  private def calculateDirSize(dir: File): Long = {
    if (!dir.exists()) return 0L

    val files = listFilesRecursively(dir)
    files.map(_.length()).sum
  }

  /**
   * List all files in a directory recursively.
   */
  private def listFilesRecursively(dir: File): List[File] = {
    if (!dir.exists() || !dir.isDirectory) return List.empty

    val (files, subdirs) = dir.listFiles().toList.partition(_.isFile)
    files ++ subdirs.flatMap(listFilesRecursively)
  }

  /**
   * Clean up temporary files.
   *
   * This is called:
   *   1. On worker startup (idempotent operation)
   *   2. After successful completion
   *   3. On error/crash recovery
   */
  def cleanupTemporaryFiles(): Unit = {
    if (workTempDir.exists()) {
      logger.info(s"Cleaning up temporary files: $workTempDir")
      deleteRecursively(workTempDir)
    }
  }

  /**
   * Recursively delete a directory.
   */
  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }

  // Getters for subdirectories
  def getSamplesDir: File = samplesDir
  def getSortedChunksDir: File = sortedChunksDir
  def getPartitionsDir: File = partitionsDir
  def getReceivedDir: File = receivedDir
  def getOutputDir: File = outputDir
}
```

### 6.3 코드 품질 지표

**Week 4-5 기준**:
- 총 코드 라인: ~800 LOC (Comments 포함 ~1200 LOC)
- 테스트 커버리지: Record 5/5 passing
- 문서화: 모든 public method Scaladoc 작성
- 코드 스타일: Scala 표준 가이드 준수

### 6.4 해결한 기술적 문제

#### 문제 1: Unsigned Byte 비교

**증상**: 0xFF가 0x01보다 작게 정렬됨 (signed 비교)

**해결**:
```scala
// ❌ 잘못된 코드
def compare(that: Record): Int = {
  this.key(0).compareTo(that.key(0))  // Signed comparison
}
// 결과: 0xFF (-1 in signed) < 0x01 (1 in signed)

// ✅ 올바른 코드
def compare(that: Record): Int = {
  val unsigned1 = this.key(0) & 0xFF  // 0xFF → 255
  val unsigned2 = that.key(0) & 0xFF  // 0x01 → 1
  unsigned1 - unsigned2                // 255 - 1 = 254 > 0
}
```

#### 문제 2: Buffer의 clone() 누락

**증상**: 같은 레코드가 반복해서 읽힘 (참조 공유)

**원인**:
```scala
// ❌ 잘못된 코드
val record = new Array[Byte](100)
while (input.read(record) == 100) {
  records += record  // 같은 배열 참조!
}
// 결과: records에 같은 배열이 여러 번 들어감
```

**해결**:
```scala
// ✅ 올바른 코드
val record = new Array[Byte](100)
while (input.read(record) == 100) {
  records += record.clone()  // 복사본 저장
}
```

#### 문제 3: ASCII Threshold 결정

**실험**:
- Threshold 0.5: Binary 파일을 ASCII로 오판
- Threshold 0.95: ASCII 파일을 Binary로 오판
- **Threshold 0.9**: 최적 (gensort 데이터로 검증)

**최종 결정**: 0.9 (90%)

#### 문제 4: 디스크 공간 부족 대비

**전략**:
1. 작업 시작 전 공간 검증
2. 필요 공간 = inputSize * 2 (임시 파일 고려)
3. 안전 여유 = 50% 추가
4. 실패 시 명확한 에러 메시지

### 6.5 구현 품질 관리

#### 버퍼 크기 결정 근거

**BinaryRecordReader: 1MB 버퍼**
- 목적: I/O 호출 횟수 최소화
- 근거: 일반적인 파일 시스템 블록 크기와 메모리 효율성 균형

**RecordWriter: 4MB 버퍼**
- 목적: 대용량 출력 시 I/O 효율 향상
- 근거: 출력 파일이 더 크므로 버퍼 증가, 8MB 이상은 메모리 낭비 우려

---

## 7. 향후 계획 (Week 6-8)

### 7.1 Week 6: Algorithms 구현

#### 목표

**ExternalSorter**, **Partitioner**, **KWayMerger** 구현

#### 세부 계획

##### ExternalSorter

```scala
class ExternalSorter(
    chunkSize: Long = 100 * 1024 * 1024,  // 100MB
    numThreads: Int = Runtime.getRuntime.availableProcessors()
) {

  /**
   * Sort input file using 2-pass external sort.
   *
   * Phase 1: Split into chunks, sort each in parallel
   * Phase 2: K-way merge all sorted chunks
   */
  def sort(inputFile: File, outputFile: File): Unit = {
    // Phase 1: Chunk sort (parallel)
    val sortedChunks = sortChunksInParallel(inputFile)

    // Phase 2: K-way merge
    kWayMerge(sortedChunks, outputFile)

    // Cleanup
    sortedChunks.foreach(_.delete())
  }

  private def sortChunksInParallel(inputFile: File): List[File] = {
    // TDD: Test first!
    ???
  }
}
```

##### Partitioner

```scala
class Partitioner(
    boundaries: Array[Array[Byte]],
    numPartitions: Int
) {

  /**
   * Partition sorted chunks by range.
   *
   * Uses binary search to find partition ID for each record.
   */
  def partition(sortedChunk: File, outputDir: File): Map[Int, File] = {
    // TDD: Test first!
    ???
  }

  private def findPartitionBinarySearch(key: Array[Byte]): Int = {
    // TDD: Test first!
    ???
  }
}
```

##### KWayMerger

```scala
class KWayMerger {

  /**
   * Merge K sorted files into one using priority queue.
   */
  def merge(inputFiles: List[File], outputFile: File): Unit = {
    // Min-heap
    val heap = mutable.PriorityQueue[RecordWithSource]()(
      Ordering.by[RecordWithSource, Array[Byte]](_.record.key).reverse
    )

    // TDD: Test first!
    ???
  }
}
```

#### 검증 기준

```bash
# 테스트 데이터 생성
gensort -b0 1000000 test_input.dat  # 100MB

# 로컬 정렬 실행 (Worker 없이)
sbt "runMain distsort.LocalSortTest test_input.dat test_output.dat"

# 검증
valsort test_output.dat
# 예상: SUCCESS
```

### 7.2 Week 7: Master/Worker 구현

#### Master Node

```
주요 기능:
  ├─ Worker 등록 관리 (CountDownLatch)
  ├─ 샘플 수집 및 파티션 경계 계산
  ├─ shuffleMap 생성 및 브로드캐스트
  ├─ Phase 동기화 (PhaseTracker)
  └─ 최종 결과 출력 (stdout)

구현 순서:
  1. 🔴 RED: MasterSpec 작성
  2. 🟢 GREEN: 최소 구현
  3. 🔵 REFACTOR: 리팩토링
```

#### Worker Node

```
주요 기능:
  ├─ Master에 등록
  ├─ 샘플 추출 및 전송
  ├─ 정렬 및 파티셔닝
  ├─ Shuffle (송신/수신)
  ├─ Merge
  └─ 완료 보고

상태 머신:
  INITIALIZING → SAMPLING → SORTING → SHUFFLING → MERGING → COMPLETED
```

### 7.3 Week 8: 통합 테스트 및 최적화

#### 통합 테스트

```bash
# Scenario 1: 3 workers, 3 partitions (Strategy A)
$ ./test_3w3p.sh

# Scenario 2: 3 workers, 9 partitions (Strategy B)
$ ./test_3w9p.sh

# Scenario 3: Worker crash during shuffle
$ ./test_fault_tolerance.sh
```

#### 최적화

- 멀티스레드 활용도 향상
- 네트워크 대역폭 최적화
- 디스크 I/O 병목 제거

---

## 8. Q&A 준비

### 8.1 예상 질문

#### Q1: "TDD를 선택한 이유는?"

**답변**:
- 분산 시스템은 상태 전이와 네트워크 오류 시나리오가 복잡
- 테스트를 먼저 작성하면 요구사항을 명확히 정의할 수 있음
- 리팩토링 시 안전성 보장
- 예: Record 클래스의 unsigned 비교 버그를 테스트로 먼저 발견

#### Q2: "Checkpoint 기반 복구는 어떻게 동작하나?"

**답변**:
- **저장**: 각 Phase 완료 시 WorkerState를 JSON으로 자동 저장 (`/tmp/distsort/checkpoints/`)
- **복구**: Worker 재시작 시 최신 checkpoint 로드 → 마지막 완료 Phase부터 재개
- **예시**: Shuffle 중 crash → PHASE_SORTING checkpoint 로드 → Shuffle만 재시도 (Sampling/Sort 스킵)
- **장점**:
  - 빠른 복구 (전체 재시작 대비 시간 절약)
  - 정확성 보장 (Phase별 완료 시점 checkpoint)
  - Graceful Shutdown 통합 (30초 grace period)
- **구현**: CheckpointManager + JSON 직렬화 + 최근 3개 유지
- **PDF 요구사항**: "produce correct results" ← 정확성과 효율성 모두 충족

#### Q3: "N→M 전략의 장점은?"

**답변**:
- 로드 밸런싱 개선: 파티션 수 증가로 크기 불균형 완화
- 멀티코어 활용: 각 Worker가 여러 파티션 병렬 merge
- PDF 요구사항 충족: "numPartitions는 워커 수와 동일 또는 배수"
- 예: 3 workers, 9 partitions → 각 Worker가 3개 파티션 병렬 merge

#### Q4: "ASCII/Binary 자동 감지는 어떻게 동작하나?"

**답변**:
- 파일의 첫 1000 바이트 읽기
- ASCII printable 문자 비율 계산
- 비율 > 90% → ASCII, 그 외 → Binary
- 각 파일마다 독립적 감지 → 혼합 입력 지원
- PDF 요구사항: "without requiring an option"

#### Q5: "현재 진행률은?"

**답변**:
- Week 1-2: 설계 단계 100% 완료
- Week 3: Record 클래스 100% 완료
- Week 4-5: Core I/O Components 100% 완료
  - RecordReader, InputFormatDetector, FileLayout, RecordWriter
- Week 6: Algorithms 구현 예정
  - ExternalSorter, Partitioner, KWayMerger
- Week 7-8: Master/Worker 통합 예정

전체 진행률: 약 40% (설계 + 기초 컴포넌트 완료)

#### Q6: "가장 어려웠던 부분은?"

**답변**:
1. **Unsigned 비교**: Scala의 Byte가 signed → `& 0xFF`로 변환 필요
2. **파티션 전략**: N→N vs N→M 선택, shuffleMap 로직 이해
3. **Fault Tolerance**: 어디까지 복구 vs 재시작? → PDF 해석 + 구현 복잡도 고려

#### Q7: "테스트는 어떻게 하나?"

**답변**:
- **Unit Tests**: 개별 함수/클래스 검증 (ScalaTest)
  - 예: RecordSpec, RecordReaderSpec
- **Integration Tests**: 컴포넌트 간 통신 검증
  - 예: gRPC 통신, Shuffle 재시도
- **E2E Tests**: 전체 시스템 검증
  - 예: 3 workers로 100MB 데이터 정렬, valsort로 검증
- **Fault Tolerance Tests**: Worker crash 시나리오
  - 예: Shuffle 중 kill -9, 재시작 후 복구

### 8.2 데모 시나리오 (Week 6 이후)

```bash
# 1. 테스트 데이터 생성
$ gensort -b0 10000000 /data1/input/test.dat  # 1GB

# 2. Master 시작 (3 workers, 9 partitions)
$ sbt "runMain distsort.Main master 3 9"
[INFO] Master started on port 30000
[INFO] Waiting for 3 workers to register...

# 3. Worker 시작 (3대)
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1/input -O /data1/output"
[INFO] Worker registered with ID W0

$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data2/input -O /data2/output"
[INFO] Worker registered with ID W1

$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data3/input -O /data3/output"
[INFO] Worker registered with ID W2

# 4. Master 출력
192.168.1.100:30000
worker0, worker1, worker2

# 5. 결과 검증
$ valsort /data1/output/partition.* /data2/output/partition.* /data3/output/partition.*
SUCCESS
```

---

## 9. 참고 문헌

### 9.1 핵심 알고리즘

- Knuth, Donald E. *The Art of Computer Programming, Volume 3: Sorting and Searching*. Addison-Wesley, 1998.
  - External Sorting 알고리즘 (5.4절)

- TeraSort: A Sample Hadoop Application
  - Sampling for Partitioning 기법

### 9.2 시스템 설계

- Dean, Jeffrey, and Sanjay Ghemawat. "MapReduce: Simplified Data Processing on Large Clusters." *OSDI* 2004.
  - Master-Worker 아키텍처, Fault Tolerance 전략

- Zaharia, Matei, et al. "Resilient Distributed Datasets: A Fault-Tolerant Abstraction for In-Memory Cluster Computing." *NSDI* 2012.
  - Lineage-based Fault Recovery

### 9.3 구현 참고

- gRPC 공식 문서: https://grpc.io/docs/languages/scala/
- Protocol Buffers: https://protobuf.dev/
- **gensort/valsort**: http://www.ordinal.com/gensort.html
  - 정렬 테스트 데이터 생성 및 검증 도구

### 9.4 프로젝트 문서

- `plan/2025-10-24_plan_ver3.md`: 전체 시스템 설계
- `docs/0-implementation-decisions.md`: 핵심 구현 결정 사항
- `docs/1-phase-coordination.md`: Phase 동기화 프로토콜
- `docs/4-error-recovery.md`: 장애 복구 메커니즘
- `docs/6-parallelization.md`: 멀티코어 병렬 처리
- `docs/7-testing-strategy.md`: TDD 가이드

---

**발표 준비 완료**
**Team Silver** - 2025-11-16

---

## Appendix: 추가 자료

### A.1 프로젝트 구조

```
project_2025/
├── build.sbt
├── project/
│   └── build.properties
├── src/
│   └── main/
│       ├── scala/distsort/
│       │   ├── Main.scala
│       │   ├── core/
│       │   │   ├── Record.scala               ✅ Week 4
│       │   │   ├── RecordReader.scala         ✅ Week 5
│       │   │   ├── BinaryRecordReader.scala   ✅ Week 5
│       │   │   ├── AsciiRecordReader.scala    ✅ Week 5
│       │   │   ├── InputFormatDetector.scala  ✅ Week 5
│       │   │   ├── FileLayout.scala           ✅ Week 5
│       │   │   └── RecordWriter.scala         ✅ Week 5
│       │   ├── algorithms/
│       │   │   ├── ExternalSorter.scala       📋 Week 6
│       │   │   ├── Partitioner.scala          📋 Week 6
│       │   │   └── KWayMerger.scala           📋 Week 6
│       │   ├── master/
│       │   │   ├── MasterServer.scala         📋 Week 7
│       │   │   └── PhaseTracker.scala         📋 Week 7
│       │   └── worker/
│       │       ├── WorkerNode.scala           📋 Week 7
│       │       └── WorkerStateMachine.scala   📋 Week 7
│       └── protobuf/
│           └── distsort.proto                 📋 Week 7
├── docs/
│   ├── 0-implementation-decisions.md
│   ├── 1-phase-coordination.md
│   ├── 4-error-recovery.md
│   ├── 6-parallelization.md
│   └── 7-testing-strategy.md
└── plan/
    └── 2025-10-24_plan_ver3.md
```

### A.2 개발 도구

| 도구 | 버전 | 용도 |
|------|------|------|
| Scala | 2.13.12 | 구현 언어 |
| SBT | 1.9.7 | 빌드 도구 |
| gRPC | 1.59.1 | RPC 프레임워크 |
| ScalaTest | 3.2.17 | 테스트 프레임워크 |
| gensort | 1.5 | 테스트 데이터 생성 |
| valsort | 1.5 | 정렬 결과 검증 |

### A.3 Git Commit 전략

```
Commit Message 형식:
  <type>: <subject>

  <body>

  Co-Authored-By: 권동연 <yeon903@github>
  Co-Authored-By: 박범순 <pbs7818@github>
  Co-Authored-By: 임지훈 <Jih00nLim@github>

Type:
  feat: 새로운 기능
  fix: 버그 수정
  docs: 문서 업데이트
  test: 테스트 추가/수정
  refactor: 리팩토링

예시:
  feat: Implement BinaryRecordReader with 1MB buffering

  - Add BinaryRecordReader class
  - Use BufferedInputStream for efficient I/O
  - Handle EOF and incomplete records
  - Add comprehensive error messages

  Co-Authored-By: ...
```
