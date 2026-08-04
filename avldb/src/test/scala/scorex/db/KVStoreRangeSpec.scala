package scorex.db

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.ByteBuffer

class KVStoreRangeSpec extends AnyFlatSpec with Matchers {

  private def key(prefix: Byte, n: Int): Array[Byte] =
    ByteBuffer.allocate(5).put(prefix).putInt(n).array

  private def withStore(f: LDBKVStore => Unit): Unit = {
    val dir = java.nio.file.Files.createTempDirectory("kvstore-range").toFile
    val store = new LDBKVStore(
      LDBFactory.factory.open(dir, new org.iq80.leveldb.Options().createIfMissing(true)))
    try f(store) finally store.close()
  }

  private val filter: Array[Byte] => Boolean = k => k.length == 5 && k(0) == 0x72.toByte

  /** 0..19 under prefix 0x72, plus a longer decoy that sorts INSIDE the range,
    * and neighbours under 0x71 / 0x73.
    */
  private def populate(store: LDBKVStore): Unit = {
    val entries = (0 until 20).map(n => key(0x72, n) -> Array(n.toByte))
    val decoyLong = ByteBuffer.allocate(9).put(0x72.toByte).putInt(5).putInt(0).array
    val all = entries ++ Seq(
      decoyLong -> Array(99.toByte),
      key(0x71, 3) -> Array(98.toByte),
      key(0x73, 3) -> Array(97.toByte)
    )
    store.update(all.map(_._1).toArray, all.map(_._2).toArray, Array.empty).get
  }

  /** Same as populate but WITHOUT the 0x73 neighbour, so `end` is the last key in
    * the whole store. Exercises the past-the-end cursor path that the 0x73 decoy
    * otherwise hides.
    */
  private def populateNoTail(store: LDBKVStore): Unit = {
    val entries = (0 until 20).map(n => key(0x72, n) -> Array(n.toByte))
    store.update(entries.map(_._1).toArray, entries.map(_._2).toArray, Array.empty).get
  }

  "getRangeWithFilter" should "return ascending filtered entries within inclusive bounds" in withStore { s =>
    populate(s)
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 100, reverse = false)(filter)
      .map(_._2.head.toInt) shouldBe (0 until 20)
  }

  it should "exclude keys failing the filter even when they sort in range" in withStore { s =>
    populate(s)
    val got = s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 100, reverse = false)(filter)
    got.length shouldBe 20
    got.map(_._2.head.toInt) should not contain 99
  }

  it should "apply offset and limit over filtered entries only" in withStore { s =>
    populate(s)
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 5, 3, reverse = false)(filter)
      .map(_._2.head.toInt) shouldBe Seq(5, 6, 7)
  }

  it should "return descending entries that are the exact reverse of ascending" in withStore { s =>
    populate(s)
    val asc = s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 100, reverse = false)(filter)
    val desc = s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 100, reverse = true)(filter)
    desc.map(_._2.head.toInt) shouldBe asc.map(_._2.head.toInt).reverse
  }

  it should "apply offset from the correct end when reversed" in withStore { s =>
    populate(s)
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 2, 3, reverse = true)(filter)
      .map(_._2.head.toInt) shouldBe Seq(17, 16, 15)
  }

  it should "work in both directions when end is the last key in the store" in withStore { s =>
    populateNoTail(s)
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 3, reverse = true)(filter)
      .map(_._2.head.toInt) shouldBe Seq(19, 18, 17)
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 3, reverse = false)(filter)
      .map(_._2.head.toInt) shouldBe Seq(0, 1, 2)
  }

  it should "respect inclusive bounds on a sub-range" in withStore { s =>
    populate(s)
    s.getRangeWithFilter(key(0x72,3), key(0x72,6), 0, 100, reverse = false)(filter)
      .map(_._2.head.toInt) shouldBe Seq(3, 4, 5, 6)
  }

  it should "return empty for an empty range, zero limit, or offset past the end" in withStore { s =>
    populate(s)
    s.getRangeWithFilter(key(0x72,50), key(0x72,60), 0, 100, reverse = false)(filter) shouldBe empty
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 0, reverse = false)(filter) shouldBe empty
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 999, 10, reverse = false)(filter) shouldBe empty
  }

  it should "return a truncated page, not overlap, when offset+limit exceeds the matches reversed" in withStore { s =>
    populate(s)
    // 20 matches; descending offset=18 limit=5 -> only entries 1, 0 remain.
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 18, 5, reverse = true)(filter)
      .map(_._2.head.toInt) shouldBe Seq(1, 0)
    // offset at/past the end -> empty, so a naive client loop terminates.
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 20, 5, reverse = true)(filter) shouldBe empty
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 999, 10, reverse = true)(filter) shouldBe empty
  }

  it should "throw RangeScanBudgetExceeded when a scan exceeds its visit budget" in withStore { s =>
    populate(s)
    a[RangeScanBudgetExceeded] should be thrownBy
      s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 3, reverse = true, visitBudget = 5L)(filter)
    // window (offset+limit) larger than the budget must throw up-front,
    // BEFORE allocating the window array.
    a[RangeScanBudgetExceeded] should be thrownBy
      s.getRangeWithFilter(key(0x72,0), key(0x72,19), 1000000, 3, reverse = true, visitBudget = 100L)(filter)
    // forward scans respect the budget too, but the default is unlimited
    noException should be thrownBy
      s.getRangeWithFilter(key(0x72,0), key(0x72,19), 0, 3, reverse = false)(filter)
  }

  /** THE COST TEST. Spec §5 rests the safety of an unauthenticated route on
    * forward scans being O(offset + limit) with no buffering of skipped rows.
    * A "read everything then drop/take" implementation passes every other case
    * in this file; only this one rejects it.
    */
  it should "visit only offset+limit entries on a forward scan" in withStore { s =>
    populate(s)
    var calls = 0
    val counting: Array[Byte] => Boolean = k => { calls += 1; filter(k) }
    s.getRangeWithFilter(key(0x72,0), key(0x72,19), 5, 3, reverse = false)(counting)
    withClue(s"forward scan visited $calls keys for offset=5 limit=3: ") {
      calls should be <= 12
    }
  }
}
