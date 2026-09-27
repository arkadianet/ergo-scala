package org.ergoplatform.modifiers.mempool

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionContracts
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionFixture
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionPlan
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionRules
import org.ergoplatform.modifiers.mempool.rentauction.RentAuctionTransactions
import org.ergoplatform.nodeView.state.ErgoState
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.ErgoValidationSettings
import org.ergoplatform.settings.ErgoValidationSettingsUpdate
import org.ergoplatform.settings.Parameters
import org.ergoplatform.settings.ValidationRules
import org.ergoplatform.utils.BoxUtils
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ByteArrayConstant
import sigma.ast.IntArrayConstant
import sigma.ast.IntConstant
import sigma.ast.LongConstant
import sigma.interpreter.ContextExtension
import sigma.interpreter.ProverResult

import scala.util.Try

/** Native plus activated block-executor evidence, independent of builder preflight. */
class RentAuctionV2Spec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.CLOSE_ALLOWANCE
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.WINDOW

  private val rule124Off = ErgoValidationSettings.initial.updated(
    ErgoValidationSettingsUpdate(Seq(ValidationRules.txMonotonicHeight), Seq()))
  private val rule123Off = ErgoValidationSettings.initial.updated(
    ErgoValidationSettingsUpdate(Seq(ValidationRules.txReemission), Seq()))

  private def executeActivated(plan: RentAuctionPlan, p: Parameters = params,
    extension: Option[ExtensionCandidate] = None,
    vs: ErgoValidationSettings = validation, activated: Boolean = true): Try[Long] = Try {
    val active = chain.copy(rentAuctionActivationHeight = if (activated) Some(1) else None)
    val rules = new RentAuctionRules(contracts, p, vs)
    val txs = Seq(plan.transaction -> plan.boxes)
    val ext = extension.getOrElse(rules.extension(txs, plan.height, Blake2b256(owner.bytes)))
    val lookup = plan.boxes.map(b => Base16.encode(b.id) -> b).toMap
    ErgoState.execTransactions(Seq(plan.transaction),
      context(plan.height).copy(currentParameters = p, validationSettings = vs)(active),
      ErgoNodeTestConstants.settings.nodeSettings.copy(checkpoint = None), Some(ext)) { id =>
      Try(lookup(Base16.encode(id)))
    }.toTry.get
  }

  private def accepted(plan: RentAuctionPlan, p: Parameters = params,
    vs: ErgoValidationSettings = validation): Unit = {
    val nativeCost = native(plan, p, vs).get
    nativeCost should be > 0
    executeActivated(plan, p, vs = vs).get shouldBe
      nativeCost.toLong + new RentAuctionRules(contracts, p, vs).cost(plan.transaction, plan.boxes)
  }

  private def rejected(plan: RentAuctionPlan, reason: String,
    p: Parameters = params, nativePasses: Boolean = true,
    vs: ErgoValidationSettings = validation): Unit = {
    if (nativePasses) native(plan, p, vs).get should be > 0
    else native(plan, p, vs).failed.get.getMessage should include(reason)
    executeActivated(plan, p, vs = vs).failed.get.getMessage should include(reason)
  }

  private def withOutputs(plan: RentAuctionPlan, outs: IndexedSeq[ErgoBoxCandidate]): RentAuctionPlan =
    plan.copy(transaction = ErgoTransaction(plan.transaction.inputs, outs))

  private def register(out: ErgoBoxCandidate, id: ErgoBox.NonMandatoryRegisterId,
    value: sigma.ast.EvaluatedValue[_ <: sigma.ast.SType]): ErgoBoxCandidate =
    new ErgoBoxCandidate(out.value, out.ergoTree, out.creationHeight,
      out.additionalTokens, out.additionalRegisters.updated(id, value))

  private def extension(plan: RentAuctionPlan, input: Int, key: Byte,
    value: sigma.ast.EvaluatedValue[_ <: sigma.ast.SType]): RentAuctionPlan = {
    val in = plan.transaction.inputs(input)
    val replacement = Input(in.boxId, ProverResult(in.spendingProof.proof,
      ContextExtension(in.spendingProof.extension.values.updated(key, value))))
    plan.copy(transaction = ErgoTransaction(plan.transaction.inputs.updated(input, replacement),
      plan.transaction.outputCandidates))
  }

  private def bid(plan: RentAuctionPlan, p: Parameters = params): RentAuctionPlan =
    new RentAuctionTransactions(contracts, p, plan.height, validation).bid(plan.transaction.outputs.head,
      IndexedSeq(box(1000000000L, created = plan.height)), MINIMUM_BID, recipient256, anyone,
      if (p.minValuePerByte > 360) 2000000L else 1000000L).get

  private val scriptFailure = "Scripts of all transaction inputs should pass verification"

  private def dated(out: ErgoBoxCandidate, at: Int): ErgoBoxCandidate =
    new ErgoBoxCandidate(out.value, out.ergoTree, at, out.additionalTokens, out.additionalRegisters)

  private def freshLot(source: ErgoBox): ErgoBoxCandidate = {
    val rules = new RentAuctionRules(contracts, params, validation)
    def candidate(seed: Long): ErgoBoxCandidate = output(seed + CLOSE_ALLOWANCE,
      contracts.auction, tokens = source.additionalTokens.toArray.toSeq,
      registers = lotRegisters(rules.commitment(Seq(source)), height + WINDOW,
        height + MAXIMUM_WINDOW, seed = seed))
    candidate(rules.openingSeed(candidate(1L)))
  }

  property("beneficiary rent payments must be fresh while unrelated old payments remain allowed") {
    val aged = height - Constants.StoragePeriod
    val source = box(1000000L, nobody, aged, Seq(token -> 100L))
    val funding = box(100000000L, anyone, aged)
    val inputs = IndexedSeq(source, funding)
    val lot = freshLot(source)
    val outputs = IndexedSeq(lot, output(source.value, owner),
      output(funding.value - lot.value - 1000000L), output(1000000L, owner, aged))
    val plan = RentAuctionPlan(transaction(inputs, outputs, Map(0 -> 0.toShort)), inputs, height)
    accepted(plan)
    accepted(plan, vs = rule124Off)
    Seq(aged -> validation, 0 -> rule124Off).foreach { case (at, vs) =>
      rejected(withOutputs(plan, outputs.updated(1, dated(outputs(1), at))),
        "rent ERG must pay the designated beneficiary separately", vs = vs)
    }
  }

  property("native EIP-27 redemption payments in rent collections must be fresh") {
    val aged = height - Constants.StoragePeriod
    val debt = sigma.data.Digest32Coll @@ chain.reemission.reemissionTokenIdBytes
    val source = box(1000000L, nobody, aged, Seq(debt -> 1000000L, token -> 100L))
    val plan = new RentAuctionTransactions(contracts, params, height, validation)
      .collect(IndexedSeq(source), IndexedSeq(box(100000000L, anyone, aged)),
        owner, owner, anyone, 1000000L).get
    accepted(plan)
    val outputs = plan.transaction.outputCandidates
    val payment = outputs.indexWhere(_.ergoTree == contracts.legacyDeposit)
    payment should be >= 0
    rejected(withOutputs(plan, outputs.updated(payment, dated(outputs(payment), aged))),
      "EIP-27 redemption payments must be freshly dated")
  }

  property("rule 123 disabled requires accounting tokens in lots instead of a paid burn") {
    val debt = sigma.data.Digest32Coll @@ chain.reemission.reemissionTokenIdBytes
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(debt -> 1000000L, token -> 100L))
    val funding = IndexedSeq(box(100000000L))
    def collect(vs: ErgoValidationSettings): RentAuctionPlan =
      new RentAuctionTransactions(contracts, params, height, vs)
        .collect(IndexedSeq(source), funding, owner, owner, anyone, 1000000L).get
    val auctioned = collect(rule123Off)
    auctioned.transaction.outputs.head.additionalTokens.toArray.toSeq should contain(debt -> 1000000L)
    auctioned.transaction.outputs.exists(_.ergoTree == contracts.legacyDeposit) shouldBe false
    accepted(auctioned, vs = rule123Off)
    val burned = collect(validation)
    accepted(burned)
    rejected(burned, "fresh lot token totals must equal fully consumed token totals", vs = rule123Off)
  }

  property("funded accounting tokens cannot satisfy recreation and triggered native redemption together") {
    val debt = sigma.data.Digest32Coll @@ chain.reemission.reemissionTokenIdBytes
    val aged = height - Constants.StoragePeriod
    val boundary = 100000L * EmissionRules.CoinsInOneErgo
    val reason = "Funded source holds the EIP-27 accounting token while native redemption applies; " +
      "its recreation must keep the token and redemption forbids it; skip this box"
    val builder = new RentAuctionTransactions(contracts, params, height, validation)
    val funding = IndexedSeq(box(100000000L))
    def collect(sources: ErgoBox*): scala.util.Try[RentAuctionPlan] =
      builder.collect(sources.toIndexedSeq, funding, owner, owner, anyone, 1000000L)
    Seq(10000000000L, boundary).foreach { value =>
      val source = box(value, nobody, aged, Seq(debt -> 1000000L, token -> 50L))
      collect(source).failed.get.getMessage should include(reason)
      val charge = params.storageFeeFactor * source.bytes.length
      charge.toLong should be < source.value
      val inputs = source +: funding
      val outputs = IndexedSeq(
        output(source.value - charge, nobody, tokens = source.additionalTokens.toArray.toSeq),
        output(charge.toLong, owner), output(1000000L, contracts.legacyDeposit),
        output(funding.head.value - 1000000L))
      val keep = RentAuctionPlan(transaction(inputs, outputs, Map(0 -> 0.toShort)), inputs, height)
      rejected(keep, "conform EIP-27 rules", nativePasses = false)
      accepted(keep, vs = rule123Off)
      val stripped = output(outputs.head.value, nobody, tokens = Seq(token -> 50L))
      rejected(withOutputs(keep, outputs.updated(0, stripped)),
        "#0 => Success((false,50))", nativePasses = false)
      val plain = box(value, nobody, aged, Seq(token -> 50L))
      accepted(collect(plain).get)
    }
    val large = box(boundary + 1L, nobody, aged, Seq(debt -> 1000000L, token -> 50L))
    val alone = collect(large).get
    alone.transaction.outputs.head.additionalTokens shouldBe large.additionalTokens
    alone.transaction.outputs.exists(_.ergoTree == contracts.legacyDeposit) shouldBe false
    accepted(alone)
    val consumed = box(1000000L, nobody, aged, Seq(debt -> 1000000L))
    collect(large, consumed).failed.get.getMessage should include(reason)
    accepted(collect(consumed).get)
  }

  property("native Int storage charges remain pinned across exact serialized wrap boundaries") {
    params.storageFeeFactor shouldBe 1250000
    val rules = new RentAuctionRules(contracts, params, validation)
    val builder = new RentAuctionTransactions(contracts, params, height, validation)
    val funding = box(5000000000L)
    Seq(1717 -> 2146250000, 1718 -> -2147467296, 3435 -> -1217296, 3436 -> 32704)
      .foreach { case (size, expected) =>
        val dust = size * params.minValuePerByte.toLong
        // A funded recreation needs dust for its wider current-height encoding too.
        val value = if (size == 3436) 2L * dust else dust
        val source = (0 to size).iterator.map { padding =>
          box(value, nobody, height - Constants.StoragePeriod, Seq(token -> 100L),
            Map(ErgoBox.R4 -> ByteArrayConstant(new Array[Byte](padding))))
        }.find(_.bytes.length == size).get
        source.bytes.length shouldBe size
        val charge = params.storageFeeFactor * source.bytes.length
        charge shouldBe expected
        BoxUtils.minimalErgoAmount(source, params) shouldBe dust
        val claim = rules.classify(source, rentInput(source, 0.toShort), 3, height).get
        val built = builder.collect(IndexedSeq(source), IndexedSeq(funding),
          owner, owner, anyone, 1000000L)
        if (size == 1717) {
          source.value shouldBe dust
          claim.fullyConsumed shouldBe true
          accepted(built.get)
        } else if (charge < 0) {
          claim.fullyConsumed shouldBe false
          built.failed.get.getMessage should include("Legacy wrapping rent charge is non-positive")
          val lot = freshLot(source)
          val inputs = IndexedSeq(source, funding)
          val outputs = IndexedSeq(lot, output(source.value, owner), output(funding.value - lot.value))
          val bad = RentAuctionPlan(transaction(inputs, outputs, Map(0 -> 0.toShort)), inputs, height)
          rejected(bad, "#0 => Success((false,50))", nativePasses = false)
          executeActivated(bad, activated = false).failed.get.getMessage should include(
            "#0 => Success((false,50))")
          val recreation = output(source.value - charge, nobody,
            tokens = source.additionalTokens.toArray.toSeq, registers = source.additionalRegisters)
          val keep = RentAuctionPlan(transaction(inputs,
            IndexedSeq(recreation, output(funding.value + charge, owner)), Map(0 -> 0.toShort)), inputs, height)
          accepted(keep)
          executeActivated(keep, activated = false).get shouldBe native(keep).get.toLong
        } else {
          charge.toLong should be < dust
          claim.fullyConsumed shouldBe false
          val plan = built.get
          plan.transaction.outputs.head.additionalTokens shouldBe source.additionalTokens
          plan.transaction.outputs.exists(_.ergoTree == contracts.auction) shouldBe false
          accepted(plan)
        }
      }
  }

  property("opening, full refund, sale, burn and reserve merge execute at zero, default and maximum byte prices") {
    Parameters.MinValueMin shouldBe 0
    Seq(0, 360, 10000).foreach { price =>
      val p = priced(price)
      val fee = if (price <= 360) 1000000L else 2000000L
      Seq(1, 20, 32).foreach { n =>
        val collection = collectPlan(n, p, recipient256)
        accepted(collection, p)
        val first = bid(collection, p)
        accepted(first, p)
        val second = new RentAuctionTransactions(contracts, p, height, validation)
          .bid(first.transaction.outputs.head, IndexedSeq(box(1000000000L)), MINIMUM_BID + INCREMENT,
            owner, anyone, fee).get
        accepted(second, p)
        second.transaction.outputs(1).value shouldBe MINIMUM_BID
        Seq(collection.transaction.outputs.head, first.transaction.outputs.head, second.transaction.outputs.head).foreach { lot =>
          val closed = new RentAuctionTransactions(contracts, p, height + WINDOW, validation)
            .close(IndexedSeq(lot), fee).get
          accepted(closed, p)
          if (lot.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long] > 0L) {
            val reserve = box(1000000000000L, contracts.reserve, height + WINDOW - 1, Seq(nft -> 1L))
            val sponsor = if (price <= 360) None else Some(box(1000000L, created = height + WINDOW))
            val merge = new RentAuctionTransactions(contracts, p, height + WINDOW, validation)
              .merge(reserve, IndexedSeq(closed.transaction.outputs(2)), sponsor).get
            accepted(merge, p)
          }
        }
      }
    }
  }

  property("votes do not change seed principal or strand prescribed payouts") {
    val collection = collectPlan(32)
    accepted(collection)
    val first = bid(collection)
    accepted(first)
    Seq(collection.transaction.outputs.head, first.transaction.outputs.head).foreach { lot =>
      val closed = new RentAuctionTransactions(contracts, priced(10000), height + WINDOW, validation)
        .close(IndexedSeq(lot), 2000000L).get
      accepted(closed, priced(10000))
    }
  }

  property("mixed sold and unsold same-id lots close with disjoint payouts and deterministic fees") {
    val a = collectPlan()
    val b = collectPlan()
    accepted(a)
    accepted(b)
    val sold = bid(a)
    accepted(sold)
    val closed = new RentAuctionTransactions(contracts, params, height + WINDOW, validation)
      .close(IndexedSeq(sold.transaction.outputs.head, b.transaction.outputs.head), 1000001L).get
    accepted(closed)
    closed.transaction.outputs.flatMap(_.additionalTokens.toArray).map(_._2).sum shouldBe 1000000000L
    val wrongPrice = extension(closed, 0, 2.toByte, IntConstant(params.minValuePerByte - 1))
    // Change price and payouts together so the script passes; the activated rule authenticates the price.
    val outs = wrongPrice.transaction.outputCandidates
    val delta = 128L + recipient256.bytes.length + 42L
    val changed = withOutputs(wrongPrice, outs.updated(1, change(outs(1), outs(1).value - delta, outs(1).ergoTree))
      .updated(2, change(outs(2), outs(2).value + delta, outs(2).ergoTree)))
    rejected(changed, "close byte price must equal active minValuePerByte")
    val fees = extension(extension(closed, 0, 1.toByte, LongConstant(500000L)),
      1, 1.toByte, LongConstant(500001L))
    val feeOuts = fees.transaction.outputCandidates
    rejected(withOutputs(fees, feeOuts.updated(0, change(feeOuts(0), feeOuts(0).value + 1, feeOuts(0).ergoTree))
      .updated(3, change(feeOuts(3), feeOuts(3).value - 1, feeOuts(3).ergoTree))),
      "incorrect deterministic close fee allocation")
  }

  property("sharing close slots fails native and activated validation with valid context variables") {
    val collections = IndexedSeq(collectPlan(), collectPlan())
    collections.foreach(accepted(_))
    val sales = collections.map(bid(_))
    sales.foreach(accepted(_))
    val closed = new RentAuctionTransactions(contracts, params, height + WINDOW, validation)
      .close(sales.map(_.transaction.outputs.head), 1000000L).get
    accepted(closed)
    val sharing = extension(closed, 1, 0.toByte, IntConstant(0))
    sharing.transaction.inputs(1).spendingProof.extension.values.keySet shouldBe
      Set(0.toByte, 1.toByte, 2.toByte)
    rejected(sharing, "#1 => Success((false,", nativePasses = false)
    // Native validation runs first in execTransactions and rejects the second input's tag.
    // This direct check is supporting evidence for the additional node rule's precise reason.
    new RentAuctionRules(contracts, params, validation)
      .validate(sharing.transaction, sharing.boxes, sharing.height, None) shouldBe
      Left("close payout ranges must be disjoint")
  }

  property("unsold quantities cannot escape through extra outputs or collector tokens") {
    val collection = collectPlan()
    accepted(collection)
    val closed = new RentAuctionTransactions(contracts, params, height + WINDOW, validation)
      .close(IndexedSeq(collection.transaction.outputs.head), 1000000L).get
    accepted(closed)
    val outs = closed.transaction.outputCandidates
    val extra = output(500000L, anyone, height + WINDOW, tokens(1))
    rejected(withOutputs(closed, outs.updated(1, change(outs(1), 500000L, contracts.fee)) :+ extra),
      "close requires exactly one unclaimed fee output")
    val stolen = output(outs.head.value, outs.head.ergoTree, height + WINDOW, tokens(1), outs.head.additionalRegisters)
    rejected(withOutputs(closed, outs.updated(0, stolen)), scriptFailure, nativePasses = false)
  }

  property("same-id aggregation and explicit partitions remain exact") {
    val sources = (1 to 3).map(_ => box(1000000L, nobody,
      height - Constants.StoragePeriod, Seq(token -> 100L))).toIndexedSeq
    val builder = new RentAuctionTransactions(contracts, params, height, validation)
    val bundled = builder.collect(sources, IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L).get
    accepted(bundled)
    bundled.transaction.outputs.head.additionalTokens(0)._2 shouldBe 300L
    val split = builder.collect(sources, IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L,
      partition = Seq(Seq(token -> 125L), Seq(token -> 175L))).get
    accepted(split)
    val out = bundled.transaction.outputCandidates.head
    val stolen = output(out.value, out.ergoTree, height, Seq(token -> 299L), out.additionalRegisters)
    rejected(withOutputs(bundled, bundled.transaction.outputCandidates.updated(0, stolen)),
      "fresh lot token totals must equal fully consumed token totals")
  }

  property("fresh lots reject changed origin, deadlines, preset bids, underfunded seed and noncanonical parties") {
    val plan = collectPlan()
    accepted(plan)
    val outs = plan.transaction.outputCandidates
    val lot = outs.head
    Seq(
      ErgoBox.R4 -> ByteArrayConstant(new Array[Byte](32)),
      ErgoBox.R5 -> IntArrayConstant(Array(height + WINDOW - 1, height + 1440))
    ).foreach { case (id, value) =>
      rejected(withOutputs(plan, outs.updated(0, register(lot, id, value))),
        "fully consumed tokens must enter fresh canonical lots")
    }
    val seeded = register(lot, ErgoBox.R8, LongConstant(lot.value - CLOSE_ALLOWANCE - 1L))
    rejected(withOutputs(plan, outs.updated(0, seeded)), "auction state requires canonical registers and parsed party scripts")
    val invalid = register(lot, ErgoBox.R9, ByteArrayConstant(Array(0.toByte)))
    rejected(withOutputs(plan, outs.updated(0, invalid)), "auction state requires canonical registers and parsed party scripts")
    val fakeBid = register(register(lot, ErgoBox.R6, LongConstant(MINIMUM_BID)),
      ErgoBox.R7, ByteArrayConstant(owner.bytes))
    val funded = change(fakeBid, fakeBid.value + MINIMUM_BID, contracts.auction)
    val changeIndex = outs.size - 2
    rejected(withOutputs(plan, outs.updated(0, funded).updated(changeIndex,
      change(outs(changeIndex), outs(changeIndex).value - MINIMUM_BID, outs(changeIndex).ergoTree))),
      "fully consumed tokens must enter fresh canonical lots")
  }

  property("fresh lots cannot be created by ordinary inputs or count as beneficiary rent") {
    val plan = collectPlan()
    accepted(plan)
    val inputs = plan.boxes.map(b => box(b.value, anyone, height, b.additionalTokens.toArray.toSeq))
    val ordinary = RentAuctionPlan(transaction(inputs, plan.transaction.outputCandidates), inputs, height)
    rejected(ordinary, "auction creation requires a rent source")
    val outs = plan.transaction.outputCandidates
    val redirected = withOutputs(plan, outs.updated(1, change(outs(1), outs(1).value, anyone)))
    rejected(redirected, "rent ERG must pay the designated beneficiary separately")
    val ext = new RentAuctionRules(contracts, params, validation).extension(Seq(plan.transaction -> plan.boxes),
      height, Blake2b256(contracts.auction.bytes))
    native(plan).get should be > 0
    executeActivated(plan, extension = Some(ext)).failed.get.getMessage should include(
      "rent ERG must pay the designated beneficiary separately")
  }

  property("funded recreations remain unique and cannot discharge fresh-lot obligations") {
    val created = height - Constants.StoragePeriod
    val a = box(1000000000L, owner, created, Seq(token -> 100L))
    val b = box(a.value, owner, created, Seq(token -> 100L))
    val fee = params.storageFeeFactor * a.bytes.length
    val ins = IndexedSeq(a, b)
    val outs = IndexedSeq(output(a.value - fee, owner, tokens = Seq(token -> 100L)),
      output(b.value + fee, owner, tokens = Seq(token -> 100L)))
    rejected(RentAuctionPlan(transaction(ins, outs, Map(0 -> 0.toShort, 1 -> 0.toShort)), ins, height),
      "rent obligations must have distinct output indices")
    val funded = new RentAuctionTransactions(contracts, params, height, validation)
      .collect(ins, IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L).get
    accepted(funded)
    funded.transaction.outputs.count(_.ergoTree == contracts.auction) shouldBe 0
  }

  property("accounting tokens burn and their native payment remains separate") {
    val debt = sigma.data.Digest32Coll @@ chain.reemission.reemissionTokenIdBytes
    val source = box(1000000L, nobody, height - Constants.StoragePeriod,
      Seq(debt -> 1000000L, token -> 100L))
    val plan = new RentAuctionTransactions(contracts, params, height, validation)
      .collect(IndexedSeq(source), IndexedSeq(box(100000000L)), owner, owner, anyone, 1000000L).get
    accepted(plan)
    plan.transaction.outputs.head.additionalTokens.toArray.toSeq shouldBe Seq(token -> 100L)
    plan.transaction.outputs.filter(_.ergoTree == contracts.legacyDeposit).map(_.value).sum shouldBe 1000000L
  }

  property("aged auctions, deposits and authentic reserve cannot enter the rent shortcut") {
    val aged = height - Constants.StoragePeriod
    Seq(lot(created = aged, end = aged + WINDOW, cap = aged + 1440), depositBox(MINIMUM_BID, aged),
      box(1000000L, contracts.reserve, aged, Seq(nft -> 1L))).foreach { b =>
      val plan = RentAuctionPlan(transaction(IndexedSeq(b), IndexedSeq(output(b.value, anyone,
        tokens = b.additionalTokens.toArray.toSeq)), Map(0 -> 0.toShort)), IndexedSeq(b), height)
      rejected(plan, "rent cannot bypass or share an auction/deposit transaction")
    }
  }

  property("32 closes fit and a 33rd is refused by activated rules") {
    val lots = (1 to 33).map(_ => lot()).toIndexedSeq
    val builder = new RentAuctionTransactions(contracts, params, height + WINDOW, validation)
    accepted(builder.close(lots.take(32), 1000000L).get)
    // Assemble without the builder limit, with unique tags and script-valid allocations.
    val plans = lots.map(b => settleSpend(b, height + WINDOW))
    val inputs = plans.zipWithIndex.map { case (s, i) =>
      val in = s.tx.inputs.head
      Input(in.boxId, ProverResult(in.spendingProof.proof,
        ContextExtension(in.spendingProof.extension.values.updated(0.toByte, IntConstant(i)))))
    }
    val outs = plans.map(_.tx.outputCandidates.head) :+ output(33000000L, contracts.fee, height + WINDOW)
    rejected(RentAuctionPlan(ErgoTransaction(inputs, outs), lots, height + WINDOW),
      "close requires 1 to 32 lots and no funding inputs")
  }

  property("one-million fee dust and maximum-height bidding use native and activated validation") {
    val fund = box(1000000L)
    val plan = RentAuctionPlan(transaction(IndexedSeq(fund), IndexedSeq(output(fund.value, contracts.fee))),
      IndexedSeq(fund), height)
    rejected(plan, "Every output of the transaction should contain at least", priced(10000), nativePasses = false)
    val b = lot(end = Int.MaxValue, cap = Int.MaxValue, created = Int.MaxValue - 1440)
    val first = bidSpend(b, MINIMUM_BID, Int.MaxValue - 1, Int.MaxValue, Int.MaxValue)
    accepted(RentAuctionPlan(first.tx, first.boxes, first.at))
    val closed = settleSpend(first.tx.outputs.head, Int.MaxValue)
    accepted(RentAuctionPlan(closed.tx, closed.boxes, closed.at))
  }

  property("attestation failures are rejected by the activated executor") {
    val plan = collectPlan()
    native(plan).get should be > 0
    val rules = new RentAuctionRules(contracts, params, validation)
    val correct = rules.extension(Seq(plan.transaction -> plan.boxes), height, Blake2b256(owner.bytes))
    val wrongHash = ExtensionCandidate(correct.fields.map { case (key, value) =>
      key -> (if (key.sameElements(RentAuctionRules.ATTESTATION_KEY)) new Array[Byte](32) else value)
    })
    Seq(wrongHash, ExtensionCandidate(correct.fields :+ correct.fields.head)).foreach { ext =>
      executeActivated(plan, extension = Some(ext)).failed.get.getMessage should include(
        "missing, duplicated or incorrect rent extension fields")
    }
  }

  property("bid rejection vectors pass through native and activated validation") {
    val fresh = collectPlan()
    accepted(fresh)
    val first = bid(fresh)
    accepted(first)
    val spend = bidSpend(first.transaction.outputs.head, MINIMUM_BID + INCREMENT,
      recipient = owner)
    val plan = RentAuctionPlan(spend.tx, spend.boxes, spend.at)
    accepted(plan)
    val outs = plan.transaction.outputCandidates
    Seq(
      outs.updated(1, change(outs(1), outs(1).value, anyone)),
      outs.updated(1, register(outs(1), ErgoBox.R4, ByteArrayConstant(new Array[Byte](32)))),
      outs.updated(0, register(outs(0), ErgoBox.R9, ByteArrayConstant(anyone.bytes))),
      outs.updated(0, register(outs(0), ErgoBox.R4, ByteArrayConstant(new Array[Byte](32)))),
      outs.updated(0, register(outs(0), ErgoBox.R5, IntArrayConstant(Array(height, height + 1440))))
    ).foreach(o => rejected(withOutputs(plan, o), scriptFailure, nativePasses = false))
    val unparsed = register(first.transaction.outputCandidates.head, ErgoBox.R7, ByteArrayConstant(Array(0.toByte)))
    rejected(withOutputs(first, first.transaction.outputCandidates.updated(0, unparsed)),
      "auction state requires canonical registers and parsed party scripts")
    Seq(MINIMUM_BID - 1, MINIMUM_BID, MINIMUM_BID + INCREMENT - 1).foreach { value =>
      val wrong = bidSpend(first.transaction.outputs.head, value)
      rejected(RentAuctionPlan(wrong.tx, wrong.boxes, wrong.at), scriptFailure, nativePasses = false)
    }
    val late = bidSpend(fresh.transaction.outputs.head, MINIMUM_BID, height + WINDOW - 1)
    accepted(RentAuctionPlan(late.tx, late.boxes, late.at))
    val ended = bidSpend(fresh.transaction.outputs.head, MINIMUM_BID, height + WINDOW)
    rejected(RentAuctionPlan(ended.tx, ended.boxes, ended.at), scriptFailure, nativePasses = false)
  }

  property("opening seed binds the conservative size even above native dust") {
    val p = priced(10000)
    val plan = collectPlan(32, p)
    accepted(plan, p)
    val outs = plan.transaction.outputCandidates
    val fresh = outs.head
    val seed = fresh.additionalRegisters(ErgoBox.R8).value.asInstanceOf[Long]
    val reduced = change(register(fresh, ErgoBox.R8, LongConstant(seed - 1)), fresh.value - 1, contracts.auction)
    val changeIndex = outs.size - 2
    rejected(withOutputs(plan, outs.updated(0, reduced).updated(changeIndex,
      change(outs(changeIndex), outs(changeIndex).value + 1, outs(changeIndex).ergoTree))),
      "fully consumed tokens must enter fresh canonical lots", p)
  }

  property("dense consumed sources split into three lots without changing native wrapped arithmetic") {
    val source = box(5000000L, nobody, height - Constants.StoragePeriod, tokens(90, Long.MaxValue))
    val plan = new RentAuctionTransactions(contracts, params, height, validation)
      .collect(IndexedSeq(source), IndexedSeq(box(1000000000L)), owner, owner, anyone, 1000000L).get
    accepted(plan)
    plan.transaction.outputs.count(_.ergoTree == contracts.auction) shouldBe 3
  }

  property("native-valid extra token funding and unauthorized output tokens fail collection restrictions") {
    val plan = collectPlan()
    accepted(plan)
    val sponsor = box(1000000L, tokens = Seq(token -> 1L))
    val outs = plan.transaction.outputCandidates
    val extraInputs = plan.boxes :+ sponsor
    val fundedTx = ErgoTransaction(plan.transaction.inputs :+ Input(sponsor.id, ProverResult.empty),
      outs.updated(outs.size - 1, change(outs.last, outs.last.value + sponsor.value, contracts.fee)))
    rejected(RentAuctionPlan(fundedTx, extraInputs, height), "collection funding must be token-free")
    // The native mint exception cannot put unrelated assets into a collection output.
    val mint = sigma.data.Digest32Coll @@ sigma.Colls.fromArray(plan.boxes.head.id)
    val changed = output(outs(1).value, outs(1).ergoTree, height, Seq(mint -> 1L))
    rejected(withOutputs(plan, outs.updated(1, changed)),
      "collection tokens may only enter recreations or fresh lots")
  }

  property("deposits cannot divert principal, double count or accompany a reserve withdrawal") {
    val deposits = IndexedSeq(depositBox(MINIMUM_BID), depositBox(MINIMUM_BID))
    val merge = mergeSpend(deposits)
    val plan = RentAuctionPlan(merge.tx, merge.boxes, merge.at)
    accepted(plan)
    val outs = plan.transaction.outputCandidates
    rejected(withOutputs(plan, outs.updated(0, change(outs.head, outs.head.value - 1, contracts.reserve))
      .updated(1, change(outs(1), outs(1).value + 1, contracts.fee))), scriptFailure, nativePasses = false)
    val reserve = plan.boxes.head
    val reward = chain.reemission.reemissionRules.reemissionRewardPerBlock
    val miner = org.ergoplatform.ErgoTreePredef.rewardOutputScript(chain.monetary.minerRewardDelay,
      defaults.defaultMinerPk)
    val withdrawal = IndexedSeq(output(reserve.value - reward, contracts.reserve, tokens = Seq(nft -> 1L)),
      output(reward + deposits.map(_.value).sum, miner))
    rejected(withOutputs(plan, withdrawal), scriptFailure, nativePasses = false)
    val malformed = output(100000000L, contracts.deposit)
    val fund = box(malformed.value)
    rejected(RentAuctionPlan(transaction(IndexedSeq(fund), IndexedSeq(malformed)), IndexedSeq(fund), height),
      "new deposits must be well-formed and freshly dated")
  }

  property("a funded recreation shaped like a fresh auction is never counted as token supply") {
    val source = box(1000000L, nobody, height - Constants.StoragePeriod, Seq(token -> 100L))
    val rules = new RentAuctionRules(contracts, params, validation)
    var registers = lotRegisters(rules.commitment(Seq(source)), height + WINDOW, height + 1440)
    var recreationSource = box(5000000000L, contracts.auction, height - Constants.StoragePeriod,
      Seq(token -> 100L), registers)
    // Stabilize the value/register encoding used by the wrapped native fee calculation.
    (1 to 4).foreach { _ =>
      val charge = params.storageFeeFactor * recreationSource.bytes.length
      registers = registers.updated(ErgoBox.R8, LongConstant(recreationSource.value - charge - CLOSE_ALLOWANCE))
      recreationSource = box(5000000000L, contracts.auction, height - Constants.StoragePeriod,
        Seq(token -> 100L), registers)
    }
    val charge = params.storageFeeFactor * recreationSource.bytes.length
    charge should be > 0
    val recreated = output(recreationSource.value - charge, contracts.auction, height,
      Seq(token -> 100L), registers)
    val ins = IndexedSeq(source, recreationSource)
    val tx = transaction(ins, IndexedSeq(recreated, output(source.value + charge, owner)),
      Map(0 -> 0.toShort, 1 -> 0.toShort))
    rejected(RentAuctionPlan(tx, ins, height), "recreations cannot count as fresh lots")
  }

  property("refunds and unrelated rent recreations cannot share an output") {
    val b = lot(MINIMUM_BID, recipient = owner)
    val bid = bidSpend(b, MINIMUM_BID + INCREMENT)
    val refund = bid.tx.outputCandidates(1)
    val aged = box(MINIMUM_BID, owner, height - Constants.StoragePeriod,
      registers = refund.additionalRegisters)
    val ins = bid.boxes :+ aged
    val outs = bid.tx.outputCandidates.updated(2,
      change(bid.tx.outputCandidates(2), 2000000L + aged.value, contracts.fee))
    rejected(RentAuctionPlan(transaction(ins, outs, Map(2 -> 1.toShort)), ins, height),
      "rent cannot bypass or share an auction/deposit transaction")
  }

  property("no-bid close authenticates price and all closes pin height and fee ceilings") {
    val opened = collectPlan()
    accepted(opened)
    val closed = new RentAuctionTransactions(contracts, params, height + WINDOW, validation)
      .close(IndexedSeq(opened.transaction.outputs.head), 1000000L).get
    accepted(closed)
    rejected(extension(closed, 0, 2.toByte, IntConstant(10000)),
      "close byte price must equal active minValuePerByte")
    val outs = closed.transaction.outputCandidates
    val old = new ErgoBoxCandidate(outs.head.value, outs.head.ergoTree, height,
      outs.head.additionalTokens, outs.head.additionalRegisters)
    rejected(withOutputs(closed, outs.updated(0, old)), scriptFailure, nativePasses = false)
    val redirected = outs.updated(0, change(outs.head, outs.head.value, anyone))
    rejected(withOutputs(closed, redirected), scriptFailure, nativePasses = false)
    val lots = IndexedSeq(lot(), lot())
    val invalid = lots.zipWithIndex.map { case (b, i) =>
      val single = settleSpend(b, height + WINDOW)
      val in = single.tx.inputs.head
      Input(in.boxId, ProverResult(in.spendingProof.proof, ContextExtension(
        in.spendingProof.extension.values.updated(0.toByte, IntConstant(i))
          .updated(1.toByte, LongConstant(1500000L)))))
    }
    val returns = lots.map { b =>
      val single = settleSpend(b, height + WINDOW).tx.outputCandidates.head
      change(single, single.value - 500000L, single.ergoTree)
    }
    rejected(RentAuctionPlan(ErgoTransaction(invalid, returns :+
      output(3000000L, contracts.fee, height + WINDOW)), lots, height + WINDOW), "invalid close fee output")
  }

  property("opening time and token-entry cap are activated restrictions") {
    val plan = collectPlan()
    val outs = plan.transaction.outputCandidates
    val backdated = new ErgoBoxCandidate(outs.head.value, outs.head.ergoTree, height - 1,
      outs.head.additionalTokens, outs.head.additionalRegisters)
    // Use old funding so native monotonic-height checks do not hide the proposal rejection.
    val oldFunding = box(plan.boxes.last.value, anyone, height - 1)
    val ins = plan.boxes.updated(plan.boxes.size - 1, oldFunding)
    val inputs = plan.transaction.inputs.updated(plan.transaction.inputs.size - 1, Input(oldFunding.id, ProverResult.empty))
    val tx = ErgoTransaction(inputs, outs.updated(0, backdated))
    rejected(RentAuctionPlan(tx, ins, height), "auction state requires canonical registers and parsed party scripts")
    val lots = collectPlan(33)
    accepted(lots)
    lots.transaction.outputs.count(_.ergoTree == contracts.auction) shouldBe 2
    val original = lots.transaction.outputCandidates
    val allTokens = original.take(2).flatMap(_.additionalTokens.toArray)
    val tooMany = output(original.head.value, contracts.auction, height, allTokens, original.head.additionalRegisters)
    val excess = output(original(1).value, owner)
    rejected(withOutputs(lots, original.updated(0, tooMany).updated(1, excess)),
      "auction state requires canonical registers and parsed party scripts")
  }

  property("single high-price merges need a sponsor while two budgets suffice") {
    val p = priced(10000)
    val one = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID)))
    rejected(RentAuctionPlan(one.tx, one.boxes, one.at),
      "Every output of the transaction should contain at least", p, nativePasses = false)
    val two = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID), depositBox(MINIMUM_BID)))
    accepted(RentAuctionPlan(two.tx, two.boxes, two.at), p)
    Seq(2080799, 2080800, 2080801).foreach { at =>
      val spend = mergeSpend(IndexedSeq(depositBox(MINIMUM_BID, at)), at)
      accepted(RentAuctionPlan(spend.tx, spend.boxes, at))
    }
  }

  property("the refund date is enforced even when baseline monotonic height would allow an old refund") {
    val at = height - 1
    val previous = lot(MINIMUM_BID, created = at, end = at + WINDOW, cap = at + 1440, recipient = owner)
    val plan = new RentAuctionTransactions(contracts, params, height, validation)
      .bid(previous, IndexedSeq(box(100000000L, created = at)), MINIMUM_BID + INCREMENT,
        owner, anyone, 1000000L).get
    accepted(plan)
    val outs = plan.transaction.outputCandidates
    val refund = outs(1)
    val old = new ErgoBoxCandidate(refund.value, refund.ergoTree, at,
      refund.additionalTokens, refund.additionalRegisters)
    rejected(withOutputs(plan, outs.updated(1, old)), scriptFailure, nativePasses = false)
  }

  property("malformed protocol imitations retain the normal rent path") {
    Seq(contracts.auction, contracts.deposit, contracts.reserve).foreach { tree =>
      val b = box(1000000L, tree, height - Constants.StoragePeriod)
      val tx = transaction(IndexedSeq(b), IndexedSeq(output(b.value, owner)), Map(0 -> 0.toShort))
      accepted(RentAuctionPlan(tx, IndexedSeq(b), height))
    }
  }
}
