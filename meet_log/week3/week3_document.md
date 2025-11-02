## 이번 주 진행 사항

### 구현 시작
- TDD(Test-Driven Development) 기반 개발 착수
  - 프로젝트 구조 설정 완료
  - SBT 빌드 환경 구성
  - ScalaTest 프레임워크 통합

### 주요 구현 성과

#### 1. Core Components 구현 시작
- **Record 클래스** (100% 완료)
  - 10-byte key + 90-byte value 구조 구현
  - Unsigned byte 비교 로직 구현
  - 100 bytes 직렬화 메서드 구현
  - 전체 테스트 케이스 통과

- **BinaryRecordReader** (80% 완료)
  - 기본 바이너리 읽기 기능 구현
  - 스트리밍 기반 읽기 최적화
  - RecordReader trait 통합 필요

- **입력 포맷 컴포넌트** (진행중)
  - AsciiRecordReader 구현 시작
  - InputFormatDetector 설계 완료
  - 자동 감지 메커니즘 계획 수립

#### 2. TDD 문서화 체계 구축
- **완성된 TDD 문서 세트**
  - `TDD-Master-Document.md`: 모든 설계 문서를 통합한 구현 가이드
  - `TDD-Test-Scenarios.md`: 100개 이상의 구체적 테스트 케이스
  - `TDD-Implementation-Checklist.md`: 주차별 구현 체크리스트

- **설계 문서 정렬**
  - 모든 `docs/` 폴더 문서와 TDD 계획 정렬
  - Phase 의존성에 따른 구현 순서 재정립
  - Critical path 항목 식별

#### 3. 핵심 기술 결정 검증
- **Auto-detection 구현 방식 확정**
  - 첫 1000 바이트 샘플링
  - ASCII ratio > 90% 기준
  - 명령행 옵션 없이 자동 처리

- **N→M 파티션 전략 상세화**
  - 3 workers → 9 partitions (worker당 3개)
  - 멀티코어 활용을 위한 병렬 merge 가능
  - Range-based assignment 구현 계획

### 개인별 진행 사항

**임지훈 (Jih00nLim)** - Core Data & File Management
- Record 클래스 완전 구현 및 테스트
- BinaryRecordReader 기본 구현
- TDD 문서 체계 전체 작성
- FileLayout 클래스 설계

**박범순 (pbs7818)** - State Management
- WorkerState trait 설계
- Phase coordination 메커니즘 연구
- CountDownLatch 기반 동기화 계획

**권동연 (yeon903)** - Parallelization
- ThreadPoolConfig 설계
- External sorter 병렬화 전략 수립
- 성능 벤치마크 기준 설정

## 다음 주 목표

### Week 4 주요 목표

1. **Core Components 완성 (Day 1-2)**
   - RecordReader trait 추상화 완료
   - AsciiRecordReader 완전 구현 (gensort -a format)
   - InputFormatDetector 구현 및 테스트
   - FileLayout 클래스 완성
   - Mixed input format 통합 테스트

2. **핵심 알고리즘 구현 (Day 3-4)**
   - Sampler: 10% 샘플링 rate 구현
   - ExternalSorter: 병렬 chunk sorting
   - Partitioner: Binary search 기반 구현
   - K-way Merger: Min-heap 사용

3. **gRPC 통신 시작 (Day 5)**
   - Protocol Buffers 정의
   - MasterService 기본 구현
   - WorkerService 기본 구현
   - Registration flow 테스트

### 테스트 마일스톤
- [ ] Phase 1-2 통합 테스트 통과
- [ ] Mixed format auto-detection 검증
- [ ] 병렬 정렬 성능 검증 (>50 MB/s)
- [ ] Master-Worker 등록 플로우 완성

## 다음 주 개인별 목표

### 임지훈 (Jih00nLim)
**담당**: Core Data Components & File Management
- RecordReader trait 계층 구조 완성
- AsciiRecordReader 전체 테스트 커버리지 달성
- InputFormatDetector 구현 및 최적화
- FileLayout 클래스 atomic operations 구현
- 통합 테스트 리드

**산출물**:
- [ ] 모든 입력 포맷 컴포넌트 작동
- [ ] 파일 관리 시스템 완성
- [ ] 통합 테스트 통과

### 박범순 (pbs7818)
**담당**: State Management & Coordination
- PhaseTracker 구현 (worker coordination)
- WorkerStateMachine 상태 전환 구현
- 동기화 메커니즘 구현 (Latches)
- 에러 복구 플로우 설계

**산출물**:
- [ ] 완전한 state machine 구현
- [ ] Phase coordination 작동
- [ ] 에러 복구 시나리오 테스트

### 권동연 (yeon903)
**담당**: Parallelization & Algorithms
- ThreadPoolConfig 구현
- Parallel ExternalSorter 생성
- K-way merger 구현
- ShuffleMap 최적화 (N→M strategy)

**산출물**:
- [ ] 병렬 정렬 작동
- [ ] 성능 벤치마크 달성 (>50 MB/s)
- [ ] 멀티코어 활용 검증

## 확정된 기술 결정 사항

### 구현 우선순위 (Phase 의존성 기반)
1. ✅ **InputFormatDetector 최우선** (Phase 1-2 필수)
2. ✅ **RecordReader 추상화 필수**
3. ✅ **AsciiRecordReader 우선순위 상향**
4. ✅ **Writer는 Phase 4로 연기**

### 핵심 설계 원칙
- **No Input Modification**: 입력 디렉토리 절대 수정 금지
- **Auto-detection**: 명령행 옵션 없이 자동 포맷 감지
- **N→M Strategy**: Worker당 여러 partition으로 멀티코어 활용
- **State-based Recovery**: 상태 기반 장애 복구

### 테스트 전략
```
Unit Tests:        70% (빠름, 매 커밋)
Integration Tests: 20% (보통, 매 PR)
E2E Tests:        10% (느림, 매 배포)
```

## 리스크 관리

### 현재 리스크
1. **gRPC 통합 복잡도**
   - 완화: 단순한 registration flow부터 시작
   - 대안: 필요시 REST API 사용

2. **성능 요구사항**
   - 목표: >50 MB/s throughput
   - 완화: 조기 성능 테스트
   - 최적화 포인트 식별됨

3. **시간 제약**
   - 구현까지 2주 남음
   - 완화: Critical path 항목 집중
   - 고급 기능 필요시 연기

## 측정 지표

### 코드 진행률
- **테스트 커버리지**: 현재 29% → 목표 80%
- **컴포넌트 완성**: 2/15 → 목표 10/15
- **코드 라인**: ~500 → 예상 ~5000

### 테스트 현황
- Unit Tests: 5개 통과 ✅
- Integration Tests: 0개 (구현 예정)
- E2E Tests: 0개 (Week 5)

## 다음 미팅 안건

1. Week 3 구현 진행사항 검토
2. TDD 접근법 평가 및 조정
3. Critical path 항목 우선순위 재확인
4. CI/CD 파이프라인 설정 논의
5. Week 4 checkpoint 일정 확정
6. 공유 테스트 데이터 저장소 생성

### Action Items
- [ ] GitHub Actions CI/CD 설정 (임지훈)
- [ ] gensort 테스트 데이터 생성 (권동연)
- [ ] gRPC proto 파일 초안 작성 (박범순)
- [ ] 주간 코드 리뷰 세션 일정 잡기
- [ ] Slack 일일 진행사항 업데이트 시작

---

**다음 업데이트**: 2025년 11월 10일 일요일 23:59
**작성자**: 임지훈 (Jih00nLim)
**검토**: 박범순, 권동연