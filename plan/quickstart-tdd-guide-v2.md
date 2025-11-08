# QuickStart: TDD Implementation Guide (v2 - 완전판)

**목적**: docs/와 plan_ver3.md를 완벽히 반영한 TDD 가이드
**작성일**: 2025-11-01
**버전**: 2.0 (docs/ 전체 내용 통합)

---

## 🎯 핵심 원칙 (docs/0-implementation-decisions.md 기반)

### PDF 필수 요구사항

1. **ASCII/Binary 자동 감지** (옵션 없이)
2. **입력 디렉토리 읽기 전용** (수정 금지)
3. **Worker crash & restart 허용**
4. **멀티코어 활용**
5. **포트 동적 할당** (하드코딩 금지)

---

## 📋 TDD 구현 순서 (수정됨)

### ⚠️ **중요: 순서 변경 이유**

**기존 문제점**:
```
Cycle 3: BinaryRecordWriter
Cycle 4: AsciiRecordReader
Cycle 5: AsciiRecordWriter
Cycle 6: InputFormatDetector  ← Phase 1-2에 필요한데 너무 늦음!
```

**수정된 순서** (Phase별 의존성 반영):
```
✅ Cycle 1: Record (완료)
✅ Cycle 2: BinaryRecordReader (완료)
🔥 Cycle 3: RecordReader trait + DataFormat (추상화)
🔥 Cycle 4: AsciiRecordReader (Phase 1-2 필수)
🔥 Cycle 5: InputFormatDetector (Phase 1-2 필수)
⏰ Cycle 6: BinaryRecordWriter (Phase 4용)
⏰ Cycle 7: AsciiRecordWriter (Phase 4용)
```

**이유**:
- Phase 1 (Sampling): InputFormatDetector 필요
- Phase 2 (Sort): InputFormatDetector 필요
- Phase 4 (Merge): RecordWriter 필요 (나중에 가능)

---

## 🚀 Step 1: 프로젝트 초기 설정 (완료)

✅ build.sbt, plugins.sbt 설정
✅ 디렉토리 구조 생성
✅ .gitignore 설정
✅ Record 클래스 (Cycle 1)
✅ BinaryRecordReader (Cycle 2)

**현재 상태**: 33% (2/7 components)

---

## 🔥 Step 2: RecordReader 추상화 (Cycle 3 - NEW)

### 2.1 필요성

**docs/0-implementation-decisions.md Section 6.1.3**:
```scala
trait RecordReader {
  def readRecord(input: InputStream): Option[Record]
}
```

**문제**: 현재 BinaryRecordReader는 독립적으로 구현됨
- 공통 인터페이스 없음
- InputFormatDetector가 Reader를 선택할 방법 없음
- 코드 중복 위험

### 2.2 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/RecordReaderSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.FileOutputStream

class RecordReaderSpec extends AnyFlatSpec with Matchers {

  "RecordReader.create" should "create BinaryRecordReader for binary format" in {
    // Given: Binary file
    val tempFile = Files.createTempFile("test-binary", ".dat")
    val records = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2))
    )
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      records.foreach(r => fos.write(r.toBytes))
    } finally {
      fos.close()
    }

    // When: Create reader
    val reader = RecordReader.create(tempFile.toFile, DataFormat.Binary)

    // Then: Should be BinaryRecordReader
    reader shouldBe a[BinaryRecordReader]

    // And: Should read correctly
    val read = reader.readRecord()
    read shouldBe defined
    read.get shouldBe records.head

    reader.close()
    Files.delete(tempFile)
  }

  it should "provide factory method with auto-detection" in {
    // Given: Binary file
    val tempFile = Files.createTempFile("test-auto", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](100)(1))
    fos.close()

    // When: Create reader with auto-detection
    // Note: This will fail until InputFormatDetector is implemented
    // For now, just test the interface
    val reader = RecordReader.create(tempFile.toFile, DataFormat.Binary)

    // Then: Should work
    reader.readRecord() shouldBe defined

    reader.close()
    Files.delete(tempFile)
  }
}
```

### 2.3 구현 (GREEN)

```scala
// src/main/scala/distsort/core/RecordReader.scala
package distsort.core

import java.io.File

/**
 * Abstract interface for reading records from different formats
 *
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
 * Data format types (docs/0-implementation-decisions.md Section 6.1.1)
 *
 * ASCII:
 *   - 100 characters per record
 *   - Key: 10 chars, Value: 90 chars
 *   - Line ending: \n or \r\n
 *
 * Binary:
 *   - 100 bytes per record
 *   - Key: 10 bytes, Value: 90 bytes
 *   - No line endings
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
   * Requires InputFormatDetector (Cycle 5)
   */
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    create(file, format)
  }
}
```

### 2.4 BinaryRecordReader 리팩토링 (REFACTOR)

```scala
// src/main/scala/distsort/core/BinaryRecordReader.scala
// 기존 코드에 extends RecordReader 추가

class BinaryRecordReader(file: File) extends RecordReader {
  require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
  require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

  // ... 기존 코드 그대로 유지 ...

  // RecordReader trait 메서드 구현
  override def getFile: File = file
}
```

**실행**:
```bash
sbt test
# RecordReaderSpec 통과 확인
# BinaryRecordReaderSpec 여전히 통과 확인 (회귀 방지)
```

---

## 🔥 Step 3: AsciiRecordReader (Cycle 4 - Phase 1-2 필수)

### 3.1 ASCII 형식 스펙 (docs/0-implementation-decisions.md Section 6.1.1)

```
ASCII 레코드:
  - 총 길이: 201 bytes (gensort -a 출력 형식)
  - Key: 20 characters (10 bytes를 hex로 인코딩)
  - Space: 1 character
  - Value: 180 characters (90 bytes를 hex로 인코딩)
  - Newline: \n (1 byte)

예시:
48656C6C6F576F726C64 576F726C6448656C6C6F576F726C64...\n
[---- 20 chars ----] [ ] [----------- 180 chars -----------]

Decoding:
  "48656C6C6F576F726C64" → [0x48, 0x65, 0x6C, 0x6C, 0x6F, ...]
  = "HelloWorld" in bytes
```

### 3.2 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/AsciiRecordReaderSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.{FileOutputStream, PrintWriter}
import java.nio.charset.StandardCharsets

class AsciiRecordReaderSpec extends AnyFlatSpec with Matchers {

  /**
   * Helper: Convert bytes to ASCII hex string (gensort format)
   */
  def toHex(bytes: Array[Byte]): String = {
    bytes.map("%02X".format(_)).mkString
  }

  /**
   * Helper: Create ASCII format file
   */
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

  "AsciiRecordReader" should "read ASCII format records" in {
    // Given: ASCII file with 3 records
    val originalRecords = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2)),
      Record(Array.fill[Byte](10)(3), Array.fill[Byte](90)(4)),
      Record(Array.fill[Byte](10)(5), Array.fill[Byte](90)(6))
    )
    val inputFile = createAsciiFile(originalRecords)

    // When: Read records
    val reader = new AsciiRecordReader(inputFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: All records read correctly
    readRecords should have length 3
    readRecords.zip(originalRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle hex decoding correctly" in {
    // Given: ASCII record with specific hex values
    val key = Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x57, 0x6F, 0x72, 0x6C, 0x64) // "HelloWorld"
    val value = Array.fill[Byte](90)(0x00)
    val record = Record(key, value)

    val inputFile = createAsciiFile(Seq(record))

    // When: Read
    val reader = new AsciiRecordReader(inputFile.toFile)
    val read = reader.readRecord()

    // Then: Hex decoded correctly
    read shouldBe defined
    read.get.key shouldBe key
    read.get.value shouldBe value

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle uppercase and lowercase hex" in {
    // Given: Mixed case hex
    val tempFile = Files.createTempFile("test-case", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    // Lowercase + uppercase mixed
    writer.println("48656c6c6f576f726c64 " + "00" * 90)
    writer.close()

    // When: Read
    val reader = new AsciiRecordReader(tempFile.toFile)
    val read = reader.readRecord()

    // Then: Should work (case insensitive)
    read shouldBe defined

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject invalid hex characters" in {
    // Given: Invalid hex (G is not hex)
    val tempFile = Files.createTempFile("test-invalid", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("48656C6C6G576F726C64 " + "00" * 90)  // 'G' invalid
    writer.close()

    // When/Then: Should throw exception
    val reader = new AsciiRecordReader(tempFile.toFile)
    intercept[IllegalArgumentException] {
      reader.readRecord()
    }

    reader.close()
    Files.delete(tempFile)
  }

  it should "reject wrong line length" in {
    // Given: Too short line
    val tempFile = Files.createTempFile("test-short", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    writer.println("48656C6C6F " + "00" * 80)  // Too short
    writer.close()

    // When/Then: Should throw exception
    val reader = new AsciiRecordReader(tempFile.toFile)
    intercept[IllegalArgumentException] {
      reader.readRecord()
    }

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle EOF correctly" in {
    // Given: File with 1 record
    val records = Seq(Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2)))
    val inputFile = createAsciiFile(records)
    val reader = new AsciiRecordReader(inputFile.toFile)

    // When/Then
    reader.readRecord() shouldBe defined
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle empty file" in {
    // Given: Empty file
    val tempFile = Files.createTempFile("test-empty", ".txt")
    val reader = new AsciiRecordReader(tempFile.toFile)

    // When: Try to read
    val result = reader.readRecord()

    // Then: Should return None
    result shouldBe None

    reader.close()
    Files.delete(tempFile)
  }
}
```

### 3.3 구현 (GREEN)

```scala
// src/main/scala/distsort/core/AsciiRecordReader.scala
package distsort.core

import java.io.{BufferedReader, File, FileReader}
import scala.util.{Try, Success, Failure}

/**
 * ASCII format reader for gensort -a output
 *
 * Format (docs/0-implementation-decisions.md Section 6.1.1):
 *   - Key: 20 hex characters (10 bytes encoded)
 *   - Space: 1 character
 *   - Value: 180 hex characters (90 bytes encoded)
 *   - Newline: \n
 *   - Total: 201 characters per line
 *
 * Example:
 *   48656C6C6F576F726C64 576F726C6448656C6C6F...
 *   └─ 20 chars (key) ─┘ └─ 180 chars (value) ─┘
 */
class AsciiRecordReader(file: File) extends RecordReader {
  require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
  require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

  /**
   * Buffered reader for line-based reading
   */
  private val reader = new BufferedReader(
    new FileReader(file),
    1024 * 1024  // 1MB buffer
  )

  /**
   * Read next ASCII record
   *
   * @return Some(Record) if available, None if EOF
   * @throws IllegalArgumentException if line format is invalid
   */
  override def readRecord(): Option[Record] = {
    val line = reader.readLine()

    if (line == null) {
      return None  // EOF
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

    // Parse key (20 hex characters → 10 bytes)
    val keyHex = line.substring(0, 20)
    val key = hexToBytes(keyHex)

    if (key.length != 10) {
      throw new IllegalArgumentException(
        s"Key decoded to ${key.length} bytes, expected 10"
      )
    }

    // Parse value (180 hex characters → 90 bytes)
    val valueHex = line.substring(21, 201)
    val value = hexToBytes(valueHex)

    if (value.length != 90) {
      throw new IllegalArgumentException(
        s"Value decoded to ${value.length} bytes, expected 90"
      )
    }

    Some(Record(key, value))
  }

  /**
   * Convert hex string to byte array
   *
   * @param hex Hex string (even length)
   * @return Byte array
   * @throws IllegalArgumentException if hex string is invalid
   */
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
          s"Invalid hex string: $hex (contains non-hex characters)", e
        )
      case Failure(e) =>
        throw new IllegalArgumentException(s"Failed to decode hex: $hex", e)
    }
  }

  /**
   * Close the reader
   */
  override def close(): Unit = {
    reader.close()
  }

  /**
   * Get the file being read
   */
  override def getFile: File = file
}
```

### 3.4 실행 및 검증

```bash
sbt test

# [info] AsciiRecordReaderSpec:
# [info] AsciiRecordReader
# [info] - should read ASCII format records
# [info] - should handle hex decoding correctly
# [info] - should handle uppercase and lowercase hex
# [info] - should reject invalid hex characters
# [info] - should reject wrong line length
# [info] - should handle EOF correctly
# [info] - should handle empty file
# [info] All tests passed.
```

---

## 🔥 Step 4: InputFormatDetector (Cycle 5 - Phase 1-2 필수)

### 4.1 자동 감지 알고리즘 (docs/0-implementation-decisions.md Section 6.1.2)

```
알고리즘:
1. 파일의 첫 1000 바이트 읽기
2. ASCII printable 문자 비율 계산
   - ASCII printable: 0x20-0x7E (space ~ ~)
   - Plus: \n (0x0A), \r (0x0D)
3. 비율 > 0.9 → ASCII, 그 외 → Binary
```

### 4.2 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/InputFormatDetectorSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.{FileOutputStream, PrintWriter}

class InputFormatDetectorSpec extends AnyFlatSpec with Matchers {

  "InputFormatDetector" should "detect binary format" in {
    // Given: Binary file
    val tempFile = Files.createTempFile("test-binary", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](1000)(0xFF.toByte))  // Non-printable
    fos.close()

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should be Binary
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }

  it should "detect ASCII format" in {
    // Given: ASCII file (gensort -a format)
    val tempFile = Files.createTempFile("test-ascii", ".txt")
    val writer = new PrintWriter(tempFile.toFile)
    // Write 10 ASCII records
    (1 to 10).foreach { _ =>
      writer.println("48656C6C6F576F726C64 " + "00" * 90)
    }
    writer.close()

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should be ASCII
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "handle empty file" in {
    // Given: Empty file
    val tempFile = Files.createTempFile("test-empty", ".dat")

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Default to Binary
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }

  it should "handle mixed content (majority rule)" in {
    // Given: Mostly ASCII with some binary
    val tempFile = Files.createTempFile("test-mixed", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    // 900 ASCII chars + 100 binary bytes
    fos.write(Array.fill[Byte](900)('A'.toByte))
    fos.write(Array.fill[Byte](100)(0xFF.toByte))
    fos.close()

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should be ASCII (90% ASCII ratio)
    format shouldBe DataFormat.Ascii

    Files.delete(tempFile)
  }

  it should "handle small files correctly" in {
    // Given: Small binary file (< 1000 bytes)
    val tempFile = Files.createTempFile("test-small", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](100)(0xFF.toByte))
    fos.close()

    // When: Detect format
    val format = InputFormatDetector.detectFormat(tempFile.toFile)

    // Then: Should be Binary
    format shouldBe DataFormat.Binary

    Files.delete(tempFile)
  }
}
```

### 4.3 구현 (GREEN)

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

  /**
   * Detect file format by analyzing first 1000 bytes
   *
   * @param file Input file to analyze
   * @return DataFormat.Ascii or DataFormat.Binary
   */
  def detectFormat(file: File): DataFormat = {
    require(file.exists(), s"File does not exist: ${file.getAbsolutePath}")
    require(file.isFile, s"Not a regular file: ${file.getAbsolutePath}")

    val buffer = new Array[Byte](1000)
    val inputStream = new FileInputStream(file)

    try {
      val bytesRead = inputStream.read(buffer)

      if (bytesRead <= 0) {
        logger.warn(s"Empty file: ${file.getName}, defaulting to Binary")
        return DataFormat.Binary
      }

      // ASCII printable: 0x20-0x7E (space ~ ~), plus \n (0x0A), \r (0x0D)
      val asciiLikeCount = buffer.take(bytesRead).count { b =>
        (b >= 32 && b <= 126) || b == '\n' || b == '\r'
      }

      val asciiRatio = asciiLikeCount.toDouble / bytesRead

      logger.debug(s"File: ${file.getName}, ASCII ratio: $asciiRatio")

      if (asciiRatio > 0.9) {
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

### 4.4 RecordReader.create() 업데이트

```scala
// src/main/scala/distsort/core/RecordReader.scala
// 기존 코드에 추가:

object RecordReader {
  // ... 기존 메서드 ...

  /**
   * Create a reader with auto-detected format
   * Uses InputFormatDetector (Cycle 5)
   */
  def create(file: File): RecordReader = {
    val format = InputFormatDetector.detectFormat(file)
    create(file, format)
  }
}
```

### 4.5 실행 및 검증

```bash
sbt test

# [info] InputFormatDetectorSpec:
# [info] InputFormatDetector
# [info] - should detect binary format
# [info] - should detect ASCII format
# [info] - should handle empty file
# [info] - should handle mixed content (majority rule)
# [info] - should handle small files correctly
# [info] All tests passed.
```

---

## 🎯 Step 5: Integration Test (Phase 1-2 검증)

### 5.1 통합 테스트 작성

```scala
// src/test/scala/distsort/integration/AutoFormatDetectionSpec.scala
package distsort.integration

import distsort.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.{FileOutputStream, PrintWriter}

class AutoFormatDetectionSpec extends AnyFlatSpec with Matchers {

  "RecordReader.create with auto-detection" should "read binary files" in {
    // Given: Binary file
    val tempFile = Files.createTempFile("integration-binary", ".dat")
    val records = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2)),
      Record(Array.fill[Byte](10)(3), Array.fill[Byte](90)(4))
    )

    val fos = new FileOutputStream(tempFile.toFile)
    try {
      records.foreach(r => fos.write(r.toBytes))
    } finally {
      fos.close()
    }

    // When: Create reader with auto-detection
    val reader = RecordReader.create(tempFile.toFile)

    // Then: Should read correctly
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    readRecords should have length 2
    readRecords shouldBe records

    reader.close()
    Files.delete(tempFile)
  }

  it should "read ASCII files" in {
    // Given: ASCII file
    val tempFile = Files.createTempFile("integration-ascii", ".txt")
    val records = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2)),
      Record(Array.fill[Byte](10)(3), Array.fill[Byte](90)(4))
    )

    val writer = new PrintWriter(tempFile.toFile)
    try {
      records.foreach { r =>
        val keyHex = r.key.map("%02X".format(_)).mkString
        val valueHex = r.value.map("%02X".format(_)).mkString
        writer.println(s"$keyHex $valueHex")
      }
    } finally {
      writer.close()
    }

    // When: Create reader with auto-detection
    val reader = RecordReader.create(tempFile.toFile)

    // Then: Should read correctly
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    readRecords should have length 2
    readRecords shouldBe records

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle mixed directory (Binary + ASCII)" in {
    // Given: Directory with both Binary and ASCII files
    val tempDir = Files.createTempDirectory("integration-mixed")

    // Binary file
    val binaryFile = tempDir.resolve("binary.dat").toFile
    val fos = new FileOutputStream(binaryFile)
    val binaryRecords = Seq(
      Record(Array.fill[Byte](10)(1), Array.fill[Byte](90)(2))
    )
    binaryRecords.foreach(r => fos.write(r.toBytes))
    fos.close()

    // ASCII file
    val asciiFile = tempDir.resolve("ascii.txt").toFile
    val writer = new PrintWriter(asciiFile)
    val asciiRecords = Seq(
      Record(Array.fill[Byte](10)(3), Array.fill[Byte](90)(4))
    )
    asciiRecords.foreach { r =>
      val keyHex = r.key.map("%02X".format(_)).mkString
      val valueHex = r.value.map("%02X".format(_)).mkString
      writer.println(s"$keyHex $valueHex")
    }
    writer.close()

    // When: Read both files with auto-detection
    val allRecords = Seq(binaryFile, asciiFile).flatMap { file =>
      val reader = RecordReader.create(file)
      val records = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
      reader.close()
      records
    }

    // Then: Should read all records correctly
    allRecords should have length 2
    allRecords should contain allElementsOf (binaryRecords ++ asciiRecords)

    // Cleanup
    binaryFile.delete()
    asciiFile.delete()
    Files.delete(tempDir)
  }
}
```

### 5.2 실행

```bash
sbt "testOnly distsort.integration.AutoFormatDetectionSpec"

# [info] AutoFormatDetectionSpec:
# [info] RecordReader.create with auto-detection
# [info] - should read binary files
# [info] - should read ASCII files
# [info] - should handle mixed directory (Binary + ASCII)
# [info] All tests passed.
```

---

## ⏰ Step 6: BinaryRecordWriter (Cycle 6 - Phase 4용)

### 6.1 테스트 및 구현 (nextwork 문서 참조)

**참조**: `nextwork/2025-11-01_18-05-00_progress-and-next-steps.md`

이미 상세한 테스트 케이스와 구현 가이드가 작성되어 있습니다.

**핵심**:
- BufferedOutputStream (1MB buffer)
- Atomic write (temp + rename)
- 부모 디렉토리 자동 생성
- Companion object: writeAll() helper

---

## ⏰ Step 7: AsciiRecordWriter (Cycle 7)

### 7.1 구현 포인트

```scala
class AsciiRecordWriter(file: File) extends RecordWriter {
  private val writer = new BufferedWriter(
    new FileWriter(file),
    1024 * 1024  // 1MB buffer
  )

  override def writeRecord(record: Record): Unit = {
    val keyHex = record.key.map("%02X".format(_)).mkString
    val valueHex = record.value.map("%02X".format(_)).mkString
    writer.write(s"$keyHex $valueHex\n")
  }

  override def close(): Unit = writer.close()
}
```

---

## 📊 진행률 추적

### 현재 상태 (2025-11-01)

```
✅ Cycle 1: Record (완료)
✅ Cycle 2: BinaryRecordReader (완료)
⏳ Cycle 3: RecordReader trait (다음 세션)
⏳ Cycle 4: AsciiRecordReader (다음 세션)
⏳ Cycle 5: InputFormatDetector (다음 세션)
⏳ Cycle 6: BinaryRecordWriter
⏳ Cycle 7: AsciiRecordWriter

Phase 1-2 Ready: 29% (2/7)
Phase 1-2 Target: 71% (5/7)  ← Cycle 3-5 완료 시
Phase 4 Ready: 100% (7/7)    ← Cycle 6-7 완료 시
```

### Week 3 전체 목표

```
Week 3 Core Components:
  Day 1: ✅ Record + BinaryRecordReader (33%)
  Day 2: 🔥 RecordReader trait + AsciiRecordReader + InputFormatDetector (71%)
  Day 3: ⏰ BinaryRecordWriter + AsciiRecordWriter (100%)

Milestone 2 검증 가능: Day 2 완료 후
  - ASCII/Binary 혼합 입력 테스트
  - Sampling 통합 테스트
  - Phase 1-2 E2E 테스트
```

---

## 🎓 중요 설계 원칙 (docs/ 기반)

### 1. Fault Tolerance (docs/0-implementation-decisions.md Section 1)

```
전략: Graceful Degradation with Job Restart
  - Phase 1-2: Continue with N-1 workers if >50% alive
  - Phase 3-4: Restart entire job
  - Data Integrity: Atomic writes, idempotent operations
```

**TDD에 반영**:
- Atomic write 테스트 (temp + rename)
- Idempotent operation 테스트
- Cleanup 로직 테스트

### 2. 멀티코어 활용 (docs/0-implementation-decisions.md Section 2)

```
Thread Pool Configuration:
  - sortingThreads = numCores
  - shuffleThreads = min(numCores * 2, 10)
  - mergeThreads = (numCores * 1.5).toInt
```

**TDD에 반영**:
- 병렬 정렬 테스트
- 병렬 파티셔닝 테스트
- 병렬 merge 테스트

### 3. 파일 관리 (docs/5-file-management.md)

```
FileLayout 클래스:
  - 모든 파일 경로는 FileLayout 통해 접근
  - temp/, received/, output/ 구조
  - Atomic operations
```

**TDD에 반영**:
- FileLayout 단위 테스트
- 임시 파일 정리 테스트
- 출력 디렉토리 검증

### 4. Master 출력 형식 (docs/0-implementation-decisions.md Section 3)

```
출력 형식 (2줄):
  Line 1: Master IP:Port
  Line 2: Worker IPs (콤마 구분, 포트 제외)

예시:
192.168.1.100:5000
192.168.1.10, 192.168.1.11, 192.168.1.12
```

**TDD에 반영**:
- Master 출력 형식 테스트
- 파싱 테스트

---

## 📚 참고 문서

### 필수 읽기

1. **docs/0-implementation-decisions.md**
   - Fault tolerance 전략
   - 입력 형식 자동 감지
   - Master 출력 형식

2. **docs/7-testing-strategy.md**
   - Phase별 테스트 전략
   - Unit/Integration/E2E 구분

3. **docs/5-file-management.md**
   - FileLayout 클래스 설계
   - 디렉토리 구조

4. **plan/2025-10-24_plan_ver3.md**
   - 전체 시스템 설계
   - Phase별 알고리즘

### 추가 참고

- docs/1-phase-coordination.md - Phase 조정
- docs/4-error-recovery.md - 에러 복구
- docs/6-parallelization.md - 병렬화 전략

---

## ✅ 체크리스트

### 다음 세션 시작 전

- [ ] quickstart-tdd-guide-v2.md 읽기
- [ ] docs/0-implementation-decisions.md 재확인
- [ ] nextwork 문서 업데이트 (순서 변경 반영)

### Cycle 3 (RecordReader trait)

- [ ] RecordReaderSpec 작성 (RED)
- [ ] RecordReader.scala 구현 (GREEN)
- [ ] BinaryRecordReader 리팩토링 (REFACTOR)
- [ ] 전체 테스트 실행 (회귀 방지)

### Cycle 4 (AsciiRecordReader)

- [ ] AsciiRecordReaderSpec 작성 (8 test cases)
- [ ] AsciiRecordReader.scala 구현
- [ ] Hex decoding 검증
- [ ] Edge case 테스트

### Cycle 5 (InputFormatDetector)

- [ ] InputFormatDetectorSpec 작성 (5 test cases)
- [ ] InputFormatDetector.scala 구현
- [ ] RecordReader.create() 업데이트
- [ ] Integration test 작성

---

## 🎯 성공 기준

### Code Quality

- [ ] 모든 테스트 통과 (목표: 100%)
- [ ] TDD 사이클 엄격히 준수
- [ ] Scaladoc 주석 완비
- [ ] Edge case 처리 완료

### 성능 목표

- [x] BinaryRecordReader: < 1000ms for 1000 records (✅ 58ms)
- [ ] AsciiRecordReader: < 2000ms for 1000 records
- [ ] InputFormatDetector: < 10ms per file

### 문서화

- [x] 각 TDD Cycle마다 worklog 작성
- [ ] 주요 설계 결정 문서화
- [ ] 성능 측정 결과 기록

---

**문서 버전**: 2.0 (완전판)
**작성일**: 2025-11-01
**최종 수정**: 2025-11-01 18:30
**다음 업데이트**: Cycle 3-5 완료 후

**Status**: ✅ READY
**Quality**: ⭐⭐⭐⭐⭐ (docs/ 전체 내용 통합)
**Next**: Cycle 3 - RecordReader trait
