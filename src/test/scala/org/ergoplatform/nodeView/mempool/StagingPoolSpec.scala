package org.ergoplatform.nodeView.mempool

import akka.actor.ActorRef
import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.ErgoBox.BoxId
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.settings.Constants.TrueTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.core.network.{ConnectedPeer, ConnectionId, Incoming}
import scorex.crypto.authds.ADKey
import sigma.interpreter.ProverResult

import java.net.InetSocketAddress

class StagingPoolSpec extends AnyFlatSpec with Matchers {

  private def randomBoxId(): BoxId = ADKey @@ scorex.util.Random.randomBytes(32)

  private def emptyProof = ProverResult.empty

  private def mkUtx(inputIds: Seq[BoxId], outCount: Int = 1): UnconfirmedTransaction = {
    val inputs = inputIds.map(id => new Input(id, emptyProof)).toIndexedSeq
    val outs = (0 until outCount).map(_ => new ErgoBoxCandidate(1L, TrueTree, creationHeight = 0)).toIndexedSeq
    val tx = ErgoTransaction(inputs, outs)
    UnconfirmedTransaction(tx, None)
  }

  private def peer(port: Int, host: String = "127.0.0.1"): ConnectedPeer =
    ConnectedPeer(
      ConnectionId(new InetSocketAddress(host, port), new InetSocketAddress("127.0.0.1", 0), Incoming),
      ActorRef.noSender,
      None
    )

  private val caps = StagingCaps.default

  it should "stage and retrieve an orphan, indexed by its missing input" in {
    val pool = StagingPool.empty(caps)
    val missing = randomBoxId()
    val utx = mkUtx(Seq(missing))

    val Right(pool2) = pool.stageOrphan(utx, priority = 100L, missingInputs = Set(missing), source = None)

    pool2.contains(utx.id) shouldBe true
    pool2.get(utx.id).map(_.utx) shouldBe Some(utx)
    pool2.waitersOn(missing) shouldBe Seq(utx.id)
    pool2.totalBytes shouldBe utx.transaction.size.toLong
  }

  it should "reject a duplicate stage" in {
    val pool = StagingPool.empty(caps)
    val utx = mkUtx(Seq(randomBoxId()))
    val Right(pool2) = pool.stageOrphan(utx, 1L, Set.empty, None)

    pool2.stageOrphan(utx, 1L, Set.empty, None) shouldBe Left(StageReject.Duplicate)
  }

  it should "reject a tx larger than the whole byte budget" in {
    val tinyCaps = caps.copy(maxBytes = 1)
    val pool = StagingPool.empty(tinyCaps)
    val utx = mkUtx(Seq(randomBoxId()))

    pool.stageOrphan(utx, 1L, Set.empty, None) shouldBe Left(StageReject.TooLarge)
  }

  it should "enforce the per-peer count cap" in {
    val p = peer(1)
    val tightCaps = caps.copy(maxCountPerPeer = 1)
    val pool = StagingPool.empty(tightCaps)
    val utx1 = mkUtx(Seq(randomBoxId()))
    val utx2 = mkUtx(Seq(randomBoxId()))

    val Right(pool2) = pool.stageOrphan(utx1, 1L, Set.empty, Some(p))
    pool2.stageOrphan(utx2, 1L, Set.empty, Some(p)) shouldBe Left(StageReject.PerPeerCount)
    pool2.size shouldBe 1
  }

  it should "enforce the waiters-per-input cascade-bomb bound" in {
    val missing = randomBoxId()
    val tightCaps = caps.copy(maxWaitersPerInput = 1)
    val pool = StagingPool.empty(tightCaps)
    val utx1 = mkUtx(Seq(missing))
    val utx2 = mkUtx(Seq(missing, randomBoxId()))

    val Right(pool2) = pool.stageOrphan(utx1, 1L, Set(missing), None)
    pool2.stageOrphan(utx2, 1L, Set(missing), None) shouldBe Left(StageReject.WaitersFull)
  }

  it should "evict the oldest orphan to make room for a newcomer" in {
    val tinyCaps = caps.copy(maxCount = 1)
    val pool = StagingPool.empty(tinyCaps)
    val low = mkUtx(Seq(randomBoxId()))
    val high = mkUtx(Seq(randomBoxId()))

    val Right(pool2) = pool.stageOrphan(low, priority = 10L, Set.empty, None)
    val Right(pool3) = pool2.stageOrphan(high, priority = 20L, Set.empty, None)

    pool3.size shouldBe 1
    pool3.contains(high.id) shouldBe true
    pool3.contains(low.id) shouldBe false
  }

  it should "evict the oldest orphan despite its fabricated high priority" in {
    val incumbent = mkUtx(Seq(randomBoxId()))
    val newcomer = mkUtx(Seq(randomBoxId()))
    val Right(first) = StagingPool.empty(caps.copy(maxCount = 1))
      .stageOrphan(incumbent, Long.MaxValue, Set.empty, None)
    val Right(second) = first.stageOrphan(newcomer, 1L, Set.empty, None)
    second.contains(incumbent.id) shouldBe false
    second.contains(newcomer.id) shouldBe true
  }

  it should "share count and byte quotas across reconnects from the same host" in {
    val first = mkUtx(Seq(randomBoxId()))
    val second = mkUtx(Seq(randomBoxId()))
    val Right(countPool) = StagingPool.empty(caps.copy(maxCountPerPeer = 1))
      .stageOrphan(first, 1L, Set.empty, Some(peer(100)))
    countPool.stageOrphan(second, 1L, Set.empty, Some(peer(200))) shouldBe Left(StageReject.PerPeerCount)
    val Right(bytePool) = StagingPool.empty(caps.copy(maxBytesPerPeer = first.transaction.size))
      .stageOrphan(first, 1L, Set.empty, Some(peer(100)))
    bytePool.stageOrphan(second, 1L, Set.empty, Some(peer(200))) shouldBe Left(StageReject.PerPeerBytes)
  }

  it should "expire at the TTL boundary and release waiter and host quotas" in {
    val missing = randomBoxId()
    val tx = mkUtx(Seq(missing))
    val Right(staged) = StagingPool.empty(caps.copy(maxCountPerPeer = 1))
      .stageOrphan(tx, 1L, Set(missing), Some(peer(100)))
    val received = staged.get(tx.id).get.receivedAt
    staged.expire(received + 99L, 100L).contains(tx.id) shouldBe true
    val expired = staged.expire(received + 100L, 100L)
    expired.isEmpty shouldBe true
    expired.totalBytes shouldBe 0L
    expired.waitersOn(missing) shouldBe empty
    expired.peerCount(peer(200)) shouldBe 0
    expired.peerBytes(peer(200)) shouldBe 0L
  }

  it should "remove a staged tx and clean every index" in {
    val pool = StagingPool.empty(caps)
    val missing = randomBoxId()
    val utx = mkUtx(Seq(missing))
    val Right(pool2) = pool.stageOrphan(utx, 1L, Set(missing), None)

    val (pool3, removed) = pool2.remove(utx.id)
    removed.map(_.txId) shouldBe Some(utx.id)
    pool3.contains(utx.id) shouldBe false
    pool3.waitersOn(missing) shouldBe empty
    pool3.totalBytes shouldBe 0L
  }

  it should "prune staged txs whose input or data input was confirmed-and-consumed" in {
    val spentInput = randomBoxId()
    val spentDataInput = randomBoxId()
    val survivorInput = randomBoxId()

    val pool = StagingPool.empty(caps)
    val doomedByInput = mkUtx(Seq(spentInput))
    val doomedByDataInput = {
      val tx = ErgoTransaction(
        IndexedSeq(new Input(randomBoxId(), emptyProof)),
        IndexedSeq(org.ergoplatform.DataInput(spentDataInput)),
        IndexedSeq(new ErgoBoxCandidate(1L, TrueTree, creationHeight = 0))
      )
      UnconfirmedTransaction(tx, None)
    }
    val survivor = mkUtx(Seq(survivorInput))

    val Right(p1) = pool.stageOrphan(doomedByInput, 1L, Set.empty, None)
    val Right(p2) = p1.stageOrphan(doomedByDataInput, 1L, Set.empty, None)
    val Right(p3) = p2.stageOrphan(survivor, 1L, Set.empty, None)

    val (p4, pruned) = p3.pruneSpentInputs(Set(spentInput, spentDataInput))
    pruned.map(_.txId).toSet shouldBe Set(doomedByInput.id, doomedByDataInput.id)
    p4.contains(doomedByInput.id) shouldBe false
    p4.contains(doomedByDataInput.id) shouldBe false
    p4.contains(survivor.id) shouldBe true
  }

  it should "enforce the per-peer byte budget and release it on removal" in {
    val owner = peer(1)
    val other = peer(2, "127.0.0.2")
    val first = mkUtx(Seq(randomBoxId()))
    val second = mkUtx(Seq(randomBoxId()))
    val pool = StagingPool.empty(caps.copy(maxBytesPerPeer = first.transaction.size.toLong))
    val Right(p1) = pool.stageOrphan(first, 1L, Set.empty, Some(owner))
    p1.stageOrphan(second, 2L, Set.empty, Some(owner)) shouldBe Left(StageReject.PerPeerBytes)
    val Right(p2) = p1.stageOrphan(second, 2L, Set.empty, Some(other))
    p2.size shouldBe 2
    val withoutFirst = p2.remove(first.id)._1
    val Right(p3) = withoutFirst.stageOrphan(first, 1L, Set.empty, Some(owner))
    p3.totalBytes shouldBe first.transaction.size.toLong + second.transaction.size
  }

  it should "reject staging when the global count or byte budget is zero" in {
    val tx = mkUtx(Seq(randomBoxId()))
    StagingPool.empty(caps.copy(maxCount = 0)).stageOrphan(tx, 1L, Set.empty, None) shouldBe Left(StageReject.Full)
    StagingPool.empty(caps.copy(maxBytes = 0)).stageOrphan(tx, 1L, Set.empty, None) shouldBe Left(StageReject.TooLarge)
  }

  it should "reject negative resource limits when constructing caps" in {
    intercept[IllegalArgumentException](caps.copy(maxCount = -1))
    intercept[IllegalArgumentException](caps.copy(maxBytes = -1))
    intercept[IllegalArgumentException](caps.copy(maxCountPerPeer = -1))
    intercept[IllegalArgumentException](caps.copy(maxBytesPerPeer = -1))
    intercept[IllegalArgumentException](caps.copy(maxWaitersPerInput = -1))
  }
  it should "stage a held tx, indexed by the outputs it creates" in {
    val pool = StagingPool.empty(caps)
    val utx = mkUtx(Seq(randomBoxId()))

    val Right(pool2) = pool.stageHeld(utx, priority = 500L, source = None, stagedTipId = None)

    pool2.get(utx.id).map(_.kind) shouldBe Some(StagedKind.Held)
    pool2.creatorOf(utx.transaction.outputs.head.id) shouldBe Some(utx.id)
  }

  it should "reject a newcomer that would evict a higher-priority incumbent, leaving the pool unchanged" in {
    val tinyCaps = caps.copy(maxCount = 1)
    val pool = StagingPool.empty(tinyCaps)
    val incumbent = mkUtx(Seq(randomBoxId()))
    val newcomer = mkUtx(Seq(randomBoxId()))

    val Right(pool2) = pool.stageHeld(incumbent, priority = 20L, None, None)
    pool2.stageHeld(newcomer, priority = 10L, None, None) shouldBe Left(StageReject.Full)

    pool2.size shouldBe 1
    pool2.contains(incumbent.id) shouldBe true
  }

  it should "vet the full multi-victim set before mutating - a 2nd required victim outranking the newcomer aborts eviction untouched" in {
    // Two single-input incumbents exactly fill the byte budget; the (larger,
    // 2-input) newcomer needs BOTH evicted to fit. `b` (priority 50) beats
    // the newcomer (priority 10), so the whole eviction is aborted and the
    // pool is byte-for-byte unchanged - evicting only `a` is never applied.
    val a = mkUtx(Seq(randomBoxId()))
    val b = mkUtx(Seq(randomBoxId()))
    val newcomer = mkUtx(Seq(randomBoxId(), randomBoxId()))
    val tightCaps = caps.copy(maxBytes = (a.transaction.size + b.transaction.size).toLong)

    val pool = StagingPool.empty(tightCaps)
    val Right(pool2) = pool.stageHeld(a, priority = 5L, None, None)
    val Right(pool3) = pool2.stageHeld(b, priority = 50L, None, None)

    pool3.stageHeld(newcomer, priority = 10L, None, None) shouldBe Left(StageReject.Full)
    pool3.size shouldBe 2
    pool3.contains(a.id) shouldBe true
    pool3.contains(b.id) shouldBe true
  }

  it should "never let a fake-high-fee orphan evict a validated held entry" in {
    val tinyCaps = caps.copy(maxCount = 1)
    val pool = StagingPool.empty(tinyCaps)
    val held = mkUtx(Seq(randomBoxId()))
    val orphanIn = randomBoxId()
    val orphan = mkUtx(Seq(orphanIn))

    val Right(pool2) = pool.stageHeld(held, priority = 5L, None, None)
    // Orphan claims a massive fee, but is unvalidated -> must not outrank Held.
    pool2.stageOrphan(orphan, priority = Long.MaxValue, Set(orphanIn), None, None) shouldBe Left(StageReject.Full)
    pool2.size shouldBe 1
    pool2.contains(held.id) shouldBe true
  }

  // Regression (#4): when the budget fills, orphans are evicted before held
  // entries regardless of claimed fee.
  it should "evict orphans before held entries when the budget fills" in {
    val tinyCaps = caps.copy(maxCount = 2)
    val pool = StagingPool.empty(tinyCaps)
    val held = mkUtx(Seq(randomBoxId()))       // low real priority
    val o1In = randomBoxId()
    val orphan1 = mkUtx(Seq(o1In))             // fat claimed fee
    val orphan2 = mkUtx(Seq(randomBoxId()))    // fatter claimed fee (the newcomer)

    val Right(p1) = pool.stageHeld(held, priority = 5L, None, None)
    val Right(p2) = p1.stageOrphan(orphan1, priority = 50L, Set(o1In), None, None)
    // Pool full; a higher-ranked orphan newcomer evicts the lower orphan, never the held.
    val Right(p3) = p2.stageOrphan(orphan2, priority = 100L, Set(randomBoxId()), None, None)
    p3.size shouldBe 2
    p3.contains(held.id) shouldBe true
    p3.contains(orphan1.id) shouldBe false
    p3.contains(orphan2.id) shouldBe true
  }

  it should "preserve TTL and host quotas when refreshing a held entry" in {
    val tx = mkUtx(Seq(randomBoxId())).withCost(100)
    val Right(first) = StagingPool.empty(caps.copy(maxCountPerPeer = 1)).stageHeld(tx, 1L, Some(peer(100)), None)
    val Right(refreshed) = first.stageHeld(tx.withCost(200), 2L, Some(peer(200)), None)
    refreshed.peerCount(peer(300)) shouldBe 1
    refreshed.peerBytes(peer(300)) shouldBe tx.transaction.size.toLong
    val received = first.get(tx.id).get.receivedAt
    refreshed.get(tx.id).get.receivedAt shouldBe received
    val expired = refreshed.expire(received + 100L, 100L)
    expired.creatorOf(tx.transaction.outputs.head.id) shouldBe None
    expired.peerCount(peer(300)) shouldBe 0
  }

  it should "rotate retries without extending the eviction age or expiry" in {
    val a = mkUtx(Seq(randomBoxId()))
    val b = mkUtx(Seq(randomBoxId()))
    val Right(first) = StagingPool.empty(caps).stageHeld(a, 1L, None, None)
    val Right(second) = first.stageHeld(b, 1L, None, None)
    val retried = second.markRetried(a.id)
    retried.byTxId.values.toSeq.sortBy(e => (e.retryOrder, e.seq)).head.txId shouldBe b.id
    retried.get(a.id).get.receivedAt shouldBe first.get(a.id).get.receivedAt
    retried.get(a.id).get.seq shouldBe first.get(a.id).get.seq
  }

}
