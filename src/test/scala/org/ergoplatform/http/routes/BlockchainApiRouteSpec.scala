package org.ergoplatform.http.routes

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.http.api.BlockchainApiRoute
import org.ergoplatform.nodeView.ErgoReadersHolder.{GetDataFromHistory, GetReaders, Readers}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.ReceivableMessages.{GetSegmentThreshold, Index}
import org.ergoplatform.nodeView.history.extra.{ExtraIndexerTestActor, ExtraIndexerTestHarness, RentIndexTestSupport}
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.rentKey
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.Stubs
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.wallet.protocol.Constants
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.db.ByteArrayWrapper
import scorex.util.bytesToId

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
  * First route-level spec for the `/blockchain` API surface. Every existing test
  * settings object hard-codes `extraIndex = false` (Stubs.scala, HistoryTestHelpers.scala),
  * which makes `BlockchainApiRoute.route` short-circuit to a "not enabled" stub -- so
  * until now there was zero route-level coverage of this surface. This spec builds a
  * harness that drives a REAL extra-indexed history (via `ExtraIndexerTestHarness` /
  * `ExtraIndexerTestActor`, the same actor `ExtraIndexerSpecification` uses) and proves
  * it against an EXISTING route (`/blockchain/indexedHeight`) before any new
  * storage-rent routes are added on top of it. A failure here is unambiguously a
  * harness problem, not a new-route problem.
  */
class BlockchainApiRouteSpec
  extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with FailFastCirceSupport
  with Stubs
  with ExtraIndexerTestHarness {

  import org.ergoplatform.utils.ErgoNodeTestConstants._

  implicit val addressEncoder: ErgoAddressEncoder = settings.addressEncoder

  // Required by ExtraIndexerTestHarness. Only chainSettings/scorexSettings/walletSettings/
  // cacheSettings are read out of this by ExtraIndexerTestActor.createDB -- it builds its
  // own NodeConfigurationSettings internally (with extraIndex = false), so this value's
  // own extraIndex flag is irrelevant to chain construction.
  val initSettings: ErgoSettings = settings

  val HEIGHT = 50

  // Build a real extra-indexed chain through the same actor ExtraIndexerSpecification
  // uses, mirroring its CreateDB/Index handshake exactly.
  private val builderIndexer: ActorRef =
    system.actorOf(Props.create(classOf[ExtraIndexerTestActor], this))

  builderIndexer ! CreateDB(HEIGHT)
  builderIndexer ! Index()
  lock.lock()
  if (!done.await(120, TimeUnit.SECONDS)) fail("indexer never signalled done -- actor likely crashed; check supervision log")

  // Backfill-gate assertion (brief gotcha #6): the rent routes added in the next task
  // return 503 while a backfill cursor is present. `RentBackfillKey` is written ONLY by
  // the production `StartExtraIndexer` handler / `backfillRentChunk` on the real
  // `ExtraIndexer` actor -- `ExtraIndexerTestActor` never sends or handles that message,
  // so the key would be absent here regardless. Absence happens to also read as `None`,
  // but relying on that would be incidental: absence is exactly what trips the
  // backfill-START branch, so if chain construction is ever routed through the real
  // `StartExtraIndexer` path, an absent key would flip the cursor to `Some(0)` and every
  // rent route added in the next two tasks would silently start 503'ing. Seed the
  // "backfill already complete" sentinel explicitly instead, so this harness is correct
  // under both the current and any future construction path.
  seedBackfillCursor(-1L)
  ExtraIndexer.rentBackfillCursor(_history.getReader) shouldBe None

  // extraIndex lives on NodeConfigurationSettings, not ErgoSettings directly.
  private val routeSettings: ErgoSettings =
    settings.copy(nodeSettings = settings.nodeSettings.copy(extraIndex = true))

  // Readers stub, following Stubs.scala:339-348's pattern: answer GetReaders with a
  // fixed Readers snapshot, and GetDataFromHistory(f) by applying f to the real,
  // extra-indexed history built above (not Stubs' own unrelated 6-block `history`).
  private val readers: Readers = Readers(_history.getReader, digestState, memPool, wallet)

  private class ReadersHolderStub extends Actor {
    def receive: Receive = {
      case GetReaders             => sender() ! readers
      case GetDataFromHistory(f)  => sender() ! f(_history.getReader)
    }
  }

  private val readersHolderRef: ActorRef = system.actorOf(Props(new ReadersHolderStub))

  // indexerOpt only needs to answer GetSegmentThreshold (ExtraIndexer.scala:615-616).
  private val stubSegmentThreshold = 8

  private class IndexerStub extends Actor {
    def receive: Receive = {
      case GetSegmentThreshold => sender() ! stubSegmentThreshold
    }
  }

  private val indexerStubRef: ActorRef = system.actorOf(Props(new IndexerStub))

  val route: Route = BlockchainApiRoute(readersHolderRef, routeSettings, Some(indexerStubRef)).route

  "the blockchain route" should "serve indexedHeight when extraIndex is enabled" in {
    Get("/blockchain/indexedHeight") ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("indexedHeight").as[Int].toOption.get should be > 0
    }
  }

  private val P = Constants.StoragePeriod

  private def itemsOf(j: Json): Vector[Json] =
    j.hcursor.downField("items").as[Vector[Json]].toOption.get

  private def creationHeightsOf(j: Json): Seq[Int] =
    itemsOf(j).map(_.hcursor.downField("creationHeight").as[Int].toOption.get)

  /** creationHeight from a 13-byte rent key: bytes 1-4, big-endian. */
  private def decodeCreationHeight(k: ByteArrayWrapper): Int =
    ByteBuffer.wrap(k.data, 1, 4).getInt

  private def expectedCountAtCutoff(c: Int): Int =
    manualRentSet(HEIGHT).keys.count(decodeCreationHeight(_) <= c)

  /** Write directly through historyStorage (this test class may not be in the
    * history package -- go through the RentIndexTestSupport bridge; insertExtra
    * raw pairs need no ExtraIndex objects).
    */
  private def plantHashKeyInRentRange(): Unit = {
    val hash32 = new Array[Byte](32)                 // 32-byte decoy, bytes 1-4 = 0
    hash32(0) = ExtraIndexer.RentKeyPrefix           // sorts inside every rent range
    RentIndexTestSupport.insertExtraRaw(_history, Array(hash32 -> Array[Byte](1)))
  }

  private def plantOrphanRentRow(creationHeight: Int, globalIndex: Long): Unit =
    RentIndexTestSupport.insertExtraRaw(_history, Array(
      rentKey(creationHeight, globalIndex) ->
        RentIndexTestSupport.fastIdToBytes(bytesToId(Array.fill[Byte](32)(0x5a)))))

  private def setBackfillCursor(c: Long): Unit =
    RentIndexTestSupport.insertExtraRaw(_history, Array(
      ExtraIndexer.RentBackfillKey -> ByteBuffer.allocate(8).putLong(c).array))

  private def setBackfillComplete(): Unit = setBackfillCursor(-1L)

  it should "return empty items, HTTP 200, below StoragePeriod" in {
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P - 1}") ~> route ~> check {
      status shouldBe StatusCodes.OK
      itemsOf(responseAs[Json]) shouldBe empty
      responseAs[Json].hcursor.downField("total").succeeded shouldBe false
      responseAs[Json].hcursor.downField("indexedHeight").succeeded shouldBe true
      responseAs[Json].hcursor.downField("fullHeight").succeeded shouldBe true
    }
  }

  it should "return empty items, HTTP 200, when atHeight is omitted on a short chain" in {
    // Default atHeight = indexedHeight + 1 gives a NEGATIVE cutoff on any chain
    // below StoragePeriod. Must not throw from rentKey's require.
    Get("/blockchain/box/unspent/rentEligible") ~> route ~> check {
      status shouldBe StatusCodes.OK
      itemsOf(responseAs[Json]) shouldBe empty
    }
  }

  it should "return exactly the oracle's boxes at or below the cutoff" in {
    val c = 3
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + c}&limit=16384&sortDirection=asc") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = manualRentSet(HEIGHT).keys.map(decodeCreationHeight).filter(_ <= c).toSeq.sorted
      expected should not be empty      // the test is meaningless if the chain has none
      creationHeightsOf(responseAs[Json]).sorted shouldBe expected
    }
  }

  it should "exclude a planted 32-byte hash key that sorts inside the range" in {
    // §2.3 filter discipline, at the route layer
    plantHashKeyInRentRange()
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}&limit=100") ~> route ~> check {
      status shouldBe StatusCodes.OK
      // decoding the decoy would produce a garbage boxId and a missing-box skip;
      // assert the item count matches the oracle exactly
      itemsOf(responseAs[Json]).size shouldBe expectedCountAtCutoff(10)
    }
  }

  it should "fill pages to limit even when an orphaned row is present" in {
    // The "short page means exhausted" contract depends on limit counting
    // EMITTED ITEMS, not scanned keys.
    plantOrphanRentRow(creationHeight = 2, globalIndex = 999999L)
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}&limit=3&sortDirection=asc") ~> route ~> check {
      status shouldBe StatusCodes.OK      // not 500
      itemsOf(responseAs[Json]).size shouldBe 3
    }
  }

  it should "page ascending and descending consistently" in {
    val base = s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}"
    val asc = Get(s"$base&limit=100&sortDirection=asc") ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json])
    }
    asc should not be empty
    asc shouldBe asc.sorted            // ascending really is ascending

    val desc = Get(s"$base&limit=100&sortDirection=desc") ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json])
    }
    desc shouldBe asc.reverse

    // A page shorter than limit means exhausted; a full page means more may follow.
    val page = Get(s"$base&offset=0&limit=2&sortDirection=asc") ~> route ~> check {
      creationHeightsOf(responseAs[Json])
    }
    page shouldBe asc.take(2)
    val next = Get(s"$base&offset=2&limit=2&sortDirection=asc") ~> route ~> check {
      creationHeightsOf(responseAs[Json])
    }
    next shouldBe asc.slice(2, 4)      // offset advances by limit, no overlap
  }

  it should "emit serializedBoxSize and storageFee per item" in {
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}&limit=1&sortDirection=asc") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val items = itemsOf(responseAs[Json])
      items should not be empty
      val c = responseAs[Json].hcursor.downField("items").downArray
      val size = c.downField("serializedBoxSize").as[Int].toOption.get
      val fee = c.downField("storageFee").as[Long].toOption.get
      val factor = responseAs[Json].hcursor.downField("storageFeeFactor").as[Int].toOption.get
      size should be > 0
      // must equal the consensus function exactly, wrap included
      fee shouldBe ErgoInterpreter.storageFee(factor, size).toLong
    }
  }

  it should "reject limit above MaxItems" in {
    Get("/blockchain/box/unspent/rentEligible?limit=16385") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      // ApiError.BadRequest's JSON envelope (matching every sibling route in
      // this file), not a bare string body -- decode and check "detail".
      responseAs[Json].hcursor.downField("detail").as[String].toOption.get should include("16384")
    }
  }

  it should "reject a negative offset" in {
    Get("/blockchain/box/unspent/rentEligible?offset=-1") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "reject an offset above MaxItems" in {
    // C1: an unbounded offset turns scanRentRange's `target = offset + limit`
    // into an unauthenticated materialization count -- offset=5000000&limit=1
    // would buffer ~5M rows before discarding all but one. Must 400 before
    // any scan happens.
    Get("/blockchain/box/unspent/rentEligible?offset=5000000&limit=1") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[Json].hcursor.downField("detail").as[String].toOption.get should include("16384")
    }
  }

  it should "reject an invalid sortDirection with the exact existing message" in {
    Get("/blockchain/box/unspent/rentEligible?sortDirection=sideways") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[Json].hcursor.downField("detail").as[String].toOption.get should include(
        """Invalid parameter for sort direction, valid values are "ASC" and "DESC"""")
    }
  }

  it should "report projected factor source on a chain with no voted extensions" in {
    // The generated test chain carries no voted parameter extensions, so the
    // §8.5 chain must land on the projected branch rather than throwing.
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}") ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("storageFeeFactorSource").as[String]
        .toOption.get shouldBe "projected"
      responseAs[Json].hcursor.downField("storageFeeFactor").as[Int].toOption.get should be > 0
    }
  }

  it should "return 503 while a backfill cursor is present" in {
    setBackfillCursor(42L)
    try {
      Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}") ~> route ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should include("backfill")
      }
    } finally setBackfillComplete()   // restore even if the assertion fails
    // and confirm the gate actually lifted, so later tests are not silently 503
    Get(s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}") ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "default to ascending order" in {
    // §5 rev. 3 flipped the rent routes to asc, against the shared sortDir
    // directive's DESC default -- so this pins a route-local override that is
    // easy to lose by reusing the shared directive unchanged.
    val base = s"/blockchain/box/unspent/rentEligible?atHeight=${P + 10}&limit=5"
    val implicitDir = Get(base) ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json])
    }
    implicitDir should not be empty
    val explicitAsc = Get(s"$base&sortDirection=asc") ~> route ~> check {
      creationHeightsOf(responseAs[Json])
    }
    implicitDir shouldBe explicitAsc
  }

  // NOTE: the DescendingScanBudget 400 is NOT route-testable at test-chain scale
  // (50 blocks << the 1M-key budget). The mechanism is covered by
  // KVStoreRangeSpec's budget cases; that rentRange passes the budget through is
  // verified by inspection. Do not fabricate a route test for it.

  /** Find a box that was created then later spent within the test chain,
    * together with the (creationHeight, globalIndex) it was originally indexed
    * under. Production correctly removes the rent row when a box is spent
    * (ExtraIndexer.scala), so this scenario -- a rent row whose target box
    * exists but is already spent -- can't arise naturally in the test chain
    * and must be planted directly, by re-inserting the row production would
    * have deleted.
    */
  private def findSpentBoxRentEntry(limit: Int): (Int, Long, scorex.util.ModifierId) = {
    val seen = scala.collection.mutable.HashMap.empty[scorex.util.ModifierId, (Int, Long)]
    var globalBoxIndex = 0L
    var found: Option[(Int, Long, scorex.util.ModifierId)] = None
    for (i <- 1 to limit if found.isEmpty) {
      val header = _history.headerIdsAtHeight(i).last
      val block = _history.getFullBlock(_history.typedModifierById[org.ergoplatform.modifiers.history.header.Header](header).get)
      block.get.transactions.foreach { tx =>
        if (i > 1) tx.inputs.foreach { in =>
          if (found.isEmpty) {
            seen.get(bytesToId(in.boxId)).foreach { case (h, g) =>
              found = Some((h, g, bytesToId(in.boxId)))
            }
          }
        }
        tx.outputs.foreach { out =>
          val id = bytesToId(out.id)
          seen.put(id, (out.creationHeight, globalBoxIndex))
          globalBoxIndex += 1
        }
      }
    }
    found.getOrElse(throw new IllegalStateException("test chain has no spent box to plant a stale rent row for"))
  }

  private def plantSpentBoxRentRow(): Int = {
    val (h, g, boxId) = findSpentBoxRentEntry(HEIGHT)
    RentIndexTestSupport.insertExtraRaw(_history, Array(
      rentKey(h, g) -> RentIndexTestSupport.fastIdToBytes(boxId)))
    h
  }

  it should "return exactly the oracle's boxes maturing in range" in {
    val from = P + 2; val to = P + 4
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=$from&toHeight=$to&limit=16384") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = manualRentSet(HEIGHT).keys.map(decodeCreationHeight)
        .filter(h => h >= from - P && h <= to - P).toSeq.sorted
      expected should not be empty
      creationHeightsOf(responseAs[Json]).sorted shouldBe expected
    }
  }

  it should "return empty items, HTTP 200, when toHeight is below StoragePeriod" in {
    // toHeight - StoragePeriod is NEGATIVE; must not reach rentKey's require
    Get("/blockchain/box/unspent/rentMaturingInRange?fromHeight=1&toHeight=2") ~> route ~> check {
      status shouldBe StatusCodes.OK
      itemsOf(responseAs[Json]) shouldBe empty
    }
  }

  it should "accept fromHeight == toHeight as a single-height slice" in {
    // This is what subsumes the Rust node's separate maturesAt route; if it
    // 400s, the range validation used > instead of >=.
    val h = P + 3
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=$h&toHeight=$h&limit=16384") ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json]).foreach(_ shouldBe (h - P))
    }
  }

  it should "reject fromHeight greater than toHeight" in {
    Get("/blockchain/box/unspent/rentMaturingInRange?fromHeight=100&toHeight=99") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "reject a negative fromHeight" in {
    Get("/blockchain/box/unspent/rentMaturingInRange?fromHeight=-1&toHeight=100") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "reject an offset above MaxItems for rentMaturingInRange" in {
    // C1, same unauthenticated-OOM guard as rentEligible.
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=${P + 2}&toHeight=${P + 4}&offset=5000000&limit=1") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[Json].hcursor.downField("detail").as[String].toOption.get should include("16384")
    }
  }

  it should "return 503 while a backfill cursor is present for rentMaturingInRange" in {
    // MUST be tested on this route too, not just rentEligible -- a gate wired
    // into one handler and forgotten in the other is exactly the B5 regression.
    setBackfillCursor(42L)
    try {
      Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=${P + 2}&toHeight=${P + 4}") ~> route ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should include("backfill")
      }
    } finally setBackfillComplete()
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=${P + 2}&toHeight=${P + 4}") ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "echo fromHeight, toHeight, indexedHeight and fullHeight" in {
    val from = P + 2
    val to = P + 4
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=$from&toHeight=$to") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val c = responseAs[Json].hcursor
      c.downField("fromHeight").as[Int].toOption.get shouldBe from
      c.downField("toHeight").as[Int].toOption.get shouldBe to
      c.downField("indexedHeight").as[Int].toOption.get should be > 0
      c.downField("fullHeight").as[Int].toOption.get should be > 0
      c.downField("atHeight").succeeded shouldBe false   // range route has no atHeight
      c.downField("total").succeeded shouldBe false      // no total, ever
    }
  }

  it should "reject limit above MaxItems for rentMaturingInRange" in {
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=${P + 2}&toHeight=${P + 4}&limit=16385") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[Json].hcursor.downField("detail").as[String].toOption.get should include("16384")
    }
  }

  it should "default to ascending order for rentMaturingInRange" in {
    val base = s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=${P + 2}&toHeight=${P + 10}&limit=100"
    val implicitDir = Get(base) ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json])
    }
    implicitDir should not be empty
    val explicitAsc = Get(s"$base&sortDirection=asc") ~> route ~> check {
      creationHeightsOf(responseAs[Json])
    }
    implicitDir shouldBe explicitAsc
  }

  it should "exclude a rent row whose box is already spent" in {
    val spentHeight = plantSpentBoxRentRow()
    val maturesAt = spentHeight + P
    Get(s"/blockchain/box/unspent/rentMaturingInRange?fromHeight=$maturesAt&toHeight=$maturesAt&limit=16384") ~> route ~> check {
      status shouldBe StatusCodes.OK
      creationHeightsOf(responseAs[Json]) should not contain spentHeight
    }
  }
}
