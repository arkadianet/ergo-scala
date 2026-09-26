package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.utils.ErgoCorePropertyTest

class RentAuctionDepositSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  property("current pay-to-reemission deposits can be swept with the scheduled reward") {
    val reserve = box(1000000000000L, contracts.reserve, height - 1, Seq(nft -> 1L))
    val oldDeposit = box(100000000L, contracts.legacyDeposit)
    val reward = chain.reemission.reemissionRules.reemissionRewardPerBlock
    val miner = ErgoTreePredef.rewardOutputScript(
      chain.monetary.minerRewardDelay, defaults.defaultMinerPk
    )
    val inputs = IndexedSeq(reserve, oldDeposit)
    val tx = transaction(inputs, IndexedSeq(
      output(reserve.value - reward, contracts.reserve, tokens = Seq(nft -> 1L)),
      output(reward + oldDeposit.value, miner)
    ))
    validate(tx, inputs).get should be > 0
    tx.outputCandidates.head.value shouldBe reserve.value - reward
  }

  property("new proceeds increase the real reserve and pay fees from separate funding") {
    val spend = mergeSpend(IndexedSeq(depositBox(100000000L)))
    spend.result.get should be > 0
    spend.tx.outputCandidates.head.value - spend.boxes.head.value shouldBe 100000000L
    spend.tx.outputCandidates(1).value shouldBe MERGE_BUDGET
    info(s"Deposit-merge transaction cost: ${spend.result.get}")
  }

  property("deposits merge before and after the mainnet re-emission start height") {
    Seq(2080799, 2080800, 2080801).foreach { h =>
      mergeSpend(IndexedSeq(depositBox(MINIMUM_BID, h)), h).result.get should be > 0
    }
  }

  property("batching ten deposits credits the sum of all principals") {
    val deposits = (1 to 10)
      .map(i => depositBox(MINIMUM_BID + i * INCREMENT)).toIndexedSeq
    val spend = mergeSpend(deposits)
    spend.result.get should be > 0
    spend.tx.outputCandidates.head.value - spend.boxes.head.value shouldBe
      deposits.map(_.value - MERGE_BUDGET).sum
    info(s"Ten-deposit merge cost: ${spend.result.get}")
    mergeSpend(deposits :+ depositBox(MINIMUM_BID)).result.isFailure shouldBe true
  }

  property("two equal deposits cannot count the same reserve increase twice") {
    val spend = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID), depositBox(MINIMUM_BID)))
    val outs = spend.tx.outputCandidates
    val attack = spend.withOutputs(outs
      .updated(0, change(outs.head, outs.head.value - MINIMUM_BID, contracts.reserve))
      .updated(1, change(outs(1), outs(1).value + MINIMUM_BID, contracts.fee)))
    attack.result.isFailure shouldBe true
  }

  property("the reward-withdrawal path cannot sweep a new deposit") {
    val spend = mergeSpend(IndexedSeq(depositBox(100000000L)))
    val reserve = spend.boxes.head
    val deposit = spend.boxes(1)
    val reward = chain.reemission.reemissionRules.reemissionRewardPerBlock
    val miner = ErgoTreePredef.rewardOutputScript(
      chain.monetary.minerRewardDelay, defaults.defaultMinerPk
    )
    val attack = spend.withOutputs(IndexedSeq(
      output(reserve.value - reward, contracts.reserve, tokens = Seq(nft -> 1L)),
      output(reward + deposit.value, miner)
    ))
    attack.result.isFailure shouldBe true
  }

  property("fees cannot be increased by subtracting from the bid principal") {
    val spend = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID)))
    val outs = spend.tx.outputCandidates
    spend.withOutputs(outs
      .updated(0, change(outs.head, outs.head.value - 1L, contracts.reserve))
      .updated(1, change(outs(1), outs(1).value + 1L, contracts.fee)))
      .result.isFailure shouldBe true
  }

  property("a counterfeited reserve script cannot receive auction proceeds") {
    val spend = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID)))
    val counterfeit = box(spend.boxes.head.value, anyone, height - 1, Seq(nft -> 1L))
    val inputs = IndexedSeq(counterfeit, spend.boxes(1))
    validate(transaction(inputs, spend.tx.outputCandidates), inputs)
      .isFailure shouldBe true
    val outs = spend.tx.outputCandidates
    spend.withOutputs(outs.updated(0, change(outs.head, outs.head.value, anyone)))
      .result.isFailure shouldBe true
  }

  property("settlement and deposit merge execute as a connected transaction chain") {
    val bid = bidSpend(lot(), 100000000L)
    bid.result.get should be > 0
    val settle = settleSpend(bid.tx.outputs.head, height + WINDOW)
    settle.result.get should be > 0
    val merge = mergeSpend(IndexedSeq(settle.tx.outputs(1)), settle.at)
    merge.result.get should be > 0
    merge.tx.outputCandidates.head.value - merge.boxes.head.value shouldBe 100000000L
  }

  property("unrelated tokens in the authentic reserve do not prevent merging") {
    val spend = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID)))
    val reserve = box(spend.boxes.head.value, contracts.reserve, height - 1,
      Seq(nft -> 1L, token -> 100L))
    val inputs = spend.boxes.updated(0, reserve)
    val tx = transaction(inputs, spend.tx.outputCandidates)
    validate(tx, inputs).get should be > 0
    tx.outputs.head.additionalTokens.toArray.toSeq shouldBe Seq(nft -> 1L)
    tx.outputs.head.value - reserve.value shouldBe MINIMUM_BID
  }
}
