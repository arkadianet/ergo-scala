package org.ergoplatform.modifiers.mempool

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionContracts
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionPlan
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.nodeView.state.ErgoState
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.Parameters
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ByteArrayConstant

import scala.util.Try

/** Reproducible accounting; timings are observations, never pass/fail thresholds. */
class RentAuctionEconomicsSpec extends ErgoCorePropertyTest with RentAuctionFixture with ApiCodecs {
  private val nanoErg = BigDecimal(1000000000L)

  private def activated(plan: RentAuctionPlan, p: Parameters): Try[Long] = Try {
    val active = chain.copy(rentAuctionActivationHeight = Some(1))
    val rules = new RentAuctionRules(contracts, p, validation)
    val extension = rules.extension(Seq(plan.transaction -> plan.boxes), plan.height, Blake2b256(owner.bytes))
    val lookup = plan.boxes.map(b => Base16.encode(b.id) -> b).toMap
    ErgoState.execTransactions(Seq(plan.transaction), context(plan.height).copy(currentParameters = p)(active),
      ErgoNodeTestConstants.settings.nodeSettings.copy(checkpoint = None), Some(extension)) { id =>
      Try(lookup(Base16.encode(id)))
    }.toTry.get
  }

  private def accepted(plan: RentAuctionPlan, p: Parameters): (Int, Long) = {
    val cost = native(plan, p).get
    val extra = new RentAuctionRules(contracts, p, validation).cost(plan.transaction, plan.boxes)
    activated(plan, p).get shouldBe cost.toLong + extra
    (cost, extra)
  }

  private def collection(count: Int, p: Parameters): RentAuctionPlan = {
    val sources = tokens(count).map(t => box(1000000L, owner,
      height - Constants.StoragePeriod, Seq(t))).toIndexedSeq
    new RentAuctionTransactions(contracts, p, height, validation).collect(sources,
      IndexedSeq(box(100000000000L)), owner, owner, anyone, fee(p)).get
  }

  private def fee(p: Parameters): Long = if (p.minValuePerByte == 360) 1000000L else 2000000L

  property("collector economics use real bundled collections and native plus activated execution") {
    Seq(360, 10000).foreach { price =>
      val p = priced(price)
      Seq(1, 20, 32).foreach { count =>
        val opening = collection(count, p)
        val openingCost = accepted(opening, p)
        val fresh = opening.transaction.outputs.head
        val first = new RentAuctionTransactions(contracts, p, height, validation).bid(fresh,
          IndexedSeq(box(1000000000L)), RentAuctionContracts.MINIMUM_BID, recipient256, anyone, fee(p)).get
        val bidCost = accepted(first, p)
        Seq(false, true).foreach { sold =>
          val lot = if (sold) first.transaction.outputs.head else fresh
          val closing = new RentAuctionTransactions(contracts, p, height + RentAuctionContracts.WINDOW, validation)
            .close(IndexedSeq(lot), fee(p)).get
          val closeCost = accepted(closing, p)
          val returned = closing.transaction.outputs.head
          // Producer and collector are the same operator; funding change is returned separately.
          val rent = opening.transaction.outputs.filter(_.ergoTree == owner).map(_.value).sum
          val result = rent + returned.value - fresh.value - fee(p)
          val share = if (sold) RentAuctionContracts.MINIMUM_BID / 10 else 0L
          result shouldBe count * 1000000L - 2 * fee(p) + share
          val payoutSizes = closing.transaction.outputs.map(_.bytes.length).mkString(",")
          info(s"RT-INFO economics price=$price sources=$count sold=$sold fee=${fee(p)} " +
            s"netNanoErg=$result perSourceErg=${BigDecimal(result) / count / nanoErg} " +
            s"sourceBytes=${opening.boxes.head.bytes.length} lotBytes=${fresh.bytes.length} " +
            s"successorBytes=${first.transaction.outputs.head.bytes.length} payouts=[$payoutSizes] " +
            s"seed=${fresh.additionalRegisters(ErgoBox.R8).value} " +
            s"nativeOpenBidClose=${openingCost._1},${bidCost._1},${closeCost._1} " +
            s"extraOpenBidClose=${openingCost._2},${bidCost._2},${closeCost._2}")
        }
        if (price == 360 && count == 20) {
          // This exact, validated schema-2 collection is the transport-only Lithos test fixture.
          val envelope = Json.obj("schemaVersion" -> 2.asJson, "height" -> height.asJson,
            "transaction" -> opening.transaction.asJson)
          val path = Paths.get("target", "rent-auction-bundled-collection.json")
          Files.createDirectories(path.getParent)
          val rendered = envelope.spaces2.split("\n", -1).map(_.reverse.dropWhile(_.isWhitespace).reverse).mkString("\n")
          Files.write(path, (rendered + "\n").getBytes(StandardCharsets.UTF_8))
          info(s"RT-INFO fixture=$path tx=${opening.transaction.id}")
        }
      }
    }
  }

  private def benchmark(label: String, plan: RentAuctionPlan, p: Parameters): Unit = {
    val costs = accepted(plan, p)
    val rules = new RentAuctionRules(contracts, p, validation)
    def run(): Unit = {
      // Decode anew so transaction-local lazy values cannot hide repeated validation work.
      val tx = ErgoTransactionSerializer.parseBytes(plan.transaction.bytes)
      val fresh = plan.copy(transaction = tx)
      native(fresh, p).get shouldBe costs._1
      rules.validate(tx, plan.boxes, plan.height, Some(Blake2b256(owner.bytes))) shouldBe Right(())
    }
    (1 to 10).foreach(_ => run())
    val samples = (1 to 31).map { _ =>
      val start = System.nanoTime()
      run()
      (System.nanoTime() - start).toDouble / 1000000.0
    }.sorted
    val ms = samples(samples.size / 2)
    val total = costs._1.toLong + costs._2
    info(f"RT-INFO resource=$label inputs=${plan.boxes.size} outputs=${plan.transaction.outputs.size} " +
      s"txBytes=${plan.transaction.bytes.length} native=${costs._1} extra=${costs._2} " +
      f"medianMs=$ms%.3f costPerMs=${total / ms}%.1f samples=31 warmup=10")
  }

  property("measure a block-cost-filling collection, 32-lot sale close, and comparable plain transfer") {
    val p = priced(360)
    val rules = new RentAuctionRules(contracts, p, validation)
    def total(plan: RentAuctionPlan): Long = native(plan, p).fold({ error =>
      val reason = "Accumulated cost of block transactions should not exceed <maxBlockCost>"
      error.getMessage should include(reason)
      val activeReason = if (rules.cost(plan.transaction, plan.boxes) > p.maxBlockCost)
        "Rent-auction validation exceeds block cost" else reason
      activated(plan, p).failed.get.getMessage should include(activeReason)
      Long.MaxValue
    }, cost => cost.toLong + rules.cost(plan.transaction, plan.boxes))
    var low = 1
    var high = 2048
    total(collection(high, p)) should be > p.maxBlockCost.toLong
    while (high - low > 1) {
      val middle = (high + low) / 2
      if (total(collection(middle, p)) <= p.maxBlockCost) low = middle else high = middle
    }
    val largest = collection(low, p)
    accepted(largest, p)
    largest.transaction.bytes.length should be < p.maxBlockSize
    val exceeds = collection(high, p)
    native(exceeds, p).get should be > 0
    activated(exceeds, p).failed.get.getMessage should include(
      "Accumulated cost of block transactions should not exceed <maxBlockCost>")
    info(s"RT-INFO blockLimit=${p.maxBlockCost} collectionSources=$low " +
      s"lots=${largest.transaction.outputs.count(_.ergoTree == contracts.auction)} nextSources=$high")
    benchmark("collection", largest, p)

    val sales = (1 to 32).map { _ =>
      val opened = collection(1, p)
      accepted(opened, p)
      val bid = new RentAuctionTransactions(contracts, p, height, validation).bid(opened.transaction.outputs.head,
        IndexedSeq(box(1000000000L)), RentAuctionContracts.MINIMUM_BID, recipient256, anyone, fee(p)).get
      accepted(bid, p)
      bid.transaction.outputs.head
    }.toIndexedSeq
    val closed = new RentAuctionTransactions(contracts, p, height + RentAuctionContracts.WINDOW, validation).close(sales, fee(p)).get
    benchmark("close32", closed, p)

    Seq("collection" -> largest, "close32" -> closed).foreach { case (label, plan) =>
      // Match wire sizes by replacing contract bytes with ordinary output register padding.
      val inputs = plan.boxes.map(b => box(b.value, anyone, created = plan.height,
        tokens = b.additionalTokens.toArray.toSeq))
      val outputs = plan.transaction.outputCandidates.map { b =>
        if (b.ergoTree != contracts.auction && b.ergoTree != contracts.deposit) b
        else {
          val padding = 32 + b.ergoTree.bytes.length - anyone.bytes.length
          output(b.value, anyone, h = b.creationHeight, tokens = b.additionalTokens.toArray.toSeq,
            registers = b.additionalRegisters.updated(ErgoBox.R4, ByteArrayConstant(new Array[Byte](padding))))
        }
      }
      val plain = RentAuctionPlan(transaction(inputs, outputs), inputs, plan.height)
      benchmark(s"plain-transfer-padded-$label", plain, p)
      math.abs(plain.transaction.bytes.length - plan.transaction.bytes.length) should be < plan.transaction.bytes.length / 10
    }
  }
}
