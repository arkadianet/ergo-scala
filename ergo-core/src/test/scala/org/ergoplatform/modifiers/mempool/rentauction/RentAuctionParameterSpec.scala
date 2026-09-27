package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.utils.BoxUtils
import org.ergoplatform.utils.ErgoCorePropertyTest
import sigma.ast.ByteArrayConstant

class RentAuctionParameterSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.CLOSE_ALLOWANCE
  import RentAuctionContracts.MAX_DEPOSIT_BYTES
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  property("serializer envelopes cover maximum values, heights, indices, scripts and token amounts") {
    val rules = new RentAuctionRules(contracts, priced(10000))
    Seq(owner, recipient256).foreach { tree =>
      Seq(0, 1, 32).foreach { n =>
        val candidate = output(Long.MaxValue, tree, Int.MaxValue, tokens(n, Long.MaxValue),
          tag(Array.fill(32)(1.toByte)))
        val full = candidate.toBox(ErgoBox.allZerosModifierId, Short.MaxValue)
        full.bytes.length should be <= rules.winnerBytes(tree.bytes.length, n)
        if (n == 0) full.bytes.length should be <= rules.returnBytes(tree.bytes.length)
      }
    }
    output(Long.MaxValue, contracts.fee, Int.MaxValue)
      .toBox(ErgoBox.allZerosModifierId, Short.MaxValue).bytes.length should be <= 200
    output(Long.MaxValue, contracts.deposit, Int.MaxValue, registers = tag(new Array[Byte](32)))
      .toBox(ErgoBox.allZerosModifierId, Short.MaxValue).bytes.length should be <= MAX_DEPOSIT_BYTES
    val template = output(Long.MaxValue, contracts.auction, Int.MaxValue,
      tokens(32, Long.MaxValue), lotRegisters(new Array[Byte](32), Int.MaxValue, Int.MaxValue)
        .updated(ErgoBox.R9, ByteArrayConstant(recipient256.bytes)))
    rules.lotSizeBound(template, successor = true) should be <= ErgoBox.MaxBoxSize
  }

  property("measure complete native lifecycles at both byte prices with maximum recipient scripts") {
    Seq(360, 10000).foreach { price =>
      val p = priced(price)
      val fee = if (price == 360) 1000000L else 2000000L
      Seq(1, 32).foreach { n =>
        val collected = collectPlan(n, p)
        native(collected, p).get should be > 0
        val rules = new RentAuctionRules(contracts, p)
        val fresh = collected.transaction.outputs.head
        val builder = new RentAuctionTransactions(contracts, p, height)
        val bid = builder.bid(fresh, IndexedSeq(box(1000000000L)), MINIMUM_BID,
          recipient256, anyone, fee).get
        native(bid, p).get should be > 0
        val successor = bid.transaction.outputs.head
        val close = new RentAuctionTransactions(contracts, p, height + WINDOW)
          .close(IndexedSeq(successor), fee).get
        native(close, p).get should be > 0
        val outs = close.transaction.outputs
        val reserve = box(1000000000000L, contracts.reserve, height + WINDOW - 1, Seq(nft -> 1L))
        val sponsor = if (price == 360) None else Some(box(1000000L, created = height + WINDOW))
        val merged = new RentAuctionTransactions(contracts, p, height + WINDOW)
          .merge(reserve, IndexedSeq(outs(2)), sponsor).get
        native(merged, p).get should be > 0
        info(s"SIZE price=$price tokens=$n fresh=${fresh.bytes.length} successor256=${successor.bytes.length} " +
          s"return=${outs(0).bytes.length} winner=${outs(1).bytes.length} deposit=${outs(2).bytes.length} " +
          s"fee=${outs(3).bytes.length} seed=${fresh.value - CLOSE_ALLOWANCE}")
        fresh.bytes.length should be <= rules.lotSizeBound(fresh, successor = false)
        successor.bytes.length should be <= rules.lotSizeBound(fresh, successor = true)
        outs.foreach(b => b.value should be >= BoxUtils.minimalErgoAmount(b, p))
      }
    }
  }

  property("a vote after collection does not strand no-bid returns or minimum-bid settlement") {
    val collected = collectPlan()
    native(collected).get should be > 0
    val fresh = collected.transaction.outputs.head
    val bid = new RentAuctionTransactions(contracts, params, height)
      .bid(fresh, IndexedSeq(box(1000000000L)), MINIMUM_BID, recipient256, anyone, 1000000L).get
    native(bid).get should be > 0
    Seq(fresh, bid.transaction.outputs.head).foreach { lot =>
      val closed = new RentAuctionTransactions(contracts, priced(10000), height + WINDOW)
        .close(IndexedSeq(lot), 2000000L).get
      native(closed, priced(10000)).get should be > 0
    }
  }

  property("one-million native fee is dust at the maximum voted byte price") {
    val fund = box(1000000L)
    val tx = ErgoTransaction(IndexedSeq(org.ergoplatform.Input(fund.id, sigma.interpreter.ProverResult.empty)),
      IndexedSeq(output(fund.value, contracts.fee)))
    val failure = native(RentAuctionPlan(tx, IndexedSeq(fund), height), priced(10000)).failed.get
    failure.getMessage should include("Every output of the transaction should contain at least")
  }

  property("bidding at the maximum Int boundary retains the absolute cap") {
    val at = Int.MaxValue - 1
    val b: ErgoBox = lot(end = Int.MaxValue, cap = Int.MaxValue, created = Int.MaxValue - 1440)
    val bid = bidSpend(b, MINIMUM_BID, at, Int.MaxValue, Int.MaxValue)
    bid.result.get should be > 0
    settleSpend(bid.tx.outputs.head, Int.MaxValue).result.get should be > 0
  }
}
