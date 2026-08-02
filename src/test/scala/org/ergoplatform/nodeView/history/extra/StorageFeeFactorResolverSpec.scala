package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.http.api.StorageFeeFactorResolver
import org.ergoplatform.settings.{Args, ErgoSettingsReader, NetworkType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StorageFeeFactorResolverSpec extends AnyFlatSpec with Matchers {

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
}
