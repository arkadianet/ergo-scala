package org.ergoplatform.modifiers.mempool.rentauction

import java.util.Base64

import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.settings.ChainSettings
import sigma.ast.ErgoTree
import sigma.ast.SSigmaProp
import sigma.ast.Value
import sigma.compiler.ir.CompiletimeIRContext
import sigma.compiler.SigmaCompiler
import sigma.VersionContext

import scala.io.Source

/** Reference contracts for the draft rent-auction consensus feature. */
final class RentAuctionContracts(val chain: ChainSettings) {
  import RentAuctionContracts.CARRIER
  import RentAuctionContracts.EXTENSION
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.SEED
  import RentAuctionContracts.TOKENS_PER_LOT

  val reserve: ErgoTree =
    chain.reemission.reemissionRules.reemissionBoxProp(chain.monetary)
  val legacyDeposit: ErgoTree = chain.reemission.reemissionRules.payToReemission
  val fee: ErgoTree = ErgoTreePredef.feeProposition(chain.monetary.minerRewardDelay)

  private def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)

  private def compile(name: String, replacements: Map[String, String]): ErgoTree = {
    val resource = Source.fromResource(s"rent-auction/$name.es")
    val source = try resource.mkString finally resource.close()
    val code = replacements.foldLeft(source) { case (text, (key, value)) =>
      text.replace(s"__${key}__", value)
    }
    VersionContext.withVersions(3, 0) {
      val compiler = new SigmaCompiler(chain.addressPrefix)
      val result = compiler.compile(Map.empty, code)(new CompiletimeIRContext)
      val prop = result.buildTree.asInstanceOf[Value[SSigmaProp.type]]
      ErgoTree.fromProposition(ErgoTree.defaultHeaderWithVersion(0), prop)
    }
  }

  val deposit: ErgoTree = compile(
    "deposit",
    Map(
      "NFT" -> base64(chain.reemission.reemissionNftIdBytes.toArray),
      "RESERVE" -> base64(reserve.bytes),
      "FEE" -> base64(fee.bytes),
      "MERGE_BUDGET" -> MERGE_BUDGET.toString,
      "MIN_BID" -> MINIMUM_BID.toString
    )
  )

  val auction: ErgoTree = compile(
    "auction",
    Map(
      "DEPOSIT" -> base64(deposit.bytes),
      "SEED" -> SEED.toString,
      "INCREMENT" -> INCREMENT.toString,
      "MIN_BID" -> MINIMUM_BID.toString,
      "CARRIER" -> CARRIER.toString,
      "EXTENSION" -> EXTENSION.toString,
      "MERGE_BUDGET" -> MERGE_BUDGET.toString,
      "TOKENS_PER_LOT" -> TOKENS_PER_LOT.toString,
      "MAXIMUM_WINDOW" -> MAXIMUM_WINDOW.toString
    )
  )
}

object RentAuctionContracts {
  // Proposed draft constants; network assignment requires protocol review.
  val SEED: Long = 5000000L
  val CARRIER: Long = 2000000L
  val MERGE_BUDGET: Long = 1000000L
  val MINIMUM_BID: Long = 5000000L
  val INCREMENT: Long = 1000000L
  val WINDOW: Int = 720
  val MAXIMUM_WINDOW: Int = 1440
  val EXTENSION: Int = 30
  val TOKENS_PER_LOT: Int = 32
}
