package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest

class RentAuctionBuilderSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  private def builder(at: Int = height): RentAuctionTransactions =
    new RentAuctionTransactions(contracts, params, at)

  private def verify(plan: RentAuctionPlan): Unit =
    validate(plan.transaction, plan.boxes, plan.height).get should be > 0

  property("production builders execute collection, two bids, settlement and merge") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val collected = builder().collect(IndexedSeq(source),
      IndexedSeq(box(100000000L)), owner, anyone, 1000000L).get
    verify(collected)
    val first = builder().bid(collected.transaction.outputs.head,
      IndexedSeq(box(100000000L)), 10000000L, owner, anyone, 1000000L).get
    verify(first)
    val second = builder().bid(first.transaction.outputs.head,
      IndexedSeq(box(100000000L)), 20000000L, owner, anyone, 1000000L).get
    verify(second)
    second.transaction.outputs(1).value shouldBe 10000000L
    val at = height + RentAuctionContracts.WINDOW
    val settled = builder(at).settle(second.transaction.outputs.head,
      IndexedSeq.empty, anyone, 1000000L).get
    verify(settled)
    val reserve = box(1000000000000L, contracts.reserve, at - 1, Seq(nft -> 1L))
    val merged = builder(at).merge(reserve,
      IndexedSeq(settled.transaction.outputs(1))).get
    verify(merged)
    merged.transaction.outputs.head.value - reserve.value shouldBe 20000000L
  }

  property("production builders burn an unsold lot and reject an early close") {
    builder().settle(lot(), IndexedSeq.empty, anyone, 1000000L).isFailure shouldBe true
    val closed = builder(height + RentAuctionContracts.WINDOW)
      .settle(lot(), IndexedSeq.empty, anyone, 1000000L).get
    verify(closed)
    closed.transaction.outputs.flatMap(_.additionalTokens.toArray) shouldBe empty
  }

  property("builder rejects insufficient funding, stale sources and token funding") {
    val source = box(1000000L, nobody, height, Seq(token -> 100L))
    builder().collect(IndexedSeq(source), IndexedSeq(box(100000000L)),
      owner, anyone, 1000000L).isFailure shouldBe true
    builder().bid(lot(), IndexedSeq.empty, 10000000L,
      owner, anyone, 1000000L).isFailure shouldBe true
    builder().bid(lot(), IndexedSeq(box(100000000L, tokens = Seq(token -> 1L))),
      10000000L, owner, anyone, 1000000L).isFailure shouldBe true
  }

  property("funded rent and token-free fully consumed sources build valid transactions") {
    val aged = height - Constants.StoragePeriod
    val funded = box(1000000000L, owner, aged, Seq(token -> 100L))
    val empty = box(1000000L, nobody, aged)
    val plan = builder().collect(IndexedSeq(funded, empty),
      IndexedSeq(box(100000000L)), owner, anyone, 1000000L).get
    verify(plan)
    plan.transaction.outputs.head.ergoTree shouldBe owner
  }

  property("wallet signs funding while leaving the expired source proof empty") {
    val key = defaults.defaultRootSecret
    val tree = sigma.ast.ErgoTree.fromSigmaBoolean(key.publicKey.key)
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val plan = builder().collect(IndexedSeq(source),
      IndexedSeq(box(100000000L, tree)), owner, tree, 1000000L).get
    val prover = org.ergoplatform.wallet.interpreter.ErgoProvingInterpreter(key, params)
    val signed = prover.sign(plan.unsigned, plan.boxes, IndexedSeq.empty,
      context(height)).get
    val tx = org.ergoplatform.modifiers.mempool.ErgoTransaction(
      signed.inputs, signed.dataInputs, signed.outputCandidates)
    tx.inputs.head.spendingProof.proof shouldBe empty
    tx.inputs(1).spendingProof.proof should not be empty
    validate(tx, plan.boxes, height).get should be > 0
  }

  property("EIP-27 accounting tokens burn while co-held assets enter auction") {
    val debt = sigma.data.Digest32Coll @@ chain.reemission.reemissionTokenIdBytes
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(debt -> 1000000L, token -> 100L))
    val plan = builder().collect(IndexedSeq(source),
      IndexedSeq(box(100000000L)), owner, anyone, 1000000L).get
    verify(plan)
    plan.transaction.outputs.head.additionalTokens.toArray.toSeq shouldBe
      Seq(token -> 100L)
    plan.transaction.outputs.filter(_.ergoTree == contracts.legacyDeposit)
      .map(_.value).sum shouldBe 1000000L
  }
}
