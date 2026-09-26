package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.Parameters
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.scalacheck.Gen
import scorex.crypto.hash.Blake2b256
import sigma.Coll

import scala.util.Try

class RentAuctionParameterSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  private val maximumDust = new Parameters(0,
    params.parametersTable.updated(
      Parameters.MinValuePerByteIncrease, Parameters.MinValueMax),
    defaults.emptyVSUpdate)

  private def validateMaximumDust(spend: Spend): Try[Int] =
    spend.tx.statelessValidity().flatMap { _ =>
      spend.tx.statefulValidity(spend.boxes, IndexedSeq.empty,
        context(spend.at).copy(currentParameters = maximumDust)(chain))(
        ErgoInterpreter(maximumDust))
    }

  property("larger separately funded seed supports the maximum voted dust parameter") {
    val seed = 50000000L
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val sponsor = box(seed + 1000000L)
    val inputs = IndexedSeq(source, sponsor)
    val outs = IndexedSeq(
      output(seed, contracts.auction, tokens = Seq(token -> 100L),
        registers = lotRegisters(source.id, height + WINDOW,
          height + MAXIMUM_WINDOW, seed = seed)),
      output(2000000L, owner)
    )
    val tx = transaction(inputs, outs, Map(0 -> 0.toShort))
    validateMaximumDust(Spend(tx, inputs, height)).get should be > 0
    new RentAuctionRules(contracts, maximumDust)
      .validate(tx, inputs, height, Some(Blake2b256(owner.bytes))) shouldBe Right(())
    val bid = bidSpend(tx.outputs.head, MINIMUM_BID)
    validateMaximumDust(bid).get should be > 0
    val settlement = settleSpend(bid.tx.outputs.head, height + WINDOW)
    val initial = settlement.tx.outputCandidates
    // Add dust support from the seed; the reserve also receives this donation.
    val extra = 10000000L
    val funded = settlement.withOutputs(initial
      .updated(1, change(initial(1), initial(1).value + extra, contracts.deposit))
      .updated(2, change(initial(2), initial(2).value - extra, contracts.fee)))
    validateMaximumDust(funded).get should be > 0
    val merge = mergeSpend(IndexedSeq(funded.tx.outputs(1)), funded.at)
    val feeSponsor = box(2000000L, created = funded.at)
    val mergeInputs = merge.boxes :+ feeSponsor
    val mergeOutputs = merge.tx.outputCandidates.updated(1,
      change(merge.tx.outputCandidates(1),
        MERGE_BUDGET + feeSponsor.value, contracts.fee))
    val supported = Spend(transaction(mergeInputs, mergeOutputs), mergeInputs, funded.at)
    validateMaximumDust(supported).get should be > 0
    mergeOutputs.head.value - merge.boxes.head.value shouldBe MINIMUM_BID + extra
  }

  property("bidding near the maximum Int height cannot wrap the extension deadline") {
    val at = Int.MaxValue - 1
    val b = lot(end = Int.MaxValue, cap = Int.MaxValue,
      created = Int.MaxValue - MAXIMUM_WINDOW)
    val bid = bidSpend(b, MINIMUM_BID, at, Int.MaxValue, Int.MaxValue)
    bid.result.get should be > 0
    settleSpend(bid.tx.outputs.head, Int.MaxValue).result.get should be > 0
  }

  property("random funded bids preserve tokens and transfer their full principal") {
    forAll(Gen.choose(0, WINDOW - 1), Gen.choose(MINIMUM_BID, 1000000000L)) {
      (offset, amount) =>
        val bid = bidSpend(lot(), amount, height + offset, recipient = owner)
        bid.result.get should be > 0
        val end = bid.tx.outputs.head.additionalRegisters(org.ergoplatform.ErgoBox.R5)
          .value.asInstanceOf[Coll[Int]](0)
        val settle = settleSpend(bid.tx.outputs.head, end)
        settle.result.get should be > 0
        val merge = mergeSpend(IndexedSeq(settle.tx.outputs(1)), end)
        merge.result.get should be > 0
        settle.tx.outputs.head.additionalTokens shouldBe bid.boxes.head.additionalTokens
        merge.tx.outputs.head.value - merge.boxes.head.value shouldBe amount
    }
  }

  property("non-empty proofs do not classify as rent even when variable 127 is present") {
    val b = box(1000000L, anyone, height - Constants.StoragePeriod)
    val in = rentInput(b, 0)
    val signed = org.ergoplatform.Input(b.id,
      sigma.interpreter.ProverResult(Array(1.toByte), in.spendingProof.extension))
    new RentAuctionRules(contracts, params).classify(b, signed, 1, height) shouldBe None
  }
}
