package distsort.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.io.{File, FileOutputStream}

class SamplerSpec extends AnyFlatSpec with Matchers {

  def generateTestRecords(count: Int, seed: Int = 42): Seq[Record] = {
    val random = new scala.util.Random(seed)
    (1 to count).map { i =>
      val key = new Array[Byte](10)
      val value = new Array[Byte](90)
      random.nextBytes(key)
      random.nextBytes(value)
      Record(key, value)
    }
  }

  def createBinaryFile(records: Seq[Record]): File = {
    val file = Files.createTempFile("sampler-test", ".dat").toFile
    val out = new FileOutputStream(file)
    try {
      records.foreach(r => out.write(r.toBytes))
    } finally {
      out.close()
    }
    file
  }

  "Sampler" should "extract deterministic samples with fixed seed" in {
    // Given: 1000 records
    val records = generateTestRecords(1000, seed = 42)
    val file = createBinaryFile(records)

    // When: Sample with 10% rate and fixed seed (DETERMINISTIC)
    val sampler = new Sampler(sampleRate = 0.1, seed = 42)
    val samples = sampler.extractSamples(file)

    // Then: Should get approximately 10% (probabilistic sampling has variance)
    samples.size should be >= 70
    samples.size should be <= 130
    // Expected: ~100 samples with 10% rate, allowing for probabilistic variance

    // ✅ ENHANCED: Verify determinism - run again with same seed
    val sampler2 = new Sampler(sampleRate = 0.1, seed = 42)
    val samples2 = sampler2.extractSamples(file)

    samples.size shouldBe samples2.size
    samples.zip(samples2).foreach { case (s1, s2) =>
      java.util.Arrays.equals(s1.key, s2.key) shouldBe true
      java.util.Arrays.equals(s1.value, s2.value) shouldBe true
    }

    file.delete()
  }

  it should "use deterministic sampling with seed" in {
    // Given: Same file
    val records = generateTestRecords(1000)
    val file = createBinaryFile(records)

    // When: Sample twice with same seed
    val sampler1 = new Sampler(sampleRate = 0.1, seed = 12345)
    val sampler2 = new Sampler(sampleRate = 0.1, seed = 12345)

    val samples1 = sampler1.extractSamples(file)
    val samples2 = sampler2.extractSamples(file)

    // Then: Should get identical samples
    samples1 shouldBe samples2

    file.delete()
  }

  it should "produce different samples with different seeds" in {
    // Given: Same file
    val records = generateTestRecords(1000)
    val file = createBinaryFile(records)

    // When: Sample with different seeds
    val sampler1 = new Sampler(sampleRate = 0.1, seed = 123)
    val sampler2 = new Sampler(sampleRate = 0.1, seed = 456)

    val samples1 = sampler1.extractSamples(file)
    val samples2 = sampler2.extractSamples(file)

    // Then: Should get different samples
    samples1 should not be samples2

    file.delete()
  }

  it should "handle empty file" in {
    // Given: Empty file
    val file = Files.createTempFile("empty", ".dat").toFile

    // When: Sample
    val sampler = new Sampler(sampleRate = 0.1)
    val samples = sampler.extractSamples(file)

    // Then: Should return empty
    samples shouldBe empty

    file.delete()
  }

  it should "handle very small files" in {
    // Given: File with only 5 records
    val records = generateTestRecords(5)
    val file = createBinaryFile(records)

    // When: Sample with 10% rate
    val sampler = new Sampler(sampleRate = 0.1)
    val samples = sampler.extractSamples(file)

    // Then: Should get at least 1 sample
    samples should not be empty

    file.delete()
  }

  it should "sample from multiple files deterministically" in {
    // Given: Multiple files with more records
    val files = (1 to 3).map { i =>
      val records = generateTestRecords(1000, seed = i)
      createBinaryFile(records)
    }

    // When: Sample all files with fixed seed for determinism
    val sampler = new Sampler(sampleRate = 0.1, seed = 42)
    val samples = sampler.extractSamplesFromFiles(files)

    // Then: Should get approximately 10% (probabilistic sampling has variance)
    samples.size should be >= 240
    samples.size should be <= 360
    // Expected: ~300 samples with 10% rate, allowing for probabilistic variance

    // ✅ ENHANCED: Verify determinism
    val sampler2 = new Sampler(sampleRate = 0.1, seed = 42)
    val samples2 = sampler2.extractSamplesFromFiles(files)

    samples.size shouldBe samples2.size
    samples.zip(samples2).foreach { case (s1, s2) =>
      java.util.Arrays.equals(s1.key, s2.key) shouldBe true
    }

    files.foreach(_.delete())
  }

  it should "maintain deterministic sampling rate across different file sizes" in {
    // Given: Files with different sizes
    val smallFile = createBinaryFile(generateTestRecords(50, seed = 1))
    val mediumFile = createBinaryFile(generateTestRecords(500, seed = 2))
    val largeFile = createBinaryFile(generateTestRecords(5000, seed = 3))

    // When: Sample each with fixed seed (DETERMINISTIC)
    val sampler = new Sampler(sampleRate = 0.1, seed = 42)
    val smallSamples = sampler.extractSamples(smallFile)
    val mediumSamples = sampler.extractSamples(mediumFile)
    val largeSamples = sampler.extractSamples(largeFile)

    // Then: Approximately 10% from each (probabilistic sampling has variance)
    // Small files have larger relative variance
    smallSamples.size should be >= 1
    smallSamples.size should be <= 12      // Expected: ~5 samples (wide range for small N)
    mediumSamples.size should be >= 30
    mediumSamples.size should be <= 70     // Expected: ~50 samples
    largeSamples.size should be >= 450
    largeSamples.size should be <= 550     // Expected: ~500 samples

    // ✅ ENHANCED: Verify determinism for each file
    val sampler2 = new Sampler(sampleRate = 0.1, seed = 42)
    sampler2.extractSamples(smallFile).size shouldBe smallSamples.size
    sampler2.extractSamples(mediumFile).size shouldBe mediumSamples.size
    sampler2.extractSamples(largeFile).size shouldBe largeSamples.size

    smallFile.delete()
    mediumFile.delete()
    largeFile.delete()
  }

  it should "auto-detect file format" in {
    // Given: Both binary and ASCII files
    val binaryRecords = generateTestRecords(100)
    val binaryFile = createBinaryFile(binaryRecords)

    // Create ASCII file
    val asciiFile = Files.createTempFile("ascii", ".txt").toFile
    val writer = new java.io.PrintWriter(asciiFile)
    try {
      binaryRecords.foreach { r =>
        val keyHex = r.key.map("%02X".format(_)).mkString
        val valueHex = r.value.map("%02X".format(_)).mkString
        writer.println(s"$keyHex $valueHex")
      }
    } finally {
      writer.close()
    }

    // When: Sample both with auto-detection
    val sampler = new Sampler(sampleRate = 0.1)
    val binarySamples = sampler.extractSamples(binaryFile)
    val asciiSamples = sampler.extractSamples(asciiFile)

    // Then: Both should work
    binarySamples should not be empty
    asciiSamples should not be empty

    binaryFile.delete()
    asciiFile.delete()
  }

  it should "extract only keys when onlyKeys is true" in {
    // Given: File with records
    val records = generateTestRecords(100)
    val file = createBinaryFile(records)

    // When: Sample only keys
    val sampler = new Sampler(sampleRate = 0.1)
    val keys = sampler.extractSampleKeys(file)

    // Then: Should get only keys (10 bytes each)
    keys should not be empty
    keys.foreach { key =>
      key.length shouldBe 10
    }

    file.delete()
  }
}