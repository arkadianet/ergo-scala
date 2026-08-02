package org.ergoplatform.wallet.interpreter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ErgoInterpreterStorageFeeSpec extends AnyFlatSpec with Matchers {

  "storageFee" should "equal the inline consensus expression for typical values" in {
    val factor = 1250000
    val size = 105
    ErgoInterpreter.storageFee(factor, size) shouldBe factor * size
  }

  it should "reproduce Int wrap-around exactly, including negative results" in {
    // 1250000 * 2000 = 2.5e9 > Int.MaxValue -> wraps negative.
    // Pins CURRENT consensus semantics. A consensus fix that widens this
    // arithmetic MUST update this test deliberately.
    val factor = 1250000
    val size = 2000
    ErgoInterpreter.storageFee(factor, size) shouldBe factor * size
    ErgoInterpreter.storageFee(factor, size) should be < 0
  }

  it should "handle zero and boundary inputs" in {
    ErgoInterpreter.storageFee(0, 12345) shouldBe 0
    ErgoInterpreter.storageFee(12345, 0) shouldBe 0
    ErgoInterpreter.storageFee(Int.MaxValue, 1) shouldBe Int.MaxValue
  }
}
