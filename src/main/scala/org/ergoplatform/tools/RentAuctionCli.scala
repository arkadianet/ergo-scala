package org.ergoplatform.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionContracts
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionPlan
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.settings.Args
import org.ergoplatform.settings.ChainSettings
import org.ergoplatform.settings.ErgoSettingsReader
import org.ergoplatform.settings.ErgoValidationSettingsUpdate
import org.ergoplatform.settings.NetworkType
import org.ergoplatform.settings.Parameters
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import sigma.serialization.ErgoTreeSerializer

import scala.util.Try

/** Offline JSON interface; never signs, broadcasts, or reads wallet secrets. */
object RentAuctionCli extends ApiCodecs {
  private def tree(hex: String): ErgoTree = {
    val bytes = Base16.decode(hex).get
    val result = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(bytes)
    require(result.root.isRight, "An unparsed script cannot be used as a recipient")
    require(new ErgoTree(result.header, result.constants, result.root)
      .bytes.sameElements(bytes), "Non-canonical recipient script")
    result
  }

  def manifest(chain: ChainSettings): Json = {
    val contracts = chain.rentAuctionContracts
    def entry(t: ErgoTree): Json = Json.obj(
      "ergoTree" -> Base16.encode(t.bytes).asJson,
      "blake2b256" -> Base16.encode(Blake2b256(t.bytes)).asJson,
      "bytes" -> t.bytes.length.asJson)
    Json.obj(
      "schemaVersion" -> 1.asJson,
      "networkPrefix" -> chain.addressPrefix.asJson,
      "activationHeight" -> chain.rentAuctionActivationHeight.asJson,
      "auction" -> entry(contracts.auction),
      "deposit" -> entry(contracts.deposit),
      "reserve" -> entry(contracts.reserve),
      "fee" -> entry(contracts.fee),
      "reserveNft" -> chain.reemission.reemissionNftId.asJson,
      "storagePeriod" -> org.ergoplatform.settings.Constants.StoragePeriod.asJson,
      "window" -> RentAuctionContracts.WINDOW.asJson,
      "maximumWindow" -> RentAuctionContracts.MAXIMUM_WINDOW.asJson,
      "minimumBid" -> RentAuctionContracts.MINIMUM_BID.asJson,
      "increment" -> RentAuctionContracts.INCREMENT.asJson,
      "tokensPerLot" -> RentAuctionContracts.TOKENS_PER_LOT.asJson)
  }

  def prepare(request: Json, chain: ChainSettings): Try[Json] =
    if (request.hcursor.get[String]("action").contains("inspect")) inspect(request, chain)
    else prepareTransaction(request, chain)

  private def inspect(request: Json, chain: ChainSettings): Try[Json] = Try {
    val c = request.hcursor
    val height = c.get[Int]("height").toTry.get
    val fee = c.downField("parameters").get[Int]("storageFeeFactor").toTry.get
    val boxes = c.get[Vector[ErgoBox]]("boxes").toTry.get
    val contracts = chain.rentAuctionContracts
    Json.obj("height" -> height.asJson, "boxes" -> boxes.map { b =>
      val charge = fee * b.bytes.length
      val eligible = height - b.creationHeight >=
        org.ergoplatform.settings.Constants.StoragePeriod
      def longRegister(id: ErgoBox.NonMandatoryRegisterId): Option[Long] =
        Try(b.additionalRegisters(id).value.asInstanceOf[Long]).toOption
      val deadline = Try(b.additionalRegisters(ErgoBox.R5).value
        .asInstanceOf[sigma.Coll[Int]](0)).toOption
      Json.obj("boxId" -> Base16.encode(b.id).asJson,
        "bytes" -> b.bytes.length.asJson,
        "value" -> b.value.asJson,
        "charge" -> charge.asJson,
        "eligible" -> eligible.asJson,
        "fullyConsumed" -> (eligible && b.value - charge <= 0).asJson,
        "auction" -> (b.ergoTree == contracts.auction).asJson,
        "deposit" -> (b.ergoTree == contracts.deposit).asJson,
        "deadline" -> deadline.asJson,
        "bid" -> longRegister(ErgoBox.R6).asJson,
        "box" -> b.asJson)
    }.asJson)
  }

  private def prepareTransaction(request: Json, chain: ChainSettings): Try[Json] = Try {
    val c = request.hcursor
    val height = c.get[Int]("height").toTry.get
    require(height >= 0, "Height must be non-negative")
    // Require current parameters explicitly: offline defaults must not masquerade
    // as the live chain's voted values.
    val p = c.downField("parameters")
    val storageFee = p.get[Int]("storageFeeFactor").toTry.get
    val dust = p.get[Int]("minValuePerByte").toTry.get
    require(storageFee > 0 && dust > 0, "Invalid storage/dust parameters")
    val params = new Parameters(height, Parameters.DefaultParameters ++ Map(
      Parameters.StorageFeeFactorIncrease -> storageFee,
      Parameters.MinValuePerByteIncrease -> dust,
      Parameters.BlockVersion -> chain.protocolVersion.toInt),
      ErgoValidationSettingsUpdate.empty)
    val builder = new RentAuctionTransactions(chain.rentAuctionContracts, params, height)
    def boxes(name: String): IndexedSeq[ErgoBox] =
      c.get[Option[Vector[ErgoBox]]](name).toTry.get.getOrElse(Vector.empty)
    def one(name: String): ErgoBox = c.get[ErgoBox](name).toTry.get
    def script(name: String): ErgoTree = tree(c.get[String](name).toTry.get)
    def fee: Long = c.get[Option[Long]]("fee").toTry.get.getOrElse(1000000L)
    val plan: RentAuctionPlan = c.get[String]("action").toTry.get match {
      case "collect" => builder.collect(boxes("sources"), boxes("funding"),
        script("beneficiary"), script("change"), fee,
        c.get[Option[Long]]("seed").toTry.get.getOrElse(0L)).get
      case "bid" => builder.bid(one("auction"), boxes("funding"),
        c.get[Long]("bid").toTry.get, script("recipient"), script("change"), fee).get
      case "settle" => builder.settle(one("auction"), boxes("funding"),
        script("closer"), fee).get
      case "merge" => builder.merge(one("reserve"), boxes("deposits"),
        c.get[Option[ErgoBox]]("sponsor").toTry.get).get
      case other => throw new IllegalArgumentException(s"Unknown action: $other")
    }
    val signing = Json.obj(
      "tx" -> plan.unsigned.asJson,
      "inputsRaw" -> plan.boxes.map(b => Base16.encode(b.bytes)).asJson,
      "dataInputsRaw" -> Json.arr())
    Json.obj(
      "schemaVersion" -> 1.asJson,
      "height" -> height.asJson,
      "transactionId" -> plan.transaction.id.asJson,
      "unsignedTransaction" -> plan.unsigned.asJson,
      "signingRequest" -> signing,
      "emptyProofTransaction" -> plan.transaction.asJson,
      "inputBoxes" -> plan.boxes.asJson,
      "outputBoxes" -> plan.transaction.outputs.asJson,
      "additionalValidationCost" -> new RentAuctionRules(
        chain.rentAuctionContracts, params)
        .cost(plan.transaction, plan.boxes).asJson)
  }

  def main(args: Array[String]): Unit = {
    val result = Try {
      require(args.length >= 3,
        "Usage: RentAuctionCli <manifest|prepare> <mainnet|testnet|config:path> " +
          "<output.json> [request.json]")
      val chain = if (args(1).startsWith("config:")) {
        val path = args(1).stripPrefix("config:")
        require(Files.isRegularFile(Paths.get(path)), "Configuration file not found")
        ErgoSettingsReader.read(Args(Some(path), None)).chainSettings
      } else {
        val network = NetworkType.fromString(args(1)).getOrElse(
          throw new IllegalArgumentException("Unknown network"))
        val config = ConfigFactory.defaultOverrides()
          .withFallback(ConfigFactory.parseResources(s"${network.verboseName}.conf"))
          .withFallback(ConfigFactory.defaultApplication())
          .withFallback(ConfigFactory.defaultReference()).resolve()
        ErgoSettingsReader.fromConfig(config, Some(network)).chainSettings
      }
      val json = args(0) match {
        case "manifest" => manifest(chain)
        case "prepare" =>
          require(args.length == 4, "prepare requires a request JSON file")
          val bytes = Files.readAllBytes(Paths.get(args(3)))
          require(bytes.length <= 4194304, "Request exceeds 4 MiB")
          prepare(parse(new String(bytes, StandardCharsets.UTF_8)).toTry.get, chain).get
        case other => throw new IllegalArgumentException(s"Unknown command: $other")
      }
      Files.write(Paths.get(args(2)), json.spaces2.getBytes(StandardCharsets.UTF_8))
    }
    result.failed.foreach { error =>
      System.err.println(s"rent-auction: ${error.getMessage}")
      sys.exit(1)
    }
  }
}
