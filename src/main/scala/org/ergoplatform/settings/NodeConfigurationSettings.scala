package org.ergoplatform.settings

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.ergoplatform.ErgoLikeContext.Height
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.SortingOption
import org.ergoplatform.nodeView.state.StateType
import scorex.util.ModifierId

import scala.concurrent.duration.FiniteDuration

case class CheckpointSettings(height: Height, blockId: ModifierId)

trait CheckpointingSettingsReader extends ModifierIdReader {
  implicit val checkpointSettingsReader: ValueReader[CheckpointSettings] = { (cfg, path) =>
    CheckpointSettings(
      cfg.as[Int](s"$path.height"),
      ModifierId @@ cfg.as[String](s"$path.blockId")
    )
  }
}

/**
  * Configuration file for Ergo node regime
  *
  * @see src/main/resources/application.conf for parameters description
  */
case class NodeConfigurationSettings(override val stateType: StateType,
                                     override val verifyTransactions: Boolean,
                                     override val blocksToKeep: Int,
                                     override val utxoSettings: UtxoSettings,
                                     override val nipopowSettings: NipopowSettings,
                                     mining: Boolean,
                                     maxTransactionCost: Int,
                                     maxTransactionSize: Int,
                                     blockCandidateGenerationInterval: FiniteDuration,
                                     useExternalMiner: Boolean,
                                     internalMinersCount: Int,
                                     internalMinerPollingInterval: FiniteDuration,
                                     miningPubKeyHex: Option[String],
                                     offlineGeneration: Boolean,
                                     keepVersions: Int,
                                     acceptableChainUpdateDelay: FiniteDuration,
                                     mempoolCapacity: Int,
                                     mempoolCleanupDuration: FiniteDuration,
                                     mempoolSorting: SortingOption,
                                     rebroadcastCount: Int,
                                     minimalFeeAmount: Long,
                                     headerChainDiff: Int,
                                     adProofsSuffixLength: Int,
                                     extraIndex: Boolean,
                                     blacklistedTransactions: Seq[String] = Seq.empty,
                                     checkpoint: Option[CheckpointSettings] = None,
                                     // Bounded local staging for child-before-parent transactions.
                                     // Off by default - a no-op feature until explicitly enabled.
                                     stagingEnabled: Boolean = false,
                                     stagingMaxCount: Int = 2048,
                                     stagingMaxBytes: Long = 8 * 1024 * 1024,
                                     stagingMaxCountPerPeer: Int = 128,
                                     stagingMaxBytesPerPeer: Long = 1024 * 1024,
                                     stagingMaxWaitersPerInput: Int = 64,
                                     stagingTtlMillis: Long = 1200000L,
                                     stagingMaxValidationAttempts: Int = 32,
                                     stagingMaxValidationCost: Long = 2000000L,
                                     stagingMaxPackageCost: Long = 10000000L,
                                     stagingMaxPackageTransactions: Int = 32) extends ClientCapabilities {
  /**
    * Whether the node keeping all the full blocks of the blockchain or not.
    * @return true if the blockchain is pruned, false if not
    */
  val isFullBlocksPruned: Boolean = blocksToKeep >= 0 || utxoSettings.utxoBootstrap

  val areSnapshotsStored = utxoSettings.storingUtxoSnapshots > 0
}

/**
  * Custom config reader for ergo.node settings section
  */
trait NodeConfigurationReaders extends StateTypeReaders with CheckpointingSettingsReader
                                  with UtxoSettingsReader with NipopowSettingsReader with ModifierIdReader {

  implicit val nodeConfigurationReader: ValueReader[NodeConfigurationSettings] = { (cfg, path) =>
    val stateTypeKey = s"$path.stateType"
    val stateType = stateTypeFromString(cfg.as[String](stateTypeKey), stateTypeKey)
    NodeConfigurationSettings(
      stateType,
      cfg.as[Boolean](s"$path.verifyTransactions"),
      cfg.as[Int](s"$path.blocksToKeep"),
      cfg.as[UtxoSettings](s"$path.utxo"),
      cfg.as[NipopowSettings](s"$path.nipopow"),
      cfg.as[Boolean](s"$path.mining"),
      cfg.as[Int](s"$path.maxTransactionCost"),
      cfg.as[Int](s"$path.maxTransactionSize"),
      cfg.as[FiniteDuration](s"$path.blockCandidateGenerationInterval"),
      cfg.as[Boolean](s"$path.useExternalMiner"),
      cfg.as[Int](s"$path.internalMinersCount"),
      cfg.as[FiniteDuration](s"$path.internalMinerPollingInterval"),
      cfg.as[Option[String]](s"$path.miningPubKeyHex"),
      cfg.as[Boolean](s"$path.offlineGeneration"),
      cfg.as[Int](s"$path.keepVersions"),
      cfg.as[FiniteDuration](s"$path.acceptableChainUpdateDelay"),
      cfg.as[Int](s"$path.mempoolCapacity"),
      cfg.as[FiniteDuration](s"$path.mempoolCleanupDuration"),
      cfg.as[SortingOption](s"$path.mempoolSorting"),
      cfg.as[Int](s"$path.rebroadcastCount"),
      cfg.as[Long](s"$path.minimalFeeAmount"),
      cfg.as[Int](s"$path.headerChainDiff"),
      cfg.as[Int](s"$path.adProofsSuffixLength"),
      cfg.as[Boolean](s"$path.extraIndex"),
      cfg.as[Seq[String]](s"$path.blacklistedTransactions"),
      cfg.as[Option[CheckpointSettings]](s"$path.checkpoint"),
      cfg.as[Option[Boolean]](s"$path.staging.enabled").getOrElse(false),
      cfg.as[Option[Int]](s"$path.staging.maxCount").getOrElse(2048),
      cfg.as[Option[Long]](s"$path.staging.maxBytes").getOrElse(8 * 1024 * 1024),
      cfg.as[Option[Int]](s"$path.staging.maxCountPerPeer").getOrElse(128),
      cfg.as[Option[Long]](s"$path.staging.maxBytesPerPeer").getOrElse(1024 * 1024),
      cfg.as[Option[Int]](s"$path.staging.maxWaitersPerInput").getOrElse(64),
      cfg.as[Option[Long]](s"$path.staging.ttlMillis").getOrElse(1200000L),
      cfg.as[Option[Int]](s"$path.staging.maxValidationAttempts").getOrElse(32),
      cfg.as[Option[Long]](s"$path.staging.maxValidationCost").getOrElse(2000000L),
      cfg.as[Option[Long]](s"$path.staging.maxPackageCost").getOrElse(10000000L),
      cfg.as[Option[Int]](s"$path.staging.maxPackageTransactions").getOrElse(32)
    )
  }

  implicit val sortingOptionReader: ValueReader[SortingOption] = { (cfg, path) =>
    val sorting = cfg.as[String](s"$path")
    sorting match {
      case "bySize" => SortingOption.FeePerByte
      case "byExecutionCost" => SortingOption.FeePerCycle
      case _ => SortingOption.random()
    }
  }

}
