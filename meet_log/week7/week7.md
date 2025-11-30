# Week 7 진행 상황 보고

**작성일**: 2025-11-30
**팀명**: Team Silver
**팀원**: 권동연, 박범순, 임지훈

---

## 1. 이번 주 진행 사항

### 1.1 파티션 분배 버그 수정

#### ✅ 파티션 누락 문제 해결 (100%)

**발견된 문제:**
- 3개 Worker로 실행 시 partition.0, partition.3만 생성
- partition.1, partition.2가 누락됨
- `shuffleMap`과 `workerPartitionAssignments`가 독립적으로 계산되어 불일치 발생

**원인 분석:**
```
shuffleMap: partition 0 → Worker 0, partition 1 → Worker 1, partition 2 → Worker 2, partition 3 → Worker 0
assignments: Worker 0 → [0, 1], Worker 1 → [2], Worker 2 → [3]
```
- Worker 0이 partition 0,1을 받아야 하는데 실제 shuffleMap에서는 partition 1은 Worker 1로 지정

**해결 방안:**
```scala
// 기존: 독립적으로 계산
private def getWorkerPartitionAssignments(workerIndex: Int): Seq[Int] = {
  val partitionsPerWorker = numPartitions / numWorkers
  // ... 독립적 계산
}

// 수정: shuffleMap에서 직접 도출
private def getWorkerPartitionAssignments(workerIndex: Int): Seq[Int] = synchronized {
  val assignments = shuffleMap
    .filter { case (_, targetWorker) => targetWorker == workerIndex }
    .keys.toSeq.sorted
  assignments
}
```

**검증:**
- 3개 Worker + input_large 테스트 통과
- 모든 파티션 파일 정상 생성 확인

### 1.2 동적 파티션 수 개선

#### ✅ Worker 수의 배수로 파티션 생성 (100%)

**요구사항:**
- 파티션 수가 항상 Worker 수의 배수가 되도록 변경
- 각 Worker에게 균등하게 파티션 분배
- 더 작은 파티션 크기로 유연성 확보

**변경 사항:**
```scala
// 기존 설정
TARGET_PARTITION_SIZE_MB = 128MB  // 파티션당 128MB
MIN_PARTITIONS_PER_WORKER = 1     // Worker당 최소 1개

// 변경된 설정
TARGET_PARTITION_SIZE_MB = 32MB   // 파티션당 32MB (더 세분화)
MIN_PARTITIONS_PER_WORKER = 3     // Worker당 최소 3개

// Worker 수의 배수로 올림
val idealPartitions = math.max(1, (totalInputSizeMB / TARGET_PARTITION_SIZE_MB).toInt)
val constrainedPartitions = math.max(minPartitions, math.min(maxPartitions, idealPartitions))
val finalPartitions = ((constrainedPartitions + expectedWorkers - 1) / expectedWorkers) * expectedWorkers
```

**결과 예시:**
| Workers | Input Size | Partitions | Per Worker |
|---------|------------|------------|------------|
| 1 | 32MB | 3 | 3 |
| 2 | 32MB | 6 | 3 |
| 3 | 32MB | 9 | 3 |
| 2 | 200MB | 8 | 4 |
| 3 | 200MB | 9 | 3 |

### 1.3 Recovery Worker 버그 수정

#### ✅ Shuffling Phase 복구 실패 문제 해결 (100%)

**발견된 문제:**
- Worker 2가 crash 후 복구되어 재등록
- 복구된 Worker가 "Max retries exceeded waiting for phase PHASE_SHUFFLING" 에러
- 다른 Worker들이 Shuffling을 완료하지 못해 복구 Worker가 Phase 진입 불가

**원인 분석:**
- Worker 0, Worker 1이 crashed Worker 2로 파티션 전송 시도
- `workerClients` 맵이 갱신되지 않아 복구된 Worker 2의 새 endpoint를 모름
- Thread visibility 문제: `workerClients`가 여러 스레드에서 접근되지만 `@volatile` 미적용

**해결 방안:**
```scala
// ShuffleManager.scala - Thread visibility 보장
@volatile private var workerClients: Map[Int, WorkerClient] = Map.empty

// refreshWorkerClients()에서 디버깅 로그 추가
private def refreshWorkerClients(): Unit = {
  refreshClientsCallback.foreach { callback =>
    try {
      val oldClientKeys = workerClients.keySet
      val newClients = callback()
      if (newClients.nonEmpty) {
        workerClients = newClients
        if (oldClientKeys != newClients.keySet) {
          System.err.println(s"[Worker-$workerIndex] ⚠️ Worker set changed: ${oldClientKeys.mkString(",")} → ${newClients.keySet.mkString(",")}")
        }
      }
    } catch {
      case ex: Exception =>
        System.err.println(s"[Worker-$workerIndex] ⚠️ Failed to refresh worker info: ${ex.getMessage}")
    }
  }
}
```

**동작 흐름:**
1. Worker 2 crash → Master가 감지 → FAILED 마킹
2. 복구 Worker가 등록 → Master가 새 endpoint 저장 (예: port 50003)
3. Worker 0, 1이 partition 전송 실패 시 `refreshWorkerClients()` 호출
4. `@volatile` 덕분에 새 endpoint가 즉시 visible
5. 재시도 시 복구된 Worker 2로 정상 전송

### 1.4 Worker Crash 감지 시간 단축

#### ✅ 15초 → 4초로 감지 시간 개선 (100%)

**기존 설정:**
```scala
heartbeatInterval = 5.seconds      // Worker: 5초마다 heartbeat
heartbeatCheckInterval = 5.seconds // Master: 5초마다 체크
MISSED_HEARTBEAT_THRESHOLD = 3     // 3회 미수신 시 FAILED
// 최대 감지 시간: 5초 × 3 = 15초
```

**변경된 설정:**
```scala
heartbeatInterval = 2.seconds      // Worker: 2초마다 heartbeat
heartbeatCheckInterval = 2.seconds // Master: 2초마다 체크
MISSED_HEARTBEAT_THRESHOLD = 2     // 2회 미수신 시 FAILED
// 최대 감지 시간: 2초 × 2 = 4초
```

**효과:**
- Worker crash 후 최대 4초 내에 Master가 감지
- 복구 Worker가 더 빠르게 Phase에 합류 가능
- 전체 Fault Tolerance 응답 시간 개선

### 1.5 로그 정리

#### ✅ Shutdown 로그 간소화 (100%)

**기존 로그:**
```
[Worker-0] Shutting down... (caller: distsort.worker.Worker.stop(Worker.scala:245)
at distsort.worker.Worker.$anonfun$startHeartbeat$1(Worker.scala:189) ...)
```

**변경된 로그:**
```
[Worker-0] Shutting down...
```

**변경 사항:**
- `stop()` 메서드에서 caller stack trace 출력 제거
- 불필요한 디버깅 정보 삭제
- 깔끔한 종료 메시지만 유지

---

## 2. 주요 성과

### 2.1 기능 검증

| 기능 | 상태 | 비고 |
|------|------|------|
| 파티션 균등 분배 | ✅ | shuffleMap 기반 분배 |
| 동적 파티션 수 | ✅ | Worker 배수 보장 |
| Recovery Worker | ✅ | @volatile로 thread visibility 해결 |
| 빠른 Crash 감지 | ✅ | 15초 → 4초 |
| 깔끔한 로그 | ✅ | stack trace 제거 |

### 2.2 Fault Tolerance 개선

**개선 전:**
- Crash 감지: 15초
- 복구 Worker endpoint 갱신: 불안정 (thread visibility 문제)
- 파티션 분배: 불일치 가능

**개선 후:**
- Crash 감지: 4초
- 복구 Worker endpoint 갱신: 안정적 (@volatile 적용)
- 파티션 분배: shuffleMap 기반으로 일관성 보장

### 2.3 코드 품질 개선

- Thread safety 강화 (`@volatile` 적용)
- 데이터 일관성 보장 (shuffleMap 단일 소스)
- 로그 가독성 향상

---

## 3. 다음 주 목표 (Week 8)

### 3.1 최종 테스트

**1. 대규모 분산 환경 테스트**
- 여러 VM에서 Worker 실행
- 500MB+ 데이터 처리 테스트
- 성능 벤치마크 측정

**2. Fault Tolerance 최종 검증**
- Worker crash 후 복구 시나리오 테스트
- 다양한 Phase에서 crash 테스트
- 데이터 무결성 검증

### 3.2 최종 발표 준비

- 발표 슬라이드 완성
- 데모 시나리오 준비
- Q&A 예상 질문 준비

---

## 4. 개인별 진행 사항 및 다음 주 목표

### 권동연

**이번 주:**
- 파티션 분배 버그 분석 및 수정
- shuffleMap 기반 분배 로직 구현
- 동적 파티션 수 계산 로직 개선
- Recovery Worker 테스트

**다음 주:**
- 최종 발표 자료 작성
- 데모 시나리오 준비
- Fault Tolerance 최종 검증

### 박범순

**이번 주:**
- Recovery Worker 버그 분석
- ShuffleManager thread visibility 문제 해결
- @volatile 적용 및 테스트
- Worker endpoint 갱신 로직 디버깅

**다음 주:**
- 대규모 데이터 테스트
- 성능 벤치마크 측정
- 병목 구간 분석

### 임지훈

**이번 주:**
- Heartbeat 타이밍 최적화
- Crash 감지 시간 단축 구현
- 로그 정리 작업
- Week 7 진행 상황 보고서 작성

**다음 주:**
- 통합 테스트 수행
- 최종 발표 자료 작성
- 문서 정리

---

## 5. 확정된 기술 결정 사항

### 5.1 파티션 분배 전략

**결정:**
```scala
// shuffleMap에서 직접 worker별 파티션 도출
private def getWorkerPartitionAssignments(workerIndex: Int): Seq[Int] = synchronized {
  shuffleMap.filter { case (_, targetWorker) => targetWorker == workerIndex }.keys.toSeq.sorted
}
```

**이유:**
- 단일 소스 (shuffleMap)에서 모든 분배 정보 도출
- 불일치 가능성 제거
- 동기화로 thread safety 보장

### 5.2 동적 파티션 수 계산

**결정:**
```scala
TARGET_PARTITION_SIZE_MB = 32MB
MIN_PARTITIONS_PER_WORKER = 3
MAX_PARTITIONS_PER_WORKER = 10

// Worker 수의 배수로 올림
val finalPartitions = ((constrained + workers - 1) / workers) * workers
```

**이유:**
- 균등 분배 보장
- 작은 데이터에서도 충분한 병렬성
- Worker 수에 따른 유연한 조정

### 5.3 Heartbeat 타이밍

**결정:**
```scala
heartbeatInterval = 2.seconds
MISSED_HEARTBEAT_THRESHOLD = 2
```

**이유:**
- 빠른 crash 감지 (4초 이내)
- 네트워크 오버헤드 최소화 (2초 간격)
- 실용적인 복구 시간

### 5.4 Thread Visibility

**결정:**
```scala
@volatile private var workerClients: Map[Int, WorkerClient] = Map.empty
```

**이유:**
- 여러 스레드에서 접근하는 공유 변수
- `@volatile`로 즉시 visibility 보장
- Recovery Worker endpoint 갱신 즉시 반영

---

## 6. 이슈 및 해결

### 이슈 1: 파티션 누락 (partition.1, partition.2 missing)

**문제:**
- 3 Workers + 4 partitions에서 partition.1, partition.2 미생성
- shuffleMap과 workerPartitionAssignments 불일치

**해결:**
- workerPartitionAssignments를 shuffleMap에서 직접 도출
- 단일 소스로 일관성 보장

**검증:**
- 3 Workers 테스트에서 모든 파티션 정상 생성

### 이슈 2: Recovery Worker Phase 진입 실패

**문제:**
- 복구된 Worker가 SHUFFLING Phase에 진입 못함
- 다른 Workers가 Shuffling 완료 못함

**해결:**
- workerClients에 @volatile 적용
- refreshWorkerClients()에서 새 endpoint 즉시 갱신

**검증:**
- Worker crash 후 복구 Worker 정상 Phase 진입

### 이슈 3: 느린 Crash 감지

**문제:**
- Worker crash 후 15초 후에야 Master가 감지
- 복구 시간이 너무 김

**해결:**
- Heartbeat 간격 5초 → 2초
- Threshold 3회 → 2회
- 최대 감지 시간 4초

**검증:**
- Crash 후 4초 이내 FAILED 로그 확인

---

## 7. 파일 변경 사항

### 수정된 파일:

**MasterService.scala:**
- `getWorkerPartitionAssignments()`: shuffleMap 기반으로 변경
- `calculateNumPartitions()`: Worker 배수 올림 로직 추가
- `TARGET_PARTITION_SIZE_MB`: 128 → 32
- `MIN_PARTITIONS_PER_WORKER`: 1 → 3
- `heartbeatCheckInterval`: 5초 → 2초
- `MISSED_HEARTBEAT_THRESHOLD`: 3 → 2

**Worker.scala:**
- `heartbeatInterval`: 5초 → 2초
- `stop()`: caller stack trace 제거

**ShuffleManager.scala:**
- `workerClients`: `@volatile` 추가
- `refreshWorkerClients()`: 디버깅 로그 추가

### 커밋 내역:

1. `파티션 수가 항상 worker 수의 배수가 되도록 수정`
2. `ShuffleManager workerClients 가시성 및 디버깅 개선`
3. `Worker crash 감지 시간 단축 (15초 → 4초)`
4. `shutdown 로그에서 caller stack trace 제거`

---

## 8. 다음 주 마일스톤

### Milestone 7: Final Testing & Presentation (Week 8)

**목표:**
- 모든 기능 최종 검증
- 대규모 데이터 테스트 통과
- 발표 자료 완성

**검증 기준:**
```bash
# 대규모 분산 환경 테스트
# VM1: Master
./master 3

# VM2-4: Workers (각각 다른 VM)
./worker <master-ip:port> -I input -O output

# Fault Tolerance 테스트
# Worker crash 후 복구 Worker 투입
# 4초 이내 crash 감지 확인
# 복구 Worker가 정상적으로 Phase 합류

# 데이터 무결성 검증
./verify_sort.sh output
```

**완료 조건:**
- [ ] 대규모 분산 환경 테스트 성공
- [ ] 500MB+ 데이터 처리 성공
- [ ] Fault Tolerance 테스트 통과
- [ ] 데이터 무결성 검증 통과
- [ ] 최종 발표 자료 완성

---

**작성자**: Team Silver (권동연, 박범순, 임지훈)
**버전**: Week 7 Final
