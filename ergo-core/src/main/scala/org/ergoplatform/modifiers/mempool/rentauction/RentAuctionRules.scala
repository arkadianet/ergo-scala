package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.Parameters
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

import scala.util.Try

/**
  * Additional consensus checks, enabled only by explicit chain activation.
  * Ordinary node transaction validation is also required.
  */
final class RentAuctionRules(contracts: RentAuctionContracts, params: Parameters) {
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.SEED
  import RentAuctionContracts.TOKENS_PER_LOT
  import RentAuctionContracts.WINDOW
  import RentAuctionRules.ATTESTATION_KEY
  import RentAuctionRules.BENEFICIARY_KEY
  import RentAuctionRules.Rent

  /** EIP-27 accounting tokens must be burned, never auctioned or transferred. */
  def auctionTokens(b: ErgoBox, h: Int): Seq[(Digest32Coll, Long)] = {
    val reemission = contracts.chain.reemission
    val debt = reemission.reemissionTokenIdBytes
    b.additionalTokens.toArray.toSeq.filterNot { case (id, _) =>
      reemission.checkReemissionRules && h > reemission.activationHeight && id == debt
    }
  }

  /** Deterministic additional cost, charged before evaluating the new rules. */
  def cost(tx: ErgoTransaction, inputs: IndexedSeq[ErgoBox]): Long = {
    val inputBytes = inputs.foldLeft(0L)((n, b) => n + b.bytes.length)
    val outputBytes = tx.outputs.foldLeft(0L)((n, b) => n + b.bytes.length)
    val tokens = inputs.foldLeft(0L)((n, b) => n + b.additionalTokens.length) +
      tx.outputCandidates.foldLeft(0L)((n, b) => n + b.additionalTokens.length)
    val auctionStates = inputs.count(_.ergoTree == contracts.auction) +
      tx.outputCandidates.count(_.ergoTree == contracts.auction)
    100L + 2L * (inputBytes + outputBytes) + 100L * tokens +
      2000L * auctionStates + 50L * tx.inputs.size
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

  private def tagged(out: ErgoBoxCandidate): Boolean =
    out.additionalRegisters.get(ErgoBox.R4).exists { r =>
      r.tpe == SCollection(SByte) && r.value.asInstanceOf[Coll[Byte]].length == 32
    }

  private def depositShape(out: ErgoBoxCandidate): Boolean =
    tagged(out) && out.additionalTokens.isEmpty && out.value >= MINIMUM_BID + MERGE_BUDGET

  private def validRecipient(bytes: Coll[Byte]): Boolean = Try {
    if (bytes.isEmpty || bytes.length > 256) false else {
      val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(bytes.toArray)
      tree.root.isRight && new ErgoTree(tree.header, tree.constants, tree.root)
        .bytes.sameElements(bytes.toArray)
    }
  }.getOrElse(false)

  private def auctionShape(b: ErgoBoxCandidate): Boolean = Try {
    val registers = b.additionalRegisters
    val types = tagged(b) && registers(ErgoBox.R5).tpe == SCollection(SInt) &&
      registers(ErgoBox.R6).tpe == SLong &&
      registers(ErgoBox.R7).tpe == SCollection(SByte) &&
      registers(ErgoBox.R8).tpe == SLong
    if (!types) false else {
      val limits = registers(ErgoBox.R5).value.asInstanceOf[Coll[Int]]
      val bid = registers(ErgoBox.R6).value.asInstanceOf[Long]
      val recipient = registers(ErgoBox.R7).value.asInstanceOf[Coll[Byte]]
      val seed = registers(ErgoBox.R8).value.asInstanceOf[Long]
      seed >= SEED && b.value - seed == bid && bid >= 0 &&
        b.additionalTokens.nonEmpty && b.additionalTokens.length <= TOKENS_PER_LOT &&
        limits.length == 2 && limits(0) <= limits(1) &&
        limits(0) >= b.creationHeight &&
        limits(1).toLong - b.creationHeight.toLong <= MAXIMUM_WINDOW &&
        ((bid == 0 && recipient.isEmpty) ||
          (bid >= MINIMUM_BID && validRecipient(recipient)))
    }
  }.getOrElse(false)

  private def canonicalLot(
    b: ErgoBox,
    out: ErgoBoxCandidate,
    chunk: Int,
    h: Int
  ): Boolean = {
    val expected = Map(
      ErgoBox.R4 -> ByteArrayConstant(b.id),
      ErgoBox.R5 -> IntArrayConstant(Array(h + WINDOW, h + MAXIMUM_WINDOW)),
      ErgoBox.R6 -> LongConstant(0L),
      ErgoBox.R7 -> ByteArrayConstant(Array.emptyByteArray),
      ErgoBox.R8 -> LongConstant(out.value)
    )
    out.ergoTree == contracts.auction && out.value >= SEED &&
    out.creationHeight == h && out.additionalTokens.toArray.toSeq ==
      auctionTokens(b, h).slice(
        chunk * TOKENS_PER_LOT, (chunk + 1) * TOKENS_PER_LOT) &&
    out.additionalRegisters == expected
  }

  def validate(
    tx: ErgoTransaction,
    inputs: IndexedSeq[ErgoBox],
    h: Int,
    beneficiaryHash: Option[Array[Byte]]
  ): Either[String, Unit] = {
    if (inputs.size != tx.inputs.size || !inputs.zip(tx.inputs).forall {
      case (b, in) => b.id.sameElements(in.boxId)
    }) return Left("inputs must be resolved from the transaction's box ids")
    val rents = claims(tx, inputs, h)
    val protectedInput = inputs.exists { b =>
      (b.ergoTree == contracts.auction && auctionShape(b)) ||
      (b.ergoTree == contracts.deposit && depositShape(b)) ||
      (b.ergoTree == contracts.reserve &&
        b.tokens.get(contracts.chain.reemission.reemissionNftId).contains(1L))
    }
    val obligations = rents.filter { case (b, r) =>
      !r.fullyConsumed || auctionTokens(b, h).nonEmpty
    }
    val indices = obligations.flatMap { case (b, r) =>
      val count = if (!r.fullyConsumed) 1
      else (auctionTokens(b, h).length + TOKENS_PER_LOT - 1) / TOKENS_PER_LOT
      (0 until count).map(r.outputIndex + _)
    }
    val tokenClaims = rents.filter { case (b, r) =>
      r.fullyConsumed && auctionTokens(b, h).nonEmpty
    }
    val lotMappings = tokenClaims.flatMap { case (b, r) =>
      val count = (auctionTokens(b, h).length + TOKENS_PER_LOT - 1) / TOKENS_PER_LOT
      (0 until count).map(chunk => (b, chunk, r.outputIndex + chunk))
    }
    val lotIndices = lotMappings.map(_._3).toSet
    val auctionOutputs = tx.outputCandidates.zipWithIndex.collect {
      case (out, i) if out.ergoTree == contracts.auction => i
    }.toSet
    val auctionInputs = inputs.count(_.ergoTree == contracts.auction)
    val obligationIndices = indices.toSet
    val credited = rents.map { case (b, r) =>
      if (r.fullyConsumed) BigInt(b.value)
      else (BigInt(b.value) - tx.outputCandidates(r.outputIndex).value).max(0)
    }.sum
    val paid = tx.outputCandidates.zipWithIndex.collect {
      case (out, i) if !obligationIndices.contains(i) && beneficiaryHash.exists { hash =>
        Blake2b256(out.ergoTree.bytes).sameElements(hash)
      } => BigInt(out.value)
    }.sum
    val checks = Seq(
      (!protectedInput || rents.isEmpty,
        "rent cannot bypass or share an auction/deposit transaction"),
      (indices.distinct.size == indices.size,
        "rent obligations must have distinct output indices"),
      (h <= Int.MaxValue - MAXIMUM_WINDOW || tokenClaims.isEmpty,
        "auction deadline would overflow"),
      (lotMappings.forall { case (b, chunk, i) =>
        tx.outputCandidates.lift(i).exists(canonicalLot(b, _, chunk, h))
      }, "fully consumed tokens must enter fresh canonical lots"),
      (if (auctionInputs > 0 && rents.isEmpty) auctionOutputs.subsetOf(Set(0))
        else auctionOutputs == lotIndices,
        "auction creation requires a rent source"),
      (auctionOutputs.forall(i => auctionShape(tx.outputCandidates(i))),
        "auction state requires a parsed canonical recipient script"),
      (tx.outputCandidates.filter(_.ergoTree == contracts.deposit).forall { out =>
        depositShape(out) && out.creationHeight == h
      }, "new deposits must be well-formed and freshly dated"),
      (rents.isEmpty || beneficiaryHash.exists(_.length == 32),
        "rent requires a producer-designated beneficiary"),
      (rents.isEmpty || paid >= credited,
        "rent ERG must pay the designated beneficiary separately")
    )
    checks.find(!_._1).map(c => Left(c._2)).getOrElse(Right(()))
  }

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
  final case class Rent(outputIndex: Int, fullyConsumed: Boolean)

  // Experimental keys only; assignment and activation need a reviewed node EIP.
  val ATTESTATION_KEY: Array[Byte] = Array(3.toByte, 0.toByte)
  val BENEFICIARY_KEY: Array[Byte] = Array(3.toByte, 1.toByte)
}
