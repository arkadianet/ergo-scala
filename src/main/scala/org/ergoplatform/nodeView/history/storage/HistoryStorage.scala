package org.ergoplatform.nodeView.history.storage

import com.github.benmanes.caffeine.cache.Caffeine
import org.ergoplatform.modifiers.{BlockSection, NetworkObjectTypeId}
import org.ergoplatform.modifiers.history.HistoryModifierSerializer
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.history.extra.{ExtraIndex, ExtraIndexSerializer, Segment}
import org.ergoplatform.settings.{Algos, CacheSettings, ErgoSettings}
import org.ergoplatform.utils.ScorexEncoding
import scorex.db.{ByteArrayWrapper, LDBFactory, LDBKVStore}
import scorex.util.{ModifierId, ScorexLogging, idToBytes}

import scala.util.{Failure, Success, Try}
import spire.syntax.all.cfor

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.asScalaIteratorConverter

/**
  * Storage for Ergo history
  *
  * @param indexStore   - Additional key-value storage for indexes, required by History for efficient work.
  *                     contains links to bestHeader, bestFullBlock, heights and scores for different blocks, etc.
  * @param objectsStore - key-value store, where key is id of ErgoPersistentModifier and value is it's bytes
  * @param extraStore   - key-value store, where key is id of Index and value is it's bytes
  * @param config       - cache configs
  */
class HistoryStorage(indexStore: LDBKVStore, objectsStore: LDBKVStore, extraStore: LDBKVStore, config: CacheSettings)
  extends ScorexLogging
    with AutoCloseable
    with ScorexEncoding {

  private lazy val headersCache =
    Caffeine.newBuilder()
      .maximumSize(config.history.headersCacheSize)
      .build[String, BlockSection]()

  private lazy val blockSectionsCache =
    Caffeine.newBuilder()
      .maximumSize(config.history.blockSectionsCacheSize)
      .build[String, BlockSection]()

  private lazy val extraCache =
    Caffeine.newBuilder()
      .maximumSize(config.history.extraCacheSize)
      .build[String, ExtraIndex]()

  private lazy val indexCache =
    Caffeine.newBuilder()
      .maximumSize(config.history.indexesCacheSize)
      .build[ByteArrayWrapper, Array[Byte]]

  private def cacheModifier(mod: BlockSection): Unit = mod.modifierTypeId match {
    case Header.modifierTypeId => headersCache.put(mod.id, mod)
    case _ => blockSectionsCache.put(mod.id, mod)
  }

  private def lookupModifier(id: ModifierId): Option[BlockSection] =
    Option(headersCache.getIfPresent(id)) orElse Option(blockSectionsCache.getIfPresent(id))

  private def removeModifier(id: ModifierId): Unit = {
    headersCache.invalidate(id)
    blockSectionsCache.invalidate(id)
    extraCache.invalidate(id)
  }

  def modifierBytesById(id: ModifierId): Option[Array[Byte]] = {
    objectsStore.get(idToBytes(id)).map(_.tail).orElse(extraStore.get(idToBytes(id))) // removing modifier type byte with .tail (only in objectsStore)
  }

  /**
    * @return bytes and type of a network object stored in the database with identifier `id`
    */
  def modifierTypeAndBytesById(id: ModifierId): Option[(NetworkObjectTypeId.Value, Array[Byte])] = {
    objectsStore.get(idToBytes(id)).map(bs => (NetworkObjectTypeId.fromByte(bs.head), bs.tail)) // first byte is type id, tail is modifier bytes
  }

  def modifierById(id: ModifierId): Option[BlockSection] =
    lookupModifier(id) orElse objectsStore.get(idToBytes(id)).flatMap { bytes =>
      HistoryModifierSerializer.parseBytesTry(bytes) match {
        case Success(pm) =>
          log.trace(s"Cache miss for existing modifier $id")
          cacheModifier(pm)
          Some(pm)
        case Failure(e) =>
          log.warn(s"Failed to parse modifier ${encoder.encode(id)} from db (bytes are: ${Algos.encode(bytes)})", e)
          None
      }
    }

  def getExtraIndex(id: ModifierId): Option[ExtraIndex] = {
    Option(extraCache.getIfPresent(id)) orElse extraStore.get(idToBytes(id)).flatMap { bytes =>
      ExtraIndexSerializer.parseBytesTry(bytes) match {
        case Success(pm) =>
          log.trace(s"Cache miss for existing index $id")
          if(!pm.isInstanceOf[Segment[_]]){
            extraCache.put(pm.id, pm) // cache non-segment objects
          }
          Some(pm)
        case Failure(_) =>
          log.warn(s"Failed to parse index ${encoder.encode(id)} from db (bytes are: ${Algos.encode(bytes)})")
          None
      }
    }
  }

  def getIndex(id: ByteArrayWrapper): Option[Array[Byte]] =
    Option(indexCache.getIfPresent(id)).orElse {
      indexStore.get(id.data).map { value =>
        indexCache.put(id, value)
        value
      }
    }

  /**
    * @return object with `id` if it is in the objects database
    */
  def get(id: ModifierId): Option[Array[Byte]] = {
    val idBytes = idToBytes(id)
    objectsStore.get(idBytes).orElse(extraStore.get(idBytes))
  }
  def get(id: Array[Byte]): Option[Array[Byte]] = objectsStore.get(id).orElse(extraStore.get(id))

  /** Ordered scan over raw extraStore keys. See KVStoreReader.getRangeWithFilter.
    * Used by the storage-rent index, whose 13-byte keys cannot be ModifierIds.
    * Propagates RangeScanBudgetExceeded to the caller.
    */
  def getExtraRange(start: Array[Byte], end: Array[Byte], offset: Int, limit: Int,
                    reverse: Boolean, visitBudget: Long = Long.MaxValue)
                   (keyFilter: Array[Byte] => Boolean): Array[(Array[Byte], Array[Byte])] =
    extraStore.getRangeWithFilter(start, end, offset, limit, reverse, visitBudget)(keyFilter)

  /** Generic UNORDERED enumeration of raw extraStore entries matching a predicate.
    * Deliberately routed through the pre-existing KVStoreReader.getWithFilter
    * rather than getExtraRange, so tests can verify the ordered scan against an
    * independent code path. Do not reimplement this in terms of getExtraRange.
    */
  def getAllExtraRaw(cond: (Array[Byte], Array[Byte]) => Boolean): Seq[(Array[Byte], Array[Byte])] =
    extraStore.getWithFilter(cond).toSeq

  /**
    * @return if object with `id` is in the objects database
    */
  def contains(id: Array[Byte]): Boolean = get(id).isDefined
  def contains(id: ModifierId): Boolean = get(id).isDefined

  def insert(indexesToInsert: Array[(ByteArrayWrapper, Array[Byte])],
             objectsToInsert: Array[BlockSection]): Try[Unit] = {
    objectsStore.insert(
      objectsToInsert.map(mod => mod.serializedId),
      objectsToInsert.map(mod => HistoryModifierSerializer.toBytes(mod))
    ).flatMap { _ =>
      cfor(0)(_ < objectsToInsert.length, _ + 1) { i => cacheModifier(objectsToInsert(i))}
      if (indexesToInsert.nonEmpty) {
        indexStore.insert(
          indexesToInsert.map(_._1.data),
          indexesToInsert.map(_._2)
        ).map { _ =>
          cfor(0)(_ < indexesToInsert.length, _ + 1) { i =>
            indexCache.put(indexesToInsert(i)._1, indexesToInsert(i)._2)
          }
        }
      } else Success(())
    }
  }

  /** Write extra-index objects and raw key-value pairs, and remove raw keys.
    *
    * CRASH CONSISTENCY (live-indexing flush, i.e. `ExtraIndexer.saveProgress`):
    * the raw pairs carry the indexer progress keys (including IndexedHeightKey)
    * alongside storage-rent rows. They are written in ONE atomic WriteBatch
    * together with `keysToRemove`, so the height marker can never become
    * durable ahead of the data it vouches for. Recovery is replay from
    * IndexedHeightKey, which only heals writes landing before the marker.
    * DO NOT split the live-indexing flush call into separate writes —
    * HistoryStorageBatchingSpec enforces it. This guarantee is scoped to that
    * one call site: other callers (e.g. rollback, see ExtraIndexer.scala around
    * `removeAfter`) may legitimately issue multiple separate `insertExtra`
    * batches when their own recovery story doesn't depend on single-batch
    * atomicity.
    *
    * Objects go in a separate (also atomic) batch first; a crash between the two
    * leaves the marker un-advanced, so the block is re-indexed and object writes
    * are idempotent overwrites.
    *
    * Empty batches are skipped: removeAfter calls this once per un-spent box, and
    * an empty createWriteBatch/write pair per call is pure overhead. The guards
    * must never separate batch 2's contents from each other.
    */
  def insertExtra(indexesToInsert: Array[(Array[Byte], Array[Byte])],
                  objectsToInsert: Array[ExtraIndex],
                  keysToRemove: Array[Array[Byte]] = Array.empty): Unit = {
    if (objectsToInsert.nonEmpty) {
      extraStore.insert(
        objectsToInsert.map(mod => mod.serializedId),
        objectsToInsert.map(mod => ExtraIndexSerializer.toBytes(mod))
      ).get
    }
    if (indexesToInsert.nonEmpty || keysToRemove.nonEmpty) {
      extraStore.update(
        indexesToInsert.map(_._1),
        indexesToInsert.map(_._2),
        keysToRemove
      ).get
    }
  }

  def removeExtra(indexesToRemove: Array[ModifierId]) : Unit = {
    extraStore.remove(indexesToRemove.map(idToBytes))
    cfor(0)(_ < indexesToRemove.length, _ + 1) { i => removeModifier(indexesToRemove(i)) }
  }

  /**
    * Insert single object to database. This version allows for efficient insert
    * when identifier and bytes of object (i.e. modifier, a block section) are known.
    *
    * @param objectIdToInsert - object id to insert
    * @param objectToInsert - object bytes to insert
    * @return - Success if insertion was successful, Failure otherwise
    */
  def insert(objectIdToInsert: Array[Byte],
             objectToInsert: Array[Byte]): Try[Unit] = {
    objectsStore.insert(objectIdToInsert, objectToInsert)
  }

  /**
    * Remove elements from stored indices and modifiers
    *
    * @param indicesToRemove - indices keys to remove
    * @param idsToRemove - identifiers of modifiers to remove
    * @return
    */
  def remove(indicesToRemove: Array[ByteArrayWrapper],
             idsToRemove: Array[ModifierId]): Try[Unit] = {

      objectsStore.remove(idsToRemove.map(idToBytes)).map { _ =>
        cfor(0)(_ < idsToRemove.length, _ + 1) { i => removeModifier(idsToRemove(i))}
        indexStore.remove(indicesToRemove.map(_.data)).map { _ =>
          cfor(0)(_ < indicesToRemove.length, _ + 1) { i => indexCache.invalidate(indicesToRemove(i))}
          ()
        }
      }
  }

  override def close(): Unit = {
    log.warn("Closing history storage...")
    extraStore.close()
    indexStore.close()
    objectsStore.close()
  }

  /**
    * Delete the extra index database and reopen it.
    *
    * @param ergoSettings - settings to use
    * @return new HistoryStorage instance with empty extra database, or this instance in case of failure
    */
  def deleteExtraDB(ergoSettings: ErgoSettings): HistoryStorage = {
    log.warn(s"Removing extra index database due to old schema.")
    close()
    // org.ergoplatform.wallet.utils.FileUtils
    val root = new File(s"${ergoSettings.directory}/history/extra")
    if (root.exists()) {
      Files.walk(root.toPath).iterator().asScala.toSeq.reverse.foreach(path => Try(Files.delete(path)))
    }else {
      log.error(s"Could not delete ${root.toString}")
      return this
    }
    log.info(s"Deleted ${root.toString}")
    HistoryStorage.apply(ergoSettings)
  }

}

object HistoryStorage {
  def apply(ergoSettings: ErgoSettings): HistoryStorage = {
    val indexStore = LDBFactory.createKvDb(s"${ergoSettings.directory}/history/index")
    val objectsStore = LDBFactory.createKvDb(s"${ergoSettings.directory}/history/objects")
    val extraStore = LDBFactory.createKvDb(s"${ergoSettings.directory}/history/extra")
    new HistoryStorage(indexStore, objectsStore, extraStore, ergoSettings.cacheSettings)
  }
}
