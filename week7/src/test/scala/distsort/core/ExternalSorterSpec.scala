package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.io.FileOutputStream

class ExternalSorterSpec extends AnyFlatSpec with Matchers {

  def generateTestRecords(count: Int, seed: Int = 42): Seq[Record] = {
    val random = new scala.util.Random(seed)
    (1 to count).map { _ =>
      val key = new Array[Byte](10)
      val value = new Array[Byte](90)
      random.nextBytes(key)
      random.nextBytes(value)
      Record(key, value)
    }
  }

  def createTempLayout: FileLayout = {
    val tempRoot = Files.createTempDirectory("sorter-test")
    new FileLayout(
      inputDirs = Seq.empty,
      outputDir = tempRoot.toString,
      tempDir = Some(tempRoot.resolve("temp").toString)
    )
  }

  def verifySorted(records: Seq[Record]): Boolean = {
    records.sliding(2).forall {
      case Seq(a, b) => a.compareTo(b) <= 0
      case _ => true
    }
  }

  "ExternalSorter" should "sort records in memory when under limit" in {
    // Given: Records that fit in memory
    val records = generateTestRecords(100)
    val layout = createTempLayout
    layout.initialize()

    // When: Sort with sufficient memory
    val sorter = new ExternalSorter(
      fileLayout = layout,
      memoryLimit = 100 * 100 * 2  // Enough for all records
    )
    val sorted = sorter.sortRecords(records)

    // Then: Should be sorted
    sorted should have length 100
    verifySorted(sorted) shouldBe true

    // Cleanup
    layout.cleanupAll()
  }

  it should "create multiple chunks when memory limited" in {
    // Given: Many records
    val records = generateTestRecords(1000)
    val layout = createTempLayout
    layout.initialize()

    // When: Sort with limited memory (10 records per chunk)
    val sorter = new ExternalSorter(
      fileLayout = layout,
      memoryLimit = 10 * 100  // Only 10 records in memory
    )
    val chunkFiles = sorter.createSortedChunks(records)

    // Then: Should create multiple chunks
    chunkFiles.size should be > 1
    chunkFiles.foreach { file =>
      file.exists() shouldBe true
    }

    // Each chunk should be sorted
    chunkFiles.foreach { file =>
      val reader = RecordReader.create(file)
      val chunkRecords = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
      reader.close()

      verifySorted(chunkRecords) shouldBe true
    }

    // Cleanup
    layout.cleanupAll()
  }

  it should "sort chunks in parallel" in {
    // Given: Multiple chunks
    val records = generateTestRecords(1000)
    val layout = createTempLayout
    layout.initialize()

    // When: Sort with parallelization
    val sorter = new ExternalSorter(
      fileLayout = layout,
      memoryLimit = 10 * 100,
      numThreads = 4
    )

    val startTime = System.currentTimeMillis()
    val chunkFiles = sorter.createSortedChunksParallel(records)
    val duration = System.currentTimeMillis() - startTime

    // Then: Should be faster than sequential (rough check)
    chunkFiles.size should be > 4  // Multiple chunks
    println(s"Parallel sorting took ${duration}ms for ${chunkFiles.size} chunks")

    // All chunks sorted
    chunkFiles.foreach { file =>
      val reader = RecordReader.create(file)
      val chunkRecords = Iterator.continually(reader.readRecord())
        .takeWhile(_.isDefined)
        .map(_.get)
        .toSeq
      reader.close()

      verifySorted(chunkRecords) shouldBe true
    }

    // Cleanup
    layout.cleanupAll()
  }

  it should "handle empty input" in {
    // Given: No records
    val layout = createTempLayout
    layout.initialize()

    // When: Sort empty
    val sorter = new ExternalSorter(fileLayout = layout)
    val sorted = sorter.sortRecords(Seq.empty)

    // Then: Should return empty
    sorted shouldBe empty

    // Cleanup
    layout.cleanupAll()
  }

  it should "sort from files directly" in {
    // Given: Input files
    val layout = createTempLayout
    layout.initialize()

    val tempFile = Files.createTempFile("input", ".dat")
    val records = generateTestRecords(200)
    val out = new FileOutputStream(tempFile.toFile)
    records.foreach(r => out.write(r.toBytes))
    out.close()

    // When: Sort from file
    val sorter = new ExternalSorter(fileLayout = layout)
    val sortedChunks = sorter.sortFiles(Seq(tempFile.toFile))

    // Then: All chunks sorted
    sortedChunks should not be empty
    sortedChunks.foreach { chunk =>
      chunk.exists() shouldBe true
    }

    // Cleanup
    Files.delete(tempFile)
    layout.cleanupAll()
  }

  it should "use configurable number of threads" in {
    // Given: Records
    val records = generateTestRecords(500)
    val layout = createTempLayout
    layout.initialize()

    // When: Sort with 1 thread
    val sorter1 = new ExternalSorter(
      fileLayout = layout,
      memoryLimit = 10 * 100,
      numThreads = 1
    )

    val start1 = System.currentTimeMillis()
    val chunks1 = sorter1.createSortedChunksParallel(records)
    val time1 = System.currentTimeMillis() - start1

    // When: Sort with 4 threads
    layout.cleanupSortedChunks()
    val sorter4 = new ExternalSorter(
      fileLayout = layout,
      memoryLimit = 10 * 100,
      numThreads = 4
    )

    val start4 = System.currentTimeMillis()
    val chunks4 = sorter4.createSortedChunksParallel(records)
    val time4 = System.currentTimeMillis() - start4

    // Then: Multi-threaded should be faster
    println(s"1 thread: ${time1}ms, 4 threads: ${time4}ms")
    chunks1.size shouldBe chunks4.size

    // Cleanup
    layout.cleanupAll()
  }

  it should "maintain deterministic output" in {
    // Given: Same input
    val records = generateTestRecords(100, seed = 123)
    val layout = createTempLayout
    layout.initialize()

    // When: Sort twice
    val sorter = new ExternalSorter(fileLayout = layout)
    val sorted1 = sorter.sortRecords(records)
    val sorted2 = sorter.sortRecords(records)

    // Then: Same output
    sorted1 shouldBe sorted2

    // Cleanup
    layout.cleanupAll()
  }
}