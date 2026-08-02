package org.ergoplatform.nodeView.history.storage

import org.ergoplatform.nodeView.history.extra.ExtraIndexer
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.rentKey
import org.ergoplatform.nodeView.history.extra.NumericBoxIndex
import org.ergoplatform.settings.{CacheSettings, HistoryCacheSettings, MempoolCacheSettings, NetworkCacheSettings}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.db.{LDBFactory, LDBKVStore}
import scorex.util.ModifierId
import scorex.util.encode.Base16

import scala.concurrent.duration._
import scala.util.Try

/** Guards spec §3.2: progress keys, rent puts and rent deletes must reach the
  * store in ONE atomic batch. Functional tests cannot see the difference; this
  * one counts the calls.
  */
class HistoryStorageBatchingSpec extends AnyFlatSpec with Matchers {

  private val sampleExtraIndexObject =
    NumericBoxIndex(0L, ModifierId @@ Base16.encode(Array.fill(32)(0.toByte)))

  /** Counting subclass over a real store. `update` is the atomic batch;
    * `insert(K, V)` (single-key overload) is the non-atomic bare db.put we must
    * never use for raw pairs. NOTE: the ARRAY overload `insert(keys, values)`
    * delegates to `update` internally (LDBKVStore.scala:60-62), so object writes
    * also increment updateCalls — count accordingly.
    */
  class CountingStore(db: org.iq80.leveldb.DB) extends LDBKVStore(db) {
    var updateCalls = 0
    var singlePutCalls = 0
    override def update(ik: Array[K], iv: Array[V], rem: Array[K]): Try[Unit] = {
      updateCalls += 1; super.update(ik, iv, rem)
    }
    override def insert(id: K, value: V): Try[Unit] = {
      singlePutCalls += 1; super.insert(id, value)
    }
  }

  private def tempStore(): LDBKVStore =
    new LDBKVStore(LDBFactory.factory.open(
      java.nio.file.Files.createTempDirectory("hs-batching").toFile,
      new org.iq80.leveldb.Options().createIfMissing(true)))

  private def countingStore(): CountingStore =
    new CountingStore(LDBFactory.factory.open(
      java.nio.file.Files.createTempDirectory("hs-batching-extra").toFile,
      new org.iq80.leveldb.Options().createIfMissing(true)))

  /** HistoryStorage(indexStore, objectsStore, extraStore, config) —
    * extraStore is the THIRD parameter (HistoryStorage.scala:29).
    */
  private def storageWith(extra: LDBKVStore): HistoryStorage =
    new HistoryStorage(tempStore(), tempStore(), extra,
      CacheSettings(
        HistoryCacheSettings(100, 100, 100, 100),
        NetworkCacheSettings(100, 4.hours),
        MempoolCacheSettings(100, 4.hours)))

  it should "put progress keys, rent puts and rent deletes in ONE atomic batch" in {
    val extra = countingStore()
    val s = storageWith(extra)
    val existing = rentKey(1, 1L)
    s.insertExtra(Array(existing -> Array[Byte](1)), Array.empty)
    extra.updateCalls = 0
    extra.singlePutCalls = 0

    // Marker + a rent put + a rent delete, objects EMPTY. The §3.2 rule says
    // these must be one batch: exactly one update, zero bare puts.
    s.insertExtra(
      Array(
        ExtraIndexer.IndexedHeightKey -> java.nio.ByteBuffer.allocate(4).putInt(42).array,
        rentKey(2, 2L) -> Array[Byte](2)
      ),
      Array.empty,
      Array(existing)
    )
    extra.updateCalls shouldBe 1
    extra.singlePutCalls shouldBe 0
  }

  it should "use the batching array overload for objects, never bare puts" in {
    val extra = countingStore()
    val s = storageWith(extra)
    extra.updateCalls = 0
    extra.singlePutCalls = 0

    // With objects present there are TWO batches: one for objects (the array
    // insert overload delegates to update, LDBKVStore.scala:60-62) and one for
    // the raw pairs. Two is correct here; it does NOT license relaxing the
    // assertion above, which measures the objects-empty case.
    s.insertExtra(
      Array(ExtraIndexer.IndexedHeightKey -> java.nio.ByteBuffer.allocate(4).putInt(43).array),
      Array(sampleExtraIndexObject),
      Array.empty
    )
    extra.updateCalls shouldBe 2
    extra.singlePutCalls shouldBe 0
  }
}
