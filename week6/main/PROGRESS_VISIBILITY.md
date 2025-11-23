# Progress Visibility Feature

**작성일**: 2025-11-23
**목적**: 사용자에게 실시간 진행 상황 제공 (stdout 깔끔 유지)

---

## 개요

로그를 파일로 리다이렉트하면서 발생한 문제를 해결:
- **문제**: 로그가 `/tmp/distsort.log`로 가면서 사용자는 진행 상황을 전혀 볼 수 없음
- **해결**: stderr로 진행 상황 메시지를 출력하여 가시성 제공
- **효과**: stdout은 깔끔 (2줄), 사용자는 진행 상황 확인 가능

---

## 출력 스트림 분리

### stdout (표준 출력)
```
141.223.91.80:30040
141.223.91.81, 141.223.91.82, 141.223.91.83
```
- **용도**: 필수 정보만 (Master IP:port, Worker IPs)
- **특징**: 파이프라인 친화적, 파싱 용이

### stderr (표준 에러)
```
[Master] Waiting for 3 workers to register...
[Master] ✓ All 3 workers registered
[Master] Phase 1/4: Sampling...
[Worker-0] Phase 1/4: Sampling...
[Worker-1] Phase 1/4: Sampling...
[Worker-2] Phase 1/4: Sampling...
[Worker-0] ✓ Sampling complete
[Worker-1] ✓ Sampling complete
[Worker-2] ✓ Sampling complete
[Master] ✓ Sampling complete
[Master] ✓ Computed 9 partition boundaries
[Master] Phase 2/4: Sorting...
[Worker-0] Phase 2/4: Sorting...
[Worker-1] Phase 2/4: Sorting...
[Worker-2] Phase 2/4: Sorting...
...
[Master] ✓ Workflow completed successfully!
```
- **용도**: 진행 상황 메시지
- **특징**: 사용자가 실시간으로 확인

### File (/tmp/distsort.log)
```
15:30:45.123 [main] INFO  Master - Starting Master node...
15:30:45.234 [grpc-worker-1] DEBUG MasterService - Worker registration: worker-0
...
```
- **용도**: 상세 디버그 로그
- **특징**: 타임스탬프, 스레드 정보, 로그 레벨 포함

---

## Master 진행 상황 메시지

### 구현 위치
`src/main/scala/distsort/master/Master.scala`

### 메시지 타이밍

| 타이밍 | 메시지 | 코드 위치 |
|--------|--------|-----------|
| Worker 등록 대기 | `[Master] Waiting for N workers to register...` | Master.scala:99 |
| Worker 등록 완료 | `[Master] ✓ All N workers registered` | Master.scala:104 |
| Sampling 시작 | `[Master] Phase 1/4: Sampling...` | Master.scala:108 |
| Sampling 완료 | `[Master] ✓ Sampling complete` | Master.scala:112 |
| Boundary 계산 완료 | `[Master] ✓ Computed N partition boundaries` | Master.scala:122 |
| Sorting 시작 | `[Master] Phase 2/4: Sorting...` | Master.scala:126 |
| Sorting 완료 | `[Master] ✓ Sorting complete` | Master.scala:131 |
| Shuffling 시작 | `[Master] Phase 3/4: Shuffling...` | Master.scala:135 |
| Shuffling 완료 | `[Master] ✓ Shuffling complete` | Master.scala:140 |
| Merging 시작 | `[Master] Phase 4/4: Merging...` | Master.scala:144 |
| Merging 완료 | `[Master] ✓ Merging complete` | Master.scala:149 |
| Workflow 완료 | `[Master] ✓ Workflow completed successfully!` | Master.scala:152 |

### 코드 예시
```scala
// Phase 1: Sampling
System.err.println("[Master] Phase 1/4: Sampling...")
if (!masterService.waitForPhaseCompletion(WorkerPhase.PHASE_SAMPLING)) {
  throw new RuntimeException("Timeout waiting for sampling phase")
}
System.err.println("[Master] ✓ Sampling complete")
```

---

## Worker 진행 상황 메시지

### 구현 위치
`src/main/scala/distsort/worker/Worker.scala`

### 메시지 타이밍

각 Worker는 자신의 인덱스를 포함하여 메시지 출력:

| Phase | 시작 메시지 | 완료 메시지 | 코드 위치 |
|-------|------------|------------|-----------|
| Sampling | `[Worker-N] Phase 1/4: Sampling...` | `[Worker-N] ✓ Sampling complete` | Worker.scala (performSampling) |
| Sorting | `[Worker-N] Phase 2/4: Sorting...` | `[Worker-N] ✓ Sorting complete (M chunks)` | Worker.scala (performLocalSort) |
| Shuffling | `[Worker-N] Phase 3/4: Shuffling...` | `[Worker-N] ✓ Shuffling complete` | Worker.scala (performShuffle) |
| Merging | `[Worker-N] Phase 4/4: Merging...` | `[Worker-N] ✓ Merging complete` | Worker.scala (performMerge) |
| 전체 완료 | - | `[Worker-N] ✓ All phases complete!` | Worker.scala (performMerge) |

### 코드 예시
```scala
private def performSampling(): Unit = {
  System.err.println(s"[Worker-$workerIndex] Phase 1/4: Sampling...")

  // ... sampling logic ...

  System.err.println(s"[Worker-$workerIndex] ✓ Sampling complete")
}

private def performLocalSort(): Seq[File] = {
  System.err.println(s"[Worker-$workerIndex] Phase 2/4: Sorting...")

  // ... sorting logic ...

  System.err.println(s"[Worker-$workerIndex] ✓ Sorting complete (${sortedChunks.length} chunks)")
  sortedChunks
}
```

---

## 메시지 형식 규칙

### 1. 태그 형식
- Master: `[Master]`
- Worker: `[Worker-N]` (N은 Worker 인덱스 0부터 시작)

### 2. Phase 표시
- `Phase 1/4: Sampling`
- `Phase 2/4: Sorting`
- `Phase 3/4: Shuffling`
- `Phase 4/4: Merging`

### 3. 완료 표시
- 체크마크: `✓`
- 예시: `✓ Sampling complete`

### 4. 추가 정보
- 필요시 괄호로 추가 정보 제공
- 예시: `✓ Sorting complete (3 chunks)`

---

## 실행 예시

### Terminal 1: Master
```bash
$ ./master 3
141.223.91.80:30040
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
141.223.91.81, 141.223.91.82, 141.223.91.83
$
```

### Terminal 2-4: Workers
```bash
$ ./worker 141.223.91.80:30040 -I input1 -O output
[Worker-0] Phase 1/4: Sampling...
[Worker-0] ✓ Sampling complete
[Worker-0] Phase 2/4: Sorting...
[Worker-0] ✓ Sorting complete (3 chunks)
[Worker-0] Phase 3/4: Shuffling...
[Worker-0] ✓ Shuffling complete
[Worker-0] Phase 4/4: Merging...
[Worker-0] ✓ Merging complete
[Worker-0] ✓ All phases complete!
$
```

---

## stdout 파이프라인 활용

stderr로 진행 상황을 보내므로 stdout은 여전히 파이프라인 친화적:

```bash
# Master IP만 추출
./master 3 | head -1

# Worker IPs만 추출
./master 3 | tail -1

# 스크립트에서 활용
MASTER_ADDR=$(./master 3 2>/dev/null | head -1)
./worker $MASTER_ADDR -I input1 -O output
```

진행 상황 메시지는 stderr로 출력되므로 `2>/dev/null`로 무시 가능:
```bash
# 진행 상황 숨기기
./master 3 2>/dev/null

# 진행 상황만 파일로 저장
./master 3 2>progress.log

# stdout과 stderr 모두 저장
./master 3 >output.txt 2>progress.log
```

---

## 장점

### 1. 깔끔한 stdout
- Slides 예시 준수 (2줄만 출력)
- 파이프라인/스크립트 친화적
- 파싱 용이

### 2. 실시간 가시성
- 사용자는 진행 상황 실시간 확인
- Worker 별 진행 상황 명확히 파악
- 어느 Phase에서 시간이 걸리는지 확인 가능

### 3. 유연한 로그 관리
- stderr 메시지는 필요 시 파일로 리다이렉트 가능
- 상세 로그는 별도 파일에 보관
- 용도에 따라 선택적 확인

---

## 관련 파일

**수정된 파일:**
- `src/main/scala/distsort/master/Master.scala` - Master 진행 상황 메시지
- `src/main/scala/distsort/worker/Worker.scala` - Worker 진행 상황 메시지

**문서:**
- `CLEAN_OUTPUT.md` - 출력 형식 가이드
- `git_project_forder/meet_log/week6/week6.md` - Week 6 진행 상황 보고

**참고:**
- `src/main/resources/logback.xml` - 로그 파일 설정
- `/tmp/distsort.log` - 상세 로그 파일 위치
