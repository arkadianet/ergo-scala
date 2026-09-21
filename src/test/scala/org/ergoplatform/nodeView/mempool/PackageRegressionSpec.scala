package org.ergoplatform.nodeView.mempool

import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.{ProcessingOutcome, SortingOption}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.nodeView.wallet.IdUtils.encodedBoxId
import org.ergoplatform.nodeView.wallet.persistence.OffChainRegistry
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.ErgoTestHelpers
import org.ergoplatform.wallet.Constants.PaymentsScanId
import org.ergoplatform.wallet.boxes.TrackedBox
import org.scalatest.flatspec.AnyFlatSpec
import sigma.interpreter.ProverResult

class PackageRegressionSpec extends AnyFlatSpec with ErgoTestHelpers {
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

  private def heldPair(cfg: ErgoSettings, wus: WrappedUtxoState): (ErgoMemPool, ErgoTransaction) = {
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    val winner = spend(IndexedSeq(box), 200000L, cfg)
    val held = spend(IndexedSeq(box), 100000L, cfg)
    val (p1, o1) = ErgoMemPool.empty(cfg).process(utx(winner), wus)
    o1.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    val (p2, o2) = p1.process(utx(held), wus)
    o2.isInstanceOf[ProcessingOutcome.DoubleSpendingLoser] shouldBe true
    p2.staging.get(held.id).exists(_.isHeld) shouldBe true
    (p2, held)
  }

  Seq("input", "data-input").foreach { dependency =>
    it should s"preserve an external $dependency ancestor during package replacement" in {
      val (cfg, wus) = fixture(capacity = 3)
      val boxes = wus.takeBoxes(100).filter(b => b.ergoTree == TrueTree && b.value > 10000000L).take(4)
      boxes.size shouldBe 4
      val ancestor = spend(IndexedSeq(boxes(0)), 200000L, cfg)
      val f1 = spend(IndexedSeq(boxes(1)), 300000L, cfg)
      val f2 = spend(IndexedSeq(boxes(2)), 400000L, cfg)
      val full = Seq(ancestor, f1, f2).foldLeft(ErgoMemPool.empty(cfg)) { (p, tx) =>
        val (next, outcome) = p.process(utx(tx), wus)
        outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
        next
      }
      val heldInput = if (dependency == "data-input") boxes(3) else ancestor.outputs(1)
      val rawHeld = spend(IndexedSeq(heldInput), 100000L, cfg)
      val held = if (dependency == "data-input") {
        ErgoTransaction(rawHeld.inputs, IndexedSeq(org.ergoplatform.DataInput(ancestor.outputs(1).id)), rawHeld.outputCandidates)
      } else rawHeld
      val (staged, heldOutcome) = full.process(utx(held), wus)
      heldOutcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      staged.staging.get(held.id).exists(_.isHeld) shouldBe true
      val childInputs = IndexedSeq(held.outputs(1), boxes(0))
      val child = spend(childInputs, childInputs.map(_.value).sum, cfg)
      val (result, outcome) = staged.process(utx(child), wus)
      outcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
      result.getAll.map(_.id).toSet shouldBe staged.getAll.map(_.id).toSet
      val missing = result.getAll.flatMap(u => u.transaction.inputIds ++ u.transaction.dataInputs.map(_.boxId))
        .filter(id => wus.withUnconfirmedTransactions(result.getAll).boxById(id).isEmpty)
      withClue(s"${outcome.getClass.getSimpleName}; ancestor remains=${result.contains(ancestor.id)}; missing inputs: ") {
        missing shouldBe empty
      }
    }

  }

  it should "not resurrect spent wallet change when scanning a rescued package" in {
    val (cfg, wus) = fixture()
    val (staged, held) = heldPair(cfg, wus)
    val child = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (_, outcome) = staged.process(utx(child), wus)
    outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    val accepted = outcome.asInstanceOf[ProcessingOutcome.Accepted]
    val actorScanOrder = accepted.admitted.map(_.transaction)
    def scan(txs: Seq[ErgoTransaction]): OffChainRegistry = txs.foldLeft(OffChainRegistry.empty) { (registry, tx) =>
      val owned = tx.outputs.filter(_.ergoTree == TrueTree).map { box =>
        TrackedBox(tx, box.index, None, box, Set(PaymentsScanId))
      }
      registry.updateOnTransaction(owned, tx.inputIds.map(encodedBoxId), Seq.empty)
    }
    scan(Seq(held, child)).digest.walletBalance shouldBe 0L
    withClue("txModify's child-first scan resurrects the spent parent output: ") {
      scan(actorScanOrder).digest.walletBalance shouldBe 0L
    }
  }

  it should "wake orphans waiting on any newly admitted package member" in {
    val (cfg, wus) = fixture()
    val box = wus.takeBoxes(100).find(b => b.ergoTree == TrueTree && b.value > 10000000L).get
    val winner = spend(IndexedSeq(box), 200000L, cfg)
    val held = spend(IndexedSeq(box), 100000L, cfg, changeParts = 2)
    val p1 = ErgoMemPool.empty(cfg).process(utx(winner), wus)._1
    val staged = p1.process(utx(held), wus)._1
    staged.staging.get(held.id).exists(_.isHeld) shouldBe true
    val sibling = spend(IndexedSeq(held.outputs(2)), held.outputs(2).value, cfg)
    // Stage before the held parent arrives, otherwise it itself triggers the package.
    val s0 = ErgoMemPool.empty(cfg).process(utx(sibling), wus)._1
    val s1 = s0.process(utx(winner), wus)._1
    val s2 = s1.process(utx(held), wus)._1
    // Arrival of a held parent now retries the already-known child immediately.
    s2.contains(sibling.id) shouldBe true
    s2.contains(held.id) shouldBe true
    val booster = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (result, outcome) = s2.process(utx(booster), wus)
    outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    result.contains(held.id) shouldBe true
    withClue("Held parent was admitted, but its other waiting child was never woken: ") {
      result.contains(sibling.id) shouldBe true
    }
  }

  it should "preserve transitive external ancestors during capacity eviction" in {
    val (cfg, wus) = fixture(capacity = 4)
    val boxes = wus.takeBoxes(100).filter(b => b.ergoTree == TrueTree && b.value > 10000000L).take(3)
    val grandparent = spend(IndexedSeq(boxes(0)), 200000L, cfg)
    val parent = spend(IndexedSeq(grandparent.outputs(1)), 200000L, cfg)
    val fillers = boxes.tail.map(b => spend(IndexedSeq(b), 400000L, cfg))
    val full = (Seq(grandparent, parent) ++ fillers).foldLeft(ErgoMemPool.empty(cfg)) { (pool, tx) =>
      val (next, outcome) = pool.process(utx(tx), wus)
      outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
      next
    }
    val held = spend(IndexedSeq(parent.outputs(1)), 100000L, cfg)
    val staged = full.process(utx(held), wus)._1
    staged.staging.get(held.id).exists(_.isHeld) shouldBe true
    val child = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (result, outcome) = staged.process(utx(child), wus)
    outcome.isInstanceOf[ProcessingOutcome.Accepted] shouldBe true
    result.getAll.map(_.id).toSet shouldBe Set(grandparent.id, parent.id, held.id, child.id)
  }

  it should "reject a package exceeding cached cost before running any scripts" in {
    val (base, wus) = fixture()
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingMaxPackageCost = 1L))
    val (staged, held) = heldPair(cfg, wus)
    val child = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (result, outcome) = staged.process(utx(child), wus)
    outcome.asInstanceOf[ProcessingOutcome.Declined].e.getMessage should include ("staging.maxPackageCost")
    result.getAll.map(_.id).toSet shouldBe staged.getAll.map(_.id).toSet
    result.staging.get(held.id).exists(_.isHeld) shouldBe true
    outcome.validationWork.get shouldBe empty
  }
  private def stale(pool: ErgoMemPool, tx: ErgoTransaction, cfg: ErgoSettings): ErgoMemPool = {
    val entry = pool.staging.get(tx.id).get
    val updated = pool.staging.stageHeld(entry.utx, entry.priority, entry.source, None).right.get
    new ErgoMemPool(pool.pool, pool.stats, pool.sortingOption, updated)(cfg)
  }

  it should "cache refreshed ancestors and a declined child without extending retention" in {
    val (cfg, wus) = fixture()
    val (original, held) = heldPair(cfg, wus)
    val old = stale(original, held, cfg)
    val child = spend(IndexedSeq(held.outputs(1)), 100000L, cfg)
    val (first, outcome) = old.process(utx(child), wus)
    outcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    outcome.validationWork.get.map(_.transaction.id) shouldBe Seq(held.id, child.id)
    val refreshed = first.staging.get(held.id).get
    refreshed.stagedTipId shouldBe wus.stateContext.lastHeaderOpt.map(_.id)
    refreshed.receivedAt shouldBe old.staging.get(held.id).get.receivedAt
    first.getAll.map(_.id) shouldBe old.getAll.map(_.id)
    val (second, repeated) = first.process(utx(child), wus)
    repeated.validationWork.get shouldBe empty
    second.getAll.map(_.id) shouldBe old.getAll.map(_.id)
    val tweakedChild = spend(IndexedSeq(held.outputs(1)), 110000L, cfg)
    val (_, tweaked) = second.process(utx(tweakedChild), wus)
    tweaked.validationWork.get.map(_.transaction.id) shouldBe Seq(tweakedChild.id)
  }

  it should "preflight stale cached costs before any ancestor refresh" in {
    val (base, wus) = fixture()
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingMaxPackageCost = 1L))
    val (original, held) = heldPair(cfg, wus)
    val old = stale(original, held, cfg)
    val child = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (result, outcome) = old.process(utx(child), wus)
    outcome.validationWork.get shouldBe empty
    outcome.asInstanceOf[ProcessingOutcome.Declined].e.getMessage should include ("staging.maxPackageCost")
    result.staging.get(held.id).get.stagedTipId shouldBe None
  }

  it should "bound wide packages before executing any ancestor scripts" in {
    val (base, wus) = fixture()
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingMaxPackageTransactions = 2))
    val boxes = wus.takeBoxes(100).filter(b => b.ergoTree == TrueTree && b.value > 10000000L).take(3)
    val parents = boxes.map(b => spend(IndexedSeq(b), 100000L, cfg))
    val full = boxes.foldLeft(ErgoMemPool.empty(cfg))((p, b) => p.process(utx(spend(IndexedSeq(b), 200000L, cfg)), wus)._1)
    val held = parents.foldLeft(full)((p, tx) => p.process(utx(tx), wus)._1)
    val old = parents.foldLeft(held)((p, tx) => stale(p, tx, cfg))
    val inputs = parents.map(_.outputs(1))
    val child = spend(inputs.toIndexedSeq, inputs.map(_.value).sum, cfg)
    val (result, outcome) = old.process(utx(child), wus)
    outcome.isInstanceOf[ProcessingOutcome.Declined] shouldBe true
    outcome.validationWork.get shouldBe empty
    result.getAll.map(_.id) shouldBe old.getAll.map(_.id)
  }

  it should "retain partial refresh progress and defer the child when the batch budget is exhausted" in {
    val (base, wus) = fixture()
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingMaxValidationAttempts = 1))
    val (original, held) = heldPair(cfg, wus)
    val old = stale(original, held, cfg)
    val child = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val (deferred, first) = old.process(utx(child), wus)
    first.validationWork.get.map(_.transaction.id) shouldBe Seq(held.id)
    first.validationWork.get.flatMap(_.error) shouldBe empty
    deferred.staging.contains(child.id) shouldBe true
    val (admitted, next) = deferred.retryStaging(wus)
    next.validationWork.get.map(_.transaction.id) shouldBe Seq(child.id)
    admitted.contains(child.id) shouldBe true
    admitted.contains(held.id) shouldBe true
  }

  it should "invalidate a script-invalid package child and avoid validating its repeat" in {
    val (cfg, wus) = fixture()
    val (heldPool, held) = heldPair(cfg, wus)
    val valid = spend(IndexedSeq(held.outputs(1)), held.outputs(1).value, cfg)
    val invalid = ErgoTransaction(valid.inputs, IndexedSeq(new ErgoBoxCandidate(held.outputs(1).value + 1000000L,
      cfg.chainSettings.monetary.feeProposition, 0)))
    val (result, outcome) = heldPool.process(utx(invalid), wus)
    outcome.isInstanceOf[ProcessingOutcome.Invalidated] shouldBe true
    result.isInvalidated(invalid.id) shouldBe true
    outcome.validationWork.get.map(_.transaction.id) shouldBe Seq(invalid.id)
    outcome.validationWork.get.head.error.nonEmpty shouldBe true
    result.process(utx(invalid), wus)._2.validationWork.get shouldBe empty
  }

  it should "retry a held loser when its winner leaves the mempool" in {
    val (cfg, wus) = fixture()
    val (heldPool, held) = heldPair(cfg, wus)
    val winner = heldPool.getAll.head
    val (result, outcome) = heldPool.invalidate(winner).retryStaging(wus)
    result.contains(held.id) shouldBe true
    outcome.admitted.map(_.id) shouldBe Seq(held.id)
    // Same-tip validation is cached; admission must not charge it a second time.
    outcome.validationWork.get shouldBe empty
  }

}
