# File Management & Layout

**문서 목적**: 분산 정렬 시스템의 파일 경로 관리, 디렉토리 구조, 파일 명명 규칙을 상세히 문서화

**참조 문서**:
- `2025-10-24_plan_ver3.md` - 전체 시스템 설계
- `2-worker-state-machine.md` - Worker 상태별 파일 관리
- `4-error-recovery.md` - 파일 cleanup 전략

---

## 1. 디렉토리 구조 개요

### 1.1 Master 디렉토리 구조

```
<working-directory>/
├── master.log                    # Master 실행 로그
├── master_checkpoint.json        # Master 상태 checkpoint (optional)
└── metrics/
    ├── phase_timings.csv         # Phase별 소요 시간
    ├── worker_health.csv         # Worker health metrics
    └── error_log.csv             # 에러 발생 기록
```

Master는 주로 coordination 역할이므로 데이터 파일을 저장하지 않음.

### 1.2 Worker 디렉토리 구조

```
<worker-root>/
├── input/                        # 입력 파일들 (여러 디렉토리 가능)
│   ├── dir1/
│   │   ├── input_file1.dat
│   │   └── input_file2.dat
│   └── dir2/
│       └── input_file3.dat
│
├── temp/                         # 임시 작업 디렉토리
│   ├── sampling/
│   │   └── sample_keys.bin      # Sampling phase 임시 데이터
│   ├── sorting/
│   │   ├── chunk_0000.sorted    # External sort chunks
│   │   ├── chunk_0001.sorted
│   │   └── ...
│   └── partitioning/
│       ├── partition.0          # Local partitions (shuffle 전)
│       ├── partition.1
│       └── ...
│
├── received/                     # Shuffle로 받은 partition 파일들
│   ├── partition.0              # 다른 worker들로부터 받은 파일
│   ├── partition.3
│   └── ...
│
├── output/                       # 최종 출력 디렉토리
│   ├── partition.0              # 최종 merged partition 파일
│   ├── partition.1
│   └── ...
│
└── logs/
    ├── worker.log               # Worker 실행 로그
    ├── state_transitions.log   # State machine 전환 기록
    └── rpc_calls.log           # RPC 호출 기록
```

### 1.3 FileLayout 클래스

```scala
import java.io.File
import java.nio.file.{Files, Path, Paths}

/**
 * Worker의 파일 시스템 레이아웃을 관리하는 클래스
 * 모든 파일 경로 접근은 이 클래스를 통해 수행
 */
class FileLayout(
  inputDirs: Seq[String],
  outputDir: String,
  tempDir: Option[String] = None
) {
  // Base directories
  private val outputPath: Path = Paths.get(outputDir).toAbsolutePath
  private val tempBasePath: Path = tempDir match {
    case Some(dir) => Paths.get(dir).toAbsolutePath
    case None => Paths.get(outputDir, ".temp").toAbsolutePath
  }

  // Subdirectories
  private val samplingDir: Path = tempBasePath.resolve("sampling")
  private val sortingDir: Path = tempBasePath.resolve("sorting")
  private val partitioningDir: Path = tempBasePath.resolve("partitioning")
  private val receivedDir: Path = outputPath.resolve(".received")
  private val logsDir: Path = outputPath.resolve("logs")

  // Initialize directory structure
  def initialize(): Unit = {
    createDirectoryIfNotExists(outputPath)
    createDirectoryIfNotExists(tempBasePath)
    createDirectoryIfNotExists(samplingDir)
    createDirectoryIfNotExists(sortingDir)
    createDirectoryIfNotExists(partitioningDir)
    createDirectoryIfNotExists(receivedDir)
    createDirectoryIfNotExists(logsDir)

    logger.info(s"File layout initialized:")
    logger.info(s"  Output: $outputPath")
    logger.info(s"  Temp: $tempBasePath")
  }

  private def createDirectoryIfNotExists(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
      logger.debug(s"Created directory: $path")
    }
  }

  // ============================================================
  // Input files
  // ============================================================

  def getInputFiles: Seq[File] = {
    inputDirs.flatMap { dir =>
      val dirFile = new File(dir)
      if (!dirFile.exists()) {
        logger.warn(s"Input directory does not exist: $dir")
        Seq.empty
      } else if (!dirFile.isDirectory) {
        logger.warn(s"Input path is not a directory: $dir")
        Seq.empty
      } else {
        val allFiles = listFilesRecursively(dirFile)

        logger.info(s"Found ${allFiles.size} files in $dir")

        allFiles
      }
    }
  }

  private def listFilesRecursively(dir: File): Seq[File] = {
    // Get all files and subdirectories
    val allItems = dir.listFiles()
    if (allItems == null) {
      logger.warn(s"Cannot list files in directory: ${dir.getAbsolutePath}")
      return Seq.empty
    }

    val (files, subdirs) = allItems.partition(_.isFile)

    // Recursively process subdirectories
    val subFiles = subdirs.flatMap(listFilesRecursively)

    files ++ subFiles
  }

  // ============================================================
  // Sampling phase
  // ============================================================

  def getSamplingFile: File = {
    samplingDir.resolve("sample_keys.bin").toFile
  }

  // ============================================================
  // Sorting phase
  // ============================================================

  def getSortedChunkFile(chunkIndex: Int): File = {
    sortingDir.resolve(f"chunk_$chunkIndex%04d.sorted").toFile
  }

  def listSortedChunks: Seq[File] = {
    val chunkPattern = "chunk_\\d{4}\\.sorted".r
    sortingDir.toFile.listFiles()
      .filter(f => chunkPattern.findFirstIn(f.getName).isDefined)
      .sortBy(_.getName)
      .toSeq
  }

  // ============================================================
  // Partitioning phase
  // ============================================================

  def getLocalPartitionFile(partitionId: Int): File = {
    partitioningDir.resolve(s"partition.$partitionId").toFile
  }

  def getLocalPartitionTempFile(partitionId: Int): File = {
    partitioningDir.resolve(s"partition.$partitionId.tmp").toFile
  }

  def listLocalPartitions: Seq[File] = {
    val partitionPattern = "partition\\.\\d+".r
    partitioningDir.toFile.listFiles()
      .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
      .sortBy(f => extractPartitionId(f.getName))
      .toSeq
  }

  // ============================================================
  // Received partitions (shuffle)
  // ============================================================

  def getReceivedPartitionFile(partitionId: Int, fromWorkerId: Option[String] = None): File = {
    fromWorkerId match {
      case Some(workerId) =>
        receivedDir.resolve(s"partition.$partitionId.from_$workerId").toFile
      case None =>
        receivedDir.resolve(s"partition.$partitionId").toFile
    }
  }

  def getReceivedPartitionTempFile(partitionId: Int, fromWorkerId: String): File = {
    receivedDir.resolve(s"partition.$partitionId.from_$fromWorkerId.tmp").toFile
  }

  def listReceivedPartitions(partitionId: Int): Seq[File] = {
    val partitionPattern = s"partition\\.$partitionId(\\.from_.*)?".r
    receivedDir.toFile.listFiles()
      .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
      .sortBy(_.getName)
      .toSeq
  }

  def listAllReceivedPartitions: Seq[File] = {
    val partitionPattern = "partition\\.\\d+(\\.from_.*)?".r
    receivedDir.toFile.listFiles()
      .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
      .sortBy(f => extractPartitionId(f.getName))
      .toSeq
  }

  // ============================================================
  // Output files
  // ============================================================

  def getOutputPartitionFile(partitionId: Int): File = {
    outputPath.resolve(s"partition.$partitionId").toFile
  }

  def getOutputPartitionTempFile(partitionId: Int): File = {
    outputPath.resolve(s"partition.$partitionId.tmp").toFile
  }

  def listOutputPartitions: Seq[File] = {
    val partitionPattern = "partition\\.\\d+".r
    outputPath.toFile.listFiles()
      .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
      .sortBy(f => extractPartitionId(f.getName))
      .toSeq
  }

  // ============================================================
  // Logs
  // ============================================================

  def getWorkerLogFile: File = {
    logsDir.resolve("worker.log").toFile
  }

  def getStateTransitionLogFile: File = {
    logsDir.resolve("state_transitions.log").toFile
  }

  def getRpcLogFile: File = {
    logsDir.resolve("rpc_calls.log").toFile
  }

  // ============================================================
  // Cleanup operations
  // ============================================================

  def cleanupSamplingFiles(): Unit = {
    logger.info("Cleaning up sampling files")
    deleteDirectory(samplingDir)
    createDirectoryIfNotExists(samplingDir)
  }

  def cleanupSortedChunks(): Unit = {
    logger.info("Cleaning up sorted chunks")
    deleteDirectory(sortingDir)
    createDirectoryIfNotExists(sortingDir)
  }

  def cleanupLocalPartitions(): Unit = {
    logger.info("Cleaning up local partitions")
    deleteDirectory(partitioningDir)
    createDirectoryIfNotExists(partitioningDir)
  }

  def cleanupReceivedPartitions(): Unit = {
    logger.info("Cleaning up received partitions")
    deleteDirectory(receivedDir)
    createDirectoryIfNotExists(receivedDir)
  }

  def cleanupTemporaryFiles(): Unit = {
    logger.info("Cleaning up all temporary files")
    deleteDirectory(tempBasePath)
    initialize()  // Recreate directory structure
  }

  def cleanupAll(): Unit = {
    logger.warn("Cleaning up ALL files including output")
    deleteDirectory(tempBasePath)
    deleteDirectory(receivedDir)

    // Delete output partition files (but keep logs)
    listOutputPartitions.foreach { file =>
      file.delete()
      logger.debug(s"Deleted output file: ${file.getName}")
    }
  }

  private def deleteDirectory(path: Path): Unit = {
    if (Files.exists(path)) {
      import scala.collection.JavaConverters._

      Files.walk(path)
        .iterator()
        .asScala
        .toSeq
        .reverse  // Delete files before directories
        .foreach { p =>
          try {
            Files.delete(p)
          } catch {
            case ex: Exception =>
              logger.warn(s"Failed to delete $p: ${ex.getMessage}")
          }
        }
    }
  }

  // ============================================================
  // Disk space management
  // ============================================================

  def checkDiskSpace(requiredBytes: Long): Boolean = {
    val tempSpace = tempBasePath.toFile.getFreeSpace
    val outputSpace = outputPath.toFile.getFreeSpace

    logger.info(s"Disk space check: required ${requiredBytes / (1024 * 1024)}MB, " +
      s"temp ${tempSpace / (1024 * 1024)}MB, " +
      s"output ${outputSpace / (1024 * 1024)}MB")

    tempSpace >= requiredBytes && outputSpace >= requiredBytes
  }

  def estimateRequiredSpace(totalInputSize: Long): Long = {
    // Conservative estimate:
    // - Sorted chunks: ~1.2x input size (overhead for metadata)
    // - Local partitions: ~1.0x input size
    // - Received partitions: ~1.0x input size (worst case: all data)
    // - Output: ~1.0x input size
    // Total: ~4.2x input size

    val overhead = 1.2
    (totalInputSize * 4.2 * overhead).toLong
  }

  def getDiskUsageReport: DiskUsageReport = {
    val samplingSize = calculateDirectorySize(samplingDir)
    val sortingSize = calculateDirectorySize(sortingDir)
    val partitioningSize = calculateDirectorySize(partitioningDir)
    val receivedSize = calculateDirectorySize(receivedDir)
    val outputSize = listOutputPartitions.map(_.length()).sum

    DiskUsageReport(
      samplingBytes = samplingSize,
      sortingBytes = sortingSize,
      partitioningBytes = partitioningSize,
      receivedBytes = receivedSize,
      outputBytes = outputSize,
      totalBytes = samplingSize + sortingSize + partitioningSize + receivedSize + outputSize
    )
  }

  private def calculateDirectorySize(path: Path): Long = {
    if (!Files.exists(path)) return 0L

    import scala.collection.JavaConverters._
    Files.walk(path)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p))
      .map(p => Files.size(p))
      .sum
  }

  // ============================================================
  // Utility methods
  // ============================================================

  private def extractPartitionId(fileName: String): Int = {
    val pattern = "partition\\.(\\d+)".r
    pattern.findFirstMatchIn(fileName) match {
      case Some(m) => m.group(1).toInt
      case None => throw new IllegalArgumentException(s"Invalid partition file name: $fileName")
    }
  }

  def getTempBasePath: Path = tempBasePath
  def getOutputPath: Path = outputPath
  def getReceivedPath: Path = receivedDir
}

case class DiskUsageReport(
  samplingBytes: Long,
  sortingBytes: Long,
  partitioningBytes: Long,
  receivedBytes: Long,
  outputBytes: Long,
  totalBytes: Long
) {
  def toHumanReadable: String = {
    def format(bytes: Long): String = f"${bytes / (1024.0 * 1024)}%.2f MB"

    s"""Disk Usage Report:
       |  Sampling:     ${format(samplingBytes)}
       |  Sorting:      ${format(sortingBytes)}
       |  Partitioning: ${format(partitioningBytes)}
       |  Received:     ${format(receivedBytes)}
       |  Output:       ${format(outputBytes)}
       |  Total:        ${format(totalBytes)}
       |""".stripMargin
  }
}
```

---

## 2. 파일 명명 규칙

### 2.1 Sampling Phase

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Sample keys | `sample_keys.bin` | `sample_keys.bin` | Binary format의 sampled keys |

### 2.2 Sorting Phase

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Sorted chunks | `chunk_NNNN.sorted` | `chunk_0000.sorted`, `chunk_0042.sorted` | 4자리 zero-padded chunk index |
| Chunk temp | `chunk_NNNN.sorted.tmp` | `chunk_0000.sorted.tmp` | 임시 chunk 파일 (쓰기 중) |

### 2.3 Partitioning Phase

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Local partition | `partition.N` | `partition.0`, `partition.15` | 로컬에서 생성된 partition |
| Local partition temp | `partition.N.tmp` | `partition.0.tmp` | 임시 partition 파일 (쓰기 중) |

### 2.4 Shuffle Phase

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Received partition | `partition.N` | `partition.0` | 다른 worker로부터 받은 partition |
| Received (with sender) | `partition.N.from_WX` | `partition.3.from_W2` | Sender worker ID 포함 |
| Received temp | `partition.N.from_WX.tmp` | `partition.3.from_W2.tmp` | 수신 중인 임시 파일 |

### 2.5 Output Phase

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Final output | `partition.N` | `partition.0`, `partition.99` | 최종 merged partition 파일 |
| Output temp | `partition.N.tmp` | `partition.0.tmp` | Merge 중인 임시 파일 |

### 2.6 Logs

| 파일 유형 | 명명 규칙 | 예시 | 설명 |
|---------|---------|------|------|
| Worker log | `worker.log` | `worker.log` | 일반 실행 로그 |
| State transitions | `state_transitions.log` | `state_transitions.log` | State machine 전환 기록 |
| RPC calls | `rpc_calls.log` | `rpc_calls.log` | gRPC 호출 기록 |

---

## 3. 파일 Operations

### 3.1 Atomic File Write

```scala
object FileOperations {
  /**
   * Atomic file write using temp file + rename
   */
  def atomicWrite(targetFile: File, content: Array[Byte]): Unit = {
    val tempFile = new File(targetFile.getParent, s"${targetFile.getName}.tmp")

    try {
      // Write to temp file
      val outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))
      outputStream.write(content)
      outputStream.close()

      // Atomic rename
      Files.move(tempFile.toPath, targetFile.toPath, StandardCopyOption.ATOMIC_MOVE)

      logger.debug(s"Atomically wrote ${content.length} bytes to ${targetFile.getName}")

    } catch {
      case ex: Exception =>
        // Cleanup on failure
        if (tempFile.exists()) {
          tempFile.delete()
        }
        throw ex
    }
  }

  /**
   * Atomic file write using streaming
   */
  def atomicWriteStream(targetFile: File)(writer: OutputStream => Unit): Unit = {
    val tempFile = new File(targetFile.getParent, s"${targetFile.getName}.tmp")

    var outputStream: OutputStream = null
    try {
      outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))
      writer(outputStream)
      outputStream.close()

      // Atomic rename
      Files.move(tempFile.toPath, targetFile.toPath, StandardCopyOption.ATOMIC_MOVE)

      logger.debug(s"Atomically wrote to ${targetFile.getName}")

    } catch {
      case ex: Exception =>
        if (outputStream != null) {
          try { outputStream.close() } catch { case _: Exception => }
        }
        if (tempFile.exists()) {
          tempFile.delete()
        }
        throw ex
    }
  }
}
```

**사용 예시**:
```scala
class ExternalSorter {
  def writeSortedChunk(records: Seq[Record], chunkIndex: Int): File = {
    val chunkFile = fileLayout.getSortedChunkFile(chunkIndex)

    FileOperations.atomicWriteStream(chunkFile) { outputStream =>
      records.foreach { record =>
        outputStream.write(record.toBytes)
      }
    }

    logger.info(s"Wrote sorted chunk $chunkIndex: ${records.size} records")
    chunkFile
  }
}
```

### 3.2 Safe File Deletion

```scala
object FileOperations {
  /**
   * Safely delete a file, logging errors but not throwing
   */
  def safeDelete(file: File): Boolean = {
    if (!file.exists()) {
      logger.debug(s"File does not exist: ${file.getAbsolutePath}")
      return true
    }

    try {
      val deleted = file.delete()
      if (deleted) {
        logger.debug(s"Deleted file: ${file.getName}")
      } else {
        logger.warn(s"Failed to delete file: ${file.getAbsolutePath}")
      }
      deleted
    } catch {
      case ex: Exception =>
        logger.error(s"Exception deleting file ${file.getAbsolutePath}: $ex")
        false
    }
  }

  /**
   * Delete multiple files
   */
  def safeDeleteAll(files: Seq[File]): Int = {
    var deletedCount = 0
    files.foreach { file =>
      if (safeDelete(file)) {
        deletedCount += 1
      }
    }
    logger.info(s"Deleted $deletedCount/${files.size} files")
    deletedCount
  }

  /**
   * Delete files matching a pattern
   */
  def deleteMatching(directory: File, pattern: String): Int = {
    val regex = pattern.r
    val matchingFiles = directory.listFiles()
      .filter(f => regex.findFirstIn(f.getName).isDefined)

    safeDeleteAll(matchingFiles.toSeq)
  }
}
```

### 3.3 File Validation

```scala
object FileOperations {
  /**
   * Validate partition file structure
   */
  def validatePartitionFile(file: File, expectedRecordSize: Int = 100): ValidationResult = {
    if (!file.exists()) {
      return ValidationResult(valid = false, error = Some("File does not exist"))
    }

    val fileSize = file.length()

    // Check if file size is multiple of record size
    if (fileSize % expectedRecordSize != 0) {
      return ValidationResult(
        valid = false,
        error = Some(s"File size $fileSize is not multiple of record size $expectedRecordSize")
      )
    }

    // Check if file is readable
    try {
      val inputStream = new FileInputStream(file)
      val buffer = new Array[Byte](expectedRecordSize)
      val bytesRead = inputStream.read(buffer)
      inputStream.close()

      if (bytesRead != expectedRecordSize && bytesRead != -1) {
        return ValidationResult(
          valid = false,
          error = Some(s"Unexpected read size: $bytesRead")
        )
      }
    } catch {
      case ex: IOException =>
        return ValidationResult(
          valid = false,
          error = Some(s"IOException: ${ex.getMessage}")
        )
    }

    ValidationResult(valid = true, error = None)
  }

  case class ValidationResult(valid: Boolean, error: Option[String])
}
```

---

## 4. Phase별 파일 관리

### 4.1 Phase 1: Initialization

```scala
class WorkerNode {
  def initializeFileSystem(): Unit = {
    logger.info("Initializing file system")

    // Initialize FileLayout
    fileLayout.initialize()

    // Validate input directories exist
    val inputFiles = fileLayout.getInputFiles
    if (inputFiles.isEmpty) {
      throw new IllegalStateException("No input files found")
    }

    logger.info(s"Found ${inputFiles.size} input files")

    // Calculate total input size
    val totalInputSize = inputFiles.map(_.length()).sum
    logger.info(s"Total input size: ${totalInputSize / (1024 * 1024)} MB")

    // Check disk space
    val requiredSpace = fileLayout.estimateRequiredSpace(totalInputSize)
    if (!fileLayout.checkDiskSpace(requiredSpace)) {
      throw new IOException(s"Insufficient disk space: need ${requiredSpace / (1024 * 1024)} MB")
    }

    logger.info("File system initialization complete")
  }
}
```

### 4.2 Phase 2: Sampling

```scala
class ReservoirSampler {
  def saveSamplesToFile(samples: Seq[Array[Byte]]): Unit = {
    val sampleFile = fileLayout.getSamplingFile

    FileOperations.atomicWriteStream(sampleFile) { outputStream =>
      // Write sample count
      val countBytes = ByteBuffer.allocate(4).putInt(samples.size).array()
      outputStream.write(countBytes)

      // Write each sample
      samples.foreach { key =>
        outputStream.write(key)  // 10 bytes per key
      }
    }

    logger.info(s"Saved ${samples.size} samples to ${sampleFile.getName}")
  }

  def loadSamplesFromFile(): Seq[Array[Byte]] = {
    val sampleFile = fileLayout.getSamplingFile

    if (!sampleFile.exists()) {
      return Seq.empty
    }

    val inputStream = new BufferedInputStream(new FileInputStream(sampleFile))

    try {
      // Read sample count
      val countBytes = new Array[Byte](4)
      inputStream.read(countBytes)
      val count = ByteBuffer.wrap(countBytes).getInt()

      // Read samples
      val samples = (0 until count).map { _ =>
        val key = new Array[Byte](10)
        inputStream.read(key)
        key
      }

      logger.info(s"Loaded ${samples.size} samples from ${sampleFile.getName}")
      samples

    } finally {
      inputStream.close()
    }
  }
}
```

### 4.3 Phase 3: Sorting & Partitioning

```scala
class ExternalSorter {
  def sort(): Seq[File] = {
    logger.info("Starting external sort")

    val inputFiles = fileLayout.getInputFiles
    var chunkIndex = 0
    val sortedChunks = mutable.ArrayBuffer[File]()

    // Phase 1: Create sorted chunks
    inputFiles.foreach { inputFile =>
      val records = readRecordsFromFile(inputFile)
      val chunks = records.grouped(chunkSize).toSeq

      chunks.foreach { chunk =>
        val sortedChunk = chunk.sortWith((a, b) =>
          ByteArrayOrdering.compare(a.key, b.key) < 0
        )

        val chunkFile = fileLayout.getSortedChunkFile(chunkIndex)
        writeSortedChunk(sortedChunk, chunkFile)

        sortedChunks += chunkFile
        chunkIndex += 1
      }
    }

    logger.info(s"Created ${sortedChunks.size} sorted chunks")

    // Check disk usage
    val usage = fileLayout.getDiskUsageReport
    logger.info(usage.toHumanReadable)

    sortedChunks.toSeq
  }

  private def writeSortedChunk(records: Seq[Record], chunkFile: File): Unit = {
    FileOperations.atomicWriteStream(chunkFile) { outputStream =>
      records.foreach { record =>
        outputStream.write(record.toBytes)
      }
    }

    logger.debug(s"Wrote sorted chunk: ${chunkFile.getName}, ${records.size} records")
  }
}

class Partitioner {
  def createPartitions(sortedChunks: Seq[File], boundaries: Seq[Array[Byte]]): Seq[File] = {
    logger.info(s"Creating ${boundaries.length + 1} partitions")

    val numPartitions = boundaries.length + 1
    val partitionFiles = (0 until numPartitions).map { partitionId =>
      fileLayout.getLocalPartitionFile(partitionId)
    }

    // Open partition writers
    val writers = partitionFiles.map { file =>
      new BufferedOutputStream(new FileOutputStream(
        fileLayout.getLocalPartitionTempFile(extractPartitionId(file))
      ))
    }

    try {
      // K-way merge with partitioning
      val merger = new KWayMerger(sortedChunks)

      merger.mergeAll { record =>
        val partitionId = getPartition(record.key, boundaries)
        writers(partitionId).write(record.toBytes)
      }

      writers.foreach(_.close())

      // Atomic rename
      partitionFiles.zipWithIndex.foreach { case (file, partitionId) =>
        val tempFile = fileLayout.getLocalPartitionTempFile(partitionId)
        Files.move(tempFile.toPath, file.toPath, StandardCopyOption.ATOMIC_MOVE)
      }

      logger.info(s"Created ${partitionFiles.size} partition files")

      // Cleanup sorted chunks
      fileLayout.cleanupSortedChunks()

    } catch {
      case ex: Exception =>
        writers.foreach(w => try { w.close() } catch { case _: Exception => })
        (0 until numPartitions).foreach { partitionId =>
          FileOperations.safeDelete(fileLayout.getLocalPartitionTempFile(partitionId))
        }
        throw ex
    }

    partitionFiles
  }

  private def extractPartitionId(file: File): Int = {
    val pattern = "partition\\.(\\d+)".r
    pattern.findFirstMatchIn(file.getName).get.group(1).toInt
  }
}
```

### 4.4 Phase 4: Shuffle

```scala
class ShuffleManager {
  def performShuffle(shuffleMap: Map[Int, Int]): Unit = {
    logger.info("Starting shuffle phase")

    val localPartitions = fileLayout.listLocalPartitions
    val myWorkerIdNum = workerId.substring(1).toInt

    localPartitions.foreach { partitionFile =>
      val partitionId = extractPartitionId(partitionFile.getName)
      val targetWorkerId = shuffleMap(partitionId)

      if (targetWorkerId == myWorkerIdNum) {
        // Keep locally - move to received directory
        val destFile = fileLayout.getReceivedPartitionFile(partitionId)
        Files.move(partitionFile.toPath, destFile.toPath, StandardCopyOption.ATOMIC_MOVE)

        logger.info(s"Kept partition $partitionId locally")

      } else {
        // Send to another worker
        val targetWorker = workerLookup(s"W$targetWorkerId")
        sendPartitionFile(partitionFile, targetWorker)

        // Delete local copy after successful send
        FileOperations.safeDelete(partitionFile)
      }
    }

    // Cleanup local partitioning directory
    fileLayout.cleanupLocalPartitions()

    logger.info("Shuffle phase complete")
  }

  private def extractPartitionId(fileName: String): Int = {
    val pattern = "partition\\.(\\d+)".r
    pattern.findFirstMatchIn(fileName).get.group(1).toInt
  }
}
```

### 4.5 Phase 5: Merge

```scala
class MergeManager {
  def performMerge(shuffleMap: Map[Int, Int]): Unit = {
    logger.info("Starting merge phase")

    val myWorkerIdNum = workerId.substring(1).toInt
    val myPartitions = shuffleMap.filter(_._2 == myWorkerIdNum).keys.toSeq.sorted

    logger.info(s"Merging ${myPartitions.size} partitions: ${myPartitions.mkString(", ")}")

    myPartitions.foreach { partitionId =>
      mergePartition(partitionId)
    }

    // Cleanup received partitions
    fileLayout.cleanupReceivedPartitions()

    // Final disk usage report
    val usage = fileLayout.getDiskUsageReport
    logger.info(usage.toHumanReadable)

    logger.info("Merge phase complete")
  }

  private def mergePartition(partitionId: Int): Unit = {
    val receivedFiles = fileLayout.listReceivedPartitions(partitionId)

    if (receivedFiles.isEmpty) {
      logger.warn(s"No files found for partition $partitionId")
      return
    }

    logger.info(s"Merging partition $partitionId from ${receivedFiles.size} files")

    val outputFile = fileLayout.getOutputPartitionFile(partitionId)
    val tempOutputFile = fileLayout.getOutputPartitionTempFile(partitionId)

    try {
      val merger = new KWayMerger(receivedFiles)
      var recordCount = 0L

      FileOperations.atomicWriteStream(outputFile) { outputStream =>
        merger.mergeAll { record =>
          outputStream.write(record.toBytes)
          recordCount += 1

          if (recordCount % 1000000 == 0) {
            logger.debug(s"Merged $recordCount records for partition $partitionId")
          }
        }
      }

      logger.info(s"Completed merging partition $partitionId: $recordCount records, " +
        s"${outputFile.length() / (1024 * 1024)} MB")

      // Validate output file
      val validation = FileOperations.validatePartitionFile(outputFile)
      if (!validation.valid) {
        throw new IOException(s"Output validation failed: ${validation.error.get}")
      }

      // Cleanup source files
      FileOperations.safeDeleteAll(receivedFiles)

    } catch {
      case ex: Exception =>
        logger.error(s"Error merging partition $partitionId: $ex")
        FileOperations.safeDelete(tempOutputFile)
        throw ex
    }
  }
}
```

---

## 5. 디스크 공간 관리

### 5.1 사전 검사

```scala
class DiskSpaceManager(fileLayout: FileLayout) {
  /**
   * Pre-flight disk space check before starting job
   */
  def preflightCheck(): Either[String, Unit] = {
    logger.info("Performing pre-flight disk space check")

    val inputFiles = fileLayout.getInputFiles
    if (inputFiles.isEmpty) {
      return Left("No input files found")
    }

    val totalInputSize = inputFiles.map(_.length()).sum
    val requiredSpace = fileLayout.estimateRequiredSpace(totalInputSize)

    logger.info(s"Input size: ${totalInputSize / (1024 * 1024)} MB")
    logger.info(s"Estimated required space: ${requiredSpace / (1024 * 1024)} MB")

    if (!fileLayout.checkDiskSpace(requiredSpace)) {
      val tempFree = fileLayout.getTempBasePath.toFile.getFreeSpace
      val outputFree = fileLayout.getOutputPath.toFile.getFreeSpace

      Left(s"Insufficient disk space:\n" +
        s"  Required: ${requiredSpace / (1024 * 1024)} MB\n" +
        s"  Temp available: ${tempFree / (1024 * 1024)} MB\n" +
        s"  Output available: ${outputFree / (1024 * 1024)} MB")
    } else {
      Right(())
    }
  }

  /**
   * Periodic disk space monitoring during execution
   */
  def startMonitoring(): Unit = {
    val monitorExecutor = new ScheduledThreadPoolExecutor(1)

    monitorExecutor.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = checkDiskSpace()
      },
      0, 60, TimeUnit.SECONDS  // Check every 60 seconds
    )
  }

  private def checkDiskSpace(): Unit = {
    val tempFree = fileLayout.getTempBasePath.toFile.getFreeSpace
    val outputFree = fileLayout.getOutputPath.toFile.getFreeSpace

    val usage = fileLayout.getDiskUsageReport

    logger.debug(s"Disk space: temp ${tempFree / (1024 * 1024)} MB free, " +
      s"output ${outputFree / (1024 * 1024)} MB free, " +
      s"used ${usage.totalBytes / (1024 * 1024)} MB")

    // Alert if low space
    val minRequiredSpace = 1024 * 1024 * 1024  // 1 GB minimum

    if (tempFree < minRequiredSpace) {
      logger.warn(s"Low disk space in temp directory: ${tempFree / (1024 * 1024)} MB")
    }

    if (outputFree < minRequiredSpace) {
      logger.warn(s"Low disk space in output directory: ${outputFree / (1024 * 1024)} MB")
    }
  }
}
```

### 5.2 동적 Cleanup

```scala
class AdaptiveCleanupManager(fileLayout: FileLayout) {
  /**
   * Cleanup old temporary files to free space
   */
  def cleanupToFreeSpace(requiredBytes: Long): Boolean = {
    logger.info(s"Attempting to free ${requiredBytes / (1024 * 1024)} MB")

    var freedBytes = 0L

    // Step 1: Clean sampling files (no longer needed after sorting starts)
    if (stateMachine.getState.isAfter(Sorting)) {
      fileLayout.cleanupSamplingFiles()
      freedBytes += calculateDirectorySize(fileLayout.getSamplingFile.getParentFile)
      logger.info(s"Freed ${freedBytes / (1024 * 1024)} MB from sampling files")
    }

    if (freedBytes >= requiredBytes) {
      return true
    }

    // Step 2: Clean sorted chunks (no longer needed after partitioning complete)
    if (stateMachine.getState.isAfter(WaitingForShuffleSignal)) {
      fileLayout.cleanupSortedChunks()
      val chunkSize = fileLayout.listSortedChunks.map(_.length()).sum
      freedBytes += chunkSize
      logger.info(s"Freed ${chunkSize / (1024 * 1024)} MB from sorted chunks")
    }

    if (freedBytes >= requiredBytes) {
      return true
    }

    // Step 3: Clean local partitions (no longer needed after shuffle complete)
    if (stateMachine.getState.isAfter(WaitingForMergeSignal)) {
      fileLayout.cleanupLocalPartitions()
      val partitionSize = fileLayout.listLocalPartitions.map(_.length()).sum
      freedBytes += partitionSize
      logger.info(s"Freed ${partitionSize / (1024 * 1024)} MB from local partitions")
    }

    freedBytes >= requiredBytes
  }

  private def calculateDirectorySize(dir: File): Long = {
    if (!dir.exists()) return 0L

    dir.listFiles().filter(_.isFile).map(_.length()).sum
  }
}
```

---

## 6. 파일 I/O 성능 최적화

### 6.1 버퍼링 전략

```scala
object BufferedIO {
  // Default buffer sizes
  val SMALL_BUFFER_SIZE = 8 * 1024         // 8 KB - for metadata
  val MEDIUM_BUFFER_SIZE = 64 * 1024       // 64 KB - for record reading
  val LARGE_BUFFER_SIZE = 1024 * 1024      // 1 MB - for bulk transfers

  def createBufferedInputStream(file: File, bufferSize: Int = MEDIUM_BUFFER_SIZE): BufferedInputStream = {
    new BufferedInputStream(new FileInputStream(file), bufferSize)
  }

  def createBufferedOutputStream(file: File, bufferSize: Int = MEDIUM_BUFFER_SIZE): BufferedOutputStream = {
    new BufferedOutputStream(new FileOutputStream(file), bufferSize)
  }
}
```

### 6.2 메모리 매핑 (선택사항)

```scala
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MemoryMappedReader(file: File) {
  private val channel = new RandomAccessFile(file, "r").getChannel
  private val buffer: MappedByteBuffer = channel.map(
    FileChannel.MapMode.READ_ONLY,
    0,
    channel.size()
  )

  def readRecord(offset: Long): Option[Record] = {
    if (offset + 100 > channel.size()) {
      return None
    }

    buffer.position(offset.toInt)

    val key = new Array[Byte](10)
    val value = new Array[Byte](90)

    buffer.get(key)
    buffer.get(value)

    Some(Record(key, value))
  }

  def close(): Unit = {
    channel.close()
  }
}
```

**사용 시기**:
- **사용**: 작은 파일 (<2GB), 랜덤 액세스가 많을 때
- **미사용**: 큰 파일 (>2GB), 순차 읽기/쓰기만 할 때

### 6.3 Direct I/O (선택사항)

Direct I/O는 OS page cache를 bypass하여 성능 향상 가능하지만, Java에서는 기본 지원하지 않음.

**대안**: JNA 또는 JNI를 통한 native call (복잡도 증가)

**현재 설계**: BufferedInputStream/OutputStream 사용 (충분히 빠름)

---

## 7. 파일 무결성 보장

### 7.1 Atomic Rename 패턴

```scala
/**
 * 모든 파일 쓰기에 사용되는 표준 패턴
 */
def atomicWritePattern(targetFile: File): Unit = {
  val tempFile = new File(targetFile.getParent, s"${targetFile.getName}.tmp")

  try {
    // 1. Write to temp file
    writeToFile(tempFile)

    // 2. Fsync (optional, for durability)
    val channel = new FileOutputStream(tempFile, true).getChannel
    channel.force(true)  // Force metadata + data to disk
    channel.close()

    // 3. Atomic rename
    Files.move(tempFile.toPath, targetFile.toPath, StandardCopyOption.ATOMIC_MOVE)

    logger.debug(s"Atomically wrote ${targetFile.getName}")

  } catch {
    case ex: Exception =>
      if (tempFile.exists()) {
        tempFile.delete()
      }
      throw ex
  }
}
```

**보장**:
- Crash 전: 임시 파일만 존재 → 삭제 후 재시작
- Crash 후: 완전한 파일만 존재 → 무결성 보장

### 7.2 파일 잠금 (선택사항)

```scala
import java.nio.channels.{FileChannel, FileLock}

class LockedFileWriter(file: File) {
  private val channel = new RandomAccessFile(file, "rw").getChannel
  private val lock: FileLock = channel.lock()  // Exclusive lock

  def write(data: Array[Byte]): Unit = {
    val buffer = ByteBuffer.wrap(data)
    channel.write(buffer)
  }

  def close(): Unit = {
    lock.release()
    channel.close()
  }
}
```

**사용 시기**: 여러 프로세스가 동일 파일에 접근할 수 있을 때 (현재 설계에서는 불필요)

---

## 8. 파일 관리 체크리스트

### 8.1 초기화 단계
- [ ] FileLayout 객체 생성
- [ ] 모든 디렉토리 생성 (temp, output, received, logs)
- [ ] 입력 파일 존재 확인
- [ ] 디스크 공간 사전 검사
- [ ] 이전 실행의 임시 파일 cleanup

### 8.2 실행 중
- [ ] 모든 파일 쓰기는 atomic pattern 사용
- [ ] 각 phase 완료 후 불필요한 파일 cleanup
- [ ] 디스크 사용량 주기적 모니터링
- [ ] 디스크 부족 시 adaptive cleanup 시도
- [ ] 모든 파일 operations 로깅

### 8.3 Phase별 Cleanup
- [ ] Sampling 완료 후: `cleanupSamplingFiles()`
- [ ] Partitioning 완료 후: `cleanupSortedChunks()`
- [ ] Shuffle 완료 후: `cleanupLocalPartitions()`
- [ ] Merge 완료 후: `cleanupReceivedPartitions()`
- [ ] Job 완료 후: `cleanupTemporaryFiles()`

### 8.4 에러 처리
- [ ] IOException 발생 시 임시 파일 삭제
- [ ] Validation 실패 시 관련 파일 삭제
- [ ] Worker 실패 시 state-based cleanup
- [ ] Shutdown hook에서 cleanup 수행

### 8.5 최종 검증
- [ ] 모든 output partition 파일 존재 확인
- [ ] 파일 크기 검증 (레코드 크기의 배수)
- [ ] 파일 readable 여부 확인
- [ ] Checksum 검증 (optional)
- [ ] 임시 파일 모두 삭제 확인

---

## 9. 성능 튜닝 가이드

### 9.1 버퍼 크기

| Operations | 권장 버퍼 크기 | 이유 |
|-----------|--------------|------|
| 메타데이터 읽기/쓰기 | 8 KB | 작은 데이터, 빠른 응답 필요 |
| 레코드 읽기/쓰기 | 64 KB | 밸런스 (메모리 vs 성능) |
| Bulk 전송 (shuffle) | 1 MB - 4 MB | 네트워크 효율성 |
| External sort chunks | 256 MB - 512 MB | 메모리 허용 범위 내 최대화 |

### 9.2 디스크 I/O 최적화

**순차 I/O 선호**:
```scala
// Good: Sequential write
records.foreach { record =>
  outputStream.write(record.toBytes)
}

// Bad: Random access
records.foreach { record =>
  file.seek(randomOffset)
  file.write(record.toBytes)
}
```

**Batch 쓰기**:
```scala
// Good: Batch write
val buffer = new Array[Byte](batchSize * 100)
var offset = 0

records.take(batchSize).foreach { record =>
  System.arraycopy(record.toBytes, 0, buffer, offset, 100)
  offset += 100
}

outputStream.write(buffer, 0, offset)

// Bad: Individual writes
records.foreach { record =>
  outputStream.write(record.toBytes)  // Too many system calls
}
```

### 9.3 메모리 vs 디스크 트레이드오프

| 메모리 크기 | Chunk 크기 | K-way Merge K | 예상 성능 |
|-----------|-----------|--------------|----------|
| 512 MB | 100 MB | 5 | 느림, 많은 I/O |
| 1 GB | 200 MB | 5 | 보통 |
| 2 GB | 400 MB | 5 | 빠름 |
| 4 GB | 800 MB | 5 | 매우 빠름 |

**동적 조정**:
```scala
class AdaptiveChunkSizer {
  def calculateOptimalChunkSize(): Int = {
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory()
    val freeMemory = runtime.freeMemory()

    // Use 50% of max memory for chunk size
    val chunkSize = (maxMemory * 0.5).toInt

    logger.info(s"Calculated optimal chunk size: ${chunkSize / (1024 * 1024)} MB")

    chunkSize
  }
}
```

---

## 문서 완성도: 95%

**완료된 부분**:
- ✅ 완전한 디렉토리 구조 정의
- ✅ FileLayout 클래스 구현
- ✅ 파일 명명 규칙 문서화
- ✅ Atomic file operations
- ✅ Phase별 파일 관리 로직
- ✅ 디스크 공간 관리
- ✅ 파일 I/O 성능 최적화 가이드
- ✅ 파일 무결성 보장 메커니즘
- ✅ 구현 체크리스트

**추가 고려사항** (Nice-to-have):
- [ ] 파일 압축 (gzip) 지원
- [ ] Incremental checkpoint 파일
- [ ] 파일 시스템 모니터링 (inotify)
- [ ] S3/HDFS 등 distributed file system 지원

**전체 docs 폴더 완성**: 5/5 문서 완료 ✅
