# Worker Auto-Shutdown 문제 해결

**작성일**: 2025-11-23
**문제**: Worker가 workflow 완료 후 즉시 종료되어 Master가 출력을 완료하기 전에 프로세스가 종료됨

---

## 문제 분석

### 증상
- Worker가 모든 Phase를 완료한 후 즉시 종료
- Master의 10초 grace period가 작동하지 않음
- Master가 Worker IP를 출력하기 전에 Worker 프로세스 종료

### 근본 원인

**Worker.scala:373 (수정 전)**
```scala
// Mark workflow as completed
currentPhase.set(WorkerPhase.PHASE_COMPLETED)

logger.info(s"Worker $workerId completed workflow successfully")

// Clean up checkpoints on successful completion
checkpointManager.deleteAllCheckpoints()

// Auto-shutdown after successful completion
stop()  // ❌ 문제: 즉시 종료
```

**실행 흐름:**
1. Worker가 모든 Phase 완료 (Sampling → Sorting → Shuffling → Merging)
2. `currentPhase.set(WorkerPhase.PHASE_COMPLETED)` 설정
3. `stop()` 즉시 호출
4. `shutdownLatch.countDown()` 호출
5. `awaitTermination()` 반환
6. Worker 프로세스 종료

**Master의 의도했던 흐름:**
1. Master가 workflow 완료
2. Master가 `signalWorkflowComplete()` 호출
3. Master가 10초 대기 (Workers가 heartbeat로 신호 받을 시간)
4. Workers는 heartbeat 응답에서 `shouldAbort=true` 수신
5. Workers가 정상 종료
6. Master가 Worker IPs 출력 후 종료

**실제 발생한 흐름:**
1. Workers가 workflow 완료 후 즉시 `stop()` 호출
2. Workers가 Master의 신호를 기다리지 않고 종료
3. Master의 10초 grace period가 의미 없음

---

## 해결 방법

### 1. Worker의 Auto-Shutdown 제거

**Worker.scala:373-374 (수정 후)**
```scala
// Mark workflow as completed
currentPhase.set(WorkerPhase.PHASE_COMPLETED)

logger.info(s"Worker $workerId completed workflow successfully")
logger.info(s"Worker $workerId waiting for shutdown signal from Master via heartbeat...")

// Clean up checkpoints on successful completion
checkpointManager.deleteAllCheckpoints()

// DO NOT auto-shutdown - wait for Master to signal shutdown via heartbeat
// The heartbeat will receive shouldAbort=true when Master completes workflow
```

**변경 사항:**
- `stop()` 호출 제거
- Worker는 workflow 완료 후 heartbeat를 계속 전송
- Master의 shutdown 신호를 기다림

### 2. Heartbeat Shutdown 로그 개선

**Worker.scala:264-268 (수정 전)**
```scala
if (response.shouldAbort) {
  logger.error(s"Master instructed to abort: ${response.message}")  // ❌ ERROR 레벨
  stop()
}
```

**Worker.scala:264-268 (수정 후)**
```scala
if (response.shouldAbort) {
  logger.info(s"Received shutdown signal from Master: ${response.message}")  // ✅ INFO 레벨
  System.err.println(s"[Worker-$workerIndex] Received shutdown signal from Master")
  stop()
}
```

**변경 사항:**
- 로그 레벨: `ERROR` → `INFO` (정상적인 종료이므로)
- 사용자에게 stderr 메시지 추가 (진행 상황 가시성)

---

## 수정된 흐름

### 정상 종료 시나리오

**1. Worker Workflow 완료**
```
[Worker-0] Phase 4/4: Merging...
[Worker-0] ✓ Merging complete
[Worker-0] ✓ All phases complete!
(Worker는 종료하지 않고 heartbeat 계속 전송)
```

**2. Master Workflow 완료**
```
[Master] ✓ Merging complete
[Master] ✓ Workflow completed successfully!
(Master는 signalWorkflowComplete() 호출)
(Master는 10초 대기)
```

**3. Worker가 Heartbeat로 Shutdown 신호 수신**
```
Worker Heartbeat (5초 간격)
  → Master 응답: shouldAbort=true, message="Workflow completed - please shut down"
  → Worker: [Worker-0] Received shutdown signal from Master
  → Worker: stop() 호출
  → Worker 프로세스 종료
```

**4. Master가 Worker IPs 출력 후 종료**
```
141.223.91.81, 141.223.91.82, 141.223.91.83
(Master 종료)
```

### 타이밍 보장

| 시간 | Master | Worker |
|------|--------|--------|
| T+0s | Workflow 완료, signalWorkflowComplete() | Workflow 완료, heartbeat 계속 |
| T+5s | Grace period 대기 중 | Heartbeat 전송 → shouldAbort=true 수신 |
| T+5s | - | Worker 종료 시작 |
| T+10s | Worker IPs 출력 | (이미 종료 완료) |
| T+10s | Master 종료 | - |

**보장:**
- Worker Heartbeat 간격: 5초
- Master Grace period: 10초
- 최소 2회 heartbeat 기회로 shutdown 신호 수신 보장

---

## 에러 처리

### Worker 실패 시
```scala
catch {
  case ex: Exception =>
    logger.error(s"Worker $workerId workflow failed: ${ex.getMessage}", ex)
    reportError(ex)
    throw ex
}
```

**Workflow thread (Worker.scala:1044-1048)**
```scala
try {
  Thread.sleep(2000)
  worker.run()
} catch {
  case ex: Exception =>
    logger.error(s"Worker workflow failed: ${ex.getMessage}", ex)
    worker.stop()  // ✅ 에러 시에는 즉시 종료
}
```

**에러 시 동작:**
- Worker가 에러를 Master에 보고
- Worker가 즉시 stop() 호출
- 정상 종료되지 않으므로 Master의 grace period 불필요

---

## 검증

### 테스트 시나리오

**1. 정상 완료 테스트**
```bash
# Terminal 1: Master
./master 3
141.223.91.80:30040
[Master] Waiting for 3 workers to register...
[Master] ✓ All 3 workers registered
[Master] Phase 1/4: Sampling...
...
[Master] ✓ Workflow completed successfully!
(10초 대기)
141.223.91.81, 141.223.91.82, 141.223.91.83
$

# Terminal 2-4: Workers
./worker 141.223.91.80:30040 -I input1 -O output
[Worker-0] Phase 1/4: Sampling...
...
[Worker-0] ✓ All phases complete!
[Worker-0] Received shutdown signal from Master
$
```

**예상 결과:**
- ✅ Master가 Worker IPs를 정상 출력
- ✅ Workers가 heartbeat로 shutdown 신호 수신 후 종료
- ✅ 모든 프로세스가 정상 종료

**2. 로그 확인**
```bash
tail -f /tmp/distsort.log

# Worker 로그
Worker worker-0 completed workflow successfully
Worker worker-0 waiting for shutdown signal from Master via heartbeat...
Received shutdown signal from Master: Workflow completed - please shut down
Stopping Worker worker-0...
Worker worker-0 stopped
```

---

## 관련 파일

**수정된 파일:**
- `src/main/scala/distsort/worker/Worker.scala`
  - Line 373-374: Auto-shutdown 제거
  - Line 264-268: Heartbeat shutdown 로그 개선

**관련 로직:**
- `src/main/scala/distsort/master/Master.scala:161` - `signalWorkflowComplete()` 호출
- `src/main/scala/distsort/master/Master.scala:166` - 10초 grace period
- `src/main/scala/distsort/master/MasterService.scala:419-425` - Heartbeat에서 shouldAbort 전송
- `src/main/scala/distsort/worker/Worker.scala:264-268` - Heartbeat에서 shouldAbort 수신

---

## 요약

**문제:**
- Worker가 workflow 완료 후 즉시 stop() 호출
- Master의 grace period가 작동하지 않음

**해결:**
- Worker의 auto-shutdown 제거
- Heartbeat 기반 shutdown 메커니즘만 사용
- 10초 grace period로 모든 Worker가 신호 수신 보장

**효과:**
- Master가 정상적으로 Worker IPs 출력
- 모든 프로세스가 정상 종료
- Slides 예시와 일치하는 출력 형식
