# TDD 문서와 Plan v3 최종 정렬 (Final Alignment)

**작성일**: 2025-11-02
**목적**: 모든 설계 문서와 완벽히 일치하는 TDD 구현 가이드

---

## 🎯 핵심 수정사항 요약

### 1. **Phase별 의존성 기반 TDD 순서 재정립**

#### ❌ 이전 순서 (문제점)
```
Cycle 1: Record ✅
Cycle 2: BinaryRecordReader ✅
Cycle 3: BinaryRecordWriter (Phase 4용) ← Phase 1-2는?
Cycle 4: AsciiRecordReader
Cycle 5: AsciiRecordWriter
Cycle 6: InputFormatDetector ← 너무 늦음!
```

#### ✅ 수정된 순서 (Phase 의존성 반영)
```
=== Phase 0: Foundation ===
Cycle 1: Record ✅ (완료)
Cycle 2: RecordReader trait + DataFormat (NEW)

=== Phase 1-2: Input Processing (필수) ===
Cycle 3: BinaryRecordReader ✅ (완료, trait 구현 추가)
Cycle 4: AsciiRecordReader (우선순위 ↑)
Cycle 5: InputFormatDetector (우선순위 ↑)

=== Phase 2-3: Processing ===
Cycle 6: Sampler
Cycle 7: ExternalSorter
Cycle 8: Partitioner

=== Phase 4: Output ===
Cycle 9: BinaryRecordWriter
Cycle 10: AsciiRecordWriter
```

---

## 📚 완벽한 TDD 구현 가이드

### Step 1: RecordReader 추상화 (Cycle 2 - 즉시 필요)

#### 1.1 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/RecordReaderSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.{File, FileOutputStream}

class RecordReaderSpec extends AnyFlatSpec with Matchers {

  "DataFormat" should "have Binary and Ascii types" in {
    // Given/When
    val binary = DataFormat.Binary
    val ascii = DataFormat.Ascii

    // Then
    binary should not be ascii
    DataFormat.Binary shouldBe a[DataFormat]
    DataFormat.Ascii shouldBe a[DataFormat]
  }

  "RecordReader trait" should "be implemented by BinaryRecordReader" in {
    // Given
    val tempFile = Files.createTempFile("test", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](100)(1))
    fos.close()

    // When
    val reader: RecordReader = new BinaryRecordReader(tempFile.toFile)

    // Then
    reader shouldBe a[RecordReader]
    reader.getFile shouldBe tempFile.toFile

    reader.close()
    Files.delete(tempFile)
  }

  "RecordReader.create" should "create correct reader based on format" in {
    // Given
    val tempFile = Files.createTempFile("test", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](100)(1))
    fos.close()

    // When: Binary format
    val binaryReader = RecordReader.create(tempFile.toFile, DataFormat.Binary)

    // Then
    binaryReader shouldBe a[BinaryRecordReader]

    // When: ASCII format (will fail until AsciiRecordReader implemented)
    // val asciiReader = RecordReader.create(tempFile.toFile, DataFormat.Ascii)
    // asciiReader shouldBe a[AsciiRecordReader]

    binaryReader.close()
    Files.delete(tempFile)
  }
}
```

#### 1.2 구현 (GREEN)

```scala
// src/main/scala/distsort/core/RecordReader.scala
package distsort.core

import java.io.File

/**
 * Data format types (docs/0-implementation-decisions.md Section 6.1)
 *
 * PDF Requirement: "Should work on both ASCII and binary input
 *                   WITHOUT requiring an option"
 */
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

/**
 * Abstract interface for reading records from different formats
 *
 * plan_ver3.md Section 12.2: RecordReader abstraction
 * docs/0-implementation-decisions.md Section 6.1.3
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
 * Factory for creating format-specific readers
 */
object RecordReader {
  /**
   * Create a reader for the given file and format
   *
   * @param file Input file
   * @param format Data format (Binary or Ascii)
   * @return Appropriate RecordReader implementation
   */
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii =>
      // Placeholder until AsciiRecordReader is implemented
      throw new NotImplementedError("AsciiRecordReader not yet implemented")
  }

  /**
   * Create a reader with auto-detected format
   * Requires InputFormatDetector (Cycle 5)
   *
   * @param file Input file to auto-detect and read
   * @return RecordReader with appropriate format
   */
  def create(file: File): RecordReader = {
    // Placeholder until InputFormatDetector is implemented
    throw new NotImplementedError("InputFormatDetector not yet implemented")
  }
}
```

#### 1.3 BinaryRecordReader 리팩토링 (REFACTOR)

```scala
// src/main/scala/distsort/core/BinaryRecordReader.scala
// 기존 클래스에 extends RecordReader 추가

class BinaryRecordReader(file: File) extends RecordReader {
  // ... 기존 구현 ...

  // RecordReader trait 메서드 구현
  override def getFile: File = file
}
```

---

### Step 2: AsciiRecordReader (Cycle 4 - Phase 1-2 필수)

#### 2.1 ASCII 형식 명세 (Critical)

**plan_ver3.md Section 12.4 + gensort 표준**:
```
ASCII 레코드 (gensort -a 출력):
  총 길이: 201 characters + newline = 202 bytes
  구성:
    - Key: 20 hex characters (10 bytes 인코딩)
    - Space: 1 character
    - Value: 180 hex characters (90 bytes 인코딩)
    - Newline: \n

예시:
48656C6C6F576F726C64 576F726C6448656C6C6F...90 bytes hex...\n
└── 20 chars (key) ──┘ └────── 180 chars (value) ──────────┘
```

#### 2.2 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/AsciiRecordReaderSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class AsciiRecordReaderSpec extends AnyFlatSpec with Matchers {

  def toHex(bytes: Array[Byte]): String = {
    bytes.map("%02X".format(_)).mkString
  }

  def createAsciiFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-ascii", ".txt")
    val writer = new PrintWriter(tempFile.toFile, StandardCharsets.UTF_8)
    try {
      records.foreach { record =>
        val keyHex = toHex(record.key)
        val valueHex = toHex(record.value)
        writer.println(s"$keyHex $valueHex")
      }
    } finally {
      writer.close()
    }
    tempFile
  }

  "AsciiRecordReader" should "read gensort ASCII format correctly" in {
    // Given: gensort -a format file
    val records = Seq(
      Record(Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x57, 0x6F, 0x72, 0x6C, 0x64),
             Array.fill[Byte](90)(0x00)),
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2))
    )
    val inputFile = createAsciiFile(records)

    // When
    val reader = new AsciiRecordReader(inputFile.toFile)
    val read1 = reader.readRecord()
    val read2 = reader.readRecord()
    val read3 = reader.readRecord()

    // Then
    read1 shouldBe defined
    read1.get shouldBe records(0)
    read2 shouldBe defined
    read2.get shouldBe records(1)
    read3 shouldBe None // EOF

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle case-insensitive hex (0xAB vs 0xab)" in {
    // Given: Mixed case hex
    val tempFile = Files.createTempFile("test-case", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("48656c6c6f576f726c64 " + "00" * 90) // lowercase
    writer.close()

    // When
    val reader = new AsciiRecordReader(tempFile.toFile)
    val record = reader.readRecord()

    // Then
    record shouldBe defined
    record.get.key shouldBe Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F,
                                         0x57, 0x6F, 0x72, 0x6C, 0x64)

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject invalid line length (!= 201 chars)" in {
    // Given: Wrong length
    val tempFile = Files.createTempFile("test-invalid", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("48656C6C6F " + "00" * 80) // Too short
    writer.close()

    // When/Then
    val reader = new AsciiRecordReader(tempFile.toFile)
    intercept[IllegalArgumentException] {
      reader.readRecord()
    }

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject invalid hex characters" in {
    // Given: Invalid hex (G is not hex)
    val tempFile = Files.createTempFile("test-hex", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("48656C6C6G576F726C64 " + "00" * 90) // 'G' invalid
    writer.close()

    // When/Then
    val reader = new AsciiRecordReader(tempFile.toFile)
    intercept[IllegalArgumentException] {
      reader.readRecord()
    }

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle empty file" in {
    // Given
    val tempFile = Files.createTempFile("test-empty", ".txt")

    // When
    val reader = new AsciiRecordReader(tempFile.toFile)
    val result = reader.readRecord()

    // Then
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "implement RecordReader trait" in {
    // Given
    val tempFile = Files.createTempFile("test", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("00" * 10 + " " + "00" * 90)
    writer.close()

    // When
    val reader: RecordReader = new AsciiRecordReader(tempFile.toFile)

    // Then
    reader shouldBe a[RecordReader]
    reader.getFile shouldBe tempFile.toFile

    reader.close()
    Files.delete(tempFile)
  }
}
```

#### 2.3 구현 (GREEN)

```scala
// src/main/scala/distsort/core/AsciiRecordReader.scala
package distsort.core

import java.io.{BufferedReader, File, FileReader}
import scala.util.{Try, Success, Failure}

/**
 * ASCII format reader for gensort -a output
 *
 * Format (plan_ver3.md Section 12.4):
 *   - Key: 20 hex characters (10 bytes encoded)
 *   - Space: 1 character
 *   - Value: 180 hex characters (90 bytes encoded)
 *   - Newline: \n
 *   - Total: 201 characters per line (202 bytes with newline)
 *
 * Example:
 *   48656C6C6F576F726C64 576F726C6448656C6C6F...
 *   └─ 20 chars (key) ─┘ └─ 180 chars (value) ─┘
 */
class AsciiRecordReader(file: File) extends RecordReader {
  require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
  require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

  private val reader = new BufferedReader(
    new FileReader(file),
    1024 * 1024  // 1MB buffer (same as BinaryRecordReader)
  )

  override def readRecord(): Option[Record] = {
    val line = reader.readLine()

    if (line == null) {
      return None // EOF
    }

    // Validate line length (20 + 1 + 180 = 201)
    if (line.length != 201) {
      throw new IllegalArgumentException(
        s"Invalid ASCII record length: expected 201, got ${line.length}"
      )
    }

    // Validate space at position 20
    if (line.charAt(20) != ' ') {
      throw new IllegalArgumentException(
        s"Expected space at position 20, found '${line.charAt(20)}'"
      )
    }

    // Parse key (20 hex chars → 10 bytes)
    val keyHex = line.substring(0, 20)
    val key = hexToBytes(keyHex)

    if (key.length != 10) {
      throw new IllegalArgumentException(
        s"Key decoded to ${key.length} bytes, expected 10"
      )
    }

    // Parse value (180 hex chars → 90 bytes)
    val valueHex = line.substring(21, 201)
    val value = hexToBytes(valueHex)

    if (value.length != 90) {
      throw new IllegalArgumentException(
        s"Value decoded to ${value.length} bytes, expected 90"
      )
    }

    Some(Record(key, value))
  }

  private def hexToBytes(hex: String): Array[Byte] = {
    require(hex.length % 2 == 0, s"Hex string must have even length: ${hex.length}")

    Try {
      hex.grouped(2).map { pair =>
        Integer.parseInt(pair, 16).toByte
      }.toArray
    } match {
      case Success(bytes) => bytes
      case Failure(e: NumberFormatException) =>
        throw new IllegalArgumentException(
          s"Invalid hex string: contains non-hex characters", e
        )
      case Failure(e) =>
        throw new IllegalArgumentException(s"Failed to decode hex", e)
    }
  }

  override def close(): Unit = {
    reader.close()
  }

  override def getFile: File = file
}
```

---

### Step 3: InputFormatDetector (Cycle 5 - Phase 1-2 필수)

#### 3.1 자동 감지 알고리즘

**docs/0-implementation-decisions.md Section 6.1.2**:
```
알고리즘:
1. 파일의 첫 1000 바이트 읽기
2. ASCII printable 문자 비율 계산
   - ASCII printable: 0x20-0x7E (space ~ ~)
   - Plus: \n (0x0A), \r (0x0D)
3. 비율 > 0.9 → ASCII, 그 외 → Binary
```

#### 3.2 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/InputFormatDetectorSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.{FileOutputStream, PrintWriter}

class InputFormatDetectorSpec extends AnyFlatSpec with Matchers {

  "InputFormatDetector" should "detect binary format" in {
    // Given: Binary file with non-printable bytes
    val tempFile = Files.createTempFile("test-binary", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](1000)(0xFF.toByte)) // Non-printable
    fos.close()

    // When
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }

  it should "detect ASCII format (gensort -a)" in {
    // Given: ASCII file in gensort -a format
    val tempFile = Files.createTempFile("test-ascii", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    (1 to 10).foreach { _ =>
      writer.println("48656C6C6F576F726C64 " + "00" * 90)
    }
    writer.close()

    // When
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "handle empty file (default to Binary)" in {
    // Given: Empty file
    val tempFile = Files.createTempFile("test-empty", ".dat")

    // When
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Default to Binary per spec
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }

  it should "handle mixed content (majority rule)" in {
    // Given: 90% ASCII + 10% binary
    val tempFile = Files.createTempFile("test-mixed", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](900)('A'.toByte))  // 90% ASCII
    fos.write(Array.fill[Byte](100)(0xFF.toByte))  // 10% binary
    fos.close()

    // When
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should be ASCII (90% > threshold)
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "handle small files correctly" in {
    // Given: Small binary file (< 1000 bytes)
    val tempFile = Files.createTempFile("test-small", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](100)(0x00))  // 100 null bytes
    fos.close()

    // When
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }
}
```

#### 3.3 구현 (GREEN)

```scala
// src/main/scala/distsort/core/InputFormatDetector.scala
package distsort.core

import java.io.{File, FileInputStream}
import com.typesafe.scalalogging.LazyLogging

/**
 * Auto-detect input file format (ASCII vs Binary)
 *
 * Algorithm (docs/0-implementation-decisions.md Section 6.1.2):
 *   1. Read first 1000 bytes
 *   2. Calculate ASCII printable ratio
 *   3. Ratio > 0.9 → ASCII, else → Binary
 *
 * PDF Requirement:
 *   "Should work on both ASCII and binary input WITHOUT requiring an option"
 */
object InputFormatDetector extends LazyLogging {

  private val SAMPLE_SIZE = 1000
  private val ASCII_THRESHOLD = 0.9

  /**
   * Detect file format by analyzing first 1000 bytes
   *
   * @param file Input file to analyze
   * @return DataFormat.Ascii or DataFormat.Binary
   */
  def detectFormat(file: File): DataFormat = {
    require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
    require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

    val buffer = new Array[Byte](SAMPLE_SIZE)
    val inputStream = new FileInputStream(file)

    try {
      val bytesRead = inputStream.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}, defaulting to Binary")
        return DataFormat.Binary
      }

      // Count ASCII printable characters
      // ASCII printable: 0x20-0x7E (space ~ ~), plus \n (0x0A), \r (0x0D)
      val asciiLikeCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiLikeCount.toDouble / bytesRead

      logger.debug(s"File: ${file.getName}, ASCII ratio: $asciiRatio")

      if (asciiRatio > ASCII_THRESHOLD) {
        logger.info(s"Detected ASCII format for file: ${file.getName}")
        DataFormat.Ascii
      } else {
        logger.info(s"Detected Binary format for file: ${file.getName}")
        DataFormat.Binary
      }
    } finally {
      inputStream.close()
    }
  }
}
```

#### 3.4 RecordReader Factory 업데이트

```scala
// src/main/scala/distsort/core/RecordReader.scala
// Update the companion object

object RecordReader {
  def create(file: File, format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader(file)
    case DataFormat.Ascii => new AsciiRecordReader(file)  // Now implemented!
  }

  /**
   * Create a reader with auto-detected format
   *
   * plan_ver3.md Line 536-539: Phase 1 Sampling requires this
   */
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    logger.info(s"Auto-detected format ${format} for file: ${file.getName}")
    create(file, format)
  }
}
```

---

### Step 4: Integration Test (Phase 1-2 검증)

```scala
// src/test/scala/distsort/integration/RecordReaderIntegrationSpec.scala
package distsort.integration

import distsort.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.{FileOutputStream, PrintWriter}

class RecordReaderIntegrationSpec extends AnyFlatSpec with Matchers {

  "RecordReader with auto-detection" should "handle mixed input directory" in {
    // Given: Directory with both Binary and ASCII files
    val tempDir = Files.createTempDirectory("integration-mixed")

    // Binary file
    val binaryFile = tempDir.resolve("binary.dat").toFile
    val fos = new FileOutputStream(binaryFile)
    val binaryRecords = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2)),
      Record(Array.fill[Byte](10)(3), Array.fill[Byte](90)(4))
    )
    binaryRecords.foreach(r => fos.write(r.toBytes))
    fos.close()

    // ASCII file
    val asciiFile = tempDir.resolve("ascii.txt").toFile
    val writer = new PrintWriter(asciiFile)
    val asciiRecords = Seq(
      Record(Array.fill[Byte](10)(5), Array.fill[Byte](90)(6)),
      Record(Array.fill[Byte](10)(7), Array.fill[Byte](90)(8))
    )
    asciiRecords.foreach { r =>
      val keyHex = r.key.map("%02X".format(_)).mkString
      val valueHex = r.value.map("%02X".format(_)).mkString
      writer.println(s"$keyHex $valueHex")
    }
    writer.close()

    // When: Read both files with auto-detection
    val allRecords = Seq(binaryFile, asciiFile).flatMap { file =>
      val reader = RecordReader.create(file)  // Auto-detection!
      val records = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
      reader.close()
      records
    }

    // Then: All records read correctly
    allRecords should have length 4
    allRecords should contain allElementsOf (binaryRecords ++ asciiRecords)

    // Cleanup
    binaryFile.delete()
    asciiFile.delete()
    Files.delete(tempDir)
  }

  "Phase 1 Sampling" should "work with auto-detection" in {
    // Given: Mixed input files
    val tempDir = Files.createTempDirectory("sampling-test")
    val files = createMixedInputFiles(tempDir)

    // When: Sampling with auto-detection (Phase 1 simulation)
    val samples = files.flatMap { file =>
      val format = InputFormatDetector.detectFormat(file)
      val reader = RecordReader.create(file, format)

      // Take 10% sample
      var count = 0
      val samples = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .filter { _ =>
          count += 1
          count % 10 == 0  // Sample every 10th record
        }
        .toSeq

      reader.close()
      samples
    }

    // Then: Samples collected from both formats
    samples should not be empty

    // Cleanup
    cleanupDirectory(tempDir)
  }
}
```

---

## 📊 최종 TDD Cycle 순서 (Complete)

### Phase 0: Foundation (Week 3 Day 1)
- [x] **Cycle 1**: Record (완료)
- [ ] **Cycle 2**: RecordReader trait + DataFormat

### Phase 1-2: Input Processing (Week 3 Day 2)
- [x] **Cycle 3**: BinaryRecordReader (완료, trait 추가 필요)
- [ ] **Cycle 4**: AsciiRecordReader
- [ ] **Cycle 5**: InputFormatDetector

### Phase 2-3: Core Algorithms (Week 3 Day 3-4)
- [ ] **Cycle 6**: Sampler
- [ ] **Cycle 7**: ExternalSorter
- [ ] **Cycle 8**: Partitioner
- [ ] **Cycle 9**: ShuffleMap

### Phase 4: Output (Week 3 Day 5)
- [ ] **Cycle 10**: BinaryRecordWriter
- [ ] **Cycle 11**: AsciiRecordWriter

### Phase 5: Integration (Week 4)
- [ ] **Cycle 12**: FileLayout
- [ ] **Cycle 13**: Master-Worker Communication
- [ ] **Cycle 14**: End-to-End Test

---

## ✅ 체크리스트

### 즉시 작업 (Next Session)
- [ ] RecordReader.scala 생성 (trait + companion object)
- [ ] DataFormat sealed trait 정의
- [ ] BinaryRecordReader가 trait 구현하도록 리팩토링
- [ ] RecordReaderSpec 테스트 실행

### AsciiRecordReader 구현
- [ ] AsciiRecordReaderSpec 작성 (6 test cases)
- [ ] AsciiRecordReader.scala 구현
- [ ] Hex decoding 검증
- [ ] gensort -a 형식 호환성 테스트

### InputFormatDetector 구현
- [ ] InputFormatDetectorSpec 작성 (5 test cases)
- [ ] InputFormatDetector.scala 구현
- [ ] RecordReader.create(file) 메서드 완성
- [ ] Auto-detection integration test

### Integration 검증
- [ ] Mixed input directory test
- [ ] Phase 1 Sampling simulation
- [ ] Phase 2 Sort with auto-detection
- [ ] gensort/valsort 검증

---

## 🎯 성공 기준

### 기술적 요구사항 충족
- [x] Record 클래스: 10-byte key + 90-byte value
- [x] BinaryRecordReader: 100-byte records
- [ ] AsciiRecordReader: gensort -a format (201 chars)
- [ ] InputFormatDetector: 자동 감지 (옵션 없이)
- [ ] Phase 1-2 통합 테스트 통과

### 성능 목표
- [x] BinaryRecordReader: < 1000ms for 1000 records (✅ 58ms)
- [ ] AsciiRecordReader: < 2000ms for 1000 records
- [ ] InputFormatDetector: < 10ms per file
- [ ] Mixed input: < 3000ms for 2000 records (1000 each)

### 품질 지표
- [ ] 테스트 커버리지 > 90%
- [ ] 모든 edge case 처리
- [ ] Scaladoc 완비
- [ ] TDD 사이클 엄격히 준수

---

## 📚 참조 문서

### 필수 참조
1. **plan_ver3.md**
   - Section 12: 입력 형식 처리
   - Section 4.1.2: Phase 1 Sampling
   - Section 4.2.1: Phase 2 Sort

2. **docs/0-implementation-decisions.md**
   - Section 6.1: ASCII vs Binary 형식
   - Section 8: Additional Requirements

3. **docs/7-testing-strategy.md**
   - TDD 개발 철학
   - Phase별 테스트 전략

---

## 🔥 핵심 수정 포인트 정리

1. **InputFormatDetector 우선순위 상향 (Cycle 6 → Cycle 5)**
   - Phase 1-2에서 필수
   - 모든 파일 읽기에 사용

2. **RecordReader 추상화 추가 (Cycle 2 NEW)**
   - 공통 인터페이스 제공
   - Factory pattern 구현

3. **AsciiRecordReader 우선순위 상향 (Cycle 4)**
   - gensort -a 형식 지원
   - 201 characters per line

4. **Writer는 나중에 (Cycle 10-11)**
   - Phase 4에서만 필요
   - Input processing이 우선

---

**문서 버전**: 3.0 (Final)
**작성일**: 2025-11-02
**검증 완료**: plan_ver3.md, docs/*, testing-strategy.md와 완벽 일치
**Status**: ✅ **READY FOR IMPLEMENTATION**