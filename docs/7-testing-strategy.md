# Testing Strategy & TDD Implementation Guide

**작성일**: 2025-10-24
**목적**: TDD(Test-Driven Development) 기반 구현을 위한 테스트 전략 및 가이드

**참조 문서**:
- `2025-10-24_plan_ver3.md` - 전체 시스템 설계
- `0-implementation-decisions.md` - Additional Requirements (Section 8)
- `3-grpc-sequences.md` - gRPC 프로토콜

---

## 목차

1. [TDD 개발 철학](#1-tdd-개발-철학)
2. [테스트 계층 구조](#2-테스트-계층-구조)
3. [개발 순서 (TDD Workflow)](#3-개발-순서-tdd-workflow)
4. [단위 테스트 (Unit Tests)](#4-단위-테스트-unit-tests)
5. [통합 테스트 (Integration Tests)](#5-통합-테스트-integration-tests)
6. [End-to-End 테스트](#6-end-to-end-테스트)
7. [테스트 환경 설정](#7-테스트-환경-설정)
8. [Mock/Stub 전략](#8-mockstub-전략)
9. [성능 테스트](#9-성능-테스트)
10. [Fault Tolerance 테스트](#10-fault-tolerance-테스트)

---

## 1. TDD 개발 철학

### 1.1 Red-Green-Refactor Cycle

```
┌─────────────────────────────────────────────────┐
│ TDD Cycle                                       │
├─────────────────────────────────────────────────┤
│ 1. RED   : 실패하는 테스트 작성                │
│            (먼저 원하는 동작을 정의)            │
│                                                 │
│ 2. GREEN : 최소한의 코드로 테스트 통과         │
│            (일단 동작하게 만들기)               │
│                                                 │
│ 3. REFACTOR: 코드 개선 (테스트는 계속 통과)    │
│            (깔끔하게 만들기)                    │
└─────────────────────────────────────────────────┘
```

### 1.2 TDD의 이점

**분산 시스템에서 TDD가 중요한 이유**:
- ✅ 복잡한 상태 전이를 명확하게 검증
- ✅ gRPC 통신 오류 시나리오 사전 정의
- ✅ Race condition 조기 발견
- ✅ Fault tolerance 메커니즘 검증
- ✅ 리팩토링 시 안전성 보장

### 1.3 테스트 작성 원칙

**FIRST 원칙**:
- **F**ast: 빠른 실행 (단위 테스트 < 100ms)
- **I**solated: 독립적 실행 (순서 무관)
- **R**epeatable: 반복 가능 (항상 같은 결과)
- **S**elf-validating: 자동 검증 (pass/fail)
- **T**imely: 적시 작성 (구현 전에 테스트)

**Given-When-Then 패턴**:
```scala
test("RecordReader should read 100-byte records correctly") {
  // Given: 테스트 데이터 준비
  val inputFile = createTestFile(withRecords = 10)
  val reader = new RecordReader(inputFile)

  // When: 동작 수행
  val record = reader.readRecord()

  // Then: 결과 검증
  assert(record.isDefined)
  assert(record.get.key.length == 10)
  assert(record.get.value.length == 90)
}
```

---

## 2. 테스트 계층 구조

### 2.1 Testing Pyramid

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

### 2.2 테스트 범위

| 테스트 종류 | 목적 | 실행 빈도 | 예상 시간 |
|------------|------|----------|----------|
| **Unit Tests** | 개별 함수/클래스 검증 | 매 커밋 | < 5초 |
| **Integration Tests** | 컴포넌트 간 통신 검증 | 매 PR | < 30초 |
| **E2E Tests** | 전체 시스템 검증 | 매 배포 | < 5분 |
| **Performance Tests** | 성능 기준 확인 | 주간 | < 30분 |
| **Fault Tolerance Tests** | 장애 복구 검증 | 매 배포 | < 10분 |

---

## 3. 개발 순서 (TDD Workflow)

### 3.1 Phase 1: Core Data Structures

#### Step 1.1: Record 클래스
```scala
// 1. RED: 테스트 작성
class RecordSpec extends AnyFlatSpec with Matchers {
  "Record" should "store 10-byte key and 90-byte value" in {
    val key = Array.fill[Byte](10)(1)
    val value = Array.fill[Byte](90)(2)

    val record = Record(key, value)

    record.key.length shouldBe 10
    record.value.length shouldBe 90
  }

  it should "compare records by key only" in {
    val rec1 = Record(Array[Byte](1,2,3,4,5,6,7,8,9,10), Array.fill[Byte](90)(0))
    val rec2 = Record(Array[Byte](1,2,3,4,5,6,7,8,9,11), Array.fill[Byte](90)(99))

    rec1.compareTo(rec2) should be < 0
  }

  it should "serialize to 100 bytes" in {
    val record = Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2))
    val bytes = record.toBytes

    bytes.length shouldBe 100
  }
}

// 2. GREEN: 최소 구현
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
  require(value.length == 90, s"Value must be 90 bytes, got ${value.length}")

  override def compare(that: Record): Int = {
    java.util.Arrays.compareUnsigned(this.key, that.key)
  }

  def toBytes: Array[Byte] = key ++ value
}

// 3. REFACTOR: (필요 시 개선)
```

#### Step 1.2: RecordReader
```scala
// 1. RED: 테스트 작성
class RecordReaderSpec extends AnyFlatSpec with Matchers {
  "BinaryRecordReader" should "read records from file" in {
    // Given
    val tempFile = createTempFile()
    writeRecords(tempFile, numRecords = 5)
    val reader = new BinaryRecordReader(tempFile)

    // When
    val records = (1 to 5).flatMap(_ => reader.readRecord())

    // Then
    records should have length 5
    records.foreach { record =>
      record.key.length shouldBe 10
      record.value.length shouldBe 90
    }
  }

  it should "return None at EOF" in {
    val tempFile = createTempFile()
    writeRecords(tempFile, numRecords = 1)
    val reader = new BinaryRecordReader(tempFile)

    reader.readRecord() shouldBe defined
    reader.readRecord() shouldBe None
  }

  it should "handle incomplete records gracefully" in {
    val tempFile = createTempFile()
    writeBytes(tempFile, Array.fill[Byte](50)(1)) // Only 50 bytes
    val reader = new BinaryRecordReader(tempFile)

    reader.readRecord() shouldBe None
  }
}

// 2. GREEN: 구현
// 3. REFACTOR
```

### 3.2 Phase 2: Sorting & Partitioning

#### Step 2.1: External Sort
```scala
class ExternalSortSpec extends AnyFlatSpec with Matchers {
  "ExternalSorter" should "sort records that fit in memory" in {
    // Given: Unsorted records
    val unsorted = generateRandomRecords(1000)
    val tempDir = Files.createTempDirectory("test-sort")
    val sorter = new ExternalSorter(chunkSize = 1024 * 1024) // 1MB chunks

    // When: Sort
    val sortedChunks = sorter.sortAndPartition(unsorted, tempDir)

    // Then: Verify sorted
    val allRecords = sortedChunks.flatMap(readAllRecords)
    allRecords should have length 1000
    allRecords shouldBe sorted
  }

  it should "handle data larger than memory" in {
    // Given: 100MB of data with 10MB memory limit
    val largeInput = generateRandomRecords(1000000) // ~100MB
    val sorter = new ExternalSorter(chunkSize = 10 * 1024 * 1024) // 10MB

    // When
    val sortedChunks = sorter.sortAndPartition(largeInput, tempDir)

    // Then
    sortedChunks.size should be > 5 // Should create multiple chunks
    sortedChunks.foreach { chunk =>
      val records = readAllRecords(chunk)
      records shouldBe sorted
    }
  }
}
```

#### Step 2.2: K-way Merge
```scala
class KWayMergerSpec extends AnyFlatSpec with Matchers {
  "KWayMerger" should "merge sorted chunks correctly" in {
    // Given: 3 sorted chunks
    val chunk1 = createSortedChunk(Array[Byte](1), Array[Byte](5), Array[Byte](9))
    val chunk2 = createSortedChunk(Array[Byte](2), Array[Byte](6))
    val chunk3 = createSortedChunk(Array[Byte](3), Array[Byte](4), Array[Byte](7))

    // When
    val merger = new KWayMerger(Seq(chunk1, chunk2, chunk3))
    val merged = collectAllRecords(merger)

    // Then
    merged.map(_.key(0)) shouldBe Array[Byte](1,2,3,4,5,6,7,9)
    merged shouldBe sorted
  }

  it should "handle empty chunks" in {
    val chunk1 = createSortedChunk(Array[Byte](1), Array[Byte](2))
    val chunk2 = createEmptyChunk()

    val merger = new KWayMerger(Seq(chunk1, chunk2))
    val merged = collectAllRecords(merger)

    merged should have length 2
  }
}
```

#### Step 2.3: Partition Logic
```scala
class PartitionerSpec extends AnyFlatSpec with Matchers {
  "RangePartitioner" should "assign partitions based on key ranges" in {
    // Given: Boundaries for 3 partitions
    val boundaries = Array(
      Array[Byte](0,0,0,0,0,0,0,0,0,50),
      Array[Byte](0,0,0,0,0,0,0,0,0,100),
      Array[Byte](0,0,0,0,0,0,0,0,0,-1)
    )
    val partitioner = new RangePartitioner(boundaries)

    // When/Then
    partitioner.getPartition(Record(Array[Byte](0,0,0,0,0,0,0,0,0,10), null)) shouldBe 0
    partitioner.getPartition(Record(Array[Byte](0,0,0,0,0,0,0,0,0,75), null)) shouldBe 1
    partitioner.getPartition(Record(Array[Byte](0,0,0,0,0,0,0,0,0,-10), null)) shouldBe 2
  }

  "ShuffleMap" should "assign consecutive partitions to workers" in {
    // Given: 9 partitions, 3 workers
    val shuffleMap = ShuffleMap.create(numPartitions = 9, numWorkers = 3)

    // Then: Range-based assignment
    shuffleMap.getWorker(0) shouldBe 0
    shuffleMap.getWorker(1) shouldBe 0
    shuffleMap.getWorker(2) shouldBe 0
    shuffleMap.getWorker(3) shouldBe 1
    shuffleMap.getWorker(4) shouldBe 1
    shuffleMap.getWorker(5) shouldBe 1
    shuffleMap.getWorker(6) shouldBe 2
    shuffleMap.getWorker(7) shouldBe 2
    shuffleMap.getWorker(8) shouldBe 2
  }
}
```

### 3.3 Phase 3: gRPC Communication

#### Step 3.1: Service Implementation
```scala
class MasterServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "MasterService.Register" should "assign worker IDs sequentially" in {
    // Given
    val service = new MasterServiceImpl()

    // When
    val response1 = await(service.register(RegisterRequest(
      workerInfo = Some(WorkerInfo(workerId = "", address = "192.168.1.10", port = 50001))
    )))
    val response2 = await(service.register(RegisterRequest(
      workerInfo = Some(WorkerInfo(workerId = "", address = "192.168.1.11", port = 50002))
    )))

    // Then
    response1.assignedWorkerId shouldBe "W0"
    response2.assignedWorkerId shouldBe "W1"
  }

  it should "reject registration after sampling started" in {
    // Given
    val service = new MasterServiceImpl()
    service.startSampling() // Phase 2 started

    // When
    val response = await(service.register(RegisterRequest(...)))

    // Then
    response.success shouldBe false
    response.message should include("registration closed")
  }
}
```

#### Step 3.2: Client Communication
```scala
class WorkerGrpcClientSpec extends AnyFlatSpec with Matchers {
  "WorkerGrpcClient.sendPartition" should "transfer partition data" in {
    // Given
    val mockServer = startMockWorkerServer()
    val client = new WorkerGrpcClient("localhost", mockServer.port)
    val partition = createTestPartition(partitionId = 5, numRecords = 100)

    // When
    val result = await(client.sendPartition(
      partitionId = 5,
      data = partition.data,
      targetWorkerId = "W1"
    ))

    // Then
    result.success shouldBe true
    mockServer.receivedPartitions should contain(5)
    mockServer.receivedBytes shouldBe partition.data.length
  }

  it should "retry on transient failures" in {
    val mockServer = startUnstableWorkerServer(failureRate = 0.5)
    val client = new WorkerGrpcClient("localhost", mockServer.port, maxRetries = 3)

    // Should succeed after retries
    val result = await(client.sendPartition(5, data, "W1"))
    result.success shouldBe true
  }
}
```

### 3.4 Phase 4: File Management

#### Step 4.1: Atomic File Operations
```scala
class FileOperationsSpec extends AnyFlatSpec with Matchers {
  "FileOperations.atomicWrite" should "create file atomically" in {
    // Given
    val targetFile = new File("/tmp/test-output.dat")
    val content = Array.fill[Byte](1000)(42)

    // When
    FileOperations.atomicWrite(targetFile, content)

    // Then
    targetFile.exists shouldBe true
    Files.readAllBytes(targetFile.toPath) shouldBe content

    // No .tmp file should remain
    new File(targetFile.getParent, s"${targetFile.getName}.tmp").exists shouldBe false
  }

  it should "cleanup temp file on failure" in {
    val targetFile = new File("/tmp/test-output.dat")
    val tempFile = new File("/tmp/test-output.dat.tmp")

    // Simulate failure
    intercept[IOException] {
      FileOperations.atomicWrite(targetFile, null) // Will fail
    }

    // Temp file should be cleaned up
    tempFile.exists shouldBe false
  }
}
```

#### Step 4.2: Directory Layout
```scala
class FileLayoutSpec extends AnyFlatSpec with Matchers {
  "FileLayout" should "never modify input directories" in {
    // Given
    val inputDir = Files.createTempDirectory("input")
    val originalFiles = Set("file1.dat", "file2.dat", "file3.dat")
    originalFiles.foreach { name =>
      Files.write(inputDir.resolve(name), Array[Byte](1,2,3))
    }
    val beforeChecksum = computeDirectoryChecksum(inputDir)

    // When
    val fileLayout = new FileLayout(
      inputDirs = Seq(inputDir),
      outputDir = Files.createTempDirectory("output"),
      tempBaseDir = Files.createTempDirectory("temp")
    )
    fileLayout.initialize()
    fileLayout.getInputFiles // Read operation

    // Then: Input directory unchanged
    val afterChecksum = computeDirectoryChecksum(inputDir)
    afterChecksum shouldBe beforeChecksum

    // Verify no new files
    val finalFiles = Files.list(inputDir).map(_.getFileName.toString).toSet
    finalFiles shouldBe originalFiles
  }

  it should "cleanup all temporary files" in {
    val fileLayout = new FileLayout(...)
    fileLayout.initialize()

    // Create various temp files
    fileLayout.getSamplingFile.createNewFile()
    fileLayout.getLocalPartitionTempFile(0).createNewFile()
    fileLayout.getOutputPartitionTempFile(5).createNewFile()

    // When
    fileLayout.cleanupTemporaryFiles()

    // Then: All cleaned up
    fileLayout.getTempBasePath.toFile.list() shouldBe empty
  }
}
```

### 3.5 Phase 5: Worker State Machine

#### Step 5.1: State Transitions
```scala
class WorkerStateMachineSpec extends AnyFlatSpec with Matchers {
  "WorkerStateMachine" should "transition through phases correctly" in {
    // Given
    val stateMachine = new WorkerStateMachine()

    // Initial state
    stateMachine.getState shouldBe WorkerState.IDLE

    // When: Registration
    stateMachine.transitionTo(WorkerState.REGISTERED)
    stateMachine.getState shouldBe WorkerState.REGISTERED

    // When: Start sorting
    stateMachine.transitionTo(WorkerState.SORTING)
    stateMachine.getState shouldBe WorkerState.SORTING

    // Invalid transition should fail
    intercept[IllegalStateException] {
      stateMachine.transitionTo(WorkerState.REGISTERED) // Can't go back
    }
  }

  it should "allow re-registration after failure" in {
    val stateMachine = new WorkerStateMachine()
    stateMachine.transitionTo(WorkerState.SORTING)

    // Simulate crash
    stateMachine.reset()

    // Should allow re-registration
    stateMachine.transitionTo(WorkerState.REGISTERED) shouldBe (())
  }
}
```

### 3.6 Phase 6: Master Coordinator

#### Step 6.1: Phase Coordination
```scala
class MasterCoordinatorSpec extends AnyFlatSpec with Matchers {
  "MasterCoordinator" should "wait for all workers before starting next phase" in {
    // Given: 3 workers registered
    val coordinator = new MasterCoordinator(expectedWorkers = 3)
    coordinator.registerWorker(WorkerInfo("W0", "192.168.1.10", 50001))
    coordinator.registerWorker(WorkerInfo("W1", "192.168.1.11", 50002))
    coordinator.registerWorker(WorkerInfo("W2", "192.168.1.12", 50003))

    // When: Start sampling phase
    coordinator.startSamplingPhase()

    // Mark workers as ready one by one
    coordinator.markWorkerReady("W0", Phase.SAMPLING)
    coordinator.markWorkerReady("W1", Phase.SAMPLING)

    // Should not advance yet
    coordinator.allWorkersReady(Phase.SAMPLING) shouldBe false

    // When last worker ready
    coordinator.markWorkerReady("W2", Phase.SAMPLING)

    // Then: Can advance
    coordinator.allWorkersReady(Phase.SAMPLING) shouldBe true
  }

  it should "compute partition boundaries from samples" in {
    val coordinator = new MasterCoordinator(expectedWorkers = 3)

    // Workers send samples
    coordinator.receiveSample("W0", generateSamples(1000))
    coordinator.receiveSample("W1", generateSamples(1000))
    coordinator.receiveSample("W2", generateSamples(1000))

    // When
    val boundaries = coordinator.computePartitionBoundaries(numPartitions = 9)

    // Then
    boundaries should have length 9
    boundaries shouldBe sorted
  }
}
```

---

## 4. 단위 테스트 (Unit Tests)

### 4.1 Core Components

#### RecordReader/Writer
```scala
class RecordReaderWriterSpec extends AnyFlatSpec with Matchers {
  "RecordWriter" should "write records with correct format" in {
    val writer = new BinaryRecordWriter(outputFile)
    val record = Record(
      key = Array.fill[Byte](10)(42),
      value = Array.fill[Byte](90)(99)
    )

    writer.writeRecord(record)
    writer.close()

    // Verify
    val bytes = Files.readAllBytes(outputFile.toPath)
    bytes.length shouldBe 100
    bytes.take(10).forall(_ == 42) shouldBe true
    bytes.drop(10).forall(_ == 99) shouldBe true
  }
}
```

#### InputFormatDetector
```scala
class InputFormatDetectorSpec extends AnyFlatSpec with Matchers {
  "InputFormatDetector" should "detect ASCII format" in {
    val asciiFile = createAsciiFile("AAAAAAAAAA" + "B" * 90 + "\n")

    InputFormatDetector.detectFormat(asciiFile) shouldBe InputFormat.ASCII
  }

  it should "detect Binary format" in {
    val binaryFile = createBinaryFile(Array.fill[Byte](100)(0x00))

    InputFormatDetector.detectFormat(binaryFile) shouldBe InputFormat.BINARY
  }
}
```

### 4.2 Algorithms

#### External Sort
```scala
class ExternalSortAlgorithmSpec extends AnyFlatSpec with Matchers {
  "ExternalSort" should "sort in-memory data correctly" in {
    val unsorted = Array(
      Record(Array[Byte](5), null),
      Record(Array[Byte](2), null),
      Record(Array[Byte](8), null),
      Record(Array[Byte](1), null)
    )

    val sorted = ExternalSort.sortInMemory(unsorted)

    sorted.map(_.key(0)) shouldBe Array[Byte](1,2,5,8)
  }
}
```

#### K-way Merge Heap
```scala
class MergeHeapSpec extends AnyFlatSpec with Matchers {
  "MergeHeap" should "always return minimum element" in {
    val heap = new MergeHeap()

    heap.insert(HeapEntry(Record(Array[Byte](5), null), 0))
    heap.insert(HeapEntry(Record(Array[Byte](2), null), 1))
    heap.insert(HeapEntry(Record(Array[Byte](8), null), 2))

    heap.extractMin().record.key(0) shouldBe 2
    heap.extractMin().record.key(0) shouldBe 5
    heap.extractMin().record.key(0) shouldBe 8
  }
}
```

### 4.3 Utilities

#### ByteArrayComparator
```scala
class ByteArrayComparatorSpec extends AnyFlatSpec with Matchers {
  "ByteArrayComparator" should "compare unsigned bytes correctly" in {
    val a = Array[Byte](0xFF.toByte) // 255 unsigned
    val b = Array[Byte](0x01.toByte) // 1 unsigned

    // 255 > 1 in unsigned comparison
    ByteArrayComparator.compareUnsigned(a, b) should be > 0
  }
}
```

---

## 5. 통합 테스트 (Integration Tests)

### 5.1 Master-Worker Communication

```scala
class MasterWorkerIntegrationSpec extends AnyFlatSpec with Matchers {
  "Master-Worker" should "complete full registration flow" in {
    // Given: Start Master
    val master = new MasterNode(expectedWorkers = 2)
    master.start()

    // When: Workers register
    val worker1 = new WorkerNode(masterAddress = s"localhost:${master.port}")
    val worker2 = new WorkerNode(masterAddress = s"localhost:${master.port}")

    worker1.register()
    worker2.register()

    // Then: Both assigned IDs
    worker1.workerId shouldBe "W0"
    worker2.workerId shouldBe "W1"
    master.registeredWorkers should have size 2
  }

  it should "coordinate sampling phase" in {
    val master = startMaster(expectedWorkers = 2)
    val worker1 = startWorker(masterAddress = master.address)
    val worker2 = startWorker(masterAddress = master.address)

    // When: Master starts sampling
    master.startSamplingPhase()

    // Workers should receive sampling request
    eventually(timeout(5.seconds)) {
      worker1.currentPhase shouldBe Phase.SAMPLING
      worker2.currentPhase shouldBe Phase.SAMPLING
    }

    // Workers complete sampling
    worker1.completeSampling()
    worker2.completeSampling()

    // Master should compute boundaries
    eventually {
      master.boundariesComputed shouldBe true
    }
  }
}
```

### 5.2 Shuffle Phase

```scala
class ShuffleIntegrationSpec extends AnyFlatSpec with Matchers {
  "Shuffle" should "transfer partitions between workers" in {
    // Given: 3 workers, each with local partitions
    val workers = (0 to 2).map { i =>
      val worker = startWorker()
      worker.createLocalPartitions(Seq(i, i+3, i+6)) // 0,3,6 | 1,4,7 | 2,5,8
      worker
    }

    // Create shuffle map: each worker gets consecutive partitions
    val shuffleMap = ShuffleMap.create(numPartitions = 9, numWorkers = 3)
    // Worker 0 → partitions 0,1,2
    // Worker 1 → partitions 3,4,5
    // Worker 2 → partitions 6,7,8

    // When: Execute shuffle
    workers.foreach(_.startShuffle(shuffleMap))

    // Wait for completion
    eventually(timeout(10.seconds)) {
      workers.foreach(_.shuffleComplete shouldBe true)
    }

    // Then: Verify partition distribution
    workers(0).receivedPartitions should contain allOf (0, 1, 2)
    workers(1).receivedPartitions should contain allOf (3, 4, 5)
    workers(2).receivedPartitions should contain allOf (6, 7, 8)
  }
}
```

### 5.3 File System Integration

```scala
class FileSystemIntegrationSpec extends AnyFlatSpec with Matchers {
  "FileLayout" should "work with real filesystem" in {
    // Given: Real directories
    val inputDir = Files.createTempDirectory("integration-input")
    val outputDir = Files.createTempDirectory("integration-output")
    val tempDir = Files.createTempDirectory("integration-temp")

    // Create test input files
    (0 to 4).foreach { i =>
      val file = inputDir.resolve(s"input$i.dat")
      writeRandomRecords(file, numRecords = 1000)
    }

    // When: Worker processes files
    val worker = new WorkerNode(
      inputDirs = Seq(inputDir),
      outputDir = outputDir,
      tempDir = tempDir
    )

    worker.sortAndPartition()

    // Then: Output files created
    val outputFiles = Files.list(outputDir).toList
    outputFiles should not be empty
    outputFiles.foreach { file =>
      file.getFileName.toString should startWith("partition.")
    }

    // Temp directory should be cleaned
    Files.list(tempDir).toList shouldBe empty
  }
}
```

---

## 6. End-to-End 테스트

### 6.1 Simple Scenario (3 Workers, Small Data)

```scala
class EndToEndSimpleSpec extends AnyFlatSpec with Matchers {
  "Distributed Sort" should "sort 10MB across 3 workers" in {
    // Given: 10MB of random data distributed across 3 input directories
    val input1 = createInputDirectory(withSize = 3 * 1024 * 1024) // 3MB
    val input2 = createInputDirectory(withSize = 3 * 1024 * 1024)
    val input3 = createInputDirectory(withSize = 4 * 1024 * 1024)

    val output1 = Files.createTempDirectory("worker1-output")
    val output2 = Files.createTempDirectory("worker2-output")
    val output3 = Files.createTempDirectory("worker3-output")

    // When: Start master
    val master = startMasterProcess(numWorkers = 3)
    val masterAddress = waitForMasterReady(master)

    // Start workers
    val worker1 = startWorkerProcess(masterAddress, input1, output1)
    val worker2 = startWorkerProcess(masterAddress, input2, output2)
    val worker3 = startWorkerProcess(masterAddress, input3, output3)

    // Wait for completion
    waitForCompletion(master, timeout = 60.seconds)

    // Then: Verify output
    val allOutputFiles = collectOutputFiles(output1, output2, output3)

    // Should have partition files
    allOutputFiles should not be empty

    // Verify sorting with valsort
    val validation = runValsort(allOutputFiles)
    validation shouldBe "SUCCESS"

    // Verify completeness (all records present)
    val inputRecordCount = countRecords(input1, input2, input3)
    val outputRecordCount = countRecords(allOutputFiles)
    outputRecordCount shouldBe inputRecordCount
  }
}
```

### 6.2 Fault Tolerance Scenario

```scala
class EndToEndFaultToleranceSpec extends AnyFlatSpec with Matchers {
  "Distributed Sort" should "recover from worker crash" in {
    // Given: 3 workers running
    val master = startMasterProcess(numWorkers = 3)
    val workers = startWorkersProcesses(3, master.address)

    // Wait for sorting phase to start
    waitForPhase(master, Phase.SORTING)

    // When: Kill worker 1 during sorting
    workers(1).kill()
    Thread.sleep(2000)

    // Restart worker 1 with same parameters
    val restartedWorker = startWorkerProcess(
      master.address,
      workers(1).inputDirs,
      workers(1).outputDir
    )

    // Then: System should recover and complete
    waitForCompletion(master, timeout = 120.seconds)

    // Verify output correctness
    val validation = runValsort(collectAllOutputFiles())
    validation shouldBe "SUCCESS"
  }
}
```

### 6.3 Performance Baseline

```scala
class EndToEndPerformanceSpec extends AnyFlatSpec with Matchers {
  "Distributed Sort" should "sort 1GB in under 5 minutes (3 workers)" in {
    // Given: 1GB of data
    val inputs = createInputDirectories(
      numWorkers = 3,
      totalSize = 1024 * 1024 * 1024 // 1GB
    )
    val outputs = createOutputDirectories(3)

    val master = startMasterProcess(3)
    val workers = inputs.zip(outputs).map { case (in, out) =>
      startWorkerProcess(master.address, in, out)
    }

    // When: Run sorting
    val startTime = System.currentTimeMillis()
    waitForCompletion(master, timeout = 5.minutes)
    val duration = System.currentTimeMillis() - startTime

    // Then: Performance baseline
    duration should be < (5 * 60 * 1000L) // 5 minutes

    println(s"Sorted 1GB in ${duration/1000}s")
  }
}
```

---

## 7. 테스트 환경 설정

### 7.1 SBT Test Configuration

```scala
// build.sbt
libraryDependencies ++= Seq(
  // Testing frameworks
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test,

  // gRPC testing
  "io.grpc" % "grpc-testing" % "1.54.0" % Test,

  // Testcontainers (for integration tests)
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.40.17" % Test,

  // Temp file management
  "commons-io" % "commons-io" % "2.11.0" % Test
)

// Test settings
Test / parallelExecution := false // Avoid port conflicts
Test / fork := true // Isolated JVMs
Test / testOptions += Tests.Argument("-oD") // Show test durations
```

### 7.2 Test Utilities

```scala
// test/scala/distsort/TestUtils.scala
package distsort.test

import java.io.File
import java.nio.file.{Files, Path}
import scala.util.Random

object TestUtils {
  def createTempDir(prefix: String = "test"): Path = {
    Files.createTempDirectory(prefix)
  }

  def generateRandomRecords(count: Int): Seq[Record] = {
    (0 until count).map { _ =>
      val key = new Array[Byte](10)
      val value = new Array[Byte](90)
      Random.nextBytes(key)
      Random.nextBytes(value)
      Record(key, value)
    }
  }

  def writeRecordsToFile(file: Path, records: Seq[Record]): Unit = {
    val writer = new BinaryRecordWriter(file.toFile)
    try {
      records.foreach(writer.writeRecord)
    } finally {
      writer.close()
    }
  }

  def readRecordsFromFile(file: Path): Seq[Record] = {
    val reader = new BinaryRecordReader(file.toFile)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
    } finally {
      reader.close()
    }
  }

  def isSorted(records: Seq[Record]): Boolean = {
    records.sliding(2).forall { case Seq(a, b) => a <= b }
  }

  def runValsort(files: Seq[File]): String = {
    import sys.process._
    val command = Seq("valsort") ++ files.map(_.getAbsolutePath)
    command.!!.trim
  }
}
```

### 7.3 Test Fixtures

```scala
// test/scala/distsort/fixtures/TestFixtures.scala
trait TestFixtures {
  def withTempDirectory(testCode: Path => Any): Unit = {
    val tempDir = Files.createTempDirectory("test")
    try {
      testCode(tempDir)
    } finally {
      deleteRecursively(tempDir)
    }
  }

  def withMasterAndWorkers(numWorkers: Int)(
    testCode: (MasterNode, Seq[WorkerNode]) => Any
  ): Unit = {
    val master = new MasterNode(numWorkers)
    master.start()

    val workers = (0 until numWorkers).map { i =>
      val worker = new WorkerNode(s"localhost:${master.port}")
      worker.start()
      worker
    }

    try {
      testCode(master, workers)
    } finally {
      workers.foreach(_.shutdown())
      master.shutdown()
    }
  }
}
```

---

## 8. Mock/Stub 전략

### 8.1 gRPC Service Mocking

```scala
class MockMasterService extends MasterServiceGrpc.MasterService {
  var registeredWorkers: Seq[WorkerInfo] = Seq.empty
  var samplesReceived: Map[String, Seq[Sample]] = Map.empty

  override def register(request: RegisterRequest): Future[RegisterResponse] = {
    val workerId = s"W${registeredWorkers.size}"
    registeredWorkers :+= request.workerInfo.get.copy(workerId = workerId)

    Future.successful(RegisterResponse(
      success = true,
      assignedWorkerId = workerId,
      message = "OK"
    ))
  }

  override def sendSamples(request: SamplesRequest): Future[Ack] = {
    samplesReceived += (request.workerId -> request.samples)
    Future.successful(Ack(success = true))
  }
}
```

### 8.2 File System Mocking

```scala
class MockFileSystem extends FileSystem {
  private val files = mutable.Map[String, Array[Byte]]()

  override def writeFile(path: String, content: Array[Byte]): Unit = {
    files(path) = content
  }

  override def readFile(path: String): Array[Byte] = {
    files.getOrElse(path, throw new FileNotFoundException(path))
  }

  override def listFiles(dir: String): Seq[String] = {
    files.keys.filter(_.startsWith(dir)).toSeq
  }

  def verifyNoModifications(inputDirs: Seq[String]): Boolean = {
    inputDirs.forall { dir =>
      files.keys.filter(_.startsWith(dir)).isEmpty
    }
  }
}
```

### 8.3 Network Failure Simulation

```scala
class UnstableGrpcChannel extends ManagedChannel {
  private val failureRate: Double = 0.3
  private val delegate: ManagedChannel = ...

  override def newCall[ReqT, RespT](
    methodDescriptor: MethodDescriptor[ReqT, RespT],
    callOptions: CallOptions
  ): ClientCall[ReqT, RespT] = {
    if (Random.nextDouble() < failureRate) {
      throw Status.UNAVAILABLE.asRuntimeException()
    }
    delegate.newCall(methodDescriptor, callOptions)
  }
}
```

---

## 9. 성능 테스트

### 9.1 Throughput Measurement

```scala
class ThroughputSpec extends AnyFlatSpec with Matchers {
  "External Sort" should "achieve >50 MB/s throughput" in {
    // Given: 100MB of data
    val inputSize = 100 * 1024 * 1024
    val records = generateRandomRecords(inputSize / 100) // 1M records

    // When: Sort
    val startTime = System.currentTimeMillis()
    val sorter = new ExternalSorter(chunkSize = 10 * 1024 * 1024)
    sorter.sortAndPartition(records, tempDir)
    val duration = System.currentTimeMillis() - startTime

    // Then: Throughput calculation
    val throughputMBps = (inputSize.toDouble / (1024 * 1024)) / (duration / 1000.0)
    println(s"Throughput: $throughputMBps MB/s")

    throughputMBps should be > 50.0 // At least 50 MB/s
  }
}
```

### 9.2 Scalability Test

```scala
class ScalabilitySpec extends AnyFlatSpec with Matchers {
  "Distributed Sort" should "scale with number of workers" in {
    val dataSize = 1024 * 1024 * 1024 // 1GB

    // Test with different worker counts
    val results = Seq(1, 2, 3, 4, 8).map { numWorkers =>
      val duration = runSortingJob(dataSize, numWorkers)
      (numWorkers, duration)
    }

    // Print results
    results.foreach { case (workers, time) =>
      println(s"$workers workers: ${time/1000}s")
    }

    // Verify scaling: 4 workers should be < 2x time of 2 workers
    val time2Workers = results.find(_._1 == 2).get._2
    val time4Workers = results.find(_._1 == 4).get._2

    time4Workers.toDouble / time2Workers should be < 2.0
  }
}
```

### 9.3 Memory Usage Profiling

```scala
class MemoryUsageSpec extends AnyFlatSpec with Matchers {
  "External Sort" should "stay within memory limits" in {
    val memoryLimit = 100 * 1024 * 1024 // 100MB
    val inputSize = 1024 * 1024 * 1024 // 1GB (10x memory)

    // Set memory limit
    val runtime = Runtime.getRuntime
    val initialMemory = runtime.totalMemory()

    // When: Sort large data
    val sorter = new ExternalSorter(chunkSize = memoryLimit / 2)
    sorter.sortAndPartition(generateHugeInput(inputSize), tempDir)

    // Then: Memory usage should not exceed limit significantly
    val peakMemory = runtime.totalMemory()
    val usedMemory = peakMemory - initialMemory

    println(s"Peak memory usage: ${usedMemory / (1024*1024)} MB")
    usedMemory should be < (memoryLimit * 1.5) // Allow 50% overhead
  }
}
```

---

## 10. Fault Tolerance 테스트

### 10.1 Worker Crash & Recovery

```scala
class WorkerCrashSpec extends AnyFlatSpec with Matchers {
  "System" should "recover from worker crash during sorting" in {
    // Given: 3 workers running
    val master = startMaster(3)
    val workers = (0 to 2).map(i => startWorker(master.address))

    // Wait for sorting to start
    waitForPhase(workers(0), Phase.SORTING)
    Thread.sleep(2000) // Let it run for a bit

    // When: Kill worker 1
    workers(1).kill()

    // Restart immediately
    val newWorker = startWorker(
      master.address,
      inputDirs = workers(1).inputDirs,
      outputDir = workers(1).outputDir
    )

    // Then: Should complete successfully
    waitForCompletion(master, timeout = 120.seconds)

    // Verify correctness
    runValsort(collectAllOutputs()) shouldBe "SUCCESS"
  }

  it should "handle crash during shuffle" in {
    val master = startMaster(3)
    val workers = (0 to 2).map(i => startWorker(master.address))

    // Wait for shuffle phase
    waitForPhase(workers(0), Phase.SHUFFLING)
    Thread.sleep(5000)

    // Kill during shuffle
    workers(2).kill()

    // Restart
    val newWorker = startWorker(
      master.address,
      inputDirs = workers(2).inputDirs,
      outputDir = workers(2).outputDir
    )

    // Verify recovery
    waitForCompletion(master, timeout = 120.seconds)
    runValsort(collectAllOutputs()) shouldBe "SUCCESS"
  }
}
```

### 10.2 Network Partition

```scala
class NetworkPartitionSpec extends AnyFlatSpec with Matchers {
  "System" should "detect disconnected workers" in {
    val master = startMaster(3)
    val workers = (0 to 2).map(i => startWorker(master.address))

    // Simulate network partition (block worker 1's traffic)
    blockNetworkTraffic(workers(1))

    // Master should detect failure via heartbeat
    eventually(timeout(30.seconds)) {
      master.failedWorkers should contain(workers(1).workerId)
    }
  }

  it should "handle transient network failures with retries" in {
    val master = startMaster(2)
    val worker1 = startWorker(master.address)
    val worker2 = startWorkerWithUnstableNetwork(
      master.address,
      failureRate = 0.3,
      maxRetries = 5
    )

    // Should complete despite network issues
    waitForCompletion(master, timeout = 180.seconds)
    runValsort(collectAllOutputs()) shouldBe "SUCCESS"
  }
}
```

### 10.3 Data Corruption Detection

```scala
class DataIntegritySpec extends AnyFlatSpec with Matchers {
  "System" should "detect corrupted partition files" in {
    val master = startMaster(2)
    val workers = (0 to 1).map(i => startWorker(master.address))

    // Wait for partitioning to complete
    waitForPhase(workers(0), Phase.SHUFFLING)

    // Corrupt a partition file
    val partitionFile = findPartitionFile(workers(0), partitionId = 5)
    corruptFile(partitionFile, offset = 50, numBytes = 10)

    // Continue execution
    // System should detect corruption during merge
    intercept[DataCorruptionException] {
      waitForCompletion(master, timeout = 60.seconds)
    }
  }
}
```

---

## 11. Test Execution Strategy

### 11.1 Test 실행 순서

```bash
# 1. Unit tests (빠름, 자주 실행)
sbt "testOnly *Spec"

# 2. Integration tests (느림, PR 전)
sbt "testOnly *IntegrationSpec"

# 3. E2E tests (가장 느림, 배포 전)
sbt "testOnly *EndToEndSpec"

# 4. Performance tests (주간)
sbt "testOnly *PerformanceSpec"
```

### 11.2 CI/CD Pipeline

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: olafurpg/setup-scala@v13
      - run: sbt test

  integration-tests:
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - uses: actions/checkout@v2
      - uses: olafurpg/setup-scala@v13
      - run: sbt "testOnly *IntegrationSpec"

  e2e-tests:
    runs-on: ubuntu-latest
    needs: integration-tests
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v2
      - uses: olafurpg/setup-scala@v13
      - run: |
          # Install gensort/valsort
          wget http://www.ordinal.com/gensort/gensort-linux.tar.gz
          tar xzf gensort-linux.tar.gz
          export PATH=$PATH:$PWD/gensort-linux

          # Run E2E tests
          sbt "testOnly *EndToEndSpec"
```

### 11.3 Test Coverage

```scala
// plugins.sbt
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.7")

// build.sbt
coverageEnabled := true
coverageMinimumStmtTotal := 80
coverageFailOnMinimum := true
```

```bash
# Generate coverage report
sbt clean coverage test coverageReport

# View report
open target/scala-2.13/scoverage-report/index.html
```

---

## 12. Debugging & Troubleshooting

### 12.1 Test Debugging

```scala
class DebugSpec extends AnyFlatSpec with Matchers {
  "Debug" should "help diagnose sorting issues" in {
    val records = generateRandomRecords(100)
    val sorted = ExternalSort.sortInMemory(records)

    // Print first 10 keys for inspection
    println("First 10 keys:")
    sorted.take(10).foreach { rec =>
      println(rec.key.map("%02x".format(_)).mkString)
    }

    // Check for duplicates
    val duplicates = sorted.groupBy(_.key).filter(_._2.size > 1)
    if (duplicates.nonEmpty) {
      println(s"Found ${duplicates.size} duplicate keys")
    }

    // Verify sorted
    sorted shouldBe sorted.sorted
  }
}
```

### 12.2 Log Analysis

```scala
// Test with detailed logging
class VerboseLogSpec extends AnyFlatSpec with Matchers {
  override def withFixture(test: NoArgTest) = {
    // Enable verbose logging for this test
    System.setProperty("log.level", "DEBUG")
    try super.withFixture(test)
    finally System.setProperty("log.level", "INFO")
  }

  "Worker" should "log all phase transitions" in {
    val worker = new WorkerNode(...)
    // Logs will show detailed state transitions
    worker.run()
  }
}
```

---

## 13. Implementation Checklist

### Phase 1: Core (Week 3)
- [ ] Record class with tests
- [ ] RecordReader with tests (Binary & ASCII)
- [ ] RecordWriter with tests
- [ ] InputFormatDetector with tests
- [ ] ByteArrayComparator with tests

### Phase 2: Algorithms (Week 4)
- [ ] External Sort with tests
- [ ] K-way Merge with tests
- [ ] RangePartitioner with tests
- [ ] ShuffleMap with tests

### Phase 3: Communication (Week 5)
- [ ] gRPC proto definitions
- [ ] MasterService implementation with tests
- [ ] WorkerService implementation with tests
- [ ] gRPC client with retry logic + tests

### Phase 4: Integration (Week 6)
- [ ] Master-Worker registration tests
- [ ] Sampling phase tests
- [ ] Shuffle phase tests
- [ ] File management tests
- [ ] Integration tests pass

### Phase 5: E2E (Week 7)
- [ ] Simple 3-worker scenario
- [ ] Large data test (1GB+)
- [ ] Fault tolerance test
- [ ] Performance baseline
- [ ] All E2E tests pass

### Phase 6: Polish (Week 8)
- [ ] Code coverage > 80%
- [ ] All edge cases tested
- [ ] Documentation complete
- [ ] Performance optimization
- [ ] Ready for demo

---

## 14. Summary

이 문서는 TDD 기반 개발을 위한 완전한 가이드입니다.

**핵심 원칙**:
1. ✅ **테스트 먼저**: 구현 전에 테스트 작성
2. ✅ **작은 단계**: 한 번에 하나의 기능만
3. ✅ **빠른 피드백**: 단위 테스트는 초 단위로 실행
4. ✅ **리팩토링**: 테스트가 통과하면 코드 개선
5. ✅ **문서화**: 테스트 자체가 사용법 문서

**다음 단계**:
1. `sbt new` 프로젝트 생성
2. `build.sbt` 의존성 설정
3. Phase 1 시작: Record 클래스 테스트 작성
4. Red → Green → Refactor 반복

**참고 문서**:
- `2025-10-24_plan_ver3.md`: 전체 설계
- `0-implementation-decisions.md`: 구현 결정
- `3-grpc-sequences.md`: gRPC 프로토콜

---

**문서 버전**: v1.0
**최종 수정**: 2025-10-24
