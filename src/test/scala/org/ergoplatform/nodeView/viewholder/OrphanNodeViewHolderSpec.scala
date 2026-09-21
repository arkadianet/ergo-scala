package org.ergoplatform.nodeView.viewholder

import akka.testkit.TestProbe
import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{FailedTransaction, StagingValidationResult, SuccessfulTransaction}
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.ProcessingOutcome.{Accepted, Declined}
import org.ergoplatform.nodeView.state.{ErgoState, StateType, UtxoState}
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.utils.{NodeViewTestConfig, NodeViewTestOps}
import org.ergoplatform.utils.fixtures.NodeViewFixture
import org.scalatest.flatspec.AnyFlatSpec

class OrphanNodeViewHolderSpec extends AnyFlatSpec with NodeViewTestOps {
  import org.ergoplatform.utils.ErgoCoreTestConstants.parameters
  import org.ergoplatform.utils.generators.ErgoNodeTransactionGenerators.validTransactionFromBoxes
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  it should "persist an orphan through txModify and announce parent and child on resolution" in {
    val base = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    val stagingSettings = base.copy(nodeSettings = base.nodeSettings.copy(stagingEnabled = true))
    new NodeViewFixture(stagingSettings, parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(parentOpt = None, us, bh)
      applyBlock(genesis) shouldBe 'success

      val spendable = ErgoState.newBoxes(genesis.transactions).filter(_.ergoTree == TrueTree).toIndexedSeq
      spendable.nonEmpty shouldBe true

      val parent = validTransactionFromBoxes(spendable)
      val childInputs = parent.outputs.filter(_.ergoTree == TrueTree)
      childInputs.nonEmpty shouldBe true
      val child = validTransactionFromBoxes(childInputs)

      // The child must remain staged across actor messages.
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(child, None))
      expectMsgType[Declined]
      getPoolSize shouldBe 0

      val events = TestProbe()
      actorSystem.eventStream.subscribe(events.ref, classOf[SuccessfulTransaction])
      val accounting = TestProbe()
      actorSystem.eventStream.subscribe(accounting.ref, classOf[StagingValidationResult])
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(parent, None))
      expectMsgType[Accepted].admitted.map(_.id) shouldBe Seq(parent.id, child.id)
      val charged = accounting.expectMsgType[StagingValidationResult]
      charged.work.map(_.transaction.id) shouldBe Seq(parent.id, child.id)
      charged.work.forall(_.cost > 0) shouldBe true
      val announcement = events.expectMsgType[SuccessfulTransaction]
      announcement.transaction.id shouldBe parent.id
      announcement.validationCost shouldBe Some(0)
      events.expectMsgType[SuccessfulTransaction].transaction.id shouldBe child.id
      getPoolSize shouldBe 2
      getCurrentView.pool.contains(parent.id) shouldBe true
      getCurrentView.pool.contains(child.id) shouldBe true
      // Drain the wallet mailbox before the fixture closes the state storage.
      await(getCurrentView.vault.balancesWithUnconfirmed)
    }
  }

  it should "recover a waiting child only after its parent block commits the new state" in {
    val base = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    new NodeViewFixture(base.copy(nodeSettings = base.nodeSettings.copy(stagingEnabled = true)), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(None, us, bh)
      applyBlock(genesis) shouldBe 'success
      val spendable = ErgoState.newBoxes(genesis.transactions).filter(_.ergoTree == TrueTree).toIndexedSeq
      val parent = validTransactionFromBoxes(spendable)
      val child = validTransactionFromBoxes(parent.outputs.filter(_.ergoTree == TrueTree))
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(child, None))
      expectMsgType[Declined]
      val events = TestProbe()
      actorSystem.eventStream.subscribe(events.ref, classOf[SuccessfulTransaction])
      val block = makeNextBlock(getCurrentView.state.asInstanceOf[UtxoState], Seq(parent))
      applyBlock(block) shouldBe 'success
      events.expectMsgType[SuccessfulTransaction].transaction.id shouldBe child.id
      getCurrentView.pool.contains(child.id) shouldBe true
      getCurrentView.pool.contains(parent.id) shouldBe false
      await(getCurrentView.vault.balancesWithUnconfirmed)
    }
  }

  it should "publish a failed cascade attempt and account its work without announcing it" in {
    val base = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    new NodeViewFixture(base.copy(nodeSettings = base.nodeSettings.copy(stagingEnabled = true)), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(None, us, bh)
      applyBlock(genesis) shouldBe 'success
      val spendable = ErgoState.newBoxes(genesis.transactions).filter(_.ergoTree == TrueTree).toIndexedSeq
      val parent = validTransactionFromBoxes(spendable)
      val validChild = validTransactionFromBoxes(parent.outputs.filter(_.ergoTree == TrueTree))
      // The input is unresolved initially; once available, value conservation fails.
      val badOutputs = validChild.outputCandidates.map(o => new org.ergoplatform.ErgoBoxCandidate(
        o.value + 1000000L, o.ergoTree, o.creationHeight, o.additionalTokens, o.additionalRegisters))
      val child = org.ergoplatform.modifiers.mempool.ErgoTransaction(validChild.inputs, badOutputs)
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(child, None))
      expectMsgType[Declined]
      val failures = TestProbe()
      val accounting = TestProbe()
      actorSystem.eventStream.subscribe(failures.ref, classOf[FailedTransaction])
      actorSystem.eventStream.subscribe(accounting.ref, classOf[StagingValidationResult])
      nodeViewHolderRef ! LocallyGeneratedTransaction(UnconfirmedTransaction(parent, None))
      expectMsgType[Accepted].admitted.map(_.id) shouldBe Seq(parent.id)
      val failed = failures.expectMsgType[FailedTransaction]
      failed.transaction.id shouldBe child.id
      failed.validationCost shouldBe Some(0)
      val work = accounting.expectMsgType[StagingValidationResult].work
      work.map(_.transaction.id) shouldBe Seq(parent.id, child.id)
      work.last.error.nonEmpty shouldBe true
      work.last.cost shouldBe fixture.settings.nodeSettings.maxTransactionCost
      getCurrentView.pool.isInvalidated(child.id) shouldBe true
      await(getCurrentView.vault.balancesWithUnconfirmed)
    }
  }

}
