# QuickStart: TDD Implementation Guide (v3 - Final)

**목적**: 모든 설계 문서와 완벽히 정렬된 TDD 구현 가이드
**작성일**: 2025-11-02
**버전**: 3.0 (최종 - 모든 문서와 일치)

---

## 🚨 Critical Changes from v2

### 1. **Phase 의존성 기반 TDD 순서 재정립**

```diff
- Cycle 3: BinaryRecordWriter (Phase 4용)
- Cycle 4: AsciiRecordReader
- Cycle 5: AsciiRecordWriter
- Cycle 6: InputFormatDetector

+ Cycle 2: RecordReader trait (NEW)
+ Cycle 3: BinaryRecordReader refactoring
+ Cycle 4: AsciiRecordReader (Phase 1-2 필수)
+ Cycle 5: InputFormatDetector (Phase 1-2 필수)
```

### 2. **핵심 이유**

**plan_ver3.md Line 536-539 (Phase 1 Sampling)**:
```scala
for (file <- getAllInputFiles(inputDirs)) {
  val format = InputFormatDetector.detectFormat(file)  // ⭐ 필수!
  val reader = RecordReader.create(format)
}
```

- Phase 1(Sampling)과 Phase 2(Sort)에서 InputFormatDetector가 **필수**
- Writer는 Phase 4(Merge)에서만 필요 → 나중에 구현 가능

---

## 📋 TDD Cycles - Complete Roadmap

### Phase 0: Foundation ✅

#### Cycle 1: Record (완료)
```scala
// ✅ 이미 구현됨
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record]
```

#### Cycle 2: RecordReader Abstraction (NEW - 10분)

**테스트 (RED)**:
```scala
class RecordReaderSpec extends AnyFlatSpec with Matchers {
  "DataFormat" should "have Binary and Ascii types" in {
    val binary = DataFormat.Binary
    val ascii = DataFormat.Ascii
    binary should not be ascii
  }

  "RecordReader trait" should "be implemented by BinaryRecordReader" in {
    val reader: RecordReader = new BinaryRecordReader(testFile)
    reader shouldBe a[RecordReader]
    reader.getFile shouldBe testFile
  }

  "RecordReader.create" should "create correct reader based on format" in {
    val reader = RecordReader.create(testFile, DataFormat.Binary)
    reader shouldBe a[BinaryRecordReader]
  }
}
```

**구현 (GREEN)**:
```scala
// RecordReader.scala
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

trait RecordReader {
  def readRecord(): Option[Record]
  def close(): Unit
  def getFile: File
}

object RecordReader {
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii =>
      throw new NotImplementedError("AsciiRecordReader not yet implemented")
  }
}
```

---

### Phase 1-2: Input Processing (Critical Path) 🔥

#### Cycle 3: BinaryRecordReader Refactoring (5분)

**리팩토링**:
```scala
// BinaryRecordReader.scala
class BinaryRecordReader(file: File) extends RecordReader {  // ← Add extends
  // ... 기존 구현 유지 ...

  override def getFile: File = file  // ← Add this method
}
```

#### Cycle 4: AsciiRecordReader (40분)

**ASCII 형식 (gensort -a)**:
```
총 201 characters + newline:
48656C6C6F576F726C64 576F726C6448656C6C6F...
└── 20 hex chars ──┘ └── 180 hex chars ────┘
    (10-byte key)        (90-byte value)
```

**테스트 (RED)**:
```scala
class AsciiRecordReaderSpec extends AnyFlatSpec with Matchers {

  def toHex(bytes: Array[Byte]): String =
    bytes.map("%02X".format(_)).mkString

  def createAsciiFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    records.foreach { r =>
      writer.println(s"${toHex(r.key)} ${toHex(r.value)}")
    }
    writer.close()
    tempFile
  }

  "AsciiRecordReader" should "read gensort ASCII format" in {
    // Given
    val records = Seq(
      Record(Array[Byte](1,2,3,4,5,6,7,8,9,10), Array.fill[Byte](90)(0))
    )
    val file = createAsciiFile(records)

    // When
    val reader = new AsciiRecordReader(file.toFile)
    val read = reader.readRecord()

    // Then
    read shouldBe defined
    read.get shouldBe records(0)
  }

  it should "handle case-insensitive hex" in {
    // 0xAB == 0xab
  }

  it should "reject invalid line length" in {
    // Must be exactly 201 characters
  }

  it should "reject invalid hex" in {
    // 'G' is not hex
  }

  it should "handle EOF" in {
    // Return None at end
  }

  it should "handle empty file" in {
    // Return None immediately
  }
}
```

**구현 (GREEN)**:
```scala
class AsciiRecordReader(file: File) extends RecordReader {
  private val reader = new BufferedReader(new FileReader(file), 1024 * 1024)

  override def readRecord(): Option[Record] = {
    val line = reader.readLine()
    if (line == null) return None

    // Validate length
    if (line.length != 201) {
      throw new IllegalArgumentException(s"Invalid length: ${line.length}")
    }

    // Validate space
    if (line.charAt(20) != ' ') {
      throw new IllegalArgumentException("Missing space at position 20")
    }

    // Parse hex
    val keyHex = line.substring(0, 20)
    val valueHex = line.substring(21, 201)

    val key = hexToBytes(keyHex)
    val value = hexToBytes(valueHex)

    Some(Record(key, value))
  }

  private def hexToBytes(hex: String): Array[Byte] = {
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  }

  override def close(): Unit = reader.close()
  override def getFile: File = file
}
```

#### Cycle 5: InputFormatDetector (30분)

**자동 감지 알고리즘**:
```
1. Read first 1000 bytes
2. Count ASCII printable (0x20-0x7E, \n, \r)
3. Ratio > 0.9 → ASCII, else → Binary
```

**테스트 (RED)**:
```scala
class InputFormatDetectorSpec extends AnyFlatSpec with Matchers {

  "InputFormatDetector" should "detect binary format" in {
    val file = createBinaryFile(Array.fill[Byte](1000)(0xFF))
    InputFormatDetector.detectFormat(file) shouldBe DataFormat.Binary
  }

  it should "detect ASCII format" in {
    val file = createAsciiFile(10) // 10 gensort records
    InputFormatDetector.detectFormat(file) shouldBe DataFormat.Ascii
  }

  it should "handle empty file" in {
    // Default to Binary
  }

  it should "use majority rule for mixed content" in {
    // 90% ASCII → ASCII
    // 80% ASCII → Binary
  }

  it should "handle small files" in {
    // < 1000 bytes
  }
}
```

**구현 (GREEN)**:
```scala
object InputFormatDetector extends LazyLogging {

  def detectFormat(file: File): DataFormat = {
    val buffer = new Array[Byte](1000)
    val input = new FileInputStream(file)

    try {
      val bytesRead = input.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}")
        return DataFormat.Binary
      }

      val asciiCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiCount.toDouble / bytesRead

      if (asciiRatio > 0.9) {
        logger.info(s"Detected ASCII: ${file.getName}")
        DataFormat.Ascii
      } else {
        logger.info(s"Detected Binary: ${file.getName}")
        DataFormat.Binary
      }
    } finally {
      input.close()
    }
  }
}
```

**RecordReader Factory 완성**:
```scala
object RecordReader {
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii => new AsciiRecordReader(file)  // ✅ Now works!
  }

  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)  // ✅ Auto-detect!
    create(file, format)
  }
}
```

---

### Phase 1-2: Integration Test (20분)

```scala
class Phase1IntegrationSpec extends AnyFlatSpec with Matchers {

  "Phase 1 Sampling" should "work with mixed input" in {
    // Given: Binary + ASCII files
    val binaryFile = createBinaryFile(1000)
    val asciiFile = createAsciiFile(1000)

    // When: Sample with auto-detection
    val samples = Seq(binaryFile, asciiFile).flatMap { file =>
      val reader = RecordReader.create(file)  // Auto-detect!

      val samples = mutable.ArrayBuffer[Record]()
      var count = 0

      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .foreach { record =>
          if (count % 10 == 0) samples += record  // 10% sample
          count += 1
        }

      reader.close()
      samples
    }

    // Then
    samples should have length 200  // 10% of 2000
  }

  "Phase 2 Sort" should "handle auto-detected formats" in {
    // Given
    val mixedFiles = createMixedInputDirectory()

    // When
    val sorter = new ExternalSorter()
    mixedFiles.foreach { file =>
      val reader = RecordReader.create(file)  // Auto-detect!
      // ... sorting logic ...
    }

    // Then: All sorted correctly
  }
}
```

---

### Phase 2-3: Core Algorithms (Week 3 Day 3-4)

#### Cycle 6: Sampler
```scala
class Sampler {
  def extractSamples(files: Seq[File], sampleRate: Double): Seq[Record] = {
    files.flatMap { file =>
      val reader = RecordReader.create(file)  // Uses auto-detection!
      // ... sampling logic ...
    }
  }
}
```

#### Cycle 7: ExternalSorter
```scala
class ExternalSorter {
  def sortChunk(file: File): File = {
    val reader = RecordReader.create(file)  // Auto-detection!
    // ... sorting logic ...
  }
}
```

#### Cycle 8: Partitioner
```scala
class Partitioner(boundaries: Array[Array[Byte]]) {
  def getPartition(record: Record): Int = {
    // Binary search in boundaries
  }
}
```

#### Cycle 9: ShuffleMap
```scala
object ShuffleMap {
  def create(numPartitions: Int, numWorkers: Int): Map[Int, Int] = {
    (0 until numPartitions).map { p =>
      p -> (p / (numPartitions / numWorkers))
    }.toMap
  }
}
```

---

### Phase 4: Output (Week 3 Day 5)

#### Cycle 10: BinaryRecordWriter
```scala
class BinaryRecordWriter(file: File) extends RecordWriter {
  private val output = new BufferedOutputStream(
    new FileOutputStream(file), 1024 * 1024
  )

  def writeRecord(record: Record): Unit = {
    output.write(record.toBytes)
  }

  def close(): Unit = output.close()
}
```

#### Cycle 11: AsciiRecordWriter
```scala
class AsciiRecordWriter(file: File) extends RecordWriter {
  private val writer = new BufferedWriter(new FileWriter(file))

  def writeRecord(record: Record): Unit = {
    val keyHex = toHex(record.key)
    val valueHex = toHex(record.value)
    writer.write(s"$keyHex $valueHex\n")
  }

  def close(): Unit = writer.close()
}
```

---

## 🎯 Milestone 검증

### Milestone 1: Core Components (Day 1-2)
```bash
# Run tests
sbt test

# Expected output:
[info] RecordSpec:
[info] - Record should store 10-byte key and 90-byte value
[info] - Record should compare by key only
[info] RecordReaderSpec:
[info] - DataFormat should have Binary and Ascii types
[info] - RecordReader.create should create correct reader
[info] BinaryRecordReaderSpec:
[info] - should read binary records
[info] AsciiRecordReaderSpec:
[info] - should read ASCII records
[info] InputFormatDetectorSpec:
[info] - should detect formats correctly
```

### Milestone 2: Phase 1-2 Integration (Day 2)
```bash
# Test with gensort data
gensort -b0 10000 test_binary.dat
gensort -a 10000 test_ascii.dat

# Run integration test
sbt "testOnly *IntegrationSpec"

# Verify auto-detection
sbt "runMain distsort.TestAutoDetection test_binary.dat test_ascii.dat"
```

---

## 📊 진행 추적

### Week 3 Timeline

#### Day 1 (완료)
- ✅ Cycle 1: Record
- ✅ Cycle 2: BinaryRecordReader (partial)

#### Day 2 (현재)
- [ ] Cycle 2: RecordReader trait
- [ ] Cycle 3: BinaryRecordReader refactoring
- [ ] Cycle 4: AsciiRecordReader
- [ ] Cycle 5: InputFormatDetector
- [ ] Integration tests

#### Day 3
- [ ] Cycle 6: Sampler
- [ ] Cycle 7: ExternalSorter
- [ ] Cycle 8: Partitioner

#### Day 4
- [ ] Cycle 9: ShuffleMap
- [ ] Cycle 10: BinaryRecordWriter
- [ ] Cycle 11: AsciiRecordWriter

#### Day 5
- [ ] FileLayout
- [ ] End-to-end test
- [ ] Performance optimization

---

## ✅ 즉시 실행 체크리스트

### Step 1: RecordReader trait (10분)
```bash
# 1. Create file
touch src/main/scala/distsort/core/RecordReader.scala

# 2. Write test
touch src/test/scala/distsort/core/RecordReaderSpec.scala

# 3. Run test (RED)
sbt "testOnly distsort.core.RecordReaderSpec"

# 4. Implement (GREEN)
# 5. Run test again
sbt "testOnly distsort.core.RecordReaderSpec"
```

### Step 2: Refactor BinaryRecordReader (5분)
```bash
# 1. Add "extends RecordReader"
# 2. Add getFile method
# 3. Run existing tests (should still pass)
sbt "testOnly distsort.core.BinaryRecordReaderSpec"
```

### Step 3: AsciiRecordReader (40분)
```bash
# 1. Create test
touch src/test/scala/distsort/core/AsciiRecordReaderSpec.scala

# 2. Run test (RED)
sbt "testOnly distsort.core.AsciiRecordReaderSpec"

# 3. Create implementation
touch src/main/scala/distsort/core/AsciiRecordReader.scala

# 4. Run test (GREEN)
sbt "testOnly distsort.core.AsciiRecordReaderSpec"
```

### Step 4: InputFormatDetector (30분)
```bash
# Similar process...
```

### Step 5: Integration Test (20분)
```bash
# Create test file
touch src/test/scala/distsort/integration/Phase1IntegrationSpec.scala

# Run all tests
sbt test

# Check coverage
sbt clean coverage test coverageReport
```

---

## 🔥 핵심 차이점 (v2 → v3)

### 1. **InputFormatDetector 최우선**
- Phase 1-2에서 즉시 필요
- 모든 파일 읽기의 기반

### 2. **RecordReader 추상화 필수**
- Factory pattern 구현
- Auto-detection 지원

### 3. **Writer는 나중에**
- Phase 4에서만 필요
- Input이 우선

### 4. **Integration Test 조기 시작**
- Phase 1-2 검증
- Mixed input 테스트

---

## 📚 참조 문서

### 필수 확인
1. `plan_ver3.md` Section 4.1.2, 12
2. `docs/0-implementation-decisions.md` Section 6.1
3. `docs/7-testing-strategy.md`
4. `docs/9-tdd-plan-alignment-final.md`

---

## 🎯 성공 기준

### Technical
- [ ] Auto-detection works (no --ascii flag)
- [ ] gensort -a format supported
- [ ] Mixed input handled correctly
- [ ] Phase 1-2 integration tests pass

### Performance
- [ ] < 10ms per file detection
- [ ] < 2s for 1000 ASCII records
- [ ] < 1s for 1000 binary records

### Quality
- [ ] 90%+ test coverage
- [ ] All edge cases handled
- [ ] TDD cycle strictly followed

---

**Version**: 3.0 (Final)
**Date**: 2025-11-02
**Status**: ✅ **READY - Aligned with all documents**
**Next**: Start with RecordReader trait (Cycle 2)