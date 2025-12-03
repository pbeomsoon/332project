package distsort.core

import java.io.File
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Worker's file system layout manager
 * All file path access should go through this class
 *
 * Based on docs/5-file-management.md
 *
 * Directory structure:
 * ```
 * <output-dir>/
 *   ├── .temp/           (or custom temp dir)
 *   │   ├── sampling/
 *   │   ├── sorting/
 *   │   └── partitioning/
 *   ├── .received/       (shuffle received files)
 *   └── partition.*      (final output files)
 * ```
 *
 * @param inputDirs List of input directories (READ-ONLY)
 * @param outputDir Output directory for final results
 * @param tempDir Optional custom temp directory (default: outputDir/.temp)
 */
class FileLayout(
  val inputDirs: Seq[String],
  val outputDir: String,
  val tempDir: Option[String] = None
) {

  // Base directories
  private val outputPath: Path = Paths.get(outputDir).toAbsolutePath
  private val tempBasePath: Path = tempDir match {
    case Some(dir) => Paths.get(dir).toAbsolutePath
    case None => outputPath.resolve(".temp")
  }

  // Subdirectories
  private val samplingDir: Path = tempBasePath.resolve("sampling")
  private val sortingDir: Path = tempBasePath.resolve("sorting")
  private val partitioningDir: Path = tempBasePath.resolve("partitioning")
  private val receivedDir: Path = outputPath.resolve(".received")

  /**
   * Initialize directory structure
   * Creates all necessary directories if they don't exist
   */
  def initialize(): Unit = {
    createDirectoryIfNotExists(outputPath)
    createDirectoryIfNotExists(tempBasePath)
    createDirectoryIfNotExists(samplingDir)
    createDirectoryIfNotExists(sortingDir)
    createDirectoryIfNotExists(partitioningDir)
    createDirectoryIfNotExists(receivedDir)
  }

  private def createDirectoryIfNotExists(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    }
  }

  // ============================================================
  // Input files (READ-ONLY - never modify!)
  // ============================================================

  /**
   * Get all input files from all input directories
   * Recursively lists all files (not directories)
   *
   * @return Sequence of input files
   */
  def getInputFiles: Seq[File] = {
    inputDirs.flatMap { dir =>
      val dirFile = new File(dir)
      if (!dirFile.exists()) {
        Seq.empty
      } else if (!dirFile.isDirectory) {
        Seq.empty
      } else {
        listFilesRecursively(dirFile)
      }
    }
  }

  private def listFilesRecursively(dir: File): Seq[File] = {
    val items = dir.listFiles()
    if (items == null) {
      return Seq.empty
    }

    val (files, subdirs) = items.partition(_.isFile)
    files.toSeq ++ subdirs.flatMap(listFilesRecursively)
  }

  // ============================================================
  // Sampling phase files
  // ============================================================

  def getSamplingFile: File = {
    samplingDir.resolve("sample_keys.bin").toFile
  }

  // ============================================================
  // Sorting phase files
  // ============================================================

  def getSortedChunkFile(chunkIndex: Int): File = {
    sortingDir.resolve(f"chunk_$chunkIndex%04d.sorted").toFile
  }

  def listSortedChunks: Seq[File] = {
    val chunkPattern = "chunk_\\d{4}\\.sorted".r
    val sortingDirFile = sortingDir.toFile
    if (!sortingDirFile.exists()) {
      Seq.empty
    } else {
      val files = sortingDirFile.listFiles()
      if (files == null) {
        Seq.empty
      } else {
        files
          .filter(f => chunkPattern.findFirstIn(f.getName).isDefined)
          .sortBy(_.getName)
          .toSeq
      }
    }
  }

  // ============================================================
  // Partitioning phase files
  // ============================================================

  def getLocalPartitionFile(partitionId: Int): File = {
    partitioningDir.resolve(s"partition.$partitionId").toFile
  }

  def getLocalPartitionTempFile(partitionId: Int): File = {
    partitioningDir.resolve(s"partition.$partitionId.tmp").toFile
  }

  def listLocalPartitions: Seq[File] = {
    val partitionPattern = "partition\\.\\d+".r
    val partitioningDirFile = partitioningDir.toFile
    if (!partitioningDirFile.exists()) {
      Seq.empty
    } else {
      val files = partitioningDirFile.listFiles()
      if (files == null) {
        Seq.empty
      } else {
        files
          .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
          .sortBy(f => extractPartitionId(f.getName))
          .toSeq
      }
    }
  }

  // ============================================================
  // Received partitions (shuffle phase)
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
    val receivedDirFile = receivedDir.toFile
    if (!receivedDirFile.exists()) {
      Seq.empty
    } else {
      val files = receivedDirFile.listFiles()
      if (files == null) {
        Seq.empty
      } else {
        files
          .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
          .sortBy(_.getName)
          .toSeq
      }
    }
  }

  // ============================================================
  // Temp files
  // ============================================================

  def getTempFile(name: String): File = {
    sortingDir.resolve(name).toFile
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
    val outputDirFile = outputPath.toFile
    if (!outputDirFile.exists()) {
      Seq.empty
    } else {
      val files = outputDirFile.listFiles()
      if (files == null) {
        Seq.empty
      } else {
        files
          .filter(f => partitionPattern.findFirstIn(f.getName).isDefined)
          .sortBy(f => extractPartitionId(f.getName))
          .toSeq
      }
    }
  }

  // ============================================================
  // Cleanup operations
  // ============================================================

  def cleanupSamplingFiles(): Unit = {
    deleteDirectory(samplingDir)
    createDirectoryIfNotExists(samplingDir)
  }

  def cleanupSortedChunks(): Unit = {
    deleteDirectory(sortingDir)
    createDirectoryIfNotExists(sortingDir)
  }

  def cleanupLocalPartitions(): Unit = {
    deleteDirectory(partitioningDir)
    createDirectoryIfNotExists(partitioningDir)
  }

  def cleanupReceivedPartitions(): Unit = {
    deleteDirectory(receivedDir)
    createDirectoryIfNotExists(receivedDir)
  }

  def cleanupTemporaryFiles(): Unit = {
    deleteDirectory(tempBasePath)
    initialize()  // Recreate directory structure
  }

  def cleanupAll(): Unit = {
    deleteDirectory(tempBasePath)
    deleteDirectory(receivedDir)

    // Delete output partition files (but keep outputDir itself)
    listOutputPartitions.foreach { file =>
      file.delete()
    }
  }

  private def deleteDirectory(path: Path): Unit = {
    if (Files.exists(path)) {
      Files.walk(path)
        .iterator()
        .asScala
        .toSeq
        .reverse  // Delete files before directories
        .foreach { p =>
          Try(Files.delete(p))
        }
    }
  }

  // ============================================================
  // Disk space management
  // ============================================================

  def checkDiskSpace(requiredBytes: Long): Boolean = {
    val tempSpace = tempBasePath.toFile.getFreeSpace
    val outputSpace = outputPath.toFile.getFreeSpace

    tempSpace >= requiredBytes && outputSpace >= requiredBytes
  }

  def estimateRequiredSpace(totalInputSize: Long): Long = {
    // Conservative estimate from docs/5-file-management.md:
    // - Sorted chunks: ~1.2x input size
    // - Local partitions: ~1.0x input size
    // - Received partitions: ~1.0x input size (worst case)
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

    Try {
      Files.walk(path)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p))
        .map(p => Files.size(p))
        .sum
    }.getOrElse(0L)
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

  // Public accessors for paths
  def getTempBasePath: Path = tempBasePath
  def getOutputPath: Path = outputPath
  def getReceivedPath: Path = receivedDir
}

/**
 * Disk usage report
 */
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