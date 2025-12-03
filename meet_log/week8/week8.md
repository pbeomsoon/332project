# Week 8 진행 상황 보고

**작성일**: 2025-12-04
**팀명**: Team Silver
**팀원**: 권동연, 박범순, 임지훈

---

## 1. 이번 주 진행 사항

### 1.1 성능 최적화

#### ✅ 정렬 성능 대폭 개선 (100%)

**문제:**
- 기존 `.sorted` 메서드가 싱글스레드로 동작
- 3.2GB 데이터 정렬 시 timeout 발생

**해결:**
```scala
// Before: 싱글스레드 정렬
val sorted = allRecords.sorted

// After: 멀티코어 병렬 정렬
val recordArray = allRecords.toArray
java.util.Arrays.parallelSort(recordArray, Record.comparator)
```

**Record.scala에 Comparator 추가:**
```scala
val comparator: java.util.Comparator[Record] = new java.util.Comparator[Record] {
  override def compare(x: Record, y: Record): Int = x.compare(y)
}
```

**결과:**
- 정렬 시간: ~60초 → ~6-8초 (약 10배 향상)

### 1.2 I/O 최적화

#### ✅ Bulk Read/Write 구현 (100%)

**ExternalSorter - Bulk Read:**
```scala
val BULK_SIZE = 10000  // 10000 records = 1MB
val bulkBuffer = new Array[Byte](RECORD_SIZE * BULK_SIZE)

// System.arraycopy로 효율적 복사
System.arraycopy(bulkBuffer, offset, key, 0, 10)
System.arraycopy(bulkBuffer, offset + 10, value, 0, 90)
```

**KWayMerger - Bulk Write:**
```scala
val BULK_SIZE = 10000  // 10000 records = 1MB
val buffer = new Array[Byte](BULK_SIZE * RECORD_SIZE)

// 버퍼가 가득 차면 한번에 write
if (bufferIndex >= BULK_SIZE) {
  out.write(buffer, 0, bufferIndex * RECORD_SIZE)
  bufferIndex = 0
}
```

**ShuffleManager - 버퍼 크기 최적화:**
```scala
// Before: 8MB per partition (288 partitions × 8MB = 2.3GB!)
val out = new BufferedOutputStream(..., 8 * 1024 * 1024)

// After: 256KB per partition (288 partitions × 256KB = 72MB)
val out = new BufferedOutputStream(..., 256 * 1024)
```

### 1.3 Heartbeat 설정 최적화

#### ✅ False Positive 방지 (100%)

**문제:**
- 정렬 중 Worker가 바쁘면 Heartbeat 지연
- Master가 살아있는 Worker를 죽었다고 오인

**해결:**
```scala
// MasterService.scala
private val heartbeatCheckInterval = 5.seconds  // 체크 주기
private val MISSED_HEARTBEAT_THRESHOLD = 6      // 6회 미수신 시 FAILED (30초)

// Worker.scala - Await timeout 단축
val response = Await.result(responseFuture, 3.seconds)  // 10초→3초

// Worker.scala - 등록 재시도 횟수 증가
private def registerWithMaster(retries: Int = 10, ...)  // 5→10 (63초 이상)
```

**효과:**
- Crash 감지: 30초
- 등록 재시도: 최대 127초
- 복구 Worker가 감지 전에 시작해도 자동으로 대기 후 등록

### 1.4 파티션 파일 매칭 버그 수정

#### ✅ 정확한 파티션 파일 필터링 (100%)

**문제:**
```scala
// Before: partition.30도 partition.3으로 매칭됨!
name.contains(s".$partitionId")
```

**해결:**
```scala
// After: 정확한 매칭
val partitionFiles = allFiles.filter { file =>
  val name = file.getName
  name == s"partition.$partitionId" ||                           // local
  name == s"partition.$partitionId.received" ||                  // old format
  name.matches(s"partition-$partitionId-[a-f0-9]{8}\\.received") // streaming
}
```

### 1.5 K-way Merge 버그 수정

#### ✅ 정렬 순서 보장 (100%)

**문제:**
- 여러 파티션 파일을 단순 append → 정렬 깨짐
- `valsort`에서 unordered records 에러

**해결:**
```scala
// Before: 단순 append (정렬 깨짐!)
val writer = RecordWriter.create(outputFile)
partitionFiles.foreach { file =>
  reader.readRecord() match { case Some(r) => writer.write(r) }
}

// After: K-way merge (정렬 유지!)
val merger = new KWayMerger(partitionFiles)
merger.mergeToFile(outputFile)
```

### 1.6 최종 데모 준비

#### ✅ README 문서 작성 (100%)

**내용:**
- 접속 방법 (Master/Worker SSH)
- 빌드 방법
- 실행파일 배포 방법
- Master/Worker 실행 방법
- Fault Tolerance 데모 방법
- 결과 검증 방법

---

## 2. 주요 성과

### 2.1 기능 검증

| 기능 | 상태 | 비고 |
|------|------|------|
| 멀티코어 정렬 | ✅ | Arrays.parallelSort |
| Bulk I/O | ✅ | 10000 records씩 처리 |
| K-way Merge | ✅ | 정렬 순서 보장 |
| Fault Tolerance | ✅ | Ctrl+C → 재실행으로 복구 |
| valsort 검증 | ✅ | SUCCESS |

### 2.2 성능 개선

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| 정렬 (3.2GB) | ~60초 | ~6-8초 | 10x |
| Shuffle 메모리 | 2.3GB | 72MB | 32x |
| Crash 감지 | 15초 | 30초 | 안정성 향상 |

### 2.3 안정성 검증

- ✅ Ctrl+C로 Worker 종료 후 재실행 → 자동 복구
- ✅ Sort 단계에서 crash 테스트 통과
- ✅ 복구 Worker가 정상적으로 Phase 합류
- ✅ valsort로 최종 출력 검증 성공

---

## 3. 개인별 진행 사항

### 권동연

**이번 주:**
- Arrays.parallelSort 적용
- Record.comparator 구현
- Bulk read/write 최적화
- 대규모 데이터 테스트

### 박범순

**이번 주:**
- ShuffleManager 메모리 최적화
- 파티션 파일 매칭 버그 수정
- K-way merge 버그 수정
- Fault tolerance 테스트

### 임지훈

**이번 주:**
- Heartbeat 설정 최적화
- 등록 재시도 로직 개선
- README 문서 작성
- Week 8 보고서 작성

---

## 4. 확정된 기술 결정 사항

### 4.1 정렬 알고리즘

**결정:** `java.util.Arrays.parallelSort`

**이유:**
- JVM 기본 제공, 추가 라이브러리 불필요
- Fork-Join 기반 멀티코어 활용
- 대용량 데이터에서 10배 성능 향상

### 4.2 Heartbeat 타이밍

**결정:**
```scala
heartbeatInterval = 5.seconds
MISSED_HEARTBEAT_THRESHOLD = 6  // 30초
registerWithMaster(retries = 10)  // 127초
```

**이유:**
- GC pause 등으로 인한 false positive 방지
- 복구 Worker가 감지 전에 시작해도 자동 대기

### 4.3 파티션 파일 매칭

**결정:** 정확한 문자열 매칭 + 정규식

**이유:**
- `contains`는 substring 매칭으로 오류 발생
- 정확한 매칭으로 파티션 중복/누락 방지

### 4.4 Merge 전략

**결정:** KWayMerger 사용 (min-heap 기반)

**이유:**
- 여러 정렬된 파일을 정렬 순서 유지하며 병합
- 메모리 효율적 (스트리밍 방식)

---

## 5. 파일 변경 사항

### 수정된 파일:

**Record.scala:**
- `comparator` 추가 (Java Comparator)

**ExternalSorter.scala:**
- `sortRecordRanges()`: parallelSort 적용
- `writeChunkBulk()`: Bulk write 최적화

**KWayMerger.scala:**
- `mergeToFile()`: Bulk write 최적화

**ShuffleManager.scala:**
- 버퍼 크기 8MB → 256KB

**Worker.scala:**
- `performMerge()`: KWayMerger 사용
- 파티션 파일 필터링 정규식 수정
- Heartbeat Await timeout 3초

**MasterService.scala:**
- `MISSED_HEARTBEAT_THRESHOLD`: 6

**README.md:**
- 실행 방법 위주로 재작성

---

## 6. 테스트 결과 (최종)

| 테스트 | 결과 | 비고 |
|--------|------|------|
| Small dataset (320MB) | ✅ | 정상 완료 |
| Big dataset (3.2GB) | ✅ | ~2분 내 완료 |
| Large dataset (32GB) | ✅ | 정상 완료 |
| Fault Tolerance | ✅ | Sort 중 crash → 복구 |
| valsort 검증 | ✅ | SUCCESS |

---

## 7. 데모 시나리오

### 7.1 정상 실행

```bash
# Terminal 1: Master
cd ~/main && ./master 3

# Terminal 2-4: Workers (각 VM에서)
java -cp ~/distsort.jar distsort.worker.Worker <IP:PORT> -I /dataset/small -O ~/output/small
```

### 7.2 Fault Tolerance 데모

1. 정상 실행 시작
2. Sort 단계에서 Worker 하나를 `Ctrl+C`로 종료
3. 같은 명령어로 Worker 재실행
4. Master가 crash 감지 → 복구 Worker 등록
5. 작업 완료까지 대기

### 7.3 결과 검증

```bash
cd ~/output/small
ls partition.* | sort -t. -k2 -n | xargs cat > /tmp/all_output
valsort /tmp/all_output
# Records: XXXXXX: OK
```

---

**작성자**: Team Silver (권동연, 박범순, 임지훈)
**버전**: Week 8 Final (2025-12-04)
