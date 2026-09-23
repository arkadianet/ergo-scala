package org.ergoplatform.nodeView.history.modifierprocessors

import org.ergoplatform.mining.InputBlockFields
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Algos
import org.ergoplatform.subblocks.InputBlockAnnouncement
import scorex.crypto.authds.LeafData

private[modifierprocessors] object WaitlistFixtureSupport {
  def provedAnnouncement(
    header: Header,
    transactions: Seq[ErgoTransaction],
    parentId: Option[Array[Byte]]
  ): InputBlockAnnouncement = {
    val digest = Algos.merkleTreeRoot(transactions.map(tx => LeafData @@ tx.serializedId))
    // Every preceding input block in the waitlist fixture has an empty body.
    val previousDigest = Algos.merkleTreeRoot(Seq.empty[LeafData])
    val extension = InputBlockFields.toExtensionFields(parentId, digest, previousDigest)
    val fields = new InputBlockFields(
      parentId, digest, previousDigest, extension.proofForInputBlockData.get)
    InputBlockAnnouncement(
      InputBlockAnnouncement.initialMessageVersion,
      header.copy(extensionRoot = extension.digest),
      fields,
      None
    )
  }
}
