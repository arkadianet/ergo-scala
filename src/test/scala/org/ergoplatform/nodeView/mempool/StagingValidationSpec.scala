package org.ergoplatform.nodeView.mempool

import org.ergoplatform.modifiers.mempool.UnconfirmedTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.{Failure, Success}

class StagingValidationSpec extends AnyFlatSpec with Matchers {
  private def transaction: UnconfirmedTransaction = {
    val tx = org.ergoplatform.modifiers.mempool.ErgoTransaction(IndexedSeq.empty,
      IndexedSeq(new org.ergoplatform.ErgoBoxCandidate(1000000L, org.ergoplatform.settings.Constants.TrueTree, 0)))
    UnconfirmedTransaction(tx, None)
  }

  it should "reserve a full script limit before starting and refund successful unused cost" in {
    val work = new StagingValidation(10, 150L, 100)
    var runs = 0
    def validate() = { runs += 1; Success(30) }
    work.validate(transaction)(validate()) shouldBe Success(30)
    work.validate(transaction)(validate()) shouldBe Success(30)
    work.validate(transaction)(validate()) shouldBe Failure(StagingValidation.Deferred)
    runs shouldBe 2
    work.work.map(_.cost).sum shouldBe 60
  }

  it should "charge failures conservatively and keep budget exhaustion out of the failure ledger" in {
    val work = new StagingValidation(10, 100L, 100)
    val failure = new Exception("invalid script")
    work.validate(transaction)(Failure(failure)) shouldBe Failure(failure)
    work.validate(transaction)(fail("deferred script must not execute")) shouldBe Failure(StagingValidation.Deferred)
    work.work.map(_.cost) shouldBe Seq(100)
    work.work.flatMap(_.error) shouldBe Seq(failure)
  }

  it should "cap cheap validations by count as well as cost" in {
    val work = new StagingValidation(2, 1000L, 100)
    work.validate(transaction)(Success(1)) shouldBe Success(1)
    work.validate(transaction)(Success(1)) shouldBe Success(1)
    work.validate(transaction)(fail("attempt cap must stop scripts")) shouldBe Failure(StagingValidation.Deferred)
    work.work.size shouldBe 2
  }
}
