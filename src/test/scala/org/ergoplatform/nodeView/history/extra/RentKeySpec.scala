package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.nodeView.history.extra.ExtraIndexer.{RentKeyLength, RentKeyPrefix, isRentKey, rentKey}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.db.ByteArrayUtils

class RentKeySpec extends AnyFlatSpec with Matchers {

  "rentKey" should "always be 13 bytes with the 0x72 prefix" in {
    val k = rentKey(1234, 5678L)
    k.length shouldBe RentKeyLength
    k(0) shouldBe RentKeyPrefix
  }

  it should "order lexicographically by (creationHeight, globalIndex)" in {
    // The (255,7) -> (256,0) adjacency is the one that fails under
    // little-endian encoding. Do not remove it.
    val pairs = Seq(
      (0, 0L), (0, 1L), (0, Long.MaxValue),
      (1, 0L), (255, 7L), (256, 0L),
      (65535, 3L), (65536, 0L),
      (1051200, 42L), (Int.MaxValue, Long.MaxValue)
    )
    val keys = pairs.map { case (h, g) => rentKey(h, g) }
    keys.zip(keys.tail).foreach { case (a, b) =>
      withClue(s"${a.toSeq} should sort before ${b.toSeq}: ") {
        ByteArrayUtils.compare(a, b) should be < 0
      }
    }
  }

  it should "reject negative components" in {
    an[IllegalArgumentException] should be thrownBy rentKey(-1, 0L)
    an[IllegalArgumentException] should be thrownBy rentKey(Int.MinValue, 0L)
    an[IllegalArgumentException] should be thrownBy rentKey(0, -1L)
  }

  "isRentKey" should "accept only 13-byte 0x72-prefixed keys" in {
    isRentKey(rentKey(10, 20L)) shouldBe true
    val hashLike = Array.fill[Byte](32)(0)
    hashLike(0) = RentKeyPrefix
    isRentKey(hashLike) shouldBe false          // 32-byte hash starting 0x72
    val wrongPrefix = rentKey(10, 20L).clone()
    wrongPrefix(0) = 0x71.toByte
    isRentKey(wrongPrefix) shouldBe false
    isRentKey(Array.emptyByteArray) shouldBe false
  }
}
