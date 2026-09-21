package org.ergoplatform.nodeView.mempool

import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import scala.util.{Failure, Try}

/** Work actually executed, including unsuccessful and subsequently evicted transactions. */
case class ValidationWork(transaction: UnconfirmedTransaction, cost: Int, error: Option[Throwable], recheck: Boolean = false)

/** Local to one actor invocation. Reserve a full transaction limit before starting
  * scripts, refund unused cost on success, and conservatively charge the reservation
  * on failure (the interpreter does not return the consumed cost on that path).
  * Exhaustion defers work; it is not a transaction validation failure.
  */
private[mempool] final class StagingValidation(maxAttempts: Int, maxCost: Long, transactionLimit: Int) {
  private var records = Vector.empty[ValidationWork]
  private var spent = 0L
  def work: Seq[ValidationWork] = records
  def canValidate: Boolean = records.size < maxAttempts && maxCost - spent >= transactionLimit

  def validate(tx: UnconfirmedTransaction, recheck: Boolean = false)(run: => Try[Int]): Try[Int] = {
    if (!canValidate) Failure(StagingValidation.Deferred)
    else {
      val result = run
      val cost = result.getOrElse(transactionLimit)
      spent += cost
      records :+= ValidationWork(tx, cost, result.failed.toOption, recheck)
      result
    }
  }
}

private[mempool] object StagingValidation {
  case object Deferred extends Exception("staging validation budget exhausted; retry on next pool or state change")
}
