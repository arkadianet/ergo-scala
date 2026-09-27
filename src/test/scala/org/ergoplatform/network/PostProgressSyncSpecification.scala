package org.ergoplatform.network

import akka.actor.{ActorRef, Cancellable, Props}
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import org.ergoplatform.consensus.Equal
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.network.message.{Message, ModifiersData, ModifiersSpec}
import org.ergoplatform.network.message.inputblocks.{OrderingBlockAnnouncement, OrderingBlockAnnouncementMessageSpec}
import org.ergoplatform.network.peer.PeerInfo
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.ModifiersFromRemote
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoSyncInfo, ErgoSyncInfoMessageSpec, ErgoSyncInfoV2}
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.wallet.utils.FileUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import scorex.core.network.{ConnectedPeer, DeliveryTracker, SendToPeer, SendToPeers}
import scorex.core.network.NetworkController.ReceivableMessages.SendToNetwork
import scorex.testkit.utils.AkkaFixture

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class PostProgressSyncSpecification extends AnyPropSpec with Matchers with FileUtils {
  import org.ergoplatform.utils.ErgoNodeTestConstants.settings
  import org.ergoplatform.utils.generators.ChainGenerator._
  import org.ergoplatform.utils.generators.ConnectedPeerGenerators._

  class Synchronizer(nc: ActorRef, vh: ActorRef, s: ErgoSettings,
                     tracker: ErgoSyncTracker, delivery: DeliveryTracker,
                     meshDelay: FiniteDuration = 300.millis)(implicit ec: ExecutionContext)
    extends ErgoNodeViewSynchronizer(nc, vh, ErgoSyncInfoMessageSpec, s, tracker, delivery) {
    override protected def meshRefreshDelay: FiniteDuration = meshDelay
    def reply(peer: ConnectedPeer, sync: ErgoSyncInfo): Unit = sendSyncToPeer(peer, sync)
    def periodic(history: ErgoHistory): Unit = sendSync(history)
  }

  class Fixture(meshDelay: FiniteDuration = 300.millis) extends AkkaFixture {
    implicit val ec: ExecutionContext = system.dispatcher
    val config = settings.copy(directory = createTempDir.getAbsolutePath)
    val history = ErgoHistory.readOrGenerate(config)(null)
    val blocks = genChain(8, history).toVector
    applyChain(history, blocks.take(3))
    val nc = TestProbe()
    val vh = TestProbe()
    val tracker = ErgoSyncTracker(config.scorexSettings.network)
    val delivery = DeliveryTracker.empty(config)
    val actor = TestActorRef[Synchronizer](Props(new Synchronizer(nc.ref, vh.ref, config, tracker, delivery, meshDelay)))
    actor ! ChangedHistory(history)
    actor ! ChangedMempool(ErgoMemPool.empty(config))
    nc.receiveWhile(100.millis) { case _ => () }
    vh.receiveWhile(100.millis) { case _ => () }

    def peer(height: Int = history.fullBlockHeight, subBlocks: Boolean = true,
             stateType: StateType = StateType.Utxo): ConnectedPeer = {
      val spec = PeerSpec(config.scorexSettings.network.agentName,
        if (subBlocks) Version.SubblocksVersion else Version(4, 0, 0),
        config.scorexSettings.network.nodeName, None,
        Seq(ModePeerFeature(stateType, verifyingTransactions = true, None, -1)))
      val p = ConnectedPeer(connectionIdGen.sample.get, TestProbe().ref,
        Some(PeerInfo(spec, System.currentTimeMillis())))
      tracker.updateStatus(p, Equal, Some(height))
      p
    }

    def request(headers: Seq[Header], p: ConnectedPeer): Unit = {
      headers.foreach(h => delivery.setRequested(Header.modifierTypeId, h.id, p)(_ => Cancellable.alreadyCancelled))
      val data = ModifiersData(Header.modifierTypeId, headers.map(h => h.id -> h.bytes).toMap)
      actor ! Message(ModifiersSpec, Left(ModifiersSpec.toBytes(data)), Some(p))
      if (!headers.forall(h => history.contains(h.id))) vh.expectMsgType[ModifiersFromRemote]
    }

    def applyHeaders(headers: Seq[Header]): Unit = {
      headers.foreach { h =>
        history.append(h).get
        actor ! SyntacticallySuccessfulModifier(Header.modifierTypeId, h.id)
        actor ! ChangedHistory(history)
      }
    }

    def syncs(duration: FiniteDuration = 700.millis): Seq[(Set[ConnectedPeer], ErgoSyncInfoV2)] = {
      nc.receiveWhile(duration) { case m => m }.collect {
        case s: SendToNetwork if s.message.spec == ErgoSyncInfoMessageSpec =>
          val peers = s.sendingStrategy match {
            case SendToPeer(p) => Set(p)
            case SendToPeers(ps) => ps.toSet
            case other => fail(s"Unexpected sync strategy $other")
          }
          peers -> s.message.data.get.asInstanceOf[ErgoSyncInfoV2]
      }
    }
  }

  private def fixture(test: Fixture => Unit, meshDelay: FiniteDuration = 300.millis): Unit = {
    val f = new Fixture(meshDelay)
    try test(f) finally {
      Await.result(f.system.terminate(), Duration.Inf)
      f.history.closeStorage()
    }
  }

  property("ordering announcement supplier receives the applied best header") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      peer(height = 100)
      val block = blocks(3)
      val announcement = OrderingBlockAnnouncement(OrderingBlockAnnouncement.CurrentVersion,
        block.header, Seq.empty, Seq.empty, block.extension.fields)
      actor ! Message(OrderingBlockAnnouncementMessageSpec,
        Left(OrderingBlockAnnouncementMessageSpec.toBytes(announcement)), Some(supplier))
      vh.expectMsgType[ProcessOrderingBlock].oba.header.id shouldBe block.header.id
      syncs(150.millis) shouldBe empty
      applyHeaders(Seq(block.header))
      val sent = syncs()
      sent.map(_._1) shouldBe Seq(Set(supplier))
      sent.head._2.lastHeaders.head.id shouldBe block.header.id
    }
  }

  property("full block application refreshes nearby mesh peers only") {
    fixture { f =>
      import f._
      val source = peer(height = 100)
      val nearby = peer()
      peer(height = 100)
      peer(subBlocks = false)
      peer(stateType = StateType.Digest)
      request(Seq(blocks(3).header), source)
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(3).header))
      actor ! RemoteBlockApplied(blocks(3).header, Seq.empty)
      val sent = syncs()
      sent.flatMap(_._1).toSet shouldBe Set(source, nearby)
      sent.size shouldBe 2
      sent.foreach(_._2.lastHeaders.head.id shouldBe blocks(3).header.id)
    }
  }

  property("local block notification refreshes mesh only when sections are held") {
    fixture { f =>
      import f._
      val nearby = peer()
      val block = blocks(3)
      applyHeaders(Seq(block.header))
      actor ! LocalBlockApplied(block.header, Seq.empty)
      syncs() shouldBe empty
      block.mandatoryBlockSections.foreach(section => history.append(section).get)
      history.getFullBlock(block.header).isDefined shouldBe true
      actor ! LocalBlockApplied(block.header, Seq.empty)
      val sent = syncs()
      sent.map(_._1) shouldBe Seq(Set(nearby))
      sent.head._2.lastHeaders.head.id shouldBe block.header.id
    }
  }

  property("header progress alone does not refresh mesh peers") {
    fixture { f =>
      import f._
      val source = peer(height = 100)
      val nearby = peer()
      request(Seq(blocks(3).header), source)
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(3).header))
      syncs(1500.millis).flatMap(_._1) should not contain nearby
    }
  }

  property("requested batch keeps its immediate reply and sends one fresh trailing status") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val headers = blocks.slice(3, 7).map(_.header)
      request(headers, supplier)
      val first = nc.expectMsgType[SendToNetwork]
      first.message.data.get.asInstanceOf[ErgoSyncInfoV2].lastHeaders.head.id shouldBe blocks(2).header.id
      val sentAt = tracker.statuses(supplier).lastSyncSentTime.get
      applyHeaders(headers)
      val sent = syncs()
      sent.size shouldBe 1
      sent.head._2.lastHeaders.head.id shouldBe headers.last.id
      tracker.statuses(supplier).lastSyncSentTime.get - sentAt should be >= 250L
    }
  }

  property("syntactically invalid header sends no SyncInfo") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      peer()
      val invalid = blocks(3).header.copy(height = blocks(3).header.height + 7)
      delivery.setRequested(Header.modifierTypeId, invalid.id, supplier)(_ => Cancellable.alreadyCancelled)
      val data = ModifiersData(Header.modifierTypeId, Map(invalid.id -> invalid.bytes))
      actor ! Message(ModifiersSpec, Left(ModifiersSpec.toBytes(data)), Some(supplier))
      history.append(invalid).isFailure shouldBe true
      actor ! SyntacticallyFailedModification(Header.modifierTypeId, invalid.id,
        new IllegalArgumentException("invalid header height"))
      syncs() shouldBe empty
    }
  }

  property("view holder rejection adds no refresh after the immediate reply") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val header = blocks(3).header
      request(Seq(header), supplier)
      nc.expectMsgType[SendToNetwork]
      actor ! SyntacticallyFailedModification(Header.modifierTypeId, header.id,
        new IllegalArgumentException("rejected header"))
      syncs() shouldBe empty
    }
  }

  property("burst refresh uses live history and rechecks an intervening base send") {
    fixture({ f =>
      import f._
      val supplier = peer()
      val nearby = peer()
      val emissions = new java.util.concurrent.ConcurrentLinkedQueue[(Long, SendToNetwork)]()
      nc.setAutoPilot(new TestActor.AutoPilot {
        override def run(sender: ActorRef, message: Any): TestActor.AutoPilot = {
          message match {
            case sent: SendToNetwork if sent.message.spec == ErgoSyncInfoMessageSpec =>
              emissions.add(System.nanoTime() -> sent)
            case _ => ()
          }
          TestActor.KeepRunning
        }
      })
      request(Seq(blocks(3).header, blocks(4).header), supplier)
      nc.expectMsgType[SendToNetwork]
      actor.underlyingActor.reply(nearby, history.syncInfoV2(full = true))
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(3).header))
      actor ! RemoteBlockApplied(blocks(3).header, Seq.empty)
      // A base reply can move the deadline while the refresh is pending.
      syncs(30.millis) shouldBe empty
      actor.underlyingActor.reply(supplier, history.syncInfoV2(full = true))
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(4).header))
      actor ! RemoteBlockApplied(blocks(4).header, Seq.empty)
      val trailing = syncs(1600.millis)
      trailing.size shouldBe 2
      trailing.flatMap(_._1).toSet shouldBe Set(supplier, nearby)
      trailing.foreach(_._2.lastHeaders.head.id shouldBe blocks(4).header.id)
      val recorded = Vector.newBuilder[(Long, SendToNetwork)]
      val iterator = emissions.iterator()
      while (iterator.hasNext) recorded += iterator.next()
      val all = recorded.result()
      Seq(supplier, nearby).foreach { remote =>
        val times = all.collect {
          case (time, sent) if sent.sendingStrategy == SendToPeer(remote) => time
        }
        times.size should be >= 2
        (times.last - times(times.size - 2)).nanos.toMillis should be >= 250L
      }
    }, meshDelay = 1.second)
  }

  property("genuinely distant peers remain excluded from local input-block delivery") {
    fixture { f =>
      import f._
      val distant = peer(height = 100)
      val nearby = peer()
      val header = blocks(3).header
      history.applyInputBlock(org.ergoplatform.subblocks.InputBlockAnnouncement(
        org.ergoplatform.subblocks.InputBlockAnnouncement.initialMessageVersion,
        header, org.ergoplatform.mining.InputBlockFields.empty, None))
      actor ! NewBestInputBlock(Some(header.id), local = true)
      val sent = nc.expectMsgType[SendToNetwork]
      sent.message.spec shouldBe org.ergoplatform.network.message.inputblocks.InputBlockMessageSpec
      sent.sendingStrategy match {
        case SendToPeers(peers) =>
          peers should contain(nearby)
          peers should not contain distant
        case other => fail(s"Unexpected relay strategy $other")
      }
    }
  }

  property("disconnect cancels a pending trailing refresh") {
    fixture { f =>
      import f._
      val supplier = peer()
      request(Seq(blocks(3).header), supplier)
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(3).header))
      actor ! DisconnectedPeer(supplier)
      syncs() shouldBe empty
    }
  }

  property("header IBD keeps one immediate pipeline reply and no refresh") {
    fixture { f =>
      import f._
      val cold = ErgoHistory.readOrGenerate(config.copy(directory = createTempDir.getAbsolutePath))(null)
      try {
        val headers = genHeaderChain(5, None, diffBitsOpt = None, useRealTs = false).headers
        headers.take(3).foreach(h => cold.append(h).get)
        cold.isHeadersChainSynced shouldBe false
        actor ! ChangedHistory(cold)
        val supplier = peer(height = 100)
        peer(height = 0)
        request(Seq(headers(3)), supplier)
        val first = nc.expectMsgType[SendToNetwork]
        first.message.data.get.asInstanceOf[ErgoSyncInfoV2].lastHeaders.head.id shouldBe headers(2).id
        cold.append(headers(3)).get
        actor ! SyntacticallySuccessfulModifier(Header.modifierTypeId, headers(3).id)
        cold.isHeadersChainSynced shouldBe false
        syncs() shouldBe empty
        actor ! ChangedHistory(history)
      } finally cold.closeStorage()
    }
  }

  property("IBD score tracking suppresses a lighter fork after headers become synced") {
    fixture { f =>
      import f._
      val cold = ErgoHistory.readOrGenerate(config.copy(directory = createTempDir.getAbsolutePath))(null)
      try {
        val headers = genHeaderChain(6, None, diffBitsOpt = None, useRealTs = false).headers
        actor ! ChangedHistory(cold)
        headers.foreach { h =>
          cold.append(h).get
          actor ! SyntacticallySuccessfulModifier(Header.modifierTypeId, h.id)
        }
        cold.isHeadersChainSynced shouldBe false
        // Bootstrap completion can set the flag independently of a new best header.
        cold.setHeadersChainSynced()
        val supplier = peer(height = 100)
        val fork = genHeaderChain(1, Some(headers(3)), diffBitsOpt = None,
          useRealTs = false).headers.last
        request(Seq(fork), supplier)
        nc.expectMsgType[SendToNetwork]
        cold.append(fork).get
        actor ! SyntacticallySuccessfulModifier(Header.modifierTypeId, fork.id)
        syncs() shouldBe empty
        actor ! ChangedHistory(history)
      } finally cold.closeStorage()
    }
  }

  property("lighter fork keeps its pipeline reply without a refresh") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      peer()
      val fork = genHeaderChain(1, Some(blocks.head.header), diffBitsOpt = None,
        useRealTs = false).headers.last
      request(Seq(fork), supplier)
      applyHeaders(Seq(fork))
      val sent = syncs()
      sent.size shouldBe 1
      sent.head._1 shouldBe Set(supplier)
      sent.head._2.lastHeaders.head.id shouldBe blocks(2).header.id
    }
  }

  property("SyncInfo continuation keeps its immediate reply and adds the applied tip") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val h = blocks(3).header
      val sync = ErgoSyncInfoV2(Seq(h))
      actor ! Message(ErgoSyncInfoMessageSpec, Left(ErgoSyncInfoMessageSpec.toBytes(sync)), Some(supplier))
      vh.expectMsgType[ModifiersFromRemote]
      val initial = syncs(100.millis)
      initial.size shouldBe 1
      initial.head._2.lastHeaders.head.id shouldBe blocks(2).header.id
      val sentAt = tracker.statuses(supplier).lastSyncSentTime.get
      applyHeaders(Seq(h))
      val sent = syncs()
      sent.size shouldBe 1
      sent.head._1 shouldBe Set(supplier)
      sent.head._2.lastHeaders.head.id shouldBe h.id
      tracker.statuses(supplier).lastSyncSentTime.get - sentAt should be >= 250L
    }
  }

  property("full block event deduplicates a tip already refreshed to its supplier") {
    fixture { f =>
      import f._
      val supplier = peer()
      request(Seq(blocks(3).header), supplier)
      nc.expectMsgType[SendToNetwork]
      applyHeaders(Seq(blocks(3).header))
      syncs()
      actor ! RemoteBlockApplied(blocks(3).header, Seq.empty)
      syncs() shouldBe empty
    }
  }

  property("Fork peers at equal height stop their exchange on a short link") {
    fixture { f =>
      import f._
      val otherHistory = ErgoHistory.readOrGenerate(config.copy(directory = createTempDir.getAbsolutePath))(null)
      val leftHistory = ErgoHistory.readOrGenerate(config.copy(directory = createTempDir.getAbsolutePath))(null)
      try {
        val common = genHeaderChain(18, None, diffBitsOpt = None,
          useRealTs = false).headers
        common.foreach { h =>
          leftHistory.append(h).get
          otherHistory.append(h).get
        }
        val tip = genHeaderChain(1, Some(common.last), diffBitsOpt = None,
          useRealTs = false).headers.last
        val fork = genHeaderChain(1, Some(common.last), diffBitsOpt = None,
          useRealTs = false).headers.last
        leftHistory.append(tip).get
        otherHistory.append(fork).get
        leftHistory.headersHeight shouldBe otherHistory.headersHeight
        leftHistory.bestHeaderIdOpt should not be otherHistory.bestHeaderIdOpt
        leftHistory.compare(otherHistory.syncInfoV2(full = true)) shouldBe org.ergoplatform.consensus.Fork
        otherHistory.compare(leftHistory.syncInfoV2(full = true)) shouldBe org.ergoplatform.consensus.Fork
        actor ! ChangedHistory(leftHistory)
        val remote = peer()
        val otherNc = TestProbe()
        val otherTracker = ErgoSyncTracker(config.scorexSettings.network)
        otherTracker.updateStatus(remote, Equal, Some(leftHistory.headersHeight))
        val other = TestActorRef[Synchronizer](Props(new Synchronizer(otherNc.ref, vh.ref,
          config, otherTracker, DeliveryTracker.empty(config))))
        other ! ChangedHistory(otherHistory)
        other ! ChangedMempool(ErgoMemPool.empty(config))
        def wire(probe: TestProbe, target: ActorRef): Unit = {
          probe.setAutoPilot(new TestActor.AutoPilot {
            override def run(sender: ActorRef, message: Any): TestActor.AutoPilot = {
              message match {
                case sent: SendToNetwork if sent.message.spec == ErgoSyncInfoMessageSpec =>
                  val bytes = ErgoSyncInfoMessageSpec.toBytes(sent.message.data.get.asInstanceOf[ErgoSyncInfo])
                  target ! Message(ErgoSyncInfoMessageSpec, Left(bytes), Some(remote))
                case _ => ()
              }
              TestActor.KeepRunning
            }
          })
        }
        wire(nc, other)
        wire(otherNc, actor)
        actor.underlyingActor.reply(remote, leftHistory.syncInfoV2(full = true))
        syncs(700.millis).size should be <= 2
        syncs(1200.millis) shouldBe empty
        otherNc.receiveWhile(100.millis) { case _ => () }
        otherNc.expectNoMessage(600.millis)
        system.stop(other)
      } finally {
        otherHistory.closeStorage()
        leftHistory.closeStorage()
      }
    }
  }
}
