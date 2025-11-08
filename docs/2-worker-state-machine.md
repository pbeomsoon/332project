# Worker State Machine

**작성일**: 2025-10-24
**목적**: Worker의 내부 상태 관리 및 전환 로직 정의

---

## 1. 개요

Worker는 복잡한 비동기 작업을 수행하며, 각 단계에서 적절한 상태 관리가 필요합니다.

### 1.1 왜 State Machine이 필요한가?

**문제:**
```scala
// Bad: 상태 없이 순차 실행만
def start(): Unit = {
  registerWithMaster()
  extractSample()
  sortAndPartition()  // 만약 여기서 에러?
  shuffle()           // 재시작 시 어디부터?
  merge()
}
```

**해결:**
```scala
// Good: 명확한 상태 관리
sealed trait WorkerState
currentState = Initializing

try {
  currentState = Sampling
  extractSample()

  currentState = WaitingForConfig
  waitForConfig()

  // ...
} catch {
  case e: Exception =>
    logger.error(s"Failed in state $currentState")
    // 상태 기반 복구
}
```

---

## 2. 상태 정의

### 2.1 State Enum

```scala
sealed trait WorkerState {
  def isTerminal: Boolean = false
  def canTransitionTo(next: WorkerState): Boolean
}

case object Initializing extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Sampling => true
    case Failed => true
    case _ => false
  }
}

case object Sampling extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case WaitingForPartitionConfig => true
    case Failed => true
    case _ => false
  }
}

case object WaitingForPartitionConfig extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Sorting => true
    case Failed => true
    case _ => false
  }
}

case object Sorting extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case WaitingForShuffleSignal => true
    case Failed => true
    case _ => false
  }
}

case object WaitingForShuffleSignal extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Shuffling => true
    case Failed => true
    case _ => false
  }
}

case object Shuffling extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case WaitingForMergeSignal => true
    case Failed => true
    case _ => false
  }
}

case object WaitingForMergeSignal extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Merging => true
    case Failed => true
    case _ => false
  }
}

case object Merging extends WorkerState {
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Completed => true
    case Failed => true
    case _ => false
  }
}

case object Completed extends WorkerState {
  override def isTerminal: Boolean = true
  override def canTransitionTo(next: WorkerState): Boolean = false
}

case object Failed extends WorkerState {
  override def isTerminal: Boolean = true
  override def canTransitionTo(next: WorkerState): Boolean = next match {
    case Initializing => true  // 재시작 허용
    case _ => false
  }
}
```

---

## 3. 상태 전환 다이어그램

### 3.1 정상 흐름

```
Initializing
    ↓ (register success)
Sampling
    ↓ (sample sent)
WaitingForPartitionConfig
    ↓ (config received)
Sorting
    ↓ (sorting complete)
WaitingForShuffleSignal
    ↓ (signal received)
Shuffling
    ↓ (shuffle complete)
WaitingForMergeSignal
    ↓ (signal received)
Merging
    ↓ (merge complete)
Completed
```

### 3.2 실패 및 복구

```
Any State
    ↓ (exception)
Failed
    ↓ (restart)
Initializing
    ↓ (retry from beginning)
...
```

---

## 4. WorkerStateMachine 클래스

### 4.1 핵심 구현

```scala
class WorkerStateMachine {
  private val currentState = new AtomicReference[WorkerState](Initializing)
  private val stateHistory = new ConcurrentLinkedQueue[StateTransition]()

  case class StateTransition(
    from: WorkerState,
    to: WorkerState,
    timestamp: Long,
    reason: String
  )

  def getState: WorkerState = currentState.get()

  def transition(to: WorkerState, reason: String): Unit = {
    val from = currentState.get()

    // 전환 가능 여부 검증
    if (!from.canTransitionTo(to)) {
      throw new IllegalStateException(
        s"Invalid state transition: $from -> $to"
      )
    }

    // 상태 전환
    currentState.set(to)

    // 히스토리 기록
    val transition = StateTransition(from, to, System.currentTimeMillis(), reason)
    stateHistory.add(transition)

    logger.info(s"State transition: $from -> $to ($reason)")
  }

  def requireState(expected: WorkerState): Unit = {
    val current = currentState.get()
    if (current != expected) {
      throw new IllegalStateException(
        s"Expected state $expected but was $current"
      )
    }
  }

  def isInState(states: WorkerState*): Boolean = {
    states.contains(currentState.get())
  }

  def getHistory: List[StateTransition] = {
    stateHistory.asScala.toList
  }

  def printHistory(): Unit = {
    println("=== State Transition History ===")
    stateHistory.asScala.foreach { t =>
      val elapsed = t.timestamp - stateHistory.peek().timestamp
      println(f"[+${elapsed / 1000.0}%.1fs] ${t.from} -> ${t.to}: ${t.reason}")
    }
  }
}
```

---

## 5. Worker 구현 (State Machine 통합)

### 5.1 WorkerNode with State Machine

```scala
class WorkerNode(config: WorkerConfig) {
  private val stateMachine = new WorkerStateMachine()
  private val shuffleStartLatch = new CountDownLatch(1)
  private val mergeStartLatch = new CountDownLatch(1)

  def start(): Unit = {
    try {
      // Phase 0: Initialization
      stateMachine.transition(Sampling, "Starting sampling phase")
      registerWithMaster()

      // Phase 1: Sampling
      val samples = extractSample()
      sendSamplesToMaster(samples)

      stateMachine.transition(WaitingForPartitionConfig, "Sampling complete, waiting for config")
      waitForPartitionConfig()

      // Phase 2: Sort & Partition
      stateMachine.transition(Sorting, "Config received, starting sort")
      val startSort = System.currentTimeMillis()
      sortAndPartition()
      val elapsedSort = (System.currentTimeMillis() - startSort) / 1000.0

      reportPhaseComplete(WorkerPhase.PHASE_SORTING, elapsedSort)
      stateMachine.transition(WaitingForShuffleSignal, "Sorting complete, waiting for signal")
      waitForShuffleStart()

      // Phase 3: Shuffle
      stateMachine.transition(Shuffling, "Signal received, starting shuffle")
      val startShuffle = System.currentTimeMillis()
      shuffle()
      val elapsedShuffle = (System.currentTimeMillis() - startShuffle) / 1000.0

      reportPhaseComplete(WorkerPhase.PHASE_SHUFFLING, elapsedShuffle)
      stateMachine.transition(WaitingForMergeSignal, "Shuffle complete, waiting for signal")
      waitForMergeStart()

      // Phase 4: Merge
      stateMachine.transition(Merging, "Signal received, starting merge")
      val startMerge = System.currentTimeMillis()
      merge()
      val elapsedMerge = (System.currentTimeMillis() - startMerge) / 1000.0

      reportPhaseComplete(WorkerPhase.PHASE_MERGING, elapsedMerge)
      stateMachine.transition(Completed, "All phases completed successfully")

      logger.info("Worker completed successfully")
      stateMachine.printHistory()

    } catch {
      case e: Exception =>
        logger.error(s"Worker failed in state ${stateMachine.getState}: ${e.getMessage}", e)
        stateMachine.transition(Failed, s"Exception: ${e.getMessage}")
        stateMachine.printHistory()

        // 정리
        cleanup()
        throw e
    }
  }

  // 상태 기반 재시작 가능 여부 판단
  def canRestart: Boolean = {
    stateMachine.getState match {
      case Failed => true
      case Completed => false
      case _ => false  // 진행 중인 상태에서는 재시작 불가
    }
  }

  // 상태 기반 정리
  private def cleanup(): Unit = {
    stateMachine.getState match {
      case Sorting | WaitingForShuffleSignal =>
        // Phase 2 중 실패: 정렬된 청크 삭제
        cleanupSortedChunks()
        cleanupLocalPartitions()

      case Shuffling | WaitingForMergeSignal =>
        // Phase 3 중 실패: 수신한 파티션 삭제
        cleanupReceivedPartitions()

      case Merging =>
        // Phase 4 중 실패: 출력 파일 삭제
        cleanupOutputFiles()

      case _ =>
        // 기타: 전체 임시 파일 삭제
        cleanupAllTemporaryFiles()
    }
  }
}
```

---

## 6. 상태 기반 RPC 처리

### 6.1 SetPartitionBoundaries RPC

```scala
class WorkerServiceImpl(worker: WorkerNode)
    extends WorkerServiceGrpc.WorkerServiceImplBase {

  override def setPartitionBoundaries(
      request: PartitionConfig,
      responseObserver: StreamObserver[Ack]): Unit = {

    // 상태 검증
    if (!worker.stateMachine.isInState(WaitingForPartitionConfig)) {
      val error = s"Cannot set partition config in state ${worker.stateMachine.getState}"
      logger.error(error)

      responseObserver.onNext(
        Ack.newBuilder()
          .setSuccess(false)
          .setMessage(error)
          .build()
      )
      responseObserver.onCompleted()
      return
    }

    // Config 저장
    worker.setPartitionConfig(request)

    // 성공 응답
    responseObserver.onNext(Ack.newBuilder().setSuccess(true).build())
    responseObserver.onCompleted()

    logger.info("Partition config set successfully")
  }
}
```

### 6.2 ShuffleData RPC (수신)

```scala
override def shuffleData(
    responseObserver: StreamObserver[ShuffleAck]):
    StreamObserver[ShuffleDataChunk] = {

  new StreamObserver[ShuffleDataChunk] {
    override def onNext(chunk: ShuffleDataChunk): Unit = {
      // 상태 검증
      if (!worker.stateMachine.isInState(Shuffling, WaitingForMergeSignal)) {
        logger.warn(s"Received shuffle data in wrong state: ${worker.stateMachine.getState}")
        // Phase 2 재시작 시나리오에서 수신 가능
      }

      // 데이터 처리
      processShuffleChunk(chunk)
    }

    override def onCompleted(): Unit = {
      responseObserver.onNext(
        ShuffleAck.newBuilder().setSuccess(true).build()
      )
      responseObserver.onCompleted()
    }
  }
}
```

---

## 7. 상태 모니터링

### 7.1 GetStatus RPC 구현

```scala
override def getStatus(
    request: StatusRequest,
    responseObserver: StreamObserver[WorkerStatus]): Unit = {

  val state = worker.stateMachine.getState
  val (phase, progress, message) = state match {
    case Initializing =>
      (WorkerStatus.Phase.INITIALIZING, 0.0, "Initializing worker")

    case Sampling =>
      (WorkerStatus.Phase.SAMPLING, 10.0, "Extracting samples")

    case WaitingForPartitionConfig =>
      (WorkerStatus.Phase.SAMPLING, 15.0, "Waiting for partition config")

    case Sorting =>
      val progress = worker.getSortProgress()  // 0-100
      (WorkerStatus.Phase.SORTING, 15.0 + progress * 0.4, s"Sorting: ${progress}%")

    case WaitingForShuffleSignal =>
      (WorkerStatus.Phase.SORTING, 55.0, "Sorting complete, waiting for shuffle signal")

    case Shuffling =>
      val progress = worker.getShuffleProgress()
      (WorkerStatus.Phase.SHUFFLING, 55.0 + progress * 0.25, s"Shuffling: ${progress}%")

    case WaitingForMergeSignal =>
      (WorkerStatus.Phase.SHUFFLING, 80.0, "Shuffle complete, waiting for merge signal")

    case Merging =>
      val progress = worker.getMergeProgress()
      (WorkerStatus.Phase.MERGING, 80.0 + progress * 0.2, s"Merging: ${progress}%")

    case Completed =>
      (WorkerStatus.Phase.COMPLETED, 100.0, "All phases completed")

    case Failed =>
      (WorkerStatus.Phase.FAILED, 0.0, "Worker failed")
  }

  val status = WorkerStatus.newBuilder()
    .setCurrentPhase(phase)
    .setProgressPercentage(progress)
    .setMessage(message)
    .build()

  responseObserver.onNext(status)
  responseObserver.onCompleted()
}
```

---

## 8. 재시작 시나리오

### 8.1 State 기반 재시작 판단

```scala
object WorkerMain {
  def main(args: Array[String]): Unit = {
    val config = CommandLineParser.parseWorkerArgs(args)
    val worker = new WorkerNode(config)

    // 이전 상태 복구 시도 (선택적)
    val previousState = loadPreviousState(config.workerId)

    previousState match {
      case Some(Failed) =>
        logger.info("Worker previously failed. Cleaning up and restarting...")
        worker.cleanup()
        worker.start()

      case Some(Completed) =>
        logger.info("Worker already completed. Nothing to do.")
        System.exit(0)

      case Some(state) if !state.isTerminal =>
        logger.warn(s"Worker was in non-terminal state: $state. Cleaning up and restarting...")
        worker.cleanup()
        worker.start()

      case None =>
        logger.info("First run. Starting worker...")
        worker.start()
    }
  }

  private def loadPreviousState(workerId: String): Option[WorkerState] = {
    val stateFile = new File(s"/tmp/worker_${workerId}_state")
    if (stateFile.exists()) {
      try {
        val state = Source.fromFile(stateFile).mkString.trim
        Some(parseState(state))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to load previous state: ${e.getMessage}")
          None
      }
    } else {
      None
    }
  }
}
```

### 8.2 State 저장

```scala
class WorkerStateMachine {
  def transition(to: WorkerState, reason: String): Unit = {
    val from = currentState.get()

    if (!from.canTransitionTo(to)) {
      throw new IllegalStateException(s"Invalid state transition: $from -> $to")
    }

    currentState.set(to)
    stateHistory.add(StateTransition(from, to, System.currentTimeMillis(), reason))

    logger.info(s"State transition: $from -> $to ($reason)")

    // ⭐ 상태 저장 (장애 복구용)
    saveState(to)
  }

  private def saveState(state: WorkerState): Unit = {
    try {
      val stateFile = new File(s"/tmp/worker_${worker.workerId}_state")
      val writer = new PrintWriter(stateFile)
      writer.println(state.toString)
      writer.close()
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save state: ${e.getMessage}")
    }
  }
}
```

---

## 9. 상태 기반 메트릭

### 9.1 Phase Duration 측정

```scala
class WorkerStateMachine {
  case class PhaseDuration(
    phase: String,
    startTime: Long,
    endTime: Option[Long],
    durationMs: Option[Long]
  )

  private val phaseDurations = mutable.Map[String, PhaseDuration]()

  def transition(to: WorkerState, reason: String): Unit = {
    val from = currentState.get()
    val now = System.currentTimeMillis()

    // 이전 Phase 종료
    from match {
      case Sampling | Sorting | Shuffling | Merging =>
        phaseDurations.get(from.toString).foreach { pd =>
          phaseDurations(from.toString) = pd.copy(
            endTime = Some(now),
            durationMs = Some(now - pd.startTime)
          )
        }
      case _ =>
    }

    // 새 Phase 시작
    to match {
      case Sampling | Sorting | Shuffling | Merging =>
        phaseDurations(to.toString) = PhaseDuration(
          phase = to.toString,
          startTime = now,
          endTime = None,
          durationMs = None
        )
      case _ =>
    }

    // 상태 전환
    currentState.set(to)
    stateHistory.add(StateTransition(from, to, now, reason))

    logger.info(s"State transition: $from -> $to ($reason)")
    saveState(to)
  }

  def printPhaseDurations(): Unit = {
    println("=== Phase Durations ===")
    phaseDurations.values.toList.sortBy(_.startTime).foreach { pd =>
      val duration = pd.durationMs.map(ms => f"${ms / 1000.0}%.2fs").getOrElse("In progress")
      println(s"${pd.phase}: $duration")
    }
  }
}
```

---

## 10. 테스트

### 10.1 State Transition 테스트

```scala
class WorkerStateMachineTest extends AnyFlatSpec with Matchers {

  "WorkerStateMachine" should "allow valid transitions" in {
    val sm = new WorkerStateMachine()

    sm.getState shouldEqual Initializing
    sm.transition(Sampling, "test")
    sm.getState shouldEqual Sampling

    sm.transition(WaitingForPartitionConfig, "test")
    sm.getState shouldEqual WaitingForPartitionConfig
  }

  it should "reject invalid transitions" in {
    val sm = new WorkerStateMachine()

    sm.transition(Sampling, "test")

    assertThrows[IllegalStateException] {
      sm.transition(Shuffling, "test")  // Sampling -> Shuffling: 불가능
    }
  }

  it should "track state history" in {
    val sm = new WorkerStateMachine()

    sm.transition(Sampling, "reason1")
    sm.transition(WaitingForPartitionConfig, "reason2")
    sm.transition(Sorting, "reason3")

    val history = sm.getHistory
    history should have size 3
    history(0).from shouldEqual Initializing
    history(0).to shouldEqual Sampling
    history(0).reason shouldEqual "reason1"
  }

  it should "allow Failed -> Initializing for restart" in {
    val sm = new WorkerStateMachine()

    sm.transition(Sampling, "test")
    sm.transition(Failed, "error")
    sm.getState shouldEqual Failed

    // 재시작 허용
    sm.transition(Initializing, "restart")
    sm.getState shouldEqual Initializing
  }
}
```

---

## 11. 구현 체크리스트

### State Machine
- [ ] `WorkerState` sealed trait 정의
- [ ] 각 상태별 `canTransitionTo()` 구현
- [ ] `WorkerStateMachine` 클래스 구현
- [ ] State 저장/복구 로직
- [ ] State history 추적

### Worker 통합
- [ ] `WorkerNode`에 `stateMachine` 통합
- [ ] 각 Phase에서 `transition()` 호출
- [ ] State 기반 cleanup 로직
- [ ] State 기반 재시작 판단

### RPC 통합
- [ ] RPC 핸들러에서 상태 검증
- [ ] `GetStatus` RPC 구현
- [ ] State 기반 진행률 계산

### 모니터링
- [ ] Phase duration 측정
- [ ] State history 출력
- [ ] 메트릭 수집

### 테스트
- [ ] State transition 단위 테스트
- [ ] Invalid transition 테스트
- [ ] 재시작 시나리오 테스트

---

**문서 버전:** 1.0
**최종 수정:** 2025-10-24
**이전 문서:** [1-phase-coordination.md](./1-phase-coordination.md)
**다음 문서:** [3-grpc-sequences.md](./3-grpc-sequences.md)
