package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.Input
import org.ergoplatform.http.api.StorageFeeFactorResolver
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.mempool.ErgoMemPoolUtils.SortingOption
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.Constants.TrueTree
import org.ergoplatform.settings._
import org.ergoplatform.utils.{BoxUtils, ErgoCoreTestConstants, ErgoNodeTestConstants}
import org.ergoplatform.utils.generators.ChainGenerator._
import org.ergoplatform.wallet.utils.FileUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.ADKey
import sigma.Colls
import sigma.interpreter.{ContextExtension, ProverResult}
import sigmastate.helpers.TestingHelpers._

import java.nio.ByteBuffer
import scala.concurrent.duration._

class StorageFeeFactorResolverSpec extends AnyFlatSpec with Matchers with FileUtils {

  // ErgoSettingsReader.read(Args(None, Some(NetworkType.TestNet))) hits a config-loading
  // path (networkConfigFileOpt = Some, userConfigFileOpt = None) that falls back to
  // ConfigFactory.defaultReference() only, skipping defaultApplication() — so
  // src/main/resources/application.conf (which supplies scorex.network.appVersion,
  // substituted into testnet.conf's nodeName) never loads, and config resolution
  // throws ConfigException.UnresolvedSubstitution. Every other spec that reads
  // TestNet settings (e.g. ScanApiRouteSpec) goes through the userConfigPathOpt path
  // instead, which does pull in defaultApplication(). Mirror that here.
  private val settings = ErgoSettingsReader.read(
    Args(Some("src/test/resources/application.conf"), Some(NetworkType.TestNet)))

  "factorAt" should "use launch parameters inside the first voting epoch without touching history" in {
    // history is null on purpose: the epochStart == 0 branch must not read it,
    // which requires indexedHeight to be lazy.
    val res = StorageFeeFactorResolver.factorAt(
      atHeight = 5, history = null, stateParams = fail("must not read state"), settings = settings)
    res.source shouldBe StorageFeeFactorResolver.Historical
    res.factor shouldBe settings.launchParameters.storageFeeFactor
  }

  it should "read the voted storageFeeFactor from a real epoch-start Extension end-to-end (the historical branch)" in {
    // On mainnet epochStart != 0 && epochStart <= indexedHeight always holds, so
    // readFromExtension is the LIVE path -- unlike the epochStart == 0 test above,
    // this one must actually build a chain with a real voted Extension and read it
    // back through history.bestHeaderAtHeight / typedModifierById[Extension] /
    // Parameters.parseExtension, not just exercise the epochStart==0 shortcut.
    val votingLength = 4
    val votingSettings = ErgoCoreTestConstants.votingSettings.copy(votingLength = votingLength)
    val testChainSettings = ErgoCoreTestConstants.chainSettings.copy(voting = votingSettings)

    val nodeSettings = NodeConfigurationSettings(
      StateType.Utxo, verifyTransactions = true, blocksToKeep = -1,
      UtxoSettings(utxoBootstrap = false, 0, 2), NipopowSettings(nipopowBootstrap = false, 1),
      mining = false,
      ErgoNodeTestConstants.initSettings.nodeSettings.maxTransactionCost,
      ErgoNodeTestConstants.initSettings.nodeSettings.maxTransactionSize,
      blockCandidateGenerationInterval = 20.seconds, useExternalMiner = false, internalMinersCount = 1,
      internalMinerPollingInterval = 1.second, miningPubKeyHex = None, offlineGeneration = false,
      200, 5.minutes, 100000, 1.minute, mempoolSorting = SortingOption.FeePerByte, rebroadcastCount = 20,
      1000000, headerChainDiff = 5000, adProofsSuffixLength = 112 * 1024, extraIndex = false)

    val dir = createTempDir
    val testSettings: ErgoSettings = ErgoSettings(
      dir.getAbsolutePath, NetworkType.TestNet, testChainSettings, nodeSettings,
      ErgoNodeTestConstants.initSettings.scorexSettings,
      ErgoNodeTestConstants.initSettings.walletSettings,
      ErgoNodeTestConstants.initSettings.cacheSettings)

    var history: ErgoHistory = ErgoHistory.readOrGenerate(testSettings)(null)

    val newFactor = ErgoCoreTestConstants.parameters.storageFeeFactor + 12345
    val votedParams = Parameters(
      ErgoCoreTestConstants.parameters.height,
      ErgoCoreTestConstants.parameters.parametersTable.updated(Parameters.StorageFeeFactorIncrease, newFactor),
      ErgoCoreTestConstants.parameters.proposedUpdate)

    // Voting-epoch-start block (height == votingLength) carries the full voted
    // parameters table in its Extension, exactly as consensus requires. Unlike
    // ErgoNodeVotingSpecification (which only feeds blocks through
    // ErgoStateContext.appendFullBlock, a lighter-weight check), a real
    // ErgoHistory.append validates that a block's header.extensionId matches
    // the digest of the extension actually attached to it -- so the extension
    // must be baked in AT MINING TIME via `nextBlock`, not spliced in
    // afterwards with `.copy(extension = ...)` (which desyncs the header from
    // its own committed extension digest and fails validation).
    val proof = ProverResult(Array(0x7c.toByte), ContextExtension.empty)
    val inputs = IndexedSeq(Input(ADKey @@ Array.fill(32)(0: Byte), proof))
    val minimalAmount = BoxUtils.minimalErgoAmountSimulated(
      TrueTree, Colls.emptyColl, Map(), ErgoCoreTestConstants.parameters)
    val outputs = IndexedSeq(testBox(minimalAmount, TrueTree, creationHeight = ErgoCoreTestConstants.startHeight))
    val txs = Seq(ErgoTransaction(inputs, outputs))

    var prevOpt: Option[ErgoFullBlock] = None
    val chain = (1 to votingLength * 2).map { h =>
      val ext =
        if (h % votingLength == 0) votedParams.toExtensionCandidate
        else ErgoCoreTestConstants.defaultExtension
      val block = nextBlock(prevOpt, txs, ext)
      prevOpt = Some(block)
      block
    }
    history = applyChain(history, chain)

    val epochStart = votingLength

    // Fake the indexer's progress marker directly (bypassing the real indexer
    // actor, which is irrelevant to this test) so factorAt's
    // `epochStart <= indexedHeight` gate opens onto the historical branch.
    history.historyStorage.insertExtra(
      Array((ExtraIndexer.IndexedHeightKey, ByteBuffer.allocate(4).putInt(epochStart).array)),
      Array.empty)

    val res = StorageFeeFactorResolver.factorAt(
      atHeight = epochStart,
      history = history.getReader,
      stateParams = fail("must not read state: the historical branch must find the voted extension"),
      settings = testSettings)

    res.source shouldBe StorageFeeFactorResolver.Historical
    res.factor shouldBe newFactor
  }
}
