package org.ergoplatform.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.util.encode.Base16

class RentAuctionCliSpec extends ErgoCorePropertyTest
  with RentAuctionFixture with ApiCodecs {

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
      "change" -> Base16.encode(anyone.bytes).asJson)
    val json = RentAuctionCli.prepare(request, chain).get
    val expected = new RentAuctionTransactions(contracts, params, height)
      .collect(IndexedSeq(source), IndexedSeq(funding), owner, anyone, 1000000L).get
    json.hcursor.get[String]("transactionId").toOption.get shouldBe
      expected.transaction.id
    json.hcursor.downField("signingRequest").get[Vector[String]]("inputsRaw")
      .toOption.get shouldBe expected.boxes.map(b => Base16.encode(b.bytes))
    val outputs = json.hcursor.get[Vector[org.ergoplatform.ErgoBox]]("outputBoxes")
      .toOption.get
    outputs.head.additionalTokens(0)._2 shouldBe Long.MaxValue
    val directory = Paths.get("target/rent-auction-vectors")
    Files.createDirectories(directory)
    Seq("collect-request.json" -> request, "collect-plan.json" -> json,
      "mainnet-contracts.json" -> RentAuctionCli.manifest(chain)).foreach {
      case (name, value) => Files.write(directory.resolve(name),
        value.spaces2.getBytes(StandardCharsets.UTF_8))
    }
  }

  property("malformed recipients and missing live parameters fail closed") {
    RentAuctionCli.prepare(Json.obj("action" -> "collect".asJson), chain)
      .isFailure shouldBe true
    val invalid = Json.obj("action" -> "bid".asJson,
      "height" -> height.asJson,
      "parameters" -> Json.obj("storageFeeFactor" -> 1250000.asJson,
        "minValuePerByte" -> 360.asJson),
      "auction" -> lot().asJson, "funding" -> Vector(box(30000000L)).asJson,
      "recipient" -> "00".asJson, "change" -> Base16.encode(anyone.bytes).asJson,
      "bid" -> 10000000L.asJson)
    RentAuctionCli.prepare(invalid, chain).isFailure shouldBe true
  }
}
