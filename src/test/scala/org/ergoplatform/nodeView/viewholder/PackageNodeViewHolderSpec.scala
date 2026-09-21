package org.ergoplatform.nodeView.viewholder

import akka.testkit.TestProbe
import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{
  FailedOnRecheckTransaction, FailedTransaction, StagingValidationResult, SuccessfulTransaction
}
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.ProcessingOutcome.{Accepted, DoubleSpendingLoser, Invalidated}
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.SortingOption
import org.ergoplatform.nodeView.state.{ErgoState, StateType, UtxoState}
import org.ergoplatform.nodeView.wallet.scanning.{EqualsScanningPredicate, ScanRequest, ScanWalletInteraction}
import org.ergoplatform.settings.Constants.{FalseTree, TrueTree}
import org.ergoplatform.utils.{NodeViewTestConfig, NodeViewTestOps}
import org.ergoplatform.utils.fixtures.NodeViewFixture
import org.scalatest.flatspec.AnyFlatSpec
import sigma.ast.{ByteArrayConstant, ErgoTree, Height, IntConstant, LE}
import sigma.interpreter.ProverResult

class PackageNodeViewHolderSpec extends AnyFlatSpec with NodeViewTestOps {
  import org.ergoplatform.utils.ErgoCoreTestConstants.parameters
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  it should "scan a rescued parent before its child and publish both successes in dependency order" in {
    val base = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingEnabled = true, mempoolSorting = SortingOption.FeePerByte))
    new NodeViewFixture(cfg, parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(None, us, bh)
      applyBlock(genesis) shouldBe 'success
      val wallet = getCurrentView.vault
      val scan = ScanRequest("staging wallet regression",
        EqualsScanningPredicate(org.ergoplatform.ErgoBox.R1, ByteArrayConstant(TrueTree.bytes)),
        Some(ScanWalletInteraction.Forced), Some(true))
      await(wallet.addScan(scan)).response shouldBe 'success
      val box = ErgoState.newBoxes(genesis.transactions).find(b => b.ergoTree == TrueTree && b.value > 10000000L && b.additionalTokens.isEmpty).get
      val feeProp = cfg.chainSettings.monetary.feeProposition
      def parent(fee: Long): ErgoTransaction = ErgoTransaction(
        IndexedSeq(new Input(box.id, ProverResult.empty)),
        IndexedSeq(new ErgoBoxCandidate(fee, feeProp, box.creationHeight), new ErgoBoxCandidate(box.value - fee, TrueTree, box.creationHeight)))
      // Keep incumbent change outside the scan so this checks only the rescued family's wallet effects.
      val winner = ErgoTransaction(IndexedSeq(new Input(box.id, ProverResult.empty)),
        IndexedSeq(new ErgoBoxCandidate(200000L, feeProp, box.creationHeight),
          new ErgoBoxCandidate(box.value - 200000L, FalseTree, box.creationHeight)))
      val held = parent(100000L)
      val child = ErgoTransaction(IndexedSeq(new Input(held.outputs(1).id, ProverResult.empty)),
        IndexedSeq(new ErgoBoxCandidate(held.outputs(1).value, feeProp, box.creationHeight)))

      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(winner, None))
      val firstOutcome = expectMsgType[org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.ProcessingOutcome]
      firstOutcome match {
        case invalid: Invalidated => throw invalid.e
        case other => other.isInstanceOf[Accepted] shouldBe true
      }
      val balanceBefore = await(wallet.balancesWithUnconfirmed).walletBalance
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(held, None))
      expectMsgType[DoubleSpendingLoser]
      getPoolSize shouldBe 1

      val events = TestProbe()
      actorSystem.eventStream.subscribe(events.ref, classOf[SuccessfulTransaction])
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(child, None))
      expectMsgType[Accepted].admitted.map(_.id) shouldBe Seq(held.id, child.id)
      events.expectMsgType[SuccessfulTransaction].transaction.id shouldBe held.id
      events.expectMsgType[SuccessfulTransaction].transaction.id shouldBe child.id
      getCurrentView.pool.getAll.map(_.id).toSet shouldBe Set(held.id, child.id)
      testProbe.awaitAssert {
        await(wallet.balancesWithUnconfirmed).walletBalance shouldBe balanceBefore
        await(wallet.walletBoxes(unspentOnly = true, considerUnconfirmed = true))
          .exists(_.trackedBox.box.id.sameElements(held.outputs(1).id)) shouldBe false
      }
    }
  }
  it should "charge a held transaction recheck after a block without blaming its original sender" in {
    val base = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    val cfg = base.copy(nodeSettings = base.nodeSettings.copy(stagingEnabled = true, mempoolSorting = SortingOption.FeePerByte))
    new NodeViewFixture(cfg, parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(None, us, bh)
      applyBlock(genesis) shouldBe 'success
      val box = ErgoState.newBoxes(genesis.transactions)
        .find(b => b.ergoTree == TrueTree && b.value > 10000000L && b.additionalTokens.isEmpty).get
      val deadline = ErgoTree.fromProposition(LE(Height, IntConstant(genesis.header.height + 1)).toSigmaProp)
      val feeProp = cfg.chainSettings.monetary.feeProposition
      val funding = ErgoTransaction(IndexedSeq(new Input(box.id, ProverResult.empty)),
        IndexedSeq(new ErgoBoxCandidate(100000L, feeProp, box.creationHeight),
          new ErgoBoxCandidate(box.value - 100000L, deadline, box.creationHeight)))
      def spend(fee: Long): ErgoTransaction = ErgoTransaction(IndexedSeq(new Input(funding.outputs(1).id, ProverResult.empty)),
        IndexedSeq(new ErgoBoxCandidate(fee, feeProp, box.creationHeight),
          new ErgoBoxCandidate(funding.outputs(1).value - fee, TrueTree, box.creationHeight)))
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(funding, None))
      expectMsgType[Accepted]
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(spend(200000L), None))
      expectMsgType[Accepted]
      val held = spend(100000L)
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(held, None))
      expectMsgType[DoubleSpendingLoser]
      val failures = TestProbe()
      actorSystem.eventStream.subscribe(failures.ref, classOf[FailedTransaction])
      actorSystem.eventStream.subscribe(failures.ref, classOf[FailedOnRecheckTransaction])
      val accounting = TestProbe()
      actorSystem.eventStream.subscribe(accounting.ref, classOf[StagingValidationResult])
      // Funding confirms, and the next admission context passes the script's deadline.
      applyBlock(makeNextBlock(getCurrentView.state.asInstanceOf[UtxoState], Seq(funding))) shouldBe 'success
      failures.expectMsgType[FailedOnRecheckTransaction].id shouldBe held.id
      val recheck = accounting.expectMsgType[StagingValidationResult].work.find(_.transaction.id == held.id).get
      recheck.recheck shouldBe true
      recheck.error.nonEmpty shouldBe true
      recheck.cost shouldBe cfg.nodeSettings.maxTransactionCost
      getCurrentView.pool.isInvalidated(held.id) shouldBe true
      await(getCurrentView.vault.balancesWithUnconfirmed)
    }
  }

}
