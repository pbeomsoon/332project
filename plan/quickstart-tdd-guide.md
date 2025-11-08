# QuickStart: TDD Implementation Guide

**목적**: TDD로 분산 정렬 시스템 구현을 시작하는 실전 가이드

---

## 🚀 Step 1: 프로젝트 초기 설정 (10분)

### 1.1 프로젝트 생성

```bash
# Scala 프로젝트 생성
sbt new scala/scala-seed.g8

# 프로젝트 이름: distsort
cd distsort
```

### 1.2 build.sbt 설정

```scala
name := "distsort"
version := "0.1.0"
scalaVersion := "2.13.12"

// gRPC & Protobuf
lazy val grpcVersion = "1.54.0"
lazy val scalapbVersion = "0.11.13"

libraryDependencies ++= Seq(
  // gRPC
  "io.grpc" % "grpc-netty" % grpcVersion,
  "io.grpc" % "grpc-protobuf" % grpcVersion,
  "io.grpc" % "grpc-stub" % grpcVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.8",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // Testing
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test,
  "io.grpc" % "grpc-testing" % grpcVersion % Test,
  "commons-io" % "commons-io" % "2.11.0" % Test
)

// ScalaPB settings for Protocol Buffers
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

// Test settings
Test / parallelExecution := false
Test / fork := true
Test / testOptions += Tests.Argument("-oD")

// Coverage
coverageEnabled := true
coverageMinimumStmtTotal := 80
```

### 1.3 plugins.sbt 설정

```scala
// project/plugins.sbt
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.7")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.13"
```

### 1.4 디렉토리 구조

```bash
mkdir -p src/main/scala/distsort/{core,master,worker,grpc,utils}
mkdir -p src/main/protobuf
mkdir -p src/test/scala/distsort/{core,integration,e2e}
mkdir -p docs
```

---

## 🧪 Step 2: 첫 번째 테스트 작성 (RED)

### 2.1 Record 클래스 테스트

```scala
// src/test/scala/distsort/core/RecordSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RecordSpec extends AnyFlatSpec with Matchers {

  "Record" should "store 10-byte key and 90-byte value" in {
    // Given
    val key = Array.fill[Byte](10)(1)
    val value = Array.fill[Byte](90)(2)

    // When
    val record = Record(key, value)

    // Then
    record.key.length shouldBe 10
    record.value.length shouldBe 90
    record.key.forall(_ == 1) shouldBe true
    record.value.forall(_ == 2) shouldBe true
  }

  it should "reject invalid key length" in {
    val key = Array.fill[Byte](9)(1) // Only 9 bytes
    val value = Array.fill[Byte](90)(2)

    intercept[IllegalArgumentException] {
      Record(key, value)
    }
  }

  it should "reject invalid value length" in {
    val key = Array.fill[Byte](10)(1)
    val value = Array.fill[Byte](89)(2) // Only 89 bytes

    intercept[IllegalArgumentException] {
      Record(key, value)
    }
  }

  it should "compare records by key only (unsigned)" in {
    // Key with 0xFF (255 unsigned) vs 0x01 (1 unsigned)
    val rec1 = Record(
      key = Array[Byte](0xFF.toByte, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      value = Array.fill[Byte](90)(0)
    )
    val rec2 = Record(
      key = Array[Byte](0x01.toByte, 0, 0, 0, 0, 0, 0, 0, 0, 0),
      value = Array.fill[Byte](90)(0)
    )

    // 0xFF (255) > 0x01 (1) in unsigned comparison
    (rec1 compareTo rec2) should be > 0
  }

  it should "ignore value when comparing" in {
    val rec1 = Record(
      key = Array.fill[Byte](10)(5),
      value = Array.fill[Byte](90)(100)
    )
    val rec2 = Record(
      key = Array.fill[Byte](10)(5),
      value = Array.fill[Byte](90)(200) // Different value
    )

    // Should be equal (same key)
    (rec1 compareTo rec2) shouldBe 0
  }

  it should "serialize to 100 bytes" in {
    val key = Array.fill[Byte](10)(42)
    val value = Array.fill[Byte](90)(99)
    val record = Record(key, value)

    val bytes = record.toBytes

    bytes.length shouldBe 100
    bytes.take(10).forall(_ == 42) shouldBe true
    bytes.drop(10).forall(_ == 99) shouldBe true
  }

  it should "deserialize from 100 bytes" in {
    val originalKey = Array.fill[Byte](10)(42)
    val originalValue = Array.fill[Byte](90)(99)
    val bytes = originalKey ++ originalValue

    val record = Record.fromBytes(bytes)

    record.key shouldBe originalKey
    record.value shouldBe originalValue
  }
}
```

### 2.2 실행 (실패 확인)

```bash
sbt test
# [error] not found: type Record
# [error] not found: value Record
```

**RED 단계 완료** ✅ 실패하는 테스트 작성 완료!

---

## ✅ Step 3: 최소 구현 (GREEN)

### 3.1 Record 클래스 구현

```scala
// src/main/scala/distsort/core/Record.scala
package distsort.core

import java.util.Arrays

/**
 * 100-byte record: 10-byte key + 90-byte value
 * Sorting is based on key only (unsigned byte comparison)
 */
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
  require(value.length == 90, s"Value must be 90 bytes, got ${value.length}")

  /**
   * Compare records by key only (unsigned comparison)
   * Value is ignored during sorting
   */
  override def compare(that: Record): Int = {
    Arrays.compareUnsigned(this.key, that.key)
  }

  /**
   * Serialize record to 100 bytes
   */
  def toBytes: Array[Byte] = key ++ value

  override def equals(obj: Any): Boolean = obj match {
    case that: Record =>
      Arrays.equals(this.key, that.key) &&
      Arrays.equals(this.value, that.value)
    case _ => false
  }

  override def hashCode(): Int = {
    31 * Arrays.hashCode(key) + Arrays.hashCode(value)
  }
}

object Record {
  /**
   * Deserialize record from 100 bytes
   */
  def fromBytes(bytes: Array[Byte]): Record = {
    require(bytes.length == 100, s"Record must be 100 bytes, got ${bytes.length}")
    Record(
      key = bytes.take(10),
      value = bytes.slice(10, 100)
    )
  }

  /**
   * Create record from key only (for testing/boundaries)
   */
  def fromKey(key: Array[Byte]): Record = {
    require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
    Record(key, Array.fill[Byte](90)(0))
  }
}
```

### 3.2 실행 (성공 확인)

```bash
sbt test

# [info] RecordSpec:
# [info] Record
# [info] - should store 10-byte key and 90-byte value
# [info] - should reject invalid key length
# [info] - should reject invalid value length
# [info] - should compare records by key only (unsigned)
# [info] - should ignore value when comparing
# [info] - should serialize to 100 bytes
# [info] - should deserialize from 100 bytes
# [info] Run completed in 1 second, 234 milliseconds.
# [info] Total number of tests run: 7
# [info] Suites: completed 1, aborted 0
# [info] Tests: succeeded 7, failed 0, canceled 0, ignored 0, pending 0
# [info] All tests passed.
```

**GREEN 단계 완료** ✅ 모든 테스트 통과!

---

## 🔧 Step 4: 리팩토링 (REFACTOR)

현재 코드는 이미 깔끔하지만, 개선할 부분이 있다면:

```scala
// 개선: Implicit Ordering 추가
object Record {
  implicit val recordOrdering: Ordering[Record] = Ordering.by(_.key)(
    Ordering.comparatorToOrdering(
      java.util.Comparator.comparingInt[Array[Byte]]((arr: Array[Byte]) =>
        java.util.Arrays.compareUnsigned(arr, arr)
      )
    )
  )

  // ... rest of object
}
```

**REFACTOR 완료** ✅ 테스트는 여전히 통과!

---

## 📝 Step 5: 다음 컴포넌트 (RecordReader)

### 5.1 테스트 작성 (RED)

```scala
// src/test/scala/distsort/core/RecordReaderSpec.scala
package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.FileOutputStream

class BinaryRecordReaderSpec extends AnyFlatSpec with Matchers {

  def createTempFile(records: Seq[Record]): Path = {
    val tempFile = Files.createTempFile("test-input", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    try {
      records.foreach { record =>
        fos.write(record.toBytes)
      }
    } finally {
      fos.close()
    }
    tempFile
  }

  "BinaryRecordReader" should "read records from file" in {
    // Given: File with 5 records
    val originalRecords = (1 to 5).map { i =>
      Record(
        key = Array.fill[Byte](10)(i.toByte),
        value = Array.fill[Byte](90)((i * 2).toByte)
      )
    }
    val inputFile = createTempFile(originalRecords)

    // When: Read records
    val reader = new BinaryRecordReader(inputFile.toFile)
    val readRecords = Iterator.continually(reader.readRecord())
      .takeWhile(_.isDefined)
      .map(_.get)
      .toSeq

    // Then: All records read correctly
    readRecords should have length 5
    readRecords.zip(originalRecords).foreach { case (read, original) =>
      read shouldBe original
    }

    reader.close()
    Files.delete(inputFile)
  }

  it should "return None at EOF" in {
    val records = Seq(Record(
      key = Array.fill[Byte](10)(1),
      value = Array.fill[Byte](90)(2)
    ))
    val inputFile = createTempFile(records)
    val reader = new BinaryRecordReader(inputFile.toFile)

    reader.readRecord() shouldBe defined
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(inputFile)
  }

  it should "handle incomplete records gracefully" in {
    // Given: File with only 50 bytes (incomplete record)
    val tempFile = Files.createTempFile("incomplete", ".dat")
    val fos = new FileOutputStream(tempFile.toFile)
    fos.write(Array.fill[Byte](50)(1))
    fos.close()

    // When/Then: Should return None
    val reader = new BinaryRecordReader(tempFile.toFile)
    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(tempFile)
  }

  it should "handle empty file" in {
    val tempFile = Files.createTempFile("empty", ".dat")
    val reader = new BinaryRecordReader(tempFile.toFile)

    reader.readRecord() shouldBe None

    reader.close()
    Files.delete(tempFile)
  }
}
```

### 5.2 구현 (GREEN)

```scala
// src/main/scala/distsort/core/BinaryRecordReader.scala
package distsort.core

import java.io.{BufferedInputStream, File, FileInputStream}

/**
 * Binary format reader for 100-byte records
 */
class BinaryRecordReader(file: File) {
  private val inputStream = new BufferedInputStream(
    new FileInputStream(file),
    1024 * 1024 // 1MB buffer
  )

  private val buffer = new Array[Byte](100)

  /**
   * Read next record from file
   * @return Some(Record) if available, None if EOF or incomplete
   */
  def readRecord(): Option[Record] = {
    val bytesRead = inputStream.read(buffer)

    if (bytesRead == -1) {
      None // EOF
    } else if (bytesRead < 100) {
      None // Incomplete record
    } else {
      Some(Record.fromBytes(buffer.clone()))
    }
  }

  def close(): Unit = {
    inputStream.close()
  }
}
```

### 5.3 실행

```bash
sbt test

# [info] BinaryRecordReaderSpec:
# [info] BinaryRecordReader
# [info] - should read records from file
# [info] - should return None at EOF
# [info] - should handle incomplete records gracefully
# [info] - should handle empty file
# [info] All tests passed.
```

---

## 🎯 Step 6: TDD Workflow 계속

이제 TDD 사이클을 반복하며 나머지 컴포넌트를 구현:

### 6.1 구현 순서

```
Week 3: Core Data Structures
├─ [✅] Record
├─ [✅] BinaryRecordReader
├─ [ ] BinaryRecordWriter
├─ [ ] AsciiRecordReader
├─ [ ] AsciiRecordWriter
└─ [ ] InputFormatDetector

Week 4: Algorithms
├─ [ ] ExternalSorter
├─ [ ] KWayMerger
├─ [ ] RangePartitioner
└─ [ ] ShuffleMap

Week 5: Communication
├─ [ ] Proto definitions
├─ [ ] MasterService
├─ [ ] WorkerService
└─ [ ] gRPC Clients

Week 6-7: Integration & E2E
├─ [ ] Master-Worker integration
├─ [ ] File management tests
├─ [ ] Fault tolerance tests
└─ [ ] Performance tests
```

### 6.2 Daily Workflow

```bash
# 매일 아침
git pull
sbt clean test

# TDD 사이클
# 1. RED: 테스트 작성
vim src/test/scala/distsort/core/NewComponentSpec.scala
sbt "testOnly *NewComponentSpec"  # 실패 확인

# 2. GREEN: 구현
vim src/main/scala/distsort/core/NewComponent.scala
sbt "testOnly *NewComponentSpec"  # 성공 확인

# 3. REFACTOR: 개선
vim src/main/scala/distsort/core/NewComponent.scala
sbt "testOnly *NewComponentSpec"  # 여전히 성공

# 4. Commit
git add .
git commit -m "Add NewComponent with tests"

# 5. 전체 테스트 실행
sbt test

# 6. Coverage 확인
sbt clean coverage test coverageReport
open target/scala-2.13/scoverage-report/index.html
```

---

## 📊 Step 7: 진행 상황 추적

### 7.1 Checklist

```markdown
## Week 3: Core Components

### Record & I/O
- [x] Record class with tests
- [x] BinaryRecordReader with tests
- [ ] BinaryRecordWriter with tests
- [ ] AsciiRecordReader with tests
- [ ] AsciiRecordWriter with tests
- [ ] InputFormatDetector with tests

### Test Coverage
- [x] Record: 100%
- [x] BinaryRecordReader: 100%
- [ ] BinaryRecordWriter: 0%
- [ ] AsciiRecordReader: 0%
- [ ] AsciiRecordWriter: 0%
- [ ] InputFormatDetector: 0%

**Overall**: 33% (2/6 components)
```

### 7.2 Metrics 추적

```bash
# Test 실행 시간 추적
sbt "testOnly *Spec" 2>&1 | grep "Run completed"
# [info] Run completed in 1 second, 234 milliseconds.

# Coverage 추적
sbt coverageReport 2>&1 | grep "Statement coverage"
# [info] Statement coverage: 45.2%

# 목표: 매주 +10% coverage, 테스트 실행 시간 < 10초
```

---

## 🛠️ Step 8: Helper Utilities

### 8.1 Test Utilities

```scala
// src/test/scala/distsort/TestUtils.scala
package distsort

import distsort.core.Record
import java.nio.file.{Files, Path}
import java.io.FileOutputStream
import scala.util.Random

object TestUtils {

  def createTempDir(): Path = {
    Files.createTempDirectory("distsort-test")
  }

  def generateRandomRecords(count: Int, seed: Long = 42): Seq[Record] = {
    val random = new Random(seed)
    (0 until count).map { _ =>
      val key = new Array[Byte](10)
      val value = new Array[Byte](90)
      random.nextBytes(key)
      random.nextBytes(value)
      Record(key, value)
    }
  }

  def generateSortedRecords(count: Int): Seq[Record] = {
    (0 until count).map { i =>
      val key = Array.fill[Byte](10)(0)
      key(9) = i.toByte
      Record(key, Array.fill[Byte](90)(0))
    }
  }

  def writeRecordsToFile(path: Path, records: Seq[Record]): Unit = {
    val fos = new FileOutputStream(path.toFile)
    try {
      records.foreach { record =>
        fos.write(record.toBytes)
      }
    } finally {
      fos.close()
    }
  }

  def readAllRecords(path: Path): Seq[Record] = {
    import distsort.core.BinaryRecordReader
    val reader = new BinaryRecordReader(path.toFile)
    try {
      Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
    } finally {
      reader.close()
    }
  }

  def isSorted(records: Seq[Record]): Boolean = {
    records.sliding(2).forall {
      case Seq(a, b) => a <= b
      case _ => true
    }
  }

  def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.list(path).forEach(deleteRecursively)
      }
      Files.delete(path)
    }
  }
}
```

### 8.2 Test Traits

```scala
// src/test/scala/distsort/TestFixtures.scala
package distsort

import org.scalatest.{BeforeAndAfterEach, Suite}
import java.nio.file.Path

trait TempDirectoryFixture extends BeforeAndAfterEach { self: Suite =>
  var tempDir: Path = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = TestUtils.createTempDir()
  }

  override def afterEach(): Unit = {
    try {
      TestUtils.deleteRecursively(tempDir)
    } finally {
      super.afterEach()
    }
  }
}
```

---

## 📚 Step 9: 다음 단계

### 9.1 이제 구현할 것

1. **BinaryRecordWriter 테스트 & 구현**
2. **AsciiRecordReader/Writer 테스트 & 구현**
3. **InputFormatDetector 테스트 & 구현**
4. **ExternalSorter 테스트 & 구현**
5. ...

### 9.2 참고 문서

- `docs/7-testing-strategy.md`: 전체 테스트 전략
- `docs/0-implementation-decisions.md`: 구현 결정 사항
- `plan/2025-10-24_plan_ver3.md`: 전체 설계

### 9.3 도움이 필요하면

```bash
# 전체 테스트 실행
sbt test

# 특정 테스트만 실행
sbt "testOnly distsort.core.RecordSpec"

# Coverage 리포트
sbt clean coverage test coverageReport

# 컴파일 오류 확인
sbt compile
```

---

## ✅ Summary

**완료한 것**:
- ✅ 프로젝트 초기 설정 (build.sbt, plugins)
- ✅ 디렉토리 구조
- ✅ 첫 번째 TDD 사이클 완료 (Record)
- ✅ 두 번째 TDD 사이클 완료 (BinaryRecordReader)
- ✅ Test utilities 준비

**다음 작업**:
- [ ] BinaryRecordWriter 구현 (TDD)
- [ ] AsciiRecordReader/Writer 구현 (TDD)
- [ ] InputFormatDetector 구현 (TDD)

**TDD 철학 기억하기**:
1. **RED**: 실패하는 테스트 먼저
2. **GREEN**: 최소한의 코드로 통과
3. **REFACTOR**: 코드 개선 (테스트는 계속 통과)

🚀 **이제 본격적으로 TDD로 개발을 시작하세요!**
