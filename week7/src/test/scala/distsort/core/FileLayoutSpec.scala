package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path, Paths}
import java.io.File
import scala.util.Try

class FileLayoutSpec extends AnyFlatSpec with Matchers {

  def withTempDir[T](test: Path => T): T = {
    val tempDir = Files.createTempDirectory("test-layout")
    try {
      test(tempDir)
    } finally {
      // Cleanup
      deleteDirectory(tempDir)
    }
  }

  def deleteDirectory(path: Path): Unit = {
    if (Files.exists(path)) {
      import scala.jdk.CollectionConverters._
      Files.walk(path)
        .iterator()
        .asScala
        .toSeq
        .reverse
        .foreach(p => Try(Files.delete(p)))
    }
  }

  def createTestFile(dir: Path, name: String): Path = {
    val file = dir.resolve(name)
    Files.write(file, "test content".getBytes)
    file
  }

  "FileLayout" should "initialize directory structure" in withTempDir { tempRoot =>
    // Given
    val inputDir = tempRoot.resolve("input")
    val outputDir = tempRoot.resolve("output")
    val tempDir = tempRoot.resolve("temp")

    Files.createDirectory(inputDir)

    // When
    val layout = new FileLayout(
      inputDirs = Seq(inputDir.toString),
      outputDir = outputDir.toString,
      tempDir = Some(tempDir.toString)
    )
    layout.initialize()

    // Then
    Files.exists(outputDir) shouldBe true
    Files.exists(tempDir) shouldBe true
    Files.exists(tempDir.resolve("sampling")) shouldBe true
    Files.exists(tempDir.resolve("sorting")) shouldBe true
    Files.exists(tempDir.resolve("partitioning")) shouldBe true
    Files.exists(outputDir.resolve(".received")) shouldBe true
  }

  it should "use default temp directory if not specified" in withTempDir { tempRoot =>
    // Given
    val outputDir = tempRoot.resolve("output")

    // When
    val layout = new FileLayout(
      inputDirs = Seq(),
      outputDir = outputDir.toString
    )
    layout.initialize()

    // Then
    Files.exists(outputDir.resolve(".temp")) shouldBe true
    Files.exists(outputDir.resolve(".temp").resolve("sampling")) shouldBe true
  }

  it should "never modify input directories" in withTempDir { tempRoot =>
    // Given
    val inputDir = tempRoot.resolve("input")
    Files.createDirectory(inputDir)
    val testFile = createTestFile(inputDir, "data.txt")
    val originalModTime = Files.getLastModifiedTime(testFile)
    val originalContent = Files.readAllBytes(testFile)

    // When
    val layout = new FileLayout(
      inputDirs = Seq(inputDir.toString),
      outputDir = tempRoot.resolve("output").toString
    )
    layout.initialize()
    val inputFiles = layout.getInputFiles

    // Then
    inputFiles should have length 1
    inputFiles.head.getName shouldBe "data.txt"

    // Input should not be modified
    Files.getLastModifiedTime(testFile) shouldBe originalModTime
    Files.readAllBytes(testFile) shouldBe originalContent
  }

  it should "list all input files recursively" in withTempDir { tempRoot =>
    // Given
    val inputDir = tempRoot.resolve("input")
    Files.createDirectory(inputDir)
    val subDir = inputDir.resolve("subdir")
    Files.createDirectory(subDir)

    createTestFile(inputDir, "file1.dat")
    createTestFile(inputDir, "file2.dat")
    createTestFile(subDir, "file3.dat")

    // When
    val layout = new FileLayout(
      inputDirs = Seq(inputDir.toString),
      outputDir = tempRoot.resolve("output").toString
    )
    val files = layout.getInputFiles

    // Then
    files should have length 3
    files.map(_.getName).sorted shouldBe Seq("file1.dat", "file2.dat", "file3.dat")
  }

  it should "handle multiple input directories" in withTempDir { tempRoot =>
    // Given
    val input1 = tempRoot.resolve("input1")
    val input2 = tempRoot.resolve("input2")
    Files.createDirectory(input1)
    Files.createDirectory(input2)

    createTestFile(input1, "file1.dat")
    createTestFile(input2, "file2.dat")

    // When
    val layout = new FileLayout(
      inputDirs = Seq(input1.toString, input2.toString),
      outputDir = tempRoot.resolve("output").toString
    )
    val files = layout.getInputFiles

    // Then
    files should have length 2
    files.map(_.getName).sorted shouldBe Seq("file1.dat", "file2.dat")
  }

  it should "generate correct file paths" in withTempDir { tempRoot =>
    // Given/When
    val layout = new FileLayout(
      inputDirs = Seq(),
      outputDir = tempRoot.resolve("output").toString,
      tempDir = Some(tempRoot.resolve("temp").toString)
    )
    layout.initialize()

    // Then
    layout.getSamplingFile.getName shouldBe "sample_keys.bin"
    layout.getSortedChunkFile(0).getName shouldBe "chunk_0000.sorted"
    layout.getSortedChunkFile(42).getName shouldBe "chunk_0042.sorted"
    layout.getLocalPartitionFile(5).getName shouldBe "partition.5"
    layout.getOutputPartitionFile(10).getName shouldBe "partition.10"
    layout.getReceivedPartitionFile(3, Some("W2")).getName shouldBe "partition.3.from_W2"
  }

  it should "cleanup temporary files completely" in withTempDir { tempRoot =>
    // Given
    val layout = new FileLayout(
      inputDirs = Seq(),
      outputDir = tempRoot.resolve("output").toString,
      tempDir = Some(tempRoot.resolve("temp").toString)
    )
    layout.initialize()

    // Create some temp files
    val samplingFile = layout.getSamplingFile
    val sortedChunk = layout.getSortedChunkFile(0)
    val partition = layout.getLocalPartitionFile(1)

    Files.createFile(samplingFile.toPath)
    Files.createFile(sortedChunk.toPath)
    Files.createFile(partition.toPath)

    // When
    layout.cleanupTemporaryFiles()

    // Then
    Files.exists(samplingFile.toPath) shouldBe false
    Files.exists(sortedChunk.toPath) shouldBe false
    Files.exists(partition.toPath) shouldBe false

    // But temp directory should be recreated
    Files.exists(layout.getTempBasePath) shouldBe true
    Files.exists(layout.getTempBasePath.resolve("sampling")) shouldBe true
  }

  it should "list sorted chunks correctly" in withTempDir { tempRoot =>
    // Given
    val layout = new FileLayout(
      inputDirs = Seq(),
      outputDir = tempRoot.resolve("output").toString
    )
    layout.initialize()

    // Create chunk files
    Files.createFile(layout.getSortedChunkFile(0).toPath)
    Files.createFile(layout.getSortedChunkFile(1).toPath)
    Files.createFile(layout.getSortedChunkFile(10).toPath)

    // When
    val chunks = layout.listSortedChunks

    // Then
    chunks should have length 3
    chunks.map(_.getName) shouldBe Seq("chunk_0000.sorted", "chunk_0001.sorted", "chunk_0010.sorted")
  }

  it should "check disk space" in withTempDir { tempRoot =>
    // Given/When
    val layout = new FileLayout(
      inputDirs = Seq(),
      outputDir = tempRoot.resolve("output").toString
    )
    layout.initialize()

    // Then
    layout.checkDiskSpace(1024 * 1024) shouldBe true  // 1MB should be available

    val estimatedSpace = layout.estimateRequiredSpace(100 * 1024 * 1024)  // 100MB input
    estimatedSpace should be > (400L * 1024 * 1024)  // Should estimate > 400MB
  }
}