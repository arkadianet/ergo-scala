package org.ergoplatform.nodeView.mempool

import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, ErgoTransactionSerializer}
import org.ergoplatform.nodeView.state.UtxoState
import sigma.VersionContext

import scala.util.Try

private[mempool] object MempoolValidation {
  // Preserve the mempool check introduced in 6.0 for versioned serializers.
  // Protocol rules can allow unparseable output trees, but deserialization can
  // fail on versioning rules (for example, a tree version above the activated
  // script version). Both single-transaction and package admission must apply
  // this check under the active version context before script validation.
  def checkSerialization(tx: ErgoTransaction, state: UtxoState): Try[Unit] = {
    val version = Header.scriptFromBlockVersion(state.stateContext.blockVersion)
    VersionContext.withVersions(version, version) {
      ErgoTransactionSerializer.parseBytesTry(tx.bytes).map(_ => ())
    }
  }
}
