package org.ergoplatform.nodeView.history.modifierprocessors

import org.ergoplatform.mining.InputBlockFields
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.Algos
import org.ergoplatform.subblocks.InputBlockAnnouncement
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants.settings
import org.ergoplatform.utils.HistoryTestHelpers.generateHistory
import org.ergoplatform.utils.generators.ChainGenerator.{applyChain, genChain}
import org.ergoplatform.utils.generators.ValidBlocksGenerators.createUtxoState
import scorex.crypto.authds.merkle.BatchMerkleProof

class InputBlockFieldBindingSpec extends ErgoCorePropertyTest {
  private val digest = Algos.merkleTreeRoot(Seq.empty)
  private val wrongDigest = Algos.hash("unannounced transactions")
  private val extension = InputBlockFields.toExtensionFields(None, digest, digest)

  private def check(fields: InputBlockFields, accepted: Boolean): Unit = {
    val state = createUtxoState(settings)._1
    val history = generateHistory(true, StateType.Utxo, false, -1)
    try {
      applyChain(history, genChain(2, history, stateOpt = Some(state)))
      val header = genChain(2, history, stateOpt = Some(state)).tail.head.header
        .copy(extensionRoot = extension.digest)
      val announcement = InputBlockAnnouncement(1, header, fields, None)
      history.applyInputBlock(announcement) shouldBe None
      val result = history.applyInputBlockTransactions(announcement.id, Seq.empty, state)
      result._1 shouldBe (if (accepted) Seq(announcement.id) else Seq.empty)
      history.bestInputBlocksChain() shouldBe result._1
    } finally {
      history.closeStorage()
      state.closeStorage()
    }
  }

  property("empty indices cannot bypass the transaction body digest check") {
    check(new InputBlockFields(None, wrongDigest, digest,
      BatchMerkleProof(Seq.empty, Seq.empty)(Algos.hash)), accepted = false)
  }

  property("receiver rejects a previous transactions digest not bound to the committed leaf") {
    check(new InputBlockFields(None, digest, wrongDigest,
      extension.proofForInputBlockData.get), accepted = false)
  }

  property("receiver accepts matching bodies and a bound previous transactions digest") {
    check(new InputBlockFields(None, digest, digest,
      extension.proofForInputBlockData.get), accepted = true)
  }
}
