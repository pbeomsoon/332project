# Week 6 진행 상황 보고

**작성일**: 2025-11-23
**팀명**: Team Silver
**팀원**: 권동연, 박범순, 임지훈

---

## 1. 이번 주 진행 사항

### 1.1 클러스터 환경 테스트 및 검증

#### ✅ 클러스터 배포 및 실행 (100%)

**1. 클러스터 구성**
- Master 노드: silver@141.223.16.227 (포트 7777)
- Worker 노드: 같은 머신에서 3개 프로세스 실행
- 네트워크: ens192 (10.1.25.21), ens224 (2.2.2.254)

**2. JAR 빌드 검증**
- `sbt assembly` 성공 (distsort.jar 생성)
- gRPC 서비스 정의 보존 확인
- Netty 네이티브 라이브러리 정상 작동
- 자동 로컬 주소 감지 기능 검증

**3. 분산 정렬 테스트 실행**

**테스트 절차:**
```bash
# Terminal 1: Master 시작
./master 3
# 출력: 2.2.2.254:33748

# Terminal 2-4: Worker 실행 (각 터미널에서 별도 실행)
./worker 2.2.2.254:33748 -I input1 -O output
./worker 2.2.2.254:33748 -I input1 -O output
./worker 2.2.2.254:33748 -I input1 -O output
```

**테스트 결과:**
- ✅ 3개 Worker 정상 등록
- ✅ Sampling Phase 완료
- ✅ Sorting Phase 완료 (~3-4초)
- ✅ Shuffling Phase 완료 (~3-4초)
- ✅ Merging Phase 완료 (~1초)
- ✅ 9개 파티션 파일 생성 (partition.0 ~ partition.8)
- ✅ 총 63MB 출력, 630k 레코드

**성능 측정:**
- 입력: 62MB (650k 레코드)
- 처리 시간: ~10초
- 처리 속도: ~6MB/초

### 1.2 Shutdown Race Condition 해결

#### ✅ 안전한 종료 메커니즘 구현 (100%)

**발견된 문제:**
- Master가 workflow 완료 후 즉시 종료
- Worker들이 완료 보고 시 "UNAVAILABLE" 에러
- Worker들이 무한 재시도 루프에 빠짐

**해결 방안:**

**1. MasterService 종료 신호 추가**
```scala
// 완료 플래그 추가
@volatile private var workflowCompleted: Boolean = false

// Heartbeat에서 종료 신호 전송
override def heartbeat(request: HeartbeatRequest): Future[HeartbeatResponse] = {
  if (workflowCompleted) {
    HeartbeatResponse(
      acknowledged = true,
      shouldAbort = true,
      message = "Workflow completed - please shut down"
    )
  } else {
    HeartbeatResponse(acknowledged = true, shouldAbort = false, message = "Healthy")
  }
}

// 완료 신호 메서드
def signalWorkflowComplete(): Unit = {
  workflowCompleted = true
}
```

**2. Master Grace Period 추가**
```scala
// Workflow 완료 후
logger.info("Distributed sorting workflow completed successfully!")
outputFinalResult()

// Workers에게 종료 신호 전송
masterService.signalWorkflowComplete()

// 10초 grace period (Workers가 heartbeat로 신호 받을 시간)
Thread.sleep(10000)

// Master 종료
stop()
```

**타이밍 분석:**
- Worker Heartbeat 간격: 5초
- Master Grace Period: 10초
- 보장: 모든 Worker가 최소 2회 heartbeat로 종료 신호 수신

### 1.3 출력 형식 개선 (Slides 예시 준수)

#### ✅ 깔끔한 CLI 출력 구현 (100%)

**개선 사항:**

**1. 로그를 파일로 리다이렉트**
```xml
<!-- logback.xml -->
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
  <file>/tmp/distsort.log</file>
  ...
</appender>
```

**2. stdout 출력 최소화**
- Master: 2줄만 출력 (IP:port, Worker IPs)
- Worker: 아무것도 출력 안 함 (에러 없으면)

**3. 스크립트 정리**
- 시작 메시지를 stderr로 리다이렉트
- stdout은 필수 정보만

**결과 (Slides 예시와 동일):**
```bash
$ master 3
141.223.91.80:30040
141.223.91.81, 141.223.91.82, 141.223.91.83
$
```

### 1.4 진행 상황 가시성 개선

#### ✅ 실시간 진행 상황 메시지 추가

**개선 사항:**

**1. Master 진행 상황 표시 (stderr)**
```
[Master] Waiting for 3 workers to register...
[Master] ✓ All 3 workers registered
[Master] Phase 1/4: Sampling...
[Master] ✓ Sampling complete
[Master] ✓ Computed 9 partition boundaries
[Master] Phase 2/4: Sorting...
[Master] ✓ Sorting complete
[Master] Phase 3/4: Shuffling...
[Master] ✓ Shuffling complete
[Master] Phase 4/4: Merging...
[Master] ✓ Merging complete
[Master] ✓ Workflow completed successfully!
```

**2. Worker 진행 상황 표시 (stderr)**
```
[Worker-0] Phase 1/4: Sampling...
[Worker-0] ✓ Sampling complete
[Worker-0] Phase 2/4: Sorting...
[Worker-0] ✓ Sorting complete (3 chunks)
[Worker-0] Phase 3/4: Shuffling...
[Worker-0] ✓ Shuffling complete
[Worker-0] Phase 4/4: Merging...
[Worker-0] ✓ Merging complete
[Worker-0] ✓ All phases complete!
```

**3. 출력 스트림 분리**
- **stdout**: 필수 출력만 (Master IP:port, Worker IPs)
- **stderr**: 진행 상황 메시지 (사용자가 확인)
- **file**: 상세 로그 (/tmp/distsort.log)

**효과:**
- 로그를 파일로 보내도 진행 상황 실시간 확인 가능
- stdout은 여전히 깔끔 (파이프라인 친화적)
- 사용자는 Worker 별 진행 상황을 명확히 파악

---

## 2. 주요 성과

### 2.1 기능 검증

| 기능 | 상태 | 비고 |
|------|------|------|
| Master-Worker 연결 | ✅ | 자동 로컬 감지 작동 |
| Sampling Phase | ✅ | 정상 완료 |
| Sorting Phase | ✅ | 정상 완료 |
| Shuffling Phase | ✅ | 정상 완료 |
| Merging Phase | ✅ | 정상 완료 |
| Partition 생성 | ✅ | 9개 파일 정상 생성 |
| Graceful Shutdown | ✅ | Race condition 해결 |
| Clean Output | ✅ | Slides 예시 준수 |
| Progress Visibility | ✅ | 실시간 진행 상황 표시 |

### 2.2 성능 측정

**클러스터 환경:**
- Master: 1개
- Workers: 3개 (동일 머신)
- 입력: 62MB (~650k 레코드)
- 출력: 9개 파티션 (총 63MB, 630k 레코드)

**처리 시간:**
- Sampling: < 1s
- Sorting: ~3-4s
- Shuffling: ~3-4s
- Merging: < 1s
- **총 처리 시간: ~10초**

### 2.3 안정성 검증

- ✅ 모든 Worker 정상 등록
- ✅ Phase 동기화 정상 작동
- ✅ Heartbeat 정상 작동
- ✅ 종료 시 모든 프로세스 정상 종료
- ✅ 데이터 무결성 유지 (파티션 검증)

---

## 3. 다음 주 목표 (Week 7)

### 3.1 실제 분산 환경 테스트

**1. 여러 VM에서 Worker 실행**
- 다른 머신에서 Worker 실행
- 네트워크 지연 환경 테스트
- Worker 간 데이터 Shuffle 검증

**2. 대용량 데이터 처리**
- 100MB+ 데이터 테스트
- 성능 벤치마크 측정
- 병목 구간 분석 및 최적화

### 3.2 Fault Tolerance 강화

**1. Worker 실패 시나리오 테스트**
- Worker 재등록 메커니즘 검증
- Checkpoint 복구 테스트
- 실패 복구 시간 측정

**2. 안정성 개선**
- 더 많은 실패 시나리오 처리
- 자동 복구 메커니즘 개선
- 데이터 무결성 검증 강화

### 3.3 최종 발표 준비

- 발표 슬라이드 작성
- 데모 시나리오 준비
- Q&A 예상 질문 준비

---

## 4. 개인별 진행 사항 및 다음 주 목표

### 권동연

**이번 주:**
- 클러스터 환경 테스트 수행
- Shutdown race condition 분석
- MasterService 종료 로직 구현
- Grace period 타이밍 설계

**다음 주:**
- 실제 분산 환경 테스트 (여러 VM)
- Worker 실패 시나리오 테스트
- 성능 최적화 작업
- 발표 자료 작성

### 박범순

**이번 주:**
- JAR 빌드 검증 및 최적화
- 출력 형식 개선 (logback 설정)
- Master/Worker 스크립트 정리
- 테스트 디렉토리 구성

**다음 주:**
- 대용량 데이터 테스트
- 성능 벤치마크 측정
- 병목 구간 분석
- 최적화 작업

### 임지훈

**이번 주:**
- 클러스터 테스트 절차 문서화
- Week 6 진행 상황 보고서 작성
- 테스트 입력/출력 디렉토리 구성
- README 문서 작성

**다음 주:**
- Fault tolerance 테스트 작성
- 통합 테스트 강화
- 최종 발표 자료 작성
- 데모 시나리오 준비

---

## 5. 확정된 기술 결정 사항

### 5.1 Graceful Shutdown 전략

**결정:**
```scala
// Master: Grace period 기반 종료
masterService.signalWorkflowComplete()  // 종료 신호 전송
Thread.sleep(10000)                     // 10초 대기
stop()                                  // Master 종료

// Worker: Heartbeat로 종료 신호 수신
if (response.shouldAbort) {
  stop()  // 정상 종료
}
```

**이유:**
- Worker Heartbeat 간격이 5초
- 10초 grace period로 최소 2회 신호 수신 보장
- 기존 Heartbeat 메커니즘 활용 (새로운 RPC 불필요)

### 5.2 로그 관리 전략

**결정:**
```
stdout: 필수 정보만 (Master IP:port, Worker IPs)
stderr: 에러 및 경고 메시지
File:   모든 디버그 로그 (/tmp/distsort.log)
```

**이유:**
- Slides 예시 준수
- stdout이 깔끔해서 파이프라인 친화적
- 로그 파일로 상세 디버깅 가능

### 5.3 테스트 데이터 구성

**결정:**
```
test_input/sample.bin    100KB   (빠른 기능 테스트)
input1/data.bin          62MB    (성능 테스트)
input2/data.bin          62MB    (성능 테스트)
input3/data.bin          62MB    (성능 테스트)
input4/data.bin          62MB    (성능 테스트)
```

**이유:**
- 소규모/대규모 테스트 모두 지원
- 실제 클러스터 테스트에 사용된 데이터 보존
- 재현 가능한 테스트 환경

---

## 6. 이슈 및 해결

### 이슈 1: Shutdown Race Condition

**문제:**
- Master 종료 후 Worker가 완료 보고 시도
- "UNAVAILABLE" 에러로 무한 재시도

**해결:**
- Master에 `workflowCompleted` 플래그 추가
- Heartbeat 응답에 `shouldAbort` 전달
- 10초 grace period로 모든 Worker가 신호 수신
- Worker는 `shouldAbort` 수신 시 정상 종료

**검증:**
- 클러스터 테스트에서 모든 프로세스 정상 종료 확인
- 무한 재시도 루프 해결

### 이슈 2: 출력 형식 불일치

**문제:**
- stdout에 로그가 너무 많이 출력됨
- Slides 예시와 다른 형식

**해결:**
- logback.xml에서 STDOUT → FILE 변경
- Master에서 중복 println 제거
- 스크립트 시작 메시지를 stderr로 리다이렉트

**검증:**
- Master 출력이 정확히 2줄 (IP:port, Worker IPs)
- Worker는 조용히 실행 (에러 없으면 출력 없음)

### 이슈 3: 자동 로컬 주소 감지

**문제:**
- 클러스터 환경에서 여러 네트워크 인터페이스
- Master 주소가 로컬 주소인지 판별 필요

**해결:**
- Worker에서 Master 주소가 로컬 인터페이스인지 자동 감지
- 로컬이면 127.0.0.1로 변환
- 원격이면 원래 주소 사용

**검증:**
- 같은 머신에서 Master + Workers 정상 작동
- 2.2.2.254 → 127.0.0.1 자동 변환 확인

---

## 7. 다음 주 마일스톤

### Milestone 6: Production Ready (Week 7)

**목표:**
- 실제 분산 환경에서 정상 작동
- 대용량 데이터 처리 성공
- Fault tolerance 검증

**검증 기준:**
```bash
# 여러 VM에서 테스트
# VM1: Master
./master 3

# VM2: Worker 1
./worker <master-ip:port> -I input1 -O output

# VM3: Worker 2
./worker <master-ip:port> -I input2 -O output

# VM4: Worker 3
./worker <master-ip:port> -I input3 -O output

# 검증
ls output/partition.*
./verify_sort.sh output
```

**완료 조건:**
- [ ] 실제 분산 환경 테스트 성공 (여러 VM)
- [ ] 100MB+ 데이터 처리 성공
- [ ] Worker 재등록 테스트 통과
- [ ] Checkpoint 복구 테스트 통과
- [ ] 최종 발표 자료 완성

---

## 8. 참고 사항

### 8.1 파일 변경 사항

**수정된 파일:**
- `src/main/scala/distsort/master/MasterService.scala` (종료 신호 로직)
- `src/main/scala/distsort/master/Master.scala` (grace period, 진행 상황 메시지)
- `src/main/scala/distsort/worker/Worker.scala` (진행 상황 메시지)
- `src/main/resources/logback.xml` (로그 파일 리다이렉트)
- `master` (스크립트 정리)
- `worker` (스크립트 정리)

**새로 생성된 문서:**
- `SHUTDOWN_RACE_FIX.md` - Shutdown 해결 상세
- `SHUTDOWN_FIX_SUMMARY.md` - Shutdown 해결 요약
- `CLEAN_OUTPUT.md` - 출력 형식 가이드
- `test_shutdown.sh` - Shutdown 테스트 스크립트
- `test_input/README.md` - 테스트 데이터 가이드
- `test_output/README.md` - 출력 검증 가이드

### 8.2 문서 업데이트

**이번 주 추가된 문서:**
- `git_project_forder/week6/` (전체 소스 백업)
- `git_project_forder/meet_log/week6.md`
- `worklog/2025-11-23.md`

### 8.3 테스트 데이터 위치

**소규모 테스트:**
- `test_input/sample.bin` (100KB, 1000 레코드)

**대규모 테스트:**
- `input1/data.bin` (62MB, 650k 레코드)
- `input2/data.bin` (62MB, 650k 레코드)
- `input3/data.bin` (62MB, 650k 레코드)
- `input4/data.bin` (62MB, 650k 레코드)

### 8.4 로그 파일 위치

- **실시간 로그**: `/tmp/distsort.log`
- **확인 방법**: `tail -f /tmp/distsort.log`

---

**작성자**: Team Silver (권동연, 박범순, 임지훈)
**버전**: Week 6 Final
