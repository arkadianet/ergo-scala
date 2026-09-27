package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.Input
import org.ergoplatform.modifiers.history.CPreHeader
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.mempool.ErgoTransactionSerializer
import org.ergoplatform.nodeView.state.UpcomingStateContext
import org.ergoplatform.nodeView.state.VotingData
import org.ergoplatform.settings.ChainSettings
import org.ergoplatform.settings.Constants
import org.ergoplatform.settings.ErgoValidationSettings
import org.ergoplatform.settings.MonetarySettings
import org.ergoplatform.settings.Parameters
import org.ergoplatform.utils.ErgoCoreTestConstants
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import scorex.util.ModifierId
import sigma.ast.ByteArrayConstant
import sigma.ast.ErgoTree
import sigma.ast.EvaluatedValue
import sigma.ast.IntConstant
import sigma.ast.IntArrayConstant
import sigma.ast.LongConstant
import sigma.ast.ShortConstant
import sigma.ast.SigmaPropConstant
import sigma.ast.SigmaAnd
import sigma.ast.BoolToSigmaProp
import sigma.ast.GE
import sigma.ast.SizeOf
import sigma.ast.SType
import sigma.Coll
import sigma.Colls
import sigma.data.Digest32Coll
import sigma.interpreter.ContextExtension
import sigma.interpreter.ProverResult
import sigma.serialization.ErgoTreeSerializer
import sigmastate.helpers.TestingHelpers.testBox

import scala.util.Try

trait RentAuctionFixture {
  protected type Registers =
    scala.collection.Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]]

  protected val defaults: ErgoCoreTestConstants.type = ErgoCoreTestConstants
  protected val validation: ErgoValidationSettings = ErgoValidationSettings.initial
  protected val chain: ChainSettings = defaults.chainSettings.copy(
    addressPrefix = 0,
    monetary = MonetarySettings(),
    reemission = defaults.chainSettings.reemission.copy(
      checkReemissionRules = true,
      emissionNftId = ModifierId @@
        "20fa2bf23962cdf51b07722d6237c0c7b8a44f78856c0f7ec308dc1ef1a92a51",
      reemissionTokenId = ModifierId @@
        "d9a2cc8a09abfaed87afacfbb7daee79a6b26f10c6613fc13d3f3953e5521d1a",
      reemissionNftId = ModifierId @@
        "d3feeffa87f2df63a7a15b4905e618ae3ce4c69a7975f171bd314d0b877927b8",
      activationHeight = 777217,
      reemissionStartHeight = 2080800
    )
  )
  protected val params: Parameters = new Parameters(
    0,
    defaults.parameters.parametersTable + (Parameters.BlockVersion -> 4),
    defaults.emptyVSUpdate
  )
  protected lazy val contracts: RentAuctionContracts = new RentAuctionContracts(chain)
  protected implicit lazy val interpreter: ErgoInterpreter = ErgoInterpreter(params)
  protected val height: Int = 2100001
  protected val owner: ErgoTree = ErgoTree.fromSigmaBoolean(defaults.defaultMinerPk)
  protected val anyone: ErgoTree = Constants.TrueTree
  protected val nobody: ErgoTree = Constants.FalseTree
  protected val token: Digest32Coll =
    Digest32Coll @@ Colls.fromArray(Array.fill(32)(7.toByte))
  protected val nft: Digest32Coll =
    Digest32Coll @@ chain.reemission.reemissionNftIdBytes
  private var sequence: Int = 0

  protected lazy val recipient256: ErgoTree = {
    val pk = SigmaPropConstant(defaults.defaultMinerPk)
    val trees = for {
      n <- (1 to 250).iterator
      k <- Iterator(0, 64, 8192, 1048576, 134217728)
    } yield ErgoTree.fromProposition(ErgoTree.defaultHeaderWithVersion(0),
      SigmaAnd(pk, BoolToSigmaProp(
        GE(SizeOf(ByteArrayConstant(Array.fill(n)(1.toByte))), IntConstant(k)))))
    trees.find(_.bytes.length == 256).get
  }

  protected def priced(price: Int): Parameters = new Parameters(0,
    params.parametersTable.updated(Parameters.MinValuePerByteIncrease, price), defaults.emptyVSUpdate)

  protected def tokens(count: Int, amount: Long = 1000000000L): Seq[(Digest32Coll, Long)] =
    (1 to count).map(i => (Digest32Coll @@ Colls.fromArray(Blake2b256(s"v2-token-$i"))) -> amount)

  protected def collectPlan(count: Int = 1, p: Parameters = params,
    collector: ErgoTree = owner, at: Int = height): RentAuctionPlan = {
    val sources = tokens(count).map { t =>
      box(1000000L, nobody, at - Constants.StoragePeriod, Seq(t))
    }.toIndexedSeq
    new RentAuctionTransactions(contracts, p, at, validation).collect(sources,
      IndexedSeq(box(1000000000L, created = at)), owner, collector, anyone,
      if (p.minValuePerByte > 360) 2000000L else 1000000L).get
  }

  protected def native(plan: RentAuctionPlan, p: Parameters = params,
    vs: ErgoValidationSettings = validation): Try[Int] =
    plan.transaction.statelessValidity().flatMap { _ =>
      plan.transaction.statefulValidity(plan.boxes, IndexedSeq.empty,
        context(plan.height).copy(currentParameters = p, validationSettings = vs)(chain))(ErgoInterpreter(p))
    }

  protected def context(h: Int): UpcomingStateContext = {
    val pre = CPreHeader(
      Header.Interpreter60Version, Header.GenesisParentId,
      defaults.defaultTimestamp, defaults.defaultNBits, h,
      defaults.defaultVotes, defaults.defaultMinerPkPoint
    )
    UpcomingStateContext(
      Seq.empty, None, pre, defaults.genesisStateDigest, params,
      validation, VotingData.empty
    )(chain)
  }

  protected def box(
    value: Long,
    tree: ErgoTree = Constants.TrueTree,
    created: Int = height,
    tokens: Seq[(Digest32Coll, Long)] = Seq.empty,
    registers: Registers = Map.empty
  ): ErgoBox = {
    sequence += 1
    val txId = ModifierId @@ Base16.encode(Blake2b256(sequence.toString))
    testBox(value, tree, created, tokens, registers, transactionId = txId)
  }

  protected def output(
    value: Long,
    tree: ErgoTree = Constants.TrueTree,
    h: Int = height,
    tokens: Seq[(Digest32Coll, Long)] = Seq.empty,
    registers: Registers = Map.empty
  ): ErgoBoxCandidate = new ErgoBoxCandidate(
    value, tree, h, Colls.fromArray(tokens.toArray), registers
  )

  protected def tag(id: Array[Byte]):
    Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]] =
    Map(ErgoBox.R4 -> ByteArrayConstant(id))

  protected def lotRegisters(
    id: Array[Byte],
    end: Int,
    cap: Int,
    bid: Long = 0L,
    recipient: Array[Byte] = Array.emptyByteArray,
    seed: Long = 5000000L
  ): Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]] = Map(
    ErgoBox.R4 -> ByteArrayConstant(id),
    ErgoBox.R5 -> IntArrayConstant(Array(end, cap)),
    ErgoBox.R6 -> LongConstant(bid),
    ErgoBox.R7 -> ByteArrayConstant(recipient),
    ErgoBox.R8 -> LongConstant(seed),
    ErgoBox.R9 -> ByteArrayConstant(owner.bytes)
  )

  protected def lot(
    bid: Long = 0L,
    end: Int = height + RentAuctionContracts.WINDOW,
    cap: Int = height + RentAuctionContracts.MAXIMUM_WINDOW,
    recipient: ErgoTree = Constants.TrueTree,
    created: Int = height,
    seed: Long = 5000000L
  ): ErgoBox = box(
    seed + RentAuctionContracts.CLOSE_ALLOWANCE + bid, contracts.auction, created,
    Seq(token -> 100L),
    lotRegisters(
      Array.fill(32)(9.toByte), end, cap, bid,
      if (bid == 0L) Array.emptyByteArray else recipient.bytes,
      seed
    )
  )

  protected def rentInput(b: ErgoBox, index: Short): Input = Input(
    b.id,
    ProverResult(
      Array.emptyByteArray,
      ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(index)))
    )
  )

  protected def transaction(
    inputs: IndexedSeq[ErgoBox],
    outputs: IndexedSeq[ErgoBoxCandidate],
    rent: Map[Int, Short] = Map.empty
  ): ErgoTransaction = ErgoTransaction(
    inputs.zipWithIndex.map { case (b, i) =>
      rent.get(i).fold(Input(b.id, ProverResult.empty))(rentInput(b, _))
    },
    outputs
  )

  protected def validate(
    tx: ErgoTransaction,
    inputs: IndexedSeq[ErgoBox],
    h: Int = height
  ): Try[Int] = {
    val decoded = ErgoTransactionSerializer.parseBytes(
      ErgoTransactionSerializer.toBytes(tx)
    )
    decoded.statelessValidity().flatMap { _ =>
      decoded.statefulValidity(inputs, IndexedSeq.empty, context(h))
    }
  }

  protected final case class Spend(
    tx: ErgoTransaction,
    boxes: IndexedSeq[ErgoBox],
    at: Int
  ) {
    def result: Try[Int] = validate(tx, boxes, at)
    def withOutputs(outputs: IndexedSeq[ErgoBoxCandidate]): Spend =
      copy(tx = ErgoTransaction(tx.inputs, outputs))
  }

  protected def change(
    out: ErgoBoxCandidate,
    value: Long,
    tree: ErgoTree
  ): ErgoBoxCandidate = new ErgoBoxCandidate(
    value, tree, out.creationHeight, out.additionalTokens, out.additionalRegisters
  )

  protected def bidSpend(
    b: ErgoBox,
    amount: Long,
    at: Int = height,
    end: Int = height + RentAuctionContracts.WINDOW,
    cap: Int = height + RentAuctionContracts.MAXIMUM_WINDOW,
    recipient: ErgoTree = Constants.TrueTree
  ): Spend = {
    val previous = b.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long]
    val funding = box(amount + 2000000L, created = at)
    val nextEnd = if (cap - at <= RentAuctionContracts.EXTENSION) cap
    else math.max(end, at + RentAuctionContracts.EXTENSION)
    val registers = b.additionalRegisters ++ Map(
      ErgoBox.R5 -> IntArrayConstant(Array(nextEnd, cap)),
      ErgoBox.R6 -> LongConstant(amount),
      ErgoBox.R7 -> ByteArrayConstant(recipient.bytes)
    )
    val next = output(
      b.value - previous + amount, contracts.auction, at,
      b.additionalTokens.toArray.toSeq, registers
    )
    val refund = if (previous == 0L) IndexedSeq.empty else {
      val recipientBytes =
        b.additionalRegisters(ErgoBox.R7).value.asInstanceOf[Coll[Byte]].toArray
      val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(recipientBytes)
      IndexedSeq(output(previous, tree, at, registers = tag(b.id)))
    }
    val outputs = IndexedSeq(next) ++ refund ++
      IndexedSeq(output(2000000L, contracts.fee, at))
    val inputs = IndexedSeq(b, funding)
    Spend(transaction(inputs, outputs), inputs, at)
  }

  protected def settleSpend(b: ErgoBox, at: Int): Spend = {
    val bid = b.additionalRegisters(ErgoBox.R6).value.asInstanceOf[Long]
    val seed = b.additionalRegisters(ErgoBox.R8).value.asInstanceOf[Long]
    val collector = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(
      b.additionalRegisters(ErgoBox.R9).value.asInstanceOf[Coll[Byte]].toArray)
    val fee = 1000000L
    val share = bid / RentAuctionContracts.COLLECTOR_SHARE_DENOMINATOR
    val returned = output(seed + RentAuctionContracts.CLOSE_ALLOWANCE - fee + share,
      collector, at, registers = tag(b.id))
    val sale = if (bid == 0L) IndexedSeq.empty else {
      val recipient = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(
        b.additionalRegisters(ErgoBox.R7).value.asInstanceOf[Coll[Byte]].toArray)
      val carrier = new RentAuctionRules(contracts, params, validation)
        .carrier(recipient.bytes.length, b.additionalTokens.length)
      IndexedSeq(output(carrier, recipient, at, b.additionalTokens.toArray.toSeq, tag(b.id)),
        output(bid - share - carrier, contracts.deposit, at, registers = tag(b.id)))
    }
    val extension = ContextExtension(Map(0.toByte -> IntConstant(0),
      1.toByte -> LongConstant(fee), 2.toByte -> IntConstant(params.minValuePerByte)))
    val tx = ErgoTransaction(IndexedSeq(Input(b.id,
      ProverResult(Array.emptyByteArray, extension))),
      IndexedSeq(returned) ++ sale :+ output(fee, contracts.fee, at))
    Spend(tx, IndexedSeq(b), at)
  }

  protected def burnSpend(b: ErgoBox, at: Int): Spend = settleSpend(b, at)

  protected def nativeRejected(spend: Spend, reason: String): Unit = {
    val result = spend.result
    require(result.isFailure, "Expected native validation failure")
    require(result.failed.get.getMessage.contains(reason), result.failed.get.getMessage)
  }

  protected def depositBox(principal: Long, at: Int = height): ErgoBox = box(
    principal + RentAuctionContracts.MERGE_BUDGET, contracts.deposit, at,
    registers = tag(Array.fill(32)(11.toByte))
  )

  protected def mergeSpend(
    deposits: IndexedSeq[ErgoBox],
    at: Int = height,
    reserveValue: Long = 1000000000000L
  ): Spend = {
    val reserve = box(reserveValue, contracts.reserve, at - 1, Seq(nft -> 1L))
    val fee = deposits.size * RentAuctionContracts.MERGE_BUDGET
    val principal = deposits.map(_.value).sum - fee
    val outputs = IndexedSeq(
      output(reserve.value + principal, contracts.reserve, at, Seq(nft -> 1L)),
      output(fee, contracts.fee, at)
    )
    val inputs = IndexedSeq(reserve) ++ deposits
    Spend(transaction(inputs, outputs), inputs, at)
  }
}
