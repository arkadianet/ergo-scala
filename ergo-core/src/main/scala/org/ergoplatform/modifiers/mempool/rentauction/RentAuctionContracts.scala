package org.ergoplatform.modifiers.mempool.rentauction

import java.util.Base64

import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.settings.ChainSettings
import scorex.crypto.hash.Blake2b256
import sigma.ast.ErgoTree
import sigma.ast.SSigmaProp
import sigma.ast.Value
import sigma.compiler.ir.CompiletimeIRContext
import sigma.compiler.SigmaCompiler
import sigma.VersionContext

import scala.io.Source

/** Reference contracts for the draft rent-auction consensus feature. */
final class RentAuctionContracts(val chain: ChainSettings) {
  import RentAuctionContracts.EXTENSION
  import RentAuctionContracts.INCREMENT
  import RentAuctionContracts.MAXIMUM_WINDOW
  import RentAuctionContracts.MERGE_BUDGET
  import RentAuctionContracts.MINIMUM_BID
  import RentAuctionContracts.TOKENS_PER_LOT

  val reserve: ErgoTree =
    chain.reemission.reemissionRules.reemissionBoxProp(chain.monetary)
  val legacyDeposit: ErgoTree = chain.reemission.reemissionRules.payToReemission
  val fee: ErgoTree = ErgoTreePredef.feeProposition(chain.monetary.minerRewardDelay)

  private def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)

  private def compile(name: String, replacements: Map[String, String]): ErgoTree = {
    val path = s"rent-auction/$name.es"
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    require(stream != null, s"Missing contract resource $path")
    val resource = Source.fromInputStream(stream, "UTF-8")
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
      "MIN_DEPOSIT_VALUE" -> RentAuctionContracts.MIN_DEPOSIT_VALUE.toString
    )
  )

  val auction: ErgoTree = compile(
    "auction",
    Map(
      "DEPOSIT_HASH" -> base64(Blake2b256(deposit.bytes)),
      "CLOSE_ALLOWANCE" -> RentAuctionContracts.CLOSE_ALLOWANCE.toString,
      "COLLECTOR_SHARE_DENOMINATOR" -> RentAuctionContracts.COLLECTOR_SHARE_DENOMINATOR.toString,
      "MAX_BYTE_PRICE" -> RentAuctionContracts.MAX_BYTE_PRICE.toString,
      "PAYOUT_OVERHEAD_BYTES" -> RentAuctionContracts.PAYOUT_OVERHEAD_BYTES.toString,
      "TOKEN_ENTRY_BYTES" -> RentAuctionContracts.TOKEN_ENTRY_BYTES.toString,
      "MIN_DEPOSIT_VALUE" -> RentAuctionContracts.MIN_DEPOSIT_VALUE.toString,
      "INCREMENT" -> INCREMENT.toString,
      "MIN_BID" -> MINIMUM_BID.toString,
      "EXTENSION" -> EXTENSION.toString,
      "TOKENS_PER_LOT" -> TOKENS_PER_LOT.toString,
      "MAXIMUM_WINDOW" -> MAXIMUM_WINDOW.toString
    )
  )
}

object RentAuctionContracts {
  // Proposed draft constants; network assignment requires protocol review.
  val CLOSE_ALLOWANCE: Long = 2000000L
  val MAX_CLOSE_FEE: Long = 2000000L
  val COLLECTOR_SHARE_DENOMINATOR: Long = 10L
  val MAX_BYTE_PRICE: Int = 10000
  val MAX_PARTY_BYTES: Int = 256
  val PAYOUT_OVERHEAD_BYTES: Int = 128
  val TOKEN_ENTRY_BYTES: Int = 42
  val MAX_DEPOSIT_BYTES: Int = 2048
  val MIN_DEPOSIT_VALUE: Long = 20480000L
  val MAX_CLOSE_LOTS: Int = 32
  val MAX_MERGE_DEPOSITS: Int = 10
  val MAX_MERGE_FEE: Long = 10000000L
  val MERGE_BUDGET: Long = 1000000L
  val MINIMUM_BID: Long = 50000000L
  val INCREMENT: Long = 1000000L
  val WINDOW: Int = 720
  val MAXIMUM_WINDOW: Int = 1440
  val EXTENSION: Int = 30
  val TOKENS_PER_LOT: Int = 32
}
