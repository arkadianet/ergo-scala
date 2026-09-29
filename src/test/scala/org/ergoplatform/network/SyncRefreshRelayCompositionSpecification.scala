package org.ergoplatform.network

import akka.actor.Props
import akka.testkit.{TestActorRef, TestProbe}
import org.ergoplatform.consensus.Equal
import org.ergoplatform.mining.InputBlockFields
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.{ChangedHistory, ChangedMempool, NewBestInputBlock}
import org.ergoplatform.network.message.{InvData, InvSpec, Message}
import org.ergoplatform.network.message.inputblocks.InputBlockMessageSpec
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoSyncInfoMessageSpec}
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.subblocks.InputBlockAnnouncement
import org.ergoplatform.wallet.utils.FileUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import scorex.core.network.{DeliveryTracker, SendToPeer, SendToPeers}
import scorex.core.network.NetworkController.ReceivableMessages.SendToNetwork

import scala.concurrent.Await
import scala.concurrent.duration._

class SyncRefreshRelayCompositionSpecification extends AnyPropSpec with Matchers with FileUtils {
  import org.ergoplatform.utils.generators.ChainGenerator.applyChain

  Seq(true, false).foreach { local =>
    property(s"refreshed follower receives input-block relay with local=$local while stale follower is excluded") {
      val harness = new PostProgressSyncSpecification
      val follower = new harness.Fixture
      import follower._
      val minerConfig = config.copy(directory = createTempDir.getAbsolutePath)
      val minerHistory = ErgoHistory.readOrGenerate(minerConfig)(null)
      try {
        applyChain(minerHistory, blocks.take(4))
        val minerNetwork = TestProbe()(system)
        val minerView = TestProbe()(system)
        val minerTracker = ErgoSyncTracker(config.scorexSettings.network)
        val minerDelivery = DeliveryTracker.empty(config)
        val miner = TestActorRef[harness.Synchronizer](Props(new harness.Synchronizer(
          minerNetwork.ref, minerView.ref, minerConfig, minerTracker, minerDelivery)))(system)
        miner ! ChangedHistory(minerHistory)
        miner ! ChangedMempool(ErgoMemPool.empty(minerConfig))
        minerNetwork.receiveWhile(100.millis) { case _ => () }

        val remote = peer(height = 100)
        minerTracker.updateStatus(remote, Equal, Some(minerHistory.fullBlockHeight - 3))
        val inputHeader = blocks(4).header
        minerHistory.applyInputBlock(InputBlockAnnouncement(
          InputBlockAnnouncement.initialMessageVersion, inputHeader, InputBlockFields.empty, None))

        def reachesFollower(messages: Seq[Any]): Boolean = messages.exists {
          case s: SendToNetwork if s.message.spec == (if (local) InputBlockMessageSpec else InvSpec) =>
            if (local) {
              s.message.data.get.asInstanceOf[InputBlockAnnouncement].id shouldBe inputHeader.id
            } else {
              val inv = s.message.data.get.asInstanceOf[InvData]
              inv.typeId shouldBe org.ergoplatform.modifiers.InputBlockTypeId.value
              inv.ids shouldBe Seq(inputHeader.id)
            }
            s.sendingStrategy match {
              case SendToPeers(peers) => peers.contains(remote)
              case SendToPeer(p) => p == remote
              case _ => false
            }
          case _ => false
        }

        miner ! NewBestInputBlock(Some(inputHeader.id), local)
        reachesFollower(minerNetwork.receiveWhile(200.millis) { case m => m }) shouldBe false

        request(Seq(blocks(3).header), remote)
        applyHeaders(Seq(blocks(3).header))
        val refreshed = syncs().reverse.find(_._1.contains(remote)).get._2
        refreshed.lastHeaders.head.id shouldBe blocks(3).header.id
        miner ! Message(ErgoSyncInfoMessageSpec, Left(ErgoSyncInfoMessageSpec.toBytes(refreshed)), Some(remote))
        minerTracker.statuses(remote).height shouldBe minerHistory.fullBlockHeight
        minerNetwork.receiveWhile(200.millis) { case _ => () }

        miner ! NewBestInputBlock(Some(inputHeader.id), local)
        reachesFollower(minerNetwork.receiveWhile(300.millis) { case m => m }) shouldBe true
      } finally {
        Await.result(system.terminate(), Duration.Inf)
        minerHistory.closeStorage()
        history.closeStorage()
      }
    }
  }
}
