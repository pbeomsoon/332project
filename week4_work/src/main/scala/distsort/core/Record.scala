package distsort.core

import java.util.Arrays

/**
 * 100-byte record: 10-byte key + 90-byte value
 * Sorting is based on key only (unsigned byte comparison)
 *
 * @param key 10-byte key (used for sorting)
 * @param value 90-byte value (not used in sorting)
 */
case class Record(key: Array[Byte], value: Array[Byte]) extends Ordered[Record] {
  require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
  require(value.length == 90, s"Value must be 90 bytes, got ${value.length}")

  /**
   * Compare records by key only (unsigned comparison)
   * Value is ignored during sorting
   *
   * This is critical for distributed sorting:
   * - All records with keys in range [bound_i, bound_i+1) go to partition i
   * - Value is just payload that follows the key
   *
   * Note: Manual implementation for Java 8 compatibility
   * (Arrays.compareUnsigned only available in Java 9+)
   */
  override def compare(that: Record): Int = {
    // Compare byte-by-byte as unsigned
    var i = 0
    while (i < 10) {
      val byte1 = this.key(i) & 0xFF  // Convert to unsigned int
      val byte2 = that.key(i) & 0xFF  // Convert to unsigned int
      if (byte1 != byte2) {
        return byte1 - byte2
      }
      i += 1
    }
    0  // Keys are equal
  }

  /**
   * Serialize record to 100 bytes
   * Format: [10 bytes key][90 bytes value]
   */
  def toBytes: Array[Byte] = key ++ value

  /**
   * Custom equals for array comparison
   */
  override def equals(obj: Any): Boolean = obj match {
    case that: Record =>
      Arrays.equals(this.key, that.key) &&
      Arrays.equals(this.value, that.value)
    case _ => false
  }

  /**
   * Custom hashCode for array hashing
   */
  override def hashCode(): Int = {
    31 * Arrays.hashCode(key) + Arrays.hashCode(value)
  }

  /**
   * String representation for debugging
   */
  override def toString: String = {
    s"Record(key=${key.take(10).map("%02X".format(_)).mkString}, value=...)"
  }
}

object Record {
  /**
   * Deserialize record from 100 bytes
   *
   * @param bytes 100-byte array
   * @return Record instance
   * @throws IllegalArgumentException if bytes.length != 100
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
   * Value will be filled with zeros
   *
   * @param key 10-byte key
   * @return Record with given key and zero-filled value
   */
  def fromKey(key: Array[Byte]): Record = {
    require(key.length == 10, s"Key must be 10 bytes, got ${key.length}")
    Record(key, Array.fill[Byte](90)(0))
  }

  /**
   * Implicit ordering for collections
   */
  implicit val recordOrdering: Ordering[Record] = new Ordering[Record] {
    override def compare(x: Record, y: Record): Int = x.compare(y)
  }
}
