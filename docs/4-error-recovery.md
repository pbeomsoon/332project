# Error Recovery & Fault Tolerance

**문서 목적**: 분산 정렬 시스템의 모든 장애 시나리오와 복구 전략을 상세히 문서화

**참조 문서**:
- `2025-10-24_plan_ver3.md` - 전체 시스템 설계
- `2-worker-state-machine.md` - Worker 상태 머신
- `3-grpc-sequences.md` - gRPC 호출 시퀀스

---

## 1. Failure Taxonomy (장애 분류)

### 1.1 Network Failures
- **Transient Network Failure**: 일시적인 네트워크 지연/패킷 손실
- **Network Partition**: Worker와 Master 간 연결 완전 단절
- **Slow Network**: 극심한 네트워크 지연 (timeout 근처)

### 1.2 Process Failures
- **Worker Crash**: Worker 프로세스 비정상 종료
- **Master Crash**: Master 프로세스 비정상 종료
- **Worker Hang**: Worker가 응답 없이 무한 대기
- **Out-of-Memory**: Worker 메모리 부족으로 인한 종료

### 1.3 Data Failures
- **Corrupt Input File**: 입력 파일 손상
- **Disk Full**: 디스크 공간 부족
- **Partial Write**: 파일 쓰기 중 실패 (불완전한 파일)
- **Missing Partition**: Shuffle 시 partition 파일 누락

### 1.4 Logic Failures
- **Invalid State Transition**: Worker가 잘못된 상태 전환 시도
- **Protocol Violation**: gRPC 호출 순서 위반
- **Configuration Mismatch**: Worker들 간 설정 불일치
- **Boundary Calculation Error**: 잘못된 partition boundary

---

## 2. Failure Detection (장애 감지)

### 2.1 Master-side Detection

```scala
class MasterNode {
  // Health check mechanism
  private val workerHealthChecker = new ScheduledThreadPoolExecutor(1)
  private val workerLastSeen = new ConcurrentHashMap[String, Long]()

  def startHealthChecking(): Unit = {
    workerHealthChecker.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = checkWorkerHealth()
      },
      0, 10, TimeUnit.SECONDS
    )
  }

  private def checkWorkerHealth(): Unit = {
    val now = System.currentTimeMillis()
    val staleThreshold = 60 * 1000  // 60 seconds

    registeredWorkers.asScala.foreach { worker =>
      val lastSeen = workerLastSeen.getOrDefault(worker.workerId, now)
      val staleDuration = now - lastSeen

      if (staleDuration > staleThreshold) {
        logger.warn(s"Worker ${worker.workerId} appears to be dead " +
          s"(last seen ${staleDuration / 1000}s ago)")
        handleWorkerFailure(worker)
      }
    }
  }

  // Update last seen time when receiving any RPC from worker
  override def notifyPhaseComplete(request: PhaseCompleteRequest): Future[Ack] = {
    workerLastSeen.put(request.workerId, System.currentTimeMillis())
    // ... rest of implementation
  }

  override def submitSample(request: SampleData): Future[Ack] = {
    // Extract worker ID from metadata or request
    val workerId = extractWorkerIdFromContext()
    workerLastSeen.put(workerId, System.currentTimeMillis())
    // ... rest of implementation
  }
}
```

### 2.2 Worker-side Detection

```scala
class WorkerNode {
  // Heartbeat to master
  private val heartbeatExecutor = new ScheduledThreadPoolExecutor(1)

  def startHeartbeat(): Unit = {
    heartbeatExecutor.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = sendHeartbeat()
      },
      0, 30, TimeUnit.SECONDS
    )
  }

  private def sendHeartbeat(): Unit = {
    try {
      val request = HeartbeatRequest(
        workerId = workerId,
        currentState = stateMachine.getState.toString,
        timestamp = System.currentTimeMillis()
      )

      val ack = masterStub.heartbeat(request)

      if (!ack.success) {
        logger.warn(s"Master rejected heartbeat: ${ack.message}")
      }
    } catch {
      case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.UNAVAILABLE =>
        logger.error("Master appears to be down!")
        handleMasterFailure()

      case ex: Exception =>
        logger.error(s"Heartbeat failed: $ex")
    }
  }
}
```

### 2.3 Timeout-based Detection

```scala
class TimeoutDetector {
  case class OperationTimeout(
    phase: WorkerPhase,
    timeoutMs: Long,
    action: () => Unit
  )

  private val timeouts = Map(
    WorkerPhase.PHASE_SAMPLING -> OperationTimeout(
      WorkerPhase.PHASE_SAMPLING,
      10 * 60 * 1000,  // 10 minutes
      () => handleSamplingTimeout()
    ),
    WorkerPhase.PHASE_SORTING -> OperationTimeout(
      WorkerPhase.PHASE_SORTING,
      30 * 60 * 1000,  // 30 minutes
      () => handleSortingTimeout()
    ),
    WorkerPhase.PHASE_SHUFFLING -> OperationTimeout(
      WorkerPhase.PHASE_SHUFFLING,
      30 * 60 * 1000,  // 30 minutes
      () => handleShufflingTimeout()
    ),
    WorkerPhase.PHASE_MERGING -> OperationTimeout(
      WorkerPhase.PHASE_MERGING,
      60 * 60 * 1000,  // 60 minutes
      () => handleMergingTimeout()
    )
  )

  def startPhaseTimeout(phase: WorkerPhase): ScheduledFuture[_] = {
    val timeout = timeouts(phase)

    scheduler.schedule(
      new Runnable {
        override def run(): Unit = {
          logger.error(s"Phase $phase timed out after ${timeout.timeoutMs}ms")
          timeout.action()
        }
      },
      timeout.timeoutMs,
      TimeUnit.MILLISECONDS
    )
  }
}
```

---

## 3. Fault Tolerance Strategy (명확한 전략)

### 3.1 PDF 요구사항 해석

**PDF 원문**:
> "The system must be fault-tolerant, which means that if a worker crashes and restarts, the overall computation should still produce correct results."

### 3.2 구현 전략: Graceful Degradation with Job Restart

```
┌─────────────────────────────────────────────────────────┐
│ Fault Tolerance Strategy                                │
├─────────────────────────────────────────────────────────┤
│ Phase 1 (Registration):                                 │
│   ❌ Worker 부족 → Job 중단                              │
│   ⏱️  Timeout: 5분                                      │
│                                                         │
│ Phase 2 (Sampling):                                     │
│   ✅ >50% Worker 살아있음 → 계속 진행                    │
│   ✅ Boundary 재계산 (남은 sample 사용)                  │
│   ❌ <50% Worker → Job 중단                             │
│                                                         │
│ Phase 3 (Sorting):                                      │
│   ✅ >50% Worker 살아있음 → 계속 진행                    │
│   ✅ numPartitions = 남은 worker 수로 조정               │
│   ❌ <50% Worker → Job 중단                             │
│                                                         │
│ Phase 4 (Shuffle):                                      │
│   ❌ 어떤 Worker든 실패 → 전체 Job 중단                  │
│   📝 이유: Partition 데이터 유실, 복구 불가              │
│                                                         │
│ Phase 5 (Merge):                                        │
│   ❌ 어떤 Worker든 실패 → 전체 Job 중단                  │
│   📝 이유: 최종 output 불완전, 복구 불가                 │
│                                                         │
│ Master Crash:                                           │
│   ❌ 복구 불가 → 전체 Job 재시작                         │
│   📝 현재 설계: Master는 SPOF (Single Point of Failure)  │
└─────────────────────────────────────────────────────────┘
```

### 3.3 PDF 요구사항 해석: "Same Output Expected" 명확화

**PDF 원문 (page 19)**:
> "If a worker crashes and restarts, the new worker should generate the **same output expected of the initial worker**."

**핵심 질문**: 이 문장의 정확한 의미는?

#### **Interpretation A: 최종 결과의 정확성** (우리의 현재 구현)

```
해석:
  "Same output" = 전체 distributed sort의 최종 결과가 정확해야 함
  = Worker restart 후에도 전체 job이 correct sorted output을 생성

구현:
  ✅ Worker crash → 전체 job restart
  ✅ 모든 worker가 처음부터 다시 시작
  ✅ 최종 partition.0, partition.1, ... 파일들은 정확하게 정렬됨

정당화:
  - "overall computation should still produce correct results" ← 전체 결과 강조
  - Worker가 어떤 partition을 담당하든, 최종 정렬 결과만 맞으면 됨
  - 실제 시스템: Hadoop 초기 버전도 JobTracker SPOF + job restart
```

#### **Interpretation B: Worker-Partition 일관성** (Advanced Option)

```
해석:
  "Same output" = 재시작된 worker가 원래 worker와 동일한 partition 생성
  = Worker W2가 crash → 재시작 시 여전히 partition.2를 담당해야 함

구현 요구사항:
  ❌ Job을 처음부터 재시작하면 worker ID가 바뀔 수 있음
  ❌ 새로운 worker ID → 다른 partition 할당
  ✅ Worker re-registration 필요 (같은 input/output dir → 같은 worker ID)

구현 방법:
  ✅ Worker 재시작 시 input/output dir로 identity 확인
  ✅ 동일한 worker ID 재할당
  ✅ 해당 worker가 담당했던 partition 재생성
```

#### **구현: Worker Re-registration (Interpretation B를 위한 선택사항)**

```scala
class MasterNode {
  // Worker identity tracking
  private case class WorkerIdentity(
    inputDirs: Seq[String],
    outputDir: String
  )

  private val workerIdentities = new ConcurrentHashMap[WorkerIdentity, String]()  // identity → workerId

  override def registerWorker(request: RegisterRequest): Future[RegisterResponse] = {
    synchronized {
      val identity = WorkerIdentity(
        inputDirs = request.inputDirectories.sorted,  // Canonical ordering
        outputDir = request.outputDirectory
      )

      // Check if this is a restarted worker
      val existingWorkerId = workerIdentities.get(identity)

      if (existingWorkerId != null) {
        // This worker is restarting with the same input/output directories
        logger.info(s"Worker $existingWorkerId restarting (detected by matching dirs)")

        // Find existing worker info
        val existingWorker = registeredWorkers.asScala.find(_.workerId == existingWorkerId)

        if (existingWorker.isDefined) {
          logger.info(s"Re-registering worker $existingWorkerId with same ID")

          // Update worker connection info (IP/port may have changed)
          val updatedWorker = existingWorker.get.copy(
            address = request.address,
            port = request.port
          )

          // Replace in registry
          registeredWorkers.remove(existingWorker.get)
          registeredWorkers.add(updatedWorker)

          // Reset worker state to re-join current phase
          resetWorkerState(existingWorkerId)

          return Future.successful(RegisterResponse(
            success = true,
            workerId = existingWorkerId,  // ✅ Same worker ID!
            totalWorkers = expectedWorkers,
            message = "Worker re-registered after restart"
          ))
        }
      }

      // New worker registration (normal case)
      val workerId = s"W${workerIdCounter.getAndIncrement()}"

      val workerInfo = WorkerInfo(
        workerId = workerId,
        address = request.address,
        port = request.port,
        inputDirectories = request.inputDirectories,
        outputDirectory = request.outputDirectory
      )

      registeredWorkers.add(workerInfo)
      workerIdentities.put(identity, workerId)
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

  private def resetWorkerState(workerId: String): Unit = {
    logger.info(s"Resetting state for restarted worker $workerId")

    // Remove from failure tracking
    failedWorkers.remove(workerId)

    // Reset phase completion tracking
    phaseTracker.resetWorker(workerId)

    // Worker will receive fresh StartPhase RPCs and re-execute from current phase
  }
}
```

#### **프로젝트 결정: Interpretation A 채택, B는 Optional**

```
┌────────────────────────────────────────────────────────────┐
│ Milestone 1-3: Interpretation A (전체 재시작)              │
│   ✅ 구현 단순                                              │
│   ✅ 정확성 보장                                            │
│   ✅ 디버깅 용이                                            │
│   ❌ Worker crash → 전체 job 손실                           │
│                                                            │
│ Milestone 4-5: Interpretation B 추가 (선택사항)            │
│   ✅ Worker re-registration 지원                           │
│   ✅ Partition consistency 유지                            │
│   ✅ Partial recovery 가능 (Shuffle output replication과  │
│       함께 사용 시)                                         │
│   ⚠️  복잡도 증가                                           │
└────────────────────────────────────────────────────────────┘
```

**PDF 요구사항 만족 여부**:

| Interpretation | "Correct Results" | "Same Output Expected" | 복잡도 | 구현 우선순위 |
|----------------|-------------------|------------------------|--------|--------------|
| **A (전체 재시작)** | ✅ Yes | ⚠️ Job-level yes, Worker-level unclear | Low | 🔴 High (M1-3) |
| **B (Re-registration)** | ✅ Yes | ✅ Yes (Worker-level) | High | 🟡 Medium (M4-5) |

**최종 판단**: Interpretation A가 PDF의 핵심 요구사항("correct results")을 충족하며, Interpretation B는 보다 엄격한 해석으로 향후 개선 시 추가 구현

### 3.4 Worker Restart 시나리오

#### **시나리오 A: Worker Crashes During Sampling**

```
Initial State:
  - 5 workers registered (W0, W1, W2, W3, W4)
  - W0, W1, W2 submitted samples
  - W3 crashes before submitting sample
  - W4 still sampling

Detection:
  1. Master: No heartbeat from W3 for 60s
  2. Master: Sampling phase timeout approaching (10 min)

Recovery Decision:
  - Alive workers: 4 out of 5 (80%)
  - Threshold: 50%
  - Decision: ✅ Continue with 4 workers

Recovery Actions:
  1. Master marks W3 as failed
  2. Wait for W4 to submit sample
  3. Recompute boundaries using 3 samples (W0, W1, W2)
  4. Set numPartitions = 4 (4 alive workers)
  5. Generate shuffleMap = {0→0, 1→1, 2→2, 3→3}
  6. Continue to Sorting phase with W0, W1, W2, W4

W3 Restarts:
  - Tries to re-register
  - Master rejects: "Job already in progress, registration closed"
  - W3 exits and waits for next job
```

#### **시나리오 B: Worker Crashes During Shuffle**

```
Initial State:
  - 4 workers (W0, W1, W2, W3) in shuffle phase
  - W1 is sending partition.1 to W2 (50% complete)
  - W1 crashes

Detection:
  1. W2: Partial file received, no completion signal
  2. W2: Receive timeout after 60s
  3. W2 cleans up temp file: partition.1.from_W1.tmp
  4. Master: No heartbeat from W1 for 60s

Recovery Decision:
  - Phase: Shuffle (Phase 4)
  - Critical data lost: partition.1 (only existed in W1's temp dir)
  - Other workers waiting for partition.1
  - Decision: ❌ Cannot continue

Recovery Actions:
  1. Master sends AbortRequest to all workers
  2. W0, W2, W3 cleanup temporary files
  3. W0, W2, W3 exit
  4. Job must be restarted from Phase 1

W1 Restarts:
  - Re-registers for new job (if master restarts)
  - Starts from Phase 1 with all other workers
```

### 3.5 정당화 (Why This Strategy?)

**Q: 왜 Shuffle/Merge에서는 복구 불가인가?**

A: **데이터 위치 문제**
```
Shuffle 중 Worker W1 crash:
  - W1이 만든 partition.1은 W1의 temp/ 디렉토리에만 존재
  - 다른 worker들은 partition.1을 가지고 있지 않음
  - partition.1을 재생성하려면:
      → Phase 1부터 다시 시작 필요
      → Sampling → Sorting → Partitioning을 다시 해야 함

결론: 부분 복구보다 전체 재시작이 더 간단하고 안전
```

**Q: 실제 분산 시스템은?**

A: **Lineage 또는 Replication 사용**
```
Spark:
  ✅ RDD lineage 추적
  ✅ 유실된 partition만 재계산
  ✅ 부모 RDD부터 재실행

Hadoop:
  ✅ Map output을 여러 곳에 복제
  ✅ 한 곳 실패해도 다른 곳에서 읽기

우리 프로젝트:
  ❌ Lineage tracking 복잡 (프로젝트 범위 초과)
  ❌ Replication은 Milestone 4-5에서 선택적 구현
  ✅ 전체 재시작 (단순, 안전, 구현 용이)
```

### 3.6 향후 개선 가능성

**Milestone 4-5에서 선택적 구현**:

```scala
// Option A: Shuffle Output Replication (간단한 버전)
class ShuffleManagerWithReplication {
  def sendPartitionWithBackup(partition: File, partitionId: Int): Unit = {
    val primaryWorker = getPrimaryWorker(partitionId)
    val backupWorker = getBackupWorker(partitionId)

    // Send to both workers
    Future.sequence(Seq(
      sendTo(primaryWorker, partition),
      sendTo(backupWorker, partition)
    ))
  }

  // Primary 실패 시 backup에서 가져오기
  def fetchPartition(partitionId: Int): File = {
    try {
      fetchFrom(primaryWorker, partitionId)
    } catch {
      case _: Exception =>
        logger.warn(s"Primary failed for partition $partitionId, using backup")
        fetchFrom(backupWorker, partitionId)
    }
  }
}
```

**Trade-off**:
- ✅ Shuffle 실패 시 복구 가능
- ❌ Network traffic 2배
- ❌ Disk space 2배 필요

---

## 4. Error Recovery Matrix

### 4.1 Master Perspective

| Failure Type | Detection | Recovery Strategy | Fallback |
|--------------|-----------|-------------------|----------|
| **Worker fails during Registration** | Timeout (5 min) | Wait for timeout, then abort | Restart entire job |
| **Worker fails during Sampling** | NotifyPhaseComplete timeout (10 min) | Continue with N-1 workers if >50% alive | Abort if <50% |
| **Worker fails during Sorting** | NotifyPhaseComplete timeout (30 min) | Continue with N-1 workers if >50% alive | Abort if <50% |
| **Worker fails during Shuffle** | NotifyPhaseComplete timeout (30 min) | **Cannot recover** - data partitions incomplete | Abort job |
| **Worker fails during Merge** | NotifyPhaseComplete timeout (60 min) | **Cannot recover** - final output incomplete | Abort job |
| **Master crashes** | N/A | **No recovery** - restart entire job | N/A |

### 4.2 Worker Perspective

| Failure Type | Detection | Recovery Strategy | Fallback |
|--------------|-----------|-------------------|----------|
| **Master unavailable** | Heartbeat failure | Wait 5 min, then exit | None |
| **RPC timeout (transient)** | gRPC deadline exceeded | Retry up to 3 times with exponential backoff | Fail after 3 retries |
| **Corrupt input file** | IOException during read | Skip file and log warning | Continue with other files |
| **Disk full (temp dir)** | IOException during write | Clean old temp files, retry once | Fail if still full |
| **Disk full (output dir)** | IOException during merge | **Cannot recover** | Fail immediately |
| **OOM during sorting** | OutOfMemoryError | Reduce chunk size, retry with smaller chunks | Fail after 3 retries |
| **Partition file missing** | FileNotFoundException during shuffle | Request re-send from source worker | Fail after 3 retries |
| **Network partition** | gRPC unavailable | Wait for network recovery (up to 5 min) | Fail if no recovery |

### 4.3 Data Failure Recovery

| Failure Type | Detection | Recovery Strategy | Data Integrity |
|--------------|-----------|-------------------|----------------|
| **Partial write (temp file)** | File size mismatch | Delete temp file, retry write | Safe - atomic rename not yet done |
| **Partial write (final file)** | File size mismatch after rename | **Data corruption** | Unsafe - need to restart from scratch |
| **Corrupt partition during shuffle** | Checksum mismatch (optional) | Request re-send from source | Safe - receiver deletes corrupt file |
| **Missing partition file** | File not found during merge | Request from source worker | Safe if source still has it |
| **Duplicate partition files** | Multiple files for same partition | Merge all, assuming idempotency | Safe - duplicates produce same result |

---

## 5. Recovery Implementations

### 5.1 RPC Retry with Exponential Backoff

```scala
class RetryableRpcClient {
  def withRetry[T](
    operation: => Future[T],
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    operationName: String
  ): Future[T] = {
    def attempt(retriesLeft: Int, delayMs: Long): Future[T] = {
      operation.recoverWith {
        case ex: StatusRuntimeException if retriesLeft > 0 =>
          ex.getStatus.getCode match {
            case Status.Code.UNAVAILABLE | Status.Code.DEADLINE_EXCEEDED =>
              logger.warn(s"$operationName failed (${ex.getStatus.getCode}), " +
                s"retrying in ${delayMs}ms ($retriesLeft retries left)")

              val promise = Promise[T]()
              scheduler.schedule(
                new Runnable {
                  override def run(): Unit = {
                    attempt(retriesLeft - 1, delayMs * 2).onComplete {
                      case Success(result) => promise.success(result)
                      case Failure(err) => promise.failure(err)
                    }
                  }
                },
                delayMs,
                TimeUnit.MILLISECONDS
              )
              promise.future

            case _ =>
              logger.error(s"$operationName failed with non-retryable error: ${ex.getStatus}")
              Future.failed(ex)
          }

        case ex: Exception =>
          logger.error(s"$operationName failed with exception: $ex")
          Future.failed(ex)
      }
    }

    attempt(maxRetries, initialDelayMs)
  }
}
```

**사용 예시**:
```scala
class WorkerNode {
  private val retryClient = new RetryableRpcClient()

  def submitSampleWithRetry(sampleData: SampleData): Future[Ack] = {
    retryClient.withRetry(
      operation = masterStub.submitSample(sampleData),
      maxRetries = 3,
      initialDelayMs = 1000,
      operationName = "SubmitSample"
    )
  }
}
```

### 4.2 State-based Cleanup on Failure

```scala
class WorkerNode {
  def handleFailure(reason: String): Unit = {
    logger.error(s"Worker entering failed state: $reason")

    val currentState = stateMachine.getState

    // State-specific cleanup
    currentState match {
      case Sampling =>
        cleanupSamplingState()

      case Sorting | WaitingForShuffleSignal =>
        cleanupSortingState()
        cleanupLocalPartitions()

      case Shuffling | WaitingForMergeSignal =>
        cleanupReceivedPartitions()
        cleanupInFlightTransfers()

      case Merging =>
        cleanupTemporaryMergeFiles()
        cleanupPartialOutput()

      case _ =>
        logger.info(s"No cleanup needed for state $currentState")
    }

    // Notify master of failure
    try {
      masterStub.notifyWorkerFailure(WorkerFailureNotification(
        workerId = workerId,
        failedState = currentState.toString,
        reason = reason,
        timestamp = System.currentTimeMillis()
      ))
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to notify master of failure: $ex")
    }

    stateMachine.transition(Failed, reason)
  }

  private def cleanupSamplingState(): Unit = {
    logger.info("Cleaning up sampling state")
    // No files to clean, just release memory
    System.gc()
  }

  private def cleanupSortingState(): Unit = {
    logger.info("Cleaning up sorted chunks")
    val chunkPattern = "chunk_.*\\.tmp".r

    tempDir.listFiles().foreach { file =>
      if (chunkPattern.findFirstIn(file.getName).isDefined) {
        file.delete()
        logger.debug(s"Deleted sorted chunk: ${file.getName}")
      }
    }
  }

  private def cleanupLocalPartitions(): Unit = {
    logger.info("Cleaning up local partition files")
    val partitionPattern = "partition\\.\\d+".r

    tempDir.listFiles().foreach { file =>
      if (partitionPattern.findFirstIn(file.getName).isDefined) {
        file.delete()
        logger.debug(s"Deleted partition file: ${file.getName}")
      }
    }
  }

  private def cleanupReceivedPartitions(): Unit = {
    logger.info("Cleaning up received partition files")
    receivedPartitionsDir.listFiles().foreach { file =>
      file.delete()
      logger.debug(s"Deleted received file: ${file.getName}")
    }
  }

  private def cleanupInFlightTransfers(): Unit = {
    logger.info("Cancelling in-flight transfers")
    // Cancel ongoing gRPC streams
    // (implementation depends on how streams are tracked)
  }

  private def cleanupTemporaryMergeFiles(): Unit = {
    logger.info("Cleaning up temporary merge files")
    val tempPattern = "partition\\.\\d+\\.tmp".r

    outputDir.listFiles().foreach { file =>
      if (tempPattern.findFirstIn(file.getName).isDefined) {
        file.delete()
        logger.debug(s"Deleted temp merge file: ${file.getName}")
      }
    }
  }

  private def cleanupPartialOutput(): Unit = {
    logger.info("Cleaning up partial output files")
    // Delete any partition.X files that are incomplete
    // (this is tricky - might want to keep them for debugging)
  }
}
```

### 4.3 Master-side Worker Failure Handling

```scala
class MasterNode {
  private val failedWorkers = new ConcurrentHashMap[String, WorkerFailureInfo]()

  case class WorkerFailureInfo(
    workerId: String,
    failedPhase: WorkerPhase,
    failureTime: Long,
    reason: String
  )

  override def notifyWorkerFailure(notification: WorkerFailureNotification): Future[Ack] = {
    logger.error(s"Worker ${notification.workerId} failed in state ${notification.failedState}: " +
      s"${notification.reason}")

    val failureInfo = WorkerFailureInfo(
      workerId = notification.workerId,
      failedPhase = notification.failedState,
      failureTime = notification.timestamp,
      reason = notification.reason
    )

    failedWorkers.put(notification.workerId, failureInfo)

    // Decide whether to continue or abort
    val currentPhase = getCurrentPhase()
    val decision = makeRecoveryDecision(currentPhase, failedWorkers.size())

    decision match {
      case RecoveryDecision.ContinueWithRemainingWorkers =>
        logger.warn(s"Continuing with ${expectedWorkers - failedWorkers.size()} workers")
        Future.successful(Ack(success = true, message = "Failure acknowledged, continuing"))

      case RecoveryDecision.AbortJob =>
        logger.error("Too many worker failures, aborting job")
        abortAllWorkers()
        Future.successful(Ack(success = false, message = "Job aborted due to excessive failures"))
    }
  }

  sealed trait RecoveryDecision
  object RecoveryDecision {
    case object ContinueWithRemainingWorkers extends RecoveryDecision
    case object AbortJob extends RecoveryDecision
  }

  private def makeRecoveryDecision(phase: WorkerPhase, numFailed: Int): RecoveryDecision = {
    val numAlive = expectedWorkers - numFailed
    val alivePercentage = numAlive.toDouble / expectedWorkers

    phase match {
      case WorkerPhase.PHASE_SAMPLING | WorkerPhase.PHASE_SORTING =>
        // Can tolerate up to 50% failures in early phases
        if (alivePercentage >= 0.5) {
          RecoveryDecision.ContinueWithRemainingWorkers
        } else {
          RecoveryDecision.AbortJob
        }

      case WorkerPhase.PHASE_SHUFFLING | WorkerPhase.PHASE_MERGING =>
        // Cannot tolerate any failures in shuffle/merge phases
        // (data is already partitioned and assigned)
        RecoveryDecision.AbortJob

      case _ =>
        RecoveryDecision.AbortJob
    }
  }

  private def abortAllWorkers(): Unit = {
    logger.warn("Sending abort signal to all workers")

    registeredWorkers.asScala.foreach { worker =>
      try {
        val stub = createWorkerStub(worker)
        stub.abort(AbortRequest(reason = "Master initiated abort due to excessive failures"))
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to send abort to ${worker.workerId}: $ex")
      }
    }

    System.exit(1)
  }
}
```

### 4.4 Worker-side Graceful Shutdown

```scala
class WorkerNode {
  private val shutdownHook = new Thread {
    override def run(): Unit = {
      logger.info("Shutdown hook triggered, cleaning up...")
      gracefulShutdown()
    }
  }

  def registerShutdownHook(): Unit = {
    Runtime.getRuntime.addShutdownHook(shutdownHook)
  }

  def gracefulShutdown(): Unit = {
    logger.info("Starting graceful shutdown")

    // Stop accepting new work
    grpcServer.shutdown()

    // Wait for in-flight operations to complete
    try {
      grpcServer.awaitTermination(30, TimeUnit.SECONDS)
    } catch {
      case _: InterruptedException =>
        logger.warn("Shutdown interrupted, forcing termination")
        grpcServer.shutdownNow()
    }

    // Perform cleanup based on current state
    val currentState = stateMachine.getState
    if (!currentState.isTerminal) {
      handleFailure("Worker shutting down")
    }

    // Close resources
    closeAllConnections()

    logger.info("Graceful shutdown complete")
  }

  override def abort(request: AbortRequest): Future[Ack] = {
    logger.error(s"Received abort request: ${request.reason}")

    // Trigger graceful shutdown
    Future {
      Thread.sleep(100)  // Give time to send ack
      gracefulShutdown()
      System.exit(1)
    }

    Future.successful(Ack(success = true, message = "Abort acknowledged"))
  }
}
```

---

## 5. Specific Failure Scenarios

### 5.1 Scenario: Worker Crashes During Sorting

**발생 시점**: Worker가 external sort 중 OutOfMemoryError로 crash

**감지**:
- Master: Worker로부터 60초 동안 heartbeat 없음
- Master: NotifyPhaseComplete(SORTING) timeout (30분)

**복구 절차**:

1. **Master 측**:
```scala
def handleWorkerCrashDuringSorting(workerId: String): Unit = {
  logger.warn(s"Worker $workerId crashed during sorting")

  val numAlive = expectedWorkers - failedWorkers.size()

  if (numAlive >= expectedWorkers / 2) {
    logger.info(s"Continuing with $numAlive workers (>50% alive)")

    // Recalculate partition boundaries with fewer samples
    val remainingSamples = collectSamplesFromAliveWorkers()
    val newBoundaries = computeBoundaries(remainingSamples)

    // Update numPartitions to match alive workers
    val newNumPartitions = numAlive
    val newShuffleMap = createShuffleMap(numAlive, newNumPartitions)

    // Redistribute configuration to alive workers
    redistributeConfig(newBoundaries, newShuffleMap)
  } else {
    logger.error("Less than 50% workers alive, aborting job")
    abortAllWorkers()
  }
}
```

2. **재시작된 Worker 측** (optional):
```scala
def attemptRecovery(): Unit = {
  logger.info("Attempting to recover from crash")

  // Clean up any partial state
  cleanupAllTemporaryFiles()

  // Re-register with master
  initialize()

  // Master will reassign work in reconfiguration
}
```

### 5.2 Scenario: Network Partition During Shuffle

**발생 시점**: Worker A가 Worker B에게 partition 파일 전송 중 네트워크 단절

**감지**:
- Worker A: gRPC status UNAVAILABLE
- Worker B: Partial partition file received, no completion signal

**복구 절차**:

1. **Sender (Worker A)**:
```scala
def handleShuffleNetworkFailure(targetWorker: WorkerInfo, partitionId: Int): Boolean = {
  logger.error(s"Network failure while sending partition $partitionId to ${targetWorker.workerId}")

  // Wait for network recovery
  var attempt = 0
  val maxAttempts = 3

  while (attempt < maxAttempts) {
    Thread.sleep(30000)  // Wait 30 seconds

    logger.info(s"Retry attempt ${attempt + 1}/$maxAttempts for partition $partitionId")

    try {
      // Check if target is reachable
      val healthCheck = createWorkerStub(targetWorker).healthCheck(HealthCheckRequest())

      if (healthCheck.healthy) {
        // Retry sending
        return sendPartitionFile(partitionId, partitionFiles(partitionId), targetWorker)
      }
    } catch {
      case _: Exception =>
        logger.warn(s"Target ${targetWorker.workerId} still unreachable")
    }

    attempt += 1
  }

  // Failed after all retries
  logger.error(s"Failed to send partition $partitionId after $maxAttempts attempts")
  false
}
```

2. **Receiver (Worker B)**:
```scala
override def receivePartition(
  responseObserver: StreamObserver[PartitionFileAck]
): StreamObserver[PartitionFile] = {
  new StreamObserver[PartitionFile] {
    private var lastReceivedTime = System.currentTimeMillis()
    private val timeoutMs = 60 * 1000  // 60 seconds

    // Timeout checker
    private val timeoutChecker = scheduler.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          val elapsed = System.currentTimeMillis() - lastReceivedTime
          if (elapsed > timeoutMs) {
            logger.error(s"Receive timeout for partition $partitionId, cleaning up")
            cleanup()
          }
        }
      },
      timeoutMs, timeoutMs, TimeUnit.MILLISECONDS
    )

    override def onNext(chunk: PartitionFile): Unit = {
      lastReceivedTime = System.currentTimeMillis()
      // ... write chunk
    }

    override def onError(t: Throwable): Unit = {
      logger.error(s"Receive error: $t, cleaning up partial file")
      cleanup()
      timeoutChecker.cancel(false)
    }

    private def cleanup(): Unit = {
      outputStream.foreach(_.close())
      tempFile.foreach(_.delete())
    }
  }
}
```

### 5.3 Scenario: Disk Full During Merge

**발생 시점**: Worker가 최종 partition 파일 write 중 disk full

**감지**:
- Worker: IOException with "No space left on device"

**복구 절차**:

```scala
def performMergeWithDiskCheck(): Unit = {
  val config = partitionConfig.get
  val myPartitions = getMyPartitions(config.shuffleMap)

  myPartitions.foreach { partitionId =>
    try {
      // Pre-check disk space
      val estimatedOutputSize = estimateOutputSize(partitionId)
      val availableSpace = outputDir.getFreeSpace

      if (availableSpace < estimatedOutputSize * 1.2) {  // 20% buffer
        throw new IOException(s"Insufficient disk space: need $estimatedOutputSize, " +
          s"available $availableSpace")
      }

      mergePartition(partitionId)

    } catch {
      case ex: IOException if ex.getMessage.contains("No space left") =>
        logger.error(s"Disk full while merging partition $partitionId")

        // Attempt cleanup
        cleanupTemporaryMergeFiles()

        val freedSpace = outputDir.getFreeSpace
        logger.info(s"Freed space, now have $freedSpace bytes")

        if (freedSpace > estimatedOutputSize) {
          // Retry once
          logger.info(s"Retrying merge for partition $partitionId")
          mergePartition(partitionId)
        } else {
          // Cannot recover
          handleFailure(s"Disk full, cannot merge partition $partitionId")
        }
    }
  }
}

private def estimateOutputSize(partitionId: Int): Long = {
  val partitionFiles = listReceivedPartitionFiles(partitionId)
  partitionFiles.map(_.length()).sum
}
```

### 5.4 Scenario: Master Crash

**발생 시점**: Master 프로세스가 중간에 crash

**감지**:
- Workers: Heartbeat failures
- Workers: Cannot send NotifyPhaseComplete

**복구 절차**:

**현재 설계**: Master crash는 복구 불가 → 전체 job 재시작 필요

**향후 개선 (Checkpoint-based Recovery)**:
```scala
class MasterNode {
  // Periodically checkpoint state
  def saveCheckpoint(): Unit = {
    val checkpoint = MasterCheckpoint(
      registeredWorkers = registeredWorkers.asScala.toSeq,
      currentPhase = getCurrentPhase(),
      partitionConfig = currentPartitionConfig,
      phaseCompletions = phaseTracker.getCompletions,
      timestamp = System.currentTimeMillis()
    )

    val checkpointFile = new File("/tmp/master_checkpoint.json")
    val json = Json.toJson(checkpoint).toString()
    Files.write(checkpointFile.toPath, json.getBytes(StandardCharsets.UTF_8))

    logger.debug("Saved master checkpoint")
  }

  // Recover from checkpoint on restart
  def recoverFromCheckpoint(): Option[MasterCheckpoint] = {
    val checkpointFile = new File("/tmp/master_checkpoint.json")

    if (checkpointFile.exists()) {
      try {
        val json = new String(Files.readAllBytes(checkpointFile.toPath), StandardCharsets.UTF_8)
        val checkpoint = Json.parse(json).as[MasterCheckpoint]

        logger.info(s"Recovered checkpoint from ${new Date(checkpoint.timestamp)}")
        Some(checkpoint)
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to recover checkpoint: $ex")
          None
      }
    } else {
      None
    }
  }
}
```

**Worker 측 대응**:
```scala
class WorkerNode {
  private def handleMasterFailure(): Unit = {
    logger.error("Master appears to be down")

    // Wait for master recovery
    var waitTime = 0
    val maxWaitTime = 5 * 60 * 1000  // 5 minutes

    while (waitTime < maxWaitTime) {
      Thread.sleep(30000)  // Check every 30 seconds
      waitTime += 30000

      try {
        val healthCheck = masterStub.healthCheck(HealthCheckRequest())
        if (healthCheck.healthy) {
          logger.info("Master recovered, re-registering")
          initialize()  // Re-register
          return
        }
      } catch {
        case _: Exception =>
          logger.debug(s"Master still down, waiting... ($waitTime/$maxWaitTime ms)")
      }
    }

    // Master did not recover
    logger.error("Master did not recover within timeout, exiting")
    gracefulShutdown()
    System.exit(1)
  }
}
```

---

## 6. Data Integrity Guarantees

### 6.1 Atomic Operations

**파일 쓰기**:
```scala
def atomicWrite(data: Array[Byte], targetFile: File): Unit = {
  val tempFile = new File(targetFile.getParent, s"${targetFile.getName}.tmp")

  try {
    // Write to temp file
    val outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))
    outputStream.write(data)
    outputStream.close()

    // Atomic rename
    Files.move(tempFile.toPath, targetFile.toPath, StandardCopyOption.ATOMIC_MOVE)

    logger.debug(s"Atomically wrote ${data.length} bytes to ${targetFile.getName}")

  } catch {
    case ex: Exception =>
      tempFile.delete()  // Cleanup on failure
      throw ex
  }
}
```

**Idempotent Operations**:
```scala
// Sampling: Idempotent - same input produces same samples (with fixed seed)
class ReservoirSampler(sampleSize: Int, seed: Long = 12345) {
  private val random = new Random(seed)
  // ... deterministic sampling
}

// Partitioning: Idempotent - same boundaries produce same partitions
class Partitioner(boundaries: Seq[Array[Byte]]) {
  def getPartition(key: Array[Byte]): Int = {
    // Deterministic binary search
    // ...
  }
}

// Merging: Idempotent - same inputs produce same output
class KWayMerger(files: Seq[File]) {
  // Deterministic merge using total ordering
  // ...
}
```

### 6.2 Checksum Verification (Optional)

```scala
import java.security.MessageDigest

class ChecksummedPartitionSender {
  def sendWithChecksum(partitionFile: File, target: WorkerInfo): Future[Boolean] = {
    // Compute checksum
    val checksum = computeChecksum(partitionFile)

    val metadata = PartitionMetadata(
      partitionId = extractPartitionId(partitionFile),
      fileSize = partitionFile.length(),
      checksum = checksum
    )

    // Send metadata first
    val stub = createWorkerStub(target)
    stub.sendPartitionMetadata(metadata).flatMap { ack =>
      if (ack.success) {
        // Then send actual data
        sendPartitionFileStreaming(partitionFile, target)
      } else {
        Future.successful(false)
      }
    }
  }

  private def computeChecksum(file: File): String = {
    val md5 = MessageDigest.getInstance("MD5")
    val inputStream = new BufferedInputStream(new FileInputStream(file))

    val buffer = new Array[Byte](8192)
    var bytesRead = 0

    while ({bytesRead = inputStream.read(buffer); bytesRead != -1}) {
      md5.update(buffer, 0, bytesRead)
    }

    inputStream.close()
    md5.digest().map("%02x".format(_)).mkString
  }
}

class ChecksummedPartitionReceiver {
  private var expectedMetadata: Option[PartitionMetadata] = None

  override def sendPartitionMetadata(metadata: PartitionMetadata): Future[Ack] = {
    logger.info(s"Expecting partition ${metadata.partitionId}: " +
      s"${metadata.fileSize} bytes, checksum ${metadata.checksum}")
    expectedMetadata = Some(metadata)
    Future.successful(Ack(success = true, message = "Metadata received"))
  }

  override def receivePartition(...): StreamObserver[PartitionFile] = {
    new StreamObserver[PartitionFile] {
      override def onCompleted(): Unit = {
        // Verify checksum
        val actualChecksum = computeChecksum(finalFile)
        val expectedChecksum = expectedMetadata.get.checksum

        if (actualChecksum == expectedChecksum) {
          logger.info(s"Checksum verified for partition $partitionId")
          responseObserver.onNext(PartitionFileAck(success = true, ...))
        } else {
          logger.error(s"Checksum mismatch! Expected $expectedChecksum, got $actualChecksum")
          finalFile.delete()
          responseObserver.onNext(PartitionFileAck(
            success = false,
            errorMessage = "Checksum mismatch"
          ))
        }

        responseObserver.onCompleted()
      }
    }
  }
}
```

### 6.3 Exactly-Once vs At-Least-Once Semantics

**현재 설계**:
- **Sampling**: At-most-once (실패 시 재전송 안함, 충분한 샘플이 있으면 OK)
- **Sorting**: Exactly-once (local operation, crash시 재시작)
- **Shuffle**: At-least-once → **Exactly-once** (idempotent operations + atomic writes)
- **Merge**: Exactly-once (deterministic merge + atomic writes)

**Exactly-once 보장 메커니즘**:
```scala
class ExactlyOnceShuffleReceiver {
  private val receivedPartitions = new ConcurrentHashMap[Int, String]()  // partitionId → checksum

  override def receivePartition(...): StreamObserver[PartitionFile] = {
    new StreamObserver[PartitionFile] {
      override def onNext(chunk: PartitionFile): Unit = {
        if (chunk.offset == 0) {
          // First chunk - check for duplicate
          val existingChecksum = receivedPartitions.get(chunk.partitionId)

          if (existingChecksum != null) {
            logger.warn(s"Partition ${chunk.partitionId} already received, " +
              s"ignoring duplicate")
            responseObserver.onNext(PartitionFileAck(
              success = true,
              partitionId = chunk.partitionId,
              receiverWorkerId = workerId,
              errorMessage = "Duplicate, already received"
            ))
            responseObserver.onCompleted()
            return  // Ignore duplicate
          }
        }

        // ... normal processing
      }

      override def onCompleted(): Unit = {
        val checksum = computeChecksum(finalFile)
        receivedPartitions.put(partitionId, checksum)

        logger.info(s"Partition $partitionId received exactly-once (checksum: $checksum)")

        // ... send ack
      }
    }
  }
}
```

---

## 7. Testing Failure Scenarios

### 7.1 Fault Injection Framework

```scala
object FaultInjector {
  private val random = new Random()

  sealed trait FaultType
  case object NetworkDelay extends FaultType
  case object NetworkFailure extends FaultType
  case object ProcessCrash extends FaultType
  case object DiskFull extends FaultType
  case object CorruptData extends FaultType

  case class FaultConfig(
    faultType: FaultType,
    probability: Double,  // 0.0 to 1.0
    phase: Option[WorkerPhase] = None
  )

  private var enabledFaults = List.empty[FaultConfig]

  def enable(faults: FaultConfig*): Unit = {
    enabledFaults = faults.toList
    logger.warn(s"Fault injection enabled: ${faults.mkString(", ")}")
  }

  def disable(): Unit = {
    enabledFaults = List.empty
    logger.info("Fault injection disabled")
  }

  def maybeInjectFault(currentPhase: WorkerPhase): Unit = {
    enabledFaults.filter(_.phase.forall(_ == currentPhase)).foreach { config =>
      if (random.nextDouble() < config.probability) {
        logger.warn(s"Injecting fault: ${config.faultType} in phase $currentPhase")

        config.faultType match {
          case NetworkDelay =>
            Thread.sleep(5000)  // 5 second delay

          case NetworkFailure =>
            throw new StatusRuntimeException(Status.UNAVAILABLE)

          case ProcessCrash =>
            logger.error("FAULT INJECTION: Simulating process crash")
            System.exit(99)

          case DiskFull =>
            throw new IOException("No space left on device (simulated)")

          case CorruptData =>
            // Handled by caller - corrupt data before sending
            logger.warn("Data corruption should be handled by caller")
        }
      }
    }
  }
}
```

**사용 예시**:
```scala
// Test: Worker crashes during sorting
FaultInjector.enable(
  FaultConfig(ProcessCrash, probability = 0.1, phase = Some(WorkerPhase.PHASE_SORTING))
)

// Test: Network failures during shuffle
FaultInjector.enable(
  FaultConfig(NetworkFailure, probability = 0.2, phase = Some(WorkerPhase.PHASE_SHUFFLING))
)
```

### 7.2 Chaos Engineering Tests

```scala
class ChaosTest extends FunSuite {
  test("System survives 30% worker crashes during sampling") {
    val master = new MasterNode(expectedWorkers = 10)
    val workers = (0 until 10).map(_ => new WorkerNode(master.address))

    // Inject crashes
    FaultInjector.enable(
      FaultConfig(ProcessCrash, probability = 0.3, phase = Some(WorkerPhase.PHASE_SAMPLING))
    )

    // Run job
    val result = runDistributedSort(master, workers)

    // Verify: job completes with at least 7 workers (70%)
    assert(result.completedWorkers >= 7)
    assert(result.outputValid)
  }

  test("System handles network partitions during shuffle") {
    val master = new MasterNode(expectedWorkers = 5)
    val workers = (0 until 5).map(_ => new WorkerNode(master.address))

    // Inject network failures
    FaultInjector.enable(
      FaultConfig(NetworkFailure, probability = 0.15, phase = Some(WorkerPhase.PHASE_SHUFFLING))
    )

    val result = runDistributedSort(master, workers)

    // Verify: all shuffle transfers eventually succeed (with retries)
    assert(result.allShufflesComplete)
    assert(result.outputValid)
  }

  test("System detects and recovers from disk full") {
    // ... test disk full scenario
  }
}
```

---

## 8. Monitoring & Alerting

### 8.1 Health Metrics

```scala
case class WorkerHealthMetrics(
  workerId: String,
  state: WorkerState,
  cpuUsage: Double,
  memoryUsage: Long,
  diskUsage: Long,
  networkThroughput: Double,
  lastHeartbeat: Long,
  errorsLast5Min: Int
)

class HealthMonitor {
  private val metrics = new ConcurrentHashMap[String, WorkerHealthMetrics]()

  def updateMetrics(workerId: String, newMetrics: WorkerHealthMetrics): Unit = {
    metrics.put(workerId, newMetrics)

    // Check for anomalies
    checkHealthAnomaly(newMetrics)
  }

  private def checkHealthAnomaly(metrics: WorkerHealthMetrics): Unit = {
    if (metrics.memoryUsage > 0.9 * Runtime.getRuntime.maxMemory()) {
      logger.warn(s"Worker ${metrics.workerId} memory usage high: " +
        s"${metrics.memoryUsage / (1024 * 1024)}MB")
    }

    if (metrics.errorsLast5Min > 10) {
      logger.error(s"Worker ${metrics.workerId} error rate high: " +
        s"${metrics.errorsLast5Min} errors in last 5 minutes")
    }

    val staleDuration = System.currentTimeMillis() - metrics.lastHeartbeat
    if (staleDuration > 60000) {
      logger.error(s"Worker ${metrics.workerId} heartbeat stale: " +
        s"last seen ${staleDuration / 1000}s ago")
    }
  }

  def generateHealthReport(): String = {
    val report = new StringBuilder
    report.append("=== Worker Health Report ===\n")

    metrics.asScala.toSeq.sortBy(_._1).foreach { case (workerId, m) =>
      report.append(s"$workerId: ${m.state}, " +
        s"CPU ${m.cpuUsage}%, " +
        s"Mem ${m.memoryUsage / (1024 * 1024)}MB, " +
        s"Errors ${m.errorsLast5Min}\n")
    }

    report.toString()
  }
}
```

### 8.2 Alerting Rules

```scala
sealed trait Alert {
  def severity: AlertSeverity
  def message: String
}

sealed trait AlertSeverity
case object Critical extends AlertSeverity
case object Warning extends AlertSeverity
case object Info extends AlertSeverity

case class WorkerDownAlert(workerId: String) extends Alert {
  def severity = Critical
  def message = s"Worker $workerId is down"
}

case class HighErrorRateAlert(workerId: String, errorCount: Int) extends Alert {
  def severity = Warning
  def message = s"Worker $workerId has high error rate: $errorCount errors"
}

case class SlowProgressAlert(phase: WorkerPhase, duration: Long) extends Alert {
  def severity = Warning
  def message = s"Phase $phase is progressing slowly: ${duration / 1000}s elapsed"
}

class AlertManager {
  private val alertHandlers = new ConcurrentLinkedQueue[Alert => Unit]()

  def registerHandler(handler: Alert => Unit): Unit = {
    alertHandlers.add(handler)
  }

  def fireAlert(alert: Alert): Unit = {
    logger.log(severityToLogLevel(alert.severity), alert.message)

    alertHandlers.asScala.foreach { handler =>
      try {
        handler(alert)
      } catch {
        case ex: Exception =>
          logger.error(s"Alert handler failed: $ex")
      }
    }
  }

  private def severityToLogLevel(severity: AlertSeverity): Level = severity match {
    case Critical => Level.ERROR
    case Warning => Level.WARN
    case Info => Level.INFO
  }
}
```

---

## 9. Error Recovery Best Practices

### 9.1 Design Principles

1. **Fail Fast**: 복구 불가능한 에러는 즉시 실패 처리
2. **Graceful Degradation**: 일부 Worker 실패 시에도 계속 진행 (가능한 경우)
3. **Idempotency**: 모든 operations은 재시도 가능하도록 설계
4. **Atomic Operations**: 파일 쓰기 등은 atomic하게 수행
5. **Clear State Management**: State machine으로 명확한 상태 추적
6. **Comprehensive Logging**: 모든 실패 시나리오 로깅
7. **Timeout Everything**: 모든 RPC 및 operations에 timeout 설정

### 9.2 Recovery Decision Tree

```
Error Occurred
    |
    ├─ Is it transient? (network timeout, etc.)
    │   ├─ YES → Retry with exponential backoff (max 3 times)
    │   │           |
    │   │           ├─ Success → Continue
    │   │           └─ Failed after retries → Proceed to next check
    │   │
    │   └─ NO → Proceed to next check
    |
    ├─ Is it recoverable? (disk full, OOM, etc.)
    │   ├─ YES → Attempt recovery (cleanup, reduce memory, etc.)
    │   │           |
    │   │           ├─ Success → Continue
    │   │           └─ Failed → Proceed to next check
    │   │
    │   └─ NO → Proceed to next check
    |
    ├─ Can we continue without this worker?
    │   ├─ YES (early phases, >50% alive) → Continue with remaining workers
    │   └─ NO (late phases, critical worker) → Abort entire job
    |
    └─ Abort entire job and cleanup
```

### 9.3 Implementation Checklist

- [ ] All RPC calls have explicit timeouts
- [ ] All RPC calls have retry logic for transient failures
- [ ] All file writes use atomic operations (temp + rename)
- [ ] All operations are idempotent
- [ ] Worker state machine tracks all states
- [ ] State-based cleanup on failure
- [ ] Master tracks worker health with heartbeats
- [ ] Master can detect and handle worker failures
- [ ] Workers can detect and handle master failures
- [ ] Graceful shutdown hooks registered
- [ ] Comprehensive error logging
- [ ] Fault injection framework for testing
- [ ] Chaos engineering tests
- [ ] Health monitoring and alerting
- [ ] Recovery decision logic implemented

---

## 문서 완성도: 95%

**완료된 부분**:
- ✅ Failure taxonomy (장애 분류)
- ✅ Failure detection mechanisms (감지 메커니즘)
- ✅ Comprehensive error recovery matrix
- ✅ RPC retry with exponential backoff
- ✅ State-based cleanup implementations
- ✅ Specific failure scenarios and recovery procedures
- ✅ Data integrity guarantees (atomic operations, checksums)
- ✅ Fault injection framework
- ✅ Monitoring and alerting
- ✅ Best practices and decision trees

**추가 고려사항** (Nice-to-have):
- [ ] Master checkpoint/recovery (현재는 Master crash → job restart)
- [ ] Worker task migration (failed worker의 work를 다른 worker에 재할당)
- [ ] Distributed consensus (Raft/Paxos for Master HA)
- [ ] Automated rollback mechanisms

**다음 문서**: `5-file-management.md`

---

## Appendix: Removed Section

**Note**: Section 10 (Configurable Recovery Strategies) was removed as it is not required by the project specification. The system uses a simple global restart strategy for worker failures
