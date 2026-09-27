package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ByteArrayConstant
import sigma.ast.IntArrayConstant
import sigma.ast.IntConstant
import sigma.interpreter.ContextExtension
import sigma.interpreter.ProverResult

class RentAuctionContractSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  private def rejects(spend: Spend): Unit = {
    spend.tx.statelessValidity().isSuccess shouldBe true
    nativeRejected(spend, "Scripts of all transaction inputs should pass verification")
  }

  property("compile both contracts with the Scala Sigma compiler") {
    contracts.auction.bytes.length should be < 4096
    contracts.deposit.bytes.length should be < 4096
    Seq("auction" -> contracts.auction, "deposit" -> contracts.deposit).foreach { case (name, tree) =>
      (tree.header & 7) shouldBe 0
      info(s"TREE $name bytes=${tree.bytes.length} blake2b256=${Base16.encode(Blake2b256(tree.bytes))}")
    }
    Base16.encode(Blake2b256(contracts.auction.bytes)) shouldBe
      "3643df8a7e682486e5ffa7b3a113a65b18c3f65320856c3e32616afaf2c8ce09"
    Base16.encode(Blake2b256(contracts.deposit.bytes)) shouldBe
      "1cb94a993884ef836418ee714efa8169d23adc2bdca8a2dcd8c71c57f91ba845"
    chain.isMainnet shouldBe true
    chain.reemission.checkReemissionRules shouldBe true
    params.blockVersion shouldBe 4
  }

  property("ordinary token-free funding passes full node transaction validation") {
    val funding = box(10000000L)
    val tx = transaction(IndexedSeq(funding), IndexedSeq(output(funding.value)))
    validate(tx, IndexedSeq(funding)).get should be > 0
  }

  property("first funded bid and higher bid refund the previous recipient") {
    val first = bidSpend(lot(), MINIMUM_BID, recipient = owner)
    first.result.get should be > 0
    val second = bidSpend(first.tx.outputs.head, MINIMUM_BID + INCREMENT)
    second.result.get should be > 0
    second.tx.outputCandidates(1).ergoTree shouldBe owner
    second.tx.outputCandidates(1).value shouldBe MINIMUM_BID
    info(s"Higher-bid transaction cost: ${second.result.get}")
  }

  property("equal, lower, sub-increment and below-reserve bids fail") {
    val funded = lot(MINIMUM_BID + INCREMENT)
    Seq(MINIMUM_BID, MINIMUM_BID + INCREMENT, MINIMUM_BID + INCREMENT + 1L)
      .foreach(amount => rejects(bidSpend(funded, amount)))
    rejects(bidSpend(lot(), MINIMUM_BID - 1L))
  }

  property("a missing or redirected refund is rejected") {
    val spend = bidSpend(lot(MINIMUM_BID, recipient = owner), MINIMUM_BID + INCREMENT)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs.updated(1, change(outs(1), MINIMUM_BID, anyone))))
    rejects(spend.withOutputs(IndexedSeq(
      outs.head, change(outs.last, outs.last.value + MINIMUM_BID, contracts.fee)
    )))
  }

  property("under-refunding and refund provenance substitution fail") {
    val spend = bidSpend(lot(MINIMUM_BID), MINIMUM_BID + INCREMENT)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs
      .updated(1, change(outs(1), MINIMUM_BID - 1L, anyone))
      .updated(2, change(outs(2), outs(2).value + 1L, contracts.fee))))
    val wrongTag = output(MINIMUM_BID, anyone, registers = tag(Array.fill(32)(1.toByte)))
    rejects(spend.withOutputs(outs.updated(1, wrongTag)))
  }

  property("successor cannot change the lot, tokens, script or amount accounting") {
    val spend = bidSpend(lot(), MINIMUM_BID)
    val outs = spend.tx.outputCandidates
    val next = outs.head
    val wrongLot = new ErgoBoxCandidate(
      next.value, next.ergoTree, height, next.additionalTokens,
      next.additionalRegisters.updated(ErgoBox.R4,
        ByteArrayConstant(Array.fill(32)(1.toByte)))
    )
    rejects(spend.withOutputs(outs.updated(0, wrongLot)))
    rejects(spend.withOutputs(outs.updated(0, change(next, next.value, anyone))))
    val fewer = output(next.value, contracts.auction, tokens = Seq(token -> 99L),
      registers = next.additionalRegisters)
    rejects(spend.withOutputs(outs.updated(0, fewer)))
    val underfunded = change(next, next.value - 1L, next.ergoTree)
    rejects(spend.withOutputs(outs.updated(0, underfunded)
      .updated(1, change(outs(1), outs(1).value + 1L, contracts.fee))))
  }

  property("deadline extension is enforced and capped") {
    val end = height + WINDOW
    val cap = height + MAXIMUM_WINDOW
    val late = bidSpend(lot(), MINIMUM_BID, at = end - 1)
    late.result.get should be > 0
    val wrong = new ErgoBoxCandidate(
      late.tx.outputCandidates.head.value, contracts.auction, end - 1,
      late.tx.outputCandidates.head.additionalTokens,
      late.tx.outputCandidates.head.additionalRegisters.updated(
        ErgoBox.R5, IntArrayConstant(Array(end, cap))
      )
    )
    rejects(late.withOutputs(late.tx.outputCandidates.updated(0, wrong)))
    bidSpend(lot(end = cap, cap = cap), MINIMUM_BID,
      at = cap - 1, end = cap, cap = cap).result.get should be > 0
    rejects(bidSpend(lot(), MINIMUM_BID, at = end))
  }

  property("settlement returns backing and deducts only designed costs from the bid") {
    val end = height + WINDOW
    val b = lot(100000000L, recipient = owner)
    rejects(settleSpend(b, end - 1))
    val spend = settleSpend(b, end)
    spend.result.get should be > 0
    spend.tx.outputCandidates(2).value - MERGE_BUDGET shouldBe
      100000000L - 10000000L - spend.tx.outputCandidates(1).value - MERGE_BUDGET
    info(s"Settlement transaction cost: ${spend.result.get}")
  }

  property("settlement rejects a stolen winner output or legacy proceeds destination") {
    val spend = settleSpend(lot(100000000L, recipient = owner), height + WINDOW)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs.updated(1, change(outs(1), outs(1).value, anyone))))
    rejects(spend.withOutputs(outs.updated(2,
      change(outs(2), outs(2).value, contracts.legacyDeposit))))
  }

  property("settlement cannot deduct proceeds or substitute another lot's payment") {
    val spend = settleSpend(lot(100000000L), height + WINDOW)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs
      .updated(2, change(outs(2), outs(2).value - 1L, contracts.deposit))
      .updated(3, change(outs(3), outs(3).value + 1L, contracts.fee))))
    val wrong = output(outs(2).value, contracts.deposit, spend.at,
      registers = tag(Array.fill(32)(1.toByte)))
    rejects(spend.withOutputs(outs.updated(2, wrong)))
  }

  property("unsold lots burn at the deadline and cannot be recreated or sold early") {
    val b = lot()
    rejects(burnSpend(b, height + WINDOW - 1))
    val burn = burnSpend(b, height + WINDOW)
    burn.result.get should be > 0
    burn.tx.outputs.flatMap(_.additionalTokens.toArray) shouldBe empty
    rejects(burn.withOutputs(IndexedSeq(output(b.value, anyone, burn.at,
      Seq(token -> 100L)))))
    rejects(burn.withOutputs(IndexedSeq(output(b.value, contracts.auction, burn.at,
      registers = b.additionalRegisters))))
    val sold = settleSpend(lot(MINIMUM_BID), height + WINDOW)
    rejects(sold.withOutputs(IndexedSeq(output(sold.boxes.head.value, contracts.fee, sold.at))))
  }

  property("two sold auction inputs cannot share settlement slots tagged for the first input") {
    val plan = new RentAuctionTransactions(contracts, params, height + WINDOW)
      .close(IndexedSeq(lot(MINIMUM_BID), lot(MINIMUM_BID)), 1000000L).get
    val spend = Spend(plan.transaction, plan.boxes, plan.height)
    spend.result.get should be > 0
    val in = spend.tx.inputs(1)
    in.spendingProof.extension.values.keySet shouldBe Set(0.toByte, 1.toByte, 2.toByte)
    val sharing = Input(in.boxId, ProverResult(in.spendingProof.proof,
      ContextExtension(in.spendingProof.extension.values.updated(0.toByte, IntConstant(0)))))
    // Only the second input's payout index changes. Prices, fees, tags and outputs stay valid.
    val tx = ErgoTransaction(spend.tx.inputs.updated(1, sharing), spend.tx.outputCandidates)
    val shared = Spend(tx, spend.boxes, spend.at)
    rejects(shared)
    // The second script evaluates to false, rather than throwing on a missing context variable.
    shared.result.failed.get.getMessage should include("#1 => Success((false,")
  }

  property("malformed register types are rejected by the compiled contract") {
    val spend = bidSpend(lot(), MINIMUM_BID)
    val next = spend.tx.outputCandidates.head
    val malformed = new ErgoBoxCandidate(next.value, next.ergoTree, height,
      next.additionalTokens, next.additionalRegisters.updated(ErgoBox.R6, IntConstant(5)))
    rejects(spend.withOutputs(spend.tx.outputCandidates.updated(0, malformed)))
  }
}
