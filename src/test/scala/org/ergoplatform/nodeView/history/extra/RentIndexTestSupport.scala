package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.nodeView.history.ErgoHistory
import scorex.util.ModifierId

/** Test-only bridge into `protected[history]` / `private[extra]` internals needed
  * by route-level specs living outside this package tree (e.g.
  * `org.ergoplatform.http.routes`) to plant raw rent-index rows directly,
  * bypassing ordinary indexing. Mirrors how `ExtraIndexer.rentRange` exposes
  * read access to production code outside the history package -- this is the
  * write-side, test-only equivalent.
  */
object RentIndexTestSupport {

  /** Insert raw key/value pairs directly into extraStore, bypassing ExtraIndex
    * object serialization. Used to plant rent-index rows (and decoys) that don't
    * correspond to any real ExtraIndex object.
    */
  def insertExtraRaw(history: ErgoHistory, pairs: Array[(Array[Byte], Array[Byte])]): Unit =
    history.historyStorage.insertExtra(pairs, Array.empty)

  /** Expose `ExtraIndexer.fastIdToBytes` (private[extra]) to test code outside
    * this package tree.
    */
  def fastIdToBytes(id: ModifierId): Array[Byte] = ExtraIndexer.fastIdToBytes(id)
}
