package org.ergoplatform.nodeView.mempool

import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.{ProcessingOutcome, SortingOption}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.ErgoTestHelpers
import org.scalatest.flatspec.AnyFlatSpec
import sigma.interpreter.ProverResult

class OrphanDependencySpec extends AnyFlatSpec with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoNodeTestConstants.settings
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  private def fixture(capacity: Int = 100): (ErgoSettings, WrappedUtxoState) = {
    val cfg = settings.copy(nodeSettings = settings.nodeSettings.copy(
      stagingEnabled = true, mempoolSorting = SortingOption.FeePerByte, mempoolCapacity = capacity))
    val (us, bh) = createUtxoState(cfg)
    val genesis = validFullBlock(None, us, bh)
    (cfg, WrappedUtxoState(us, bh, cfg).applyModifier(genesis)(_ => ()).get)
  }

  private def spend(boxes: IndexedSeq[ErgoBox], fee: Long, cfg: ErgoSettings,
                    changeParts: Int = 1): ErgoTransaction = {
    val change = boxes.map(_.value).sum - fee
    val outputs = IndexedSeq(new ErgoBoxCandidate(fee, cfg.chainSettings.monetary.feeProposition, 0)) ++
      (if (change == 0) IndexedSeq.empty else (0 until changeParts).map { i =>
        new ErgoBoxCandidate(change / changeParts + (if (i == 0) change % changeParts else 0), TrueTree, 0)
      }.toIndexedSeq)
    ErgoTransaction(boxes.map(b => new Input(b.id, ProverResult.empty)), outputs)
  }

  private def utx(tx: ErgoTransaction): UnconfirmedTransaction = UnconfirmedTransaction(tx, None)

  it should "retry an orphan after another queued parent resolves" in {
    val (cfg, wus) = fixture()
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    val parent = spend(IndexedSeq(box), 100000L, cfg, changeParts = 2)
    val candidate = (0 until 20).iterator.flatMap { salt =>
      (1 to 2).iterator.map { idx =>
        val mid = spend(IndexedSeq(parent.outputs(idx)), 100000L + salt, cfg)
        val inputs = IndexedSeq(parent.outputs(3 - idx), mid.outputs(1))
        val child = spend(inputs, inputs.map(_.value).sum, cfg)
        val p1 = ErgoMemPool.empty(cfg).process(utx(child), wus)._1
        val p2 = p1.process(utx(mid), wus)._1
        val seed = parent.outputs.map(_.id).toSet.flatMap(p2.staging.waitersOn).toList
        (mid, child, p2, seed)
      }
    }.find { case (mid, child, _, seed) => seed.indexOf(child.id) < seed.indexOf(mid.id) }.get
    val (mid, child, staged, _) = candidate
    val (result, outcome) = staged.process(utx(parent), wus)
    outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    result.contains(mid.id) shouldBe true
    child.inputIds.forall(wus.withUnconfirmedTransactions(result.getAll).boxById(_).isDefined) shouldBe true
    withClue("All child inputs now exist, but it remains staged: ") { result.contains(child.id) shouldBe true }
  }

  it should "recover a reconvergent family in every arrival order" in {
    val (cfg, wus) = fixture()
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    val parent = spend(IndexedSeq(box), 100000L, cfg, changeParts = 2)
    val mid = spend(IndexedSeq(parent.outputs(1)), 100000L, cfg)
    val childInputs = IndexedSeq(parent.outputs(2), mid.outputs(1))
    val child = spend(childInputs, childInputs.map(_.value).sum, cfg)
    Seq(parent, mid, child).permutations.foreach { arrival =>
      val result = arrival.foldLeft(ErgoMemPool.empty(cfg))((pool, tx) => pool.process(utx(tx), wus)._1)
      withClue(s"Arrival order: ${arrival.map(_.id)}") {
        result.getAll.map(_.id).toSet shouldBe Set(parent.id, mid.id, child.id)
        result.staging.isEmpty shouldBe true
      }
    }
  }
}
