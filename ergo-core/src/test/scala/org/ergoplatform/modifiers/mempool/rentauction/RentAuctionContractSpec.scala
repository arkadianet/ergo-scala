package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ByteArrayConstant
import sigma.ast.IntArrayConstant
import sigma.ast.IntConstant

class RentAuctionContractSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.CARRIER
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  private def rejects(spend: Spend): Unit = {
    spend.tx.statelessValidity().isSuccess shouldBe true
    spend.result.isFailure shouldBe true
  }

  property("compile both contracts with the Scala Sigma compiler") {
    contracts.auction.bytes.length should be < 4096
    contracts.deposit.bytes.length should be < 4096
    Base16.encode(Blake2b256(contracts.auction.bytes)) shouldBe
      "2d8fa0b8de876e355140f9e1869ef896fa1c7f97004e54a5998eb711b4d61d3d"
    Base16.encode(Blake2b256(contracts.deposit.bytes)) shouldBe
      "11291b4314346bd6891da123de61bd61c7cdd5e078d34a884a888918cc5570cc"
    info(s"Auction tree: ${contracts.auction.bytes.length} bytes")
    info(s"Deposit tree: ${contracts.deposit.bytes.length} bytes")
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

  property("settlement is permissionless at the deadline and pays the complete bid") {
    val end = height + WINDOW
    val b = lot(100000000L, recipient = owner)
    rejects(settleSpend(b, end - 1))
    val spend = settleSpend(b, end)
    spend.result.get should be > 0
    spend.tx.outputCandidates(1).value - MERGE_BUDGET shouldBe 100000000L
    info(s"Settlement transaction cost: ${spend.result.get}")
  }

  property("settlement rejects a stolen winner output or legacy proceeds destination") {
    val spend = settleSpend(lot(100000000L, recipient = owner), height + WINDOW)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs.updated(0, change(outs(0), CARRIER, anyone))))
    rejects(spend.withOutputs(outs.updated(1,
      change(outs(1), outs(1).value, contracts.legacyDeposit))))
  }

  property("settlement cannot deduct proceeds or substitute another lot's payment") {
    val spend = settleSpend(lot(100000000L), height + WINDOW)
    val outs = spend.tx.outputCandidates
    rejects(spend.withOutputs(outs
      .updated(1, change(outs(1), outs(1).value - 1L, contracts.deposit))
      .updated(2, change(outs(2), outs(2).value + 1L, contracts.fee))))
    val wrong = output(outs(1).value, contracts.deposit, spend.at,
      registers = tag(Array.fill(32)(1.toByte)))
    rejects(spend.withOutputs(outs.updated(1, wrong)))
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
    rejects(burnSpend(lot(MINIMUM_BID), height + WINDOW))
  }

  property("two auction inputs cannot share a single refund or settlement") {
    val b = lot(MINIMUM_BID)
    val other = lot(MINIMUM_BID)
    val spend = settleSpend(b, height + WINDOW)
    val outs = spend.tx.outputCandidates
    val inputs = IndexedSeq(b, other)
    val tx = transaction(inputs, outs.updated(2,
      change(outs(2), outs(2).value + other.value, contracts.fee)))
    rejects(Spend(tx, inputs, spend.at))
  }

  property("malformed register types are rejected by the compiled contract") {
    val spend = bidSpend(lot(), MINIMUM_BID)
    val next = spend.tx.outputCandidates.head
    val malformed = new ErgoBoxCandidate(next.value, next.ergoTree, height,
      next.additionalTokens, next.additionalRegisters.updated(ErgoBox.R6, IntConstant(5)))
    rejects(spend.withOutputs(spend.tx.outputCandidates.updated(0, malformed)))
  }
}
