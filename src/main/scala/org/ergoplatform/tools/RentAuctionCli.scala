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
import org.ergoplatform.settings.ErgoValidationSettings
import org.ergoplatform.settings.ErgoValidationSettingsUpdate
import org.ergoplatform.settings.NetworkType
import org.ergoplatform.settings.Parameters
import org.ergoplatform.settings.ValidationRules
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
      "schemaVersion" -> 2.asJson,
      "networkPrefix" -> chain.addressPrefix.asJson,
      "activationHeight" -> chain.rentAuctionActivationHeight.asJson,
      "votingLength" -> chain.voting.votingLength.asJson,
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
      "tokensPerLot" -> RentAuctionContracts.TOKENS_PER_LOT.asJson,
      "closeAllowance" -> RentAuctionContracts.CLOSE_ALLOWANCE.asJson,
      "maxCloseFee" -> RentAuctionContracts.MAX_CLOSE_FEE.asJson,
      "collectorShareDenominator" -> RentAuctionContracts.COLLECTOR_SHARE_DENOMINATOR.asJson,
      "mergeBudget" -> RentAuctionContracts.MERGE_BUDGET.asJson,
      "minimumDepositValue" -> RentAuctionContracts.MIN_DEPOSIT_VALUE.asJson,
      "maxDepositBytes" -> RentAuctionContracts.MAX_DEPOSIT_BYTES.asJson,
      "maxPartyBytes" -> RentAuctionContracts.MAX_PARTY_BYTES.asJson,
      "maxBytePrice" -> RentAuctionContracts.MAX_BYTE_PRICE.asJson,
      "payoutOverheadBytes" -> RentAuctionContracts.PAYOUT_OVERHEAD_BYTES.asJson,
      "tokenEntryBytes" -> RentAuctionContracts.TOKEN_ENTRY_BYTES.asJson,
      "maxCloseLots" -> RentAuctionContracts.MAX_CLOSE_LOTS.asJson,
      "maxMergeDeposits" -> RentAuctionContracts.MAX_MERGE_DEPOSITS.asJson,
      "maxMergeFee" -> RentAuctionContracts.MAX_MERGE_FEE.asJson)
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
    Json.obj("schemaVersion" -> 2.asJson, "height" -> height.asJson, "boxes" -> boxes.map { b =>
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
        "seedPrincipal" -> longRegister(ErgoBox.R8).asJson,
        "collector" -> Try(Base16.encode(b.additionalRegisters(ErgoBox.R9)
          .value.asInstanceOf[sigma.Coll[Byte]].toArray)).toOption.asJson,
        "collectionCommitment" -> Try(Base16.encode(b.additionalRegisters(ErgoBox.R4)
          .value.asInstanceOf[sigma.Coll[Byte]].toArray)).toOption.asJson,
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
    require(p.downField("disabledRules").succeeded,
      "parameters.disabledRules is required (use [] when no validation rule is disabled)")
    val ids = p.get[Vector[Json]]("disabledRules").toTry.get.map { value =>
      val id = value.asNumber.flatMap(_.toInt)
      require(id.exists(_.isValidShort), s"Invalid disabled rule id: $value")
      val ruleId = id.get.toShort
      require(ValidationRules.rulesSpec.get(ruleId).exists(_.mayBeDisabled),
        s"Unknown or non-disableable rule id: $ruleId")
      ruleId
    }
    ids.groupBy(identity).foreach { case (id, occurrences) =>
      require(occurrences.size == 1, s"Duplicate disabled rule id: $id")
    }
    val validation = ErgoValidationSettings.initial.updated(
      ErgoValidationSettingsUpdate(ids, Seq()))
    val storageFee = p.get[Int]("storageFeeFactor").toTry.get
    val dust = p.get[Int]("minValuePerByte").toTry.get
    require(storageFee >= 0 && dust >= 0 && dust <= RentAuctionContracts.MAX_BYTE_PRICE, "Invalid storage/dust parameters")
    val params = new Parameters(height, Parameters.DefaultParameters ++ Map(
      Parameters.StorageFeeFactorIncrease -> storageFee,
      Parameters.MinValuePerByteIncrease -> dust,
      Parameters.BlockVersion -> chain.protocolVersion.toInt),
      ErgoValidationSettingsUpdate.empty)
    val builder = new RentAuctionTransactions(chain.rentAuctionContracts, params, height, validation)
    def boxes(name: String): IndexedSeq[ErgoBox] =
      c.get[Option[Vector[ErgoBox]]](name).toTry.get.getOrElse(Vector.empty)
    def one(name: String): ErgoBox = c.get[ErgoBox](name).toTry.get
    def script(name: String): ErgoTree = tree(c.get[String](name).toTry.get)
    def fee: Long = c.get[Option[Long]]("fee").toTry.get.getOrElse(1000000L)
    val plan: RentAuctionPlan = c.get[String]("action").toTry.get match {
      case "collect" => builder.collect(boxes("sources"), boxes("funding"),
        script("beneficiary"), script("collector"), script("change"), fee,
        partition = c.get[Option[Vector[Vector[Json]]]]("lots").toTry.get.toSeq.flatten.map { lot =>
          lot.map { entry =>
            val id = Base16.decode(entry.hcursor.get[String]("tokenId").toTry.get).get
            require(id.length == 32, "Token id must contain 32 bytes")
            (sigma.data.Digest32Coll @@ sigma.Colls.fromArray(id)) ->
              entry.hcursor.get[Long]("amount").toTry.get
          }
        },
        seed = c.get[Option[Long]]("seed").toTry.get.getOrElse(0L)).get
      case "bid" => builder.bid(one("auction"), boxes("funding"),
        c.get[Long]("bid").toTry.get, script("recipient"), script("change"), fee).get
      case "close" => builder.close(boxes("auctions"), fee).get
      case "merge" => builder.merge(one("reserve"), boxes("deposits"),
        c.get[Option[ErgoBox]]("sponsor").toTry.get).get
      case other => throw new IllegalArgumentException(s"Unknown action: $other")
    }
    val rules = new RentAuctionRules(chain.rentAuctionContracts, params, validation)
    val accounting = (plan.boxes ++ plan.transaction.outputs)
      .filter(b => b.ergoTree == chain.rentAuctionContracts.auction && rules.auctionShape(b)).map { b =>
        val bid = b.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long]
        val seed = b.additionalRegisters(ErgoBox.R8).value.asInstanceOf[Long]
        val recipient = b.additionalRegisters(ErgoBox.R7).value.asInstanceOf[sigma.Coll[Byte]]
        val carrier = if (bid == 0L) 0L else rules.carrier(recipient.length, b.additionalTokens.length)
        val share = bid / RentAuctionContracts.COLLECTOR_SHARE_DENOMINATOR
        Json.obj("boxId" -> Base16.encode(b.id).asJson, "seedPrincipal" -> seed.asJson,
          "closeAllowance" -> RentAuctionContracts.CLOSE_ALLOWANCE.asJson,
          "bid" -> bid.asJson, "collectorShare" -> share.asJson, "carrier" -> carrier.asJson,
          "reservePrincipal" -> (if (bid == 0L) 0L else
            bid - share - carrier - RentAuctionContracts.MERGE_BUDGET).asJson)
      }
    val signing = Json.obj(
      "tx" -> plan.unsigned.asJson,
      "inputsRaw" -> plan.boxes.map(b => Base16.encode(b.bytes)).asJson,
      "dataInputsRaw" -> Json.arr())
    Json.obj(
      "schemaVersion" -> 2.asJson,
      "height" -> height.asJson,
      "auctionAccounting" -> accounting.asJson,
      "feePaid" -> plan.transaction.outputs.last.value.asJson,
      "transactionId" -> plan.transaction.id.asJson,
      "unsignedTransaction" -> plan.unsigned.asJson,
      "signingRequest" -> signing,
      "emptyProofTransaction" -> plan.transaction.asJson,
      "inputBoxes" -> plan.boxes.asJson,
      "outputBoxes" -> plan.transaction.outputs.asJson,
      "additionalValidationCost" -> new RentAuctionRules(
        chain.rentAuctionContracts, params, validation)
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
