package org.ergoplatform.nodeView.mempool

import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import scorex.util.{ModifierId, ScorexLogging}
import scala.collection.mutable
import scala.util.Random
import org.ergoplatform.settings.Algos

/**
  * Additional types and functions used in ErgoMemPool
  */
object ErgoMemPoolUtils extends ScorexLogging {

  /** Parents precede children for wallet scans and transaction events.
    * Include data-input dependencies and report each transaction only once.
    */
  private[mempool] def inDependencyOrder(txs: Seq[UnconfirmedTransaction]): Seq[UnconfirmedTransaction] = {
    val unique = txs.groupBy(_.id).values.map(_.head).toSeq.sortBy(_.id)
    val creators = unique.flatMap(u => u.transaction.outputs.map(b => Algos.encode(b.id) -> u.id)).toMap
    val remaining = mutable.Map.empty[ModifierId, Int]
    val children = mutable.Map.empty[ModifierId, Vector[UnconfirmedTransaction]]
    unique.foreach { u =>
      val tx = u.transaction
      val parents = (tx.inputIds ++ tx.dataInputs.map(_.boxId)).flatMap(b => creators.get(Algos.encode(b))).toSet
      remaining(u.id) = parents.size
      parents.foreach { p => children(p) = children.getOrElse(p, Vector.empty) :+ u }
    }
    val ready = mutable.Queue.empty[UnconfirmedTransaction]
    ready ++= unique.filter(u => remaining(u.id) == 0)
    val result = Vector.newBuilder[UnconfirmedTransaction]
    while (ready.nonEmpty) {
      val u = ready.dequeue()
      result += u
      children.getOrElse(u.id, Vector.empty).foreach { child =>
        remaining(child.id) -= 1
        if (remaining(child.id) == 0) ready.enqueue(child)
      }
    }
    result.result()
  }

  /**
   * Hierarchy of sorting strategies for mempool transactions
   */
  sealed trait SortingOption

  object SortingOption {
    /**
     * Sort transactions by fee paid for transaction size, so fee/byte
     */
    case object FeePerByte extends SortingOption

    /**
     * Sort transactions by fee paid for transaction contracts validation cost, so fee/execution unit
     */
    case object FeePerCycle extends SortingOption

    /**
     * @return randomly chosen mempool sorting strategy
     */
    def random(): SortingOption = {
      if (Random.nextBoolean()) {
        FeePerByte
      } else {
        FeePerCycle
      }
    }
  }

  /**
   * Root of possible mempool transaction validation result family
   */
  sealed trait ProcessingOutcome {
    /** Transactions newly present in the committed pool, in dependency order.
      * A declined trigger may still have admitted other transactions.
      */
    def admitted: Seq[UnconfirmedTransaction] = Seq.empty

    /** Defined only for opt-in staging. Admission events must not charge this work again. */
    def validationWork: Option[Seq[ValidationWork]] = None

    /**
     * Time when transaction validation was started
     */
    protected val validationStartTime: Long

    /**
     * We assume that validation ends when this processing result class is constructed
     */
    private val validationEndTime: Long = System.currentTimeMillis()

    /**
     * 5.0 JIT costing was designed in a way that 1000 cost units are roughly corresponding to 1 ms of 1 CPU core
     * on commodity hardware (of 2021). So if we do not know the exact cost of transaction, we can estimate it by
     * tracking validation time and then getting estimated validation cost by multiplying the time (in ms) by 1000
     */
    val costPerMs = 1000

    /**
     * Estimated validation cost, see comment for `costPerMs`
     */
    def cost: Int = {
      val timeDiff = validationEndTime - validationStartTime
      if (timeDiff == 0) {
        costPerMs
      } else if (timeDiff > 1000000) {
        Int.MaxValue // shouldn't be here, so this branch is mostly to have safe .toInt below
      } else {
        (timeDiff * costPerMs).toInt
      }
    }
  }

  object ProcessingOutcome {

    private[mempool] def withWork(outcome: ProcessingOutcome,
                                  admittedTxs: Seq[UnconfirmedTransaction],
                                  work: Seq[ValidationWork]): ProcessingOutcome = {
      val started = System.currentTimeMillis()
      val total = math.min(Int.MaxValue.toLong, work.map(_.cost.toLong).sum).toInt
      outcome match {
        case a: Accepted => new Accepted(a.tx, started, admittedTxs.filterNot(_.id == a.tx.id)) {
          override val validationWork = Some(work)
          override val cost = total
        }
        case i: Invalidated => new Invalidated(i.e, started) {
          override val validationWork = Some(work)
          override val cost = total
          override val admitted = inDependencyOrder(admittedTxs)
        }
        case d: DoubleSpendingLoser => new DoubleSpendingLoser(d.winnerTxIds, started) {
          override val validationWork = Some(work)
          override val cost = total
          override val admitted = inDependencyOrder(admittedTxs)
        }
        case d: Declined => new Declined(d.e, started, inDependencyOrder(admittedTxs)) {
          override val validationWork = Some(work)
          override val cost = total
        }
      }
    }

    /**
     * Object signalling that a transaction is accepted to the memory pool
     *
     * @param coAdmitted - staged txs admitted alongside `tx` (resolved orphans and related
     *                   transactions); empty on the common single-tx path
     */
    class Accepted(val tx: UnconfirmedTransaction,
                   override protected val validationStartTime: Long,
                   val coAdmitted: Seq[UnconfirmedTransaction] = Seq.empty) extends ProcessingOutcome {
      override val cost: Int = tx.lastCost.getOrElse(super.cost)

      override lazy val admitted: Seq[UnconfirmedTransaction] = inDependencyOrder(tx +: coAdmitted)

    }

    /**
     * Class signalling that a valid transaction was rejected as it is double-spending inputs of mempool transactions
     * and has no bigger weight (fee/byte) than them on average.
     *
     * @param winnerTxIds - identifiers of transactions won in replace-by-fee auction
     */
    class DoubleSpendingLoser(val winnerTxIds: Set[ModifierId],
                              override protected val validationStartTime: Long) extends ProcessingOutcome

    /**
     * Class signalling that a transaction declined from being accepted into the memory pool
     */
    class Declined(val e: Throwable,
                   override protected val validationStartTime: Long,
                   override val admitted: Seq[UnconfirmedTransaction] = Seq.empty) extends ProcessingOutcome


    /**
     * Class signalling that a transaction turned out to be invalid when checked in the mempool
     */
    class Invalidated(val e: Throwable,
                      override protected val validationStartTime: Long) extends ProcessingOutcome

  }

}
