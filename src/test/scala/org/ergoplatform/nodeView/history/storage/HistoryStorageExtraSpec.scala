package org.ergoplatform.nodeView.history.storage

import org.ergoplatform.nodeView.history.extra.ExtraIndexer.{isRentKey, rentKey}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.utils.HistoryTestHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HistoryStorageExtraSpec extends AnyFlatSpec with Matchers {

  private def storage(): HistoryStorage =
    HistoryTestHelpers.generateHistory(
      verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false, blocksToKeep = 0
    ).historyStorage

  "insertExtra" should "insert raw keys and remove others" in {
    val s = storage()
    val k1 = rentKey(10, 1L)
    val k2 = rentKey(10, 2L)
    s.insertExtra(Array(k1 -> Array[Byte](1), k2 -> Array[Byte](2)), Array.empty)
    s.get(k1).map(_.toSeq) shouldBe Some(Seq[Byte](1))
    s.get(k2).map(_.toSeq) shouldBe Some(Seq[Byte](2))

    s.insertExtra(Array.empty, Array.empty, Array(k1))
    s.get(k1) shouldBe None
    s.get(k2).map(_.toSeq) shouldBe Some(Seq[Byte](2))
  }

  it should "treat removal of an absent key as a no-op" in {
    val s = storage()
    noException should be thrownBy s.insertExtra(Array.empty, Array.empty, Array(rentKey(999, 999L)))
  }

  it should "make inserted raw keys visible to a filtered range scan" in {
    val s = storage()
    val keys = (0 until 5).map(n => rentKey(100, n.toLong))
    s.insertExtra(keys.map(_ -> Array[Byte](7)).toArray, Array.empty)
    s.getExtraRange(rentKey(0, 0L), rentKey(100, Long.MaxValue), 0, 100, reverse = false)(isRentKey)
      .length shouldBe 5
  }
}
