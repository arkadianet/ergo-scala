package org.ergoplatform.nodeView.history.extra

import akka.actor.{ActorRef, ActorSystem, Props}
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.http.api.SortDirection
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{RemoteBlockApplied, Rollback}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.ReceivableMessages.{BackfillRentChunk, Index, StartExtraIndexer}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.{RentBackfillKey, isRentKey}
import org.ergoplatform.nodeView.history.extra.IndexedContractTemplateSerializer.hashTreeTemplate
import org.ergoplatform.nodeView.history.extra.IndexedErgoAddressSerializer.hashErgoTree
import org.ergoplatform.nodeView.history.extra.SegmentSerializer.{boxSegmentId, txSegmentId}
import org.ergoplatform.nodeView.history.ErgoHistoryReader
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.db.ByteArrayWrapper
import scorex.util.{ModifierId, bytesToId}
import spire.implicits.cfor

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.reflect.ClassTag

class ExtraIndexerSpecification extends ErgoCorePropertyTest with ExtraIndexerTestHarness {
  import org.ergoplatform.utils.ErgoNodeTestConstants._

  implicit val addressEncoder: ErgoAddressEncoder = settings.addressEncoder
  val initSettings: ErgoSettings = settings

  type ID_LL = mutable.HashMap[ModifierId,(Long,Long)]

  val HEIGHT: Int = 50
  val BRANCHPOINT: Int = HEIGHT / 2
  implicit val segmentThreshold: Int = 8

  val system: ActorSystem = ActorSystem.create("indexer-test")
  val indexer: ActorRef = system.actorOf(Props.create(classOf[ExtraIndexerTestActor], this))

  def history: ErgoHistoryReader = _history.getReader

  def manualIndex(limit: Int): (ID_LL, // address -> (erg,tokenSum)
                                ID_LL, // template -> (spentBoxCount,unspentBoxCount)
                                ID_LL, // tokenId -> (boxesCount,_)
                                Int, // txs indexed
                                Int) = { // boxes indexed
    var txsIndexed = 0
    var boxesIndexed = 0
    val addresses: ID_LL = mutable.HashMap[ModifierId, (Long, Long)]()
    val templates: ID_LL = mutable.HashMap[ModifierId, (Long, Long)]()
    val indexedTokens: ID_LL = mutable.HashMap[ModifierId, (Long, Long)]()
    cfor(1)(_ <= limit, _ + 1) { i =>
      val header = history.headerIdsAtHeight(i).last
      val block = history.getFullBlock(history.typedModifierById[Header](header).get)
      block.get.transactions.foreach { tx =>
        txsIndexed += 1
        if (i != 1) {
          tx.inputs.foreach { input =>
            val iEb: IndexedErgoBox = _history.getReader.typedExtraIndexById[IndexedErgoBox](bytesToId(input.boxId)).get
            val address = hashErgoTree(ExtraIndexer.getAddress(iEb.box.ergoTree)(addressEncoder).script)
            val prevAddress = addresses(address)
            addresses.put(address, (prevAddress._1 - iEb.box.value, prevAddress._2 - iEb.box.additionalTokens.toArray.map(_._2).sum))
            val template = hashTreeTemplate(ExtraIndexer.getAddress(iEb.box.ergoTree)(addressEncoder).script)
            val prevTemplate = templates(template)
            templates.put(template, (prevTemplate._1 + 1, prevTemplate._2 - 1))
          }
        }
        tx.outputs.foreach { output =>
          boxesIndexed += 1
          val address = hashErgoTree(ExtraIndexer.getAddress(output.ergoTree)(addressEncoder).script)
          val prevAddress = addresses.getOrElse(address, (0L, 0L))
          addresses.put(address, (prevAddress._1 + output.value, prevAddress._2 + output.additionalTokens.toArray.map(_._2).sum))
          val template = hashTreeTemplate(ExtraIndexer.getAddress(output.ergoTree)(addressEncoder).script)
          val prevTemplate = templates.getOrElse(template, (0L, 0L))
          templates.put(template, (prevTemplate._1, prevTemplate._2 + 1))
          cfor(0)(_ < output.additionalTokens.length, _ + 1) { j =>
            val token = IndexedToken.fromBox(new IndexedErgoBox(i, None, None, None, output, 0), j)
            val prev2 = indexedTokens.getOrElse(token.id, (0L, 0L))
            indexedTokens.put(token.id, (prev2._1 + 1, 0))
          }
        }
      }
    }
    (addresses, templates, indexedTokens, txsIndexed, boxesIndexed)
  }

  def checkSegmentables[T <: Segment[T] : ClassTag](segmentables: ID_LL,
                                                    isChild: Boolean = false,
                                                    check: ((T, (Long, Long))) => Boolean): Int = {
    var errors: Int = 0
    segmentables.foreach { segmentable =>
      history.typedExtraIndexById[T](segmentable._1) match {
        case Some(obj: T) =>
          if (isChild) { // this is a segment
            // check tx segments
            val txSegments: ID_LL = mutable.HashMap.empty[ModifierId, (Long, Long)]
            txSegments ++= (0 until obj.txSegmentCount).map(n => obj.factory(txSegmentId(obj.id, n)).id).map(Tuple2(_, (0L, 0L)))
            checkSegmentables(txSegments, isChild = true, check) shouldBe 0
            // check box segments
            val boxSegments: ID_LL = mutable.HashMap.empty[ModifierId, (Long, Long)]
            boxSegments ++= (0 until obj.boxSegmentCount).map(n => obj.factory(boxSegmentId(obj.id, n)).id).map(Tuple2(_, (0L, 0L)))
            checkSegmentables(boxSegments, isChild = true, check) shouldBe 0
          } else { // this is the parent object
            // check properties of object
            if (!check((obj, segmentable._2)))
              errors += 1
          }
          // check boxes in memory
          obj.boxes.foreach { boxNum =>
            NumericBoxIndex.getBoxByNumber(history, boxNum) match {
              case Some(iEb) =>
                if (iEb.isSpent)
                  boxNum.toInt should be <= 0
                else
                  boxNum.toInt should be >= 0
              case None =>
                System.err.println(s"Box $boxNum not found in database")
                errors += 1
            }
          }
          // check txs in memory
          obj.txs.foreach { txNum =>
            NumericTxIndex.getTxByNumber(history, txNum) shouldNot be(empty)
          }

        case None =>
          System.err.println(s"Segmentable object ${segmentable._1} should exist, but was not found")
          errors += 1
      }
    }
    errors
  }

  def checkAddresses(addresses: ID_LL): Int =
    checkSegmentables[IndexedErgoAddress](addresses, isChild = false, seg => {
      seg._1.balanceInfo.get.nanoErgs == seg._2._1 && seg._1.balanceInfo.get.tokens.map(_._2).sum == seg._2._2
    })

  def checkTemplates(templates: ID_LL): Int =
    checkSegmentables[IndexedContractTemplate](templates, isChild = false, seg => {
      seg._1.boxCount == (seg._2._1 + seg._2._2)
    })

  def checkTokens(indexedTokens: ID_LL): Int =
    checkSegmentables[IndexedToken](indexedTokens, isChild = false, seg => {
      seg._1.boxCount == seg._2._1
    })

  /** Assert the on-disk rent index equals the oracle using TWO code paths that
    * share nothing with getRangeWithFilter — otherwise a bug in the new scan
    * would make this pass vacuously.
    *   - no missing rows: HistoryStorage.get raw point lookups (predates this feature)
    *   - no ghost rows:   getAllExtraRaw -> KVStoreReader.getWithFilter (ditto)
    */
  def checkRentIndex(limit: Int): Unit = {
    val expected = manualRentSet(limit)
    expected.foreach { case (k, boxId) =>
      withClue(s"missing rent row ${k.data.toSeq}: ") {
        _history.historyStorage.get(k.data).map(bytesToId) shouldBe Some(boxId)
      }
    }
    val onDisk = _history.historyStorage.getAllExtraRaw((k, _) => isRentKey(k))
    onDisk.foreach { case (k, _) =>
      withClue(s"ghost rent row ${k.toSeq}: ") {
        expected.contains(ByteArrayWrapper(k)) shouldBe true
      }
    }
    onDisk.size shouldBe expected.size
  }

  /** Blocks until `pred` holds, polling on the existing lock/done handshake with a
    * short timeout per attempt rather than a single unbounded await. The backfill
    * self-perpetuates through the actor mailbox (each processed chunk fires
    * `caughtUpHook`, same as live indexing), so many signals can arrive in quick
    * succession; a plain single `await()` risks missing one between our check of
    * `pred` and re-entering the wait, which would hang the test forever. Polling
    * makes that impossible: worst case we just re-check on the next tick.
    */
  def awaitCondition(timeoutMs: Long = 30000)(pred: => Boolean): Unit = {
    val deadline = System.currentTimeMillis + timeoutMs
    while (!pred) {
      if (System.currentTimeMillis > deadline)
        throw new RuntimeException("Timed out waiting for backfill condition")
      lock.lock()
      try {
        if (!pred) done.await(50, TimeUnit.MILLISECONDS)
      } finally {
        lock.unlock()
      }
    }
  }

  // seedBackfillCursor now lives on ExtraIndexerTestHarness (org.ergoplatform.nodeView.
  // history.extra.ExtraIndexerTestHarness) so that suites outside this package tree,
  // like the route-level BlockchainApiRouteSpec, can seed the sentinel too --
  // historyStorage is protected[history].

  private def ensureBackfillStarted(): Unit =
    if (_history.historyStorage.get(RentBackfillKey).isEmpty) seedBackfillCursor(0L)

  /** Trigger `chunks` rounds of backfill progress and wait for each to land.
    * Seeds the cursor at 0 on first use, exactly like the production startup
    * wiring would for a pre-feature database. Since a single `BackfillRentChunk`
    * self-perpetuates until the watermark is reached, this waits for the cursor
    * to move at least once per requested round rather than assuming exactly one
    * chunk elapses -- with the small `RentBackfillChunkSize` the test actor uses,
    * one round leaves the backfill well short of complete.
    */
  def runBackfillChunks(actor: ActorRef, chunks: Int): Unit = {
    ensureBackfillStarted()
    val watermark = IndexerState.fromHistory(_history).globalBoxIndex
    cfor(0)(_ < chunks, _ + 1) { _ =>
      val before = ExtraIndexer.rentBackfillCursor(_history.getReader)
      actor ! BackfillRentChunk(watermark)
      awaitCondition() { ExtraIndexer.rentBackfillCursor(_history.getReader) != before }
    }
  }

  /** Drive the backfill to completion against the CURRENT global box index. */
  def runBackfillToCompletion(actor: ActorRef): Unit = {
    ensureBackfillStarted()
    val watermark = IndexerState.fromHistory(_history).globalBoxIndex
    actor ! BackfillRentChunk(watermark)
    awaitCondition() { ExtraIndexer.rentBackfillCursor(_history.getReader).isEmpty }
  }

  // example G-30;R-20;G-35;R-30
  def rollbackWithPattern(pattern: String): Unit = {

    def rollback(n: Int): Unit = {
      println(s"Rollback to $n")
      var state = IndexerState.fromHistory(_history)

      val txIndexBefore = state.globalTxIndex
      val boxIndexBefore = state.globalBoxIndex

      // manually count balances
      val (addresses, templates, indexedTokens, txsIndexed, boxesIndexed) = manualIndex(n)

      // perform rollback
      indexer ! Rollback(history.bestHeaderIdAtHeight(n).get)
      lock.lock()
      done.await()
      state = IndexerState.fromHistory(_history)

      // address balances
      checkAddresses(addresses) shouldBe 0

      addresses.keys.foreach { addr =>
        val utxos = history.typedExtraIndexById[IndexedErgoAddress](addr).get
          .retrieveUtxos(history, ErgoMemPool.empty(settings), 0, 1000, SortDirection.ASC, unconfirmed = false, Set.empty)
        utxos.exists(_.isSpent) shouldBe false
      }

      checkTemplates(templates) shouldBe 0

      // token indexes
      checkTokens(indexedTokens) shouldBe 0

      // check indexnumbers
      state.globalTxIndex shouldBe txsIndexed
      state.globalBoxIndex shouldBe boxesIndexed

      // check txs
      cfor(0)(_ < txIndexBefore, _ + 1) { txNum =>
        val txOpt = history.typedExtraIndexById[NumericTxIndex](bytesToId(NumericTxIndex.indexToBytes(txNum)))
        if (txNum < state.globalTxIndex)
          txOpt shouldNot be(empty)
        else
          txOpt shouldBe None
      }

      // check boxes
      cfor(0)(_ < boxIndexBefore, _ + 1) { boxNum =>
        val boxOpt = history.typedExtraIndexById[NumericBoxIndex](bytesToId(NumericBoxIndex.indexToBytes(boxNum)))
        if (boxNum < state.globalBoxIndex)
          boxOpt shouldNot be(empty)
        else
          boxOpt shouldBe None
      }

      checkRentIndex(n)
    }

    def generate(n: Int): Unit = {
      println(s"Generate to $n")
      indexer ! CreateDB(n)
      indexer ! Index()
      lock.lock()
      done.await()

      val (addresses, _, _, _, _) = manualIndex(n)

      addresses.keys.foreach { addr =>
        val utxos = history.typedExtraIndexById[IndexedErgoAddress](addr).get
          .retrieveUtxos(history, ErgoMemPool.empty(settings), 0, 1000, SortDirection.ASC, unconfirmed = false, Set.empty)
        val trees = utxos.map(_.box.ergoTree).map(hashErgoTree)
        trees.forall(_ == addr) shouldBe true
      }

      addresses.keys.foreach { addr =>
        val utxos = history.typedExtraIndexById[IndexedErgoAddress](addr).get
          .retrieveUtxos(history, ErgoMemPool.empty(settings), 0, 1000, SortDirection.ASC, unconfirmed = false, Set.empty)
        utxos.exists(_.isSpent) shouldBe false
      }

      checkRentIndex(n)
    }

    pattern.split(";").map(_.split("-")).map(x => x(0) -> x(1).toInt).foreach {
      case ("G", n) => generate(n)
      case ("R", n) => rollback(n)
      case _ => System.err.println(s"Malformed rollback pattern: $pattern")
    }

    indexer ! Reset()
  }

  property("transactions") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    val state = IndexerState.fromHistory(_history)
    cfor(0)(_ < state.globalTxIndex, _ + 1) { n =>
      val id = history.typedExtraIndexById[NumericTxIndex](bytesToId(NumericTxIndex.indexToBytes(n)))
      id shouldNot be(empty)
      history.typedExtraIndexById[IndexedErgoTransaction](id.get.m) shouldNot be(empty)
    }
    indexer ! Reset()
  }

  property("boxes") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    val state = IndexerState.fromHistory(_history)
    cfor(0)(_ < state.globalBoxIndex, _ + 1) { n =>
      val id = history.typedExtraIndexById[NumericBoxIndex](bytesToId(NumericBoxIndex.indexToBytes(n)))
      id shouldNot be(empty)
      history.typedExtraIndexById[IndexedErgoBox](id.get.m) shouldNot be(empty)
    }
    indexer ! Reset()
  }

  property("storage rent rows") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    checkRentIndex(HEIGHT)
    indexer ! Reset()
  }

  property("storage rent rows with multi-block batches") {
    val bigBatchIndexer = system.actorOf(
      Props.create(classOf[ExtraIndexerTestActor], this, Int.box(500), Boolean.box(true)))
    bigBatchIndexer ! CreateDB(HEIGHT)
    bigBatchIndexer ! Index()
    lock.lock()
    done.await()
    checkRentIndex(HEIGHT)
    bigBatchIndexer ! Reset()
  }

  property("storage rent backfill populates an existing index") {
    val noRent = system.actorOf(
      Props.create(classOf[ExtraIndexerTestActor], this, Int.box(1), Boolean.box(false)))
    noRent ! CreateDB(HEIGHT)
    noRent ! Index()
    lock.lock()
    done.await()

    // Pre-feature database: indexed, but no rent rows at all.
    _history.historyStorage.getAllExtraRaw((k, _) => isRentKey(k)) shouldBe empty

    runBackfillToCompletion(noRent)

    checkRentIndex(HEIGHT)
    ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe None
    noRent ! Reset()
  }

  property("storage rent backfill resumes from its cursor") {
    val noRent = system.actorOf(
      Props.create(classOf[ExtraIndexerTestActor], this, Int.box(1), Boolean.box(false)))
    noRent ! CreateDB(HEIGHT)
    noRent ! Index()
    lock.lock()
    done.await()

    runBackfillChunks(noRent, chunks = 1)
    ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe defined

    runBackfillToCompletion(noRent)
    checkRentIndex(HEIGHT)
    ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe None
    noRent ! Reset()
  }

  /** Required by spec section 11.2. This is the ONLY test that exercises the
    * buffer-first lookup in backfillRentChunk (`boxes.getOrElse(iEb.id, iEb)`),
    * which is what section 9's lock-free argument rests on. Without it, deleting
    * that lookup passes everything else.
    */
  property("storage rent backfill interleaves with live indexing") {
    // A large saveLimit keeps the live-indexing buffers from flushing until the
    // extension below is fully caught up, so there is a real window in which a
    // freshly-indexed spend of an original, not-yet-backfilled box sits only in
    // the unflushed `boxes` buffer -- exactly what the buffer-first lookup is for.
    val noRent = system.actorOf(
      Props.create(classOf[ExtraIndexerTestActor], this, Int.box(1000000), Boolean.box(false)))
    noRent ! CreateDB(HEIGHT)
    noRent ! Index()
    lock.lock()
    done.await()

    // Start the backfill (self-perpetuates in the background through the shared
    // mailbox) but only wait for the first round of progress.
    runBackfillChunks(noRent, chunks = 1)

    // Extend the chain and index it while the first backfill pass (still
    // targeting the original, smaller watermark) is in flight. Some of the
    // original boxes it hasn't reached yet get spent by the new blocks before
    // the backfill's own walk visits them.
    noRent ! CreateDB(HEIGHT + 10)
    noRent ! Index()

    // Let both the original backfill pass and the extension's indexing fully
    // settle before starting a second pass with an updated watermark: running
    // two chains toward different watermarks concurrently is unsafe (the
    // shorter one can stomp the cursor to -1 while the longer one is still
    // mid-flight).
    awaitCondition() {
      ExtraIndexer.rentBackfillCursor(_history.getReader).isEmpty &&
        IndexerState.fromHistory(_history).caughtUp
    }

    // Cover any boxes the first pass never reached, including the extension's.
    // Re-deriving rows for the already-covered range is an idempotent overwrite.
    seedBackfillCursor(0L)
    runBackfillToCompletion(noRent)

    checkRentIndex(HEIGHT + 10)
    ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe None
    noRent ! Reset()
  }

  /** Brief step 5's sentinel-branch requirement. Drives a REAL StartExtraIndexer
    * round-trip through the production `ExtraIndexer` actor (not a direct call
    * into the branch body, and not `ExtraIndexerTestActor`, which never handles
    * StartExtraIndexer at all) so a future regression in the three-way startup
    * branch would actually be caught. This is the one place in the whole feature
    * where a bug is silent -- a fresh node mistaken for pre-feature, or a
    * mid-backfill restart that never resumes, both surface only as a 503 that
    * never clears, with an otherwise-green suite.
    */
  property("storage rent backfill writes the sentinel on a fresh database") {
    // Build the underlying chain but deliberately never send this actor Index():
    // the extra indexer's OWN progress (IndexedHeightKey/GlobalBoxIndexKey) must
    // still be at its zero default, which is exactly what "fresh database" means
    // to the startup branch -- as opposed to a pre-feature database, which would
    // have indexed height/box progress already recorded.
    val builder = system.actorOf(Props.create(classOf[ExtraIndexerTestActor], this))
    builder ! CreateDB(HEIGHT)
    lock.lock()
    created.await()

    IndexerState.fromHistory(_history).globalBoxIndex shouldBe 0
    _history.historyStorage.get(RentBackfillKey) shouldBe empty

    // Real production actor, rent writes on by default -- the actual code path
    // Task 11's route gate depends on, not a test double.
    val realIndexer = system.actorOf(
      Props.create(classOf[ExtraIndexer], initSettings.cacheSettings, addressEncoder))
    realIndexer ! StartExtraIndexer(_history)

    awaitCondition() { _history.historyStorage.get(RentBackfillKey).isDefined }

    // Both assertions together are what distinguish "sentinel written" from
    // "key never written at all" -- rentBackfillCursor alone reads None in
    // both cases, so it can't tell them apart on its own.
    ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe None
    _history.historyStorage.get(RentBackfillKey) shouldBe defined

    system.stop(realIndexer)
    builder ! Reset()
  }

  property("addresses") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    val (addresses, _, _, _, _) = manualIndex(HEIGHT)
    checkAddresses(addresses) shouldBe 0
    indexer ! Reset()
  }

  property("templates") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    val (_, templates, _, _, _) = manualIndex(HEIGHT)
    checkTemplates(templates) shouldBe 0
    indexer ! Reset()
  }

  property("tokens") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    val (_, _, indexedTokens, _, _) = manualIndex(HEIGHT)
    checkTokens(indexedTokens) shouldBe 0
    indexer ! Reset()
  }

  property("alternating gens and rollbacks") {
    rollbackWithPattern("G-10;R-5;G-15;R-10;G-20;R-5")
  }

  property("multiple gens before rollback") {
    rollbackWithPattern("G-5;G-10;G-15;R-10;G-20;G-25;R-15")
  }

  property("consecutive rollbacks") {
    rollbackWithPattern("G-30;R-25;R-20;R-15;R-10;R-5")
  }

  property("rollback to 1") {
    rollbackWithPattern("G-10;G-20;G-30;R-10;G-35;R-1")
  }

  property("random gens and rollbacks") {
    rollbackWithPattern("G-5;G-15;R-5;G-20;G-25;R-15;G-30;R-10;G-50;R-25")
  }

  property("tokens dont disappear when rolling back with orphan block") {
    indexer ! CreateDB(HEIGHT)
    indexer ! Index()
    lock.lock()
    done.await()
    indexer ! GenerateBetterChainTip()
    lock.lock()
    created.await()
    val newBestHeaderOpt = history.typedModifierById[Header](history.headerIdsAtHeight(history.fullBlockHeight).last)
    indexer ! RemoteBlockApplied(newBestHeaderOpt.get) // will be ignored
    indexer ! CreateDB(HEIGHT + 1)
    lock.lock()
    created.await()
    indexer ! Index()
    lock.lock()
    done.await()
    indexer ! Rollback(history.bestHeaderIdAtHeight(HEIGHT).get)
    lock.lock()
    done.await()
    val (_, _, indexedTokens, _, _) = manualIndex(HEIGHT)
    checkTokens(indexedTokens) shouldBe 0
    indexer ! Reset()
  }
}
