package org.ergoplatform.http.api

import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.nodeView.history.ErgoHistoryReader
import org.ergoplatform.nodeView.history.extra.ExtraIndexer
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.getIndex
import org.ergoplatform.settings.{ErgoSettings, Parameters}
import scorex.util.ScorexLogging

import scala.util.Try

case class FactorAtHeight(factor: Int, source: String)

object StorageFeeFactorResolver extends ScorexLogging {

  val Historical = "historical"
  val Projected = "projected"

  /** Parameters live in the Extension of each voting-epoch-start block and take
    * effect AT that block (ErgoStateContext.process:245-254). parseExtension
    * returns the FULL table, not a delta (Parameters.scala:220-228), so treating
    * it as absolute is correct. This is the epoch-of-H reading, deliberately not
    * the "params of H-1" convention — they differ only at an exact epoch start,
    * where epoch-of-H is what consensus applies.
    */
  def factorAt(atHeight: Int,
               history: ErgoHistoryReader,
               stateParams: => Parameters,
               settings: ErgoSettings): FactorAtHeight = {
    val votingLength = settings.chainSettings.voting.votingLength
    val epochStart = atHeight - (atHeight % votingLength)
    // LAZY: the epochStart == 0 branch must not touch history.
    lazy val indexedHeight = getIndex(ExtraIndexer.IndexedHeightKey, history).getInt

    if (epochStart == 0) {
      // Height 0 is not a voting start (Header.votingStarts requires height > 0)
      // and has no block, so the first epoch runs on launch parameters.
      FactorAtHeight(settings.launchParameters.storageFeeFactor, Historical)
    } else if (epochStart <= indexedHeight) {
      readFromExtension(epochStart, history) match {
        case Some(f) => FactorAtHeight(f, Historical)
        case None =>
          log.warn(s"No voted parameters at epoch start $epochStart; using current (projected)")
          FactorAtHeight(stateParams.storageFeeFactor, Projected)
      }
    } else {
      FactorAtHeight(stateParams.storageFeeFactor, Projected)
    }
  }

  private def readFromExtension(epochStart: Int, history: ErgoHistoryReader): Option[Int] =
    for {
      header <- history.bestHeaderAtHeight(epochStart)
      ext <- history.typedModifierById[Extension](header.extensionId)
      // storageFeeFactor is a bare Map.apply (Parameters.scala:33) and throws
      // NoSuchElementException when the key is absent — parseExtension only
      // guarantees the table is non-empty. Keep that inside the Try or it
      // escapes as a 500, which §8.4 forbids.
      factor <- Parameters.parseExtension(epochStart, ext).flatMap(p => Try(p.storageFeeFactor)).toOption
    } yield factor
}
