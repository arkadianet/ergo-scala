package org.ergoplatform.modifiers.mempool

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionContracts
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.nodeView.state.ErgoState
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

import scala.util.Try

/**
  * Runs production block transaction validation and state-change calculation.
  * The activated production extension/transaction checks run inside the executor.
  * Does not exercise PoW, history, mempool or persistent state application.
  */
class RentAuctionBlockSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  private lazy val rules = new RentAuctionRules(contracts, params)

  private def executeBlockTransactions(
    txs: Seq[ErgoTransaction],
    initial: IndexedSeq[ErgoBox],
    at: Int,
    extension: ExtensionCandidate,
    activated: Boolean = true
  ): Try[Long] = Try {
    ErgoState.boxChanges(txs).get
    var live = initial.map(b => Base16.encode(b.id) -> b).toMap
    txs.foreach { tx =>
      tx.inputs.foreach(i => live.contains(Base16.encode(i.boxId)) shouldBe true)
      live = (live -- tx.inputs.map(i => Base16.encode(i.boxId))) ++
        tx.outputs.map(b => Base16.encode(b.id) -> b)
    }
    val settings = chain.copy(rentAuctionActivationHeight =
      if (activated) Some(1) else None)
    val activeContext = context(at).copy()(settings)
    val lookup = (initial ++ txs.flatMap(_.outputs))
      .map(b => Base16.encode(b.id) -> b).toMap
    val nodeSettings = ErgoNodeTestConstants.settings.nodeSettings.copy(checkpoint = None)
    ErgoState.execTransactions(txs, activeContext, nodeSettings, Some(extension)) { id =>
      Try(lookup(Base16.encode(id)))
    }.toTry.get
  }

  property("collection and consecutive bids share a block at arbitrary positions") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> 100L))
    val sponsor = box(RentAuctionContracts.SEED)
    val initialLot = output(RentAuctionContracts.SEED, contracts.auction,
      tokens = Seq(token -> 100L), registers = lotRegisters(source.id,
        height + RentAuctionContracts.WINDOW,
        height + RentAuctionContracts.MAXIMUM_WINDOW))
    val collect = transaction(IndexedSeq(source, sponsor),
      IndexedSeq(initialLot, output(source.value, owner)), Map(0 -> 0.toShort))
    val first = bidSpend(collect.outputs.head, RentAuctionContracts.MINIMUM_BID)
    val second = bidSpend(first.tx.outputs.head,
      RentAuctionContracts.MINIMUM_BID + RentAuctionContracts.INCREMENT)
    val plainBox = box(1000000L)
    val plain = transaction(IndexedSeq(plainBox), IndexedSeq(output(plainBox.value)))
    val transactions = Seq(plain, collect, first.tx, second.tx)
    val initial = IndexedSeq(plainBox, source, sponsor, first.boxes(1), second.boxes(1))
    val claimHash = rules.attestation(
      Seq(collect -> IndexedSeq(source, sponsor)), height).get
    val extension = ExtensionCandidate(Seq(
      RentAuctionRules.ATTESTATION_KEY -> claimHash,
      RentAuctionRules.BENEFICIARY_KEY -> Blake2b256(owner.bytes)
    ))
    executeBlockTransactions(transactions, initial, height, extension).get should be > 0L
    executeBlockTransactions(transactions, initial, height, ExtensionCandidate(Seq.empty))
      .isFailure shouldBe true
    executeBlockTransactions(transactions, initial, height,
      ExtensionCandidate(Seq.empty), activated = false)
      .get should be > 0L
    executeBlockTransactions(
      Seq(collect, second.tx, first.tx), initial, height, extension)
      .isFailure shouldBe true
    executeBlockTransactions(transactions :+ first.tx, initial, height, extension)
      .isFailure shouldBe true
  }

  property("settlement and reserve merge execute in one block with net state changes") {
    val b = lot(100000000L)
    val settlement = settleSpend(b, height + RentAuctionContracts.WINDOW)
    val merge = mergeSpend(IndexedSeq(settlement.tx.outputs(1)), settlement.at)
    val txs = Seq(settlement.tx, merge.tx)
    val initial = IndexedSeq(b, merge.boxes.head)
    val cost = executeBlockTransactions(
      txs, initial, settlement.at, ExtensionCandidate(Seq.empty)).get
    cost should be <= params.maxBlockCost.toLong
    val changes = ErgoState.boxChanges(txs).get
    changes._1.size shouldBe 2
    // The transient proceeds deposit never enters the final live UTXO set.
    ErgoState.newBoxes(txs).exists(_.ergoTree == contracts.deposit) shouldBe false
    info(s"Settlement plus merge block transaction cost: $cost")
  }

  property("unsold-token cleanup removes token-bearing state") {
    val b = lot()
    val burn = burnSpend(b, height + RentAuctionContracts.WINDOW)
    executeBlockTransactions(
      Seq(burn.tx), IndexedSeq(b), burn.at, ExtensionCandidate(Seq.empty))
      .get should be > 0L
    val live = ErgoState.newBoxes(Seq(burn.tx))
    live.flatMap(_.additionalTokens.toArray) shouldBe empty
    live.exists(_.ergoTree == contracts.auction) shouldBe false
  }

  property("a scheduled reserve reward precedes its auction-proceeds merge") {
    val reserve = box(1000000000000L, contracts.reserve, height - 1,
      Seq(nft -> 1L))
    val deposit = depositBox(100000000L, height - 1)
    val reward = chain.reemission.reemissionRules.reemissionRewardPerBlock
    val miner = ErgoTreePredef.rewardOutputScript(
      chain.monetary.minerRewardDelay, defaults.defaultMinerPk)
    def withdraw(b: ErgoBox): ErgoTransaction = transaction(IndexedSeq(b),
      IndexedSeq(output(b.value - reward, contracts.reserve, tokens = Seq(nft -> 1L)),
        output(reward, miner)))
    val builder = new RentAuctionTransactions(contracts, params, height)
    val rewardTx = withdraw(reserve)
    val merge = builder.merge(rewardTx.outputs.head, IndexedSeq(deposit)).get
    executeBlockTransactions(Seq(rewardTx, merge.transaction),
      IndexedSeq(reserve, deposit), height, ExtensionCandidate(Seq.empty))
      .get should be > 0L
    merge.transaction.outputs.head.value shouldBe
      reserve.value - reward + deposit.value - RentAuctionContracts.MERGE_BUDGET
    val mergeFirst = builder.merge(reserve, IndexedSeq(deposit)).get
    executeBlockTransactions(Seq(mergeFirst.transaction,
      withdraw(mergeFirst.transaction.outputs.head)), IndexedSeq(reserve, deposit),
      height, ExtensionCandidate(Seq.empty)).isFailure shouldBe true
  }
}
