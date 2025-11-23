# 강제 종료 메커니즘 - 근본 해결책

**작성일**: 2025-11-23
**문제**: gRPC Netty 스레드(non-daemon)로 인한 프로세스 종료 불가

---

## 근본 원인 분석

### JVM 종료 조건
JVM 프로세스가 종료되려면:
1. **모든 non-daemon 스레드가 종료**
2. **OR** `System.exit()` 호출

### gRPC의 스레드 모델
```
Worker 프로세스:
├─ Main thread (non-daemon)
├─ Workflow thread (non-daemon)
├─ Heartbeat scheduler threads (non-daemon)
├─ ExecutorService threads (non-daemon)
├─ gRPC Server threads (non-daemon)
│  └─ Netty EventLoop threads (non-daemon) ⚠️
├─ Master ManagedChannel threads (non-daemon)
│  └─ Netty EventLoop threads (non-daemon) ⚠️
└─ Worker Client ManagedChannels (non-daemon)
   └─ Netty EventLoop threads (non-daemon) ⚠️
```

**핵심 문제**: Netty EventLoop threads는 **non-daemon**이며, I/O 대기 중이거나 pending tasks가 있으면 **종료되지 않는다**.

---

## 왜 shutdown()만으로는 안 되는가?

### 현재 방식 (불충분):
```scala
server.shutdown()
if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
  server.shutdownNow()
}
```

### 문제점:
1. **`shutdown()`**: 새 요청 거부, 기존 작업 완료 대기
   - Netty EventLoop은 계속 실행될 수 있음
   - I/O selector가 대기 중이면 멈추지 않음

2. **`shutdownNow()`**: 작업 중단 시도
   - "시도"만 할 뿐 **보장하지 않음**
   - Netty 내부 스레드는 여전히 살아있을 수 있음

3. **`awaitTermination(timeout)`**: 종료 대기
   - Timeout 후에도 스레드가 살아있을 수 있음
   - **단순히 기다리기만 포기할 뿐**

### 실제 상황:
```
Worker.stop() 호출
  → server.shutdown()
  → server.awaitTermination(10s)
  → 10초 timeout
  → server.shutdownNow()
  → shutdownLatch.countDown()
  → main thread의 awaitTermination() 반환
  → main() 종료
  → BUT: Netty threads 여전히 살아있음!
  → JVM은 non-daemon thread가 있으므로 종료 안 됨
  → Process hangs forever
```

---

## 근본 해결책: Forced Termination

### 해결 방법 1: Watchdog Thread (안전망)

```scala
// Worker.scala - Heartbeat에서 shutdown signal 수신 시
if (response.shouldAbort) {
  // Watchdog: 15초 후 무조건 강제 종료
  val watchdog = new Thread(() => {
    Thread.sleep(15000)
    System.err.println(s"[Worker-$workerIndex] Shutdown timeout - forcing exit")
    System.exit(0)  // ⚡ 무조건 종료
  }, "shutdown-watchdog")
  watchdog.setDaemon(true)  // Daemon이므로 정상 종료 시 자동 소멸
  watchdog.start()

  stop()  // 정상 shutdown 시도
}
```

**작동 원리:**
- Watchdog은 daemon thread → 정상 종료 시 자동 소멸
- 15초 내 정상 종료되면 watchdog은 의미 없음
- 15초 내 종료 안 되면 watchdog이 강제 종료

### 해결 방법 2: Immediate Exit after Successful Shutdown

```scala
// Worker.scala:stop() 마지막
def stop(): Unit = {
  // ... 모든 리소스 정리 ...

  shutdownLatch.countDown()
  System.err.println(s"[Worker-$workerIndex] Shutdown complete")

  // 정상 종료 완료 후 즉시 exit
  Thread.sleep(500)  // 로그 flush 대기
  System.exit(0)     // ⚡ 깔끔하게 종료
}
```

**작동 원리:**
- 모든 cleanup이 완료되면 즉시 exit
- Netty threads가 살아있어도 상관없음
- JVM이 강제로 종료됨

---

## 두 가지 방법의 조합

### 정상 시나리오:
```
1. Worker가 shutdown signal 수신
2. Watchdog thread 시작 (15초 타이머)
3. stop() 호출
4. 모든 리소스 정리 완료 (~5초)
5. System.exit(0) 호출
6. 프로세스 즉시 종료 ✅
7. Watchdog은 daemon이므로 함께 종료
```

### 비정상 시나리오 (cleanup 블로킹):
```
1. Worker가 shutdown signal 수신
2. Watchdog thread 시작 (15초 타이머)
3. stop() 호출
4. gRPC shutdown이 멈춤 (예: I/O 대기)
5. ... 10초 경과 ...
6. ... 15초 경과 ...
7. Watchdog이 강제 종료 ✅
8. 프로세스 종료
```

---

## 코드 변경 사항

### Worker.scala

**1. Heartbeat shutdown signal 처리:**
```scala
if (response.shouldAbort) {
  System.err.println(s"[Worker-$workerIndex] Received shutdown signal from Master")

  // Watchdog for forced termination
  val watchdog = new Thread(() => {
    Thread.sleep(15000)
    System.err.println(s"[Worker-$workerIndex] Shutdown timeout - forcing exit")
    System.exit(0)
  }, "shutdown-watchdog")
  watchdog.setDaemon(true)
  watchdog.start()

  stop()
}
```

**2. stop() 메서드 마지막:**
```scala
def stop(): Unit = {
  System.err.println(s"[Worker-$workerIndex] Shutting down...")

  // Stop heartbeat (2s timeout)
  // Shutdown gRPC server (3s timeout)
  // Shutdown master channel (2s timeout)
  // Cleanup shuffle manager (5s timeout)
  // Cleanup temporary files
  // Shutdown executor (3s timeout)

  shutdownLatch.countDown()
  System.err.println(s"[Worker-$workerIndex] Shutdown complete")

  // Force exit
  Thread.sleep(500)
  System.exit(0)
}
```

### Master.scala

**stop() 메서드 마지막:**
```scala
def stop(): Unit = {
  logger.info("Stopping Master node...")

  // Shutdown gRPC server
  // Shutdown executor service

  shutdownLatch.countDown()
  logger.info("Master node stopped")

  // Force exit
  Thread.sleep(500)
  System.exit(0)
}
```

---

## 타임아웃 전략

### Worker 총 shutdown 시간:
```
Heartbeat stop:      2s
gRPC server:         3s
Master channel:      2s
Shuffle cleanup:     5s
File cleanup:        ~1s
Executor:            3s
Log flush:           0.5s
─────────────────────────
Total:              ~16.5s
Watchdog timeout:    15s
```

**최악의 경우:**
- 정상 cleanup이 멈추면 → Watchdog이 15초에 강제 종료 ✅
- 정상 cleanup이 완료되면 → ~5초에 정상 종료 ✅

---

## 장점

### 1. 보장된 종료
- 어떤 상황에서도 15초 내 종료 **보장**
- Netty threads가 살아있어도 상관없음

### 2. 빠른 정상 종료
- 정상 케이스는 ~5초에 종료
- Watchdog은 백업 안전망 역할

### 3. 디버깅 가능
- Timeout 발생 시 메시지 출력:
  ```
  [Worker-0] Shutdown timeout - forcing exit
  ```
- 로그 파일에서 어디서 블로킹됐는지 확인 가능

### 4. 클린 셧다운
- 모든 리소스를 정리 시도
- 정리가 완료되면 즉시 exit
- 정리가 실패해도 강제 exit

---

## 대안과 비교

### ❌ Daemon Threads로 변경
- gRPC/Netty는 non-daemon threads 사용
- 우리가 제어할 수 없음

### ❌ 타임아웃만 줄이기
- awaitTermination() timeout을 줄여도
- 스레드가 실제로 종료되는 건 아님
- 여전히 hanging 가능

### ✅ Forced Termination (현재 방법)
- 근본적으로 종료 보장
- 정상 cleanup도 시도
- 실전에서 검증된 방법 (많은 프로덕션 시스템이 사용)

---

## 실제 동작 예시

### 성공 케이스:
```bash
$ worker 2.2.2.254:34270 -I input1 -O output
[Worker-0] Phase 1/4: Sampling...
[Worker-0] ✓ Sampling complete
[Worker-0] Phase 2/4: Sorting...
[Worker-0] ✓ Sorting complete (1 chunks)
[Worker-0] Phase 3/4: Shuffling...
[Worker-0] ✓ Shuffling complete
[Worker-0] Phase 4/4: Merging...
[Worker-0] ✓ Merging complete
[Worker-0] ✓ All phases complete!
[Worker-0] Received shutdown signal from Master
[Worker-0] Shutting down...
[Worker-0] Shutdown complete
$  # ✅ 즉시 터미널로 복귀!
```

### Timeout 케이스 (만약 발생):
```bash
[Worker-0] Received shutdown signal from Master
[Worker-0] Shutting down...
... (15초 경과) ...
[Worker-0] Shutdown timeout - forcing exit
$  # ✅ 강제로라도 종료!
```

---

## 결론

**타임아웃을 줄이는 것은 근본 해결책이 아니다.**

**진짜 해결책:**
1. ✅ Watchdog thread로 강제 종료 보장
2. ✅ 정상 cleanup 후 즉시 System.exit()
3. ✅ gRPC Netty threads를 기다리지 않음

**효과:**
- 모든 경우에 프로세스 종료 보장
- 정상 케이스는 빠르게 종료 (~5초)
- 비정상 케이스도 15초 내 종료
- Production-ready 솔루션
