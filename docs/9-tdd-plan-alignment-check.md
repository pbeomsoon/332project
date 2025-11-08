# TDD 문서와 Plan v3 일치성 검증

**작성일**: 2025-11-01
**목적**: 현재 TDD 진행 상황이 plan_ver3.md와 일치하는지 검증

---

## ✅ 일치하는 부분

### 1. Record 클래스 스펙 ✅

**plan_ver3.md (Section 3.1)**:
- 100 bytes total: 10-byte key + 90-byte value
- Key만 정렬 기준으로 사용
- Unsigned byte comparison

**quickstart-tdd-guide.md (Step 2-3)**:
- Record(key: 10 bytes, value: 90 bytes)
- Arrays.compareUnsigned 사용
- Ordered[Record] 구현

**실제 구현 (Record.scala)**:
- ✅ 10 bytes key + 90 bytes value
- ✅ Manual unsigned comparison (Java 8 호환)
- ✅ Ordered[Record] + implicit ordering

**결론**: **완벽히 일치** 🎯

---

### 2. BinaryRecordReader ✅

**plan_ver3.md (Section 12.3)**:
```scala
class BinaryRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Array[Byte]]
  // 100 bytes 읽기, EOF 처리
}
```

**quickstart-tdd-guide.md (Step 5)**:
- BufferedInputStream (1MB buffer)
- readRecord(): Option[Record]
- EOF, incomplete record 처리

**실제 구현 (BinaryRecordReader.scala)**:
- ✅ BufferedInputStream (1MB buffer)
- ✅ readRecord(): Option[Record]
- ✅ EOF, empty, incomplete 처리
- ✅ buffer.clone() (array sharing 버그 방지)

**결론**: **완벽히 일치** 🎯

---

### 3. 테스트 전략 ✅

**plan_ver3.md (Section 10)**:
- Unit test, Integration test, E2E test
- Given-When-Then 패턴

**docs/7-testing-strategy.md**:
- Phase별 테스트 전략
- TDD 사이클 엄격히 준수

**실제 구현**:
- ✅ Given-When-Then 패턴 사용
- ✅ Edge case 테스트 (invalid length, EOF, incomplete)
- ✅ 7 + 6 = 13개 테스트 모두 통과

**결론**: **완벽히 일치** 🎯

---

## ⚠️ 불일치 및 누락 부분

### 1. ❌ InputFormatDetector 우선순위 문제 (심각)

#### plan_ver3.md의 요구사항:

**Section 12.1 (매우 중요!)**:
> "Should work on both ASCII and binary input **without requiring an option**"

**Section 4.1.2 (Line 536-539) - Sampling 단계**:
```scala
for (file <- getAllInputFiles(inputDirs)) {
  // ⭐ 각 파일마다 형식 자동 감지
  val format = InputFormatDetector.detectFormat(file)
  val reader = RecordReader.create(format)
  // ...
}
```

**Section 4.2.1 (Line 611-613) - Sort 단계**:
```scala
def sortChunk(inputFile: File, ...) = {
  // ⭐ 파일 형식 자동 감지
  val format = InputFormatDetector.detectFormat(inputFile)
  val reader = RecordReader.create(format)
  // ...
}
```

**Section 7.1 (Line 1238) - Parallel Sort**:
```scala
def sortAll(inputFiles: List[File]): List[File] = {
  // ⭐ format 파라미터 제거 - 각 파일마다 자동 감지
  val futures = inputFiles.map { file =>
    executor.submit(new Callable[File] {
      override def call(): File = sortChunk(file)  // 자동 감지 사용
    })
  }
}
```

#### 현재 TDD 계획 (nextwork):

```
Cycle 3: BinaryRecordWriter
Cycle 4: AsciiRecordReader
Cycle 5: AsciiRecordWriter
Cycle 6: InputFormatDetector  ← 너무 늦음! ⚠️
```

#### 문제점:

**InputFormatDetector는 Phase 1 (Sampling)부터 필요합니다!**

현재 계획대로 하면:
- Cycle 3-5까지는 Binary만 지원
- Cycle 6에 가서야 ASCII 지원
- 하지만 **Phase 1 (Sampling)부터 자동 감지가 필수**

#### 권장 수정:

```
❌ 현재 순서:
  Cycle 3: BinaryRecordWriter
  Cycle 4: AsciiRecordReader
  Cycle 5: AsciiRecordWriter
  Cycle 6: InputFormatDetector

✅ 올바른 순서:
  Cycle 3: AsciiRecordReader     ← 먼저 구현
  Cycle 4: InputFormatDetector   ← Reader 선택을 위해 필요
  Cycle 5: BinaryRecordWriter
  Cycle 6: AsciiRecordWriter
```

**이유**:
1. Sampling과 Sort 단계에서 모두 InputFormatDetector 필요
2. InputFormatDetector는 BinaryRecordReader + AsciiRecordReader를 선택
3. 따라서 AsciiRecordReader를 먼저 구현해야 함
4. Writer는 나중에 구현해도 됨 (출력은 Phase 4)

---

### 2. ⚠️ RecordReader 추상화 누락

#### plan_ver3.md Section 12.2:

```scala
trait RecordReader {
  def readRecord(input: InputStream): Option[Array[Byte]]
}

object RecordReader {
  def create(format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader()
    case DataFormat.Ascii => new AsciiRecordReader()
  }
}
```

#### 현재 구현:

```scala
// BinaryRecordReader.scala
class BinaryRecordReader(file: File) {
  def readRecord(): Option[Record]
  def close(): Unit
}

// ❌ 공통 trait 없음!
// ❌ RecordReader.create() 없음!
```

#### 문제점:

- BinaryRecordReader와 AsciiRecordReader가 공통 인터페이스 없음
- InputFormatDetector가 Reader를 선택할 방법이 없음
- 코드 중복 발생 가능

#### 권장 수정:

```scala
// 추가 필요: RecordReader.scala
package distsort.core

import java.io.File

trait RecordReader {
  def readRecord(): Option[Record]
  def close(): Unit
  def getFile: File
}

object RecordReader {
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii => new AsciiRecordReader(file)
  }
}
```

---

### 3. ⚠️ AsciiRecordReader 구현 필요 (높은 우선순위)

#### plan_ver3.md Section 12.4:

```scala
class AsciiRecordReader extends RecordReader {
  override def readRecord(input: InputStream): Option[Array[Byte]] = {
    val line = new Array[Byte](102)  // key(10) + space(1) + value(90) + newline(1)
    // ASCII → Binary 변환
    // ...
  }
}
```

#### 현재 상태:

- ❌ 미구현
- ❌ TDD Cycle 4로 계획됨 (너무 늦음)

#### 권장:

**TDD Cycle 3으로 앞당겨야 함!**

---

### 4. ✅ BinaryRecordWriter 계획 (OK)

#### plan_ver3.md Section 4.2.2:

- BufferedOutputStream 사용
- 100 bytes 쓰기
- Atomic write (temp + rename)

#### nextwork 계획:

- TDD Cycle 3: BinaryRecordWriter
- 테스트 케이스 6개 설계 완료
- 구현 코드 스켈레톤 제공

**결론**: **계획 잘 되어 있음** ✅
(단, 순서를 Cycle 5로 변경 권장)

---

## 📊 전체 비교표

| 컴포넌트 | plan_ver3 필요 | TDD 계획 | 실제 구현 | 우선순위 | 상태 |
|---------|----------------|----------|-----------|----------|------|
| Record | Phase 2 | Cycle 1 | ✅ 완료 | 최고 | ✅ OK |
| BinaryRecordReader | Phase 1-2 | Cycle 2 | ✅ 완료 | 최고 | ✅ OK |
| **AsciiRecordReader** | **Phase 1-2** | **Cycle 4** | ❌ 미완 | **최고** | ⚠️ **우선순위 낮음** |
| **InputFormatDetector** | **Phase 1-2** | **Cycle 6** | ❌ 미완 | **최고** | ⚠️ **우선순위 낮음** |
| BinaryRecordWriter | Phase 4 | Cycle 3 | ❌ 미완 | 중 | ✅ OK |
| AsciiRecordWriter | Phase 4 | Cycle 5 | ❌ 미완 | 중 | ✅ OK |
| RecordReader trait | Phase 1-4 | ❌ 없음 | ❌ 미완 | 최고 | ❌ **누락** |

---

## 🔥 심각한 문제점

### 문제 1: Phase별 의존성 무시

**plan_ver3의 Phase 구조**:
```
Phase 0: 초기화
Phase 1: Sampling ← InputFormatDetector 필요! ⚠️
Phase 2: Sort & Partition ← InputFormatDetector 필요! ⚠️
Phase 3: Shuffle
Phase 4: Merge ← RecordWriter 필요
```

**현재 TDD 계획**:
```
Cycle 1-2: Binary Reader만 구현 ✅
Cycle 3: Binary Writer 구현 (Phase 4용) ← Phase 1-2는?
Cycle 4: ASCII Reader 구현
Cycle 6: InputFormatDetector 구현 ← 너무 늦음!
```

**문제**: Phase 1-2를 테스트하려면 InputFormatDetector가 필요한데, Cycle 6까지 기다려야 함!

---

### 문제 2: Milestone 1-2 검증 불가능

**plan_ver3 Milestone 2 (Section 13)**:
```
Milestone 2: Strategy A 구현 (Week 3-4)
- [ ] 샘플링 구현 (ASCII/Binary)  ← InputFormatDetector 필요!
- [ ] 정렬 및 파티셔닝 (N개 파티션) ← InputFormatDetector 필요!
```

**현재 계획**:
- Week 3 (현재): Binary만 지원
- InputFormatDetector는 Week 3 마지막 (Cycle 6)

**문제**: Milestone 2를 제대로 검증할 수 없음! (ASCII 입력 테스트 불가)

---

### 문제 3: Integration Test 지연

**docs/7-testing-strategy.md Phase 1.3**:
```
Integration Test:
- 실제 input 파일 (ASCII + Binary 혼합)
- Sampling → PartitionBoundaries 계산
```

**현재 계획으로는**:
- Cycle 3-5: Binary Writer, ASCII Reader/Writer 구현
- Cycle 6: 드디어 InputFormatDetector
- Cycle 6 이후에야 혼합 입력 테스트 가능

**문제**: Integration Test가 너무 늦게 시작됨

---

## ✅ 권장 조치사항

### 1. 즉시 조치: TDD Cycle 순서 변경

#### 변경 전 (현재 nextwork 계획):
```
Week 3 Day 2 (다음 세션):
  Cycle 3: BinaryRecordWriter (30분)
  Cycle 4: AsciiRecordReader (40분)
  Cycle 5: AsciiRecordWriter (30분)
  Cycle 6: InputFormatDetector (30분)
```

#### 변경 후 (권장):
```
Week 3 Day 2 (다음 세션):
  Cycle 3: RecordReader trait 추가 (10분)
  Cycle 4: AsciiRecordReader (40분)
  Cycle 5: InputFormatDetector (30분)

Week 3 Day 3:
  Cycle 6: BinaryRecordWriter (30분)
  Cycle 7: AsciiRecordWriter (30분)
```

#### 이유:

1. **Phase 1-2 우선**: Sampling과 Sort에 InputFormatDetector 필수
2. **Writer는 Phase 4**: Merge 단계에서 필요 (나중에 구현 가능)
3. **Integration Test 조기 시작**: ASCII/Binary 혼합 입력 테스트 가능

---

### 2. RecordReader 추상화 추가

**새 파일 생성**: `src/main/scala/distsort/core/RecordReader.scala`

```scala
package distsort.core

import java.io.File

/**
 * Abstract interface for reading records from different formats
 */
trait RecordReader {
  /**
   * Read next record from file
   * @return Some(Record) if available, None if EOF
   */
  def readRecord(): Option[Record]

  /**
   * Close the reader and release resources
   */
  def close(): Unit

  /**
   * Get the file being read
   */
  def getFile: File
}

/**
 * Data format types
 */
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

/**
 * Factory for creating format-specific readers
 */
object RecordReader {
  /**
   * Create a reader for the given file and format
   */
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii => new AsciiRecordReader(file)
  }

  /**
   * Create a reader with auto-detected format
   */
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    create(file, format)
  }
}
```

**리팩토링 필요**: BinaryRecordReader가 trait 구현하도록 수정

```scala
class BinaryRecordReader(file: File) extends RecordReader {
  // 현재 코드 그대로 유지
}
```

---

### 3. nextwork 문서 업데이트

**다음 세션 계획 수정**:

```markdown
## 🔜 다음 작업 계획 (수정됨)

### 즉시 작업 (Next Session)

#### Task 0: Git 커밋 ✅ (이미 완료)

#### Task 1: RecordReader 추상화 추가 (10분)
**목적**: BinaryRecordReader와 AsciiRecordReader의 공통 인터페이스 제공

**작업**:
1. RecordReader.scala 생성 (trait + companion object)
2. DataFormat sealed trait 정의
3. BinaryRecordReader가 trait 구현하도록 리팩토링
4. 기존 테스트 확인 (여전히 통과해야 함)

#### Task 2: AsciiRecordReader (40분) ← **우선순위 상향!**
**목적**: ASCII 형식 레코드 읽기

**ASCII 형식**:
```
Format: "48656C6C6F576F726C64 576F726C6448656C6C..." (201 chars + newline)
        [20 hex chars]      [1 space]    [180 hex chars]    [\n]
        = 10 bytes key      + 1 space    + 90 bytes value   + newline
```

**테스트 케이스 (RED)**:
1. Read valid ASCII record
2. Handle EOF
3. Invalid hex characters
4. Wrong line length
5. Case insensitive hex (0xAB vs 0xab)
6. Multiple records

**구현 (GREEN)**:
- Line-based reading (BufferedReader)
- Hex string → bytes conversion
- Validation (length, space position, hex validity)

#### Task 3: InputFormatDetector (30분) ← **우선순위 상향!**
**목적**: ASCII vs Binary 자동 감지

**감지 로직**:
```scala
def detectFormat(file: File): DataFormat = {
  // 1. 첫 1000 bytes 읽기
  // 2. ASCII printable 비율 계산
  // 3. > 90% → ASCII, otherwise Binary
}
```

**테스트 케이스**:
1. Detect Binary file
2. Detect ASCII file
3. Empty file (default to Binary)
4. Mixed content (majority rule)

#### Task 4: Integration Test (20분)
**목적**: Phase 1-2 통합 테스트

**테스트**:
```scala
"Sampling" should "work with mixed ASCII/Binary input" in {
  val binaryFile = createBinaryFile(1000)
  val asciiFile = createAsciiFile(1000)

  val sampler = new Sampler()
  val samples = sampler.extractSamples(List(binaryFile, asciiFile))

  samples should have length 2000
}
```
```

---

### 4. Milestone 검증 계획 업데이트

**Milestone 2 검증 (Week 3 말)**:

```bash
# ASCII + Binary 혼합 입력 테스트
gensort -b0 1000000 input_binary.dat
gensort -a 1000000 input_ascii.dat

# Sampling 테스트 (InputFormatDetector 사용)
sbt "testOnly distsort.core.InputFormatDetectorSpec"
sbt "testOnly distsort.integration.SamplingIntegrationSpec"

# 성공 조건:
✅ Binary 파일 자동 감지
✅ ASCII 파일 자동 감지
✅ 혼합 입력에서 샘플 추출
✅ 파티션 경계 계산
```

---

## 📋 수정된 Week 3 일정

### Day 1 (완료 - 2025-11-01)
- ✅ 프로젝트 초기 설정
- ✅ Record 클래스 (Cycle 1)
- ✅ BinaryRecordReader (Cycle 2)
- ✅ 테스트 검증 (13/13 passing)
- ✅ Git 커밋

**진행률**: 33% (2/7 components)

### Day 2 (다음 세션 - 권장 수정)
- [ ] RecordReader trait 추가
- [ ] AsciiRecordReader (Cycle 3)
- [ ] InputFormatDetector (Cycle 4)
- [ ] Integration Test (Phase 1-2)

**예상 진행률**: 71% (5/7 components)

### Day 3 (그 다음 세션)
- [ ] BinaryRecordWriter (Cycle 5)
- [ ] AsciiRecordWriter (Cycle 6)
- [ ] Phase 4 Integration Test

**예상 진행률**: 100% (7/7 components)

---

## 🎯 최종 권장사항

### 즉시 수정 필요 (Priority: 🔥 긴급)

1. **nextwork 문서 수정**
   - TDD Cycle 순서 변경
   - AsciiRecordReader → Cycle 3
   - InputFormatDetector → Cycle 4
   - Writers → Cycle 5-6

2. **다음 세션 계획 변경**
   - BinaryRecordWriter를 뒤로 미룸
   - AsciiRecordReader + InputFormatDetector 우선

3. **RecordReader 추상화 추가**
   - 공통 trait 정의
   - Factory 패턴 구현

### 장기 계획 (Priority: ⚠️ 중요)

4. **Integration Test 조기 시작**
   - Phase 1-2 통합 테스트
   - ASCII/Binary 혼합 입력

5. **Milestone 검증 기준 명확화**
   - Milestone 2: ASCII 지원 필수
   - gensort/valsort 검증

---

## ✅ 결론

### 현재 상태 평가

**잘된 점**:
- ✅ Record, BinaryRecordReader 완벽 구현
- ✅ TDD 프로세스 철저히 준수
- ✅ 테스트 품질 우수 (13/13 passing)
- ✅ Java 8 호환성 확보

**개선 필요**:
- ⚠️ **InputFormatDetector 우선순위 낮음** (가장 심각)
- ⚠️ RecordReader 추상화 누락
- ⚠️ TDD Cycle 순서가 Phase 의존성 무시

**영향도**:
- 🔴 **높음**: Phase 1-2 통합 테스트 불가
- 🔴 **높음**: Milestone 2 검증 불가
- 🟡 **중간**: 코드 중복 가능성

### 권장 조치

**즉시 (다음 세션)**:
1. nextwork 문서 업데이트
2. TDD Cycle 3-4 순서 변경
3. RecordReader 추상화 추가

**단기 (Week 3 내)**:
4. AsciiRecordReader 구현
5. InputFormatDetector 구현
6. Phase 1-2 Integration Test

**중기 (Week 4)**:
7. Writers 구현
8. Phase 4 Integration Test
9. E2E Test

---

**문서 작성**: 2025-11-01 18:10
**검토 필요**: nextwork 문서 수정
**조치 필요**: TDD Cycle 순서 변경

**Status**: ⚠️ **수정 권장**
**Priority**: 🔥 **긴급** (다음 세션 전에 계획 수정 필요)
