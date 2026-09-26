package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import scorex.crypto.hash.Blake2b256
import sigma.ast.ByteArrayConstant
import sigma.ast.IntArrayConstant
import sigma.ast.LongConstant
import sigma.Colls
import sigma.data.Digest32Coll

class RentAuctionRulesSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.SEED
  import RentAuctionContracts.TOKENS_PER_LOT
  import RentAuctionContracts.WINDOW
  import RentAuctionRules.ATTESTATION_KEY
  import RentAuctionRules.BENEFICIARY_KEY

  private lazy val rules = new RentAuctionRules(contracts, params)
  private lazy val beneficiary = Some(Blake2b256(owner.bytes))

  private def collection(count: Int = 1): Spend = {
    val sources = (1 to count).map { _ =>
      box(1000000L, nobody, height - Constants.StoragePeriod, Seq(token -> 100L))
    }.toIndexedSeq
    val funding = box(count * SEED)
    val lots = sources.map { b =>
      output(SEED, contracts.auction, tokens = b.additionalTokens.toArray.toSeq,
        registers = lotRegisters(b.id, height + WINDOW, height + MAXIMUM_WINDOW))
    }
    val outputs = lots :+ output(sources.map(_.value).sum, owner)
    val inputs = sources :+ funding
    Spend(transaction(inputs, outputs,
      sources.indices.map(i => i -> i.toShort).toMap), inputs, height)
  }

  private def proposed(spend: Spend): Either[String, Unit] =
    rules.validate(spend.tx, spend.boxes, spend.at, beneficiary)

  property("canonical collection is valid today and under the proposed restrictions") {
    val spend = collection()
    spend.result.get should be > 0
    proposed(spend) shouldBe Right(())
    // Recipient differs from the header's delayed miner-reward script.
    spend.tx.outputCandidates.last.ergoTree shouldBe owner
  }

  property("multiple sources create distinct lots even for the same token id") {
    val spend = collection(3)
    spend.result.get should be > 0
    proposed(spend) shouldBe Right(())
    val sourceIds = spend.tx.outputs.take(3).map(_.additionalRegisters(ErgoBox.R4))
    sourceIds.distinct.size shouldBe 3
  }

  property("dense source boxes split deterministically into bounded-size lots") {
    val tokens = (1 to 90).map { i =>
      (Digest32Coll @@ Colls.fromArray(Blake2b256(s"dense-$i"))) -> Long.MaxValue
    }
    val b = box(5000000L, nobody, height - Constants.StoragePeriod, tokens)
    b.bytes.length should be <= 4096
    // At this size, the legacy fee is positive again after its second wrap.
    val chunks = tokens.grouped(TOKENS_PER_LOT).toIndexedSeq
    val funding = box(chunks.size * SEED)
    val outs = chunks.map { chunk =>
      output(SEED, contracts.auction, tokens = chunk,
        registers = lotRegisters(b.id, height + WINDOW, height + MAXIMUM_WINDOW))
    } :+ output(b.value, owner)
    val inputs = IndexedSeq(b, funding)
    val spend = Spend(transaction(inputs, outs, Map(0 -> 0.toShort)), inputs, height)
    spend.result.get should be > 0
    proposed(spend) shouldBe Right(())
    spend.tx.outputs.foreach(_.bytes.length should be <= 4096)
    val swapped = outs.updated(0, outs(1)).updated(1, outs(0))
    proposed(spend.withOutputs(swapped)).isLeft shouldBe true
    info(s"Dense source bytes: ${b.bytes.length}; largest lot bytes: " +
      spend.tx.outputs.take(chunks.size).map(_.bytes.length).max)
  }

  property("legacy token seizure is rejected by the additional rule") {
    val b = box(1000000L, nobody, height - Constants.StoragePeriod, Seq(token -> 100L))
    val tx = transaction(IndexedSeq(b), IndexedSeq(output(b.value, owner,
      tokens = Seq(token -> 100L))), Map(0 -> 0.toShort))
    validate(tx, IndexedSeq(b)).get should be > 0
    rules.validate(tx, IndexedSeq(b), height, beneficiary).isLeft shouldBe true
  }

  property("initial lots cannot be pre-bid, backdated or assigned a different deadline") {
    val spend = collection()
    val initial = spend.tx.outputCandidates.head
    val mutations = Seq(
      initial.additionalRegisters.updated(ErgoBox.R6, LongConstant(MINIMUM_BID)),
      initial.additionalRegisters.updated(ErgoBox.R7, ByteArrayConstant(owner.bytes)),
      initial.additionalRegisters.updated(ErgoBox.R5,
        IntArrayConstant(Array(height, height + MAXIMUM_WINDOW))),
      initial.additionalRegisters.updated(ErgoBox.R4,
        ByteArrayConstant(Array.fill(32)(1.toByte)))
    )
    mutations.foreach { registers =>
      val changed = new ErgoBoxCandidate(initial.value, initial.ergoTree,
        initial.creationHeight, initial.additionalTokens, registers)
      proposed(spend.withOutputs(spend.tx.outputCandidates.updated(0, changed)))
        .isLeft shouldBe true
    }
    val backdated = new ErgoBoxCandidate(initial.value, initial.ergoTree,
      height - 1, initial.additionalTokens, initial.additionalRegisters)
    proposed(spend.withOutputs(spend.tx.outputCandidates.updated(0, backdated)))
      .isLeft shouldBe true
  }

  property("missing tokens cannot be hidden in a collector's extra output") {
    val spend = collection()
    val outs = spend.tx.outputCandidates
    val changed = output(SEED, contracts.auction, tokens = Seq(token -> 99L),
      registers = outs.head.additionalRegisters)
    val stolen = output(outs.last.value, owner, tokens = Seq(token -> 1L))
    val attack = spend.withOutputs(IndexedSeq(changed, stolen))
    attack.result.get should be > 0
    proposed(attack).isLeft shouldBe true
  }

  property("ordinary ERG rent must reach the producer's chosen beneficiary") {
    val spend = collection()
    val outs = spend.tx.outputCandidates
    val redirected = change(outs(1), outs(1).value, anyone)
    val attack = spend.withOutputs(outs.updated(1, redirected))
    attack.result.get should be > 0
    proposed(attack) shouldBe
      Left("rent ERG must pay the designated beneficiary separately")
    rules.validate(spend.tx, spend.boxes, height, None).isLeft shouldBe true
  }

  property("auction seed cannot also count as ordinary rent payment") {
    val spend = collection()
    rules.validate(spend.tx, spend.boxes, height,
      Some(Blake2b256(contracts.auction.bytes))).isLeft shouldBe true
  }

  property("auction refunds cannot also discharge an unrelated rent recreation") {
    val current = lot(MINIMUM_BID, recipient = owner)
    val bid = bidSpend(current, MINIMUM_BID + INCREMENT)
    val refund = bid.tx.outputCandidates(1)
    val expired = box(MINIMUM_BID, owner, height - Constants.StoragePeriod,
      registers = refund.additionalRegisters)
    val all = bid.boxes :+ expired
    val outs = bid.tx.outputCandidates.updated(2,
      change(bid.tx.outputCandidates(2), 2000000L + expired.value, contracts.fee))
    val tx = transaction(all, outs, Map(2 -> 1.toShort))
    validate(tx, all).get should be > 0
    rules.validate(tx, all, height, beneficiary) shouldBe
      Left("rent cannot bypass or share an auction/deposit transaction")
  }

  property("aged auctions and deposits cannot escape through the rent shortcut") {
    val aged = height - Constants.StoragePeriod
    val auction = lot(end = aged + WINDOW, cap = aged + MAXIMUM_WINDOW, created = aged)
    val deposit = depositBox(MINIMUM_BID, aged)
    val reserve = box(1000000L, contracts.reserve, aged, Seq(nft -> 1L))
    Seq(auction, deposit, reserve).foreach { b =>
      val tx = transaction(IndexedSeq(b), IndexedSeq(output(b.value, anyone,
        tokens = b.additionalTokens.toArray.toSeq)), Map(0 -> 0.toShort))
      validate(tx, IndexedSeq(b)).get should be > 0
      rules.validate(tx, IndexedSeq(b), height, beneficiary).isLeft shouldBe true
    }
    burnSpend(lot(end = aged + WINDOW, cap = aged + MAXIMUM_WINDOW, created = aged),
      height).result.get should be > 0
  }

  property("auction creation outside a rent claim is rejected") {
    val spend = collection()
    val unrestricted = spend.boxes.map(b => box(b.value, anyone, height,
      b.additionalTokens.toArray.toSeq))
    val tx = transaction(unrestricted, spend.tx.outputCandidates)
    validate(tx, unrestricted).get should be > 0
    rules.validate(tx, unrestricted, height, None).isLeft shouldBe true
  }

  property("malformed contract boxes do not acquire permanent rent immunity") {
    val aged = height - Constants.StoragePeriod
    val malformed = Seq(
      box(1000000L, contracts.auction, aged),
      box(1000000L, contracts.deposit, aged),
      box(1000000L, contracts.reserve, aged)
    )
    malformed.foreach { b =>
      val tx = transaction(IndexedSeq(b), IndexedSeq(output(b.value, owner)),
        Map(0 -> 0.toShort))
      validate(tx, IndexedSeq(b)).get should be > 0
      rules.validate(tx, IndexedSeq(b), height, beneficiary) shouldBe Right(())
    }
  }

  property("ordinary payments cannot create malformed or backdated deposits") {
    val funding = box(MINIMUM_BID + MERGE_BUDGET, created = height - 1)
    val invalid = Seq(
      output(funding.value, contracts.deposit),
      output(funding.value, contracts.deposit, height - 1,
        registers = tag(funding.id))
    )
    invalid.foreach { deposit =>
      val tx = transaction(IndexedSeq(funding), IndexedSeq(deposit))
      validate(tx, IndexedSeq(funding)).get should be > 0
      rules.validate(tx, IndexedSeq(funding), height, None) shouldBe
        Left("new deposits must be well-formed and freshly dated")
    }
  }

  property("a valid bid cannot create an extra malformed auction output") {
    val bid = bidSpend(lot(), MINIMUM_BID)
    val sponsor = box(SEED)
    val inputs = bid.boxes :+ sponsor
    val outs = bid.tx.outputCandidates :+ output(sponsor.value, contracts.auction)
    val tx = transaction(inputs, outs)
    validate(tx, inputs).get should be > 0
    rules.validate(tx, inputs, height, None) shouldBe
      Left("auction creation requires a rent source")
  }

  property("recipient bytes cannot strand a lot through an invalid future payout") {
    val bid = bidSpend(lot(), MINIMUM_BID)
    val next = bid.tx.outputCandidates.head
    val invalid = Seq(Array(0.toByte), owner.bytes ++ Array(0.toByte))
    invalid.foreach { bytes =>
      val changed = new ErgoBoxCandidate(next.value, next.ergoTree, height,
        next.additionalTokens,
        next.additionalRegisters.updated(ErgoBox.R7, ByteArrayConstant(bytes)))
      val attack = bid.withOutputs(bid.tx.outputCandidates.updated(0, changed))
      attack.result.get should be > 0
      rules.validate(attack.tx, attack.boxes, height, None) shouldBe
        Left("auction state requires a parsed canonical recipient script")
    }
    rules.validate(bid.tx, bid.boxes, height, None) shouldBe Right(())
  }

  property("rent commitment works at arbitrary transaction positions") {
    val claim = collection()
    val fund = box(1000000L)
    val plain = transaction(IndexedSeq(fund), IndexedSeq(output(fund.value)))
    val txs = Seq(plain -> IndexedSeq(fund), claim.tx -> claim.boxes)
    val extension = ExtensionCandidate(Seq(
      ATTESTATION_KEY -> rules.attestation(txs, height).get,
      BENEFICIARY_KEY -> beneficiary.get
    ))
    rules.validateExtension(txs, height, extension).isRight shouldBe true
    rules.validateExtension(txs, height, ExtensionCandidate(Seq.empty))
      .isLeft shouldBe true
    val changed = ExtensionCandidate(extension.fields.updated(1,
      BENEFICIARY_KEY -> Blake2b256(anyone.bytes)))
    changed.digest.sameElements(extension.digest) shouldBe false
  }

  property("wrong, missing, duplicate and reordered claim commitments fail") {
    val a = collection()
    val b = collection()
    val txs = Seq(a.tx -> a.boxes, b.tx -> b.boxes)
    val hash = rules.attestation(txs, height).get
    val valid = Seq(ATTESTATION_KEY -> hash, BENEFICIARY_KEY -> beneficiary.get)
    val cases = Seq(
      valid.take(1), valid.drop(1), valid :+ valid.head,
      valid.updated(0, ATTESTATION_KEY -> hash.drop(1)),
      valid.updated(0, ATTESTATION_KEY -> rules.attestation(txs.reverse, height).get)
    )
    cases.foreach { fields =>
      rules.validateExtension(txs, height, ExtensionCandidate(fields))
        .isLeft shouldBe true
    }
    rules.validateExtension(Seq.empty, height, ExtensionCandidate(valid))
      .isLeft shouldBe true
    rules.validateExtension(Seq.empty, height, ExtensionCandidate(Seq.empty)) shouldBe
      Right(None)
  }
}
