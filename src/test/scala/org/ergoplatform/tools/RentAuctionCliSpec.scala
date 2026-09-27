package org.ergoplatform.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionPlan
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state.ErgoState
import org.ergoplatform.utils.ErgoNodeTestConstants
import scorex.crypto.hash.Blake2b256
import scala.util.Try
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.encode.Base16

class RentAuctionCliSpec extends ErgoCorePropertyTest
  with RentAuctionFixture with ApiCodecs {

  property("schema two collection vectors bundle fixed sources into two lots with collector returns") {
    // Explicit transaction ids/indices avoid dependence on the fixture's mutable box sequence.
    val assets = tokens(4)
    val sources = assets.zipWithIndex.map { case (asset, index) =>
      output(1000000L, owner, h = 1048801, tokens = Seq(asset))
        .toBox(ErgoBox.allZerosModifierId, index.toShort)
    }.toIndexedSeq
    val funding = output(30000000L, anyone, h = 2100001)
      .toBox(ErgoBox.allZerosModifierId, 4.toShort)
    val lots = assets.grouped(2).map { entries =>
      entries.map { case (id, amount) => Json.obj(
        "tokenId" -> Base16.encode(id.toArray).asJson, "amount" -> amount.asJson)
      }.asJson
    }.toVector
    val request = Json.obj(
      "schemaVersion" -> 2.asJson,
      "action" -> "collect".asJson,
      "height" -> 2100001.asJson,
      "parameters" -> Json.obj("storageFeeFactor" -> 1250000.asJson, "minValuePerByte" -> 360.asJson),
      "sources" -> sources.asJson,
      "funding" -> Vector(funding).asJson,
      "beneficiary" -> Base16.encode(owner.bytes).asJson,
      "collector" -> Base16.encode(owner.bytes).asJson,
      "change" -> Base16.encode(anyone.bytes).asJson,
      "fee" -> 1000000L.asJson,
      "lots" -> lots.asJson)
    val json = RentAuctionCli.prepare(request, chain).get
    val plan = checkedJson(json)
    val auctions = plan.transaction.outputs.filter(_.ergoTree == contracts.auction)
    auctions.map(_.additionalTokens.length) shouldBe IndexedSeq(2, 2)
    auctions.foreach { lot =>
      lot.additionalRegisters(ErgoBox.R9).value.asInstanceOf[sigma.Coll[Byte]].toArray shouldBe owner.bytes
    }
    val rules = new RentAuctionRules(contracts, params)
    val claims = rules.claims(plan.transaction, plan.boxes, plan.height)
    claims.size shouldBe 4
    claims.foreach { case (_, rent) => rent.fullyConsumed shouldBe true }
    val directory = Paths.get("target/rent-auction-vectors")
    Files.createDirectories(directory)
    Seq("collect-request.json" -> request, "collect-plan.json" -> json).foreach {
      case (name, value) => Files.write(directory.resolve(name), value.spaces2.getBytes(StandardCharsets.UTF_8))
    }
  }

  private def checkedJson(json: Json): RentAuctionPlan = {
    json.hcursor.get[Int]("schemaVersion").toTry.get shouldBe 2
    val tx = json.hcursor.get[ErgoTransaction]("emptyProofTransaction").toTry.get
    val boxes = json.hcursor.get[Vector[org.ergoplatform.ErgoBox]]("inputBoxes").toTry.get
    val at = json.hcursor.get[Int]("height").toTry.get
    val plan = RentAuctionPlan(tx, boxes, at)
    native(plan).get should be > 0
    val active = chain.copy(rentAuctionActivationHeight = Some(1))
    val ext = new RentAuctionRules(contracts, params)
      .extension(Seq(tx -> boxes), at, Blake2b256(owner.bytes))
    val lookup = boxes.map(b => Base16.encode(b.id) -> b).toMap
    ErgoState.execTransactions(Seq(tx), context(at).copy()(active),
      ErgoNodeTestConstants.settings.nodeSettings.copy(checkpoint = None), Some(ext)) { id =>
      Try(lookup(Base16.encode(id)))
    }.toTry.get should be > 0L
    plan
  }

  property("JSON plans preserve wallet inputs, context extensions and token amounts") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(token -> Long.MaxValue))
    val funding = box(30000000L)
    val request = Json.obj(
      "action" -> "collect".asJson,
      "height" -> height.asJson,
      "parameters" -> Json.obj(
        "storageFeeFactor" -> params.storageFeeFactor.asJson,
        "minValuePerByte" -> params.minValuePerByte.asJson),
      "sources" -> Vector(source).asJson,
      "funding" -> Vector(funding).asJson,
      "beneficiary" -> Base16.encode(owner.bytes).asJson,
      "collector" -> Base16.encode(owner.bytes).asJson,
      "change" -> Base16.encode(anyone.bytes).asJson)
    val json = RentAuctionCli.prepare(request, chain).get
    checkedJson(json)
    val expected = new RentAuctionTransactions(contracts, params, height)
      .collect(IndexedSeq(source), IndexedSeq(funding), owner, owner, anyone, 1000000L).get
    json.hcursor.get[String]("transactionId").toOption.get shouldBe
      expected.transaction.id
    json.hcursor.downField("signingRequest").get[Vector[String]]("inputsRaw")
      .toOption.get shouldBe expected.boxes.map(b => Base16.encode(b.bytes))
    val outputs = json.hcursor.get[Vector[org.ergoplatform.ErgoBox]]("outputBoxes")
      .toOption.get
    outputs.head.additionalTokens(0)._2 shouldBe Long.MaxValue

  }

  property("malformed recipients and missing live parameters fail closed") {
    RentAuctionCli.prepare(Json.obj("action" -> "collect".asJson), chain)
      .failed.get.getMessage should include("height")
    val invalid = Json.obj("action" -> "bid".asJson,
      "height" -> height.asJson,
      "parameters" -> Json.obj("storageFeeFactor" -> 1250000.asJson,
        "minValuePerByte" -> 360.asJson),
      "auction" -> lot().asJson, "funding" -> Vector(box(30000000L)).asJson,
      "recipient" -> (Base16.encode(owner.bytes) + "00").asJson, "change" -> Base16.encode(anyone.bytes).asJson,
      "bid" -> 10000000L.asJson)
    RentAuctionCli.prepare(invalid, chain).failed.get.getMessage should include("Non-canonical recipient script")
  }
  property("schema two builds batch closes and explicit token partitions") {
    val collection = collectPlan(2)
    val sourceTokens = collection.boxes.take(2).flatMap(_.additionalTokens.toArray)
    val lots = sourceTokens.map { case (id, amount) => Json.arr(Json.obj(
      "tokenId" -> Base16.encode(id.toArray).asJson, "amount" -> amount.asJson)) }
    val request = Json.obj("action" -> "collect".asJson, "height" -> height.asJson,
      "parameters" -> Json.obj("storageFeeFactor" -> params.storageFeeFactor.asJson,
        "minValuePerByte" -> params.minValuePerByte.asJson),
      "sources" -> collection.boxes.take(2).asJson, "funding" -> collection.boxes.drop(2).asJson,
      "beneficiary" -> Base16.encode(owner.bytes).asJson, "collector" -> Base16.encode(owner.bytes).asJson,
      "change" -> Base16.encode(anyone.bytes).asJson, "lots" -> Json.arr(lots: _*))
    val plan = checkedJson(RentAuctionCli.prepare(request, chain).get)
    val auctions = plan.transaction.outputs.filter(_.ergoTree == contracts.auction)
    auctions.size shouldBe 2
    val inspected = RentAuctionCli.prepare(Json.obj("action" -> "inspect".asJson,
      "height" -> height.asJson, "parameters" -> request.hcursor.downField("parameters").focus.get,
      "boxes" -> auctions.asJson), chain).get
    inspected.hcursor.get[Int]("schemaVersion").toTry.get shouldBe 2
    inspected.hcursor.downField("boxes").downArray.get[String]("collector").toTry.get shouldBe
      Base16.encode(owner.bytes)
    val close = Json.obj("action" -> "close".asJson, "height" -> (height + 720).asJson,
      "parameters" -> request.hcursor.downField("parameters").focus.get,
      "auctions" -> auctions.asJson, "fee" -> 1000001L.asJson)
    val closed = checkedJson(RentAuctionCli.prepare(close, chain).get)
    closed.transaction.inputs.foreach { in =>
      in.spendingProof.extension.values.keySet shouldBe Set(0.toByte, 1.toByte, 2.toByte)
    }
    RentAuctionCli.manifest(chain).hcursor.get[Int]("schemaVersion").toTry.get shouldBe 2
  }
}
