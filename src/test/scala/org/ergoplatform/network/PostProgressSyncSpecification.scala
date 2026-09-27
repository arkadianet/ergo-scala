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
                     tracker: ErgoSyncTracker, delivery: DeliveryTracker)(implicit ec: ExecutionContext)
    extends ErgoNodeViewSynchronizer(nc, vh, ErgoSyncInfoMessageSpec, s, tracker, delivery) {
    def reply(peer: ConnectedPeer, sync: ErgoSyncInfo): Unit = sendSyncToPeer(peer, sync)
    def periodic(history: ErgoHistory): Unit = sendSync(history)
  }

  class Fixture extends AkkaFixture {
    implicit val ec: ExecutionContext = system.dispatcher
    val config = settings.copy(directory = createTempDir.getAbsolutePath)
    val history = ErgoHistory.readOrGenerate(config)(null)
    val blocks = genChain(8, history)
    applyChain(history, blocks.take(3))
    val nc = TestProbe()
    val vh = TestProbe()
    val tracker = ErgoSyncTracker(config.scorexSettings.network)
    val delivery = DeliveryTracker.empty(config)
    val actor = TestActorRef[Synchronizer](Props(new Synchronizer(nc.ref, vh.ref, config, tracker, delivery)))
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
      actor ! BlockSectionsProcessingCacheUpdate(0, 0, Header.modifierTypeId -> Seq.empty)
    }

    def syncs(duration: FiniteDuration = 350.millis): Seq[(Set[ConnectedPeer], ErgoSyncInfoV2)] = {
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

  private def fixture(test: Fixture => Unit): Unit = {
    val f = new Fixture
    try test(f) finally {
      Await.result(f.system.terminate(), Duration.Inf)
      f.history.closeStorage()
    }
  }

  property("ordering announcement supplier receives the applied best header") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val block = blocks(3)
      val announcement = OrderingBlockAnnouncement(OrderingBlockAnnouncement.CurrentVersion,
        block.header, Seq.empty, Seq.empty, block.extension.fields)
      actor ! Message(OrderingBlockAnnouncementMessageSpec,
        Left(OrderingBlockAnnouncementMessageSpec.toBytes(announcement)), Some(supplier))
      vh.expectMsgType[ProcessOrderingBlock].oba.header.id shouldBe block.header.id
      syncs(150.millis) shouldBe empty
      applyHeaders(Seq(block.header))
      val sent = syncs()
      sent.map(_._1) should contain(Set(supplier))
      sent.foreach(_._2.lastHeaders.head.id shouldBe block.header.id)
    }
  }

  property("header progress refreshes the relevant mesh supplier and excludes distant and incompatible peers") {
    fixture { f =>
      import f._
      val source = peer(height = 100)
      val nearby = peer()
      val distant = peer(height = 100)
      val legacy = peer(subBlocks = false)
      val digest = peer(stateType = StateType.Digest)
      request(Seq(blocks(3).header), source)
      applyHeaders(Seq(blocks(3).header))
      val sent = syncs()
      sent.flatMap(_._1).toSet shouldBe Set(source, nearby)
      sent.foreach(_._2.lastHeaders.head.id shouldBe blocks(3).header.id)
      tracker.getStatus(distant) shouldBe Some(Equal)
      tracker.getStatus(legacy) shouldBe Some(Equal)
      tracker.getStatus(digest) shouldBe Some(Equal)
    }
  }

  property("requested delivery sends one post-apply status per header batch") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val headers = blocks.slice(3, 7).map(_.header)
      request(headers, supplier)
      syncs(150.millis) shouldBe empty
      headers.foreach { h =>
        history.append(h).get
        actor ! SyntacticallySuccessfulModifier(Header.modifierTypeId, h.id)
        actor ! ChangedHistory(history)
        syncs(140.millis) shouldBe empty
      }
      actor ! BlockSectionsProcessingCacheUpdate(0, 0, Header.modifierTypeId -> Seq.empty)
      val sent = syncs()
      sent.size shouldBe 1
      sent.head._2.lastHeaders.head.id shouldBe headers.last.id
    }
  }

  property("rejected header does not advertise progress or refresh mesh suppliers") {
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
      actor ! ChangedHistory(history)
      syncs() shouldBe empty
    }
  }

  property("burst refresh trails the final header and observes the reply and periodic send lock") {
    fixture { f =>
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
      tracker.updateStatus(supplier, org.ergoplatform.consensus.Unknown, None)
      actor.underlyingActor.periodic(history)
      actor.underlyingActor.reply(nearby, history.syncInfoV2(full = true))
      nc.expectMsgType[SendToNetwork].message.spec shouldBe ErgoSyncInfoMessageSpec
      nc.expectMsgType[SendToNetwork].message.spec shouldBe ErgoSyncInfoMessageSpec
      request(Seq(blocks(3).header), supplier)
      applyHeaders(Seq(blocks(3).header))
      actor.underlyingActor.reply(supplier, history.syncInfoV2(full = true))
      request(Seq(blocks(4).header), supplier)
      applyHeaders(Seq(blocks(4).header))
      val trailing = syncs()
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
        times.size shouldBe 2
        (times.last - times.head).nanos.toMillis should be >= 100L
      }
    }
  }

  property("already applied requested headers still elicit one pipeline response") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      request(Seq(blocks(2).header), supplier)
      actor ! BlockSectionsProcessingCacheUpdate(0, 0, Header.modifierTypeId -> Seq.empty)
      val sent = syncs()
      sent.size shouldBe 1
      sent.head._2.lastHeaders.head.id shouldBe blocks(2).header.id
    }
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
      actor.underlyingActor.reply(supplier, history.syncInfoV2(full = true))
      nc.expectMsgType[SendToNetwork]
      request(Seq(blocks(3).header), supplier)
      applyHeaders(Seq(blocks(3).header))
      actor ! DisconnectedPeer(supplier)
      syncs() shouldBe empty
    }
  }

  property("lighter fork delivery continues header synchronization without refreshing mesh suppliers") {
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

  property("SyncInfo continuation supplier receives the applied header after a throttled reply") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      val h = blocks(3).header
      val sync = ErgoSyncInfoV2(Seq(h))
      actor ! Message(ErgoSyncInfoMessageSpec, Left(ErgoSyncInfoMessageSpec.toBytes(sync)), Some(supplier))
      vh.expectMsgType[ModifiersFromRemote]
      applyHeaders(Seq(h))
      val sent = syncs()
      sent.last._1 shouldBe Set(supplier)
      sent.last._2.lastHeaders.head.id shouldBe h.id
      sent.size should be <= 2
    }
  }

  property("semantic rejection does not trigger a supplier refresh after batch completion") {
    fixture { f =>
      import f._
      val supplier = peer(height = 100)
      peer()
      val header = blocks(3).header
      request(Seq(header), supplier)
      actor ! SemanticallyFailedModification(Header.modifierTypeId, header.id,
        new IllegalArgumentException("rejected header"))
      actor ! ChangedHistory(history)
      actor ! BlockSectionsProcessingCacheUpdate(0, 0, Header.modifierTypeId -> Seq.empty)
      syncs() shouldBe empty
    }
  }

  property("periodic sync still discovers peers from an empty history") {
    fixture { f =>
      import f._
      val empty = ErgoHistory.readOrGenerate(config.copy(directory = createTempDir.getAbsolutePath))(null)
      try {
        val remote = peer()
        tracker.updateStatus(remote, org.ergoplatform.consensus.Unknown, None)
        actor ! ChangedHistory(empty)
        actor.underlyingActor.periodic(empty)
        val sent = nc.expectMsgType[SendToNetwork]
        sent.message.spec shouldBe ErgoSyncInfoMessageSpec
        sent.message.data.get.asInstanceOf[ErgoSyncInfo].nonEmpty shouldBe false
      } finally empty.closeStorage()
    }
  }

}
