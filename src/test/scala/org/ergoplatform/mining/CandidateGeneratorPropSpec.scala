package org.ergoplatform.mining

import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.ErgoHistoryUtils._
import org.ergoplatform.nodeView.state.{ErgoStateContext, UtxoStateReader}
import org.ergoplatform.settings.MonetarySettings
import org.ergoplatform.utils.{BoxUtils, ErgoCorePropertyTest, RandomWrapper}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.scalacheck.Gen
import scorex.util.ModifierId
import sigma.data.ProveDlog

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class CandidateGeneratorPropSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.ErgoCoreGenerators._
  import org.ergoplatform.utils.generators.ErgoNodeTransactionGenerators._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  val delta: Int = settings.chainSettings.monetary.minerRewardDelay

  private def expectedRewardOutputScriptBytes(pk: ProveDlog): Array[Byte] =
    ErgoTreePredef.rewardOutputScript(delta, pk).bytes

  implicit private val verifier: ErgoInterpreter = ErgoInterpreter(parameters)

  property("minersRewardAtHeight test vectors") {
    emission.minersRewardAtHeight(525000) shouldBe 67500000000L
    emission.minersRewardAtHeight(525600) shouldBe 67500000000L
    emission.minersRewardAtHeight(590400) shouldBe 67500000000L
    emission.minersRewardAtHeight(655200) shouldBe 66000000000L
    emission.minersRewardAtHeight(720000) shouldBe 63000000000L
    emission.minersRewardAtHeight(784800) shouldBe 60000000000L
    emission.minersRewardAtHeight(849600) shouldBe 57000000000L
    emission.minersRewardAtHeight(914400) shouldBe 54000000000L
    emission.minersRewardAtHeight(979200) shouldBe 51000000000L
    emission.minersRewardAtHeight(1044000) shouldBe 48000000000L
    emission.minersRewardAtHeight(1108800) shouldBe 45000000000L
    emission.minersRewardAtHeight(1173600) shouldBe 42000000000L
    emission.minersRewardAtHeight(1238400) shouldBe 39000000000L
    emission.minersRewardAtHeight(1303200) shouldBe 36000000000L
    emission.minersRewardAtHeight(1368000) shouldBe 33000000000L
    emission.minersRewardAtHeight(1432800) shouldBe 30000000000L
    emission.minersRewardAtHeight(1497600) shouldBe 27000000000L
    emission.minersRewardAtHeight(1562400) shouldBe 24000000000L
    emission.minersRewardAtHeight(1627200) shouldBe 21000000000L
    emission.minersRewardAtHeight(1692000) shouldBe 18000000000L
    emission.minersRewardAtHeight(1756800) shouldBe 15000000000L
    emission.minersRewardAtHeight(1821600) shouldBe 12000000000L
    emission.minersRewardAtHeight(1886400) shouldBe 9000000000L
    emission.minersRewardAtHeight(1951200) shouldBe 6000000000L
    emission.minersRewardAtHeight(2016000) shouldBe 3000000000L
    emission.minersRewardAtHeight(2080799) shouldBe 3000000000L
    emission.minersRewardAtHeight(2080800) shouldBe 0L
  }

  property("collect reward from emission box only") {
    val us = createUtxoState(settings)._1
    us.emissionBoxOpt should not be None
    val expectedReward = emission.minersRewardAtHeight(us.stateContext.currentHeight)

    val incorrectTxs =
      CandidateGenerator.collectEmission(us, proveDlogGen.sample.get, emptyStateContext).toSeq
    val txs = CandidateGenerator.collectEmission(us, defaultMinerPk, emptyStateContext).toSeq

    txs.size shouldBe 1
    val emissionTx = txs.head
    emissionTx.outputs.length shouldBe 2
    emissionTx.outputs.last.value shouldBe expectedReward
    emissionTx.outputs.last.propositionBytes shouldEqual expectedRewardOutputScriptBytes(
      defaultMinerPk
    )

    us.applyModifier(validFullBlock(None, us, incorrectTxs), None)(_ => ()) shouldBe 'failure
    us.applyModifier(validFullBlock(None, us, txs), None)(_ => ()) shouldBe 'success
  }

  property("collect reward from transaction fees only") {
    val bh     = boxesHolderGen.sample.get
    val us     = createUtxoState(bh, parameters)
    val height = us.stateContext.currentHeight
    val blockTx = validTransactionFromBoxes(
      bh.boxes.take(2).values.toIndexedSeq,
      outputsProposition = feeProp
    )

    val txs =
      CandidateGenerator.collectFees(height, Seq(blockTx), defaultMinerPk, emptyStateContext).toSeq
    val incorrect = CandidateGenerator
      .collectFees(height, Seq(blockTx), proveDlogGen.sample.get, emptyStateContext)
      .toSeq
    txs.length shouldBe 1
    val feeTx = txs.head
    feeTx.outputs.length shouldBe 1
    feeTx.outputs.head.value shouldBe txs.flatMap(_.outputs).map(_.value).sum
    feeTx.outputs.head.propositionBytes shouldEqual expectedRewardOutputScriptBytes(
      defaultMinerPk
    )

    us.applyModifier(validFullBlock(None, us, blockTx +: incorrect), None)(_ => ()) shouldBe 'failure
    us.applyModifier(validFullBlock(None, us, blockTx +: txs), None)(_ => ()) shouldBe 'success
  }

  property("filter out double spend txs") {
    val tx = validErgoTransactionGen.sample.get._2
    CandidateGenerator.doublespend(Seq(tx), tx) shouldBe true

    val inputs = validErgoTransactionGenTemplate(minAssets = 0, maxAssets = -1).sample.get._1
    val (l, r) = inputs.splitAt(50)
    val tx_1   = validTransactionFromBoxes(l)
    val tx_2   = validTransactionFromBoxes(r :+ l.last) //conflicting with tx_1
    val tx_3   = validTransactionFromBoxes(r) //conflicting with tx_2, not conflicting with tx_1

    CandidateGenerator.doublespend(Seq(tx_1), tx_2) shouldBe true
    CandidateGenerator.doublespend(Seq(tx_1), tx_3) shouldBe false
    CandidateGenerator.doublespend(Seq(tx_1, tx_2), tx_1) shouldBe true
    CandidateGenerator.doublespend(Seq(tx_1, tx_2), tx_2) shouldBe true
    CandidateGenerator.doublespend(Seq(tx_1, tx_3), tx) shouldBe false
  }

  property("should only collect valid transactions") {
    def checkCollectTxs(
      maxCost: Int,
      maxSize: Int,
      withTokens: Boolean = false
    ): Unit = {

      val bh          = boxesHolderGen.sample.get
      val rnd         = new RandomWrapper
      val us          = createUtxoState(bh, parameters)
      val minValue    = BoxUtils.sufficientAmount(parameters)
      val inputs      = bh.boxes.values.toIndexedSeq.filter(_.value >= minValue * 2).takeRight(100)
      val txsWithFees = inputs.map(i =>
        validTransactionFromBoxes(IndexedSeq(i), rnd, issueNew = withTokens, feeProp)
      )
      val head = txsWithFees.head

      val h = validFullBlock(None, us, bh, rnd).header
      val upcomingContext = us.stateContext.upcoming(
        h.minerPk,
        h.timestamp,
        h.nBits,
        h.votes,
        emptyVSUpdate,
        h.version
      )
      upcomingContext.currentHeight shouldBe (us.stateContext.currentHeight + 1)

      val fromSmallMempool = CandidateGenerator
        .collectTxs(
          defaultMinerPk,
          maxCost,
          maxSize,
          us,
          upcomingContext,
          Seq(head)
        )
        ._1
      fromSmallMempool.size shouldBe 2
      fromSmallMempool.contains(head) shouldBe true

      val fromBigMempool = CandidateGenerator
        .collectTxs(
          defaultMinerPk,
          maxCost,
          maxSize,
          us,
          upcomingContext,
          txsWithFees
        )
        ._1

      val newBoxes = fromBigMempool.flatMap(_.outputs)
      val costs: Seq[Int] = fromBigMempool.map { tx =>
        us.validateWithCost(tx, upcomingContext, Int.MaxValue, Some(verifier)).getOrElse {
          val boxesToSpend =
            tx.inputs.map(i => newBoxes.find(b => b.id sameElements i.boxId).get)
          tx.statefulValidity(boxesToSpend, IndexedSeq(), upcomingContext).get
        }
      }

      fromBigMempool.length should be > 2
      fromBigMempool.map(_.size).sum should be < maxSize
      costs.sum should be < maxCost
      if (!withTokens) fromBigMempool.size should be < txsWithFees.size
    }

    // transactions reach computation cost block limit
    checkCollectTxs(parameters.maxBlockCost, Int.MaxValue)

    // transactions reach block size limit
    checkCollectTxs(Int.MaxValue, 4096)

    // miner collects correct transactions from mempool even if they have tokens
    checkCollectTxs(Int.MaxValue, Int.MaxValue, withTokens = true)

  }

  property("should collect dependent (chained) transactions in dependency order") {
    val bh       = boxesHolderGen.sample.get
    val rnd      = new RandomWrapper
    val us       = createUtxoState(bh, parameters)
    val minValue = BoxUtils.sufficientAmount(parameters)
    val input    = bh.boxes.values.toIndexedSeq.filter(_.value >= minValue * 4).last

    // tx2 spends an output of tx1 (only available via the in-block overlay) and pays a fee
    val tx1 = validTransactionFromBoxes(IndexedSeq(input), rnd, issueNew = false)
    val tx2 = validTransactionFromBoxes(tx1.outputs, rnd, issueNew = false, feeProp)

    val h = validFullBlock(None, us, bh, rnd).header
    val upcomingContext = us.stateContext.upcoming(
      h.minerPk, h.timestamp, h.nBits, h.votes, emptyVSUpdate, h.version
    )

    val (collected, invalid) = CandidateGenerator.collectTxs(
      defaultMinerPk, Int.MaxValue, Int.MaxValue, us, upcomingContext, Seq(tx1, tx2)
    )
    invalid shouldBe empty
    collected should contain(tx1)
    collected should contain(tx2)
    collected.indexOf(tx1) should be < collected.indexOf(tx2)
    // fee-collecting tx spends tx2's fee output
    collected.exists(t => t.inputs.exists(i => tx2.outputs.exists(_.id.sameElements(i.boxId)))) shouldBe true

    // child is dropped when offered before its parent
    val (collectedReversed, invalidReversed) = CandidateGenerator.collectTxs(
      defaultMinerPk, Int.MaxValue, Int.MaxValue, us, upcomingContext, Seq(tx2, tx1)
    )
    invalidReversed should contain(tx2.id)
    collectedReversed should contain(tx1)
    collectedReversed should not contain tx2
  }

  property("map overlay collectTxs matches legacy withTransactions overlay") {
    def check(txCount: Int, withTokens: Boolean): Unit = {
      val bh       = boxesHolderGen.sample.get
      val rnd      = new RandomWrapper
      val us       = createUtxoState(bh, parameters)
      val minValue = BoxUtils.sufficientAmount(parameters)
      val inputs   = bh.boxes.values.toIndexedSeq.filter(_.value >= minValue * 2).takeRight(txCount)
      val txs = inputs.map(i =>
        validTransactionFromBoxes(IndexedSeq(i), rnd, issueNew = withTokens, feeProp)
      )
      val h = validFullBlock(None, us, bh, rnd).header
      val upcomingContext = us.stateContext.upcoming(
        h.minerPk, h.timestamp, h.nBits, h.votes, emptyVSUpdate, h.version
      )
      val maxCost = parameters.maxBlockCost
      val maxSize = parameters.maxBlockSize

      val modern = CandidateGenerator.collectTxs(
        defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs
      )
      val legacy = collectTxsLegacy(
        defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs
      )
      modern._1.map(_.id) shouldBe legacy._1.map(_.id)
      modern._2 shouldBe legacy._2
    }

    check(20, withTokens = false)
    check(30, withTokens = true)
    check(10, withTokens = false)
  }

  property("map overlay collectTxs microbenchmark (informational)") {
    val bh       = boxesHolderGen.sample.get
    val rnd      = new RandomWrapper
    val us       = createUtxoState(bh, parameters)
    val minValue = BoxUtils.sufficientAmount(parameters)
    val inputs   = bh.boxes.values.toIndexedSeq.filter(_.value >= minValue * 2).takeRight(60)
    val txs = inputs.map(i =>
      validTransactionFromBoxes(IndexedSeq(i), rnd, issueNew = false, feeProp)
    )
    val h = validFullBlock(None, us, bh, rnd).header
    val upcomingContext = us.stateContext.upcoming(
      h.minerPk, h.timestamp, h.nBits, h.votes, emptyVSUpdate, h.version
    )
    val maxCost = parameters.maxBlockCost
    val maxSize = parameters.maxBlockSize
    val rounds  = 15

    (0 until 2).foreach { _ =>
      CandidateGenerator.collectTxs(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
      collectTxsLegacy(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
    }

    val tModern0 = System.nanoTime()
    (0 until rounds).foreach { _ =>
      CandidateGenerator.collectTxs(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
    }
    val modernMs = (System.nanoTime() - tModern0) / 1e6

    val tLegacy0 = System.nanoTime()
    (0 until rounds).foreach { _ =>
      collectTxsLegacy(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
    }
    val legacyMs = (System.nanoTime() - tLegacy0) / 1e6

    info(
      s"collectTxs overlay microbench pool=${txs.size} rounds=$rounds: " +
        f"map-overlay ${modernMs}%.1f ms, legacy ${legacyMs}%.1f ms " +
        "(informational; not a full O(n) candidate-generation claim)"
    )
    modernMs should be >= 0.0
    legacyMs should be >= 0.0
  }

  property("should not be able to spend recent fee boxes") {

    val delta          = 1
    val inputsNum      = 2
    val feeProposition = ErgoTreePredef.feeProposition(delta)

    val bh     = boxesHolderGen.sample.get
    var us     = createUtxoState(bh, parameters)
    val height = EmptyHistoryHeight

    val ms = MonetarySettings(minerRewardDelay = delta)
    val st = settings.copy(chainSettings = settings.chainSettings.copy(monetary = ms))
    val sc = ErgoStateContext.empty(genesisStateDigest, st.chainSettings, parameters)
    val txBoxes = bh.boxes.grouped(inputsNum).map(_.values.toIndexedSeq).toSeq

    val blockTx =
      validTransactionFromBoxes(txBoxes.head, outputsProposition = feeProposition)
    val txs = CandidateGenerator
      .collectFees(height, Seq(blockTx), defaultMinerPk, sc)
      .toSeq
    val block = validFullBlock(None, us, blockTx +: txs)

    us = us.applyModifier(block, None)(_ => ()).get

    val blockTx2 =
      validTransactionFromBoxes(txBoxes(1), outputsProposition = feeProposition)
    val block2 = validFullBlock(Some(block), us, IndexedSeq(blockTx2))

    val earlySpendingTx =
      validTransactionFromBoxes(txs.head.outputs, stateCtxOpt = Some(us.stateContext))

    val invalidBlock2 =
      validFullBlock(Some(block), us, IndexedSeq(earlySpendingTx, blockTx2))

    us.applyModifier(invalidBlock2, None)(_ => ()) shouldBe 'failure

    us = us.applyModifier(block2, None)(_ => ()).get

    val earlySpendingTx2 =
      validTransactionFromBoxes(txs.head.outputs, stateCtxOpt = Some(us.stateContext))

    val blockTx3 =
      validTransactionFromBoxes(txBoxes(2), outputsProposition = feeProposition)
    val block3 = validFullBlock(Some(block2), us, IndexedSeq(earlySpendingTx2, blockTx3))

    us.applyModifier(block3, None)(_ => ()) shouldBe 'success
  }

  property("collect reward from both emission box and fees") {
    val (us, _) = createUtxoState(settings)
    us.emissionBoxOpt should not be None
    val expectedReward = emission.minersRewardAtHeight(us.stateContext.currentHeight)

    forAll(
      Gen.nonEmptyListOf(validErgoTransactionGenTemplate(minAssets = 0, propositionGen = feeProp))
    ) { btxs =>
      val blockTxs = btxs.map(_._2)
      val height   = EmptyHistoryHeight
      val txs = CandidateGenerator.collectRewards(
        us.emissionBoxOpt,
        height,
        blockTxs,
        defaultMinerPk,
        emptyStateContext
      )
      txs.length shouldBe 2

      val emissionTx = txs.head
      emissionTx.outputs.length shouldBe 2
      emissionTx.outputs.last.value shouldBe expectedReward
      emissionTx.outputs.last.propositionBytes shouldEqual expectedRewardOutputScriptBytes(
        defaultMinerPk
      )

      val feeTx = txs.last
      feeTx.outputs.length shouldBe 1
      feeTx.outputs.head.value shouldBe blockTxs.flatMap(_.outputs).map(_.value).sum
      feeTx.outputs.head.propositionBytes shouldEqual expectedRewardOutputScriptBytes(
        defaultMinerPk
      )
    }
  }

  property("it should calculate average block mining time from creation timestamps") {
    val timestamps1 = System.currentTimeMillis()
    val timestamps2 = timestamps1 + 100
    val timestamps3 = timestamps2 + 200
    val timestamps4 = timestamps3 + 300
    val avgMiningTime = {
      CandidateGenerator.getBlockMiningTimeAvg(
        Vector(timestamps1, timestamps2, timestamps3, timestamps4)
      )
    }
    avgMiningTime shouldBe 200.millis
  }

  /**
    * Pre-overlay reference: rebuilds state via `withTransactions` and uses linear
    * `find`/`doublespend` scans. Kept only for differential testing against the map overlay.
    */
  private def collectTxsLegacy(
    minerPk: ProveDlog,
    maxBlockCost: Int,
    maxBlockSize: Int,
    us: UtxoStateReader,
    upcomingContext: ErgoStateContext,
    transactions: Seq[ErgoTransaction]
  ): (Seq[ErgoTransaction], Seq[ModifierId]) = {
    import CandidateGenerator.{CostedTransaction, collectFees, correctLimits, doublespend}

    val currentHeight = us.stateContext.currentHeight
    val verifier: ErgoInterpreter = ErgoInterpreter(upcomingContext.currentParameters)

    def inputsNotSpent(tx: ErgoTransaction, s: UtxoStateReader): Boolean =
      tx.inputs.forall(inp => s.boxById(inp.boxId).isDefined)

    @tailrec
    def loop(
      mempoolTxs: Iterable[ErgoTransaction],
      acc: Seq[CostedTransaction],
      lastFeeTx: Option[CostedTransaction],
      invalidTxs: Seq[ModifierId]
    ): (Seq[ErgoTransaction], Seq[ModifierId]) = {
      val currentCosted = acc ++ lastFeeTx
      def current: Seq[ErgoTransaction] = currentCosted.map(_._1)
      val stateWithTxs = us.withTransactions(current)

      mempoolTxs.headOption match {
        case Some(tx) =>
          if (!inputsNotSpent(tx, stateWithTxs) || doublespend(current, tx)) {
            loop(mempoolTxs.tail, acc, lastFeeTx, invalidTxs :+ tx.id)
          } else {
            stateWithTxs.validateWithCost(tx, upcomingContext, maxBlockCost, Some(verifier)) match {
              case Success(costConsumed) =>
                val newTxs = acc :+ (tx -> costConsumed)
                val newBoxes = newTxs.flatMap(_._1.outputs)
                collectFees(currentHeight, newTxs.map(_._1), minerPk, upcomingContext) match {
                  case Some(feeTx) =>
                    val boxesToSpend = feeTx.inputs.flatMap(i =>
                      newBoxes.find(b => java.util.Arrays.equals(b.id, i.boxId))
                    )
                    feeTx.statefulValidity(boxesToSpend, IndexedSeq(), upcomingContext)(verifier) match {
                      case Success(cost) =>
                        val blockTxs: Seq[CostedTransaction] = (feeTx -> cost) +: newTxs
                        if (correctLimits(blockTxs, maxBlockCost, maxBlockSize)) {
                          loop(mempoolTxs.tail, newTxs, Some(feeTx -> cost), invalidTxs)
                        } else {
                          current -> invalidTxs
                        }
                      case Failure(_) =>
                        current -> invalidTxs
                    }
                  case None =>
                    val blockTxs: Seq[CostedTransaction] = newTxs ++ lastFeeTx.toSeq
                    if (correctLimits(blockTxs, maxBlockCost, maxBlockSize)) {
                      loop(mempoolTxs.tail, blockTxs, lastFeeTx, invalidTxs)
                    } else {
                      current -> invalidTxs
                    }
                }
              case Failure(_) =>
                loop(mempoolTxs.tail, acc, lastFeeTx, invalidTxs :+ tx.id)
            }
          }
        case None =>
          current -> invalidTxs
      }
    }

    loop(transactions, Seq.empty, None, Seq.empty)
  }


}
