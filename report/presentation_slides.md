# Distributed Sorting System
## Progress Presentation

**Team Silver**
- 권동연 (yeon903)
- 박범순 (pbs7818)
- 임지훈 (Jih00nLim)

**POSTECH CSED332 - Software Design Methods**
**2025 Fall Semester**

---

## Slide 1: 프로젝트 개요

### 목표
- **분산/병렬 정렬**: 여러 머신의 여러 디스크에 저장된 Key/Value 레코드 정렬
- **Fault Tolerance**: Worker 장애 시에도 시스템이 정상 동작
- **Scala 구현**: gRPC 기반 Master-Worker 아키텍처

### 요구사항
- **입력 형식**: ASCII/Binary 자동 감지 (옵션 없이)
- **입력 디렉토리**: 읽기 전용 (수정 금지)
- **출력 디렉토리**: 최종 파일만 (임시 파일 자동 정리)
- **포트**: 하드코딩 금지 (동적 할당)

### 기술 스택
- **언어**: Scala 2.13
- **빌드**: SBT
- **통신**: gRPC + Protocol Buffers
- **테스트**: ScalaTest (TDD)

---

## Slide 2: Challenge 1 - Memory Limitation

### 문제
```
입력 데이터: 50GB
메모리:      8GB

→ 입력이 메모리에 들어가지 않음!
```

### 해법 제시
**Disk-based Merge Sort (External Sort)**

```
50GB 입력
  ↓ Split into 100MB chunks
┌─────┬─────┬─────┬─────┐
│100MB│100MB│100MB│ ... │ (500개 chunks)
└─────┴─────┴─────┴─────┘
  ↓ Sort each chunk in memory
┌──────┬──────┬──────┬──────┐
│Sorted│Sorted│Sorted│ ...  │
└──────┴──────┴──────┴──────┘
  ↓ K-way merge (500 files)
┌────────────────────────────┐
│     50GB Sorted Output     │
└────────────────────────────┘
```

**핵심**: 메모리보다 큰 데이터를 디스크 기반으로 처리

---

## Slide 3: Challenge 1 해결 - 2-Pass External Sort

### Phase 1: Chunk Sort (병렬)
- 입력을 512MB chunk로 분할
- 각 chunk를 메모리 내 정렬 (병렬 처리)
- 정렬된 chunk를 디스크에 저장

### Phase 2: K-way Merge
- Min-heap을 사용한 병합
- 스트리밍 방식 (메모리 효율적)
- 500개 파일 → 1개 정렬된 파일

### 장점
- ✅ 임의 크기 입력 처리 (1KB ~ 10TB+)
- ✅ 병렬 정렬 (멀티코어 활용)
- ✅ 메모리 효율적 (스트리밍 병합)

---

## Slide 4: Challenge 2 - Distributed Storage

### 문제
```
입력 데이터: 10TB
단일 디스크:  1TB

→ 한 머신의 디스크에 들어가지 않음!
→ 데이터가 여러 머신에 분산 저장됨
```

### 요구사항
- 입력: 여러 머신에 분산 저장
- 출력: 여러 머신에 분산 저장
- 전체적으로 정렬된 결과 생성

### 해법 제시
**Master-Worker 아키텍처 + Distributed Sorting**

---

## Slide 5: Challenge 2 해결 - Master-Worker + Sampling

### 전체 아키텍처
```
         ┌─────────────┐
         │   Master    │
         │ - 샘플 수집  │
         │ - 파티션 계산│
         │ - Phase 조율│
         └──────┬──────┘
                │ gRPC
    ┌───────────┼───────────┐
    │           │           │
┌───▼───┐   ┌──▼────┐   ┌──▼────┐
│Worker0│   │Worker1│   │Worker2│
│ 50GB  │   │ 50GB  │   │ 50GB  │
│P0,P1,P2   │P3,P4,P5   │P6,P7,P8
└───────┘   └───────┘   └───────┘
```

### Phase 1: Sampling의 핵심
1. 각 Worker가 입력의 10% 샘플 추출
2. Master가 모든 샘플 수집 (400개)
3. **⭐ 샘플을 정렬** → 데이터 분포 파악
4. 균등한 간격으로 파티션 경계 선택
5. shuffleMap 생성 및 브로드캐스트

**결과**: 각 파티션의 예상 크기가 비슷함 (로드 밸런싱)

---

## Slide 6: Sampling 상세 과정

### 샘플 정렬 과정 (4 Workers, 12 Partitions 예시)

**Step 1-2**: 샘플 수집
```
Worker 0 샘플: [0x15, 0x89, 0x23, ...] (100개)
Worker 1 샘플: [0x67, 0x12, 0xAB, ...] (100개)
Worker 2 샘플: [0x34, 0x78, 0xCC, ...] (100개)
Worker 3 샘플: [0x99, 0x3F, 0xDD, ...] (100개)

allSamples: 400개 keys (모두 합침)
```

**Step 3**: Master가 샘플 정렬
```
sortedSamples: [0x01, 0x12, ..., 0xF3, 0xFF]
               ↑                            ↑
           가장 작은                   가장 큰
```

**Step 4**: 균등 분할 (step = 400 / 12 = 33)
```
┌────┬────┬────┬────┬────┬────┐
│ P0 │ P1 │ P2 │... │P10 │P11 │
└────┴────┴────┴────┴────┴────┘
0x00 0x2F 0x5A ...         0xFF

각 파티션 예상 크기: 50GB / 12 ≈ 4.17GB
```

---

## Slide 7: Challenge 3 - Worker Crash

### 문제
```
Scenario:
- Worker가 실행 중 crash (killed by OS)
- ⭐ 모든 중간 데이터 손실 ("All intermediate data is lost")
- 같은 노드에서 새 Worker 시작

Expectation:
- Fault-tolerant system
- 새 Worker가 동일한 출력 생성 (deterministic)
```

### 해법 제시
**2-Layer Fault Tolerance**
- Layer 1: Checkpoint (진행 상태 복구)
- Layer 2: Replication (중간 데이터 복구)

---

## Slide 8: Challenge 3 해결 - 2-Layer Fault Tolerance

### Layer 1: Checkpoint (진행 상태 복구)
**목적**: Worker가 어디까지 진행했는지 추적

- Phase 완료 시마다 자동 저장
- WorkerState 포함: processedRecords, shuffleMap, completedPartitions
- JSON 직렬화 → `/tmp/distsort/checkpoints/`
- Graceful Shutdown 통합 (30초 grace period)

### Layer 2: Replication (중간 데이터 복구)
**목적**: Worker crash 시 손실된 중간 파일 복구

- replicationFactor = 2 (원본 + 복제본 1개)
- Phase 2 완료 후 각 파티션을 다른 Worker에 복제
- Checksum 검증으로 데이터 무결성 확인
- Consistent Hashing으로 deterministic replica 선택

---

## Slide 9: 통합 복구 시나리오

### Worker 2 crashes during Shuffle

**Initial State**:
- Worker 0, 1, 2, 3 → Phase 2 완료
- Worker 2가 Shuffle 중 crash

**Recovery Process**:

**Step 1**: Checkpoint 로드 (Layer 1)
```
Worker 2' 시작
→ 최신 checkpoint 로드
→ currentPhase = PHASE_SORTING (마지막 완료)
→ shuffleMap, partitionBoundaries 복구
```

**Step 2**: 중간 파일 복구 (Layer 2)
```
Worker 2'가 손실된 파티션 확인: P6, P7, P8
→ Worker 1에서 P6 복구 (checksum ✅)
→ Worker 0에서 P7 복구 (checksum ✅)
→ Worker 3에서 P8 복구 (checksum ✅)
```

**Step 3**: 마지막 Phase부터 재개
```
Shuffle부터 시작 (Sampling/Sort 스킵!)
→ Merge → Complete
```

**Result**: ✅ Fault-tolerant system (슬라이드 요구사항 충족)

---

## Slide 10: 전체 시스템 - 5-Phase 흐름

```
Phase 0: Initialization
  └─ Worker 등록, index 할당

Phase 1: Sampling
  ├─ 샘플 추출 (10%)
  ├─ Master가 샘플 정렬
  └─ 파티션 경계 계산

Phase 2: Sort & Partition
  ├─ External Sort (512MB chunks)
  ├─ 파티션 경계로 분할
  └─ Replication (백업 생성)

Phase 3: Shuffle
  ├─ shuffleMap에 따라 파티션 전송
  ├─ gRPC streaming (1MB chunks)
  └─ 재시도 로직 (지수 백오프)

Phase 4: Merge
  ├─ K-way merge (min-heap)
  └─ 최종 partition.n 생성

Phase 5: Completion
  └─ 임시 파일 정리
```

---

## Slide 11: gRPC 통신의 핵심

### 왜 gRPC?

| 항목 | HTTP/REST | gRPC (선택) |
|------|-----------|------------|
| 성능 | JSON (느림) | Protocol Buffers (빠름) |
| Streaming | 제한적 | ✅ 네이티브 지원 |
| 타입 안전성 | 런타임 에러 | ✅ 컴파일 타임 체크 |

### Streaming의 필요성
```
문제: 4GB 파티션을 Worker 간 전송
❌ 한 번에 전송 → OutOfMemoryError

✅ Streaming 해결:
   4GB 파일
     ↓
   1MB씩 chunk로 분할
     ↓
   gRPC streaming 전송
     ↓
   메모리 사용: 4GB → 1MB (4000배 절약)
```

### 재시도 로직
- 지수 백오프: 1초 → 2초 → 4초
- Retryable 에러: UNAVAILABLE, DEADLINE_EXCEEDED
- Thundering Herd 방지

---

## Slide 12: 현재 진행 상황 (Week 1-5)

### ✅ 완료된 컴포넌트

**Week 1-2: 설계 단계**
- 시스템 아키텍처 정의
- Protocol Buffers 설계
- Fault Tolerance 전략 결정 (Checkpoint + Replication)
- 7개 설계 문서 작성

**Week 3: 프로젝트 구조**
- SBT 프로젝트 설정
- gRPC stub 생성
- Record 클래스 (5/5 tests passing)

**Week 4-5: Core I/O Components**
- RecordReader (Binary/ASCII), InputFormatDetector
- FileLayout, RecordWriter
- ExternalSorter, KWayMerger, Partitioner
- CheckpointManager, ReplicationManager

### 전체 진행률: 60%
- ✅ 설계 100% 완료
- ✅ 핵심 알고리즘 100% 완료
- 🔄 Master/Worker 통합 진행 중

---

## Slide 13: 향후 계획 (Week 6-8)

### Week 6: Master/Worker 통합
- Master 구현 (Worker 관리, Phase 동기화)
- Worker 구현 (5-Phase 실행)
- 2-Layer Fault Tolerance 통합
- Heartbeat 기반 장애 감지

### Week 7: 통합 테스트
- 전체 시스템 테스트 (4 workers)
- Fault Tolerance 검증 (Worker crash 시나리오)
- 성능 측정 (1GB → 10GB 데이터)

### Week 8: 최적화 및 마무리
- 성능 튜닝 (병렬화, 메모리 사용량)
- 최종 문서 작성
- 발표 준비

### 목표
- ✅ Challenge 1, 2, 3 완전 해결
- ✅ valsort 검증 통과
- ✅ 안정적인 fault-tolerant system

---

## Q&A

**주요 설계 결정 사항**:
1. **Challenge 1**: 2-Pass External Sort (512MB chunks)
2. **Challenge 2**: Master-Worker + Sampling (균등 분할)
3. **Challenge 3**: Checkpoint + Replication (2-Layer)

**개발 방법론**: TDD (Test-Driven Development)
- Red-Green-Refactor cycle
- 분산 시스템의 복잡성 관리
- 안전한 리팩토링

**기술적 성과**:
- gRPC streaming으로 메모리 효율 (4000배 개선)
- N→M Partition 전략으로 로드 밸런싱
- Deterministic recovery (checkpoint + consistent hashing)

**감사합니다!**
