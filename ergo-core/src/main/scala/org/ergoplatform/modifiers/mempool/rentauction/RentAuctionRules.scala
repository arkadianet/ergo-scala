package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.ErgoValidationSettings
import org.ergoplatform.settings.Parameters
import org.ergoplatform.settings.ValidationRules
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ByteArrayConstant
import sigma.ast.ErgoTree
import sigma.ast.IntArrayConstant
import sigma.ast.LongConstant
import sigma.ast.SByte
import sigma.ast.SCollection
import sigma.ast.SInt
import sigma.ast.SLong
import sigma.ast.SShort
import sigma.Coll
import sigma.data.Digest32Coll
import sigma.serialization.ErgoTreeSerializer

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Additional restrictions; baseline transaction validation is always required. */
final class RentAuctionRules(
  contracts: RentAuctionContracts,
  params: Parameters,
  validationSettings: ErgoValidationSettings
) {
  import RentAuctionContracts.CLOSE_ALLOWANCE
  import RentAuctionContracts.COLLECTOR_SHARE_DENOMINATOR
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MAX_BYTE_PRICE
  import RentAuctionContracts.MAX_CLOSE_FEE
  import RentAuctionContracts.MAX_CLOSE_LOTS
  import RentAuctionContracts.MAX_DEPOSIT_BYTES
  import RentAuctionContracts.MAX_PARTY_BYTES
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.MIN_DEPOSIT_VALUE
  import RentAuctionContracts.PAYOUT_OVERHEAD_BYTES
  import RentAuctionContracts.TOKENS_PER_LOT
  import RentAuctionContracts.TOKEN_ENTRY_BYTES
  import RentAuctionContracts.WINDOW
  import RentAuctionRules.ATTESTATION_KEY
  import RentAuctionRules.BENEFICIARY_KEY
  import RentAuctionRules.Rent
  import RentAuctionRules.toEither

  /** Native EIP-27 debt redemption (validation rule 123) applies to ordinary inputs. */
  def reemissionRedemptionActive(h: Int): Boolean = {
    val r = contracts.chain.reemission
    r.checkReemissionRules && h > r.activationHeight &&
      validationSettings.isActive(ValidationRules.txReemission)
  }

  def redemptionTriggered(inputs: Seq[ErgoBox], h: Int): Boolean =
    reemissionRedemptionActive(h) && inputs.exists { b =>
      // ErgoTransaction.verifyReemissionSpending routes inputs above 100,000 ERG
      // through the emission-box branch; they never trigger ordinary redemption.
      b.value <= 100000L * EmissionRules.CoinsInOneErgo &&
        b.tokens.contains(contracts.chain.reemission.reemissionTokenId)
    }

  def isRedemptionPayment(out: ErgoBoxCandidate): Boolean =
    if (contracts.chain.isMainnet) out.ergoTree == contracts.legacyDeposit
    else out.ergoTree.toProposition(true) == contracts.legacyDeposit.toProposition(true)

  def auctionTokens(b: ErgoBox, h: Int): Seq[(Digest32Coll, Long)] = {
    b.additionalTokens.toArray.toSeq.filterNot { case (id, _) =>
      reemissionRedemptionActive(h) &&
        id == contracts.chain.reemission.reemissionTokenIdBytes
    }
  }

  /** Provisional resource charge; benchmark before activation. */
  def cost(tx: ErgoTransaction, inputs: IndexedSeq[ErgoBox]): Long = {
    val all = inputs ++ tx.outputs
    val bytes = all.foldLeft(0L)((n, b) => n + b.bytes.length)
    val tokens = all.foldLeft(0L)((n, b) => n + b.additionalTokens.length)
    val auctions = all.count(_.ergoTree == contracts.auction).toLong
    val deposits = all.count(_.ergoTree == contracts.deposit).toLong
    100L + 4L * bytes + 200L * tokens + 4000L * auctions +
      2000L * deposits + 50L * tx.inputs.size + 100L * tx.outputs.size
  }

  def classify(b: ErgoBox, in: Input, outputs: Int, h: Int): Option[Rent] = {
    val proof = in.spendingProof
    if (h - b.creationHeight < Constants.StoragePeriod || proof.proof.nonEmpty) {
      None
    } else {
      proof.extension.values.get(Constants.StorageIndexVarId).flatMap { variable =>
        if (variable.tpe != SShort) None
        else Try(variable.value.asInstanceOf[Short].toInt).toOption.flatMap { i =>
          if (i < 0 || i >= outputs) None
          else {
            // Int arithmetic intentionally matches the current Scala node.
            val fee = params.storageFeeFactor * b.bytes.length
            Some(Rent(i, b.value - fee <= 0L))
          }
        }
      }
    }
  }

  def claims(tx: ErgoTransaction, inputs: IndexedSeq[ErgoBox], h: Int):
    IndexedSeq[(ErgoBox, Rent)] = inputs.zip(tx.inputs).flatMap { case (b, in) =>
    classify(b, in, tx.outputCandidates.size, h).map(b -> _)
  }


  private def bytes(b: ErgoBoxCandidate, r: ErgoBox.NonMandatoryRegisterId): Coll[Byte] =
    b.additionalRegisters(r).value.asInstanceOf[Coll[Byte]]

  private def long(b: ErgoBoxCandidate, r: ErgoBox.NonMandatoryRegisterId): Long =
    b.additionalRegisters(r).value.asInstanceOf[Long]

  private def tagged(b: ErgoBoxCandidate): Boolean =
    b.additionalRegisters.get(ErgoBox.R4).exists { r =>
      r.tpe == SCollection(SByte) && r.value.asInstanceOf[Coll[Byte]].length == 32
    }

  def validRecipient(value: Coll[Byte]): Boolean = Try {
    if (value.isEmpty || value.length > MAX_PARTY_BYTES) false else {
      val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(value.toArray)
      tree.root.isRight && new ErgoTree(tree.header, tree.constants, tree.root)
        .bytes.sameElements(value.toArray)
    }
  }.getOrElse(false)

  def returnBytes(scriptLength: Int): Int = PAYOUT_OVERHEAD_BYTES + scriptLength

  def winnerBytes(scriptLength: Int, tokenCount: Int): Int =
    returnBytes(scriptLength) + TOKEN_ENTRY_BYTES * tokenCount

  def carrier(scriptLength: Int, tokenCount: Int): Long =
    math.max(1L, params.minValuePerByte.toLong * winnerBytes(scriptLength, tokenCount))

  /** Full-box upper bound, including maximum-width transaction output reference. */
  def lotSizeBound(b: ErgoBoxCandidate, successor: Boolean): Int = {
    val registers = b.additionalRegisters ++ Map(
      ErgoBox.R5 -> IntArrayConstant(Array(Int.MaxValue, Int.MaxValue)),
      ErgoBox.R6 -> LongConstant(if (successor) Long.MaxValue else 0L),
      ErgoBox.R7 -> ByteArrayConstant(if (successor) new Array[Byte](MAX_PARTY_BYTES)
        else Array.emptyByteArray),
      ErgoBox.R8 -> LongConstant(Long.MaxValue))
    new ErgoBoxCandidate(Long.MaxValue, b.ergoTree, Int.MaxValue,
      b.additionalTokens, registers)
      .toBox(ErgoBox.allZerosModifierId, Short.MaxValue).bytes.length
  }

  def openingSeed(b: ErgoBoxCandidate): Long = math.max(
    MAX_BYTE_PRICE.toLong * returnBytes(bytes(b, ErgoBox.R9).length),
    math.max(1L, params.minValuePerByte.toLong * lotSizeBound(b, successor = false) - CLOSE_ALLOWANCE))

  def depositShape(b: ErgoBoxCandidate): Boolean =
    b.additionalRegisters.size == 1 && tagged(b) && b.additionalTokens.isEmpty &&
      b.value >= MIN_DEPOSIT_VALUE

  /** Checks auction state shape; callers must check the auction tree separately. */
  def auctionShape(b: ErgoBoxCandidate): Boolean = Try {
    val rs = b.additionalRegisters
    val types = rs.size == 6 && tagged(b) &&
      rs(ErgoBox.R5).tpe == SCollection(SInt) && rs(ErgoBox.R6).tpe == SLong &&
      rs(ErgoBox.R7).tpe == SCollection(SByte) && rs(ErgoBox.R8).tpe == SLong &&
      rs(ErgoBox.R9).tpe == SCollection(SByte)
    if (!types) false else {
      val limits = rs(ErgoBox.R5).value.asInstanceOf[Coll[Int]]
      val bid = long(b, ErgoBox.R6)
      val seed = long(b, ErgoBox.R8)
      val recipient = bytes(b, ErgoBox.R7)
      val collector = bytes(b, ErgoBox.R9)
      validRecipient(collector) &&
        seed >= MAX_BYTE_PRICE.toLong * returnBytes(collector.length) &&
        BigInt(seed) + CLOSE_ALLOWANCE + bid == BigInt(b.value) && bid >= 0L &&
        b.additionalTokens.nonEmpty && b.additionalTokens.length <= TOKENS_PER_LOT &&
        b.additionalTokens.toArray.map(_._1).distinct.length == b.additionalTokens.length &&
        b.additionalTokens.forall(_._2 > 0L) &&
        limits.length == 2 && limits(0) <= limits(1) && limits(0) >= b.creationHeight &&
        limits(1).toLong - b.creationHeight.toLong <= MAXIMUM_WINDOW &&
        ((bid == 0L && recipient.isEmpty) || (bid >= MINIMUM_BID && validRecipient(recipient)))
    }
  }.getOrElse(false)

  def commitment(sources: Seq[ErgoBox]): Array[Byte] =
    Blake2b256(sources.flatMap(_.id).toArray)

  private def totals(boxes: Seq[ErgoBoxCandidate]): Map[Digest32Coll, BigInt] = {
    val result = scala.collection.mutable.Map.empty[Digest32Coll, BigInt]
    boxes.foreach(_.additionalTokens.toArray.foreach { case (id, amount) =>
      result.update(id, result.getOrElse(id, BigInt(0)) + amount)
    })
    result.toMap
  }

  def validate(
    tx: ErgoTransaction,
    inputs: IndexedSeq[ErgoBox],
    h: Int,
    beneficiaryHash: Option[Array[Byte]]
  ): Either[String, Unit] = toEither(Try {
    def check(ok: Boolean, message: String): Unit = require(ok, message)
    check(inputs.size == tx.inputs.size && inputs.zip(tx.inputs).forall {
      case (b, in) => b.id.sameElements(in.boxId)
    }, "inputs must be resolved from the transaction's box ids")
    val rents = claims(tx, inputs, h)
    val protectedInput = inputs.exists { b =>
      (b.ergoTree == contracts.auction && auctionShape(b)) ||
      (b.ergoTree == contracts.deposit && depositShape(b)) ||
      (b.ergoTree == contracts.reserve &&
        b.tokens.get(contracts.chain.reemission.reemissionNftId).contains(1L))
    }
    check(!protectedInput || rents.isEmpty,
      "rent cannot bypass or share an auction/deposit transaction")
    val outputs = tx.outputs
    val auctionOutputs = outputs.indices.filter(i => outputs(i).ergoTree == contracts.auction).toSet
    val auctionInputs = inputs.indices.filter(i => inputs(i).ergoTree == contracts.auction)
    check(auctionOutputs.forall(i => auctionShape(outputs(i))),
      "auction state requires canonical registers and parsed party scripts")
    check(auctionOutputs.forall(i => lotSizeBound(outputs(i), successor = true) <= ErgoBox.MaxBoxSize),
      "auction successor exceeds maximum box size")
    check(outputs.filter(_.ergoTree == contracts.deposit).forall { b =>
      depositShape(b) && b.creationHeight == h && b.bytes.length <= MAX_DEPOSIT_BYTES
    }, "new deposits must be well-formed and freshly dated")

    if (rents.nonEmpty) {
      val recreations = rents.collect { case (_, r) if !r.fullyConsumed => r.outputIndex }
      check(recreations.distinct.size == recreations.size,
        "rent obligations must have distinct output indices")
      val recreationSet = recreations.toSet
      check(auctionOutputs.intersect(recreationSet).isEmpty,
        "recreations cannot count as fresh lots")
      val sources = rents.collect { case (b, r) if r.fullyConsumed && auctionTokens(b, h).nonEmpty => b }
      check(sources.isEmpty || h <= Int.MaxValue - MAXIMUM_WINDOW,
        "auction deadline would overflow")
      val origin = commitment(sources)
      check(auctionOutputs.forall { i =>
        val b = outputs(i)
        b.creationHeight == h && bytes(b, ErgoBox.R4).toArray.sameElements(origin) &&
          b.additionalRegisters(ErgoBox.R5) == IntArrayConstant(Array(h + WINDOW, h + MAXIMUM_WINDOW)) &&
          long(b, ErgoBox.R6) == 0L && bytes(b, ErgoBox.R7).isEmpty &&
          long(b, ErgoBox.R8) >= openingSeed(b)
      }, "fully consumed tokens must enter fresh canonical lots")
      val required = scala.collection.mutable.Map.empty[Digest32Coll, BigInt]
      sources.foreach(b => auctionTokens(b, h).foreach { case (id, amount) =>
        required.update(id, required.getOrElse(id, BigInt(0)) + amount)
      })
      check(totals(auctionOutputs.toSeq.map(outputs)) == required.toMap,
        "fresh lot token totals must equal fully consumed token totals")
      check(auctionOutputs.isEmpty || sources.nonEmpty, "auction creation requires a rent source")
      val rentIds = rents.map(b => Base16.encode(b._1.id)).toSet
      check(inputs.filterNot(b => rentIds(Base16.encode(b.id))).forall(_.additionalTokens.isEmpty),
        "collection funding must be token-free")
      val excluded = recreationSet ++ auctionOutputs
      check(outputs.indices.filterNot(excluded).forall(i => outputs(i).additionalTokens.isEmpty),
        "collection tokens may only enter recreations or fresh lots")
      val credited = rents.map { case (b, r) =>
        if (r.fullyConsumed) BigInt(b.value)
        else (BigInt(b.value) - outputs(r.outputIndex).value).max(0)
      }.sum
      val paid = outputs.indices.filterNot(excluded).filter { i =>
        outputs(i).creationHeight == h &&
          beneficiaryHash.exists(hash => Blake2b256(outputs(i).ergoTree.bytes).sameElements(hash))
      }.map(i => BigInt(outputs(i).value)).sum
      check(beneficiaryHash.exists(_.length == 32), "rent requires a producer-designated beneficiary")
      check(paid >= credited, "rent ERG must pay the designated beneficiary separately")
      if (redemptionTriggered(inputs, h)) {
        check(outputs.filter(isRedemptionPayment).forall(_.creationHeight == h),
          "EIP-27 redemption payments must be freshly dated")
      }
    } else if (auctionInputs.nonEmpty) {
      check(auctionInputs.forall(i => auctionShape(inputs(i))), "malformed auction input")
      val closing = auctionInputs.forall { i =>
        h >= inputs(i).additionalRegisters(ErgoBox.R5).value.asInstanceOf[Coll[Int]](0)
      }
      if (closing) validateClose(tx, inputs, h).fold(e => throw new IllegalArgumentException(e), identity)
      else check(auctionInputs == Seq(0) && auctionOutputs == Set(0),
        "bidding requires one auction input and one successor at index zero")
    } else check(auctionOutputs.isEmpty, "auction creation requires a rent source")
  })

  private def validateClose(tx: ErgoTransaction, inputs: IndexedSeq[ErgoBox], h: Int): Either[String, Unit] = toEither(Try {
    require(inputs.nonEmpty && inputs.size <= MAX_CLOSE_LOTS &&
      inputs.forall(_.ergoTree == contracts.auction), "close requires 1 to 32 lots and no funding inputs")
    val outputs = tx.outputs
    val owned = scala.collection.mutable.Set.empty[Int]
    val slots = inputs.zipWithIndex.map { case (b, i) =>
      val ext = tx.inputs(i).spendingProof.extension.values
      require(ext.get(0.toByte).exists(_.tpe == SInt) &&
        ext.get(1.toByte).exists(_.tpe == SLong) && ext.get(2.toByte).exists(_.tpe == SInt),
        "close requires Int index, Long fee allocation and Int byte price")
      val k = ext(0.toByte).value.asInstanceOf[Int]
      val f = ext(1.toByte).value.asInstanceOf[Long]
      require(ext(2.toByte).value.asInstanceOf[Int] == params.minValuePerByte,
        "close byte price must equal active minValuePerByte")
      val count = if (long(b, ErgoBox.R6) == 0L) 1 else 3
      require(k >= 0 && k.toLong + count <= outputs.size, "close payout range is out of bounds")
      (k until k + count).foreach { j =>
        require(owned.add(j), "close payout ranges must be disjoint")
      }
      (k, f, count)
    }
    val remaining = outputs.indices.filterNot(owned)
    require(remaining.size == 1, "close requires exactly one unclaimed fee output")
    val fee = outputs(remaining.head)
    require(fee.ergoTree == contracts.fee && fee.additionalTokens.isEmpty &&
      fee.additionalRegisters.isEmpty && fee.creationHeight == h &&
      fee.value > 0L && fee.value <= MAX_CLOSE_FEE, "invalid close fee output")
    inputs.zip(slots).zipWithIndex.foreach { case ((b, (k, f, count)), i) =>
      val expected = fee.value / inputs.size + (if (i < fee.value % inputs.size) 1L else 0L)
      require(f == expected, "incorrect deterministic close fee allocation")
      val bid = long(b, ErgoBox.R6)
      val collector = bytes(b, ErgoBox.R9)
      def taggedPayout(j: Int): ErgoBox = {
        val out = outputs(j)
        require(out.additionalRegisters == Map(ErgoBox.R4 -> ByteArrayConstant(b.id)) &&
          out.creationHeight == h, "close payouts require exact tags and current height")
        out
      }
      val returned = taggedPayout(k)
      require(returned.additionalTokens.isEmpty && returned.ergoTree.bytes.sameElements(collector.toArray) &&
        BigInt(returned.value) == BigInt(long(b, ErgoBox.R8)) + CLOSE_ALLOWANCE - f +
          bid / COLLECTOR_SHARE_DENOMINATOR && returned.bytes.length <= returnBytes(collector.length),
        "incorrect collector return")
      if (count == 3) {
        val recipient = bytes(b, ErgoBox.R7)
        val winner = taggedPayout(k + 1)
        val deposit = taggedPayout(k + 2)
        val c = carrier(recipient.length, b.additionalTokens.length)
        require(winner.ergoTree.bytes.sameElements(recipient.toArray) &&
          winner.additionalTokens == b.additionalTokens && winner.value == c &&
          winner.bytes.length <= winnerBytes(recipient.length, b.additionalTokens.length),
          "incorrect winner payout")
        require(deposit.ergoTree == contracts.deposit && deposit.additionalTokens.isEmpty &&
          deposit.value == bid - bid / COLLECTOR_SHARE_DENOMINATOR - c,
          "incorrect proceeds deposit")
      }
    }
    require(outputs.forall(_.ergoTree != contracts.auction), "close cannot recreate auctions")
  })

  def attestation(
    transactions: Seq[(ErgoTransaction, IndexedSeq[ErgoBox])],
    h: Int
  ): Option[Array[Byte]] = {
    val ids = transactions.collect {
      case (tx, boxes) if claims(tx, boxes, h).nonEmpty =>
        Base16.decode(tx.id).get
    }
    if (ids.isEmpty) None else Some(Blake2b256(ids.flatten.toArray))
  }

  def extension(
    transactions: Seq[(ErgoTransaction, IndexedSeq[ErgoBox])],
    h: Int,
    beneficiaryHash: Array[Byte]
  ): ExtensionCandidate = ExtensionCandidate(attestation(transactions, h).toSeq.flatMap {
    hash => Seq(ATTESTATION_KEY -> hash, BENEFICIARY_KEY -> beneficiaryHash)
  })

  /** The caller must first verify the extension's commitment in a valid header. */
  def validateExtension(
    transactions: Seq[(ErgoTransaction, IndexedSeq[ErgoBox])],
    h: Int,
    extension: ExtensionCandidate
  ): Either[String, Option[Array[Byte]]] = {
    val expected = attestation(transactions, h)
    def values(key: Array[Byte]): Seq[Array[Byte]] =
      extension.fields.collect { case (k, v) if k.sameElements(key) => v }
    val attestations = values(ATTESTATION_KEY)
    val beneficiaries = values(BENEFICIARY_KEY)
    expected match {
      case None if attestations.isEmpty && beneficiaries.isEmpty => Right(None)
      case Some(hash) if attestations.size == 1 && beneficiaries.size == 1 &&
        attestations.head.sameElements(hash) && beneficiaries.head.length == 32 =>
        Right(Some(beneficiaries.head))
      case _ => Left("missing, duplicated or incorrect rent extension fields")
    }
  }
}

object RentAuctionRules {
  private[rentauction] def toEither(t: Try[Unit]): Either[String, Unit] = t match {
    case Success(_) => Right(())
    case Failure(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      .stripPrefix("requirement failed: "))
  }

  final case class Rent(outputIndex: Int, fullyConsumed: Boolean)

  // Experimental keys only; assignment and activation need a reviewed node EIP.
  val ATTESTATION_KEY: Array[Byte] = Array(3.toByte, 0.toByte)
  val BENEFICIARY_KEY: Array[Byte] = Array(3.toByte, 1.toByte)
}
