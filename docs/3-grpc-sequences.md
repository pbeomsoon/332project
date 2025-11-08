# gRPC Sequence Diagrams

**문서 목적**: 분산 정렬 시스템의 모든 Phase에서 발생하는 완전한 gRPC 호출 시퀀스를 상세히 문서화

**참조 문서**:
- `2025-10-24_plan_ver3.md` - 전체 시스템 설계
- `1-phase-coordination.md` - Phase 조정 메커니즘
- `2-worker-state-machine.md` - Worker 상태 머신

---

## 1. Protocol Buffers 정의 (통합본)

```protobuf
syntax = "proto3";

package distsort;

// ============================================================
// 공통 메시지
// ============================================================

message WorkerInfo {
  string worker_id = 1;
  string address = 2;
  int32 port = 3;
}

message Ack {
  bool success = 1;
  string message = 2;
}

// ============================================================
// Phase 1: Worker 등록 (Initialization)
// ============================================================

message RegisterRequest {
  WorkerInfo worker_info = 1;
  repeated string input_directories = 2;
  string output_directory = 3;
  string temp_directory = 4;
  bool use_ascii_format = 5;
}

message RegisterResponse {
  bool success = 1;
  string worker_id = 2;
  int32 total_workers = 3;
  string message = 4;
}

// ============================================================
// Phase 2: Sampling
// ============================================================

message StartSamplingRequest {
  int32 total_workers = 1;
  int32 sample_size_per_worker = 2;
}

message SampleData {
  repeated bytes keys = 1;  // 각 key는 10 bytes
  int64 total_records_read = 2;
}

// ============================================================
// Phase 3: Partition 설정
// ============================================================

message PartitionConfig {
  repeated bytes boundaries = 1;  // N-1 개의 경계값
  int32 num_partitions = 2;
  map<int32, int32> shuffle_map = 3;  // PartitionID → WorkerID
  repeated WorkerInfo all_workers = 4;
}

// ============================================================
// Phase 4: Phase 완료 알림 (범용)
// ============================================================

enum WorkerPhase {
  PHASE_SAMPLING = 0;
  PHASE_SORTING = 1;
  PHASE_SHUFFLING = 2;
  PHASE_MERGING = 3;
}

message PhaseCompleteRequest {
  string worker_id = 1;
  WorkerPhase phase = 2;
  int64 timestamp = 3;
  string details = 4;  // optional: records processed, files created, etc.
}

// ============================================================
// Phase 5: Shuffle 시작 신호
// ============================================================

message ShuffleSignal {
  int64 timestamp = 1;
  string message = 2;
}

// ============================================================
// Phase 6: Shuffle 데이터 전송 (Worker ↔ Worker)
// ============================================================

message PartitionFile {
  int32 partition_id = 1;
  string sender_worker_id = 2;
  bytes data = 3;  // 실제 파일 데이터 (또는 chunk)
  int64 offset = 4;  // streaming 시 chunk offset
  int64 total_size = 5;
  bool is_last_chunk = 6;
}

message PartitionFileAck {
  bool success = 1;
  int32 partition_id = 2;
  string receiver_worker_id = 3;
  string error_message = 4;
}

// ============================================================
// Phase 7: Merge 시작 신호
// ============================================================

message MergeSignal {
  int64 timestamp = 1;
  string message = 2;
}

// ============================================================
// gRPC Service 정의
// ============================================================

service MasterService {
  // Phase 1: Worker 등록
  rpc RegisterWorker(RegisterRequest) returns (RegisterResponse);

  // Phase 2: Sampling 데이터 수신
  rpc SubmitSample(SampleData) returns (Ack);

  // Phase 4, 5, 6: Phase 완료 알림 수신
  rpc NotifyPhaseComplete(PhaseCompleteRequest) returns (Ack);
}

service WorkerService {
  // Phase 2: Sampling 시작 명령 수신
  rpc StartSampling(StartSamplingRequest) returns (Ack);

  // Phase 3: Partition 설정 수신
  rpc ConfigurePartitions(PartitionConfig) returns (Ack);

  // Phase 5: Shuffle 시작 신호 수신
  rpc StartShuffle(ShuffleSignal) returns (Ack);

  // Phase 6: 다른 Worker로부터 partition 파일 수신
  rpc ReceivePartition(stream PartitionFile) returns (PartitionFileAck);

  // Phase 7: Merge 시작 신호 수신
  rpc StartMerge(MergeSignal) returns (Ack);
}
```

---

## 2. Phase 1: Initialization & Worker Registration

### 2.1 시퀀스 다이어그램

```
Worker1                Master                Worker2
  |                      |                      |
  |---RegisterWorker---->|                      |
  |   (WorkerInfo,       |                      |
  |    input_dirs,       |                      |
  |    output_dir)       |                      |
  |                      |                      |
  |<--RegisterResponse---|                      |
  |   (worker_id="W0",   |                      |
  |    total_workers=?)  |                      |
  |                      |                      |
  |                      |<---RegisterWorker----|
  |                      |   (WorkerInfo,       |
  |                      |    input_dirs,       |
  |                      |    output_dir)       |
  |                      |                      |
  |                      |---RegisterResponse-->|
  |                      |   (worker_id="W1",   |
  |                      |    total_workers=?)  |
  |                      |                      |
  |         ... (total N workers register) ...  |
  |                      |                      |
  |                 [Master waits]              |
  |              until N workers register       |
  |                      |                      |
```

### 2.2 구현 상세

**Worker 측**:
```scala
class WorkerNode(masterAddress: String) {
  def initialize(): Unit = {
    stateMachine.transition(Initializing, "Starting initialization")

    val request = RegisterRequest(
      workerInfo = Some(WorkerInfo(
        workerId = "", // Master가 할당
        address = getLocalAddress(),
        port = grpcPort
      )),
      inputDirectories = inputDirs,
      outputDirectory = outputDir,
      tempDirectory = tempDir,
      useAsciiFormat = useAscii
    )

    val response = masterStub.registerWorker(request)

    if (response.success) {
      this.workerId = response.workerId
      this.totalWorkers = response.totalWorkers
      logger.info(s"Registered as ${response.workerId}, total workers: ${response.totalWorkers}")

      stateMachine.transition(WaitingForSamplingCommand, "Registration successful")
    } else {
      stateMachine.transition(Failed, s"Registration failed: ${response.message}")
      throw new RuntimeException(response.message)
    }
  }
}
```

**Master 측**:
```scala
class MasterNode(expectedWorkers: Int) extends MasterServiceGrpc.MasterServiceImplBase {
  private val registeredWorkers = new ConcurrentLinkedQueue[WorkerInfo]()
  private val workerIdCounter = new AtomicInteger(0)
  private val registrationLatch = new CountDownLatch(expectedWorkers)

  override def registerWorker(request: RegisterRequest): Future[RegisterResponse] = {
    synchronized {
      if (registeredWorkers.size() >= expectedWorkers) {
        return Future.successful(RegisterResponse(
          success = false,
          workerId = "",
          totalWorkers = expectedWorkers,
          message = "All workers already registered"
        ))
      }

      val workerId = s"W${workerIdCounter.getAndIncrement()}"
      val workerInfo = request.workerInfo.get.copy(workerId = workerId)

      registeredWorkers.add(workerInfo)
      logger.info(s"Worker $workerId registered (${registeredWorkers.size()}/$expectedWorkers)")

      registrationLatch.countDown()

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
    registrationLatch.await(5, TimeUnit.MINUTES)
    logger.info(s"All $expectedWorkers workers registered")
  }
}
```

### 2.3 타이밍 및 예외 처리

- **Timeout**: Worker가 5초 내 응답 없으면 재시도 (최대 3회)
- **Master Timeout**: 5분 내 모든 Worker가 등록하지 않으면 에러
- **중복 등록**: 동일 address:port로 재등록 시 기존 등록 유지 (멱등성)
- **Worker 초과**: expectedWorkers를 초과하는 등록 요청은 거부

---

## 3. Phase 2: Sampling

### 3.1 시퀀스 다이어그램

```
Master                Worker1               Worker2
  |                      |                      |
  |---StartSampling----->|                      |
  |  (total_workers=2,   |                      |
  |   sample_size=1000)  |                      |
  |                      |                      |
  |---StartSampling------------------------------------->|
  |  (total_workers=2,   |                      |
  |   sample_size=1000)  |                      |
  |                      |                      |
  |                [Read & Sample]         [Read & Sample]
  |                 1000 keys              1000 keys
  |                      |                      |
  |<---SubmitSample------|                      |
  |   (keys: [k1..k1000],|                      |
  |    total_records=N1) |                      |
  |                      |                      |
  |<---SubmitSample-----------------------------------|
  |   (keys: [k1..k1000],|                      |
  |    total_records=N2) |                      |
  |                      |                      |
  |<---NotifyPhaseComplete----|                 |
  |   (phase=SAMPLING)   |                      |
  |                      |                      |
  |<---NotifyPhaseComplete----------------------------|
  |   (phase=SAMPLING)   |                      |
  |                      |                      |
  | [Master combines all samples,               |
  |  sorts globally,                            |
  |  selects N-1 boundaries]                    |
  |                      |                      |
```

### 3.2 구현 상세

**Master → Worker: StartSampling**

```scala
class MasterNode {
  def startSamplingPhase(): Unit = {
    logger.info("Phase 2: Starting sampling phase")

    val sampleSizePerWorker = 1000  // configurable
    val request = StartSamplingRequest(
      totalWorkers = expectedWorkers,
      sampleSizePerWorker = sampleSizePerWorker
    )

    // Broadcast to all workers
    val futures = registeredWorkers.asScala.map { worker =>
      val stub = createWorkerStub(worker)
      stub.startSampling(request).recover {
        case ex: Exception =>
          logger.error(s"Failed to send StartSampling to ${worker.workerId}: $ex")
          Ack(success = false, message = ex.getMessage)
      }
    }

    // Wait for all acks (but don't wait for samples yet)
    Await.result(Future.sequence(futures), 30.seconds)
    logger.info("All workers acknowledged StartSampling")
  }
}
```

**Worker → Master: SubmitSample**

```scala
class WorkerNode {
  def handleStartSampling(request: StartSamplingRequest): Future[Ack] = {
    stateMachine.transition(Sampling, "Starting sampling")

    Future {
      val sampler = new ReservoirSampler(request.sampleSizePerWorker)
      var totalRecords = 0L

      // Read from all input files and sample
      for (dir <- inputDirs; file <- listInputFiles(dir)) {
        val reader = createRecordReader(file)
        reader.foreach { record =>
          totalRecords += 1
          sampler.add(record.key)  // 10 bytes key만 샘플링
        }
      }

      val sampleKeys = sampler.getSample()

      // Submit to master
      val sampleData = SampleData(
        keys = sampleKeys.map(ByteString.copyFrom),
        totalRecordsRead = totalRecords
      )

      val ack = masterStub.submitSample(sampleData)

      if (ack.success) {
        // Notify sampling phase complete
        masterStub.notifyPhaseComplete(PhaseCompleteRequest(
          workerId = workerId,
          phase = WorkerPhase.PHASE_SAMPLING,
          timestamp = System.currentTimeMillis(),
          details = s"Submitted $sampleKeys.size keys from $totalRecords records"
        ))

        stateMachine.transition(WaitingForPartitionConfig, "Sampling complete")
      }

      ack
    }
  }
}
```

**Master: 샘플 수집 및 경계 계산**

```scala
class MasterNode {
  private val samplesCollected = new ConcurrentLinkedQueue[Array[Byte]]()

  override def submitSample(request: SampleData): Future[Ack] = {
    synchronized {
      request.keys.foreach { keyBytes =>
        samplesCollected.add(keyBytes.toByteArray)
      }
      logger.info(s"Received ${request.keys.size} samples (total: ${samplesCollected.size()})")
    }

    Future.successful(Ack(success = true, message = "Sample received"))
  }

  def computeBoundaries(): Array[Byte] = {
    phaseTracker.waitForPhase(WorkerPhase.PHASE_SAMPLING)

    logger.info(s"Computing boundaries from ${samplesCollected.size()} total samples")

    val allSamples = samplesCollected.asScala.toArray
    val sortedSamples = allSamples.sortWith(ByteArrayOrdering.compare(_, _) < 0)

    val numPartitions = expectedWorkers  // or M for Strategy B
    val boundaries = new Array[Array[Byte]](numPartitions - 1)

    for (i <- 0 until numPartitions - 1) {
      val quantileIndex = ((i + 1) * sortedSamples.length) / numPartitions
      boundaries(i) = sortedSamples(quantileIndex)
    }

    logger.info(s"Computed ${boundaries.length} boundaries")
    boundaries
  }
}
```

### 3.3 타이밍 및 예외 처리

- **Sampling Timeout**: 각 Worker는 10분 내 샘플링 완료 필요
- **Sample 크기 부족**: 입력 데이터가 요청된 샘플 크기보다 작으면 전체를 샘플로 전송
- **SubmitSample 실패**: Worker는 3회 재시도 후 Failed 상태로 전환
- **일부 Worker 실패**: Master는 최소 N/2 Worker의 샘플만으로도 경계 계산 가능하도록 구현 (선택사항)

---

## 4. Phase 3: Partition Configuration

### 4.1 시퀀스 다이어그램

```
Master                Worker1               Worker2
  |                      |                      |
  | [Compute boundaries  |                      |
  |  Create shuffleMap]  |                      |
  |                      |                      |
  |--ConfigurePartitions>|                      |
  |  (boundaries=[b1],   |                      |
  |   num_partitions=2,  |                      |
  |   shuffle_map={      |                      |
  |     0→0, 1→1},       |                      |
  |   all_workers=[...]) |                      |
  |                      |                      |
  |--ConfigurePartitions------------------------------>|
  |  (boundaries=[b1],   |                      |
  |   num_partitions=2,  |                      |
  |   shuffle_map={      |                      |
  |     0→0, 1→1},       |                      |
  |   all_workers=[...]) |                      |
  |                      |                      |
  |<-------Ack-----------|                      |
  |                      |                      |
  |<-------Ack------------------------------------|
  |                      |                      |
```

### 4.2 구현 상세

**Master: PartitionConfig 생성 및 전송**

```scala
class MasterNode {
  def distributePartitionConfig(): Unit = {
    val boundaries = computeBoundaries()
    val numPartitions = expectedWorkers  // Strategy A: N, Strategy B: M

    // Create shuffle map
    val shuffleMap = createShuffleMap(expectedWorkers, numPartitions)

    val config = PartitionConfig(
      boundaries = boundaries.map(ByteString.copyFrom).toSeq,
      numPartitions = numPartitions,
      shuffleMap = shuffleMap,
      allWorkers = registeredWorkers.asScala.toSeq
    )

    logger.info(s"Distributing partition config: $numPartitions partitions, ${boundaries.length} boundaries")

    // Broadcast to all workers
    val futures = registeredWorkers.asScala.map { worker =>
      val stub = createWorkerStub(worker)
      stub.configurePartitions(config).recover {
        case ex: Exception =>
          logger.error(s"Failed to send config to ${worker.workerId}: $ex")
          Ack(success = false, message = ex.getMessage)
      }
    }

    Await.result(Future.sequence(futures), 30.seconds)
    logger.info("All workers configured with partition config")
  }

  private def createShuffleMap(numWorkers: Int, numPartitions: Int): Map[Int, Int] = {
    val shuffleMap = mutable.Map[Int, Int]()

    if (numPartitions == numWorkers) {
      // Strategy A: 1:1 mapping
      for (i <- 0 until numPartitions) {
        shuffleMap(i) = i
      }
    } else {
      // Strategy B: distribute partitions evenly
      val partitionsPerWorker = math.ceil(numPartitions.toDouble / numWorkers).toInt
      for (partitionId <- 0 until numPartitions) {
        val workerId = math.min(partitionId / partitionsPerWorker, numWorkers - 1)
        shuffleMap(partitionId) = workerId
      }
    }

    shuffleMap.toMap
  }
}
```

**Worker: PartitionConfig 수신 및 저장**

```scala
class WorkerNode extends WorkerServiceGrpc.WorkerServiceImplBase {
  private var partitionConfig: Option[PartitionConfig] = None

  override def configurePartitions(config: PartitionConfig): Future[Ack] = {
    try {
      synchronized {
        this.partitionConfig = Some(config)

        logger.info(s"Received partition config: ${config.numPartitions} partitions, " +
          s"${config.boundaries.size} boundaries")
        logger.debug(s"Shuffle map: ${config.shuffleMap}")
        logger.debug(s"All workers: ${config.allWorkers.map(_.workerId).mkString(", ")}")

        // Validate configuration
        require(config.boundaries.size == config.numPartitions - 1,
          s"Expected ${config.numPartitions - 1} boundaries, got ${config.boundaries.size}")
        require(config.allWorkers.size == totalWorkers,
          s"Expected $totalWorkers workers, got ${config.allWorkers.size}")

        // Build worker lookup map
        workerLookup = config.allWorkers.map(w => w.workerId -> w).toMap

        stateMachine.transition(Sorting, "Partition config received, starting sort")
      }

      Future.successful(Ack(success = true, message = "Configuration applied"))
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to apply partition config: $ex")
        stateMachine.transition(Failed, s"Config error: ${ex.getMessage}")
        Future.successful(Ack(success = false, message = ex.getMessage))
    }
  }
}
```

### 4.3 타이밍 및 예외 처리

- **Config 전송 실패**: Master는 실패한 Worker에게 3회 재시도
- **Validation 실패**: Worker는 config 검증 실패 시 즉시 Failed 상태로 전환
- **Config 불일치**: 모든 Worker가 동일한 config를 받도록 보장 (atomic broadcast)

---

## 5. Phase 4: Sort & Partition (Local)

### 5.1 시퀀스 다이어그램

```
Master                Worker1               Worker2
  |                      |                      |
  |                 [External Sort]        [External Sort]
  |                 [Create sorted         [Create sorted
  |                  chunks]                chunks]
  |                      |                      |
  |                 [Partition into]       [Partition into]
  |                  P0, P1 files           P0, P1 files
  |                      |                      |
  |<--NotifyPhaseComplete|                      |
  |   (phase=SORTING,    |                      |
  |    details="2 files")|                      |
  |                      |                      |
  |<--NotifyPhaseComplete-----------------------|
  |   (phase=SORTING,    |                      |
  |    details="2 files")|                      |
  |                      |                      |
  | [Wait for all        |                      |
  |  sorting complete]   |                      |
  |                      |                      |
```

### 5.2 구현 상세

**Worker: Sort & Partition**

```scala
class WorkerNode {
  def sortAndPartition(): Unit = {
    stateMachine.transition(Sorting, "Starting external sort and partitioning")

    val config = partitionConfig.getOrElse(
      throw new IllegalStateException("Partition config not received")
    )

    // Step 1: External sort - create sorted chunks
    val sorter = new ExternalSorter(tempDir, memoryLimit = 256 * 1024 * 1024)
    val sortedChunks = sorter.sort(inputDirs, useAsciiFormat)

    logger.info(s"Created ${sortedChunks.size} sorted chunks")

    // Step 2: Partition - create partition files
    val partitioner = new Partitioner(config.boundaries, config.numPartitions)
    val partitionWriters = (0 until config.numPartitions).map { partitionId =>
      val file = new File(tempDir, s"partition.$partitionId")
      partitionId -> new BufferedOutputStream(new FileOutputStream(file))
    }.toMap

    try {
      // K-way merge while partitioning
      val mergeIterator = sorter.mergeChunks(sortedChunks)

      var recordCount = 0L
      mergeIterator.foreach { record =>
        val partitionId = partitioner.getPartition(record.key)
        partitionWriters(partitionId).write(record.toBytes)
        recordCount += 1

        if (recordCount % 1000000 == 0) {
          logger.debug(s"Partitioned $recordCount records")
        }
      }

      logger.info(s"Partitioned $recordCount records into ${config.numPartitions} files")

      // Step 3: Notify master
      masterStub.notifyPhaseComplete(PhaseCompleteRequest(
        workerId = workerId,
        phase = WorkerPhase.PHASE_SORTING,
        timestamp = System.currentTimeMillis(),
        details = s"Created ${config.numPartitions} partition files from $recordCount records"
      ))

      stateMachine.transition(WaitingForShuffleSignal, "Sorting complete, waiting for shuffle")

    } finally {
      partitionWriters.values.foreach(_.close())
      sortedChunks.foreach(_.delete())  // cleanup temporary chunks
    }
  }
}
```

**Partitioner 구현**:

```scala
class Partitioner(boundaries: Seq[Array[Byte]], numPartitions: Int) {
  def getPartition(key: Array[Byte]): Int = {
    // Binary search to find partition
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

    left  // partition ID: 0 to numPartitions-1
  }
}
```

### 5.3 타이밍 및 예외 처리

- **메모리 부족**: Chunk 크기를 동적으로 조정 (adaptive chunking)
- **디스크 공간 부족**: 사전 검사 후 실패 시 즉시 Master에 통보
- **Sorting Timeout**: 각 Worker는 30분 내 완료 필요 (large input 고려)
- **부분 실패**: 일부 partition 파일만 생성된 경우 cleanup 후 재시도

---

## 6. Phase 5: Shuffle (Data Transfer)

### 6.1 시퀀스 다이어그램

```
Master         Worker1(W0)         Worker2(W1)
  |                 |                   |
  | [All sorting    |                   |
  |  complete]      |                   |
  |                 |                   |
  |--StartShuffle-->|                   |
  |                 |                   |
  |--StartShuffle---------------------->|
  |                 |                   |
  | [Workers determine which partitions |
  |  to send/keep based on shuffleMap]  |
  |                 |                   |
  | shuffleMap: {0→0, 1→1}              |
  |                 |                   |
  | W0 keeps P0, sends P1 to W1         |
  | W1 sends P0 to W0, keeps P1         |
  |                 |                   |
  |                 |---ReceivePartition(P1, stream)-->|
  |                 |                   |
  |                 |<------Ack---------|
  |                 |                   |
  |<---ReceivePartition(P0, stream)-----|
  |                 |                   |
  |-------Ack------>|                   |
  |                 |                   |
  |                 |                   |
  |<--NotifyPhaseComplete--|            |
  |   (phase=SHUFFLING)    |            |
  |                 |                   |
  |<--NotifyPhaseComplete---------------|
  |   (phase=SHUFFLING)    |            |
  |                 |                   |
  | [Wait for all   |                   |
  |  shuffling complete]                |
  |                 |                   |
```

### 6.2 구현 상세

**Master → Workers: StartShuffle**

```scala
class MasterNode {
  def startShufflePhase(): Unit = {
    phaseTracker.waitForPhase(WorkerPhase.PHASE_SORTING)

    logger.info("Phase 5: All workers completed sorting, starting shuffle")

    val signal = ShuffleSignal(
      timestamp = System.currentTimeMillis(),
      message = "Begin shuffle phase"
    )

    val futures = registeredWorkers.asScala.map { worker =>
      val stub = createWorkerStub(worker)
      stub.startShuffle(signal).recover {
        case ex: Exception =>
          logger.error(s"Failed to send StartShuffle to ${worker.workerId}: $ex")
          Ack(success = false, message = ex.getMessage)
      }
    }

    Await.result(Future.sequence(futures), 30.seconds)
    logger.info("All workers acknowledged StartShuffle")
  }
}
```

**Worker: Shuffle 시작 및 데이터 전송**

```scala
class WorkerNode {
  override def startShuffle(signal: ShuffleSignal): Future[Ack] = {
    shuffleStartLatch.countDown()
    logger.info(s"Received shuffle signal at ${signal.timestamp}")
    Future.successful(Ack(success = true, message = "Shuffle started"))
  }

  def performShuffle(): Unit = {
    // Wait for master's signal
    shuffleStartLatch.await(10, TimeUnit.MINUTES)

    stateMachine.transition(Shuffling, "Starting shuffle phase")

    val config = partitionConfig.get
    val myWorkerIdNum = workerId.substring(1).toInt  // "W0" → 0

    // Determine which partitions to send where
    val transferTasks = (0 until config.numPartitions).map { partitionId =>
      val targetWorkerId = config.shuffleMap(partitionId)
      val partitionFile = new File(tempDir, s"partition.$partitionId")

      if (targetWorkerId == myWorkerIdNum) {
        // Keep locally - just move to received directory
        logger.info(s"Keeping partition $partitionId locally")
        val destFile = new File(receivedPartitionsDir, s"partition.$partitionId")
        Files.move(partitionFile.toPath, destFile.toPath, StandardCopyOption.ATOMIC_MOVE)
        Future.successful(true)
      } else {
        // Send to another worker
        val targetWorker = workerLookup(s"W$targetWorkerId")
        logger.info(s"Sending partition $partitionId to ${targetWorker.workerId}")
        sendPartitionFile(partitionId, partitionFile, targetWorker)
      }
    }

    // Wait for all transfers to complete
    val results = Await.result(Future.sequence(transferTasks), 30.minutes)

    if (results.forall(_ == true)) {
      logger.info("Shuffle phase completed successfully")

      // Notify master
      masterStub.notifyPhaseComplete(PhaseCompleteRequest(
        workerId = workerId,
        phase = WorkerPhase.PHASE_SHUFFLING,
        timestamp = System.currentTimeMillis(),
        details = s"Transferred ${results.size} partitions"
      ))

      stateMachine.transition(WaitingForMergeSignal, "Shuffle complete, waiting for merge")
    } else {
      stateMachine.transition(Failed, "Shuffle failed")
    }
  }
}
```

**Worker → Worker: ReceivePartition (Streaming)**

```scala
class WorkerNode {
  private def sendPartitionFile(
    partitionId: Int,
    file: File,
    targetWorker: WorkerInfo
  ): Future[Boolean] = {
    val stub = createWorkerStub(targetWorker)

    // Create streaming iterator
    val chunkSize = 4 * 1024 * 1024  // 4 MB chunks
    val totalSize = file.length()
    val inputStream = new BufferedInputStream(new FileInputStream(file))

    val chunks = Iterator.continually {
      val buffer = new Array[Byte](chunkSize)
      val bytesRead = inputStream.read(buffer)
      if (bytesRead > 0) Some((buffer, bytesRead)) else None
    }.takeWhile(_.isDefined).map(_.get).zipWithIndex.map { case ((buffer, bytesRead), index) =>
      val offset = index * chunkSize
      val isLast = offset + bytesRead >= totalSize

      PartitionFile(
        partitionId = partitionId,
        senderWorkerId = workerId,
        data = ByteString.copyFrom(buffer, 0, bytesRead),
        offset = offset,
        totalSize = totalSize,
        isLastChunk = isLast
      )
    }

    // Send streaming request
    val responseObserver = new StreamObserver[PartitionFileAck] {
      private val promise = Promise[PartitionFileAck]()

      override def onNext(ack: PartitionFileAck): Unit = {
        promise.success(ack)
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Error sending partition $partitionId: $t")
        promise.failure(t)
      }

      override def onCompleted(): Unit = {
        logger.debug(s"Completed sending partition $partitionId")
      }

      def future: Future[PartitionFileAck] = promise.future
    }

    val requestObserver = stub.receivePartition(responseObserver)

    try {
      chunks.foreach { chunk =>
        requestObserver.onNext(chunk)
      }
      requestObserver.onCompleted()

      responseObserver.future.map { ack =>
        if (ack.success) {
          logger.info(s"Successfully sent partition $partitionId to ${targetWorker.workerId}")
          file.delete()  // cleanup local copy
          true
        } else {
          logger.error(s"Failed to send partition $partitionId: ${ack.errorMessage}")
          false
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Exception sending partition $partitionId: $ex")
        requestObserver.onError(ex)
        Future.successful(false)
    } finally {
      inputStream.close()
    }
  }

  // Receiver side
  override def receivePartition(
    responseObserver: StreamObserver[PartitionFileAck]
  ): StreamObserver[PartitionFile] = {
    new StreamObserver[PartitionFile] {
      private var partitionId: Option[Int] = None
      private var outputStream: Option[OutputStream] = None
      private var tempFile: Option[File] = None
      private var receivedBytes = 0L

      override def onNext(chunk: PartitionFile): Unit = {
        try {
          if (partitionId.isEmpty) {
            // First chunk
            partitionId = Some(chunk.partitionId)
            tempFile = Some(new File(receivedPartitionsDir, s"partition.${chunk.partitionId}.tmp"))
            outputStream = Some(new BufferedOutputStream(new FileOutputStream(tempFile.get)))

            logger.info(s"Receiving partition ${chunk.partitionId} from ${chunk.senderWorkerId}, " +
              s"total size: ${chunk.totalSize} bytes")
          }

          // Write chunk
          outputStream.get.write(chunk.data.toByteArray)
          receivedBytes += chunk.data.size()

          if (chunk.isLastChunk) {
            outputStream.get.close()

            // Atomic rename
            val finalFile = new File(receivedPartitionsDir, s"partition.${chunk.partitionId}")
            Files.move(tempFile.get.toPath, finalFile.toPath, StandardCopyOption.ATOMIC_MOVE)

            logger.info(s"Completed receiving partition ${chunk.partitionId}, " +
              s"received $receivedBytes bytes")

            responseObserver.onNext(PartitionFileAck(
              success = true,
              partitionId = chunk.partitionId,
              receiverWorkerId = workerId,
              errorMessage = ""
            ))
            responseObserver.onCompleted()
          }
        } catch {
          case ex: Exception =>
            logger.error(s"Error receiving partition chunk: $ex")
            outputStream.foreach(_.close())
            tempFile.foreach(_.delete())

            responseObserver.onNext(PartitionFileAck(
              success = false,
              partitionId = chunk.partitionId,
              receiverWorkerId = workerId,
              errorMessage = ex.getMessage
            ))
            responseObserver.onError(ex)
        }
      }

      override def onError(t: Throwable): Unit = {
        logger.error(s"Error in receive stream: $t")
        outputStream.foreach(_.close())
        tempFile.foreach(_.delete())
      }

      override def onCompleted(): Unit = {
        logger.debug(s"Receive stream completed for partition ${partitionId.getOrElse("unknown")}")
      }
    }
  }
}
```

### 6.3 타이밍 및 예외 처리

- **Network Timeout**: 각 chunk 전송은 30초 timeout
- **전송 실패**: 3회 재시도 후 Master에 실패 통보
- **부분 전송 실패**: 임시 파일 삭제 후 재전송
- **동시 전송 제한**: Worker당 최대 5개 동시 전송 (bandwidth 관리)
- **Checksum 검증**: 각 파일 전송 후 MD5 checksum 비교 (선택사항)

---

## 7. Phase 6: Merge

### 7.1 시퀀스 다이어그램

```
Master         Worker1(W0)         Worker2(W1)
  |                 |                   |
  | [All shuffling  |                   |
  |  complete]      |                   |
  |                 |                   |
  |--StartMerge---->|                   |
  |                 |                   |
  |--StartMerge------------------------->|
  |                 |                   |
  |            [K-way merge]       [K-way merge]
  |            partition files     partition files
  |            assigned to W0      assigned to W1
  |                 |                   |
  |            [Write final]       [Write final]
  |            partition.0         partition.1
  |                 |                   |
  |<--NotifyPhaseComplete--|            |
  |   (phase=MERGING)      |            |
  |                 |                   |
  |<--NotifyPhaseComplete---------------|
  |   (phase=MERGING)      |            |
  |                 |                   |
  | [All workers    |                   |
  |  complete]      |                   |
  |                 |                   |
  | [Master writes  |                   |
  |  output list]   |                   |
  |                 |                   |
```

### 7.2 구현 상세

**Master → Workers: StartMerge**

```scala
class MasterNode {
  def startMergePhase(): Unit = {
    phaseTracker.waitForPhase(WorkerPhase.PHASE_SHUFFLING)

    logger.info("Phase 6: All workers completed shuffling, starting merge")

    val signal = MergeSignal(
      timestamp = System.currentTimeMillis(),
      message = "Begin merge phase"
    )

    val futures = registeredWorkers.asScala.map { worker =>
      val stub = createWorkerStub(worker)
      stub.startMerge(signal).recover {
        case ex: Exception =>
          logger.error(s"Failed to send StartMerge to ${worker.workerId}: $ex")
          Ack(success = false, message = ex.getMessage)
      }
    }

    Await.result(Future.sequence(futures), 30.seconds)
    logger.info("All workers acknowledged StartMerge")
  }
}
```

**Worker: K-way Merge**

```scala
class WorkerNode {
  override def startMerge(signal: MergeSignal): Future[Ack] = {
    mergeStartLatch.countDown()
    logger.info(s"Received merge signal at ${signal.timestamp}")
    Future.successful(Ack(success = true, message = "Merge started"))
  }

  def performMerge(): Unit = {
    // Wait for master's signal
    mergeStartLatch.await(10, TimeUnit.MINUTES)

    stateMachine.transition(Merging, "Starting merge phase")

    val config = partitionConfig.get
    val myWorkerIdNum = workerId.substring(1).toInt

    // Find all partitions assigned to me
    val myPartitions = config.shuffleMap.filter(_._2 == myWorkerIdNum).keys.toSeq.sorted

    logger.info(s"Merging ${myPartitions.size} partitions: ${myPartitions.mkString(", ")}")

    myPartitions.foreach { partitionId =>
      mergePartition(partitionId)
    }

    logger.info("All partitions merged successfully")

    // Notify master
    masterStub.notifyPhaseComplete(PhaseCompleteRequest(
      workerId = workerId,
      phase = WorkerPhase.PHASE_MERGING,
      timestamp = System.currentTimeMillis(),
      details = s"Merged ${myPartitions.size} partitions"
    ))

    stateMachine.transition(Completed, "All work complete")
  }

  private def mergePartition(partitionId: Int): Unit = {
    // Collect all files for this partition (may come from multiple workers)
    val partitionFiles = listReceivedPartitionFiles(partitionId)

    if (partitionFiles.isEmpty) {
      logger.warn(s"No files found for partition $partitionId")
      return
    }

    logger.info(s"Merging partition $partitionId from ${partitionFiles.size} files")

    val outputFile = new File(outputDir, s"partition.$partitionId")
    val tempOutputFile = new File(outputDir, s"partition.$partitionId.tmp")

    val merger = new KWayMerger(partitionFiles)
    val outputStream = new BufferedOutputStream(new FileOutputStream(tempOutputFile))

    try {
      var recordCount = 0L
      merger.mergeAll { record =>
        outputStream.write(record.toBytes)
        recordCount += 1

        if (recordCount % 1000000 == 0) {
          logger.debug(s"Merged $recordCount records for partition $partitionId")
        }
      }

      outputStream.close()

      // Atomic rename
      Files.move(tempOutputFile.toPath, outputFile.toPath, StandardCopyOption.ATOMIC_MOVE)

      logger.info(s"Completed merging partition $partitionId: $recordCount records")

      // Cleanup received partition files
      partitionFiles.foreach(_.delete())

    } catch {
      case ex: Exception =>
        logger.error(s"Error merging partition $partitionId: $ex")
        outputStream.close()
        tempOutputFile.delete()
        throw ex
    }
  }

  private def listReceivedPartitionFiles(partitionId: Int): Seq[File] = {
    val partitionPattern = s"partition\\.$partitionId(\\.from_.*)?".r

    receivedPartitionsDir.listFiles().filter { file =>
      partitionPattern.findFirstIn(file.getName).isDefined
    }.toSeq
  }
}
```

**K-way Merger 구현**:

```scala
class KWayMerger(files: Seq[File]) {
  case class HeapEntry(record: Record, fileIndex: Int) extends Ordered[HeapEntry] {
    def compare(that: HeapEntry): Int = {
      -ByteArrayOrdering.compare(this.record.key, that.record.key)  // min-heap
    }
  }

  def mergeAll(consumer: Record => Unit): Unit = {
    val readers = files.map(f => new RecordReader(f))
    val heap = new mutable.PriorityQueue[HeapEntry]()

    // Initialize heap with first record from each file
    readers.zipWithIndex.foreach { case (reader, index) =>
      reader.nextRecord().foreach { record =>
        heap.enqueue(HeapEntry(record, index))
      }
    }

    try {
      // K-way merge using min-heap
      while (heap.nonEmpty) {
        val HeapEntry(record, fileIndex) = heap.dequeue()
        consumer(record)

        // Read next record from the same file
        readers(fileIndex).nextRecord().foreach { nextRecord =>
          heap.enqueue(HeapEntry(nextRecord, fileIndex))
        }
      }
    } finally {
      readers.foreach(_.close())
    }
  }
}
```

**Master: 최종 출력**

**PDF 요구사항 (Algorithm Phase 0)**:
```
print "Master IP:Port"
for each worker in workerList do
    print worker.IP
end for
```

**PDF 예시**:
```
141.223.91.81          <- Master IP:Port
141.223.91.81          <- Worker 1 IP
141.223.91.82          <- Worker 2 IP
141.223.91.83          <- Worker 3 IP
```

```scala
class MasterNode {
  def finalizeAndOutput(): Unit = {
    phaseTracker.waitForPhase(WorkerPhase.PHASE_MERGING)

    logger.info("All workers completed merging, writing final output")

    // Sort workers by ID (W0, W1, W2, ...) for deterministic ordering
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
    // Option 1: Use environment variable or configuration
    sys.env.getOrElse("MASTER_HOST", {
      // Option 2: Auto-detect local IP
      InetAddress.getLocalHost.getHostAddress
    })
  }
}
```

**출력 형식 상세**:

```
출력 예시 (3 workers):
───────────────────────────
192.168.1.100:5000
192.168.1.10, 192.168.1.11, 192.168.1.12
───────────────────────────

의미:
- Line 1: Master 노드의 주소 (192.168.1.100:5000)
- Line 2: Worker IPs (콤마로 구분, 한 줄에)
  * Worker 0 (192.168.1.10): partition.0, partition.1, partition.2, ... 담당
  * Worker 1 (192.168.1.11): partition.3, partition.4, partition.5, ... 담당
  * Worker 2 (192.168.1.12): partition.6, partition.7, partition.8, ... 담당
  * (range-based 할당: 연속된 partition 번호)

클라이언트 사용법:
```bash
# Master 실행 및 출력 파싱
output=$(sbt "runMain distsort.Main master 3" 2>/dev/null)

# Line 1: Master address
master_addr=$(echo "$output" | sed -n '1p')
echo "Master: $master_addr"

# Line 2: Worker IP들 (콤마로 구분)
worker_line=$(echo "$output" | sed -n '2p')
IFS=', ' read -r -a workers <<< "$worker_line"

# 각 Worker의 모든 partition 파일 다운로드
for i in "${!workers[@]}"; do
  worker_ip="${workers[$i]}"
  echo "Downloading partitions from Worker $i at $worker_ip"

  # Worker i가 담당하는 partition 파일들: partition.i, partition.(i+N), ...
  scp "${worker_ip}:/path/to/output/partition.*" ./
done

# 또는 특정 partition만 다운로드
for i in "${!workers[@]}"; do
  worker_ip="${workers[$i]}"
  # Worker i의 partition 패턴에 맞게 다운로드
  # 예: Worker 0 → partition.0, partition.1, partition.2 (range-based)
  rsync -avz "${worker_ip}:/path/to/output/" ./worker${i}_output/
done
```
```

### 7.3 타이밍 및 예외 처리

- **Merge Timeout**: 각 Worker는 60분 내 merge 완료 필요
- **메모리 부족**: Heap 크기 제한, 필요시 disk-backed heap 사용
- **부분 실패**: 일부 partition merge 실패 시 cleanup 후 재시도
- **출력 파일 검증**: 최종 파일 크기 및 레코드 수 검증

---

## 8. 전체 End-to-End 시퀀스 (요약)

```
Master              Worker1              Worker2
  |                    |                    |
  | ========== Phase 1: Registration =========
  |<--RegisterWorker---|                    |
  |<--RegisterWorker-------------------     |
  |                    |                    |
  | ========== Phase 2: Sampling =============
  |--StartSampling---->|                    |
  |--StartSampling------------------------>|
  |<--SubmitSample-----|                    |
  |<--SubmitSample--------------------------|
  |<--NotifyPhaseComplete--|                |
  |<--NotifyPhaseComplete------------------|
  |                    |                    |
  | [Compute boundaries & shuffleMap]       |
  |                    |                    |
  | ========== Phase 3: Config ==============
  |--ConfigurePartitions->|                 |
  |--ConfigurePartitions----------------->|
  |                    |                    |
  | ========== Phase 4: Sort & Partition ====
  |                [Sort locally]      [Sort locally]
  |                [Create partitions] [Create partitions]
  |<--NotifyPhaseComplete--|                |
  |<--NotifyPhaseComplete------------------|
  |                    |                    |
  | ========== Phase 5: Shuffle =============
  |--StartShuffle----->|                    |
  |--StartShuffle----------------------------->|
  |                    |--ReceivePartition-->|
  |                    |<------Ack-----------|
  |<--NotifyPhaseComplete--|                |
  |<--NotifyPhaseComplete------------------|
  |                    |                    |
  | ========== Phase 6: Merge ===============
  |--StartMerge------->|                    |
  |--StartMerge------------------------------>|
  |                [K-way merge]        [K-way merge]
  |<--NotifyPhaseComplete--|                |
  |<--NotifyPhaseComplete------------------|
  |                    |                    |
  | [Output worker addresses]               |
  | DONE               |                    |
```

---

## 9. 타이밍 및 Timeout 요약표

| Phase | Operation | Timeout | Retry | Failure Action |
|-------|-----------|---------|-------|----------------|
| 1 | Worker Registration | 5 min (total) | N/A | Master exits |
| 1 | RegisterWorker RPC | 5 sec | 3 times | Worker exits |
| 2 | StartSampling broadcast | 30 sec | No retry | Master logs error |
| 2 | Worker sampling | 10 min | N/A | Worker → Failed |
| 2 | SubmitSample RPC | 10 sec | 3 times | Worker → Failed |
| 3 | ConfigurePartitions RPC | 30 sec | 3 times | Worker → Failed |
| 4 | Local sorting | 30 min | N/A | Worker → Failed |
| 4 | NotifyPhaseComplete | 10 sec | 3 times | Worker → Failed |
| 5 | StartShuffle broadcast | 30 sec | No retry | Master logs error |
| 5 | ReceivePartition RPC | 30 min | 3 times | Worker → Failed |
| 5 | Per-chunk timeout | 30 sec | Resume | Connection retry |
| 6 | StartMerge broadcast | 30 sec | No retry | Master logs error |
| 6 | K-way merge | 60 min | N/A | Worker → Failed |

---

## 10. 에러 코드 및 처리

### 10.1 gRPC Status 코드 사용

```scala
import io.grpc.Status
import io.grpc.StatusRuntimeException

// Worker registration errors
case object WorkerLimitReached extends Exception("Worker limit reached")
  // → Status.RESOURCE_EXHAUSTED

case object InvalidConfiguration extends Exception("Invalid configuration")
  // → Status.INVALID_ARGUMENT

case object NetworkTimeout extends Exception("Network timeout")
  // → Status.DEADLINE_EXCEEDED

case object WorkerNotFound extends Exception("Worker not found")
  // → Status.NOT_FOUND

case object InternalError extends Exception("Internal error")
  // → Status.INTERNAL

// Phase-specific errors
case object SamplingFailed extends Exception("Sampling failed")
  // → Status.ABORTED

case object SortingFailed extends Exception("Sorting failed")
  // → Status.ABORTED

case object ShuffleFailed extends Exception("Shuffle transfer failed")
  // → Status.DATA_LOSS

case object MergeFailed extends Exception("Merge failed")
  // → Status.ABORTED
```

### 10.2 에러 전파 및 복구

```scala
class WorkerNode {
  private def handleRpcError(ex: Throwable, operation: String): Unit = {
    ex match {
      case e: StatusRuntimeException =>
        e.getStatus.getCode match {
          case Status.Code.DEADLINE_EXCEEDED =>
            logger.warn(s"$operation timed out, retrying...")
            // Retry logic

          case Status.Code.UNAVAILABLE =>
            logger.error(s"$operation failed: target unavailable")
            stateMachine.transition(Failed, s"Target unavailable: $operation")

          case Status.Code.INVALID_ARGUMENT =>
            logger.error(s"$operation failed: invalid argument")
            stateMachine.transition(Failed, s"Invalid argument: $operation")

          case _ =>
            logger.error(s"$operation failed: ${e.getStatus}")
            stateMachine.transition(Failed, s"RPC error: $operation")
        }

      case e: Exception =>
        logger.error(s"$operation failed with exception: $e")
        stateMachine.transition(Failed, s"Exception: $operation")
    }
  }
}
```

---

## 11. Streaming 최적화 전략

### 11.1 Flow Control

```scala
class StreamingPartitionSender {
  private val maxInFlightChunks = 10
  private val inFlightSemaphore = new Semaphore(maxInFlightChunks)

  def sendWithFlowControl(chunks: Iterator[PartitionFile],
                          requestObserver: StreamObserver[PartitionFile]): Unit = {
    chunks.foreach { chunk =>
      inFlightSemaphore.acquire()  // Block if too many in-flight

      requestObserver.onNext(chunk)

      // Release when ack received (simplified)
      Future {
        Thread.sleep(10)  // Simulated ack delay
        inFlightSemaphore.release()
      }
    }
  }
}
```

### 11.2 Adaptive Chunk Sizing

```scala
class AdaptiveChunkSizer {
  private var currentChunkSize = 4 * 1024 * 1024  // Start with 4MB
  private val minChunkSize = 1 * 1024 * 1024
  private val maxChunkSize = 16 * 1024 * 1024

  def adjustChunkSize(latencyMs: Long, throughputMBps: Double): Unit = {
    if (latencyMs > 1000 && throughputMBps < 10) {
      // High latency, low throughput → decrease chunk size
      currentChunkSize = math.max(minChunkSize, currentChunkSize / 2)
      logger.info(s"Decreased chunk size to $currentChunkSize bytes")
    } else if (latencyMs < 100 && throughputMBps > 50) {
      // Low latency, high throughput → increase chunk size
      currentChunkSize = math.min(maxChunkSize, currentChunkSize * 2)
      logger.info(s"Increased chunk size to $currentChunkSize bytes")
    }
  }

  def getChunkSize: Int = currentChunkSize
}
```

---

## 12. 모니터링 및 로깅

### 12.1 gRPC Interceptor

```scala
import io.grpc._

class LoggingInterceptor extends ServerInterceptor {
  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] = {
    val methodName = call.getMethodDescriptor.getFullMethodName
    val startTime = System.currentTimeMillis()

    logger.info(s"[→] Received RPC: $methodName")

    val listener = next.startCall(call, headers)

    new ForwardingServerCallListener.SimpleForwardingServerCallListener[ReqT](listener) {
      override def onComplete(): Unit = {
        val duration = System.currentTimeMillis() - startTime
        logger.info(s"[✓] Completed RPC: $methodName (${duration}ms)")
        super.onComplete()
      }

      override def onCancel(): Unit = {
        logger.warn(s"[✗] Cancelled RPC: $methodName")
        super.onCancel()
      }
    }
  }
}
```

### 12.2 Metrics 수집

```scala
case class RpcMetrics(
  methodName: String,
  startTime: Long,
  endTime: Long,
  status: Status,
  bytesTransferred: Long
)

class MetricsCollector {
  private val metrics = new ConcurrentLinkedQueue[RpcMetrics]()

  def recordRpc(methodName: String,
                duration: Long,
                status: Status,
                bytes: Long): Unit = {
    metrics.add(RpcMetrics(
      methodName = methodName,
      startTime = System.currentTimeMillis() - duration,
      endTime = System.currentTimeMillis(),
      status = status,
      bytesTransferred = bytes
    ))
  }

  def generateReport(): String = {
    val grouped = metrics.asScala.groupBy(_.methodName)

    val report = grouped.map { case (method, calls) =>
      val totalCalls = calls.size
      val successfulCalls = calls.count(_.status.isOk)
      val avgDuration = calls.map(m => m.endTime - m.startTime).sum / totalCalls
      val totalBytes = calls.map(_.bytesTransferred).sum

      s"$method: $successfulCalls/$totalCalls successful, " +
      s"avg ${avgDuration}ms, ${totalBytes / (1024 * 1024)}MB transferred"
    }.mkString("\n")

    report
  }
}
```

---

## 13. 구현 체크리스트

### Phase 1: Registration
- [ ] Master: RegisterWorker RPC 구현
- [ ] Master: CountDownLatch로 N개 Worker 대기
- [ ] Worker: RegisterWorker 호출 및 재시도 로직
- [ ] Worker: workerId 저장 및 검증

### Phase 2: Sampling
- [ ] Master: StartSampling broadcast 구현
- [ ] Master: SubmitSample RPC 구현
- [ ] Master: PhaseTracker로 PHASE_SAMPLING 완료 추적
- [ ] Worker: StartSampling RPC 핸들러
- [ ] Worker: ReservoirSampler 구현
- [ ] Worker: SubmitSample 호출

### Phase 3: Configuration
- [ ] Master: computeBoundaries() 구현
- [ ] Master: createShuffleMap() 구현
- [ ] Master: ConfigurePartitions broadcast 구현
- [ ] Worker: ConfigurePartitions RPC 핸들러
- [ ] Worker: 설정 검증 로직

### Phase 4: Sort & Partition
- [ ] Worker: ExternalSorter 구현
- [ ] Worker: Partitioner 구현
- [ ] Worker: NotifyPhaseComplete 호출
- [ ] Master: PHASE_SORTING 완료 추적

### Phase 5: Shuffle
- [ ] Master: StartShuffle broadcast 구현
- [ ] Master: PHASE_SHUFFLING 완료 추적
- [ ] Worker: StartShuffle RPC 핸들러
- [ ] Worker: CountDownLatch로 신호 대기
- [ ] Worker: sendPartitionFile() streaming 구현
- [ ] Worker: ReceivePartition RPC 핸들러
- [ ] Worker: 임시 파일 atomic rename

### Phase 6: Merge
- [ ] Master: StartMerge broadcast 구현
- [ ] Master: PHASE_MERGING 완료 추적
- [ ] Master: 최종 출력 (worker addresses)
- [ ] Worker: StartMerge RPC 핸들러
- [ ] Worker: KWayMerger 구현
- [ ] Worker: 최종 partition 파일 생성

### 공통
- [ ] gRPC interceptor 구현 (logging, metrics)
- [ ] 모든 RPC에 timeout 설정
- [ ] 재시도 로직 구현
- [ ] 에러 전파 및 복구 로직
- [ ] State machine 통합
- [ ] 종합 테스트

---

## 문서 완성도: 95%

**완료된 부분**:
- ✅ Protocol Buffers 완전한 정의
- ✅ 모든 Phase의 상세한 시퀀스 다이어그램
- ✅ Master 및 Worker 구현 코드 예제
- ✅ Streaming 구현 상세
- ✅ 에러 처리 및 재시도 로직
- ✅ 타이밍 및 Timeout 정책
- ✅ 모니터링 및 로깅
- ✅ 구현 체크리스트

**추가 고려사항** (Nice-to-have):
- [ ] gRPC compression 설정 (gzip)
- [ ] TLS/SSL 보안 연결 (production 환경)
- [ ] Load balancing (multiple Masters)
- [ ] Distributed tracing (OpenTelemetry)

**다음 문서**: `4-error-recovery.md`
