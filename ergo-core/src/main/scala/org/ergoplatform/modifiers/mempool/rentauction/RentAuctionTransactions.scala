package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.UnsignedInput
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.mempool.UnsignedErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.Parameters
import org.ergoplatform.utils.BoxUtils
import scorex.crypto.hash.Blake2b256
import sigma.Coll
import sigma.Colls
import sigma.ast.ByteArrayConstant
import sigma.ast.ErgoTree
import sigma.ast.IntArrayConstant
import sigma.ast.LongConstant
import sigma.ast.ShortConstant
import sigma.data.Digest32Coll
import sigma.interpreter.ContextExtension
import sigma.interpreter.ProverResult
import sigma.serialization.ErgoTreeSerializer

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

final case class RentAuctionPlan(
  transaction: ErgoTransaction,
  boxes: IndexedSeq[ErgoBox],
  height: Int
) {
  def unsigned: UnsignedErgoTransaction = UnsignedErgoTransaction(
    transaction.inputs.map(i => new UnsignedInput(i.boxId, i.spendingProof.extension)),
    transaction.dataInputs, transaction.outputCandidates)
}

/** Offline builders: callers provide resolved UTXOs and sign with their wallet. */
final class RentAuctionTransactions(
  contracts: RentAuctionContracts,
  parameters: Parameters,
  height: Int
) {
  import RentAuctionContracts.CARRIER
  import RentAuctionContracts.EXTENSION
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.SEED
  import RentAuctionContracts.TOKENS_PER_LOT
  import RentAuctionContracts.WINDOW

  private val rules = new RentAuctionRules(contracts, parameters)
  private type Tokens = Seq[(Digest32Coll, Long)]

  private def amount(n: BigInt): Long = {
    require(n >= 0 && n.isValidLong, "ERG amount is negative or exceeds Long")
    n.toLong
  }

  private def out(
    value: Long,
    tree: ErgoTree,
    tokens: Tokens = Seq.empty,
    registers: ErgoBox.AdditionalRegisters = Map.empty
  ): ErgoBoxCandidate = new ErgoBoxCandidate(value, tree, height,
    Colls.fromArray(tokens.toArray), registers)

  private def tag(b: ErgoBox): ErgoBox.AdditionalRegisters =
    Map(ErgoBox.R4 -> ByteArrayConstant(b.id))

  private def funded(candidate: ErgoBoxCandidate): ErgoBoxCandidate = {
    val minimum = BoxUtils.minimalErgoAmountSimulated(candidate, parameters)
    out(math.max(candidate.value, minimum), candidate.ergoTree,
      candidate.additionalTokens.toArray.toSeq, candidate.additionalRegisters)
  }

  private def tokenFree(boxes: IndexedSeq[ErgoBox]): Unit =
    require(boxes.forall(_.additionalTokens.isEmpty), "Funding must be token-free")

  private def checked(
    inputs: IndexedSeq[ErgoBox],
    outputs: IndexedSeq[ErgoBoxCandidate],
    rent: Map[Int, Short] = Map.empty,
    beneficiary: Option[ErgoTree] = None
  ): RentAuctionPlan = {
    require(inputs.nonEmpty, "At least one input is required")
    require(inputs.forall(_.creationHeight <= height), "Input is newer than height")
    val tx = ErgoTransaction(inputs.zipWithIndex.map { case (b, i) =>
      val extension = rent.get(i).map { index =>
        ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(index)))
      }.getOrElse(ContextExtension.empty)
      Input(b.id, ProverResult(Array.emptyByteArray, extension))
    }, outputs)
    tx.statelessValidity().get
    require(inputs.map(b => BigInt(b.value)).sum ==
      outputs.map(b => BigInt(b.value)).sum, "ERG balance mismatch")
    tx.outputs.foreach { b =>
      require(b.bytes.length <= ErgoBox.MaxBoxSize, "Output exceeds maximum box size")
      require(b.value >= BoxUtils.minimalErgoAmount(b, parameters), "Output is dust")
    }
    rules.validate(tx, inputs, height, beneficiary.map(t => Blake2b256(t.bytes)))
      .fold(error => throw new IllegalArgumentException(error), identity)
    RentAuctionPlan(tx, inputs, height)
  }

  private def finish(
    inputs: IndexedSeq[ErgoBox],
    outputs: IndexedSeq[ErgoBoxCandidate],
    change: ErgoTree,
    fee: Long,
    rent: Map[Int, Short] = Map.empty,
    beneficiary: Option[ErgoTree] = None
  ): RentAuctionPlan = {
    require(fee > 0, "Fee must be positive")
    val feeBox = funded(out(fee, contracts.fee))
    val remainder = amount(inputs.map(b => BigInt(b.value)).sum -
      outputs.map(b => BigInt(b.value)).sum - feeBox.value)
    val changeBox = out(remainder, change)
    val minimum = BoxUtils.minimalErgoAmountSimulated(changeBox, parameters)
    val finalOutputs = if (remainder >= minimum) {
      outputs ++ IndexedSeq(changeBox, feeBox)
    } else outputs :+ out(amount(BigInt(feeBox.value) + remainder), contracts.fee)
    checked(inputs, finalOutputs, rent, beneficiary)
  }

  def suggestedSeed: Long = math.max(SEED,
    3L * BoxUtils.sufficientAmount(parameters))

  def collect(
    sources: IndexedSeq[ErgoBox],
    funding: IndexedSeq[ErgoBox],
    beneficiary: ErgoTree,
    change: ErgoTree,
    fee: Long,
    seed: Long = 0L
  ): Try[RentAuctionPlan] = Try {
    require(sources.nonEmpty, "No rent sources selected")
    require(height <= Int.MaxValue - MAXIMUM_WINDOW, "Deadline would overflow")
    tokenFree(funding)
    val initialValue = if (seed == 0) suggestedSeed else seed
    require(initialValue >= SEED, "Auction seed is too small")
    val outputs = ArrayBuffer.empty[ErgoBoxCandidate]
    val rent = scala.collection.mutable.Map.empty[Int, Short]
    var rentAmount = BigInt(0)
    sources.zipWithIndex.foreach { case (b, index) =>
      require(height - b.creationHeight >= Constants.StoragePeriod,
        "Source has not reached storage-rent age")
      val charge = parameters.storageFeeFactor * b.bytes.length
      require(charge > 0, "Legacy wrapping rent charge is non-positive; skip this box")
      require(outputs.size <= Short.MaxValue, "Too many auction outputs")
      rent(index) = outputs.size.toShort
      if (b.value > charge) {
        outputs += out(b.value - charge, b.ergoTree,
          b.additionalTokens.toArray.toSeq, b.additionalRegisters)
        rentAmount += charge
      } else {
        rentAmount += b.value
        rules.auctionTokens(b, height).grouped(TOKENS_PER_LOT).foreach { tokens =>
          val registers: ErgoBox.AdditionalRegisters = Map(
            ErgoBox.R4 -> ByteArrayConstant(b.id),
            ErgoBox.R5 -> IntArrayConstant(Array(height + WINDOW,
              height + MAXIMUM_WINDOW)),
            ErgoBox.R6 -> LongConstant(0L),
            ErgoBox.R7 -> ByteArrayConstant(Array.emptyByteArray),
            ErgoBox.R8 -> LongConstant(initialValue))
          outputs += out(initialValue, contracts.auction, tokens, registers)
        }
      }
    }
    // An empty token-free source may point at the producer output below.
    outputs += funded(out(amount(rentAmount), beneficiary))
    if (contracts.chain.reemission.checkReemissionRules &&
      height > contracts.chain.reemission.activationHeight) {
      val debtId = contracts.chain.reemission.reemissionTokenId
      val debt = sources.map(b => BigInt(b.tokens.getOrElse(debtId, 0L))).sum
      if (debt > 0) outputs += out(amount(debt), contracts.legacyDeposit)
    }
    finish(sources ++ funding, outputs.toIndexedSeq, change, fee,
      rent.toMap, Some(beneficiary))
  }

  def bid(
    auction: ErgoBox,
    funding: IndexedSeq[ErgoBox],
    bid: Long,
    recipient: ErgoTree,
    change: ErgoTree,
    fee: Long
  ): Try[RentAuctionPlan] = Try {
    require(auction.ergoTree == contracts.auction, "Not an auction box")
    tokenFree(funding)
    val previous = auction.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long]
    val seed = auction.additionalRegisters(ErgoBox.R8).value.asInstanceOf[Long]
    val limits = auction.additionalRegisters(ErgoBox.R5).value.asInstanceOf[Coll[Int]]
    require(height < limits(0), "Bidding has ended")
    require(bid >= MINIMUM_BID && bid > previous && bid - previous >= INCREMENT,
      "Bid does not meet minimum or increment")
    val nextEnd = if (limits(1) - height <= EXTENSION) limits(1)
      else math.max(limits(0), height + EXTENSION)
    val registers = auction.additionalRegisters ++ Map(
      ErgoBox.R5 -> IntArrayConstant(Array(nextEnd, limits(1))),
      ErgoBox.R6 -> LongConstant(bid),
      ErgoBox.R7 -> ByteArrayConstant(recipient.bytes))
    val successor = out(amount(BigInt(seed) + bid), contracts.auction,
      auction.additionalTokens.toArray.toSeq, registers)
    val refund = if (previous == 0L) IndexedSeq.empty else {
      val bytes = auction.additionalRegisters(ErgoBox.R7)
        .value.asInstanceOf[Coll[Byte]].toArray
      val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(bytes)
      IndexedSeq(out(previous, tree, registers = tag(auction)))
    }
    finish(auction +: funding, IndexedSeq(successor) ++ refund, change, fee)
  }

  def settle(
    auction: ErgoBox,
    funding: IndexedSeq[ErgoBox],
    closer: ErgoTree,
    fee: Long
  ): Try[RentAuctionPlan] = Try {
    require(auction.ergoTree == contracts.auction, "Not an auction box")
    tokenFree(funding)
    val limits = auction.additionalRegisters(ErgoBox.R5).value.asInstanceOf[Coll[Int]]
    val bid = auction.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long]
    require(height >= limits(0), "Auction is still open")
    val outputs = if (bid == 0L) IndexedSeq.empty else {
      val bytes = auction.additionalRegisters(ErgoBox.R7)
        .value.asInstanceOf[Coll[Byte]].toArray
      val recipient = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(bytes)
      IndexedSeq(
        funded(out(CARRIER, recipient, auction.additionalTokens.toArray.toSeq,
          tag(auction))),
        funded(out(amount(BigInt(bid) + MERGE_BUDGET), contracts.deposit,
          registers = tag(auction))))
    }
    finish(auction +: funding, outputs, closer, fee)
  }

  def merge(
    reserve: ErgoBox,
    deposits: IndexedSeq[ErgoBox],
    sponsor: Option[ErgoBox] = None
  ): Try[RentAuctionPlan] = Try {
    require(deposits.nonEmpty && deposits.size <= 10, "Merge requires 1 to 10 deposits")
    require(reserve.ergoTree == contracts.reserve, "Incorrect reserve script")
    val nft = Digest32Coll @@ contracts.chain.reemission.reemissionNftIdBytes
    require(reserve.additionalTokens.nonEmpty &&
      reserve.additionalTokens(0) == (nft -> 1L), "Incorrect reserve NFT")
    require(deposits.forall(b => b.ergoTree == contracts.deposit &&
      b.additionalTokens.isEmpty && b.value >= MINIMUM_BID + MERGE_BUDGET),
      "Malformed proceeds deposit")
    tokenFree(sponsor.toIndexedSeq)
    val principal = deposits.map(b => BigInt(b.value) - MERGE_BUDGET).sum
    val fee = amount(BigInt(MERGE_BUDGET) * deposits.size +
      sponsor.map(b => BigInt(b.value)).getOrElse(BigInt(0)))
    require(fee <= 10000000L, "Merge fee exceeds the native reserve limit")
    checked(IndexedSeq(reserve) ++ deposits ++ sponsor.toIndexedSeq, IndexedSeq(
      out(amount(BigInt(reserve.value) + principal), contracts.reserve, Seq(nft -> 1L)),
      out(fee, contracts.fee)))
  }
}
