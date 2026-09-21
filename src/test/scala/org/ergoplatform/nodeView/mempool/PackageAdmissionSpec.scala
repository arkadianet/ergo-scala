package org.ergoplatform.nodeView.mempool

import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.{ProcessingOutcome, SortingOption}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.utils.ErgoTestHelpers
import org.scalatest.flatspec.AnyFlatSpec

class PackageAdmissionSpec extends AnyFlatSpec with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoNodeTestConstants.settings
  import org.ergoplatform.utils.ErgoCoreTestConstants.emptyProverResult
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  Seq(SortingOption.FeePerByte, SortingOption.FeePerCycle).foreach { sorting =>
    behavior of s"Package admission ($sorting)"

    it should "stage a fully-valid tx as Held when declined for pool fullness (staging enabled)" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 1))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxes = wus.takeBoxes(100).filter(_.ergoTree == trueTree)
      val boxHi = boxes(0)
      val boxLo = boxes(1)

      val hiTx = ErgoTransaction(IndexedSeq(new Input(boxHi.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(boxHi.value, feeProp, creationHeight = 0)))
      val loTx = ErgoTransaction(IndexedSeq(new Input(boxLo.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxLo.value - 100000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (pool1, hiOutcome) = pool0.process(UnconfirmedTransaction(hiTx, None), wus)
      hiOutcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true

      val (pool2, loOutcome) = pool1.process(UnconfirmedTransaction(loTx, None), wus)
      loOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      pool2.staging.size shouldBe 1
      pool2.staging.get(loTx.id).map(_.isHeld) shouldBe Some(true)
    }

    it should "admit a package via RBF when aggregate weight and fee both beat the conflicting incumbent (R1 and R2)" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 10))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxA = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

      // Tx1: pooled, modest fee, spends boxA.
      val tx1 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(200000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 200000L, trueTree, creationHeight = 0)))
      // Tx2: also spends boxA, small fee -> loses to Tx1, staged Held.
      val tx2 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (pool1, out1) = pool0.process(UnconfirmedTransaction(tx1, None), wus)
      out1.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true

      val (pool2, out2) = pool1.process(UnconfirmedTransaction(tx2, None), wus)
      out2.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
      pool2.staging.size shouldBe 1

      // Child spends Tx2's change output with a big fee -> package beats Tx1.
      val tx2Change = tx2.outputs(1)
      val childTx = ErgoTransaction(IndexedSeq(new Input(tx2Change.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(tx2Change.value, feeProp, creationHeight = 0)))

      val (pool3, childOutcome) = pool2.process(UnconfirmedTransaction(childTx, None), wus)
      childOutcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      childOutcome.asInstanceOf[ProcessingOutcome.Accepted].coAdmitted.map(_.id) shouldBe Seq(tx2.id)
      pool3.staging.isEmpty shouldBe true
      pool3.contains(tx1.id) shouldBe false
      pool3.contains(tx2.id) shouldBe true
      pool3.contains(childTx.id) shouldBe true
    }

    it should "reject a package via RBF when it does not beat the conflicting incumbent, leaving pool and held ancestor untouched" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 10))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxA = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

      val tx1 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(500000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 500000L, trueTree, creationHeight = 0)))
      val tx2 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (pool1, out1) = pool0.process(UnconfirmedTransaction(tx1, None), wus)
      out1.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val (pool2, out2) = pool1.process(UnconfirmedTransaction(tx2, None), wus)
      out2.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true

      // Child adds only a modest extra fee - package aggregate stays below Tx1's.
      val tx2Change = tx2.outputs(1)
      val childTx = ErgoTransaction(IndexedSeq(new Input(tx2Change.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(tx2Change.value - 100000L, trueTree, creationHeight = 0)))

      val (pool3, childOutcome) = pool2.process(UnconfirmedTransaction(childTx, None), wus)
      childOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      pool3.contains(tx1.id) shouldBe true
      pool3.staging.get(tx2.id).map(_.isHeld) shouldBe Some(true)
    }

    it should "reject a package where two held ancestors would spend the same box (intra-package double spend)" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 10))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxA = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

      // X: pooled, high fee, wins boxA.
      val txX = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(boxA.value, feeProp, creationHeight = 0)))
      // P1, P2: both also spend boxA (distinct shapes -> distinct ids), both lose to X, both staged Held.
      val p1 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100000L, trueTree, creationHeight = 0)))
      val p2 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100001L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100001L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (pool1, outX) = pool0.process(UnconfirmedTransaction(txX, None), wus)
      outX.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val (pool2, out1) = pool1.process(UnconfirmedTransaction(p1, None), wus)
      out1.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
      val (pool3, out2) = pool2.process(UnconfirmedTransaction(p2, None), wus)
      out2.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
      pool3.staging.size shouldBe 2

      val p1Out = p1.outputs(1)
      val p2Out = p2.outputs(1)
      val childTx = ErgoTransaction(
        IndexedSeq(new Input(p1Out.id, emptyProverResult), new Input(p2Out.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(p1Out.value + p2Out.value, feeProp, creationHeight = 0))
      )

      val (pool4, childOutcome) = pool3.process(UnconfirmedTransaction(childTx, None), wus)
      childOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      childOutcome.asInstanceOf[ProcessingOutcome.Declined].e.getMessage.contains("intra-package double spend") shouldBe true
      pool4.staging.size shouldBe 2
    }

    it should "remove the incumbent's in-pool child closure when a package RBF-wins (no stranding)" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 10))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxA = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

      // Pi: incumbent parent spending boxA, leaving change Y.
      val pi = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100000L, trueTree, creationHeight = 0)))
      val yBox = pi.outputs(1)
      // Ci: incumbent's in-pool child spending Y (modest fee, keeps change so the
      // closure aggregate fee stays beatable by a package drawing from the same box).
      val ci = ErgoTransaction(IndexedSeq(new Input(yBox.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(yBox.value - 100000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (poolA, outPi) = pool0.process(UnconfirmedTransaction(pi, None), wus)
      outPi.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val (poolB, outCi) = poolA.process(UnconfirmedTransaction(ci, None), wus)
      outCi.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      poolB.size shouldBe 2

      // P2: a DISTINCT tx also spending boxA (conflicts with Pi) with a small
      // fee -> loses to the CPFP-boosted Pi, staged Held.
      val p2 = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(90000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 90000L, trueTree, creationHeight = 0)))
      val (poolC, outP2) = poolB.process(UnconfirmedTransaction(p2, None), wus)
      outP2.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
      poolC.staging.get(p2.id).map(_.isHeld) shouldBe Some(true)

      // Child spends P2's change with a huge fee -> package {P2, child} beats the
      // full {Pi, Ci} closure and must remove BOTH.
      val p2Change = p2.outputs(1)
      val child = ErgoTransaction(IndexedSeq(new Input(p2Change.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(p2Change.value - 100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(100000L, trueTree, creationHeight = 0)))

      val (poolD, outChild) = poolC.process(UnconfirmedTransaction(child, None), wus)
      outChild.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      poolD.contains(p2.id) shouldBe true
      poolD.contains(child.id) shouldBe true
      // The incumbent AND its child are both gone - Ci is not stranded.
      poolD.contains(pi.id) shouldBe false
      poolD.contains(ci.id) shouldBe false
    }

    it should "seat a CPFP package atomically at capacity without evicting its own low-fee parent" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 2))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxes = wus.takeBoxes(100).filter(_.ergoTree == trueTree).take(3)
      val (boxI1, boxI2, boxL) = (boxes(0), boxes(1), boxes(2))

      // Two modest-feerate incumbents (keep change) fill the pool to capacity.
      val i1 = ErgoTransaction(IndexedSeq(new Input(boxI1.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(200000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxI1.value - 200000L, trueTree, creationHeight = 0)))
      val i2 = ErgoTransaction(IndexedSeq(new Input(boxI2.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(200000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxI2.value - 200000L, trueTree, creationHeight = 0)))
      // Low-fee parent L: lower feerate than the incumbents -> declined for pool
      // fullness -> staged Held. Leaves change for the booster child.
      val lTx = ErgoTransaction(IndexedSeq(new Input(boxL.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxL.value - 100000L, trueTree, creationHeight = 0)))

      var pool = ErgoMemPool.empty(stagingSettings)
      pool = pool.process(UnconfirmedTransaction(i1, None), wus)._1
      pool = pool.process(UnconfirmedTransaction(i2, None), wus)._1
      pool.size shouldBe 2
      val (poolHeld, lOutcome) = pool.process(UnconfirmedTransaction(lTx, None), wus)
      lOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      poolHeld.staging.get(lTx.id).map(_.isHeld) shouldBe Some(true)

      // Child sweeps L's change as a huge fee -> package {L, child} out-feerates
      // both incumbents; room is pre-made so neither member is self-evicted.
      val lChange = lTx.outputs(1)
      val child = ErgoTransaction(IndexedSeq(new Input(lChange.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(lChange.value - 100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(100000L, trueTree, creationHeight = 0)))

      val (poolFinal, childOutcome) = poolHeld.process(UnconfirmedTransaction(child, None), wus)
      childOutcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      // Both members admitted; the low-fee parent survives (not self-evicted).
      poolFinal.contains(lTx.id) shouldBe true
      poolFinal.contains(child.id) shouldBe true
      poolFinal.size shouldBe 2
      // No phantom: coAdmitted only names txs actually in the final pool.
      val acc = childOutcome.asInstanceOf[ProcessingOutcome.Accepted]
      acc.coAdmitted.map(_.id) shouldBe Seq(lTx.id)
      acc.coAdmitted.forall(u => poolFinal.contains(u.id)) shouldBe true
      // The outranked incumbents were evicted.
      poolFinal.contains(i1.id) shouldBe false
      poolFinal.contains(i2.id) shouldBe false
    }

    it should "never evict a package member's in-pool ancestor to make room" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 2))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxes = wus.takeBoxes(100).filter(_.ergoTree == trueTree).take(2)
      val (boxP, boxQ) = (boxes(0), boxes(1))

      // Pt: low-fee pool tx creating box b (its change). H (below) spends b.
      val pt = ErgoTransaction(IndexedSeq(new Input(boxP.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxP.value - 100000L, trueTree, creationHeight = 0)))
      val bBox = pt.outputs(1)
      // Qt: higher-fee pool tx; fills the pool to capacity alongside Pt.
      val qt = ErgoTransaction(IndexedSeq(new Input(boxQ.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(500000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxQ.value - 500000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (p1, oPt) = pool0.process(UnconfirmedTransaction(pt, None), wus)
      oPt.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val (p2, oQt) = p1.process(UnconfirmedTransaction(qt, None), wus)
      oQt.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      p2.size shouldBe 2

      // H: spends b with a low fee -> declined for pool fullness -> staged Held.
      val h = ErgoTransaction(IndexedSeq(new Input(bBox.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(70000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(bBox.value - 70000L, trueTree, creationHeight = 0)))
      val (p3, oH) = p2.process(UnconfirmedTransaction(h, None), wus)
      oH.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      p3.staging.get(h.id).map(_.isHeld) shouldBe Some(true)

      // C: spends H's change -> package {H, C}. Making room would require evicting
      // Pt (b's creator) - forbidden -> clean reject; H never seated.
      val hChange = h.outputs(1)
      val c = ErgoTransaction(IndexedSeq(new Input(hChange.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(hChange.value, feeProp, creationHeight = 0)))
      val (p4, oC) = p3.process(UnconfirmedTransaction(c, None), wus)
      oC.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      p4.contains(pt.id) shouldBe true      // the protected ancestor survives
      p4.contains(qt.id) shouldBe true
      p4.contains(h.id) shouldBe false      // H never seated spending a missing box
      p4.contains(c.id) shouldBe false
      p4.size shouldBe 2
    }

    it should "not co-admit a package ancestor evicted by a resolved sibling" in {
      val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
        stagingEnabled = true, mempoolSorting = sorting, mempoolCapacity = 10))
      val (us, bh) = createUtxoState(stagingSettings)
      val genesis = validFullBlock(None, us, bh)
      val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
      val feeProp = stagingSettings.chainSettings.monetary.feeProposition
      val trueTree = TrueTree
      val boxA = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

      // W: pooled winner spending boxA (moderate fee).
      val w = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(200000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 200000L, trueTree, creationHeight = 0)))
      // H: also spends boxA, lower fee -> DoubleSpendingLoser vs W -> staged Held.
      val h = ErgoTransaction(IndexedSeq(new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(boxA.value - 100000L, trueTree, creationHeight = 0)))
      val hChange = h.outputs(1)
      // C: spends H's change with a high fee -> package {H, C} out-weighs W.
      val c = ErgoTransaction(IndexedSeq(new Input(hChange.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(500000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(hChange.value - 500000L, trueTree, creationHeight = 0)))
      val cChange = c.outputs(1)
      // E: spends C's change AND boxA (double-spends H's input) with a huge fee.
      val e = ErgoTransaction(
        IndexedSeq(new Input(cChange.id, emptyProverResult), new Input(boxA.id, emptyProverResult)),
        IndexedSeq(new ErgoBoxCandidate(cChange.value + boxA.value - 100000L, feeProp, creationHeight = 0),
                   new ErgoBoxCandidate(100000L, trueTree, creationHeight = 0)))

      val pool0 = ErgoMemPool.empty(stagingSettings)
      val (p1, oW) = pool0.process(UnconfirmedTransaction(w, None), wus)
      oW.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val (p2, oH) = p1.process(UnconfirmedTransaction(h, None), wus)
      oH.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
      p2.staging.get(h.id).map(_.isHeld) shouldBe Some(true)
      // E staged as an orphan waiting on C's (not-yet-existing) output.
      val (p3, oE) = p2.process(UnconfirmedTransaction(e, None), wus)
      oE.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      p3.staging.get(e.id).map(_.isOrphan) shouldBe Some(true)

      // C arrives -> package {H, C} admits (W evicted); then resolving E evicts H.
      val (p4, oC) = p3.process(UnconfirmedTransaction(c, None), wus)
      oC.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      val acc = oC.asInstanceOf[ProcessingOutcome.Accepted]

      p4.contains(h.id) shouldBe false                 // H was RBF-evicted by E
      p4.contains(e.id) shouldBe true
      p4.contains(c.id) shouldBe true
      acc.coAdmitted.map(_.id) should not contain h.id // no phantom broadcast of H
      acc.coAdmitted.forall(u => p4.contains(u.id)) shouldBe true
    }
  }
}
