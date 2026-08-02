package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.rentKey
import org.ergoplatform.settings.ErgoSettings
import scorex.db.ByteArrayWrapper
import scorex.util.{ModifierId, bytesToId}
import spire.implicits.cfor

import java.util.concurrent.locks.{Condition, ReentrantLock}
import scala.collection.mutable

/**
  * Reusable test harness surface for `ExtraIndexerTestActor`. Extracted so that
  * suites other than `ExtraIndexerSpecification` (e.g. a later route-level spec)
  * can drive the same actor without depending on `ExtraIndexerSpecification`'s
  * path-dependent inner types.
  */
trait ExtraIndexerTestHarness {

  val initSettings: ErgoSettings

  val lock: ReentrantLock = new ReentrantLock()
  val done: Condition = lock.newCondition()
  val created: Condition = lock.newCondition()

  var _history: ErgoHistory = _

  case class CreateDB(blockCount: Int)
  case class Reset()
  case class GenerateBetterChainTip()

  /** Expected rent rows after indexing blocks 1..limit: currently-unspent boxes
    * keyed by (creationHeight, globalIndex) -> boxId.
    *
    * Mirrors the indexer's genesis handling: inputs skipped at height 1
    * (ExtraIndexer.scala:318), and pre-block genesis boxes never indexed at all,
    * so both sides cover only block-created boxes.
    */
  def manualRentSet(limit: Int): mutable.HashMap[ByteArrayWrapper, ModifierId] = {
    val expected = mutable.HashMap.empty[ByteArrayWrapper, ModifierId]
    val seen = mutable.HashMap.empty[ModifierId, (Int, Long)]
    var globalBoxIndex = 0L
    cfor(1)(_ <= limit, _ + 1) { i =>
      val header = _history.headerIdsAtHeight(i).last
      val block = _history.getFullBlock(_history.typedModifierById[Header](header).get)
      block.get.transactions.foreach { tx =>
        if (i > 1) tx.inputs.foreach { in =>
          seen.get(bytesToId(in.boxId)).foreach { case (h, g) =>
            expected.remove(ByteArrayWrapper(rentKey(h, g)))
          }
        }
        tx.outputs.foreach { out =>
          val id = bytesToId(out.id)
          seen.put(id, (out.creationHeight, globalBoxIndex))
          expected.put(ByteArrayWrapper(rentKey(out.creationHeight, globalBoxIndex)), id)
          globalBoxIndex += 1
        }
      }
    }
    expected
  }
}
