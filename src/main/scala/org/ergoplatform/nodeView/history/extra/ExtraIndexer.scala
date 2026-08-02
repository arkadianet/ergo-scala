package org.ergoplatform.nodeView.history.extra

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import org.ergoplatform.{ErgoAddress, ErgoAddressEncoder, GlobalConstants, Pay2SAddress}
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{FullBlockApplied, Rollback}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer._
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoHistoryReader}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.ReceivableMessages._
import org.ergoplatform.nodeView.history.extra.IndexedContractTemplateSerializer.hashTreeTemplate
import org.ergoplatform.nodeView.history.extra.IndexedErgoAddressSerializer.hashErgoTree
import org.ergoplatform.nodeView.history.extra.IndexedTokenSerializer.uniqueId
import org.ergoplatform.nodeView.history.storage.HistoryStorage
import org.ergoplatform.settings.{Algos, CacheSettings, ChainSettings}
import scorex.db.ByteArrayWrapper
import scorex.util.{ModifierId, ScorexLogging, bytesToId}
import sigma.ast.ErgoTree
import sigma.Extensions._
import sigma.interpreter.ProverResult

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer
import spire.syntax.all.cfor

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.collection.concurrent
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._

/**
  * Base trait for extra indexer actor and its test.
  */
trait ExtraIndexerBase extends Actor with Stash with ScorexLogging {

  private implicit val ec: ExecutionContextExecutor = context.dispatcher

  /**
    * Max buffer size (determined by config)
    */
  protected val saveLimit: Int

  /**
    * Number of transaction/box numeric indexes object segments contain
    */
  protected implicit val segmentThreshold: Int

  /**
    * Address encoder instance
    */
  protected implicit val addressEncoder: ErgoAddressEncoder

  /**
    * Database handle
    */
  protected var _history: ErgoHistory = _

  protected def chainHeight: Int = _history.fullBlockHeight

  protected def history: ErgoHistoryReader = _history.getReader

  protected def historyStorage: HistoryStorage = _history.historyStorage

  /**
   * Used in tests to indicate the indexer has caught up to the chain
   */
  protected def caughtUpHook(height: Int = 0): Unit = {}

  /** Test seam: called once per `saveProgress` invocation, immediately before
    * the write, with the exact `indexesToInsert`/`keysToRemove` arrays about to
    * be passed to a single `historyStorage.insertExtra` call. Lets a spec
    * assert that the progress marker and the pending rent mutations travel in
    * that one call -- the invariant HistoryStorage.insertExtra's docstring
    * documents and HistoryStorageBatchingSpec enforces at the storage layer --
    * without touching production behavior.
    */
  protected def onSaveProgress(indexesToInsert: Array[(Array[Byte], Array[Byte])],
                               keysToRemove: Array[Array[Byte]]): Unit = {}

  /**
   * Used in tests to get block for rollback, maybe orphan
   */
  protected def getLastTxForHeight(height: Int): ErgoTransaction = {
    history.bestBlockTransactionsAt(height).get.txs.last
  }

  /** Test seam: lets a spec build a history WITHOUT rent rows, simulating a
    * pre-feature database for the backfill tests. Always true in production;
    * every rent write site is guarded by it.
    */
  protected val rentWritesEnabled: Boolean = true

  // fast access buffers
  protected val general: ArrayBuffer[ExtraIndex] = ArrayBuffer.empty[ExtraIndex]
  protected val boxes: mutable.HashMap[ModifierId, IndexedErgoBox] = mutable.HashMap.empty[ModifierId, IndexedErgoBox]
  protected val trees: mutable.HashMap[ModifierId, IndexedErgoAddress] = mutable.HashMap.empty[ModifierId, IndexedErgoAddress]
  protected val templates: mutable.HashMap[ModifierId, IndexedContractTemplate] = mutable.HashMap.empty[ModifierId, IndexedContractTemplate]
  protected val tokens: mutable.HashMap[ModifierId, IndexedToken] = mutable.HashMap.empty[ModifierId, IndexedToken]
  protected val segments: mutable.HashMap[ModifierId, Segment[_]] = mutable.HashMap.empty[ModifierId, Segment[_]]

  /** Pending storage-rent rows: 13-byte key -> 32-byte boxId.
    * Deliberately NOT counted in `modCount`: rentPuts.size ~= boxes.size,
    * so counting them would halve the effective saveLimit and double flush
    * frequency for ~45 bytes per entry. Safe because every rentPuts entry
    * accompanies a boxes.put and every rentRemovals entry accompanies a
    * findAndSpendBox that also populates boxes — so modCount > 0 whenever these
    * are non-empty, and no flush can be skipped while rent rows are pending.
    */
  protected val rentPuts: mutable.HashMap[ByteArrayWrapper, Array[Byte]] =
    mutable.HashMap.empty[ByteArrayWrapper, Array[Byte]]

  protected val rentRemovals: mutable.HashSet[ByteArrayWrapper] =
    mutable.HashSet.empty[ByteArrayWrapper]

  /**
    * Input tokens in a transaction, cleared after every transaction
    */
  private val inputTokens: mutable.HashMap[Seq[Byte], Long] = mutable.HashMap.empty[Seq[Byte], Long]

  /**
    * Holds upcoming blocks to be indexed, and when empty, it is filled back from multiple threads
    */
  private val blockCache: concurrent.Map[Int, BlockTransactions] = new ConcurrentHashMap[Int, BlockTransactions]().asScala
  private var readingUpTo: Int = 0

  /**
    * Get transactions for specified height, preferably from cache, or from database.
    * If indexer is getting close to emptying cache, asynchronously reads 1000 blocks into it
    *
    * @param height - blockheight to get transations from
    * @return transactions at height
    */
  private def getBlockTransactionsAt(height: Int): Option[BlockTransactions] = {
    blockCache.remove(height).orElse(history.bestBlockTransactionsAt(height)).map { txs =>
      if (height % 1000 == 0) blockCache.keySet.filter(_ < height).map(blockCache.remove)
      if (readingUpTo - height < 300 && chainHeight - height > 1000) {
        readingUpTo = math.min(height + 1001, chainHeight)

        if(height < history.fullBlockHeight - 1000) {
          val blockNums = height + 1 to readingUpTo by 250
          blockNums.zip(blockNums.tail).map { range => // ranges of 250 blocks for each thread to read
            Future {
              (range._1 until range._2).foreach { blockNum =>
                history.bestBlockTransactionsAt(blockNum).map(blockCache.put(blockNum, _))
              }
            }
          }
        } else {
          val blockNums = height + 1 to readingUpTo
          Future {
            blockNums.foreach { blockNum =>
              history.bestBlockTransactionsAt(blockNum).map(blockCache.put(blockNum, _))
            }
          }
        }
      }
      txs
    }
  }

  /**
    * Spend an IndexedErgoBox from buffer or database. Also record tokens for later use in balance tracking logic.
    *
    * @param id     - id of the wanted box
    * @param txId   - id of the spending transaction
    * @param height - height of the block the spending transaction is included in
    * @return whether the box was found in buffer or database -> should always return true
    */
  private def findAndSpendBox(id: ModifierId, txId: ModifierId, height: Int, spendingProof: ProverResult): Boolean = {
    boxes.get(id).map(box => {
      box.asSpent(txId, height, spendingProof).box.additionalTokens.toArray.map(x => x._1.toArray.toSeq -> x._2).foreach { case (k, v) =>
        inputTokens.put(k, inputTokens.getOrElse(k, 0L) + v)
      }
      return true
    })
    history.typedExtraIndexById[IndexedErgoBox](id) match { // box not found in last saveLimit modifiers
      case Some(x) => // box found in DB, update
        boxes.put(id, x.asSpent(txId, height, spendingProof))
        x.box.additionalTokens.toArray.map(x => x._1.toArray.toSeq -> x._2).foreach { case (k, v) =>
          inputTokens.put(k, inputTokens.getOrElse(k, 0L) + v)
        }
        true
      case None => // box not found at all (this shouldn't happen)
        log.warn(s"Unknown box used as input: $id")
        false
    }
  }

  /**
    * Add or subtract a box from an address in the buffer or in database.
    *
    * @param id             - hash of the (ergotree) address
    * @param spendOrReceive - IndexedErgoBox to receive (Right) or spend (Left)
    */
  private def findAndUpdateTree(id: ModifierId, spendOrReceive: Either[IndexedErgoBox, IndexedErgoBox])(state: IndexerState): Unit = {
    trees.get(id).map { tree =>
      spendOrReceive match {
        case Left(iEb) => tree.addTx(state.globalTxIndex).spendBox(iEb, Some(history)) // spend box
        case Right(iEb) => tree.addTx(state.globalTxIndex).addBox(iEb) // receive box
      }
      return
    }
    history.typedExtraIndexById[IndexedErgoAddress](id) match { // address not found in last saveLimit modifiers
      case Some(x) =>
        spendOrReceive match {
          case Left(iEb) => trees.put(id, x.addTx(state.globalTxIndex).spendBox(iEb, Some(history))) // spend box
          case Right(iEb) => trees.put(id, x.addTx(state.globalTxIndex).addBox(iEb)) // receive box
        }
      case None => // address not found at all
        spendOrReceive match {
          case Left(iEb) => log.error(s"Unknown address spent box ${bytesToId(iEb.box.id)}") // spend box should never happen by an unknown address
          case Right(iEb) => trees.put(id, IndexedErgoAddress(id).initBalance.addTx(state.globalTxIndex).addBox(iEb)) // receive box, new address
        }
    }
  }

  /**
    * Add or subtract a box from a token in the buffer or in database.
    *
    * @param id             - token id
    * @param spendOrReceive - IndexedErgoBox to receive (Right) or spend (Left)
    */
  private def findAndUpdateToken(id: ModifierId, spendOrReceive: Either[IndexedErgoBox, IndexedErgoBox]): Unit = {
    tokens.get(id).map { token =>
      spendOrReceive match {
        case Left(iEb) => token.spendBox(iEb, Some(history)) // spend box
        case Right(iEb) => token.addBox(iEb) // receive box
      }
      return
    }
    history.typedExtraIndexById[IndexedToken](uniqueId(id)) match { // token not found in last saveLimit modifiers
      case Some(x) =>
        spendOrReceive match {
          case Left(iEb) => tokens.put(id, x.spendBox(iEb, Some(history))) // spend box
          case Right(iEb) => tokens.put(id, x.addBox(iEb)) // receive box
        }
      case None => // token not found at all
        log.error(s"Unknown token $id") // spend box should never happen by an unknown token
    }
  }

  private def findAndUpdateTemplate(id: ModifierId, spendOrReceive: Either[IndexedErgoBox, IndexedErgoBox]): Unit = {
    templates.get(id).map { template =>
      spendOrReceive match {
        case Left(iEb) => template.spendBox(iEb, Some(history)) // spend box
        case Right(iEb) => template.addBox(iEb) // receive box
      }
      return
    }
    history.typedExtraIndexById[IndexedContractTemplate](id) match {
      case Some(x) =>
        spendOrReceive match {
          case Left(iEb) => templates.put(id, x.spendBox(iEb, Some(history))) // spend box
          case Right(iEb) => templates.put(id, x.addBox(iEb)) // receive box
        }
      case None => // template not found at all
        spendOrReceive match {
          case Left(iEb) => log.error(s"Unknown template spent box ${bytesToId(iEb.box.id)}") // spend box should never happen by an unknown template
          case Right(iEb) => templates.put(id, IndexedContractTemplate(id).addBox(iEb)) // receive box, new template
        }

    }
  }

  /**
    * @return number of indexes in all buffers
    */
  private def modCount: Int = general.length + boxes.size + trees.size + templates.size + tokens.size

  /**
    * Write buffered indexes to database and clear buffers.
    */
  private def saveProgress(state: IndexerState): Unit = {

    val start: Long = System.currentTimeMillis

    // perform segmentation on big addresses and save their internal segment buffer
    trees.values.foreach { tree =>
      tree.buffer.values.foreach(seg => segments.put(seg.id, seg))
      tree.splitToSegments.foreach(seg => segments.put(seg.id, seg))
    }

    templates.values.foreach { template =>
      template.buffer.values.foreach(seg => segments.put(seg.id, seg))
      template.splitToSegments.foreach(seg => segments.put(seg.id, seg))
    }

    // perform segmentation on big tokens and save their internal segment buffer
    tokens.values.foreach { token =>
      token.buffer.values.foreach(seg => segments.put(seg.id, seg))
      token.splitToSegments.foreach(seg => segments.put(seg.id, seg))
    }

    // insert modifiers and progress info to db
    val indexesToInsert = Array(
      (IndexedHeightKey, ByteBuffer.allocate(4).putInt(state.indexedHeight).array),
      (GlobalTxIndexKey, ByteBuffer.allocate(8).putLong(state.globalTxIndex).array),
      (GlobalBoxIndexKey, ByteBuffer.allocate(8).putLong(state.globalBoxIndex).array),
      (RollbackToKey, ByteBuffer.allocate(4).putInt(state.rollbackTo).array)
    ) ++ rentPuts.iterator.map { case (k, v) => (k.data, v) }
    val keysToRemove = rentRemovals.iterator.map(_.data).toArray
    onSaveProgress(indexesToInsert, keysToRemove)
    historyStorage.insertExtra(
      indexesToInsert,
      (((((general ++= boxes.values) ++= trees.values) ++= templates.values) ++= tokens.values) ++= segments.values).toArray,
      keysToRemove
    )

    log.debug(s"Processed ${trees.size} ErgoTrees with ${boxes.size} boxes and inserted them to database in ${System.currentTimeMillis - start}ms")

    // clear buffers for next batch
    general.clear()
    boxes.clear()
    trees.clear()
    templates.clear()
    tokens.clear()
    segments.clear()
    rentPuts.clear()
    rentRemovals.clear()
  }

  /**
    * Process a batch of BlockTransactions into memory and occasionally write them to database.
    *
    * @param state     - current indexer state
    * @param headerOpt - header to index block transactions of (used after caught up with chain)
    */
  protected def index(state: IndexerState, headerOpt: Option[Header] = None): IndexerState = {
    val btOpt = headerOpt.flatMap { header =>
      history.typedModifierById[BlockTransactions](header.transactionsId)
    }.orElse(getBlockTransactionsAt(state.indexedHeight))
    val height = headerOpt.map(_.height).getOrElse(state.indexedHeight)

    if (btOpt.isEmpty) {
      log.error(s"Could not read block $height / $chainHeight from database, waiting for new block until retrying")
      return state.decrementIndexedHeight.copy(caughtUp = true)
    }

    val txs: Seq[ErgoTransaction] = btOpt.get.txs

    var boxCount: Int = 0
    var newState: IndexerState = state

    // record transactions and boxes
    cfor(0)(_ < txs.length, _ + 1) { n =>

      val tx: ErgoTransaction = txs(n)
      val inputs: Array[Long] = Array.ofDim[Long](tx.inputs.length)
      val outputs: Array[Long] = Array.ofDim[Long](tx.outputs.length)

      inputTokens.clear()

      //process transaction inputs
      if (height > 1) { //only after 1st block (skip genesis box)
        cfor(0)(_ < tx.inputs.size, _ + 1) { i =>
          val boxId = bytesToId(tx.inputs(i).boxId)
          val spendingProof = tx.inputs(i).spendingProof
          if (findAndSpendBox(boxId, tx.id, height, spendingProof)) { // spend box and add tx
            val iEb = boxes(boxId)
            if (rentWritesEnabled) {
              // Cancel a pending put when the box was created in this same
              // unflushed batch; otherwise the row is on disk and must be deleted.
              val rk = ByteArrayWrapper(rentKey(iEb.box.creationHeight, iEb.globalIndex))
              if (rentPuts.remove(rk).isEmpty) rentRemovals.add(rk)
            }
            findAndUpdateTree(hashErgoTree(iEb.box.ergoTree), Left(iEb))(newState)
            findAndUpdateTemplate(hashTreeTemplate(iEb.box.ergoTree), Left(iEb))
              cfor(0)(_ < iEb.box.additionalTokens.length, _ + 1) { j =>
              findAndUpdateToken(iEb.box.additionalTokens(j)._1.toModifierId, Left(iEb))
            }
            inputs(i) = iEb.globalIndex
          } else {
            log.warn(s"Not found input box: $boxId")
          }
        }
      }

      //process transaction outputs
      cfor(0)(_ < tx.outputs.size, _ + 1) { i =>
        val iEb: IndexedErgoBox = new IndexedErgoBox(height, None, None, None, tx.outputs(i), newState.globalBoxIndex)
        boxes.put(iEb.id, iEb) // box by id
        if (rentWritesEnabled) {
          rentPuts.put(
            ByteArrayWrapper(rentKey(iEb.box.creationHeight, iEb.globalIndex)),
            fastIdToBytes(iEb.id)
          )
        }
        general += NumericBoxIndex(newState.globalBoxIndex, iEb.id) // box id by global box number
        outputs(i) = iEb.globalIndex

        // box by address
        findAndUpdateTree(hashErgoTree(iEb.box.ergoTree), Right(boxes(iEb.id)))(newState)

        // box by template
        findAndUpdateTemplate(hashTreeTemplate(iEb.box.ergoTree), Right(boxes(iEb.id)))

        // check if box is creating new tokens, if yes record them
        cfor(0)(_ < iEb.box.additionalTokens.length, _ + 1) { j =>
          val idMatch = java.util.Arrays.equals(iEb.box.additionalTokens(j)._1.toArray, tx.inputs.head.boxId)
          if (idMatch && !inputTokens.contains(iEb.box.additionalTokens(j)._1.toArray.toSeq)) {
            val token = IndexedToken.fromBox(iEb, j)
            tokens.get(token.tokenId) match {
              case Some(t) => // same new token created in multiple boxes -> add amounts
                tokens.put(token.tokenId, t.addEmissionAmount(token.amount.get))
              case None => tokens.put(token.tokenId, token) // new token
            }
          }
          findAndUpdateToken(iEb.box.additionalTokens(j)._1.toModifierId, Right(iEb))
        }

        newState = newState.incrementBoxIndex
        boxCount += 1

      }

      //process transaction
      general += IndexedErgoTransaction.fromTx(tx, n, height, newState.globalTxIndex, inputs, outputs)
      general += NumericTxIndex(newState.globalTxIndex, tx.id)

      newState = newState.incrementTxIndex

    }

    log.info(s"Buffered block $height / $chainHeight [txs: ${txs.length}, boxes: $boxCount] (buffer: $modCount / $saveLimit)")

    val maxHeight = headerOpt.map(_.height).getOrElse(chainHeight)
    newState.copy(caughtUp = newState.indexedHeight == maxHeight)
  }

  /**
    * Remove all indexes after a given height and revert address balances.
    *
    * @param state  - current state of indexer
    * @param height - forking height (height of last common block)
    */
  private def removeAfter(state: IndexerState, height: Int): IndexerState = {

    var newState: IndexerState = state

    saveProgress(newState)
    log.info(s"Rolling back indexes from ${state.indexedHeight} to $height")

    try {
      val lastTxToKeep: ErgoTransaction = getLastTxForHeight(height)
      val txTarget: Long = history.typedExtraIndexById[IndexedErgoTransaction](lastTxToKeep.id).get.globalIndex
      val boxTarget: Long = history.typedExtraIndexById[IndexedErgoBox](bytesToId(lastTxToKeep.outputs.last.id)).get.globalIndex
      val toRemove: ArrayBuffer[ModifierId] = ArrayBuffer.empty[ModifierId]
      val rentKeysToRemove: ArrayBuffer[Array[Byte]] = ArrayBuffer.empty[Array[Byte]]

      // remove all tx indexes
      newState = newState.decrementTxIndex
      while (newState.globalTxIndex > txTarget) {
        val tx: IndexedErgoTransaction = NumericTxIndex.getTxByNumber(history, newState.globalTxIndex).get
        tx.inputNums.map(NumericBoxIndex.getBoxByNumber(history, _).get).foreach { iEb => // undo all spendings

          iEb.spendingHeightOpt = None
          iEb.spendingTxIdOpt = None
          iEb.spendingProofOpt = None

          val address = history.typedExtraIndexById[IndexedErgoAddress](hashErgoTree(iEb.box.ergoTree)).get.addBox(iEb, record = false)
          address.findAndModBox(iEb.globalIndex, history)

          val template = history.typedExtraIndexById[IndexedContractTemplate](hashTreeTemplate(iEb.box.ergoTree)).get
          template.findAndModBox(iEb.globalIndex, history)

          // Box is unspent again, so its rent row must come back.
          historyStorage.insertExtra(
            Array((rentKey(iEb.box.creationHeight, iEb.globalIndex), fastIdToBytes(iEb.id))),
            Array[ExtraIndex](iEb, address, template) ++ address.buffer.values ++ template.buffer.values
          )

          cfor(0)(_ < iEb.box.additionalTokens.length, _ + 1) { i =>
            history.typedExtraIndexById[IndexedToken](IndexedToken.fromBox(iEb, i).id).map { token =>
              token.findAndModBox(iEb.globalIndex, history)
              historyStorage.insertExtra(Array.empty, Array[ExtraIndex](token) ++ token.buffer.values)
            }
          }
        }
        toRemove += tx.id // tx by id
        toRemove += bytesToId(NumericTxIndex.indexToBytes(newState.globalTxIndex)) // tx id by number
        newState = newState.decrementTxIndex
      }
      newState = newState.incrementTxIndex

      // remove all box indexes, tokens and address balances
      //
      // Ordering dependency: this loop (rent deletes, via rentKeysToRemove
      // below) must run entirely AFTER the tx-undo loop above (rent
      // re-inserts, `historyStorage.insertExtra` a few lines up) has finished
      // for all boxes. A box can be un-spent by the loop above and then
      // itself rolled back past its creation height by this loop -- if this
      // loop's delete for that box's rent key ran before the earlier loop's
      // re-insert, the delete would be clobbered by a later insert and the
      // row would wrongly survive. This is NOT about final-flush ordering: a
      // per-iteration flush inside this loop would still be correct, since
      // every re-insert from the loop above is already durable by the time
      // this loop starts.
      newState = newState.decrementBoxIndex
      while (newState.globalBoxIndex > boxTarget) {
        val iEb: IndexedErgoBox = NumericBoxIndex.getBoxByNumber(history, newState.globalBoxIndex).get
        cfor(0)(_ < iEb.box.additionalTokens.length, _ + 1) { i =>
          history.typedExtraIndexById[IndexedToken](IndexedToken.fromBox(iEb, i).id).map { token =>
            if (token.boxId.get == iEb.id) { // token created, delete
              toRemove += token.id
              log.info(s"Removing token ${token.tokenId} created in box ${iEb.id} at height ${iEb.inclusionHeight}")
            } else // no token created, update
              toRemove ++= token.rollback(txTarget, boxTarget, _history)
          }
        }
        history.typedExtraIndexById[IndexedErgoAddress](hashErgoTree(iEb.box.ergoTree)).map { address =>
          address.spendBox(iEb)
          toRemove ++= address.rollback(txTarget, boxTarget, _history)
        }
        history.typedExtraIndexById[IndexedContractTemplate](hashTreeTemplate(iEb.box.ergoTree)).map { template =>
          template.spendBox(iEb)
          toRemove ++= template.rollback(txTarget, boxTarget, _history)
        }
        // Every removed box must end with no rent row — whether it was spent
        // (row already gone; absent-key delete is a documented no-op) or unspent.
        rentKeysToRemove += rentKey(iEb.box.creationHeight, iEb.globalIndex)
        toRemove += iEb.id // box by id
        toRemove += bytesToId(NumericBoxIndex.indexToBytes(newState.globalBoxIndex)) // box id by number
        newState = newState.decrementBoxIndex
      }
      newState = newState.incrementBoxIndex

      // Save changes
      newState = newState.copy(indexedHeight = height, rollbackTo = 0, caughtUp = true)
      // Three separate batches here (rent deletes, then object removal, then
      // saveProgress) are safe without the single-batch atomicity that
      // insertExtra's live-indexing flush guarantees: `rollbackTo` was already
      // made durable by the saveProgress call above, before this method did
      // any mutation, so a crash partway through leaves it set; on restart the
      // rollback is simply replayed from the top, and every step here (rent
      // key removal, object removal, and the final saveProgress) is an
      // idempotent overwrite/no-op when repeated. Rent rows are removed first
      // only to make an interrupted-and-resumed rollback re-derive the same
      // set of pending removals from `toRemove`/`rentKeysToRemove`, not for
      // any atomicity reason.
      historyStorage.insertExtra(Array.empty, Array.empty, rentKeysToRemove.toArray)
      historyStorage.removeExtra(toRemove.toArray)
      saveProgress(newState)
    } catch {
      case t: Throwable => log.error(s"removeAfter during rollback failed due to: ${t.getMessage}", t)
    }

    newState
  }

  protected val RentBackfillChunkSize: Int = 10000

  /** One chunk of the resumable storage-rent backfill. Walks the
    * dense numeric box index [cursor, min(cursor + chunk, watermark)) and
    * writes rent rows for boxes still unspent. Self-messages for the next
    * chunk until the watermark is reached, at which point it writes the -1
    * completion sentinel.
    */
  protected def backfillRentChunk(watermark: Long): Unit = {
    val cursor = ExtraIndexer.rentBackfillCursor(history).getOrElse(return)
    val end = math.min(cursor + RentBackfillChunkSize, watermark)
    val rows = ArrayBuffer.empty[(Array[Byte], Array[Byte])]
    cfor(cursor)(_ < end, _ + 1) { n =>
      // getBoxByNumber has an UNGUARDED .get on the inner NumericBoxIndex lookup
      // (NumericIndex.scala:94-99) and throws NoSuchElementException on any gap.
      // A throw here restarts the actor, which restarts the backfill, which
      // throws again -- an infinite restart loop. Absorb and log instead.
      val boxOpt = try NumericBoxIndex.getBoxByNumber(history, n) catch {
        case _: NoSuchElementException =>
          log.warn(s"Rent backfill: no box index at $n, skipping")
          None
      }
      boxOpt.foreach { iEb =>
        // Buffer first, exactly like findAndSpendBox, so an unflushed spend is seen.
        val live = boxes.getOrElse(iEb.id, iEb)
        if (!live.isSpent) {
          rows += ((rentKey(live.box.creationHeight, live.globalIndex), fastIdToBytes(live.id)))
        }
      }
    }
    val nextCursor = if (end >= watermark) -1L else end
    // Rows and cursor in ONE batch: the cursor can never advance ahead of its rows.
    rows += ((RentBackfillKey, ByteBuffer.allocate(8).putLong(nextCursor).array))
    historyStorage.insertExtra(rows.toArray, Array.empty)
    if (nextCursor >= 0L) self ! BackfillRentChunk(watermark)
    else log.info("Storage-rent backfill complete")
    // No-op in production; lets tests block on the existing lock/done handshake
    // until this chunk (or the whole backfill) has been durably written.
    caughtUpHook()
  }

  protected def loaded(state: IndexerState): Receive = {

    case Index() if !state.caughtUp && !state.rollbackInProgress =>
      val newState = index(state.incrementIndexedHeight)
      if (modCount >= saveLimit) saveProgress(newState)
      context.become(receive.orElse(loaded(newState)))
      self ! Index()

    case Index() if state.caughtUp =>
      if (modCount > 0) saveProgress(state)
      blockCache.clear()
      caughtUpHook()
      log.info("Indexer caught up with chain")

    // after the indexer caught up with the chain, stay up to date
    case FullBlockApplied(header: Header) if state.caughtUp && !state.rollbackInProgress =>
      if (header.height == state.indexedHeight + 1) { // applied block is next in line
        val newState: IndexerState = index(state.incrementIndexedHeight, Some(header))
        saveProgress(newState)
        context.become(receive.orElse(loaded(newState)))
        caughtUpHook(header.height)
      } else if (header.height > state.indexedHeight + 1) { // applied block is ahead of indexer
        context.become(receive.orElse(loaded(state.copy(caughtUp = false))))
        self ! Index()
      } else // applied block has already been indexed, skipping duplicate
        log.warn(s"Skipping block ${header.id} applied at height ${header.height}, indexed height is ${state.indexedHeight}")

    case Rollback(branchPoint: ModifierId) =>
      if (state.rollbackInProgress) {
        log.warn(s"Rollback already in progress")
        stash()
      } else {
        history.heightOf(branchPoint) match {
          case Some(branchHeight) =>
            if (branchHeight < state.indexedHeight) {
              context.become(receive.orElse(loaded(state.copy(rollbackTo = branchHeight))))
              self ! RemoveAfter(branchHeight)
            }
          case None =>
            log.error(s"No rollback height found for $branchPoint")
            val newState = state.copy(rollbackTo = 0)
            context.become(receive.orElse(loaded(newState)))
            unstashAll()
        }
      }

    case RemoveAfter(branchHeight: Int) if state.rollbackInProgress =>
      blockCache.clear()
      readingUpTo = 0
      val newState = removeAfter(state, branchHeight)
      context.become(receive.orElse(loaded(newState)))
      caughtUpHook()
      log.info(s"Successfully rolled back indexes to $branchHeight")
      unstashAll()

    case GetSegmentThreshold =>
      sender ! segmentThreshold

    case BackfillRentChunk(watermark) => backfillRentChunk(watermark)

    case _ =>

  }

}


/**
  * Actor that constructs an index of database elements.
  *
  * @param cacheSettings - cacheSettings to use for saveLimit size
  * @param ae            - ergo address encoder to use for handling addresses
  */
class ExtraIndexer(cacheSettings: CacheSettings,
                   ae: ErgoAddressEncoder)
  extends ExtraIndexerBase {

  override val saveLimit: Int = cacheSettings.history.extraCacheSize * 20

  override implicit val segmentThreshold: Int = 512

  override implicit val addressEncoder: ErgoAddressEncoder = ae

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[FullBlockApplied])
    context.system.eventStream.subscribe(self, classOf[Rollback])
    context.system.eventStream.subscribe(self, classOf[StartExtraIndexer])
  }

  override def postStop(): Unit = {
    log.error(s"Stopped extra indexer")
    super.postStop()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.error(s"Attempted extra indexer restart due to ${reason.getMessage} ", reason)
    super.preRestart(reason, message)
  }

  override def receive: Receive = {

    case StartExtraIndexer(history: ErgoHistory) =>
      log.info(s"Starting extra indexer")
      _history = history
      val state = IndexerState.fromHistory(history)
      context.become(receive.orElse(loaded(state)))
      log.info(s"Started extra indexer at height ${state.indexedHeight}")
      self ! Index()
      unstashAll()

      // Storage-rent backfill: three-way branch on cursor state (in progress /
      // needs starting / already complete -- see the cases below).
      ExtraIndexer.rentBackfillCursor(this.history) match {
        case Some(c) =>
          // Interrupted backfill: resume. Watermark = CURRENT globalBoxIndex --
          // boxes indexed live since the original watermark already have rows;
          // re-deriving them is an idempotent overwrite.
          log.info(s"Resuming storage-rent backfill from $c up to ${state.globalBoxIndex}")
          self ! BackfillRentChunk(state.globalBoxIndex)
        case None if historyStorage.get(RentBackfillKey).isEmpty =>
          if (state.globalBoxIndex > 0) {
            // Pre-feature database: backfill [0, W).
            historyStorage.insertExtra(
              Array((RentBackfillKey, ByteBuffer.allocate(8).putLong(0L).array)), Array.empty)
            log.info(s"Starting storage-rent backfill up to global box index ${state.globalBoxIndex}")
            self ! BackfillRentChunk(state.globalBoxIndex)
          } else {
            // Fresh database: live indexing builds everything. Write the -1
            // sentinel NOW so the key is never absent on a later restart --
            // otherwise the first restart triggers the spurious backfill above.
            historyStorage.insertExtra(
              Array((RentBackfillKey, ByteBuffer.allocate(8).putLong(-1L).array)), Array.empty)
          }
        case None => // sentinel present: backfill already complete, nothing to do
      }

  }
}

object ExtraIndexer {

  type ExtraIndexTypeId = Byte

  object ReceivableMessages {
    /**
      * Initialize ExtraIndexer and start indexing.
      *
      * @param history - handle to database
      */
    case class StartExtraIndexer(history: ErgoHistory)

    /**
      * Retreive the currently used segment treshold
      */
    case class GetSegmentThreshold()

    /**
      * Index block at current indexer height
      */
    case class Index()

    /**
      * Remove and roll back all indexes after branchHeight
      *
      * @param branchHeight - height of last block to keep
      */
    case class RemoveAfter(branchHeight: Int)

    /** Process one chunk of the rent backfill, then self-message for the next.
      * Runs through the actor mailbox, so it interleaves with — never runs
      * concurrently with — live block indexing. That sequentiality IS the
      * concurrency control; no locks needed.
      */
    case class BackfillRentChunk(watermark: Long)
  }

  /**
    * @return address constructed from the ErgoTree of this box
    */
  def getAddress(tree: ErgoTree)(implicit ae: ErgoAddressEncoder): ErgoAddress =
    tree.root match {
      case Right(_) => ae.fromProposition(tree).get // default most of the time
      case Left(_) => new Pay2SAddress(tree, tree.bytes) // needed for burn address 4MQyMKvMbnCJG3aJ
    }

  private val hexIndex: Array[Byte] = {
    val index = Array.fill[Byte](128)(0xff.toByte)
    "0123456789abcdef".toCharArray.zipWithIndex.foreach { case (c, i) =>
      index(c) = i.toByte
    }
    "abcdef".toCharArray.foreach { c =>
      index(c.toUpper) = index(c)
    }
    index
  }

  /**
    * Faster id to bytes - no safety checks
    *
    * @param id - ModifierId to convert to byte representation
    * @return an array of bytes
    */
  private[extra] def fastIdToBytes(id: ModifierId): Array[Byte] = {
    val x: Array[Byte] = new Array[Byte](id.length / 2)
    cfor(0)(_ < id.length, _ + 2) { i => x(i / 2) = ((hexIndex(id(i)) << 4) | hexIndex(id(i + 1))).toByte }
    x
  }

  /**
    * Current newest database schema version. Used to force extra database resync.
    */
  val NewestVersion: Int = 6
  val NewestVersionBytes: Array[Byte] = ByteBuffer.allocate(4).putInt(NewestVersion).array

  val IndexedHeightKey: Array[Byte] = Algos.hash("indexed height")
  val GlobalTxIndexKey: Array[Byte] = Algos.hash("txns height")
  val GlobalBoxIndexKey: Array[Byte] = Algos.hash("boxes height")
  val RollbackToKey: Array[Byte] = Algos.hash("rollback to")
  val SchemaVersionKey: Array[Byte] = Algos.hash("schema version")

  /** First byte of raw ordered rent-index keys.
    *
    * Raw key-space registry for extraStore:
    *   0x72 ('r') — storage-rent unspent-by-creation-height index, 13-byte keys.
    * Every other extraStore key is a 32-byte blake2b hash. This is the first
    * key-space in extraStore that cannot round-trip through ModifierId, which is
    * why raw-key removal exists in HistoryStorage.
    */
  val RentKeyPrefix: Byte = 0x72

  /** 1 prefix byte + 4 bytes creationHeight + 8 bytes globalBoxIndex. */
  val RentKeyLength: Int = 13

  /** Progress cursor for the storage-rent backfill. 32-byte hash, so
    * isRentKey can never match it.
    */
  val RentBackfillKey: Array[Byte] = Algos.hash("rent backfill")

  /** Encode a rent-index key. Big-endian so byte order equals numeric order —
    * which holds only for NON-NEGATIVE components, hence the require. A negative
    * creationHeight sorts above every positive one and would silently vanish from
    * every range scan. The consensus rule enforcing creationHeight >= 0 is
    * disabled for block version 1 (ErgoTransaction.scala:173), so this guard is
    * real. Callers must short-circuit before calling with a negative cutoff.
    */
  def rentKey(creationHeight: Int, globalIndex: Long): Array[Byte] = {
    require(creationHeight >= 0, s"negative creationHeight in rent key: $creationHeight")
    require(globalIndex >= 0, s"negative globalIndex in rent key: $globalIndex")
    ByteBuffer.allocate(RentKeyLength)
      .put(RentKeyPrefix)
      .putInt(creationHeight)
      .putLong(globalIndex)
      .array
  }

  /** Mandatory filter for every scan over the rent key-space. A 32-byte hash
    * whose first byte is 0x72 and whose bytes 1-4 encode a value below the scan
    * cutoff sorts INSIDE a rent range; decoding it yields garbage.
    */
  def isRentKey(key: Array[Byte]): Boolean =
    key.length == RentKeyLength && key(0) == RentKeyPrefix

  def getIndex(key: Array[Byte], history: HistoryStorage): ByteBuffer =
    ByteBuffer.wrap(history.modifierBytesById(bytesToId(key)).getOrElse(Array.fill[Byte](8) {
      0
    }))

  def getIndex(key: Array[Byte], history: ErgoHistoryReader): ByteBuffer = {
    getIndex(key, history.historyStorage)
  }

  /** Public scan over the rent key-space for API routes, which cannot reach the
    * protected[history] historyStorage directly. Always applies the `isRentKey`
    * filter itself, rather than taking a filter from the caller, so a route can
    * never accidentally scan raw extraStore keys outside the rent key-space.
    * Propagates RangeScanBudgetExceeded; descending callers must pass a finite
    * budget.
    */
  def rentRange(history: ErgoHistoryReader,
                start: Array[Byte], end: Array[Byte],
                offset: Int, limit: Int, reverse: Boolean,
                visitBudget: Long = Long.MaxValue): Array[(Array[Byte], Array[Byte])] =
    history.historyStorage.getExtraRange(start, end, offset, limit, reverse, visitBudget)(isRentKey)

  /** Backfill progress. None = complete, or never needed (no key on a database
    * that was built with the rent index from the start). Some(c) = in progress,
    * next globalBoxIndex to process is c. Routes gate on Some.
    */
  def rentBackfillCursor(history: ErgoHistoryReader): Option[Long] =
    history.historyStorage.get(RentBackfillKey) match {
      case Some(bytes) if bytes.length == 8 =>
        val c = ByteBuffer.wrap(bytes).getLong
        if (c < 0L) None else Some(c)
      case _ => None
    }

  def apply(chainSettings: ChainSettings, cacheSettings: CacheSettings)(implicit system: ActorSystem): ActorRef = {
    val props = Props.create(classOf[ExtraIndexer], cacheSettings, chainSettings.addressEncoder)
    system.actorOf(props.withDispatcher(GlobalConstants.IndexerDispatcher))
  }
}
