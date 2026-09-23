package org.ergoplatform.utils

import org.ergoplatform.mining.InputBlockFields
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Algos
import org.ergoplatform.subblocks.InputBlockAnnouncement
import scorex.crypto.authds.LeafData

object InputBlockTestHelpers {
  def provedAnnouncement(header: Header,
                         transactions: Seq[ErgoTransaction],
                         parentId: Option[Array[Byte]] = None,
                         weakTxIds: Option[Seq[ErgoTransaction.WeakId]] = None): InputBlockAnnouncement = {
    val digest = Algos.merkleTreeRoot(transactions.map(tx => LeafData @@ tx.serializedId))
    val extension = InputBlockFields.toExtensionFields(parentId, digest, digest)
    val fields = new InputBlockFields(parentId, digest, digest, extension.proofForInputBlockData.get)
    InputBlockAnnouncement(
      InputBlockAnnouncement.initialMessageVersion,
      header.copy(extensionRoot = extension.digest),
      fields,
      weakTxIds
    )
  }
}
