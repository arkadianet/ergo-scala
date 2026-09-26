package org.ergoplatform.modifiers.mempool.rentauction

import org.ergoplatform.ErgoBox
import org.ergoplatform.Input
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.settings.Constants
import org.ergoplatform.utils.ErgoCorePropertyTest
import sigma.ast.ByteArrayConstant
import sigma.ast.IntConstant
import sigma.interpreter.ContextExtension
import sigma.interpreter.ProverResult

class RentAuctionBaselineSpec extends ErgoCorePropertyTest with RentAuctionFixture {
  private lazy val rules = new RentAuctionRules(contracts, params)

  property("the unmodified node accepts two funded rent inputs sharing one recreation") {
    val created = height - Constants.StoragePeriod
    val a = box(1000000000L, owner, created, Seq(token -> 100L))
    val b = box(a.value, owner, created, Seq(token -> 100L))
    a.id should not equal b.id
    a.bytes.length shouldBe b.bytes.length
    val fee = params.storageFeeFactor * a.bytes.length
    fee.toLong should be < a.value
    val inputs = IndexedSeq(a, b)
    val tx = transaction(inputs, IndexedSeq(
      output(a.value - fee, owner, tokens = Seq(token -> 100L)),
      output(b.value + fee, anyone, tokens = Seq(token -> 100L))
    ), Map(0 -> 0.toShort, 1 -> 0.toShort))
    validate(tx, inputs).get should be > 0
    rules.validate(tx, inputs, height, None) shouldBe
      Left("rent obligations must have distinct output indices")
  }

  property("a failed recreation does not fall back to an otherwise true script") {
    val b = box(1000000000L, anyone, height - Constants.StoragePeriod)
    val inputs = IndexedSeq(b)
    val outs = IndexedSeq(output(b.value, owner))
    val tx = transaction(inputs, outs, Map(0 -> 0.toShort))
    validate(tx, inputs).isFailure shouldBe true
    rules.classify(b, tx.inputs.head, 1, height).get.fullyConsumed shouldBe false
    validate(transaction(inputs, outs), inputs).get should be > 0
  }

  property("malformed rent variables fall back to the box script") {
    val b = box(1000000000L, anyone, height - Constants.StoragePeriod)
    val badType = Input(b.id, ProverResult(Array.emptyByteArray,
      ContextExtension(Map(Constants.StorageIndexVarId -> IntConstant(0)))))
    val badIndex = rentInput(b, -1)
    Seq(badType, badIndex, rentInput(b, 1)).foreach { in =>
      val tx = ErgoTransaction(IndexedSeq(in), IndexedSeq(output(b.value, owner)))
      rules.classify(b, in, 1, height) shouldBe None
      validate(tx, IndexedSeq(b)).get should be > 0
    }
  }

  property("rent age is inclusive and a normal owner-script spend is unaffected") {
    val b = box(1000000L, nobody, height - Constants.StoragePeriod)
    val tx = transaction(IndexedSeq(b), IndexedSeq(output(b.value)), Map(0 -> 0.toShort))
    validate(tx, IndexedSeq(b), height).get should be > 0
    // Use outputs at the earlier height so the age check is the rejecting condition.
    val early = transaction(IndexedSeq(b), IndexedSeq(output(b.value, h = height - 1)),
      Map(0 -> 0.toShort))
    validate(early, IndexedSeq(b), height - 1).isFailure shouldBe true
    rules.classify(b, tx.inputs.head, 1, height - 1) shouldBe None
    val ordinary = box(1000000L, anyone, height - Constants.StoragePeriod)
    val normal = transaction(IndexedSeq(ordinary), IndexedSeq(output(ordinary.value)))
    validate(normal, IndexedSeq(ordinary)).get should be > 0
    rules.validate(normal, IndexedSeq(ordinary), height, None) shouldBe Right(())
  }

  property("large-box rent retains Scala Int overflow including both wrap regions") {
    Seq(1750, 3500).foreach { size =>
      val b = box(10000000L, nobody, height - Constants.StoragePeriod,
        registers = Map(ErgoBox.R4 -> ByteArrayConstant(Array.fill(size)(1.toByte))))
      val wrapped = params.storageFeeFactor * b.bytes.length
      val inputs = IndexedSeq(b)
      if (wrapped < 0) {
        val sponsor = box(-wrapped.toLong)
        val all = inputs :+ sponsor
        val tx = transaction(all, IndexedSeq(output(b.value - wrapped,
          nobody, registers = b.additionalRegisters)), Map(0 -> 0.toShort))
        rules.classify(b, tx.inputs.head, 1, height).get.fullyConsumed shouldBe false
        validate(tx, all).get should be > 0
        val sweep = transaction(inputs, IndexedSeq(output(b.value)), Map(0 -> 0.toShort))
        validate(sweep, inputs).isFailure shouldBe true
      } else {
        wrapped.toLong should be < params.storageFeeFactor.toLong * b.bytes.length
        val tx = transaction(inputs, IndexedSeq(output(b.value)), Map(0 -> 0.toShort))
        rules.classify(b, tx.inputs.head, 1, height).get.fullyConsumed shouldBe true
        validate(tx, inputs).get should be > 0
      }
    }
  }
}
