# gRPC 채널 누수 및 Master 종료 문제 해결

**작성일**: 2025-11-23

---

## 문제 1: gRPC 채널 누수

### 증상
```
SEVERE: *~*~*~ Previous channel ManagedChannelImpl{...} was not shutdown properly!!! ~*~*~*
Make sure to call shutdown()/shutdownNow() and wait until awaitTermination() returns true.
```

### 근본 원인
- ShuffleManager가 GrpcWorkerClient 인스턴스들을 생성
- Worker 간 통신을 위해 gRPC ManagedChannel 생성
- Worker 종료 시 이 채널들이 닫히지 않음 → 리소스 누수

### 해결 방법

**1. ShuffleManager에 cleanup() 메서드 추가**
```scala
// ShuffleManager.scala
def cleanup(): Unit = {
  logger.info(s"Cleaning up ShuffleManager resources (${workerClients.size} worker clients)...")

  workerClients.values.foreach { client =>
    try {
      client.close()  // GrpcWorkerClient.close() 호출
    } catch {
      case ex: Exception =>
        logger.warn(s"Error closing worker client: ${ex.getMessage}", ex)
    }
  }

  workerClients = Map.empty
  logger.info("ShuffleManager cleanup complete")
}
```

**2. Worker에 ShuffleManager를 클래스 필드로 변경**
```scala
// Worker.scala
class Worker(...) {
  // Before: shuffleManager는 performShuffle() 내부의 로컬 변수
  // After: 클래스 필드로 선언
  @volatile private var shuffleManager: distsort.shuffle.ShuffleManager = _

  // performShuffle()에서 필드에 할당
  def performShuffle(sortedChunks: Seq[File]): Unit = {
    shuffleManager = new ShuffleManager(...)
    // ... shuffle logic ...
  }
}
```

**3. Worker.stop()에서 cleanup 호출**
```scala
// Worker.scala:stop()
def stop(): Unit = {
  // ... 다른 cleanup 코드 ...

  // Cleanup shuffle manager resources (worker client connections)
  if (shuffleManager != null) {
    shuffleManager.cleanup()
  }

  // ... 나머지 cleanup ...
}
```

### 효과
- ✅ 모든 gRPC 채널이 정상적으로 종료됨
- ✅ SEVERE 경고 메시지 제거
- ✅ 리소스 누수 방지

---

## 문제 2: Master가 Worker IP 출력 후 멈춤

### 증상
```bash
[Master] ✓ Workflow completed successfully!
localhost, localhost, localhost
# 여기서 멈춤 - 터미널로 돌아가지 않음
```

### 근본 원인

**이전 코드 흐름:**
```scala
// Master.scala:run()
masterService.signalWorkflowComplete()
Thread.sleep(10000)
stop()  // shutdownLatch.countDown() 호출

// Master.scala:main() - workflow thread
new Thread(() => {
  master.run()

  // Worker IPs 출력 (stop() 이후!)
  val workerIPs = ...
  println(workerIPs)
}).start()

// Main thread
master.awaitTermination()  // shutdownLatch.await()
```

**문제:**
1. master.run()이 stop()을 호출
2. stop()이 shutdownLatch.countDown() 호출
3. 메인 스레드의 awaitTermination()이 반환
4. 메인 메서드가 종료 시작
5. Workflow 스레드가 Worker IPs를 출력하려 하지만 타이밍 이슈 발생
6. gRPC 서버 스레드, Executor 스레드 등이 완전히 종료되지 않은 상태에서 혼란

### 해결 방법

**Worker IP 출력을 stop() 이전으로 이동**
```scala
// Master.scala:run()
logger.info("Distributed sorting workflow completed successfully!")

// Signal workers
masterService.signalWorkflowComplete()

// Wait 10 seconds
Thread.sleep(10000)

// Output Worker IPs BEFORE stop()
val workerIPs = masterService.getRegisteredWorkers
  .map(w => w.host)
  .mkString(", ")
println(workerIPs)

// Now shutdown
stop()
```

**Workflow 스레드에서 중복 출력 제거**
```scala
// Master.scala:main()
new Thread(() => {
  try {
    Thread.sleep(1000)
    master.run()
    // Worker IPs are now printed inside master.run() before stop()
  } catch {
    case ex: Exception =>
      logger.error(s"Master workflow failed: ${ex.getMessage}", ex)
      master.stop()
  }
}).start()
```

### 효과
- ✅ Worker IPs가 정확히 출력됨
- ✅ stop() 이전에 모든 출력 완료
- ✅ Master가 정상적으로 종료됨
- ✅ 터미널로 즉시 돌아감

---

## 출력 순서 (수정 후)

### Master
```bash
$ ./master 3
2.2.2.254:43159                          # Line 1: Master IP:port
[Master] Waiting for 3 workers...       # stderr 진행 상황
[Master] ✓ All 3 workers registered
[Master] Phase 1/4: Sampling...
...
[Master] ✓ Workflow completed successfully!
localhost, localhost, localhost          # Line 2: Worker IPs (stop 이전)
$                                        # 터미널로 돌아감
```

### Worker
```bash
$ ./worker 2.2.2.254:43159 -I input1 -O output
[Worker-0] Phase 1/4: Sampling...
...
[Worker-0] ✓ All phases complete!
[Worker-0] Received shutdown signal from Master
$                                        # gRPC 채널 정상 종료 후 exit
```

**이전 문제:**
- Worker 종료 시 SEVERE 경고
- Master가 Worker IPs 출력 후 멈춤

**수정 후:**
- ✅ Worker 깔끔하게 종료 (gRPC 경고 없음)
- ✅ Master 정상 종료 (터미널 복귀)

---

## 변경된 파일

### 1. ShuffleManager.scala
- `cleanup()` 메서드 추가 (line 324-341)
- 모든 workerClients 종료 및 정리

### 2. Worker.scala
- shuffleManager를 클래스 필드로 선언 (line 54)
- performShuffle()에서 필드에 할당 (line 505)
- stop()에서 shuffleManager.cleanup() 호출 (line 911-914)

### 3. Master.scala
- Worker IP 출력을 master.run() 내부로 이동 (line 168-172)
- stop() 호출 전에 출력 완료
- main()의 workflow 스레드에서 중복 출력 제거 (line 412)

---

## 테스트 검증

### 기대 결과
```bash
# Master 터미널
./master 3
2.2.2.254:33748
[Master] Waiting for 3 workers to register...
[Master] ✓ All 3 workers registered
[Master] Phase 1/4: Sampling...
[Master] ✓ Sampling complete
[Master] ✓ Computed 8 partition boundaries
[Master] Phase 2/4: Sorting...
[Master] ✓ Sorting complete
[Master] Phase 3/4: Shuffling...
[Master] ✓ Shuffling complete
[Master] Phase 4/4: Merging...
[Master] ✓ Merging complete
[Master] ✓ Workflow completed successfully!
localhost, localhost, localhost
$

# Worker 터미널
./worker 2.2.2.254:33748 -I input1 -O output
[Worker-0] Phase 1/4: Sampling...
[Worker-0] ✓ Sampling complete
[Worker-0] Phase 2/4: Sorting...
[Worker-0] ✓ Sorting complete (3 chunks)
[Worker-0] Phase 3/4: Shuffling...
[Worker-0] ✓ Shuffling complete
[Worker-0] Phase 4/4: Merging...
[Worker-0] ✓ Merging complete
[Worker-0] ✓ All phases complete!
[Worker-0] Received shutdown signal from Master
$
```

### 확인 사항
- ✅ Master stdout에 정확히 2줄 출력
- ✅ Master가 Worker IPs 출력 후 즉시 종료
- ✅ Worker 종료 시 gRPC SEVERE 경고 없음
- ✅ 모든 프로세스가 깔끔하게 종료

---

## 요약

| 문제 | 원인 | 해결 |
|------|------|------|
| gRPC 채널 누수 | Worker 종료 시 채널 미종료 | ShuffleManager.cleanup() 추가 |
| Master 멈춤 | Worker IP 출력 타이밍 | stop() 전으로 출력 이동 |

**효과:**
- 깔끔한 리소스 정리
- 정상적인 프로세스 종료
- Slides 예시와 일치하는 출력
