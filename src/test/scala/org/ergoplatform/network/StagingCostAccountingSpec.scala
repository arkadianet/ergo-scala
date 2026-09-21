package org.ergoplatform.network

import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.network.ErgoNodeViewSynchronizer.IncomingTxInfo
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.settings.Constants.TrueTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StagingCostAccountingSpec extends AnyFlatSpec with Matchers {
  it should "charge work for accepted, evicted and invalid transactions, then charge zero for announcements" in {
    val tx = UnconfirmedTransaction(ErgoTransaction(IndexedSeq.empty,
      IndexedSeq(new org.ergoplatform.ErgoBoxCandidate(1000000L, TrueTree, 0))), None).withCost(700)
    val validationResults = Seq[InitialTransactionCheckOutcome](
      SuccessfulTransaction(tx, Some(700)), // survives admission
      DeclinedTransaction(tx, Some(600)), // validated, subsequently evicted
      FailedTransaction(tx, new Exception("invalid"), Some(1000000)))
    val charged = validationResults.foldLeft(IncomingTxInfo.empty()) { (costs, result) =>
      costs.record(result, result.validationCost.orElse(result.transaction.lastCost).get)
    }
    charged.acceptedCost shouldBe 700
    charged.declinedCost shouldBe 600
    charged.invalidatedCost shouldBe 1000000
    val announcements = Seq[InitialTransactionCheckOutcome](
      SuccessfulTransaction(tx, Some(0)), DeclinedTransaction(tx, Some(0)), FailedTransaction(tx, new Exception, Some(0)))
    announcements.foldLeft(charged) { (costs, result) =>
      costs.record(result, result.validationCost.orElse(result.transaction.lastCost).get)
    }.totalCost shouldBe 1001300
    val legacy = SuccessfulTransaction(tx)
    legacy.validationCost.orElse(legacy.transaction.lastCost) shouldBe Some(700)
  }
}
