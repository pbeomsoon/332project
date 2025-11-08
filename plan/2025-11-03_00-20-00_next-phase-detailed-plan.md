# Phase 3-5 상세 구현 계획

**작성일**: 2025-11-03 00:20
**현재 상태**: Phase 0-2 완료 (80/80 테스트 통과)

---

## 📊 현재까지 완료된 작업

### ✅ Phase 0: Foundation (완료)
- Record 데이터 구조
- BinaryRecordReader
- RecordReader trait

### ✅ Phase 1: Input Processing (완료)
- AsciiRecordReader (gensort -a 형식)
- InputFormatDetector (자동 감지)
- RecordReader 팩토리 패턴

### ✅ Phase 2: Core Algorithms (완료)
- FileLayout (파일 시스템 관리)
- Sampler (결정적 샘플링)
- ExternalSorter (병렬 정렬)
- Partitioner (이진 탐색 파티셔닝)
- KWayMerger (힙 기반 병합)

---

## 🎯 Phase 3: Network Components (Week 4, Days 1-2)

### 3.1 gRPC Proto 정의 (Day 1 오전)
**파일**: `main/src/main/protobuf/distsort.proto`

```protobuf
// 구현해야 할 메시지들:
message RegisterWorkerRequest {
  string worker_id = 1;
  string host = 2;
  int32 port = 3;
}

message SampleKeysRequest {
  repeated bytes keys = 1;
}

message PartitionConfigResponse {
  repeated bytes boundaries = 1;
  int32 num_partitions = 2;
}

message ShuffleRequest {
  int32 partition_id = 1;
  bytes data = 2;
}
```

**작업 목록**:
1. [ ] proto 파일 작성
2. [ ] build.sbt에 protobuf 플러그인 추가
3. [ ] 코드 생성 확인
4. [ ] 테스트용 proto 메시지 생성

### 3.2 Master Service 구현 (Day 1 오후)
**파일**: `main/src/main/scala/distsort/master/MasterService.scala`

**주요 기능**:
```scala
class MasterService extends MasterServiceGrpc.MasterServiceImplBase {
  // Worker 등록 관리
  def registerWorker(request: RegisterWorkerRequest): RegisterWorkerResponse

  // 샘플 키 수집
  def submitSampleKeys(request: SampleKeysRequest): Empty

  // 파티션 설정 배포
  def getPartitionConfig(request: Empty): PartitionConfigResponse

  // Phase 동기화
  def reportPhaseComplete(request: PhaseCompleteRequest): PhaseCompleteResponse
}
```

**구현 순서**:
1. [ ] Worker 레지스트리 구현
2. [ ] Phase 동기화 (CountDownLatch)
3. [ ] 샘플 키 수집 및 정렬
4. [ ] Boundary 계산 로직
5. [ ] Health check 메커니즘

### 3.3 Worker Service 구현 (Day 2 오전)
**파일**: `main/src/main/scala/distsort/worker/WorkerService.scala`

**주요 기능**:
```scala
class WorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {
  // Shuffle 데이터 수신
  def receiveShuffleData(request: ShuffleRequest): ShuffleResponse

  // 상태 조회
  def getWorkerStatus(request: Empty): WorkerStatusResponse

  // Phase 전환
  def startPhase(request: StartPhaseRequest): Empty
}
```

**구현 순서**:
1. [ ] State Machine 통합
2. [ ] Shuffle 데이터 수신 버퍼
3. [ ] 백프레셔 메커니즘
4. [ ] 상태 리포팅

### 3.4 gRPC Client 구현 (Day 2 오후)
**파일**: `main/src/main/scala/distsort/client/*.scala`

**클라이언트 종류**:
1. [ ] MasterClient - Worker가 Master와 통신
2. [ ] WorkerClient - Master/Worker간 Shuffle 통신
3. [ ] Connection Pool 관리
4. [ ] Retry 로직 (exponential backoff)

---

## 🔧 Phase 4: Integration (Week 4, Days 3-5)

### 4.1 Master 구현 (Day 3)
**파일**: `main/src/main/scala/distsort/master/Master.scala`

**핵심 구성요소**:
```scala
class Master(numWorkers: Int, outputDir: String) {
  private val phaseTracker = new PhaseTracker(numWorkers)
  private val workerRegistry = new WorkerRegistry()
  private val sampleCollector = new SampleCollector()

  def run(): Unit = {
    // 1. Worker 등록 대기
    waitForWorkerRegistration()

    // 2. Sampling Phase
    coordinateSamplingPhase()

    // 3. Partition 경계 계산
    val boundaries = calculateBoundaries()

    // 4. Sorting Phase
    coordinateSortingPhase()

    // 5. Shuffle Phase
    coordinateShufflePhase()

    // 6. Merge Phase
    coordinateMergePhase()
  }
}
```

**테스트 시나리오**:
1. [ ] 3-Worker 등록 시나리오
2. [ ] Phase 전환 동기화
3. [ ] Worker 실패 감지
4. [ ] 재시작 메커니즘

### 4.2 Worker 구현 (Day 4)
**파일**: `main/src/main/scala/distsort/worker/Worker.scala`

**핵심 구성요소**:
```scala
class Worker(workerId: String, masterHost: String, inputDirs: Seq[String]) {
  private val stateMachine = new WorkerStateMachine()
  private val fileLayout = new FileLayout(inputDirs, outputDir)
  private val sampler = Sampler.forWorker(workerId)
  private val sorter = new ExternalSorter(fileLayout)

  def run(): Unit = {
    // 1. Master 등록
    registerWithMaster()

    // 2. Sampling 수행
    performSampling()

    // 3. Partition 설정 수신
    val partitionConfig = receivePartitionConfig()

    // 4. Local Sort
    performLocalSort()

    // 5. Shuffle
    performShuffle(partitionConfig)

    // 6. Merge
    performFinalMerge()
  }
}
```

**구현 우선순위**:
1. [ ] State Machine 통합
2. [ ] Phase별 실행 로직
3. [ ] 에러 복구 메커니즘
4. [ ] 리소스 정리

### 4.3 Shuffle 구현 (Day 5)
**파일**: `main/src/main/scala/distsort/shuffle/ShuffleManager.scala`

**핵심 기능**:
```scala
class ShuffleManager(workerId: String, partitioner: Partitioner) {
  // Outgoing shuffle (송신)
  def shuffleToWorkers(sortedChunks: Seq[File]): Unit

  // Incoming shuffle (수신)
  def receiveFromWorkers(): Map[Int, Seq[File]]

  // 백프레셔 관리
  def applyBackpressure(): Unit
}
```

**구현 체크리스트**:
1. [ ] N→M 파티션 매핑
2. [ ] 스트리밍 전송
3. [ ] 수신 버퍼 관리
4. [ ] Progress 트래킹

---

## 🧪 Phase 5: End-to-End Testing (Week 5)

### 5.1 통합 테스트 (Days 1-2)
**파일**: `main/src/test/scala/distsort/integration/*Spec.scala`

**테스트 시나리오**:
1. [ ] **SingleNodeSpec**: 단일 노드 전체 파이프라인
2. [ ] **ThreeWorkerSpec**: 3-Worker 시나리오
3. [ ] **FaultToleranceSpec**: Worker 실패 및 복구
4. [ ] **LargeScaleSpec**: 대용량 데이터 처리

### 5.2 성능 벤치마크 (Day 3)
**파일**: `main/src/test/scala/distsort/benchmark/*Benchmark.scala`

**벤치마크 항목**:
1. [ ] Throughput 측정 (MB/s)
2. [ ] Latency 분석 (phase별)
3. [ ] 메모리 사용량
4. [ ] CPU utilization
5. [ ] Network bandwidth

### 5.3 gensort/valsort 검증 (Day 4)
**검증 스크립트**: `scripts/validate.sh`

```bash
#!/bin/bash
# 1. gensort로 테스트 데이터 생성
gensort -a 10000000 input.txt

# 2. 분산 정렬 실행
./run-distsort.sh input.txt output.txt

# 3. valsort로 검증
valsort output.txt
```

### 5.4 문서화 (Day 5)
1. [ ] README.md 업데이트
2. [ ] 실행 가이드 작성
3. [ ] 성능 리포트
4. [ ] 트러블슈팅 가이드

---

## 📅 일정별 목표

### Week 4 (11/4 - 11/10)
**월요일 (11/4)**
- [ ] gRPC proto 정의
- [ ] build.sbt 설정
- [ ] 코드 생성 확인

**화요일 (11/5)**
- [ ] MasterService 구현
- [ ] WorkerService 구현
- [ ] 기본 통신 테스트

**수요일 (11/6)**
- [ ] Master 클래스 구현
- [ ] Phase 동기화 로직
- [ ] Worker 등록 관리

**목요일 (11/7)**
- [ ] Worker 클래스 구현
- [ ] State Machine 통합
- [ ] Phase별 실행 로직

**금요일 (11/8)**
- [ ] ShuffleManager 구현
- [ ] 통합 테스트 작성
- [ ] 디버깅

### Week 5 (11/11 - 11/17)
**월-화요일**
- [ ] End-to-End 테스트
- [ ] 3-Worker 시나리오 검증

**수요일**
- [ ] Fault Tolerance 테스트
- [ ] 복구 메커니즘 검증

**목요일**
- [ ] 성능 벤치마크
- [ ] gensort/valsort 검증

**금요일**
- [ ] 최종 문서화
- [ ] 프로젝트 정리

---

## ⚠️ 주의사항

### 1. Worker Re-registration 전략
- Worker ID 기반 중복 등록 방지
- Heartbeat timeout: 30초
- 재시작 시 상태 초기화

### 2. Memory Management
- Shuffle 버퍼: 최대 256MB
- Sorting 메모리: 512MB
- gRPC message 크기: 최대 4MB

### 3. Error Handling
- 모든 gRPC 호출에 retry 로직
- Exponential backoff (초기 1초, 최대 30초)
- Circuit breaker 패턴 적용

### 4. Performance Targets
- 10GB 데이터: < 5분
- Network utilization: > 80%
- CPU utilization: > 70%

---

## 🔍 다음 즉시 작업 (11/3 이후)

1. **gRPC 설정 (최우선)**
   - `build.sbt`에 gRPC 플러그인 추가
   - `distsort.proto` 파일 작성
   - 코드 생성 테스트

2. **Master 스켈레톤**
   - MasterService 기본 구조
   - Worker 레지스트리
   - Phase 트래커

3. **Worker 스켈레톤**
   - WorkerService 기본 구조
   - State Machine 연결
   - Master 연결 로직

이 계획에 따라 Phase 3부터 순차적으로 구현하면 프로젝트를 완성할 수 있습니다.