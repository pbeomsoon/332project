# Week 5 Progress - Distributed Sorting System

**Team Silver**: 권동연, 박범순, 임지훈

## 구현 현황 (Week 5)

### 완료된 항목
- ✅ **Record 클래스** (Week 4에서 완료)
- ✅ **RecordReader 추상화** (100% 완료)
  - RecordReader trait 정의
  - BinaryRecordReader 구현
  - AsciiRecordReader 구현
  - ASCII/Binary 자동 감지 (InputFormatDetector)
- ✅ **FileLayout** (100% 완료)
  - 입력/출력 디렉토리 관리
  - 임시 파일 경로 생성
  - 디스크 공간 검증
- ✅ **RecordWriter** (100% 완료)
  - Binary/ASCII 형식 출력
  - 버퍼링 지원

### 프로젝트 구조
```
week5_work/
├── build.sbt              # SBT 빌드 설정
├── project/
│   ├── build.properties
│   └── plugins.sbt
└── src/
    └── main/scala/distsort/core/
        ├── Record.scala
        ├── RecordReader.scala         # ⭐ NEW
        ├── BinaryRecordReader.scala   # ⭐ NEW
        ├── AsciiRecordReader.scala    # ⭐ NEW
        ├── InputFormatDetector.scala  # ⭐ NEW
        ├── FileLayout.scala           # ⭐ NEW
        └── RecordWriter.scala         # ⭐ NEW
```

### 핵심 기능

#### 1. 자동 형식 감지 (InputFormatDetector)
```scala
// 파일의 첫 1000 바이트를 읽어 ASCII/Binary 자동 판별
val format = InputFormatDetector.detectFormat(file)
// ASCII ratio > 90% → Ascii, 아니면 Binary
```

#### 2. RecordReader 추상화
```scala
sealed trait DataFormat
object DataFormat {
  case object Binary extends DataFormat
  case object Ascii extends DataFormat
}

trait RecordReader {
  def readRecord(input: InputStream): Option[Array[Byte]]
}

// 팩토리 패턴
object RecordReader {
  def create(format: DataFormat): RecordReader = format match {
    case DataFormat.Binary => new BinaryRecordReader()
    case DataFormat.Ascii => new AsciiRecordReader()
  }
}
```

#### 3. FileLayout - 파일 시스템 관리
```scala
class FileLayout(
  inputDirs: List[File],
  outputDir: File,
  tempBaseDir: File,
  workerId: String
) {
  // 입력 파일 검증
  def validateInputDirectories(): Unit

  // 임시 디렉토리 생성
  def createTemporaryStructure(): Unit

  // 디스크 공간 확인
  def ensureSufficientDiskSpace(): Unit

  // 임시 파일 정리
  def cleanupTemporaryFiles(): Unit
}
```

### 테스트 실행
```bash
cd week5_work/
sbt compile
# Note: 테스트는 main/ 프로젝트에서 수행됨
```

### PDF 요구사항 충족
- ✅ **Requirement 1**: ASCII/Binary 자동 감지 (명령행 옵션 없이)
- ✅ **Requirement 2**: 입력 디렉토리 보호 (읽기 전용)
- ✅ **Requirement 3**: 임시 파일 정리 (출력 디렉토리는 partition.*만)

### 다음 단계 (Week 6)
- ExternalSorter 구현 (External Sort 알고리즘)
- Partitioner 구현 (Range-based partitioning)
- K-way Merge 구현

---

**Week 5 진행 일자**: 2025-11-12
**상태**: Core I/O Components 완료
