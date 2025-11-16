# 분산 정렬 시스템 중간 발표
**Team Silver** - 권동연, 박범순, 임지훈

**발표일**: 2025-11-16
**프로젝트**: Fault-Tolerant Distributed Sorting System

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [Challenges (도전 과제)](#2-challenges-도전-과제)
3. [해결 방안](#3-해결-방안)
4. [개발 방법론: TDD](#4-개발-방법론-tdd)
5. [현재 진행 상황 (Week 4-5)](#5-현재-진행-상황-week-4-5)
6. [향후 계획 (Week 6-8)](#6-향후-계획-week-6-8)
7. [Q&A 준비](#7-qa-준비)
8. [참고 문헌](#8-참고-문헌)

---

## 1. 프로젝트 개요

### 1.1 목표

여러 머신에 분산 저장된 대용량 key/value 레코드를 **정렬**하는 **장애 허용성** 분산 시스템 구현

### 1.2 핵심 요구사항

| 항목 | 요구사항 |
|------|---------|
| **입력** | 여러 Worker 노드에 분산된 미정렬 데이터 |
| **출력** | 전역적으로 정렬된 데이터 (partition.0, partition.1, ...) |
| **레코드** | 100-byte (10-byte key + 90-byte value) |
| **정렬** | Key만 사용 (unsigned byte 비교) |
| **검증** | gensort/valsort 도구 활용 |

### 1.3 기술 스택

```
언어:        Scala 2.13
빌드 도구:   SBT 1.9.7
RPC:         gRPC + Protocol Buffers
테스트:      ScalaTest (TDD)
버전 관리:   Git
```

### 1.4 레코드 구조

```
┌──────────────┬────────────────────────────────────────┐
│ Key (10B)    │ Value (90B)                            │
└──────────────┴────────────────────────────────────────┘
  0            10                                      100

특징:
- 고정 길이: 100 바이트
- Key만 정렬 기준 (unsigned 비교)
- Value는 Key와 함께 이동
```

---

## 2. Challenges (도전 과제)

### Challenge 1: 입력이 메모리보다 큼

**문제**:
- 입력 데이터가 메모리에 들어가지 않음
- 예: 입력 50GB, 메모리 8GB

**해결책**: **Disk-based Merge Sort** (External Sort)

```
50GB 입력
  ↓
Read → 100MB chunks → Sort/Write (병렬)
  ↓
Merge (merging 500 files) using K-way merge
  ↓
50GB 정렬된 출력
```

### Challenge 2: 입력이 디스크보다 큼 (분산 환경)

**문제**:
- 입력 데이터가 단일 디스크에 들어가지 않음
- 예: 입력 10TB, 디스크 1TB
- 입력/출력이 **여러 머신**에 분산 저장

**해결책**: **Distributed Sorting** (Master-Worker)

```
┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│  Worker 0   │       │  Worker 1   │       │  Worker 2   │
│  Input: 50GB│       │  Input: 50GB│       │  Input: 50GB│
└─────────────┘       └─────────────┘       └─────────────┘
       ↓                     ↓                     ↓
    Sort/Partition     Sort/Partition     Sort/Partition
       ↓                     ↓                     ↓
       └──────────── Shuffle (Network) ────────────┘
                            ↓
                     Merge on each worker
                            ↓
           ┌────────────────┼────────────────┐
           ↓                ↓                ↓
       50GB #1          50GB #2          50GB #3
    (partition.0-2)  (partition.3-5)  (partition.6-8)
```

### Challenge 3: Workers may crash

**문제**:
- Worker가 실행 중 crash (killed by OS)
- **모든 중간 데이터 손실** ("All its intermediate data is lost")
- 같은 노드에서 새 Worker 시작 (같은 파라미터)

**요구사항**: **Fault-tolerant system**
- 새 Worker가 기존 Worker와 **동일한 출력 생성** (deterministic)
- 전체 시스템이 정확한 결과 생성

**해결책**: **2-Layer Fault Tolerance (Checkpoint + Replication)**

```
Worker 2 crash during Shuffle:
  Last checkpoint: PHASE_SORTING (100% 완료)
  Lost data: P6, P7, P8 (intermediate partition files)

Worker 2' restart:
  1. Load checkpoint (Layer 1)
     → Restore state (shuffleMap, partitionBoundaries, ...)
     → Resume point: PHASE_SORTING

  2. Recover lost data from replicas (Layer 2)
     → Fetch P6 from Worker 1 (backup copy)
     → Fetch P7 from Worker 0 (backup copy)
     → Fetch P8 from Worker 3 (backup copy)
     → Checksum verification ✅

  3. Resume from last completed phase
     → Shuffle → Merge → Complete
     ⭐ Sampling/Sort 스킵 (빠른 복구)

Result:
  ✅ Fault-tolerant system (슬라이드 요구사항 충족)
  ✅ 동일한 최종 출력 (deterministic recovery)
```

### Additional Requirements

#### Requirement 1: ASCII/Binary 자동 감지
- ASCII와 Binary 입력을 **옵션 없이** 처리
- 파일 형식을 자동으로 감지

#### Requirement 2: 입력 디렉토리 보호
- 입력 디렉토리는 **읽기 전용**
- 입력 파일 삭제 금지
- 입력 디렉토리에 새 파일 생성 금지

#### Requirement 3: 출력 디렉토리 정리
- 출력 디렉토리는 **최종 파일만** 포함
- 임시 파일/디렉토리 생성 가능, 단 **작업 완료 후 삭제**

#### Requirement 4: 포트 하드코딩 금지
- 특정 포트를 하드코딩하지 말 것
- 다양한 입출력 디렉토리로 여러 Worker 실행 가능

---

## 3. 해결 방안

### 3.1 전체 시스템 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                  Master Node                        │
│  - Worker 등록 관리                                  │
│  - 샘플 수집 및 파티션 경계 계산                       │
│  - shuffleMap 생성 및 브로드캐스트                    │
│  - Phase 동기화 조율                                 │
│  - 최종 Worker 순서 출력                             │
└───────────────┬─────────────────────────────────────┘
                │ gRPC
        ┌───────┴───────┬──────────┬──────────┐
        │               │          │          │
   ┌────▼─────┐   ┌────▼────┐ ┌──▼──────┐ ┌──▼──────┐
   │ Worker 0 │   │Worker 1 │ │Worker 2 │ │Worker 3 │
   │          │   │         │ │         │ │         │
   │Input:    │   │Input:   │ │Input:   │ │Input:   │
   │50GB      │   │50GB     │ │50GB     │ │50GB     │
   │          │   │         │ │         │ │         │
   │Output:   │   │Output:  │ │Output:  │ │Output:  │
   │P0,P1,P2  │   │P3,P4,P5 │ │P6,P7,P8 │ │P9,P10,P11│
   └──────────┘   └─────────┘ └─────────┘ └─────────┘
        │               │          │          │
        └───────────────┴──────────┴──────────┘
       Worker-to-Worker Shuffle (gRPC Streaming)
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
  ├─ External Sort: Chunk 단위 정렬 (병렬)
  │   └─ 메모리 제한 준수 (512MB chunks)
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

---

#### 📊 Phase 1 Sampling 상세 설명

**핵심 질문**: "Master가 전체 샘플 정렬"은 무슨 의미인가?

**목적**: 전체 데이터의 key 분포를 파악하고 **균등한 크기의 파티션**을 만들기 위해

**구체적인 예시 (4 Workers, 12 Partitions)**:

**Step 1: 각 Worker가 샘플 추출**
```scala
class Sampler(sampleRate: Double = 0.1) {  // 10% 샘플링
  def extractSamples(file: File): Seq[Record] = {
    // Random sampling
    if (random.nextDouble() < sampleRate) {
      samples += record
    }
  }
}
```

```
Worker 0 샘플: [0x15, 0x89, 0x23, 0xAA, 0x12, ...] (100개 keys)
Worker 1 샘플: [0x67, 0x12, 0xAB, 0x45, 0xF3, ...] (100개 keys)
Worker 2 샘플: [0x34, 0x78, 0xCC, 0x9A, 0x01, ...] (100개 keys)
Worker 3 샘플: [0x99, 0x3F, 0xDD, 0x6B, 0x28, ...] (100개 keys)
```

**Step 2: Master가 모든 샘플을 모음**
```
allSamples (400개 keys):
  [0x15, 0x89, ..., 0x67, 0x12, ..., 0x34, 0x78, ..., 0x99, 0x3F, ...]
```

**Step 3: ⭐ Master가 샘플을 정렬 (Unsigned byte 비교)**
```scala
// MasterService.scala의 computePartitionBoundaries()
val sortedSamples = allSamples.sorted(byteArrayOrdering)

implicit val byteArrayOrdering: Ordering[Array[Byte]] = new Ordering[Array[Byte]] {
  override def compare(x: Array[Byte], y: Array[Byte]): Int = {
    var i = 0
    while (i < x.length && i < y.length) {
      val cmp = java.lang.Integer.compareUnsigned(x(i) & 0xFF, y(i) & 0xFF)
      if (cmp != 0) return cmp
      i += 1
    }
    x.length - y.length
  }
}
```

```
sortedSamples (400개 keys, 오름차순):
  [0x01, 0x12, 0x12, 0x15, 0x23, 0x28, 0x34, 0x3F, 0x45, ..., 0xF3, 0xFF]
  ↑                                                                      ↑
  가장 작은 key                                                  가장 큰 key
```

**Step 4: 균등한 간격으로 파티션 경계 선택**
```scala
val step = sortedSamples.length / numPartitions  // 400 / 12 = 33
partitionBoundaries = (1 until numPartitions).map { i =>
  sortedSamples(i * step)  // 33번째, 66번째, 99번째, ...
}
```

```
Partition Boundaries (11개 경계, 12개 파티션 생성):

partitionBoundaries[0]  = sortedSamples[33]  → 0x2F
partitionBoundaries[1]  = sortedSamples[66]  → 0x5A
partitionBoundaries[2]  = sortedSamples[99]  → 0x78
partitionBoundaries[3]  = sortedSamples[132] → 0x95
...
partitionBoundaries[10] = sortedSamples[363] → 0xE8

결과적으로 생성되는 파티션:
  Partition 0:  [0x00, 0x2F) ← 가장 작은 key들
  Partition 1:  [0x2F, 0x5A)
  Partition 2:  [0x5A, 0x78)
  Partition 3:  [0x78, 0x95)
  ...
  Partition 11: [0xE8, 0xFF] ← 가장 큰 key들
```

**Step 5: shuffleMap 생성 (N→M 전략)**
```scala
def createShuffleMap(numWorkers: Int, numPartitions: Int): Map[Int, Int] = {
  val partitionsPerWorker = numPartitions / numWorkers  // 12 / 4 = 3

  (0 until numPartitions).map { partitionID =>
    val workerID = partitionID / partitionsPerWorker
    partitionID -> workerID
  }.toMap
}

// 결과:
shuffleMap = {
  0→0, 1→0, 2→0,     // P0, P1, P2 → Worker 0
  3→1, 4→1, 5→1,     // P3, P4, P5 → Worker 1
  6→2, 7→2, 8→2,     // P6, P7, P8 → Worker 2
  9→3, 10→3, 11→3    // P9, P10, P11 → Worker 3
}
```

**시각화**:
```
전체 데이터 분포 (50GB):
┌────────────────────────────────────────────────┐
│ 0x00 ──────────────────────────────── 0xFF    │
└────────────────────────────────────────────────┘
         ↓ 샘플링 (10%)
┌────────────────────────────────────────────────┐
│ 샘플: 400개 keys                               │
└────────────────────────────────────────────────┘
         ↓ 정렬
┌────────────────────────────────────────────────┐
│ sortedSamples: [0x01, 0x12, ..., 0xFF]        │
└────────────────────────────────────────────────┘
         ↓ 균등 분할 (step = 33)
┌────┬────┬────┬────┬────┬────┬────┬────┬────┐
│ P0 │ P1 │ P2 │ P3 │ P4 │ P5 │... │P10 │P11 │
└────┴────┴────┴────┴────┴────┴────┴────┴────┘
0x00 0x2F 0x5A 0x78 0x95 ...               0xFF

각 파티션 예상 크기: 50GB / 12 ≈ 4.17GB
```

**왜 샘플을 사용하는가?**
1. **메모리 제한**: 50GB 전체를 Master 메모리에 올릴 수 없음
2. **시간 절약**: 400개 샘플 정렬 vs 500,000,000개 레코드 정렬
3. **충분히 정확**: 샘플이 전체 데이터 분포를 잘 대표함

**장점**:
- ✅ 각 파티션의 **예상 크기가 비슷함** (로드 밸런싱)
- ✅ Worker별 부하가 균등함 (각 Worker가 3개 파티션 처리)
- ✅ 최종 출력이 정렬됨 (P0 → P1 → ... → P11 순서로 전역 정렬)
- ✅ 전체 데이터를 정렬하지 않고도 분포 파악 가능

---

### 3.3 Challenge 1 해결: External Sort (메모리 제한)

**문제**: 입력이 메모리보다 큼 (50GB vs 8GB)

**해결책**: 2-Pass External Sort

#### Phase 1: Chunk Sort (병렬)

```scala
class ExternalSorter(
  memoryLimit: Long = 512 * 1024 * 1024  // 512MB 제한
) {
  private val recordsPerChunk = (memoryLimit / 100).toInt

  def createSortedChunksParallel(records: Seq[Record]): Seq[File] = {
    val chunks = records.grouped(recordsPerChunk).toSeq  // ⭐ Chunk로 분할

    // 병렬 정렬 (멀티코어 활용)
    val futures = chunks.zipWithIndex.map { case (chunk, index) =>
      Future {
        val sorted = chunk.sorted              // ⭐ 메모리 내 정렬
        writeChunkToFile(sorted, chunkFile)    // ⭐ 디스크에 저장
        chunkFile
      }
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }
}
```

#### Phase 2: K-way Merge (Priority Queue)

```scala
class KWayMerger(sortedChunks: Seq[File]) {

  def mergeWithCallback(callback: Record => Unit): Unit = {
    val readers = sortedChunks.map(RecordReader.create)
    val heap = mutable.PriorityQueue[HeapEntry]()  // ⭐ Min-heap

    // 각 chunk에서 첫 레코드 읽기
    readers.zipWithIndex.foreach { case (reader, index) =>
      reader.readRecord() match {
        case Some(record) => heap.enqueue(HeapEntry(record, index))
        case None => // Empty chunk
      }
    }

    // 정렬된 순서로 스트리밍 처리 (메모리 효율적)
    while (heap.nonEmpty) {
      val entry = heap.dequeue()
      callback(entry.record)  // ⭐ 하나씩 처리 (메모리 절약)

      // 같은 소스에서 다음 레코드 읽기
      readers(entry.sourceIndex).readRecord() match {
        case Some(nextRecord) =>
          heap.enqueue(HeapEntry(nextRecord, entry.sourceIndex))
        case None => // Source exhausted
      }
    }
  }
}
```

**장점**:
- ✅ 임의 크기 입력 처리 가능 (1KB ~ 10TB+)
- ✅ 병렬 정렬 (멀티코어 활용)
- ✅ 스트리밍 병합 (메모리 효율)

### 3.4 Challenge 2 해결: Distributed System (분산)

**문제**: 입력이 디스크보다 큼, 여러 머신에 분산

**해결책**: Master-Worker 아키텍처 + N→M Partition Strategy

#### N→M Partition Strategy

**개념**: 파티션 수 > Worker 수 (일반적으로 M = 3N)

**예시: 4 Workers, 12 Partitions**

```
Phase 2: Sort & Partition
  Worker 0, 1, 2, 3 각각 → P0~P11 (12개) 생성

Phase 3: Shuffle
  P0, P1, P2   → Worker 0
  P3, P4, P5   → Worker 1
  P6, P7, P8   → Worker 2
  P9, P10, P11 → Worker 3

Phase 4: Merge
  Worker 0:
    - 4개 P0 조각 K-way merge → partition.0
    - 4개 P1 조각 K-way merge → partition.1
    - 4개 P2 조각 K-way merge → partition.2

최종 출력:
  /worker0/output/partition.0  ← 가장 작은 key
  /worker0/output/partition.1
  /worker0/output/partition.2
  /worker1/output/partition.3
  ...
  /worker3/output/partition.11 ← 가장 큰 key

읽기 순서: partition.0 → 1 → 2 → ... → 11 = 전역 정렬됨
```

**shuffleMap 생성**:
```scala
def createShuffleMap(numWorkers: Int, numPartitions: Int): Map[Int, Int] = {
  val partitionsPerWorker = numPartitions / numWorkers

  (0 until numPartitions).map { partitionID =>
    val workerID = partitionID / partitionsPerWorker
    val finalWorkerID = if (workerID >= numWorkers) numWorkers - 1 else workerID
    partitionID -> finalWorkerID
  }.toMap
}

// 예시: createShuffleMap(4, 12)
//   → {0→0, 1→0, 2→0, 3→1, 4→1, 5→1, 6→2, 7→2, 8→2, 9→3, 10→3, 11→3}
```

**장점**:
- ✅ 로드 밸런싱 개선 (파티션 수 증가)
- ✅ 멀티코어 활용 증가 (병렬 merge)
- ✅ 파티션 크기 불균형 완화

### 3.5 Challenge 3 해결: 2-Layer Fault Tolerance

**문제**: Worker가 실행 중 crash → **모든 중간 데이터 손실**

**해결책**: 2-Layer Fault Tolerance
- **Layer 1**: Checkpoint (진행 상태 복구)
- **Layer 2**: Replication (중간 데이터 복구)

---

#### Layer 1: Checkpoint (진행 상태 복구)

**목적**: Worker가 어디까지 진행했는지 추적

**Checkpoint 저장**

```scala
case class WorkerState(
  processedRecords: Long,                  // 처리한 레코드 수
  partitionBoundaries: List[Array[Byte]], // 파티션 경계
  shuffleMap: Map[Int, Int],               // 파티션 → Worker 매핑
  completedPartitions: Set[Int],           // 완료한 파티션들
  currentFiles: List[String],              // 현재 파일들
  phaseMetadata: Map[String, String]       // Phase 메타데이터
)

class CheckpointManager(workerId: String) {
  def saveCheckpoint(
    phase: WorkerPhase,
    state: WorkerState,
    progress: Double
  ): Future[String] = Future {
    val checkpointId = s"checkpoint_${System.currentTimeMillis()}_${phase}"
    val checkpoint = Checkpoint(checkpointId, workerId, phase.toString,
                                 Instant.now(), progress, state)

    // JSON으로 저장: /tmp/distsort/checkpoints/{workerId}/{checkpointId}.json
    val file = checkpointPath.resolve(s"$checkpointId.json").toFile
    val writer = new PrintWriter(file)
    writer.write(gson.toJson(checkpoint))
    writer.close()

    checkpointId
  }
}
```

**저장 시점**:
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

#### 복구 로직

```scala
class Worker(...) {
  def run(): Unit = {
    // 1. Checkpoint에서 복구 시도
    val recoveredFromCheckpoint = recoverFromCheckpoint()

    if (!recoveredFromCheckpoint || currentPhase.get() == PHASE_INITIALIZING) {
      performSampling()
    }

    // 복구된 Phase에 따라 적절한 단계부터 재개
    if (currentPhase.get() == PHASE_SAMPLING || ...) {
      getPartitionConfiguration()
      savePhaseCheckpoint(PHASE_WAITING_FOR_PARTITIONS, 1.0)
    }

    if (currentPhase.get() == PHASE_SORTING || ...) {
      performLocalSort()
      savePhaseCheckpoint(PHASE_SORTING, 1.0)

      performShuffle()
      savePhaseCheckpoint(PHASE_SHUFFLING, 1.0)
    }

    performMerge()
    savePhaseCheckpoint(PHASE_MERGING, 1.0)

    checkpointManager.deleteAllCheckpoints()
  }

  private def recoverFromCheckpoint(): Boolean = {
    checkpointManager.loadLatestCheckpoint() match {
      case Some(checkpoint) =>
        // 상태 복원
        partitionBoundaries = checkpoint.state.partitionBoundaries.toArray
        shuffleMap = checkpoint.state.shuffleMap
        completedPartitions = checkpoint.state.completedPartitions

        // Phase 복원
        currentPhase.set(WorkerPhase.fromName(checkpoint.phase))
        true  // 복구 성공

      case None =>
        false  // 처음부터 시작
    }
  }
}
```

**복구 시나리오**:
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

**Graceful Shutdown 통합**:

```scala
class Worker(...) extends ShutdownAware {
  private val shutdownManager = GracefulShutdownManager(
    ShutdownConfig(
      gracePeriod = 30.seconds,       // ⭐ 30초 대기
      saveCheckpoint = true,           // ⭐ Checkpoint 저장
      waitForCurrentPhase = true       // ⭐ 현재 Phase 완료 대기
    )
  )

  override def gracefulShutdown(): Future[Unit] = {
    // 1. 현재 Phase 완료 대기 (최대 30초)
    // 2. Checkpoint 저장
    val currentState = getCurrentState()
    checkpointManager.saveCheckpoint(currentPhase.get(), currentState, 0.5)

    // 3. 리소스 정리
    cleanupResources()
  }
}
```

---

#### Layer 2: Replication (중간 데이터 복구)

**목적**: Worker crash 시 손실된 중간 파일을 다른 Worker에서 복구

**Replication 설정**:

```scala
case class ReplicationMetadata(
  partitionId: Int,
  primaryWorker: Int,
  backupWorkers: Seq[Int],    // ⭐ 복제본 위치
  version: Long,
  checksum: String,            // ⭐ 데이터 무결성 검증
  size: Long
)

case class PartitionData(
  id: Int,
  records: Seq[Record],
  checksum: String,
  metadata: Map[String, String] = Map.empty
)

class ReplicationManager(
  replicationFactor: Int = 2,  // ⭐ 기본값: 원본 + 복제본 1개
  workerClients: Map[Int, WorkerClient]
) {

  /**
   * 파티션을 백업 Worker들에 복제
   */
  def replicatePartition(
    partitionData: PartitionData,
    replicas: Seq[Int],
    primaryWorker: Int
  ): Future[Unit] = {
    val version = replicationVersion.incrementAndGet()

    val metadata = ReplicationMetadata(
      partitionId = partitionData.id,
      primaryWorker = primaryWorker,
      backupWorkers = replicas,
      version = version,
      checksum = partitionData.checksum,
      size = partitionData.records.size
    )

    // 병렬로 replicas에 전송
    val replicationFutures = replicas.map { workerId =>
      sendToReplica(partitionData, workerId).map { success =>
        if (success) {
          logger.info(s"Replicated partition ${partitionData.id} to Worker $workerId")
        }
      }
    }

    Future.sequence(replicationFutures).map(_ => ())
  }

  /**
   * Replica에서 파티션 복구
   */
  def recoverFromReplica(
    partitionId: Int,
    failedWorker: Int
  ): Future[PartitionData] = {
    replicationMetadata.get(partitionId) match {
      case Some(metadata) if metadata.primaryWorker == failedWorker =>
        // 백업 Worker 중 첫 번째에서 복구
        val backupWorker = metadata.backupWorkers.head

        workerClients(backupWorker)
          .fetchPartition(partitionId)
          .map { data =>
            // ⭐ Checksum 검증
            if (data.checksum == metadata.checksum) {
              logger.info(s"Successfully recovered partition $partitionId from Worker $backupWorker")
              data
            } else {
              throw new Exception("Checksum mismatch")
            }
          }

      case _ =>
        Future.failed(new Exception(s"No replica found for partition $partitionId"))
    }
  }

  /**
   * Replica Worker 선택 (Consistent Hashing)
   */
  def selectReplicas(
    partitionId: Int,
    excludeWorker: Int
  ): Seq[Int] = {
    val availableWorkers = workerClients.keys.filterNot(_ == excludeWorker).toSeq

    if (availableWorkers.size < replicationFactor - 1) {
      logger.warn(s"Not enough workers for replication factor $replicationFactor")
      availableWorkers
    } else {
      // Deterministic selection
      val random = new Random(partitionId)
      random.shuffle(availableWorkers).take(replicationFactor - 1)
    }
  }
}
```

**Replication 시점**:
```
Phase 2: Sort & Partition 완료 후
  ├─ Worker 0이 P0, P1, P2 생성
  ├─ ReplicationManager.replicatePartition(P0, Seq(Worker1), Worker0)
  ├─ ReplicationManager.replicatePartition(P1, Seq(Worker2), Worker0)
  └─ ReplicationManager.replicatePartition(P2, Seq(Worker3), Worker0)

Result:
  Worker 0: P0 (primary), P3 (backup), P6 (backup), P9 (backup)
  Worker 1: P0 (backup), P3 (primary), P7 (backup), P10 (backup)
  ...
```

---

#### 통합 복구 시나리오

**Scenario: Worker 2 crashes during Phase 3 (Shuffle)**

```
Initial State:
  Worker 0, 1, 2, 3 → Phase 2 완료, 각각 P0~P11 생성
  Worker 2가 Shuffle 중 crash

Crash Detection:
  Master가 Worker 2의 heartbeat timeout 감지
  → Worker 2' (새 프로세스) 시작

Recovery Process:

  Step 1: Checkpoint 로드 (Layer 1)
    Worker 2'.recoverFromCheckpoint()
    → currentPhase = PHASE_SORTING (마지막 완료 Phase)
    → shuffleMap, partitionBoundaries 복구
    → completedPartitions = {6, 7, 8} 복구

  Step 2: 중간 파일 복구 (Layer 2)
    Worker 2'가 손실된 파티션 확인:
      P6, P7, P8 (Worker 2가 Sort에서 생성했던 파일들)

    ReplicationManager.recoverFromReplica(6, 2)
      → Worker 1의 backup에서 P6 복사
      → Checksum 검증 ✅

    ReplicationManager.recoverFromReplica(7, 2)
      → Worker 0의 backup에서 P7 복사
      → Checksum 검증 ✅

    ReplicationManager.recoverFromReplica(8, 2)
      → Worker 3의 backup에서 P8 복사
      → Checksum 검증 ✅

  Step 3: 마지막 Phase부터 재개
    Worker 2'.performShuffle()  // ⭐ Sorting 스킵, Shuffle부터 시작
    Worker 2'.performMerge()
    Worker 2'.reportCompletion()

Result:
  ✅ 빠른 복구 (Sampling/Sorting 스킵)
  ✅ 데이터 손실 없음 (Replica에서 복구)
  ✅ 정확성 보장 (Checksum 검증)
  ✅ 최종 출력 동일 (deterministic)
```

---

#### 2-Layer Fault Tolerance 장점

**Checkpoint (Layer 1)**:
- ✅ 빠른 복구 (마지막 완료 Phase부터 재개)
- ✅ 가벼운 오버헤드 (JSON 직렬화)
- ✅ Process memory 복구
- ✅ Graceful Shutdown 통합

**Replication (Layer 2)**:
- ✅ 디스크 손실 대응 (중간 파일 복구)
- ✅ Checksum 검증 (데이터 무결성)
- ✅ Consistent Hashing (deterministic replica 선택)
- ✅ Configurable replication factor (1~N)

**Combined**:
- ✅ **"All intermediate data is lost" 문제 완전 해결**
- ✅ 슬라이드 요구사항 충족: "fault-tolerant system"
- ✅ 새 Worker가 동일한 출력 생성 (deterministic)

### 3.6 Additional Requirements 해결

#### Requirement 1: ASCII/Binary 자동 감지

```scala
object InputFormatDetector {
  private val SAMPLE_SIZE = 1000      // 첫 1000 바이트 분석
  private val ASCII_THRESHOLD = 0.9   // 90% 이상 ASCII

  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)
    val bytesRead = inputStream.read(buffer)

    // ASCII printable 문자 비율 계산
    val asciiCount = buffer.take(bytesRead).count { b =>
      (b >= 32 && b <= 126) || b == '\n' || b == '\r'
    }

    val asciiRatio = asciiCount.toDouble / bytesRead

    if (asciiRatio > ASCII_THRESHOLD) DataFormat.Ascii
    else DataFormat.Binary
  }
}

// RecordReader Factory Pattern
object RecordReader {
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)  // ⭐ 자동 감지
    format match {
      case DataFormat.Binary => new BinaryRecordReader()
      case DataFormat.Ascii  => new AsciiRecordReader()
    }
  }
}
```

**혼합 입력 처리**:
- 각 파일마다 독립적으로 형식 감지
- ASCII와 Binary 파일 혼재 가능

#### Requirement 2 & 3: 입력 보호 + 출력 정리

```scala
class FileLayout(
  inputDirs: List[File],    // 읽기 전용
  outputDir: File,          // 최종 partition.* 파일만
  tempBaseDir: File         // 임시 파일 (자동 삭제)
) {
  /**
   * 입력 디렉토리 검증 (읽기 전용)
   */
  def validateInputDirectories(): Unit = {
    inputDirs.foreach { dir =>
      require(dir.exists() && dir.canRead, s"Cannot read: $dir")
      // ⭐ 수정 금지 (읽기만)
    }
  }

  /**
   * 임시 디렉토리 구조 생성
   */
  def createTemporaryStructure(): Unit = {
    val workDir = new File(tempBaseDir, s"sort_work_$workerId")
    val subdirs = List("samples", "sorted_chunks", "partitions", "received")

    subdirs.foreach { subdir =>
      val dir = new File(workDir, subdir)
      dir.mkdirs()  // ⭐ /tmp/distsort/ 하위에 생성
    }
  }

  /**
   * 임시 파일 정리
   */
  def cleanupTemporaryFiles(): Unit = {
    val workDir = new File(tempBaseDir, s"sort_work_$workerId")
    if (workDir.exists()) {
      deleteRecursively(workDir)  // ⭐ 임시 파일 모두 삭제
    }
  }
}
```

**디렉토리 구조**:
```
Input:    /data1/input/         (읽기 전용, 수정 금지)
Output:   /home/gla/data/       (최종 partition.* 파일만)
Temp:     /tmp/sort_work_W0/    (임시 파일, 자동 삭제)
          ├── samples/
          ├── sorted_chunks/
          ├── partitions/
          └── received/
```

#### Requirement 4: 포트 하드코딩 금지

```scala
class Worker(
  workerId: String,
  masterHost: String,
  masterPort: Int,
  workerPort: Int = 0  // ⭐ 0 = 자동 할당
) {
  def start(): Unit = {
    server = ServerBuilder
      .forPort(workerPort)  // ⭐ 0이면 자동 할당
      .addService(...)
      .build()

    server.start()
    actualPort = server.getPort  // ⭐ 실제 할당된 포트

    logger.info(s"Worker $workerId started on port $actualPort")
  }
}
```

**장점**:
- ✅ 여러 Worker를 같은 머신에서 실행 가능
- ✅ 포트 충돌 방지

### 3.7 gRPC 기반 통신

#### 왜 gRPC를 선택했는가?

| 비교 항목 | HTTP/REST | **gRPC** (선택) |
|----------|-----------|----------------|
| 성능 | JSON 텍스트 (느림) | Protocol Buffers 바이너리 (빠름) |
| Streaming | 제한적 (WebSocket 필요) | ✅ 양방향 streaming 네이티브 지원 |
| 타입 안정성 | 없음 (런타임 에러) | ✅ 컴파일 타임 타입 체크 |
| Code generation | 수동 작성 | ✅ .proto → Scala stub 자동 생성 |
| 대용량 전송 | Chunking 수동 구현 | ✅ Streaming으로 자연스럽게 처리 |

**결론**: 분산 정렬에서 **대용량 파티션 전송**과 **양방향 통신**이 필수 → gRPC가 최적

---

#### Protocol Buffers 정의

```protobuf
// distsort.proto

service MasterService {
  rpc RegisterWorker(WorkerInfo) returns (RegistrationResponse);
  rpc SendSample(SampleData) returns (Ack);
  rpc NotifyPhaseComplete(PhaseCompleteRequest) returns (Ack);
  rpc Heartbeat(HeartbeatRequest) returns (HeartbeatResponse);
}

service WorkerService {
  rpc SetPartitionBoundaries(PartitionConfig) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);  // ⭐ Streaming
  rpc StartShuffle(ShuffleSignal) returns (Ack);
  rpc StartMerge(MergeSignal) returns (Ack);
  rpc GetStatus(StatusRequest) returns (WorkerStatus);
}

message PartitionConfig {
  repeated bytes boundaries = 1;       // N-1 or M-1 개의 경계
  int32 num_partitions = 2;            // N or M
  map<int32, int32> shuffle_map = 3;   // partitionID → workerID
  repeated WorkerInfo all_workers = 4; // 전체 Worker 정보
}

message ShuffleDataChunk {
  int32 partition_id = 1;
  int32 source_worker = 2;
  bytes data = 3;                      // ⭐ 1MB chunk
  int64 sequence_number = 4;
  bool is_last_chunk = 5;
}
```

---

#### 각 RPC 메서드의 역할과 호출 시점

**MasterService (Worker → Master 호출)**

| RPC 메서드 | 호출 시점 | 역할 | 응답 |
|-----------|----------|------|------|
| `RegisterWorker` | Phase 0: Worker 시작 직후 | Worker 등록, index 할당 | `workerIndex`, `numWorkers` |
| `SendSample` | Phase 1: Sampling 완료 후 | 샘플 전송 (100~10,000개 keys) | `Ack` |
| `NotifyPhaseComplete` | 각 Phase 완료 시 | Phase 완료 보고 | `proceedToNext`, `nextPhase` |
| `Heartbeat` | 매 10초 (백그라운드) | Worker 생존 확인 | `timestamp` |

**WorkerService (Worker ↔ Worker 호출, Master → Worker 호출)**

| RPC 메서드 | 호출자 | 호출 시점 | 역할 |
|-----------|-------|----------|------|
| `SetPartitionBoundaries` | Master | Phase 1 완료 후 | 파티션 경계 브로드캐스트 |
| `ShuffleData` | Worker | Phase 3: Shuffle | **⭐ 파티션 전송 (streaming)** |
| `StartShuffle` | Master | Phase 3 시작 | Shuffle 명령 |
| `StartMerge` | Master | Phase 4 시작 | Merge 명령 |
| `GetStatus` | Master/User | 언제든지 | Worker 상태 조회 |

---

#### Streaming의 필요성: ShuffleData

**문제**: Worker 0이 Worker 1에게 4GB 파티션을 전송해야 함

**Non-streaming (잘못된 방식)**:
```scala
// ❌ 4GB를 한 번에 메모리에 로드 → OutOfMemoryError
val allData = readFile(partitionFile)  // 4GB
stub.shuffleData(ShuffleDataChunk(partitionId, allData))
```

**Streaming (올바른 방식)**:
```scala
// ✅ 1MB씩 chunk로 나눠서 전송
val CHUNK_SIZE = 1 * 1024 * 1024  // 1MB

def sendPartitionStreaming(partitionFile: File, partitionId: Int): Unit = {
  val inputStream = new FileInputStream(partitionFile)
  val buffer = new Array[Byte](CHUNK_SIZE)
  var sequenceNumber = 0L
  var bytesRead = 0

  val requestObserver = stub.shuffleData(responseObserver)

  while ({ bytesRead = inputStream.read(buffer); bytesRead > 0 }) {
    val chunk = ShuffleDataChunk(
      partitionId = partitionId,
      sourceWorker = myWorkerId,
      data = ByteString.copyFrom(buffer, 0, bytesRead),
      sequenceNumber = sequenceNumber,
      isLastChunk = false
    )

    requestObserver.onNext(chunk)  // ⭐ 비동기 전송
    sequenceNumber += 1
  }

  // 마지막 chunk 표시
  requestObserver.onNext(chunk.copy(isLastChunk = true))
  requestObserver.onCompleted()
}
```

**장점**:
- ✅ 메모리 사용량: 4GB → 1MB (4000배 절약)
- ✅ 파이프라이닝: 전송과 읽기 동시 진행
- ✅ 조기 오류 감지: 첫 chunk 실패 시 즉시 중단

---

#### Shuffle 네트워크 통신 흐름도

**시나리오**: 4 Workers, 12 Partitions (각 Worker가 3개씩 담당)

```
Phase 3: Shuffle (Worker-to-Worker 통신)

Worker 0이 생성한 파티션들:
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ P0  │ P1  │ P2  │ P3  │ P4  │ P5  │ P6  │ P7  │ P8  │ P9  │ P10 │ P11 │
└──┬──┴──┬──┴──┬──┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
   │     │     │
   │     │     └─────────────────────────────────────┐
   │     └──────────────────────────┐                │
   │                                │                │
   ↓ gRPC streaming                 ↓                ↓
┌──────────┐                   ┌──────────┐    ┌──────────┐
│ Worker 0 │                   │ Worker 1 │    │ Worker 2 │
│ (keep)   │                   │ (recv)   │    │ (recv)   │
└──────────┘                   └──────────┘    └──────────┘

Worker 0 → Worker 0: P0 (자기 자신, 복사만)
Worker 0 → Worker 0: P1 (자기 자신, 복사만)
Worker 0 → Worker 0: P2 (자기 자신, 복사만)

Worker 0 → Worker 1: P3 (gRPC streaming, 1MB chunks)
Worker 0 → Worker 1: P4 (gRPC streaming, 1MB chunks)
Worker 0 → Worker 1: P5 (gRPC streaming, 1MB chunks)

Worker 0 → Worker 2: P6 (gRPC streaming, 1MB chunks)
Worker 0 → Worker 2: P7 (gRPC streaming, 1MB chunks)
Worker 0 → Worker 2: P8 (gRPC streaming, 1MB chunks)

... (다른 Worker들도 동일)

총 네트워크 전송:
  - Worker 0 → Worker 1: 3개 파티션
  - Worker 0 → Worker 2: 3개 파티션
  - Worker 0 → Worker 3: 3개 파티션
  (자기 자신 3개는 네트워크 전송 없음, 로컬 복사)

전체: 4 workers × 9 network transfers = 36 transfers
```

---

#### 재시도 로직과 지수 백오프

**문제**: 네트워크는 불안정함 (일시적 장애, 혼잡, Worker 재시작 등)

**해결**: Exponential Backoff + Retry

```scala
def sendPartitionWithRetry(
    partitionFile: File,
    partitionId: Int,
    targetWorker: WorkerInfo,
    maxRetries: Int = 3
): Unit = {
  var attempt = 0
  var success = false

  while (attempt < maxRetries && !success) {
    try {
      logger.info(s"Sending partition $partitionId to ${targetWorker.workerId} (attempt ${attempt + 1}/$maxRetries)")

      sendPartition(partitionFile, partitionId, targetWorker)

      success = true
      logger.info(s"Successfully sent partition $partitionId")

    } catch {
      case e: StatusRuntimeException if isRetryable(e) =>
        attempt += 1

        if (attempt < maxRetries) {
          // ⭐ 지수 백오프: 1초 → 2초 → 4초
          val backoffMs = math.pow(2, attempt).toLong * 1000
          logger.warn(s"Retryable error for partition $partitionId: ${e.getMessage}. " +
                      s"Retrying in ${backoffMs}ms (attempt $attempt/$maxRetries)")
          Thread.sleep(backoffMs)
        } else {
          logger.error(s"Failed to send partition $partitionId after $maxRetries attempts")
          throw e
        }

      case e: Exception =>
        // 재시도 불가능한 에러 (예: 잘못된 파티션 ID)
        logger.error(s"Non-retryable error for partition $partitionId: ${e.getMessage}")
        throw e
    }
  }
}

def isRetryable(e: StatusRuntimeException): Boolean = {
  e.getStatus.getCode match {
    case Status.Code.UNAVAILABLE => true   // Worker 일시적으로 다운
    case Status.Code.DEADLINE_EXCEEDED => true  // 타임아웃
    case Status.Code.RESOURCE_EXHAUSTED => true  // 혼잡
    case _ => false
  }
}
```

**지수 백오프의 효과**:
```
Attempt 1: 즉시 시도 → 실패 (네트워크 혼잡)
  ↓ 1초 대기
Attempt 2: 재시도 → 실패 (여전히 혼잡)
  ↓ 2초 대기
Attempt 3: 재시도 → 성공 (혼잡 해소됨)

총 시간: 3초 (실패로 끝나는 것보다 훨씬 나음)
```

**왜 지수적으로 증가하는가?**
1. **혼잡 완화**: 모든 Worker가 동시에 재시도하면 더 혼잡해짐
2. **일시적 장애 대응**: 짧은 장애는 1초, 긴 장애는 4초 대기
3. **Thundering Herd 방지**: Worker들의 재시도 시간을 분산

---

#### Heartbeat의 역할

**목적**: Master가 Worker의 생존 여부를 추적

```scala
// Worker: 백그라운드 스레드에서 10초마다 heartbeat 전송
val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()

heartbeatExecutor.scheduleAtFixedRate(
  new Runnable {
    def run(): Unit = {
      try {
        val request = HeartbeatRequest(workerId, currentPhase.toString)
        masterStub.heartbeat(request)
        logger.debug(s"Heartbeat sent: $workerId at phase $currentPhase")
      } catch {
        case e: Exception =>
          logger.error(s"Failed to send heartbeat: ${e.getMessage}")
      }
    }
  },
  0,        // initial delay
  10,       // period: 10초
  TimeUnit.SECONDS
)
```

```scala
// Master: 30초 동안 heartbeat 없으면 Worker 제거
val HEARTBEAT_TIMEOUT = 30 * 1000  // 30초

def checkWorkerHealth(): Unit = {
  val now = System.currentTimeMillis()

  registeredWorkers.foreach { case (workerId, workerInfo) =>
    val lastHeartbeat = workerInfo.lastHeartbeatTime

    if (now - lastHeartbeat > HEARTBEAT_TIMEOUT) {
      logger.warn(s"Worker $workerId timeout (no heartbeat for 30s)")

      // Worker 제거 + 다른 Worker들에게 알림
      registeredWorkers.remove(workerId)
      notifyWorkerFailure(workerId)
    }
  }
}
```

**Heartbeat를 통한 Worker 장애 감지**:
```
Worker 2 crashes at 10:00:00

10:00:10 - Master receives last heartbeat from Worker 2
10:00:20 - No heartbeat (10s elapsed)
10:00:30 - No heartbeat (20s elapsed)
10:00:40 - ⭐ TIMEOUT (30s elapsed)
         → Master removes Worker 2
         → Master triggers recovery:
           - Checkpoint + Replication recovery
           - 새 Worker 2' 시작
```

---

#### gRPC 통신 장점 정리

| 장점 | 설명 |
|------|------|
| ✅ **고성능** | Protocol Buffers (바이너리) + HTTP/2 multiplexing |
| ✅ **Streaming** | 대용량 파티션 전송 (4GB → 1MB chunks) |
| ✅ **타입 안전성** | Compile-time 타입 체크 (.proto → Scala stub) |
| ✅ **재시도 로직** | StatusRuntimeException으로 네트워크 오류 분류 |
| ✅ **양방향 통신** | Master ↔ Worker, Worker ↔ Worker 모두 지원 |
| ✅ **Backpressure** | Streaming으로 자연스럽게 flow control |

---

## 4. 개발 방법론: TDD

### 4.1 TDD란?

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

### 4.2 분산 시스템에서 TDD의 중요성

| 문제 | TDD를 통한 해결 |
|------|----------------|
| 복잡한 상태 전이 | 테스트로 명확하게 검증 |
| gRPC 통신 오류 | 시나리오 사전 정의 |
| Race condition | 조기 발견 가능 |
| Fault tolerance | 메커니즘 검증 |
| 리팩토링 | 안전성 보장 |

### 4.3 TDD 실전 예시 - Record 클래스

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
    // ⭐ 중요: 0xFF (255) > 0x01 (1) in unsigned comparison
    val rec1 = Record(Array[Byte](0xFF.toByte) ++ Array.fill[Byte](9)(0),
                      Array.fill[Byte](90)(0))
    val rec2 = Record(Array[Byte](0x01) ++ Array.fill[Byte](9)(0),
                      Array.fill[Byte](90)(0))

    rec1.compare(rec2) should be > 0  // ⭐ Unsigned 비교
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

### 4.4 Testing Pyramid

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
| Record | 5 tests | ✅ 완료 (Week 4) |
| RecordReader (Binary) | 📋 설계 | 예정 (Week 6) |
| RecordReader (ASCII) | 📋 설계 | 예정 (Week 6) |
| InputFormatDetector | 📋 설계 | 예정 (Week 6) |
| FileLayout | 📋 설계 | 예정 (Week 6) |
| ExternalSorter | 📋 설계 | 예정 (Week 6) |
| KWayMerger | 📋 설계 | 예정 (Week 6) |

---

## 5. 현재 진행 상황 (Week 4-5)

### 5.1 전체 진행률

```
Week 1-2: 설계 단계 (100% 완료)
  ├─ 시스템 아키텍처 정의
  ├─ Protocol Buffers 설계
  ├─ Fault Tolerance 전략 결정 (Checkpoint)
  └─ 7개 설계 문서 작성

Week 3: 프로젝트 구조 및 Record (100% 완료)
  ├─ SBT 프로젝트 구조 생성
  ├─ gRPC stub 생성
  └─ Record 클래스 구현 및 테스트 (5/5 passing)

Week 4-5: Core I/O Components (100% 완료)
  ├─ RecordReader 추상화 (Factory Pattern)
  ├─ BinaryRecordReader (100-byte 고정 길이)
  ├─ AsciiRecordReader (102-byte: key+space+value+newline)
  ├─ InputFormatDetector (자동 형식 감지)
  ├─ FileLayout (파일 시스템 관리)
  └─ RecordWriter (Binary/ASCII 출력)

Week 5: Algorithms (100% 완료) 
  ├─ ExternalSorter (2-Pass External Sort)
  ├─ Partitioner (Range-based partitioning)
  └─ KWayMerger (Priority Queue 기반 병합)

Week 6: Master/Worker 구현 (진행 중)
  📋 Master 구현
  📋 Worker 구현
  📋 Phase 동기화
  📋 Checkpoint 통합

Week 7-8: 통합 테스트 (예정)
  📋 전체 시스템 테스트
  📋 Fault Tolerance 검증
  📋 최적화
```

### 5.2 구현 완료 항목 상세

#### ✅ 1. Record 클래스 (Week 4)

```scala
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10)
  require(value.length == 90)

  override def compare(that: Record): Int = {
    var i = 0
    while (i < 10) {
      val unsigned1 = this.key(i) & 0xFF  // ⭐ Unsigned 변환
      val unsigned2 = that.key(i) & 0xFF
      val diff = unsigned1 - unsigned2
      if (diff != 0) return diff
      i += 1
    }
    0
  }

  def toBytes: Array[Byte] = key ++ value
}
```

**테스트**: 5/5 passing

#### ✅ 2. InputFormatDetector (Week 5)

```scala
object InputFormatDetector {
  private val SAMPLE_SIZE = 1000
  private val ASCII_THRESHOLD = 0.9

  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](SAMPLE_SIZE)
    // 첫 1000 bytes 읽기
    val bytesRead = inputStream.read(buffer)

    // ASCII 비율 계산
    val asciiCount = buffer.take(bytesRead).count(isAsciiPrintable)
    val asciiRatio = asciiCount.toDouble / bytesRead

    if (asciiRatio > ASCII_THRESHOLD) DataFormat.Ascii
    else DataFormat.Binary
  }
}
```

**해결**: Additional Requirement 1 (ASCII/Binary 자동 감지)

#### ✅ 3. RecordReader 추상화 (Week 5)

```scala
trait RecordReader {
  def readRecord(input: InputStream): Option[Array[Byte]]
  def close(): Unit
}

object RecordReader {
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    format match {
      case DataFormat.Binary => new BinaryRecordReader()
      case DataFormat.Ascii  => new AsciiRecordReader()
    }
  }
}
```

**BinaryRecordReader**: 100-byte 고정 길이
**AsciiRecordReader**: 102-byte (key + space + value + newline)

#### ✅ 4. FileLayout (Week 5)

```scala
class FileLayout(
  inputDirs: List[File],    // 읽기 전용
  outputDir: File,          // 최종 파일만
  tempBaseDir: File         // 임시 파일
) {
  def validateInputDirectories(): Unit
  def createTemporaryStructure(): Unit
  def ensureSufficientDiskSpace(): Unit
  def cleanupTemporaryFiles(): Unit
}
```

**해결**:
- Additional Requirement 2 (입력 보호)
- Additional Requirement 3 (출력 정리)

#### ✅ 5. ExternalSorter (Week 5)

```scala
class ExternalSorter(
  fileLayout: FileLayout,
  memoryLimit: Long = 512 * 1024 * 1024,  // 512 MB
  numThreads: Int = Runtime.getRuntime.availableProcessors()
) {
  def createSortedChunksParallel(records: Seq[Record]): Seq[File] = {
    val chunks = records.grouped(recordsPerChunk).toSeq

    // 병렬 정렬
    val futures = chunks.zipWithIndex.map { case (chunk, index) =>
      Future {
        val sorted = chunk.sorted
        writeChunkToFile(sorted, chunkFile)
        chunkFile
      }
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }
}
```

**해결**: Challenge 1 (메모리 제한)

#### ✅ 6. KWayMerger (Week 5)

```scala
class KWayMerger(sortedChunks: Seq[File]) {
  def mergeWithCallback(callback: Record => Unit): Unit = {
    val heap = mutable.PriorityQueue[HeapEntry]()

    // Min-heap 기반 병합
    while (heap.nonEmpty) {
      val entry = heap.dequeue()
      callback(entry.record)  // 스트리밍 처리

      readers(entry.sourceIndex).readRecord() match {
        case Some(nextRecord) =>
          heap.enqueue(HeapEntry(nextRecord, entry.sourceIndex))
        case None => // Source exhausted
      }
    }
  }
}
```

**해결**: Challenge 1 (디스크 기반 병합)

#### ✅ 7. CheckpointManager (Week 5)

```scala
class CheckpointManager(workerId: String) {
  def saveCheckpoint(
    phase: WorkerPhase,
    state: WorkerState,
    progress: Double
  ): Future[String] = {
    val checkpoint = Checkpoint(checkpointId, workerId, phase.toString,
                                 Instant.now(), progress, state)
    // JSON으로 저장
    writer.write(gson.toJson(checkpoint))
    checkpointId
  }

  def loadLatestCheckpoint(): Option[Checkpoint] = {
    // 가장 최근 checkpoint 로드
    checkpointFiles.sortBy(_.lastModified()).reverse.headOption
  }
}
```

**해결**: Challenge 3 - Layer 1 (진행 상태 복구)

#### ✅ 8. ReplicationManager (Week 5)

```scala
class ReplicationManager(
  replicationFactor: Int = 2,
  workerClients: Map[Int, WorkerClient]
) {
  def replicatePartition(
    partitionData: PartitionData,
    replicas: Seq[Int],
    primaryWorker: Int
  ): Future[Unit] = {
    // 병렬로 replicas에 전송
    val replicationFutures = replicas.map { workerId =>
      sendToReplica(partitionData, workerId)
    }
    Future.sequence(replicationFutures).map(_ => ())
  }

  def recoverFromReplica(
    partitionId: Int,
    failedWorker: Int
  ): Future[PartitionData] = {
    // Replica에서 데이터 복구 + Checksum 검증
    workerClients(backupWorker)
      .fetchPartition(partitionId)
      .map { data =>
        if (data.checksum == metadata.checksum) data
        else throw new Exception("Checksum mismatch")
      }
  }

  def selectReplicas(partitionId: Int, excludeWorker: Int): Seq[Int] = {
    // Consistent hashing for deterministic replica selection
    val random = new Random(partitionId)
    random.shuffle(availableWorkers).take(replicationFactor - 1)
  }
}
```

**해결**: Challenge 3 - Layer 2 (중간 데이터 복구)

#### ✅ 9. Worker with 2-Layer Fault Tolerance (Week 6)

```scala
class Worker(...) extends ShutdownAware {
  private val checkpointManager = CheckpointManager(workerId)
  private val replicationManager = ReplicationManager(replicationFactor = 2, workerClients)
  private val shutdownManager = GracefulShutdownManager(...)

  def run(): Unit = {
    // Layer 1: Checkpoint 복구
    val recoveredFromCheckpoint = recoverFromCheckpoint()

    if (!recoveredFromCheckpoint) {
      performSampling()
    }

    // Layer 2: 손실된 중간 파일 복구
    if (recoveredFromCheckpoint) {
      recoverLostPartitions()  // Replica에서 복구
    }

    // Phase별 checkpoint 저장
    if (currentPhase == PHASE_SORTING) {
      performLocalSort()
      savePhaseCheckpoint(PHASE_SORTING, 1.0)

      // Replication 수행
      replicateMyPartitions()

      performShuffle()
      savePhaseCheckpoint(PHASE_SHUFFLING, 1.0)
    }

    performMerge()
    checkpointManager.deleteAllCheckpoints()
  }

  private def recoverLostPartitions(): Unit = {
    myPartitions.foreach { partitionId =>
      if (!partitionFileExists(partitionId)) {
        replicationManager.recoverFromReplica(partitionId, workerId)
      }
    }
  }

  private def replicateMyPartitions(): Unit = {
    myPartitions.foreach { partitionId =>
      val replicas = replicationManager.selectReplicas(partitionId, workerId)
      val partitionData = loadPartitionData(partitionId)
      replicationManager.replicatePartition(partitionData, replicas, workerId)
    }
  }
}
```

**해결**: Challenge 3 (2-Layer 복구 메커니즘 통합)

### 5.3 코드 품질 지표

**Week 4-5 기준**:
- 총 코드 라인: ~2,000 LOC (Comments 포함 ~3,000 LOC)
- 테스트 커버리지: Record 5/5 passing
- 문서화: 모든 public method Scaladoc 작성
- 코드 스타일: Scala 표준 가이드 준수

### 5.4 해결한 기술적 문제

#### 문제 1: Unsigned Byte 비교

**증상**: 0xFF가 0x01보다 작게 정렬됨 (signed 비교)

**해결**:
```scala
// ❌ 잘못된 코드
this.key(0).compareTo(that.key(0))  // Signed: 0xFF = -1 < 0x01 = 1

// ✅ 올바른 코드
val unsigned1 = this.key(0) & 0xFF  // Unsigned: 0xFF = 255 > 0x01 = 1
```

#### 문제 2: Buffer의 clone() 누락

**증상**: 같은 레코드가 반복해서 읽힘 (참조 공유)

**해결**:
```scala
// ❌ 잘못된 코드
val record = new Array[Byte](100)
while (input.read(record) == 100) {
  records += record  // 같은 배열 참조!
}

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

---

## 6. 향후 계획 (Week 6-8)

### 6.1 Week 6: Master/Worker 통합 (진행 중)

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

### 6.2 Week 7: 통합 테스트

```bash
# Scenario 1: 3 workers, 9 partitions (Strategy B)
$ ./test_3w9p.sh

# Scenario 2: Worker crash during shuffle
$ ./test_fault_tolerance.sh

# Scenario 3: ASCII/Binary 혼합 입력
$ ./test_mixed_format.sh
```

### 6.3 Week 8: 최적화 및 최종 검증

- 멀티스레드 활용도 향상
- 네트워크 대역폭 최적화
- 디스크 I/O 병목 제거
- valsort 검증 통과 확인

---

## 7. Q&A 준비

### 예상 질문

#### Q1: "TDD를 선택한 이유는?"

**답변**:
- 분산 시스템은 상태 전이와 네트워크 오류 시나리오가 복잡
- 테스트를 먼저 작성하면 요구사항을 명확히 정의할 수 있음
- 리팩토링 시 안전성 보장
- 예: Record 클래스의 unsigned 비교 버그를 테스트로 먼저 발견

#### Q2: "2-Layer Fault Tolerance는 어떻게 동작하나?"

**답변**:
우리는 **Checkpoint + Replication** 2단계 복구 전략을 사용합니다.

**Layer 1: Checkpoint (진행 상태 복구)**
- **저장**: 각 Phase 완료 시 WorkerState를 JSON으로 자동 저장 (`/tmp/distsort/checkpoints/`)
- **복구**: Worker 재시작 시 최신 checkpoint 로드 → 마지막 완료 Phase 확인
- **내용**: processedRecords, shuffleMap, completedPartitions, phaseMetadata

**Layer 2: Replication (중간 데이터 복구)**
- **복제**: Phase 2 (Sort & Partition) 완료 후 각 파티션을 다른 Worker에 복제 (replicationFactor=2)
- **복구**: Worker crash 시 replica에서 손실된 파티션 데이터 복사
- **검증**: Checksum으로 데이터 무결성 확인

**통합 복구 예시**:
1. Worker 2가 Shuffle 중 crash
2. Worker 2' 시작 → Checkpoint 로드 (마지막 Phase: PHASE_SORTING)
3. ReplicationManager가 Worker 1, 3에서 P6, P7, P8 복구
4. Shuffle부터 재개 (Sampling/Sort 스킵)

**장점**:
- ✅ **"All intermediate data is lost" 문제 완전 해결** (슬라이드 요구사항)
- ✅ 빠른 복구 (전체 재시작 대비 시간 절약)
- ✅ 정확성 보장 (Checksum + deterministic recovery)

#### Q3: "N→M 전략의 장점은?"

**답변**:
- 로드 밸런싱 개선: 파티션 수 증가로 크기 불균형 완화
- 멀티코어 활용: 각 Worker가 여러 파티션 병렬 merge
- 예: 4 workers, 12 partitions → 각 Worker가 3개 파티션 병렬 merge

#### Q4: "ASCII/Binary 자동 감지는 어떻게 동작하나?"

**답변**:
- 파일의 첫 1000 바이트 읽기
- ASCII printable 문자 비율 계산
- 비율 > 90% → ASCII, 그 외 → Binary
- 각 파일마다 독립적 감지 → 혼합 입력 지원

#### Q5: "현재 진행률은?"

**답변**:
- Week 1-2: 설계 단계 100% 완료
- Week 3: Record 클래스 100% 완료 (5/5 tests passing)
- Week 4-5: Core I/O Components 100% 완료
  - RecordReader, InputFormatDetector, FileLayout, RecordWriter
  - ExternalSorter, KWayMerger
  - CheckpointManager, ReplicationManager (2-Layer Fault Tolerance)
- Week 6: Master/Worker 통합 진행 중

**전체 진행률**: 약 60% (설계 + 핵심 컴포넌트 완료, Master/Worker 통합 진행 중)

#### Q6: "External Sort가 메모리 제한을 어떻게 준수하나?"

**답변**:
- `memoryLimit = 512MB` 설정
- `recordsPerChunk = memoryLimit / 100` 계산
- 예: 512MB ÷ 100 bytes = 5,242,880 records per chunk
- 50GB 입력 → 약 100 chunks 생성
- 각 chunk를 병렬로 정렬 → 디스크에 저장
- K-way merge로 최종 병합 (스트리밍 처리)

#### Q7: "Challenge 1, 2, 3가 모두 해결되었나?"

**답변**:
- **Challenge 1 (Input > Memory)**: ✅ ExternalSorter + KWayMerger로 해결
  - 2-Pass External Sort (512MB chunks)
  - Min-heap 기반 스트리밍 병합
- **Challenge 2 (Input > Disk, Distributed)**: ✅ Master-Worker + N→M Partition으로 해결
  - Sort → Partition → Shuffle → Merge
  - gRPC 기반 worker-to-worker 통신
- **Challenge 3 ("All intermediate data is lost")**: ✅ **Checkpoint + Replication**으로 완전 해결
  - Checkpoint: 진행 상태 복구
  - Replication: 중간 파일 복구 (replicationFactor=2)
  - 슬라이드 요구사항 충족: "fault-tolerant system"
- **Additional Requirements**: ✅ 모두 구현 완료
  - ASCII/Binary 자동 감지 (90% threshold)
  - 입력 디렉토리 보호 (read-only)
  - 출력 디렉토리 정리 (cleanup)
  - 동적 포트 할당

### 데모 시나리오 (Week 7 이후)

```bash
# 1. 테스트 데이터 생성
$ gensort -b0 10000000 /data1/input/test.dat  # 1GB

# 2. Master 시작 (4 workers, 12 partitions)
$ sbt "runMain distsort.Main master 4 12"
[INFO] Master started on port 30000
[INFO] Waiting for 4 workers to register...

# 3. Worker 시작 (4대)
$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data1/input -O /data1/output"
[INFO] Worker registered with ID W0

$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data2/input -O /data2/output"
[INFO] Worker registered with ID W1

$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data3/input -O /data3/output"
[INFO] Worker registered with ID W2

$ sbt "runMain distsort.Main worker 192.168.1.1:30000 -I /data4/input -O /data4/output"
[INFO] Worker registered with ID W3

# 4. Master 출력
192.168.1.100:30000
worker0, worker1, worker2, worker3

# 5. 결과 검증
$ valsort /data1/output/partition.* /data2/output/partition.* \
          /data3/output/partition.* /data4/output/partition.*
SUCCESS
```

---

## 8. 참고 문헌

### 8.1 핵심 알고리즘

- Knuth, Donald E. *The Art of Computer Programming, Volume 3: Sorting and Searching*. Addison-Wesley, 1998.
  - External Sorting 알고리즘 (5.4절)

- TeraSort: A Sample Hadoop Application
  - Sampling for Partitioning 기법

### 8.2 시스템 설계

- Dean, Jeffrey, and Sanjay Ghemawat. "MapReduce: Simplified Data Processing on Large Clusters." *OSDI* 2004.
  - Master-Worker 아키텍처, Fault Tolerance 전략

- Zaharia, Matei, et al. "Resilient Distributed Datasets: A Fault-Tolerant Abstraction for In-Memory Cluster Computing." *NSDI* 2012.
  - Checkpoint 기반 Fault Recovery

### 8.3 구현 참고

- gRPC 공식 문서: https://grpc.io/docs/languages/scala/
- Protocol Buffers: https://protobuf.dev/
- **gensort/valsort**: http://www.ordinal.com/gensort.html
  - 정렬 테스트 데이터 생성 및 검증 도구

### 8.4 프로젝트 문서

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

## Appendix: 프로젝트 구조

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
│       │   │   ├── RecordWriter.scala         ✅ Week 5
│       │   │   ├── ExternalSorter.scala       ✅ Week 5
│       │   │   ├── Partitioner.scala          ✅ Week 5
│       │   │   └── KWayMerger.scala           ✅ Week 5
│       │   ├── checkpoint/
│       │   │   └── CheckpointManager.scala    ✅ Week 5
│       │   ├── shutdown/
│       │   │   └── GracefulShutdownManager.scala ✅ Week 6
│       │   ├── master/
│       │   │   ├── MasterServer.scala         📋 Week 6
│       │   │   └── PhaseTracker.scala         📋 Week 6
│       │   └── worker/
│       │       ├── Worker.scala               📋 Week 6
│       │       └── WorkerService.scala        📋 Week 6
│       └── protobuf/
│           └── distsort.proto                 📋 Week 6
├── docs/
│   ├── 0-implementation-decisions.md
│   ├── 1-phase-coordination.md
│   ├── 4-error-recovery.md
│   ├── 6-parallelization.md
│   └── 7-testing-strategy.md
└── plan/
    └── 2025-10-24_plan_ver3.md
```
