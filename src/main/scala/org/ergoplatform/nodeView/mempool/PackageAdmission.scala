package org.ergoplatform.nodeView.mempool

import org.ergoplatform.ErgoBox.BoxId
import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.{ProcessingOutcome, SortingOption}
import org.ergoplatform.nodeView.mempool.OrderedTxPool.WeightedTxId
import org.ergoplatform.nodeView.state.UtxoState
import org.ergoplatform.settings.{Algos, ErgoSettings}
import scorex.util.ModifierId

import scala.collection.mutable
import scala.util.{Failure, Success}

/** Admission policy for a child and its held ancestors. Rejection leaves the
  * live pool untouched; removals and insertion are committed together.
  */
private[mempool] final class PackageAdmission(initialPool: ErgoMemPool, work: StagingValidation)(implicit settings: ErgoSettings) {
  // Only cache metadata changes before commit; the live transaction pool remains atomic.
  private var memPool = initialPool
  private def pool = memPool.pool
  private def staging = memPool.staging
  private val stats = memPool.stats
  private val sortingOption = memPool.sortingOption
  private val nodeSettings = settings.nodeSettings
  private def getAll: Seq[UnconfirmedTransaction] = memPool.getAll
  private def feeFactor(tx: UnconfirmedTransaction): Int = memPool.feeFactor(tx)
  private def extractFee(tx: org.ergoplatform.modifiers.mempool.ErgoTransaction): Long =
    tx.outputs.filter(_.ergoTree == settings.chainSettings.monetary.feeProposition).map(_.value).sum

  // Bound the recursion used to assemble held ancestors.
  private val MaxPackageDepth = 8

  /**
    * A child failed single-tx admission with unresolved inputs. If every
    * missing input is created by a HELD staged ancestor, assemble
    * `{held ancestors…, child}`, re-validate any ancestor whose staged tip
    * has moved since (freshness gate keyed on tip identity, not height),
    * validate the child against the assembled overlay, then run package RBF
    * (R1 aggregate weight AND R2 absolute fee, both required).
    *
    * Returns `None` if this isn't a package situation (caller falls back to
    * plain orphan staging), `Some` if the package path fully handled the
    * child (admitted, RBF-rejected, or abandoned because a stale ancestor no
    * longer validates).
    */
  def process(unconfirmedTx: UnconfirmedTransaction,
                         missingInputs: IndexedSeq[BoxId],
                         validationStartTime: Long,
                         utxo: UtxoState): Option[(ErgoMemPool, ProcessingOutcome)] = {
    collectHeldAncestors(missingInputs, utxo).filter(_.nonEmpty).map { ancestorIds =>
      val tx = unconfirmedTx.transaction
      val tipId = utxo.stateContext.lastHeaderOpt.map(_.id)
      val ancestors = ancestorIds.flatMap(staging.get)
      val cachedChild = staging.get(tx.id).filter(e => e.isHeld && e.stagedTipId == tipId).map(_.utx)
      val knownCosts = (ancestors.map(_.utx) ++ cachedChild).map(_.lastCost)
      def decline(reason: Throwable): (ErgoMemPool, ProcessingOutcome) = {
        if (reason == StagingValidation.Deferred)
          memPool = memPool.stageOrphanIfEnabled(unconfirmedTx, missingInputs)
        memPool -> new ProcessingOutcome.Declined(reason, validationStartTime)
      }
      def holdChild(child: UnconfirmedTransaction): Unit = {
        memPool = memPool.stageHeldIfEnabled(child, OrderedTxPool.weighted(child.transaction, feeFactor(child))(
          settings.chainSettings.monetary).weight, tipId)
      }

      val allInputs = ancestors.flatMap(_.inputBoxIds) ++ tx.inputIds
      if (knownCosts.exists(_.isEmpty) || knownCosts.flatten.map(_.toLong).sum > nodeSettings.stagingMaxPackageCost) {
        decline(new Exception("package exceeds staging.maxPackageCost or lacks validated ancestor costs"))
      } else if (allInputs.map(Algos.encode).distinct.size != allInputs.size) {
        decline(new Exception("intra-package double spend"))
      } else {
        refreshAncestors(ancestorIds, utxo) match {
          case Left(error) => decline(error)
          case Right(members) =>
            val overlay = utxo.withUnconfirmedTransactions(getAll ++ members)
            val checked = cachedChild.filter(_.transaction.dataInputs.forall(i => overlay.boxById(i.boxId).isDefined))
              .flatMap(_.lastCost).map(Success(_)).getOrElse {
              work.validate(unconfirmedTx, recheck = staging.get(tx.id).exists(_.isHeld)) {
                MempoolValidation.checkSerialization(tx, utxo).flatMap { _ =>
                  overlay.validateWithCost(tx, utxo.stateContext.simplifiedUpcoming(), nodeSettings.maxTransactionCost, None)
                }
              }
            }
            checked match {
              case Failure(StagingValidation.Deferred) => decline(StagingValidation.Deferred)
              case Failure(error) => memPool.invalidate(unconfirmedTx) -> new ProcessingOutcome.Invalidated(error, validationStartTime)
              case Success(cost) =>
                val child = unconfirmedTx.withCost(cost)
                // Cache even on RBF/capacity/cost rejection; repeated children must not redo scripts.
                holdChild(child)
                commitPackage(members :+ child, ancestorIds, child, validationStartTime)
            }
        }
      }
    }
  }

  // Walk up from `inputs` collecting HELD staged ancestors that create them,
  // ancestors-first. `None` if a missing input has no held creator (a plain
  // orphan) or the walk exceeds `MaxPackageDepth`.
  private def collectHeldAncestors(inputs: IndexedSeq[BoxId], utxo: UtxoState): Option[Seq[ModifierId]] = {
    val order = mutable.ArrayBuffer.empty[ModifierId]
    val seen = mutable.HashSet.empty[ModifierId]

    def rec(boxId: BoxId, depth: Int): Boolean = {
      if (pool.outputs.contains(boxId) || utxo.boxById(boxId).isDefined) {
        true // resolvable externally - not part of the package
      } else if (depth > MaxPackageDepth) {
        false
      } else {
        staging.creatorOf(boxId).flatMap(staging.get) match {
          case Some(st) if st.isHeld =>
            if (seen.contains(st.txId)) {
              true // already collected (diamond dependency)
            } else if (seen.size + 1 >= nodeSettings.stagingMaxPackageTransactions) {
              false // count includes the child; depth alone does not bound fan-in
            } else if ({ seen += st.txId; !(st.inputBoxIds ++ st.dataInputBoxIds).forall(b => rec(b, depth + 1)) }) {
              false
            } else {
              seen += st.txId
              order += st.txId
              true
            }
          case _ => false // genuinely missing, or created by an orphan - not package-eligible
        }
      }
    }

    if (inputs.forall(b => rec(b, 0))) Some(order.toSeq) else None
  }

  // Persist each successful refresh immediately, including when later work is
  // deferred or admission declines. The next attempt resumes from that cache.
  private def refreshAncestors(ids: Seq[ModifierId], utxo: UtxoState): Either[Throwable, Seq[UnconfirmedTransaction]] = {
    val tipId = utxo.stateContext.lastHeaderOpt.map(_.id)
    var members = Vector.empty[UnconfirmedTransaction]
    for (id <- ids) {
      val entry = staging.get(id).get
      val checked = if (entry.stagedTipId == tipId) Success(entry.utx.lastCost.get)
      else {
        val overlay = utxo.withUnconfirmedTransactions(getAll ++ members)
        work.validate(entry.utx, recheck = true) {
          MempoolValidation.checkSerialization(entry.utx.transaction, utxo).flatMap { _ =>
            overlay.validateWithCost(entry.utx.transaction, utxo.stateContext.simplifiedUpcoming(), nodeSettings.maxTransactionCost, None)
          }
        }
      }
      checked match {
        case Failure(StagingValidation.Deferred) => return Left(StagingValidation.Deferred)
        case Failure(error) =>
          memPool = memPool.invalidate(entry.utx)
          return Left(error)
        case Success(cost) =>
          val fresh = entry.utx.withCost(cost)
          val priority = OrderedTxPool.weighted(fresh.transaction, feeFactor(fresh))(settings.chainSettings.monetary).weight
          memPool = memPool.stageHeldIfEnabled(fresh, priority, tipId)
          members :+= fresh
          if (members.map(_.lastCost.get.toLong).sum > nodeSettings.stagingMaxPackageCost)
            return Left(new Exception("package exceeds staging.maxPackageCost after refresh"))
      }
    }
    Right(members)
  }

  // Package RBF (conflict) or threshold (no conflict) gate, then atomic
  // multi-insert. `members` is ancestors-first, child last.
  private def commitPackage(members: Seq[UnconfirmedTransaction],
                            ancestorIds: Seq[ModifierId],
                            childUtx: UnconfirmedTransaction,
                            validationStartTime: Long): (ErgoMemPool, ProcessingOutcome) = {
    val pkgCost = members.map(_.lastCost.get.toLong).sum
    if (pkgCost > nodeSettings.stagingMaxPackageCost) {
      return memPool -> new ProcessingOutcome.Declined(new Exception("package exceeds staging.maxPackageCost"), validationStartTime)
    }

    val (pkgFee, pkgWeight) = aggregateFeeAndWeight(members)
    val internalOutputs = members.flatMap(_.transaction.outputs.map(b => Algos.encode(b.id))).toSet
    val externalInputs = members.flatMap(_.transaction.inputs.map(_.boxId)).filterNot(b => internalOutputs.contains(Algos.encode(b)))
    val conflicting = externalInputs.flatMap(pool.inputs.get).toSet

    packageAdmission(members, pkgFee, pkgWeight, conflicting) match {
      case Left(reason) =>
        memPool -> new ProcessingOutcome.Declined(new Exception(reason), validationStartTime)
      case Right(poolAfterConflicts) =>
        val newPool = members.foldLeft(poolAfterConflicts) { case (p, m) => p.put(m, feeFactor(m)) }
        val strippedStaging = (ancestorIds :+ childUtx.id).foldLeft(staging) { case (s, id) => s.remove(id)._1 }
        new ErgoMemPool(newPool, stats, sortingOption, strippedStaging) ->
          new ProcessingOutcome.Accepted(childUtx, validationStartTime, members.dropRight(1))
    }
  }

  private def packageAdmission(members: Seq[UnconfirmedTransaction],
                               pkgFee: Long,
                               pkgWeight: Long,
                               conflicting: Set[WeightedTxId]): Either[String, OrderedTxPool] = {
    val protectedIds = requiredAncestors(members)
    val afterConflicts: Either[String, OrderedTxPool] =
      if (conflicting.nonEmpty) {
        // Weigh + remove the WHOLE in-pool descendant closure of each conflict:
        // an incumbent's child spends its output, so evicting the incumbent
        // alone would strand the child. Aggregating the closure also folds in
        // the descendants' CPFP fee so R1/R2 are not under-counted.
        val closure = conflictClosure(conflicting.map(_.id))
        val (incFee, incWeight) = aggregateFeeAndWeight(closure)
        // R1: strictly higher aggregate weight. R2: strictly higher aggregate
        // absolute fee (the anti-pinning teeth). Both required.
        if (closure.exists(tx => protectedIds.contains(tx.id))) {
          Left("package replacement would remove a required ancestor")
        } else if (pkgWeight > incWeight && pkgFee > incFee) Right(pool.remove(closure))
        else Left("package RBF: does not beat conflicting incumbents")
      } else {
        Right(pool)
      }
    afterConflicts.flatMap(makeRoom(_, members, pkgWeight, protectedIds))
  }

  // Preserve all external ancestors, including data-input dependencies and
  // ancestors of ancestors, across both replacement and capacity eviction.
  private def requiredAncestors(members: Seq[UnconfirmedTransaction]): Set[ModifierId] = {
    val visited = mutable.HashSet.empty[ModifierId]
    val pending = mutable.Queue.empty[UnconfirmedTransaction]
    pending ++= members
    while (pending.nonEmpty) {
      val tx = pending.dequeue().transaction
      val dependencies = tx.inputIds ++ tx.dataInputs.map(_.boxId)
      dependencies.flatMap(pool.outputs.get).foreach { parent =>
        if (visited.add(parent.id)) pool.get(parent.id).foreach(pending.enqueue(_))
      }
    }
    visited.toSet
  }

  private def makeRoom(p: OrderedTxPool,
                       members: Seq[UnconfirmedTransaction],
                       pkgWeight: Long,
                       protectedIds: Set[ModifierId]): Either[String, OrderedTxPool] = {
    if (members.size > nodeSettings.mempoolCapacity) {
      Left("package larger than mempool capacity")
    } else {
      var result = p
      val candidates = p.orderedTransactions.toSeq.reverse.iterator
      while (result.size + members.size > nodeSettings.mempoolCapacity && candidates.hasNext) {
        val candidate = candidates.next()._2
        if (result.contains(candidate.id)) {
          val closure = conflictClosure(Set(candidate.id)).filter(tx => result.contains(tx.id))
          // Compare aggregate fee/factor on both sides, as in the conflict gate.
          val removable = closure.forall(tx => !protectedIds.contains(tx.id)) &&
            aggregateFeeAndWeight(closure)._2 < pkgWeight
          if (removable) result = result.remove(closure)
        }
      }
      if (result.size + members.size <= nodeSettings.mempoolCapacity) Right(result)
      else Left("package cannot make room without removing required or higher-priority transactions")
    }
  }

  // Include spenders and data-input readers so removing a creator does not
  // strand an existing dependent. Walk iteratively to avoid stack overflow.
  private lazy val dependants: Map[String, Seq[ModifierId]] = {
    pool.orderedTransactions.values.toSeq.flatMap { utx =>
      val tx = utx.transaction
      (tx.inputIds ++ tx.dataInputs.map(_.boxId)).map(id => Algos.encode(id) -> utx.id)
    }.groupBy(_._1).map { case (box, refs) => box -> refs.map(_._2).distinct }
  }

  private def conflictClosure(seedIds: Set[ModifierId]): Seq[UnconfirmedTransaction] = {
    val collected = mutable.LinkedHashMap.empty[ModifierId, UnconfirmedTransaction]
    val pending = mutable.Queue.empty[ModifierId]
    pending ++= seedIds.toSeq.sorted
    while (pending.nonEmpty) {
      val id = pending.dequeue()
      if (!collected.contains(id)) pool.get(id).foreach { utx =>
        collected.put(id, utx)
        utx.transaction.outputs.foreach { out =>
          pending ++= dependants.getOrElse(Algos.encode(out.id), Seq.empty)
        }
      }
    }
    collected.values.toSeq
  }

  // Aggregate fee and fee-per-factor weight across package members, per the
  // pool's own sortingOption (byte size or execution cost).
  private def aggregateFeeAndWeight(members: Seq[UnconfirmedTransaction]): (Long, Long) = {
    val totalFee = members.map(m => extractFee(m.transaction)).sum
    val totalFactor = sortingOption match {
      case SortingOption.FeePerByte => members.map(_.transaction.size.toLong).sum
      case SortingOption.FeePerCycle => members.map(m => feeFactor(m).toLong).sum
    }
    val weight = if (totalFactor <= 0) Long.MaxValue else totalFee * 1024L / totalFactor
    (totalFee, weight)
  }

}
