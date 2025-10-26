## 이번 주 진행 사항

### 설계 단계 완료
- 분산 정렬 시스템의 전체 설계 문서화 완료
  - **docs/** 폴더: 7개의 상세 설계 문서
  - **plan/** 폴더: 구현 가이드 및 요구사항 검증 문서

### 주요 설계 성과

#### 1. Fault Tolerance 전략 확정
- **결정**: Worker Re-registration (해석 B) 방식 채택
- **핵심 아이디어**:
  - Worker가 input/output 디렉토리 기반으로 deterministic ID 생성
  - Master가 동일한 input/output 조합 감지 시 동일한 worker ID 재할당
  - 재시작된 Worker가 원래 담당했던 partition을 다시 생성
- **장점**:
  - 한 Worker만 재시작, 나머지는 중단 없이 계속 작업
  - Worker-partition 일관성 유지
  - 전체 job 재시작 불필요

#### 2. 시스템 아키텍처 문서
- `0-implementation-decisions.md`: 핵심 아키텍처 결정 사항
- `1-phase-coordination.md`: Master-Worker 간 Phase 동기화 메커니즘
- `2-worker-state-machine.md`: Worker 상태 전환 로직
- `3-grpc-sequences.md`: 상세한 gRPC 프로토콜 시퀀스
- `4-error-recovery.md`: 포괄적인 장애 복구 전략
- `5-file-management.md`: 파일 시스템 레이아웃 및 디스크 관리
- `6-parallelization.md`: 멀티코어 병렬 처리 전략

#### 3. 구현 가이드
- `implementation_guide.md`: 완전한 구현 로드맵 작성
  - 모듈별 구현 세부사항
  - 주요 컴포넌트의 코드 스켈레톤
  - Milestone 기반 개발 계획 (Week 1-8)
  - 테스트 체크리스트

#### 4. 기술 사양 확정
- **통신**: gRPC + Protocol Buffers
- **파티션 전략**: N→N 매핑 (numPartitions = numWorkers)
- **Worker ID**: Input/output 디렉토리 기반 deterministic 생성
- **샘플링**: Fixed seed를 사용한 deterministic sampling (seed = workerID.hashCode)
- **병렬화**: Phase별 thread pool 설정

### 개인별 진행 사항
Week 1의 연구 분담에 따른 설계 완료:

**권동연 (yeon903)** - Sampling, Sort/Partition
- 샘플링 전략 및 경계 계산 방식 설계
- External sort와 병렬 chunk 처리 설계
- Binary search 기반 파티셔닝 로직 정의

**박범순 (pbs7818)** - Shuffle
- Semaphore 기반 flow control을 사용한 동시 shuffle 메커니즘 설계
- Idempotent partition 전송 프로토콜 계획
- Shuffle 복구 전략 정의

**임지훈 (Jih00nLim)** - Merge, Fault-Tolerant
- Priority queue를 사용한 K-way merge 설계
- Worker Re-registration 기반 fault tolerance 전략 확정
- Deterministic execution 보장 메커니즘 정의

## 다음 주 목표

### 구현 단계 착수
1. **프로젝트 설정**
   - Scala/sbt 프로젝트 구조 초기화
   - build.sbt에 의존성 설정 (gRPC, ScalaPB)
   - 로깅 프레임워크 설정

2. **네트워크 레이어 (Milestone 1)**
   - Protocol Buffers 메시지 정의 (distsort.proto)
   - gRPC stub 생성
   - 기본 Master/Worker skeleton 구현
   - 동적 포트 할당으로 Master-Worker 연결 테스트

3. **데이터 생성**
   - gensort를 사용한 테스트 입력 데이터 생성
   - ASCII와 binary 형식 자동 감지 검증
   - 다양한 크기의 테스트 데이터셋 준비 (1GB, 10GB)

4. **기본 워크플로우**
   - Worker 등록 플로우 구현
   - Master 출력 형식 검증 (IP:Port, Worker IPs)
   - N개 worker 등록 및 timeout 테스트

## 다음 주 개인별 목표

### 권동연 (yeon903)
- RecordReader 구현 (ASCII/Binary 자동 감지)
- Deterministic seed를 사용한 샘플링 모듈 개발
- Thread pool 기반 external sort 구현 시작

### 박범순 (pbs7818)
- gRPC 프로토콜 정의 구현
- Streaming을 사용한 shuffle sender/receiver 개발
- Partition 전송 테스트 케이스 작성

### 임지훈 (Jih00nLim)
- Worker state machine 구현
- 파일 레이아웃 관리 (FileLayout 클래스) 개발
- Master의 Worker re-registration 로직 구현

## 확정된 기술 결정 사항

### 아키텍처
- ✅ Fault Tolerance: Worker Re-registration 방식
- ✅ 통신: gRPC 사용
- ✅ 파티션 매핑: N→N (1:1)
- ✅ Deterministic execution: Fixed seed 사용

### 파일 구조
```
project_2025/
├── build.sbt
├── src/main/
│   ├── scala/distsort/
│   │   ├── Main.scala
│   │   ├── master/
│   │   ├── worker/
│   │   └── common/
│   └── protobuf/
│       └── distsort.proto
```

### Phase별 구현 순서
1. Week 3-4: 인프라 + 샘플링
2. Week 4-5: 정렬 + 파티셔닝
3. Week 5-6: Shuffle + Merge
4. Week 6: Fault Tolerance
5. Week 7-8: 테스트 + 최적화

## 다음 미팅 안건
1. 설계 문서 최종 검토 및 미결 사항 확인
2. Milestone 1 구현 태스크 분배
3. 개발 환경 및 클러스터 접근 설정
4. 초기 gRPC 프로토콜 작성 및 연결 테스트
5. Week 3 산출물 계획

---

**설계 문서**: `docs/` 및 `plan/` 폴더에서 전체 기술 사양 확인 가능
