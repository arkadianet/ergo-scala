package org.ergoplatform.tools

import org.ergoplatform.mining.CandidateGenerator
import org.ergoplatform.utils.{BoxUtils, ErgoTestHelpers, RandomWrapper}

/**
  * Informal microbenchmark for map/set overlay assembly lookups.
  * Run: `sbt "Test/runMain org.ergoplatform.tools.CollectTxsOverlayBench"`
  *
  * Does not claim full O(n) candidate generation; stop-on-first-overflow / HOL packing
  * remain out of scope (see ergoplatform/ergo#2357).
  */
object CollectTxsOverlayBench extends App with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.ErgoNodeTransactionGenerators._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  val bh       = boxesHolderGen.sample.get
  val rnd      = new RandomWrapper
  val us       = createUtxoState(bh, parameters)
  val minValue = BoxUtils.sufficientAmount(parameters)
  val inputs   = bh.boxes.values.toIndexedSeq.filter(_.value >= minValue * 2).takeRight(80)
  val txs = inputs.map(i =>
    validTransactionFromBoxes(IndexedSeq(i), rnd, issueNew = false, feeProp)
  )
  val h = validFullBlock(None, us, bh, rnd).header
  val upcomingContext = us.stateContext.upcoming(
    h.minerPk,
    h.timestamp,
    h.nBits,
    h.votes,
    emptyVSUpdate,
    h.version
  )
  val maxCost = parameters.maxBlockCost
  val maxSize = parameters.maxBlockSize
  val rounds  = 25

  (0 until 3).foreach { _ =>
    CandidateGenerator.collectTxs(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
  }

  val t0 = System.nanoTime()
  var collected = 0
  (0 until rounds).foreach { _ =>
    collected = CandidateGenerator
      .collectTxs(defaultMinerPk, maxCost, maxSize, us, upcomingContext, txs)
      ._1
      .size
  }
  val ms = (System.nanoTime() - t0) / 1e6

  println(
    s"collectTxs map-overlay: $rounds rounds, pool=${txs.size}, collected~=$collected, " +
      f"$ms%.1f ms total (${ms / rounds}%.2f ms/round)"
  )
  println(
    "Note: overlay lookup optimization only; not a claim of full O(n) candidate generation."
  )
}
