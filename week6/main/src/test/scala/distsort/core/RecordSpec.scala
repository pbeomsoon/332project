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
      value = Array.fill[Byte](90)(100.toByte)
    )
    val rec2 = Record(
      key = Array.fill[Byte](10)(5),
      value = Array.fill[Byte](90)(200.toByte) // Different value
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
