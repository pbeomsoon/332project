# 동시 다발적 Worker 장애 처리 불가 원인 분석

## 요약
현재 구현은 **단일 Worker 장애**는 처리할 수 있지만, **2개 이상의 Worker가 동시에 crash**하는 경우 정상 동작하지 못합니다.

---

## 현재 Fault Tolerance 메커니즘

### 1. 기본 설정 (MasterService.scala)
```scala
private val MIN_WORKER_THRESHOLD = 0.5          // 최소 50% worker 생존 필요
private val heartbeatTimeout = 6.seconds        // Heartbeat 타임아웃
```

- **50% 임계값**: 전체 worker의 최소 50%가 살아있어야 작업 계속 진행
- 예: 5명 worker → 최소 3명 필요, 3명 worker → 최소 2명 필요

### 2. Heartbeat 기반 장애 감지
```scala
// checkWorkerHealth() - Line 576~608
private def checkWorkerHealth(): Unit = {
  val failedWorkers = scala.collection.mutable.ListBuffer[String]()

  // 1단계: 모든 worker의 heartbeat 확인
  workerLastHeartbeat.asScala.foreach { case (workerId, lastTime) =>
    val secondsSinceLastHeartbeat = Duration.between(lastTime, now).getSeconds
    if (secondsSinceLastHeartbeat > heartbeatTimeout.toSeconds) {
      failedWorkers += workerId  // 타임아웃된 worker 목록에 추가
    }
  }

  // 2단계: 장애 worker를 **순차적으로** 처리
  for (workerId <- failedWorkers) {
    val currentAliveCount = registeredWorkers.count { ... }
    val countAfterFailure = currentAliveCount - 1

    if (countAfterFailure < (expectedWorkers * MIN_WORKER_THRESHOLD)) {
      // 임계값 미달 → 이 worker 장애 처리 건너뜀
      logger.warn(s"Skipping failure processing for $workerId")
    } else {
      // 임계값 만족 → 장애 처리 진행
      handleWorkerFailure(workerId)
    }
  }
}
```

### 3. Shuffle Map 생성 (MasterService.scala:482~501)
```scala
// computePartitionBoundaries() - Line 507~538
private def computePartitionBoundaries(): Unit = {
  // Sampling 완료 후 partition boundary 계산
  val sortedSamples = allSamples.sorted
  partitionBoundaries = (1 until numPartitions).map { i =>
    sortedSamples(i * step)
  }

  // ⭐ 현재 살아있는 worker들로 shuffle map 생성
  val aliveWorkerIndices = getAliveWorkerIndices()
  shuffleMap = createShuffleMap(aliveWorkerIndices, numPartitions)

  // shuffleMap: partitionID → workerIndex 매핑
  // 예: {0→0, 1→0, 2→1, 3→1, 4→2, 5→2}
}
```

---

## 문제점: 왜 2개 이상 동시 장애를 처리 못하는가?

### 🔴 문제 1: Shuffle 단계 중 장애 발생 시 Shuffle Map 갱신 불가

**시나리오:** 5명의 worker (index 0~4), shuffle 단계에서 worker-2, worker-4 동시 crash

1. **Sampling 단계 완료** → shuffle map 생성
   ```
   shuffleMap = {
     0 → worker-0,
     1 → worker-0,
     2 → worker-1,
     3 → worker-1,
     4 → worker-2,  ← 🔴 crash될 worker
     5 → worker-2,  ← 🔴 crash될 worker
     6 → worker-3,
     7 → worker-3,
     8 → worker-4   ← 🔴 crash될 worker
   }
   ```

2. **Shuffle 단계 시작** → 각 worker가 partition을 다른 worker에게 전송
   - worker-0이 partition 4를 worker-2에게 전송 시도
   - worker-1이 partition 8을 worker-4에게 전송 시도
   - **하지만 worker-2, worker-4는 이미 crash!**

3. **GrpcWorkerClient.sendPartition 실패**
   ```scala
   // GrpcWorkerClient.scala:39~116
   override def sendPartition(partitionId: Int, data: Iterator[Record]): Future[Boolean] = {
     try {
       val requestObserver = stub.shuffleData(responseObserver)
       // ... 데이터 전송
     } catch {
       case ex: StatusRuntimeException =>
         promise.tryFailure(ex)  // ❌ gRPC 연결 실패
     }
   }
   ```

4. **문제점:**
   - **Shuffle map이 동적으로 갱신되지 않음**
   - 살아있는 worker들은 여전히 죽은 worker에게 데이터 전송 시도
   - 재시도 메커니즘 없음
   - 데이터 손실 발생

### 🔴 문제 2: Heartbeat 감지 후 Phase 동기화 문제

**시나리오:** 2개 worker 동시 장애 → heartbeat 타임아웃 감지

1. **checkWorkerHealth()가 2개 worker 장애 감지**
   ```
   failedWorkers = [worker-2, worker-4]
   expectedWorkers = 5
   MIN_WORKER_THRESHOLD = 0.5
   ```

2. **순차 처리 (worker-2 먼저)**
   ```
   currentAliveCount = 5
   countAfterFailure = 4
   threshold = 5 * 0.5 = 2.5 (최소 3명)

   4 >= 3 ✓ → handleWorkerFailure(worker-2) 실행
   ```

3. **순차 처리 (worker-4 다음)**
   ```
   currentAliveCount = 4 (worker-2 이미 FAILED 처리됨)
   countAfterFailure = 3
   threshold = 2.5 (최소 3명)

   3 >= 3 ✓ → handleWorkerFailure(worker-4) 실행
   ```

4. **adjustLatchesForAliveWorkers() 호출**
   ```scala
   // Line 668~690
   private def adjustLatchesForAliveWorkers(aliveWorkers: Int): Unit = {
     // 각 phase의 latch를 alive worker 수로 조정
     WorkerPhase.values.foreach { phase =>
       val latch = phaseLatches.get(phase)
       // Latch를 조정하여 살아있는 worker만 기다림
     }
   }
   ```

5. **문제점:**
   - **Latch 조정이 이미 진행 중인 phase에 영향을 주지 못함**
   - 살아있는 worker들은 여전히 모든 worker의 완료를 기다림
   - **Deadlock 발생 가능**

### 🔴 문제 3: Phase Completion 추적 불일치

```scala
// reportPhaseComplete - Line 438~465
override def reportPhaseComplete(request: PhaseCompleteRequest): Future[PhaseCompleteResponse] = {
  Future {
    synchronized {
      val phase = request.phase
      val workerId = request.workerId

      // Phase completion 기록
      val completions = phaseCompletions.get(phase)
      completions.put(workerId, true)

      // Latch countdown
      val latch = phaseLatches.get(phase)
      if (latch != null) {
        latch.countDown()
      }
    }
  }
}
```

**문제점:**
- Worker가 crash하면 해당 worker의 `reportPhaseComplete` 호출 안 됨
- Latch가 조정되더라도, 이미 생성된 latch는 변경 불가
- **다른 worker들이 무한 대기 상태에 빠질 수 있음**

---

## 구체적 실패 시나리오

### 시나리오: 5명 worker 중 2명 shuffle 중 동시 crash

#### 타임라인
```
T0: 5명 worker 등록 완료 (worker-0 ~ worker-4)
T1: Sampling 완료, shuffle map 생성
    shuffleMap = {0→0, 1→0, 2→1, 3→1, 4→2, 5→2, 6→3, 7→3, 8→4}

T2: Shuffle 시작
    - worker-0: partition 1 → worker-0 (자신), partition 6 → worker-3
    - worker-1: partition 2 → worker-1 (자신), partition 5 → worker-2
    - worker-2: partition 4 → worker-2 (자신), ...
    - worker-3: partition 7 → worker-3 (자신), partition 8 → worker-4
    - worker-4: ...

T3: 💥 worker-2, worker-4 동시 crash
    - worker-1의 "partition 5 → worker-2" 전송 실패
    - worker-3의 "partition 8 → worker-4" 전송 실패

T4: Heartbeat 타임아웃 (6초 후)
    - checkWorkerHealth()가 worker-2, worker-4 장애 감지
    - handleWorkerFailure(worker-2) 실행
    - handleWorkerFailure(worker-4) 실행
    - adjustLatchesForAliveWorkers(3) 실행

T5: ❌ 살아있는 worker들 상태
    - worker-0, worker-1, worker-3: shuffle 데이터 일부 전송 실패
    - 재전송 메커니즘 없음
    - Phase completion 대기 중 deadlock 가능
```

#### 결과
- ❌ 데이터 불완전 (partition 5, 8 손실)
- ❌ Shuffle 단계 완료 불가
- ❌ 전체 작업 실패

---

## 왜 단일 장애는 처리 가능한가?

### 단일 장애 시나리오 (5명 중 1명 crash)
```
expectedWorkers = 5
MIN_WORKER_THRESHOLD = 0.5

1명 crash 후:
aliveWorkers = 4
threshold = 5 * 0.5 = 2.5
4 >= 2.5 ✓ → 계속 진행 가능

shuffleMap 재생성 (4명 worker로):
{0→0, 1→0, 2→1, 3→1, 4→2, 5→2, 6→3, 7→3}
```

**차이점:**
- 단일 장애는 **sampling 단계** 중 발생 가능
- Sampling 완료 후 **shuffle map을 재생성**
- Shuffle 시작 전에 장애 처리 완료
- **모든 partition이 살아있는 worker에게만 할당됨**

---

## 해결 방안 (구현 필요)

### Option 1: Dynamic Shuffle Map Recomputation
```scala
// Shuffle 중 worker 장애 감지 시 shuffle map 재계산
private def recomputeShuffleMapOnFailure(failedWorkerIndex: Int): Unit = {
  val aliveWorkerIndices = getAliveWorkerIndices()
  val newShuffleMap = createShuffleMap(aliveWorkerIndices, numPartitions)

  // 실패한 worker가 담당하던 partition을 살아있는 worker에게 재할당
  shuffleMap = newShuffleMap

  // 모든 worker에게 새 shuffle map 브로드캐스트
  broadcastShuffleMapUpdate(newShuffleMap)
}
```

### Option 2: Partition Retry Mechanism
```scala
// Worker에서 partition 전송 실패 시 master에게 재할당 요청
override def sendPartition(partitionId: Int, data: Iterator[Record]): Future[Boolean] = {
  try {
    // 전송 시도
  } catch {
    case ex: StatusRuntimeException =>
      // Master에게 재할당 요청
      requestPartitionReassignment(partitionId)
  }
}
```

### Option 3: Pre-replication
```scala
// 각 partition을 2개 worker에게 복제 (primary + backup)
shuffleMapWithReplication = {
  0 → (worker-0, worker-1),  // primary, backup
  1 → (worker-1, worker-2),
  ...
}
```

---

## 현재 테스트 상태

### ✅ 통과하는 테스트
- `continue with reduced workers when one fails during sampling` (1명 crash)
- `handle worker failure during critical phase (shuffling)` (1명 crash)
- `handle network partition during shuffle` (1명 partition)
- `handle worker restart after failure` (1명 crash → restart)
- `maintain data consistency despite failures` (1명 crash)

### ❌ 삭제된 테스트
- `handle concurrent worker failures` (2명 동시 crash) ← **사용자 요청으로 삭제**

---

## 결론

현재 시스템은:
1. ✅ **단일 worker 장애**는 처리 가능 (50% 임계값 내)
2. ❌ **2개 이상 동시 장애**는 처리 불가
   - Shuffle map 동적 갱신 미지원
   - Phase 동기화 메커니즘 한계
   - 데이터 재전송 메커니즘 없음

**권장 사항:**
- 프로덕션 환경에서는 **단일 장애만 발생**한다고 가정
- 또는 위의 Option 1~3 중 하나를 구현하여 concurrent failure 지원

---

*분석 날짜: 2025-11-20*
*분석 대상: main/src/main/scala/distsort/master/MasterService.scala*
*분석 대상: main/src/main/scala/distsort/shuffle/GrpcWorkerClient.scala*
