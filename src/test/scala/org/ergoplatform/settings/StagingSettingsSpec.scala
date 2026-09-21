package org.ergoplatform.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StagingSettingsSpec extends AnyFlatSpec with Matchers {
  private def read(overrides: String): NodeConfigurationSettings = {
    val config = ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()).resolve()
    ErgoSettingsReader.nodeConfigurationReader.read(config, "ergo.node")
  }

  it should "disable staging by default and keep legacy configurations readable" in {
    val config = ConfigFactory.load().withoutPath("ergo.node.staging")
    val node = ErgoSettingsReader.nodeConfigurationReader.read(config, "ergo.node")
    node.stagingEnabled shouldBe false
    node.stagingMaxCount shouldBe 2048
    node.stagingMaxBytes shouldBe 8388608L
    node.stagingMaxCountPerPeer shouldBe 128
    node.stagingMaxBytesPerPeer shouldBe 1048576L
    node.stagingMaxWaitersPerInput shouldBe 64
    node.stagingTtlMillis shouldBe 1200000L
    node.stagingMaxValidationAttempts shouldBe 32
    node.stagingMaxValidationCost shouldBe 2000000L
  }

  it should "read explicit staging overrides from ergo.node.staging" in {
    val node = read("""ergo.node.staging {
      enabled = true
      maxCount = 17
      maxBytes = 4096
      maxCountPerPeer = 3
      maxBytesPerPeer = 1024
      maxWaitersPerInput = 2
      ttlMillis = 60000
      maxValidationAttempts = 7
      maxValidationCost = 3000000
    }""")
    node.stagingEnabled shouldBe true
    node.stagingMaxCount shouldBe 17
    node.stagingMaxBytes shouldBe 4096L
    node.stagingMaxCountPerPeer shouldBe 3
    node.stagingMaxBytesPerPeer shouldBe 1024L
    node.stagingMaxWaitersPerInput shouldBe 2
    node.stagingTtlMillis shouldBe 60000L
    node.stagingMaxValidationAttempts shouldBe 7
    node.stagingMaxValidationCost shouldBe 3000000L
  }
}
