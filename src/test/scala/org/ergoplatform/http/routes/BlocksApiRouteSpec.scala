package org.ergoplatform.http.routes

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes, UniversalEntity}
import akka.http.scaladsl.server.Directives.handleRejections
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.http.api.BlocksApiRoute
import org.ergoplatform.mining.AutolykosPowScheme
import org.ergoplatform.modifiers.{BlockSection, ErgoFullBlock}
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.ErgoReadersHolder.GetDataFromHistory
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.history.modifierprocessors.EmptyBlockSectionProcessor
import org.ergoplatform.nodeView.history.storage.HistoryStorage
import org.ergoplatform.settings.{Algos, ErgoSettings}
import org.ergoplatform.utils.{ErgoNodeTestConstants, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.core.api.http.ApiRejectionHandler
import scorex.util.ModifierId

class BlocksApiRouteSpec
  extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with FailFastCirceSupport
  with Stubs {

  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  val prefix = "/blocks"

  val route: Route = BlocksApiRoute(nodeViewRef, digestReadersRef, settings).route

  val headerIdBytes: ModifierId = history.lastHeaders(1).headers.head.id
  val headerIdString: String    = Algos.encode(headerIdBytes)

  it should "get last blocks" in {
    Get(prefix) ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .headerIdsAt(0, 50)
        .map(Algos.encode)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "post block correctly" in {
    val (st, bh)             = createUtxoState(settings)
    val block: ErgoFullBlock = validFullBlock(parentOpt = None, st, bh)
    val blockJson: UniversalEntity =
      HttpEntity(block.asJson.toString).withContentType(ContentTypes.`application/json`)
    Post(prefix, blockJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "get last headers" in {
    Get(prefix + "/lastHeaders/1") ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .lastHeaders(1)
        .headers
        .map(_.asJson)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "get block at height" in {
    Get(prefix + "/at/0") ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .headerIdsAtHeight(0)
        .map(Algos.encode)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "get chain slice" in {
    Get(prefix + "/chainSlice?fromHeight=0") ~> route ~> check {
      status shouldBe StatusCodes.OK
      chain.map(_.header).asJson shouldEqual responseAs[Json]
    }
    Get(prefix + "/chainSlice?fromHeight=2&toHeight=4") ~> route ~> check {
      status shouldBe StatusCodes.OK
      chain.slice(2, 4).map(_.header).asJson shouldEqual responseAs[Json]
    }
  }

  it should "reject chain slice ranges above the maximum headers limit" in {
    Get(prefix + "/chainSlice?fromHeight=0&toHeight=16385") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "get block by header id" in {
    Get(prefix + "/" + headerIdString) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.asJson)
        .get

      responseAs[Json] shouldEqual expected
    }
  }

  it should "get block by header id with a trailing slash" in {
    Get(s"$prefix/$headerIdString/") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.asJson)
        .get

      responseAs[Json] shouldEqual expected
    }
  }

  // Use a distinct input-block id and non-empty bodies to distinguish these responses
  // from a full block (or a missing full block) selected by an earlier route.
  private val inputBlockId: ModifierId = ModifierId @@ ("ab" * 32)
  private val inputTransactions: Seq[ErgoTransaction] = chain.last.transactions
  private val inputHistoryStorage = HistoryStorage(
    settings.copy(directory = createTempDir.getAbsolutePath)
  )
  private val inputHistory = new ErgoHistory with EmptyBlockSectionProcessor {
    override protected val settings: ErgoSettings = ErgoNodeTestConstants.settings
    override val historyStorage: HistoryStorage = inputHistoryStorage
    override val powScheme: AutolykosPowScheme = settings.chainSettings.powScheme

    override def modifierById(id: ModifierId): Option[BlockSection] =
      history.modifierById(id)

    override def getFullBlock(header: Header): Option[ErgoFullBlock] =
      history.getFullBlock(header)

    override def getInputBlockTransactionIds(id: ModifierId): Option[Seq[ModifierId]] =
      if (id == inputBlockId) Some(inputTransactions.map(_.id)) else None

    override def getInputBlockTransactions(id: ModifierId): Option[Seq[ErgoTransaction]] =
      if (id == inputBlockId) Some(inputTransactions) else None
  }
  private val inputReadersRef = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case GetDataFromHistory(f) => sender() ! f(inputHistory)
    }
  }))
  private val inputRoute = BlocksApiRoute(nodeViewRef, inputReadersRef, settings).route

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally inputHistoryStorage.close()
  }

  it should "get input-block transaction ids through the composed route" in {
    inputTransactions should not be empty
    Get(s"$prefix/$inputBlockId/inputBlockTransactionIds") ~> inputRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json] shouldEqual inputTransactions.map(tx => Algos.encode(tx.id)).asJson
    }
  }

  it should "get input-block transaction bodies through the composed route" in {
    inputTransactions should not be empty
    Get(s"$prefix/$inputBlockId/inputBlockTransactions") ~> inputRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json] shouldEqual inputTransactions.asJson
    }
  }

  it should "reject unknown suffixes after a header id through the composed route" in {
    val nodeRoute = handleRejections(ApiRejectionHandler.rejectionHandler)(route)
    Get(s"$prefix/$headerIdString/thisDoesNotExist") ~> nodeRoute ~> check {
      val fullBlock = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.asJson)
        .get

      status should not be StatusCodes.OK
      responseAs[Json] should not equal fullBlock
      // ApiRejectionHandler lacks a MethodRejection case for the existing POST
      // routes. This 500 is a pre-existing handler defect, not the intended contract.
      status shouldBe StatusCodes.InternalServerError
    }
  }

  it should "get blocks by header ids" in {
    val headerIdsBytes               = history.lastHeaders(10).headers
    val headerIdsString: Seq[String] = headerIdsBytes.map(h => Algos.encode(h.id))

    Post(prefix + "/headerIds", headerIdsString.asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK

      val expected = headerIdsBytes
        .map(_.id)
        .flatMap(headerId =>
          history.typedModifierById[Header](headerId).flatMap(history.getFullBlock)
        )

      responseAs[Seq[ErgoFullBlock]] shouldEqual expected
    }
  }

  it should "get header by header id" in {
    Get(prefix + "/" + headerIdString + "/header") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.header.asJson)
        .get

      responseAs[Json] shouldEqual expected
    }
  }

  it should "get transactions by header id" in {
    Get(prefix + "/" + headerIdString + "/transactions") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val header    = history.typedModifierById[Header](headerIdBytes).value
      val fullBlock = history.getFullBlock(header).value
      val expected  = fullBlock.blockTransactions.asJson
      responseAs[Json] shouldEqual expected
    }
  }

}
