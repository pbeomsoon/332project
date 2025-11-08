# Phase Coordination Protocol

**작성일**: 2025-10-24
**목적**: Master가 모든 Worker의 Phase 진행을 조율하는 구체적 메커니즘 정의

---

## 1. 개요

### 1.1 문제 정의

분산 정렬 시스템은 5개 Phase로 구성되며, 각 Phase는 **모든 Worker가 완료해야** 다음 Phase로 진행할 수 있습니다.

```
Phase 0: Initialization
Phase 1: Sampling
Phase 2: Sort & Partition
Phase 3: Shuffle
Phase 4: Merge
```

**핵심 질문:**
- Worker 1이 Phase 1을 10초에 끝냈는데, Worker 2는 20초에 끝난다면?
- Worker 1은 대기해야 하나? 바로 Phase 2 시작?
- 누가 "모든 Worker 완료"를 감지하고 다음 Phase를 시작시키나?

### 1.2 해결 방법

**Master-Coordinated Approach** 채택:
- Master가 모든 Worker의 Phase 완료를 추적
- Master가 다음 Phase 시작 신호 전송
- Worker는 각 Phase 완료 후 **대기 상태**로 진입

---

## 2. Protocol Buffers 확장

### 2.1 새로운 RPC 정의

```protobuf
service MasterService {
  // 기존
  rpc RegisterWorker(WorkerInfo) returns (RegistrationResponse);
  rpc SendSample(SampleData) returns (Ack);
  rpc ReportCompletion(CompletionInfo) returns (Ack);

  // ⭐ 추가: Phase 완료 보고
  rpc NotifyPhaseComplete(PhaseCompleteRequest) returns (Ack);
}

message PhaseCompleteRequest {
  string worker_id = 1;
  WorkerPhase completed_phase = 2;
  double elapsed_seconds = 3;
}

enum WorkerPhase {
  PHASE_INITIALIZING = 0;
  PHASE_SAMPLING = 1;
  PHASE_SORTING = 2;
  PHASE_SHUFFLING = 3;
  PHASE_MERGING = 4;
  PHASE_COMPLETED = 5;
}

service WorkerService {
  // 기존
  rpc SetPartitionBoundaries(PartitionConfig) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);
  rpc GetStatus(StatusRequest) returns (WorkerStatus);

  // ⭐ 추가: Master가 다음 Phase 시작 명령
  rpc StartShuffle(ShuffleSignal) returns (Ack);
  rpc StartMerge(MergeSignal) returns (Ack);
}

message ShuffleSignal {
  string message = 1;  // "All workers completed sorting. Start shuffle."
}

message MergeSignal {
  string message = 1;  // "All workers completed shuffle. Start merge."
}
```

---

## 3. Master 구현

### 3.1 Phase Tracker

```scala
class PhaseTracker(numWorkers: Int) {
  private val phaseCompletions = new ConcurrentHashMap[WorkerPhase, AtomicInteger]()

  // 초기화
  WorkerPhase.values.foreach { phase =>
    phaseCompletions.put(phase, new AtomicInteger(0))
  }

  def recordCompletion(phase: WorkerPhase): Boolean = {
    val count = phaseCompletions.get(phase).incrementAndGet()
    val allComplete = (count == numWorkers)

    if (allComplete) {
      logger.info(s"All $numWorkers workers completed $phase")
    }

    allComplete
  }

  def resetPhase(phase: WorkerPhase): Unit = {
    phaseCompletions.get(phase).set(0)
  }

  def getCompletionCount(phase: WorkerPhase): Int = {
    phaseCompletions.get(phase).get()
  }
}
```

### 3.2 Master Service 구현

```scala
class MasterServer(numWorkers: Int, numPartitions: Int)
    extends MasterServiceGrpc.MasterServiceImplBase {

  private val phaseTracker = new PhaseTracker(numWorkers)
  private val workers = new ConcurrentHashMap[String, WorkerInfo]()

  // Phase 1 완료 감지
  override def sendSample(
      request: SampleData,
      responseObserver: StreamObserver[Ack]): Unit = {

    samples.put(request.getWorkerId, request)

    // 모든 샘플 수신 완료 체크
    if (samples.size() == numWorkers) {
      logger.info("All workers completed Phase 1: Sampling")

      // 파티션 경계 계산 및 브로드캐스트
      calculateAndBroadcastConfig()

      // Phase 2는 config 수신 즉시 자동 시작 (명시적 신호 불필요)
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  // ⭐ NEW: Phase 완료 보고 처리
  override def notifyPhaseComplete(
      request: PhaseCompleteRequest,
      responseObserver: StreamObserver[Ack]): Unit = {

    val workerId = request.getWorkerId
    val phase = request.getCompletedPhase

    logger.info(s"Worker $workerId completed $phase in ${request.getElapsedSeconds}s")

    val allComplete = phaseTracker.recordCompletion(phase)

    if (allComplete) {
      phase match {
        case WorkerPhase.PHASE_SORTING =>
          logger.info("All workers completed Phase 2: Sorting. Initiating Phase 3: Shuffle")
          broadcastShuffleStart()

        case WorkerPhase.PHASE_SHUFFLING =>
          logger.info("All workers completed Phase 3: Shuffle. Initiating Phase 4: Merge")
          broadcastMergeStart()

        case WorkerPhase.PHASE_MERGING =>
          logger.info("All workers completed Phase 4: Merge. Job finished!")
          printFinalOrdering()

        case _ =>
          // Sampling은 sendSample()에서 처리
          // Initializing은 registerWorker()에서 처리
      }
    }

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  private def broadcastShuffleStart(): Unit = {
    val signal = ShuffleSignal.newBuilder()
      .setMessage("All workers completed sorting. Start shuffle.")
      .build()

    workers.values().asScala.foreach { workerInfo =>
      val channel = createGrpcChannelWithRetry(workerInfo.getHost, workerInfo.getPort)
      val stub = WorkerServiceGrpc.newBlockingStub(channel)

      try {
        stub.startShuffle(signal)
        logger.info(s"Sent shuffle start signal to Worker ${workerInfo.getWorkerId}")
      } catch {
        case e: Exception =>
          logger.error(s"Failed to send shuffle start to Worker ${workerInfo.getWorkerId}: ${e.getMessage}")
      } finally {
        channel.shutdown()
      }
    }
  }

  private def broadcastMergeStart(): Unit = {
    val signal = MergeSignal.newBuilder()
      .setMessage("All workers completed shuffle. Start merge.")
      .build()

    workers.values().asScala.foreach { workerInfo =>
      val channel = createGrpcChannelWithRetry(workerInfo.getHost, workerInfo.getPort)
      val stub = WorkerServiceGrpc.newBlockingStub(channel)

      try {
        stub.startMerge(signal)
        logger.info(s"Sent merge start signal to Worker ${workerInfo.getWorkerId}")
      } catch {
        case e: Exception =>
          logger.error(s"Failed to send merge start to Worker ${workerInfo.getWorkerId}: ${e.getMessage}")
      } finally {
        channel.shutdown()
      }
    }
  }
}
```

---

## 4. Worker 구현

### 4.1 Phase 대기 메커니즘

```scala
class WorkerNode(config: WorkerConfig) {

  private val shuffleStartLatch = new CountDownLatch(1)
  private val mergeStartLatch = new CountDownLatch(1)

  def start(): Unit = {
    // Phase 0: Initialization
    registerWithMaster()

    // Phase 1: Sampling
    val samples = extractSample()
    sendSamplesToMaster(samples)

    // Phase 1 → 2 전환: PartitionConfig 수신 대기
    waitForPartitionConfig()

    // Phase 2: Sort & Partition
    val startSort = System.currentTimeMillis()
    sortAndPartition()
    val elapsedSort = (System.currentTimeMillis() - startSort) / 1000.0

    // Phase 2 완료 보고 및 Phase 3 신호 대기
    reportPhaseComplete(WorkerPhase.PHASE_SORTING, elapsedSort)
    waitForShuffleStart()  // ⭐ 대기

    // Phase 3: Shuffle
    val startShuffle = System.currentTimeMillis()
    shuffle()
    val elapsedShuffle = (System.currentTimeMillis() - startShuffle) / 1000.0

    // Phase 3 완료 보고 및 Phase 4 신호 대기
    reportPhaseComplete(WorkerPhase.PHASE_SHUFFLING, elapsedShuffle)
    waitForMergeStart()  // ⭐ 대기

    // Phase 4: Merge
    val startMerge = System.currentTimeMillis()
    merge()
    val elapsedMerge = (System.currentTimeMillis() - startMerge) / 1000.0

    // Phase 4 완료 보고
    reportPhaseComplete(WorkerPhase.PHASE_MERGING, elapsedMerge)

    logger.info("All phases completed successfully")
  }

  private def reportPhaseComplete(phase: WorkerPhase, elapsed: Double): Unit = {
    val channel = createGrpcChannelWithRetry(masterHost, masterPort)
    val stub = MasterServiceGrpc.newBlockingStub(channel)

    val request = PhaseCompleteRequest.newBuilder()
      .setWorkerId(workerId)
      .setCompletedPhase(phase)
      .setElapsedSeconds(elapsed)
      .build()

    stub.notifyPhaseComplete(request)
    logger.info(s"Reported completion of $phase (${elapsed}s)")

    channel.shutdown()
  }

  private def waitForShuffleStart(): Unit = {
    logger.info("Waiting for shuffle start signal from Master...")
    try {
      shuffleStartLatch.await(10, TimeUnit.MINUTES)
      logger.info("Received shuffle start signal")
    } catch {
      case e: InterruptedException =>
        logger.error("Timeout waiting for shuffle start signal")
        throw new RuntimeException("Shuffle start timeout", e)
    }
  }

  private def waitForMergeStart(): Unit = {
    logger.info("Waiting for merge start signal from Master...")
    try {
      mergeStartLatch.await(10, TimeUnit.MINUTES)
      logger.info("Received merge start signal")
    } catch {
      case e: InterruptedException =>
        logger.error("Timeout waiting for merge start signal")
        throw new RuntimeException("Merge start timeout", e)
    }
  }
}
```

### 4.2 Worker Service 구현

```scala
class WorkerServiceImpl(worker: WorkerNode)
    extends WorkerServiceGrpc.WorkerServiceImplBase {

  // ⭐ NEW: Master가 Shuffle 시작 신호 전송
  override def startShuffle(
      request: ShuffleSignal,
      responseObserver: StreamObserver[Ack]): Unit = {

    logger.info(s"Received shuffle start signal: ${request.getMessage}")
    worker.shuffleStartLatch.countDown()  // ⭐ Latch 해제

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }

  // ⭐ NEW: Master가 Merge 시작 신호 전송
  override def startMerge(
      request: MergeSignal,
      responseObserver: StreamObserver[Ack]): Unit = {

    logger.info(s"Received merge start signal: ${request.getMessage}")
    worker.mergeStartLatch.countDown()  // ⭐ Latch 해제

    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()
  }
}
```

---

## 5. Phase 전환 시퀀스

### 5.1 Phase 1 → Phase 2

```
┌────────┐          ┌────────┐          ┌────────┐
│Worker 1│          │ Master │          │Worker 2│
└───┬────┘          └───┬────┘          └───┬────┘
    │                   │                   │
    │ extractSample()   │                   │
    │                   │                   │
    │ SendSample        │                   │
    ├──────────────────>│                   │
    │                   │ (count: 1/2)      │
    │                   │                   │
    │                   │  extractSample()  │
    │                   │                   │
    │                   │  SendSample       │
    │                   │<──────────────────┤
    │                   │ (count: 2/2)      │
    │                   │                   │
    │                   │ calculateBoundaries()
    │                   │ createShuffleMap()│
    │                   │                   │
    │ SetPartitionConfig│                   │
    │<──────────────────┤                   │
    │                   │ SetPartitionConfig│
    │                   ├──────────────────>│
    │                   │                   │
    │ sortAndPartition()│                   │
    │ (Phase 2 자동 시작)│  sortAndPartition()
    │                   │  (Phase 2 자동 시작)
```

**특징:** Phase 1 → 2는 PartitionConfig 수신 즉시 자동 전환

---

### 5.2 Phase 2 → Phase 3

```
┌────────┐          ┌────────┐          ┌────────┐
│Worker 1│          │ Master │          │Worker 2│
└───┬────┘          └───┬────┘          └───┬────┘
    │                   │                   │
    │ sortAndPartition()│                   │
    │ (완료)            │                   │
    │                   │                   │
    │ NotifyPhaseComplete│                  │
    ├──────────────────>│                   │
    │ (PHASE_SORTING)   │ (count: 1/2)      │
    │                   │                   │
    │ waitForShuffleStart()                 │
    │ (BLOCKED)         │                   │
    │                   │  sortAndPartition()│
    │                   │  (완료)           │
    │                   │                   │
    │                   │  NotifyPhaseComplete
    │                   │<──────────────────┤
    │                   │  (PHASE_SORTING)  │
    │                   │ (count: 2/2 ✓)    │
    │                   │                   │
    │                   │ broadcastShuffleStart()
    │                   │                   │
    │ StartShuffle      │                   │
    │<──────────────────┤                   │
    │ (latch 해제)      │  StartShuffle     │
    │                   ├──────────────────>│
    │                   │  (latch 해제)     │
    │                   │                   │
    │ shuffle()         │                   │
    │ (Phase 3 시작)    │  shuffle()        │
    │                   │  (Phase 3 시작)   │
```

**특징:** 명시적 신호 대기 필요

---

### 5.3 Phase 3 → Phase 4

```
(Phase 2 → 3과 동일한 패턴)

Workers → NotifyPhaseComplete(PHASE_SHUFFLING)
Master → count == numWorkers?
Master → broadcastMergeStart()
Workers → StartMerge 수신 → merge() 시작
```

---

## 6. 타임아웃 처리

### 6.1 Master 타임아웃

```scala
class PhaseTracker(numWorkers: Int) {
  private val phaseTimeouts = Map(
    WorkerPhase.PHASE_SAMPLING -> 10.minutes,
    WorkerPhase.PHASE_SORTING -> 30.minutes,
    WorkerPhase.PHASE_SHUFFLING -> 20.minutes,
    WorkerPhase.PHASE_MERGING -> 15.minutes
  )

  def waitForPhaseCompletion(phase: WorkerPhase): Boolean = {
    val timeout = phaseTimeouts(phase)
    val deadline = System.currentTimeMillis() + timeout.toMillis

    while (getCompletionCount(phase) < numWorkers) {
      if (System.currentTimeMillis() > deadline) {
        logger.error(s"Timeout waiting for all workers to complete $phase")
        logger.error(s"Completed: ${getCompletionCount(phase)}/$numWorkers")
        return false
      }
      Thread.sleep(1000)
    }

    true
  }
}
```

### 6.2 Worker 타임아웃

```scala
private def waitForShuffleStart(): Unit = {
  logger.info("Waiting for shuffle start signal from Master...")
  val success = shuffleStartLatch.await(10, TimeUnit.MINUTES)

  if (!success) {
    logger.error("Timeout waiting for shuffle start signal")
    // 재시도 또는 Master에 문의
    requestShuffleStatus()
    throw new RuntimeException("Shuffle start timeout")
  }
}

private def requestShuffleStatus(): Unit = {
  // Master에게 현재 상태 문의
  val channel = createGrpcChannelWithRetry(masterHost, masterPort)
  val stub = MasterServiceGrpc.newBlockingStub(channel)

  // GetPhaseStatus RPC 추가 필요
  val status = stub.getPhaseStatus(PhaseStatusRequest.newBuilder().build())
  logger.info(s"Current phase status: ${status}")
}
```

---

## 7. 장애 시나리오

### 7.1 Worker 크래시 (Phase 2 진행 중)

```
상황:
- Worker 1: Phase 2 완료, Phase 3 대기 중
- Worker 2: Phase 2 진행 중 → CRASH
- Worker 3: Phase 2 완료, Phase 3 대기 중

Master 관점:
- PHASE_SORTING 완료 count: 2/3
- Worker 2로부터 보고 없음

Worker 2 재시작:
1. cleanupTemporaryFiles()
2. Phase 0부터 재시작
3. Phase 1: 샘플링 재수행
   → Master는 이미 샘플 받았으므로 무시 (또는 재계산)
4. Phase 2: 정렬 재수행
5. NotifyPhaseComplete(PHASE_SORTING) 전송
   → Master: count 3/3 달성
   → broadcastShuffleStart()
```

**해결책:**
```scala
// Master: 재등록된 Worker 감지
override def registerWorker(...): Unit = {
  if (workers.containsKey(workerId)) {
    logger.warn(s"Worker $workerId re-registered. Resetting phase tracker.")

    // 모든 Phase 카운터 리셋
    phaseTracker.resetAll()

    // 모든 Worker에게 재시작 신호 (선택적)
    broadcastRestart()
  }
}
```

---

## 8. 구현 체크리스트

### Protocol Buffers
- [ ] `NotifyPhaseComplete` RPC 추가
- [ ] `StartShuffle` RPC 추가
- [ ] `StartMerge` RPC 추가
- [ ] `PhaseCompleteRequest` 메시지 정의
- [ ] `WorkerPhase` enum 정의

### Master
- [ ] `PhaseTracker` 클래스 구현
- [ ] `notifyPhaseComplete()` 핸들러 구현
- [ ] `broadcastShuffleStart()` 구현
- [ ] `broadcastMergeStart()` 구현
- [ ] Phase 타임아웃 처리

### Worker
- [ ] `shuffleStartLatch` 추가
- [ ] `mergeStartLatch` 추가
- [ ] `reportPhaseComplete()` 구현
- [ ] `waitForShuffleStart()` 구현
- [ ] `waitForMergeStart()` 구현
- [ ] `startShuffle()` 핸들러 구현
- [ ] `startMerge()` 핸들러 구현

### 테스트
- [ ] Phase 전환 단위 테스트
- [ ] 타임아웃 테스트
- [ ] Worker 크래시 시나리오 테스트
- [ ] 3 Workers 통합 테스트

---

## 9. 참고 사항

### 9.1 왜 Latch를 사용하나?

**대안 1: Busy Waiting**
```scala
// 나쁜 예
while (!shuffleStarted) {
  Thread.sleep(100)  // CPU 낭비
}
```

**대안 2: CountDownLatch (채택)**
```scala
// 좋은 예
shuffleStartLatch.await()  // 효율적 대기
```

**이유:**
- 효율적 CPU 사용
- 타임아웃 지원
- Thread-safe

### 9.2 왜 Phase 1→2는 자동, 2→3은 신호?

**Phase 1→2:**
- PartitionConfig 수신 = 작업 시작 가능
- 모든 Worker가 동일한 Config 받으면 바로 시작 가능

**Phase 2→3:**
- Worker A가 Shuffle 시작하려면 Worker B의 파티션 파일 필요
- Worker B가 Phase 2 완료 전에는 파일 없음
- → 명시적 동기화 필요

---

**문서 버전:** 1.0
**최종 수정:** 2025-10-24
**다음 문서:** [2-worker-state-machine.md](./2-worker-state-machine.md)
