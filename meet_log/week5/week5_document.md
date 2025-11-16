# Week 5 진행 상황 보고

**작성일**: 2025-11-12
**팀명**: Team Silver
**팀원**: 권동연, 박범순, 임지훈

---

## 1. 이번 주 진행 사항

### 1.1 구현 완료 항목

#### ✅ Core I/O Components (100%)

**1. RecordReader 추상화 및 구현**
- `RecordReader` trait 정의 (추상 인터페이스)
- `BinaryRecordReader` 구현 (100-byte 고정 길이)
- `AsciiRecordReader` 구현 (102-byte: key + space + value + newline)
- Factory pattern으로 형식별 리더 생성

**2. InputFormatDetector (자동 형식 감지)**
- 파일의 첫 1000 bytes 샘플링
- ASCII printable 비율 계산
- Threshold 90%로 ASCII/Binary 자동 판별
- PDF 요구사항 충족: "without requiring an option"

**3. FileLayout (파일 시스템 관리)**
- 입력 디렉토리 검증 (읽기 전용)
- 출력 디렉토리 관리
- 임시 파일 구조 생성
- 디스크 공간 사전 확인
- 임시 파일 정리 (cleanup)

**4. RecordWriter**
- Binary/ASCII 형식 출력 지원
- 버퍼링 (1MB) for I/O 성능
- Atomic write (temp + rename)

### 1.2 설계 문서 정리

**문서 검증 및 업데이트**
- `docs/5-file-management.md` 최종 검토
- `plan/implementation_guide.md` 업데이트
- Week 5 README 작성

### 1.3 발표 준비

**중간 발표 자료 작성**
- `report/progress_presentation.md` 생성
- TDD 기반 개발 방법론 설명
- 전체 시스템 아키텍처 정리
- 핵심 설계 결정사항 문서화

---

## 2. 주요 성과

### 2.1 PDF 요구사항 충족

| 요구사항 | 구현 상태 | 검증 방법 |
|---------|----------|----------|
| ASCII/Binary 자동 감지 | ✅ 완료 | InputFormatDetector 테스트 |
| 입력 디렉토리 보호 | ✅ 완료 | FileLayout validation |
| 임시 파일 정리 | ✅ 완료 | cleanup 로직 구현 |



---

## 3. 다음 주 목표 (Week 6)

### 3.1 Algorithms 구현

**1. ExternalSorter**
- 2-Pass External Sort 알고리즘
- Chunk-based 메모리 내 정렬
- 병렬 정렬 (ThreadPool)
- K-way merge with Priority Queue

**2. Partitioner**
- Range-based partitioning
- Binary search로 파티션 ID 찾기
- N개 또는 M개 파티션 지원

**3. KWayMerger**
- Min-heap 기반 병합
- 여러 정렬된 파일 → 하나의 정렬된 파일
- Backpressure 제어 (메모리 사용량)

### 3.2 통합 테스트

- ExternalSorter + Partitioner 통합
- 로컬에서 정렬 동작 검증
- 성능 측정 (100MB 데이터)

### 3.3 발표 준비

- 중간 발표 슬라이드 최종 정리
- 데모 시나리오 준비
- Q&A 예상 질문 준비

---

## 4. 개인별 진행 사항 및 다음 주 목표

### 권동연

**이번 주:**
- RecordReader 추상화 설계
- BinaryRecordReader 구현
- AsciiRecordReader 구현
- 테스트 케이스 작성 (15개)

**다음 주:**
- ExternalSorter 구현
- Parallel sorting with ThreadPool
- Chunk management 로직
- External sort 테스트

### 박범순

**이번 주:**
- InputFormatDetector 구현
- FileLayout 클래스 설계 및 구현
- 디스크 공간 검증 로직
- Cleanup 로직 구현

**다음 주:**
- Partitioner 구현
- Range-based partition 로직
- Binary search 최적화
- Partition 테스트

### 임지훈

**이번 주:**
- RecordWriter 구현
- Week 5 README 작성
- 중간 발표 자료 작성 (`report/progress_presentation.md`)
- 문서 정리 및 통합

**다음 주:**
- KWayMerger 구현
- Priority Queue 기반 merge 로직
- Atomic write 구현
- Integration test 작성

---

## 5. 확정된 기술 결정 사항

### 5.1 ASCII/Binary 자동 감지

**결정:**
```scala
object InputFormatDetector {
  private val ASCII_THRESHOLD = 0.9
  private val SAMPLE_SIZE = 1000

  def detectFormat(file: File): DataFormat = {
    // 첫 1000 bytes 샘플링
    // ASCII ratio > 90% → Ascii, 아니면 Binary
  }
}
```

**이유:**
- PDF 명시: "without requiring an option"
- 각 파일마다 독립적 감지 (혼합 입력 지원)
- Threshold 90%는 실험적으로 결정

### 5.2 External Sort 전략

**결정:**
- 2-Pass External Sort (Chunk Sort + K-way Merge)
- Chunk size: 100MB (메모리 기반 동적 결정)
- 병렬 정렬: `numCores` 스레드

**이유:**
- PDF 제약: "입력 파일이 32MB라고 가정하지 말 것"
- 임의 크기 지원 (1KB ~ 10GB+)
- 멀티코어 활용

### 5.3 파일 시스템 레이아웃

**결정:**
```
Input:    /data1/input/         (읽기 전용)
Output:   /home/gla/data/       (최종 partition.*)
Temp:     /tmp/sort_work_${id}/ (임시 파일, 자동 삭제)
```

**이유:**
- PDF 요구사항: 입력 디렉토리 수정 금지
- 임시 파일과 출력 파일 분리
- 작업 완료 후 자동 정리

---

## 6. 이슈 및 해결

### 이슈 1: ASCII/Binary 혼합 입력 처리

**문제:**
- 일부 파일은 Binary, 일부는 ASCII일 때 어떻게?

**해결:**
- 각 파일마다 독립적으로 형식 감지
- RecordReader 팩토리 패턴으로 동적 생성

### 이슈 2: 디스크 공간 부족 대비

**문제:**
- 정렬 중 디스크 공간 부족 시?

**해결:**
- 시작 전 디스크 공간 검증
- 필요 공간 = inputSize * 2 (임시 파일 고려)
- 여유 공간 50% 확보

---

## 7. 다음 주 마일스톤

### Milestone 3: Algorithms (Week 6)

**목표:**
- ExternalSorter, Partitioner, KWayMerger 구현
- 로컬 정렬 동작 검증
- 100MB 데이터 정렬 성공

**검증 기준:**
```bash
# 테스트 데이터 생성
gensort -b0 1000000 test_input.dat  # 100MB

# 로컬 정렬 실행 (Worker 없이)
sbt "runMain distsort.LocalSortTest test_input.dat test_output.dat"

# 검증
valsort test_output.dat
# 예상: SUCCESS
```

**완료 조건:**
- [ ] ExternalSorter 테스트 12개 통과
- [ ] Partitioner 테스트 8개 통과
- [ ] KWayMerger 테스트 10개 통과
- [ ] Integration 테스트 5개 통과
- [ ] valsort 검증 통과

---

## 8. 참고 사항

### 8.1 문서 업데이트

**이번 주 추가된 문서:**
- `git_project_forder/week5_work/README.md`
- `report/progress_presentation.md`
- `meet_log/week5/week5_document.md`

---
