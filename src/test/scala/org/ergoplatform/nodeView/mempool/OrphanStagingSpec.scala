package org.ergoplatform.nodeView.mempool

import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.{ProcessingOutcome, SortingOption}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.utils.ErgoTestHelpers
import org.scalatest.flatspec.AnyFlatSpec

class OrphanStagingSpec extends AnyFlatSpec with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoNodeTestConstants.settings
  import org.ergoplatform.utils.ErgoCoreTestConstants.emptyProverResult
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  it should "never stage anything when staging is disabled (default)" in {
    val (us, bh) = createUtxoState(settings)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, settings).applyModifier(genesis)(_ => ()).get
    val feeProp = settings.chainSettings.monetary.feeProposition
    val fakeBoxId: org.ergoplatform.ErgoBox.BoxId = scorex.crypto.authds.ADKey @@ scorex.util.Random.randomBytes(32)
    val childTx = ErgoTransaction(
      IndexedSeq(new Input(fakeBoxId, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(1000000L, feeProp, creationHeight = 0))
    )

    val pool0 = ErgoMemPool.empty(settings)
    pool0.staging.isEmpty shouldBe true
    val (pool1, outcome) = pool0.process(UnconfirmedTransaction(childTx, None), wus)
    outcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    pool1.staging.isEmpty shouldBe true
  }

  it should "stage a child as an orphan and resolve it when the parent arrives (staging enabled)" in {
    val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
      stagingEnabled = true, mempoolSorting = SortingOption.FeePerByte))
    val (us, bh) = createUtxoState(stagingSettings)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
    val feeProp = stagingSettings.chainSettings.monetary.feeProposition
    val trueTree = TrueTree
    val inputBox = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

    val parentTx = ErgoTransaction(
      IndexedSeq(new Input(inputBox.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                 new ErgoBoxCandidate(inputBox.value - 100000L, trueTree, creationHeight = 0))
    )
    val parentOutputBox = parentTx.outputs(1)
    val childTx = ErgoTransaction(
      IndexedSeq(new Input(parentOutputBox.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(parentOutputBox.value, feeProp, creationHeight = 0))
    )

    val pool0 = ErgoMemPool.empty(stagingSettings)
    val (pool1, childOutcome) = pool0.process(UnconfirmedTransaction(childTx, None), wus)
    childOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    pool1.staging.size shouldBe 1
    pool1.staging.contains(childTx.id) shouldBe true

    val (pool2, parentOutcome) = pool1.process(UnconfirmedTransaction(parentTx, None), wus)
    parentOutcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    parentOutcome.asInstanceOf[ProcessingOutcome.Accepted].coAdmitted.map(_.id) shouldBe Seq(childTx.id)
    pool2.staging.isEmpty shouldBe true
    pool2.contains(parentTx.id) shouldBe true
    pool2.contains(childTx.id) shouldBe true
  }

  it should "prune staged entries on block-apply when their input is confirmed-and-consumed (staging enabled)" in {
    val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(stagingEnabled = true))
    val (us, bh) = createUtxoState(stagingSettings)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
    val feeProp = stagingSettings.chainSettings.monetary.feeProposition

    val fakeBoxId: org.ergoplatform.ErgoBox.BoxId = scorex.crypto.authds.ADKey @@ scorex.util.Random.randomBytes(32)
    val childTx = ErgoTransaction(
      IndexedSeq(new Input(fakeBoxId, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(1000000L, feeProp, creationHeight = 0))
    )

    val pool0 = ErgoMemPool.empty(stagingSettings)
    val (pool1, outcome) = pool0.process(UnconfirmedTransaction(childTx, None), wus)
    outcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    pool1.staging.size shouldBe 1

    val pool2 = pool1.pruneStagingSpentInputs(Set(fakeBoxId))
    pool2.staging.isEmpty shouldBe true
  }

  it should "co-admit only the surviving double-spender when two orphans race on one parent output" in {
    val stagingSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(
      stagingEnabled = true, mempoolSorting = SortingOption.FeePerByte, mempoolCapacity = 10))
    val (us, bh) = createUtxoState(stagingSettings)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, stagingSettings).applyModifier(genesis)(_ => ()).get
    val feeProp = stagingSettings.chainSettings.monetary.feeProposition
    val trueTree = TrueTree
    val boxP = wus.takeBoxes(100).find(_.ergoTree == trueTree).get

    // Parent P creates output X (its change).
    val p = ErgoTransaction(IndexedSeq(new Input(boxP.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                 new ErgoBoxCandidate(boxP.value - 100000L, trueTree, creationHeight = 0)))
    val xBox = p.outputs(1)
    // A and B both spend X; B pays a higher fee, so B wins the RBF race.
    val a = ErgoTransaction(IndexedSeq(new Input(xBox.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, creationHeight = 0),
                 new ErgoBoxCandidate(xBox.value - 100000L, trueTree, creationHeight = 0)))
    val b = ErgoTransaction(IndexedSeq(new Input(xBox.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(300000L, feeProp, creationHeight = 0),
                 new ErgoBoxCandidate(xBox.value - 300000L, trueTree, creationHeight = 0)))

    // Stage A then B as orphans (X not present yet).
    val pool = ErgoMemPool.empty(stagingSettings)
    val (poolA, outA) = pool.process(UnconfirmedTransaction(a, None), wus)
    outA.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    val (poolB, outB) = poolA.process(UnconfirmedTransaction(b, None), wus)
    outB.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    poolB.staging.size shouldBe 2

    // Parent arrives -> resolves the waiters; A admitted then RBF-evicted by B.
    val (poolFinal, outP) = poolB.process(UnconfirmedTransaction(p, None), wus)
    outP.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    val acc = outP.asInstanceOf[ProcessingOutcome.Accepted]

    // Exactly one survivor, and it is the one in the pool + the broadcast set.
    poolFinal.contains(p.id) shouldBe true
    poolFinal.contains(b.id) shouldBe true
    poolFinal.contains(a.id) shouldBe false
    acc.coAdmitted.map(_.id) shouldBe Seq(b.id)
    acc.coAdmitted.forall(u => poolFinal.contains(u.id)) shouldBe true
    // Even A, which lost its place before the batch committed, consumed validation work.
    acc.validationWork.get.map(_.transaction.id).toSet shouldBe Set(p.id, a.id, b.id)
    acc.cost shouldBe acc.validationWork.get.map(_.cost).sum
  }
  it should "ignore unused staging limits when the feature is disabled" in {
    val disabled = settings.copy(nodeSettings = settings.nodeSettings.copy(stagingEnabled = false,
      stagingMaxCount = -1, stagingMaxBytes = -1L, stagingTtlMillis = -1L, stagingMaxValidationAttempts = -1))
    ErgoMemPool.empty(disabled).staging.isEmpty shouldBe true
  }

  it should "leave work past the batch limit staged and recover it on the next retry" in {
    val cfg = settings.copy(nodeSettings = settings.nodeSettings.copy(stagingEnabled = true, stagingMaxValidationAttempts = 2))
    val (us, bh) = createUtxoState(cfg)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, cfg).applyModifier(genesis)(_ => ()).get
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    def spend(b: org.ergoplatform.ErgoBox): ErgoTransaction = ErgoTransaction(
      IndexedSeq(new Input(b.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(100000L, cfg.chainSettings.monetary.feeProposition, 0),
        new ErgoBoxCandidate(b.value - 100000L, TrueTree, 0)))
    val parent = spend(box)
    val child = spend(parent.outputs(1))
    val grandchild = spend(child.outputs(1))
    val staged = Seq(grandchild, child).foldLeft(ErgoMemPool.empty(cfg))((p, t) => p.process(UnconfirmedTransaction(t, None), wus)._1)
    val (limited, first) = staged.process(UnconfirmedTransaction(parent, None), wus)
    first.validationWork.get.map(_.transaction.id) shouldBe Seq(parent.id, child.id)
    limited.contains(grandchild.id) shouldBe false
    limited.staging.contains(grandchild.id) shouldBe true
    val (retried, second) = limited.retryStaging(wus)
    retried.contains(grandchild.id) shouldBe true
    second.admitted.map(_.id) shouldBe Seq(grandchild.id)
    second.validationWork.get.map(_.transaction.id) shouldBe Seq(grandchild.id)
  }

  it should "wake orphans when a rolled-back parent is returned through put" in {
    val cfg = settings.copy(nodeSettings = settings.nodeSettings.copy(stagingEnabled = true))
    val (us, bh) = createUtxoState(cfg)
    val genesis = validFullBlock(None, us, bh)
    val wus = WrappedUtxoState(us, bh, cfg).applyModifier(genesis)(_ => ()).get
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    val parent = ErgoTransaction(IndexedSeq(new Input(box.id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(100000L, cfg.chainSettings.monetary.feeProposition, 0),
        new ErgoBoxCandidate(box.value - 100000L, TrueTree, 0)))
    val child = ErgoTransaction(IndexedSeq(new Input(parent.outputs(1).id, emptyProverResult)),
      IndexedSeq(new ErgoBoxCandidate(parent.outputs(1).value, cfg.chainSettings.monetary.feeProposition, 0)))
    val staged = ErgoMemPool.empty(cfg).process(UnconfirmedTransaction(child, None), wus)._1
    val (recovered, outcome) = staged.put(UnconfirmedTransaction(parent, None)).retryStaging(wus)
    recovered.contains(child.id) shouldBe true
    outcome.admitted.map(_.id) shouldBe Seq(child.id)
    outcome.validationWork.get.map(_.transaction.id) shouldBe Seq(child.id)
    recovered.staging.isEmpty shouldBe true
  }

}
