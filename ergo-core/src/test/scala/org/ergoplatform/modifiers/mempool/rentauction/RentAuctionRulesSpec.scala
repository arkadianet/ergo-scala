package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.crypto.hash.Blake2b256

/** Supporting rule-level diagnostics; activated executor evidence lives in the root suite. */
class RentAuctionRulesSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  private lazy val rules = new RentAuctionRules(contracts, params, validation)

  property("bundling same-id sources preserves their aggregate and baseline rent witnesses") {
    val sources = (1 to 20).map(_ => box(1000000L, nobody,
      height - Constants.StoragePeriod, Seq(token -> 100L))).toIndexedSeq
    val plan = new RentAuctionTransactions(contracts, params, height, validation)
      .collect(sources, IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L).get
    validate(plan.transaction, plan.boxes).get should be > 0
    plan.transaction.outputs.count(_.ergoTree == contracts.auction) shouldBe 1
    plan.transaction.outputs.head.additionalTokens(0)._2 shouldBe 2000L
    rules.validate(plan.transaction, plan.boxes, height, Some(Blake2b256(owner.bytes))) shouldBe Right(())
    plan.transaction.inputs.take(20).map(_.spendingProof.extension).distinct.size shouldBe 1
  }

  property("attestation still rejects missing, duplicate, reordered and extraneous fields") {
    def collection(): RentAuctionPlan = {
      val source = box(1000000L, nobody, height - Constants.StoragePeriod, Seq(token -> 1L))
      new RentAuctionTransactions(contracts, params, height, validation)
        .collect(IndexedSeq(source), IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L).get
    }
    val a = collection()
    val b = collection()
    val txs = Seq(a.transaction -> a.boxes, b.transaction -> b.boxes)
    val ext = rules.extension(txs, height, Blake2b256(owner.bytes))
    rules.validateExtension(txs, height, ext).isRight shouldBe true
    val wrong = Seq(ExtensionCandidate(Seq.empty),
      ExtensionCandidate(ext.fields :+ ext.fields.head),
      rules.extension(txs.reverse, height, Blake2b256(owner.bytes)))
    wrong.foreach(e => rules.validateExtension(txs, height, e) shouldBe
      Left("missing, duplicated or incorrect rent extension fields"))
    rules.validateExtension(Seq.empty, height, ext) shouldBe
      Left("missing, duplicated or incorrect rent extension fields")
  }
}
