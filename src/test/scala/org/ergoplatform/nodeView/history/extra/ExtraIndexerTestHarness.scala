package org.ergoplatform.nodeView.history.extra

import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.settings.ErgoSettings

import java.util.concurrent.locks.{Condition, ReentrantLock}

/**
  * Reusable test harness surface for `ExtraIndexerTestActor`. Extracted so that
  * suites other than `ExtraIndexerSpecification` (e.g. a later route-level spec)
  * can drive the same actor without depending on `ExtraIndexerSpecification`'s
  * path-dependent inner types.
  */
trait ExtraIndexerTestHarness {

  val initSettings: ErgoSettings

  val lock: ReentrantLock = new ReentrantLock()
  val done: Condition = lock.newCondition()
  val created: Condition = lock.newCondition()

  var _history: ErgoHistory = _

  case class CreateDB(blockCount: Int)
  case class Reset()
  case class GenerateBetterChainTip()
}
