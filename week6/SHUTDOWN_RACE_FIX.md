# Shutdown Race Condition 해결

## 문제 상황

**증상:**
- Master가 workflow 완료 후 즉시 종료
- Worker들이 merge phase 완료 후 Master에게 완료 보고를 시도
- Master가 이미 종료되어 "UNAVAILABLE: io exception" 발생
- Worker들이 무한 재시도 루프에 빠짐

**원인:**
```scala
// Master.scala:148 (수정 전)
logger.info("Distributed sorting workflow completed successfully!")
outputFinalResult()
isRunning = false
stop()  // ❌ 즉시 종료 - Workers가 완료 보고할 기회 없음
```

## 해결 방법

### 1. MasterService에 Workflow 완료 플래그 추가

**파일:** `main/src/main/scala/distsort/master/MasterService.scala`

```scala
// Line 101: 완료 플래그 추가
@volatile private var workflowCompleted: Boolean = false

// Line 922: 완료 신호 메서드
def signalWorkflowComplete(): Unit = {
  logger.info("Signaling workflow completion to all workers")
  workflowCompleted = true
}
```

### 2. Heartbeat 응답에서 종료 신호 전송

**파일:** `main/src/main/scala/distsort/master/MasterService.scala`

```scala
// Line 418-425: Heartbeat에서 shouldAbort 플래그 설정
override def heartbeat(request: HeartbeatRequest): Future[HeartbeatResponse] = {
  Future {
    // ...
    if (workflowCompleted) {
      logger.info(s"Workflow completed - signaling Worker $workerId to shut down gracefully")
      HeartbeatResponse(
        acknowledged = true,
        shouldAbort = true,  // ✅ Worker에게 종료 신호
        message = "Workflow completed - please shut down"
      )
    } else {
      HeartbeatResponse(acknowledged = true, shouldAbort = false, message = "Healthy")
    }
  }
}
```

### 3. Master가 Grace Period 대기

**파일:** `main/src/main/scala/distsort/master/Master.scala`

```scala
// Line 147-158: Workflow 완료 후 종료 신호 전송 및 대기
logger.info("Distributed sorting workflow completed successfully!")
outputFinalResult()
isRunning = false

// ✅ Workers에게 종료 신호 전송
logger.info("Signaling workers to shut down gracefully...")
masterService.signalWorkflowComplete()

// ✅ Workers가 heartbeat로 신호를 받을 시간 제공 (10초)
logger.info("Waiting 10 seconds for workers to receive shutdown signal...")
Thread.sleep(10000)

// ✅ Grace period 이후 Master 종료
logger.info("Grace period complete, shutting down Master...")
stop()
```

## 동작 흐름

### Before (문제):
1. Master: Workflow 완료 → 즉시 stop() 호출
2. Worker: Merge 완료 → Master에게 보고 시도
3. Worker: "UNAVAILABLE" 에러 → 무한 재시도 😞

### After (해결):
1. Master: Workflow 완료 → signalWorkflowComplete() 호출
2. Master: 10초 대기 (Workers가 heartbeat 받을 시간)
3. Worker: Heartbeat 전송 → shouldAbort=true 수신
4. Worker: stop() 호출하여 정상 종료 ✅
5. Master: 10초 후 stop() 호출하여 정상 종료 ✅

## 타이밍 분석

- **Worker Heartbeat 간격:** 5초 (Worker.scala:90)
- **Master Grace Period:** 10초
- **보장 사항:** 모든 Worker가 최소 2회의 heartbeat로 종료 신호를 받을 수 있음

```
Time   Master                Worker 1              Worker 2              Worker 3
0s     Complete workflow     Merge done            Merge done            Merge done
0s     signalWorkflowComplete()
0s     Start 10s wait
2s                           Heartbeat → shouldAbort=true
3s                                                 Heartbeat → shouldAbort=true
4s                                                                       Heartbeat → shouldAbort=true
2s                           stop() ✅
3s                                                 stop() ✅
4s                                                                       stop() ✅
10s    stop() ✅
```

## Worker의 기존 Shutdown 처리

Worker는 이미 shouldAbort 플래그를 처리하는 로직을 가지고 있음:

```scala
// Worker.scala:264
if (response.shouldAbort) {
  logger.error(s"Master instructed to abort: ${response.message}")
  stop()  // ✅ 정상 종료
}
```

## 테스트 방법

### 로컬 테스트
```bash
# Terminal 1: Master
./master 3

# Terminal 2-4: Workers
./worker <master-ip:port> -I input1 -O output
./worker <master-ip:port> -I input1 -O output
./worker <master-ip:port> -I input1 -O output
```

### 예상 로그

**Master:**
```
[INFO] Distributed sorting workflow completed successfully!
[INFO] Signaling workers to shut down gracefully...
[INFO] Waiting 10 seconds for workers to receive shutdown signal...
[INFO] Workflow completed - signaling Worker worker-1 to shut down gracefully
[INFO] Workflow completed - signaling Worker worker-2 to shut down gracefully
[INFO] Workflow completed - signaling Worker worker-3 to shut down gracefully
[INFO] Grace period complete, shutting down Master...
[INFO] Master node stopped
```

**Worker:**
```
[INFO] Worker worker-1 completed PHASE_MERGING
[INFO] Heartbeat received
[ERROR] Master instructed to abort: Workflow completed - please shut down
[INFO] Worker worker-1: Cleaning up resources
[INFO] Worker worker-1 stopped
```

## 핵심 개선 사항

✅ Master가 종료 전 Workers에게 신호를 보낼 시간 제공
✅ Workers가 정상적으로 종료 신호를 받고 graceful shutdown
✅ 무한 재시도 루프 제거
✅ 기존 heartbeat 메커니즘 활용 (새로운 RPC 추가 불필요)
✅ 10초 grace period로 모든 Workers가 신호를 받을 수 있음 보장
