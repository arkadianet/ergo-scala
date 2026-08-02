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
import org.ergoplatform.nodeView.history.extra.{ExtraIndexerTestActor, ExtraIndexerTestHarness}
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.Stubs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
  done.await()

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
}
