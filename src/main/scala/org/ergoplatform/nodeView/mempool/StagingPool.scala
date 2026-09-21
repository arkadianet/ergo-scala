package org.ergoplatform.nodeView.mempool

import org.ergoplatform.ErgoBox.BoxId
import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import org.ergoplatform.settings.Algos
import scorex.core.network.ConnectedPeer
import scorex.util.ModifierId

import scala.annotation.tailrec

/**
  * Admission status of an entry in the staging store.
  */
sealed trait StagedKind
object StagedKind {
  /** Child-before-parent: inputs not yet resolvable. */
  case object Orphan extends StagedKind
  /** Parent-before-child: fully valid but lost an admission gate. */
  case object Held extends StagedKind
}

/**
  * A held/orphan entry in [[StagingPool]]. Never broadcast or pooled until it
  * resolves through `ErgoMemPool`'s orphan/package logic.
  *
  * @param utx           the transaction; fully validated (cost known) for Held, as-is for Orphan
  * @param kind           Orphan or Held
  * @param priority      eviction/RBF weight - real weight for Held; ignored for Orphan
  * @param missingInputs hex-encoded box ids not yet resolvable; empty for Held
  * @param source        peer that delivered the tx (None if submitted via API)
  * @param stagedTipId   best header id at staging time, for the resolve-time freshness gate
  * @param seq           monotonic insertion sequence, FIFO eviction tiebreak
  */
case class StagedTx(utx: UnconfirmedTransaction,
                    kind: StagedKind,
                    priority: Long,
                    missingInputs: Set[String],
                    source: Option[ConnectedPeer],
                    stagedTipId: Option[ModifierId],
                    seq: Long,
                    receivedAt: Long = System.currentTimeMillis(),
                    retryOrder: Long = 0L) {
  def txId: ModifierId = utx.id
  def size: Int = utx.transaction.size
  def isHeld: Boolean = kind == StagedKind.Held
  def isOrphan: Boolean = kind == StagedKind.Orphan

  /** Eviction rank, higher = keep longer. Validated Held entries always
    * outrank unvalidated Orphans (whose claimed fee is unverified), so orphans
    * are evicted first regardless of claimed fee; ties break on real priority. */
  def evictionRank: (Int, Long) = ((if (isHeld) 1 else 0), if (isHeld) priority else 0L)
  def inputBoxIds: IndexedSeq[BoxId] = utx.transaction.inputs.map(_.boxId)
  def outputBoxIds: IndexedSeq[BoxId] = utx.transaction.outputs.map(_.id)
  def dataInputBoxIds: IndexedSeq[BoxId] = utx.transaction.dataInputs.map(_.boxId)
}

/**
  * Hard capacity/fairness bounds for [[StagingPool]].
  */
case class StagingCaps(maxCount: Int,
                       maxBytes: Long,
                       maxCountPerPeer: Int,
                       maxBytesPerPeer: Long,
                       maxWaitersPerInput: Int) {
  require(maxCount >= 0 && maxBytes >= 0, "staging capacity must be non-negative")
  require(maxCountPerPeer >= 0 && maxBytesPerPeer >= 0, "staging peer limits must be non-negative")
  require(maxWaitersPerInput >= 0, "staging waiter limit must be non-negative")
}

object StagingCaps {
  val default: StagingCaps = StagingCaps(
    maxCount = 2048,
    maxBytes = 8 * 1024 * 1024,
    maxCountPerPeer = 128,
    maxBytesPerPeer = 1024 * 1024,
    maxWaitersPerInput = 64
  )
}

/**
  * Why a staging insert was refused. Never fatal - the tx is simply not held,
  * pool state is unchanged.
  */
sealed trait StageReject
object StageReject {
  case object Duplicate extends StageReject
  case object TooLarge extends StageReject
  case object PerPeerCount extends StageReject
  case object PerPeerBytes extends StageReject
  case object WaitersFull extends StageReject
  case object Full extends StageReject
}

/**
  * Bounded holding store for orphans (child-before-parent) and held txs
  * (parent-before-child that lost the fee/capacity/double-spend gate).
  * Immutable. Runs no validation and never
  * gossips - wiring/broadcast decisions live in `ErgoMemPool` /
  * `ErgoNodeViewHolder`.
  */
class StagingPool private(val byTxId: Map[ModifierId, StagedTx],
                          private val waitingOnInput: Map[String, Seq[ModifierId]],
                          private val byOutput: Map[String, ModifierId],
                          val totalBytes: Long,
                          private val perPeerCount: Map[String, Int],
                          private val perPeerBytes: Map[String, Long],
                          val caps: StagingCaps,
                          private val seqCounter: Long) {

  // Remote ports change on reconnect. Unresolved addresses use their host string without DNS lookup.
  private def host(peer: ConnectedPeer): String = {
    val address = peer.connectionId.remoteAddress
    Option(address.getAddress).map(_.getHostAddress).getOrElse(address.getHostString)
  }

  /** Rotate unsuccessful retries without resetting FIFO eviction age or TTL. */
  def markRetried(id: ModifierId): StagingPool = byTxId.get(id).map { entry =>
    new StagingPool(byTxId.updated(id, entry.copy(retryOrder = seqCounter + 1)), waitingOnInput, byOutput,
      totalBytes, perPeerCount, perPeerBytes, caps, seqCounter + 1)
  }.getOrElse(this)

  def expire(now: Long, ttlMillis: Long): StagingPool =
    byTxId.values.filter(e => now - e.receivedAt >= ttlMillis)
      .foldLeft(this)((p, e) => p.remove(e.txId)._1)

  def size: Int = byTxId.size
  def isEmpty: Boolean = byTxId.isEmpty
  def contains(id: ModifierId): Boolean = byTxId.contains(id)
  def get(id: ModifierId): Option[StagedTx] = byTxId.get(id)
  def peerCount(peer: ConnectedPeer): Int = perPeerCount.getOrElse(host(peer), 0)
  def peerBytes(peer: ConnectedPeer): Long = perPeerBytes.getOrElse(host(peer), 0L)

  /** Staged tx ids waiting on `boxId` (orphans whose missing input is `boxId`). */
  def waitersOn(boxId: BoxId): Seq[ModifierId] = waitingOnInput.getOrElse(Algos.encode(boxId), Seq.empty)

  /** The staged tx (either kind) that creates `boxId`, if any. */
  def creatorOf(boxId: BoxId): Option[ModifierId] = byOutput.get(Algos.encode(boxId))

  /**
    * Stage an orphan (unresolved inputs). The priority argument is ignored:
    * an unvalidated claimed fee must not buy protection from eviction.
    */
  def stageOrphan(utx: UnconfirmedTransaction,
                  priority: Long,
                  missingInputs: Set[BoxId],
                  source: Option[ConnectedPeer],
                  stagedTipId: Option[ModifierId] = None): Either[StageReject, StagingPool] = {
    val entry = StagedTx(utx, StagedKind.Orphan, priority, missingInputs.map(Algos.encode), source, stagedTipId, seqCounter)
    insert(entry)
  }

  /**
    * Stage a held tx (validated, lost an admission gate). `priority` is the
    * caller-computed real weight (fee per the configured sorting option).
    */
  def stageHeld(utx: UnconfirmedTransaction,
               priority: Long,
               source: Option[ConnectedPeer],
               stagedTipId: Option[ModifierId] = None): Either[StageReject, StagingPool] = {
    val entry = StagedTx(utx, StagedKind.Held, priority, Set.empty, source, stagedTipId, seqCounter)
    byTxId.get(utx.id) match {
      case Some(previous) =>
        // Refresh cost/tip or promote an orphan without extending retention or changing ownership.
        val refreshed = entry.copy(seq = previous.seq, receivedAt = previous.receivedAt,
          source = previous.source, retryOrder = previous.retryOrder)
        Right(remove(utx.id)._1.commit(refreshed))
      case None => insert(entry)
    }
  }

  private def insert(entry: StagedTx): Either[StageReject, StagingPool] = {
    if (byTxId.contains(entry.txId)) {
      Left(StageReject.Duplicate)
    } else if (entry.size.toLong > caps.maxBytes) {
      Left(StageReject.TooLarge)
    } else {
      for {
        _ <- checkPeerCaps(entry)
        _ <- checkWaiterCaps(entry)
        victims <- pickVictims(entry)
      } yield {
        val afterEviction = victims.foldLeft(this)((p, id) => p.remove(id)._1)
        afterEviction.commit(entry)
      }
    }
  }

  // Per-peer fairness - refuse rather than evict another peer's entries, so
  // one peer cannot grief the shared budget.
  private def checkPeerCaps(entry: StagedTx): Either[StageReject, Unit] = entry.source match {
    case Some(p) if peerCount(p) + 1 > caps.maxCountPerPeer => Left(StageReject.PerPeerCount)
    case Some(p) if peerBytes(p) + entry.size > caps.maxBytesPerPeer => Left(StageReject.PerPeerBytes)
    case _ => Right(())
  }

  // Fan-out (cascade-bomb) bound: no missing input may exceed its waiter cap.
  private def checkWaiterCaps(entry: StagedTx): Either[StageReject, Unit] = {
    val overflowing = entry.missingInputs.exists(m => waitingOnInput.getOrElse(m, Seq.empty).size >= caps.maxWaitersPerInput)
    if (overflowing) Left(StageReject.WaitersFull) else Right(())
  }

  // Decide the FULL eviction victim set before any mutation: walk incumbents
  // lowest-priority-first, accumulating freed count/bytes until the newcomer
  // fits. If a required victim outranks the newcomer, reject with the pool
  // untouched - never partial-evict-then-reject.
  private def pickVictims(entry: StagedTx): Either[StageReject, Seq[ModifierId]] = {
    val sz = entry.size.toLong
    val overCount = byTxId.size + 1 > caps.maxCount
    val overBytes = totalBytes + sz > caps.maxBytes
    if (!overCount && !overBytes) {
      Right(Seq.empty)
    } else {
      // Kind-tiered rank: orphans (unvalidated, unverified fee) are evicted
      // before any validated held entry, so a fake-high-fee orphan can never
      // push out an honest held tx.
      val ranked = byTxId.values.toList.sortBy(e => (e.evictionRank, e.seq))

      @tailrec
      def loop(remaining: List[StagedTx], victims: Vector[ModifierId], freedCount: Int, freedBytes: Long): Either[StageReject, Seq[ModifierId]] = {
        val stillOverCount = (byTxId.size - freedCount) + 1 > caps.maxCount
        val stillOverBytes = (totalBytes - freedBytes) + sz > caps.maxBytes
        if (!stillOverCount && !stillOverBytes) {
          Right(victims)
        } else remaining match {
          case Nil => Left(StageReject.Full)
          case head :: tail =>
            if (rankOrdering.gt(head.evictionRank, entry.evictionRank)) {
              Left(StageReject.Full)
            } else {
              loop(tail, victims :+ head.txId, freedCount + 1, freedBytes + head.size)
            }
        }
      }

      loop(ranked, Vector.empty, 0, 0L)
    }
  }

  private val rankOrdering: Ordering[(Int, Long)] = Ordering[(Int, Long)]

  private def commit(entry: StagedTx): StagingPool = {
    val newWaiting = entry.missingInputs.foldLeft(waitingOnInput) { (m, box) =>
      m.updated(box, m.getOrElse(box, Seq.empty) :+ entry.txId)
    }
    val newByOutput = entry.outputBoxIds.foldLeft(byOutput) { (m, box) =>
      m.updated(Algos.encode(box), entry.txId)
    }
    val (newPeerCount, newPeerBytes) = entry.source match {
      case Some(p) => (perPeerCount.updated(host(p), peerCount(p) + 1), perPeerBytes.updated(host(p), peerBytes(p) + entry.size))
      case None => (perPeerCount, perPeerBytes)
    }
    new StagingPool(
      byTxId.updated(entry.txId, entry),
      newWaiting,
      newByOutput,
      totalBytes + entry.size,
      newPeerCount,
      newPeerBytes,
      caps,
      seqCounter + 1
    )
  }

  /** Remove a staged tx and clean every index. Returns the removed entry, if any. */
  def remove(id: ModifierId): (StagingPool, Option[StagedTx]) = {
    byTxId.get(id) match {
      case None => (this, None)
      case Some(entry) =>
        val newWaiting = entry.missingInputs.foldLeft(waitingOnInput) { (m, box) =>
          m.get(box) match {
            case Some(waiters) =>
              val filtered = waiters.filterNot(_ == id)
              if (filtered.isEmpty) m - box else m.updated(box, filtered)
            case None => m
          }
        }
        val newByOutput = entry.outputBoxIds.foldLeft(byOutput) { (m, box) =>
          val key = Algos.encode(box)
          if (m.get(key).contains(id)) m - key else m
        }
        val (newPeerCount, newPeerBytes) = entry.source match {
          case Some(p) =>
            val c = peerCount(p) - 1
            val b = peerBytes(p) - entry.size
            (if (c <= 0) perPeerCount - host(p) else perPeerCount.updated(host(p), c),
             if (b <= 0) perPeerBytes - host(p) else perPeerBytes.updated(host(p), b))
          case None => (perPeerCount, perPeerBytes)
        }
        val newPool = new StagingPool(
          byTxId - id, newWaiting, newByOutput, math.max(0L, totalBytes - entry.size),
          newPeerCount, newPeerBytes, caps, seqCounter
        )
        (newPool, Some(entry))
    }
  }

  /**
    * Drop every staged tx that spends (as a regular input) or reads (as a
    * data input) any box in `spent` - that box was confirmed-and-consumed
    * on-chain, so the tx can never be admitted again.
    */
  def pruneSpentInputs(spent: Set[BoxId]): (StagingPool, Seq[StagedTx]) = {
    if (spent.isEmpty || isEmpty) {
      (this, Seq.empty)
    } else {
      val spentHex = spent.map(Algos.encode)
      val doomed = byTxId.values.filter { e =>
        (e.inputBoxIds ++ e.dataInputBoxIds).exists(b => spentHex.contains(Algos.encode(b)))
      }.map(_.txId).toSeq
      doomed.foldLeft((this, Seq.empty[StagedTx])) { case ((pool, removed), id) =>
        val (p2, r) = pool.remove(id)
        (p2, removed ++ r.toSeq)
      }
    }
  }
}

object StagingPool {
  def empty(caps: StagingCaps): StagingPool =
    new StagingPool(Map.empty, Map.empty, Map.empty, 0L, Map.empty, Map.empty, caps, 0L)
}
