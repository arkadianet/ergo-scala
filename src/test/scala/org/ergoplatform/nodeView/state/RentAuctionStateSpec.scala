package org.ergoplatform.nodeView.state

import java.nio.file.Files

import org.ergoplatform.ErgoBox
import org.ergoplatform.mining.CandidateGenerator
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.history.popow.NipopowAlgos
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants
import org.ergoplatform.utils.generators.ErgoCoreGenerators.defaultHeaderGen
import scorex.crypto.hash.Blake2b256
import scorex.db.ByteArrayWrapper

import scala.collection.immutable.SortedMap

/** Persistent state tests start from a synthetic aged UTXO snapshot, using fake PoW. */
class RentAuctionStateSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  private def settings(active: Boolean = true): ErgoSettings =
    ErgoNodeTestConstants.settings.copy(
      chainSettings = chain.copy(rentAuctionActivationHeight =
        if (active) Some(height) else None),
      nodeSettings = ErgoNodeTestConstants.settings.nodeSettings.copy(
        checkpoint = None, verifyTransactions = true))

  private def state(boxes: IndexedSeq[ErgoBox], s: ErgoSettings): UtxoState = {
    val holder = new BoxHolder(SortedMap(boxes.map(b => ByteArrayWrapper(b.id) -> b): _*))
    UtxoState.fromBoxHolder(holder, None,
      Files.createTempDirectory("rent-utxo-").toFile, s, params)
  }

  private def block(
    us: UtxoState,
    txs: Seq[ErgoTransaction],
    rent: ExtensionCandidate
  ): ErgoFullBlock = {
    val sc = us.stateContext
    val parent = sc.lastHeaderOpt.orElse(Some(
      defaultHeaderGen.sample.get.copy(height = height - 1)))
    val algorithms = new NipopowAlgos(chain)
    val interlinks = algorithms.interlinksToExtension(
      algorithms.updateInterlinks(sc.lastHeaderOpt, sc.lastExtensionOpt))
    val extension = params.toExtensionCandidate ++
      sc.validationSettings.toExtensionCandidate ++ interlinks ++ rent
    val (proof, digest) = us.proofsForTransactions(txs).get
    chain.powScheme.proveBlock(parent, Header.Interpreter60Version,
      chain.initialNBits, digest, proof, txs, defaults.defaultTimestamp,
      extension, Array.fill(3)(0.toByte), defaults.defaultMinerSecretNumber).get
  }

  property("UTXO and digest state enforce claims, persist, roll back and reapply") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val funding = box(30000000L)
    val plan = new RentAuctionTransactions(contracts, params, height)
      .collect(IndexedSeq(source), IndexedSeq(funding), owner, anyone, 1000000L).get
    val s = settings()
    val us = state(plan.boxes, s)
    val digestDirectory = Files.createTempDirectory("rent-digest-").toFile
    val ds = DigestState.recover(us.version, us.rootDigest, us.stateContext,
      digestDirectory, s).get
    val rules = new RentAuctionRules(contracts, params)
    val ext = rules.extension(Seq(plan.transaction -> plan.boxes), height,
      Blake2b256(owner.bytes))
    val valid = block(us, Seq(plan.transaction), ext)
    val invalid = block(us, Seq(plan.transaction), ExtensionCandidate(Seq.empty))
    try {
      us.applyModifier(invalid, None)(_ => ()).isFailure shouldBe true
      ds.applyModifier(invalid, None)(_ => ()).isFailure shouldBe true
      val nextU = us.applyModifier(valid, None)(_ => ()).get
      val nextD = ds.applyModifier(valid, None)(_ => ()).get
      nextU.rootDigest.toSeq shouldBe nextD.rootDigest.toSeq
      nextU.boxById(source.id) shouldBe None
      nextU.boxById(plan.transaction.outputs.head.id).isDefined shouldBe true
      val restoredU = nextU.rollbackTo(us.version).get
      val restoredD = nextD.rollbackTo(ds.version).get
      restoredU.boxById(source.id).isDefined shouldBe true
      val reappliedU = restoredU.applyModifier(valid, None)(_ => ()).get
      val reappliedD = restoredD.applyModifier(valid, None)(_ => ()).get
      reappliedU.rootDigest.toSeq shouldBe reappliedD.rootDigest.toSeq
      info(s"Persistent collection: ${plan.transaction.size} serialized bytes")
    } finally {
      us.store.close()
      ds.close()
    }
    val reopenedD = DigestState.create(None, None, digestDirectory, s)
    try reopenedD.stateContext.currentHeight shouldBe height
    finally reopenedD.close()
  }

  property("mempool policy rejects rent; candidate path pays a separate beneficiary") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val plan = new RentAuctionTransactions(contracts, params, height)
      .collect(IndexedSeq(source), IndexedSeq(box(30000000L)), owner,
        anyone, 1000000L).get
    val base = state(plan.boxes, settings())
    val us = new UtxoState(base.persistentProver, base.version, base.store, settings()) {
      override def stateContext: ErgoStateContext = new ErgoStateContext(
        Seq(defaultHeaderGen.sample.get.copy(height = height - 1)), None,
        defaults.genesisStateDigest, params, defaults.validationSettings,
        VotingData.empty)(settings().chainSettings)
    }
    val sc = context(height).copy()(settings().chainSettings)
    val beneficiary = Some(Blake2b256(owner.bytes))
    try {
      us.validateWithCost(plan.transaction, sc, params.maxBlockCost, None)
        .isFailure shouldBe true
      val cost = us.validateWithCost(plan.transaction, sc, params.maxBlockCost,
        None, beneficiary, allowRent = true).get
      us.validateWithCost(plan.transaction, sc, cost - 1,
        None, beneficiary, allowRent = true).isFailure shouldBe true
      val selected = CandidateGenerator.collectTxs(defaults.defaultMinerPk,
        params.maxBlockCost, params.maxBlockSize, us, sc,
        Seq(plan.transaction), beneficiary)
      selected._1.map(_.id) should contain(plan.transaction.id)
      selected._2 shouldBe empty
      val wrong = CandidateGenerator.collectTxs(defaults.defaultMinerPk,
        params.maxBlockCost, params.maxBlockSize, us, sc,
        Seq(plan.transaction), Some(Blake2b256(nobody.bytes)))
      wrong._1.map(_.id) should not contain plan.transaction.id
      chain.rentAuctionsActive(height) shouldBe false
      settings().chainSettings.rentAuctionsActive(height - 1) shouldBe false
      settings().chainSettings.rentAuctionsActive(height) shouldBe true
    } finally us.store.close()
  }
}
