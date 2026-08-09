package bonds

import org.ergoplatform.appkit._
import scala.collection.JavaConverters._

/** Lender-side unweld (the LENDER_TARGET standing TODO, forced by rev 3:
  * Phase 4 exits pay the lender P2PK directly, so repay/acceleration
  * receipts weld their ERG onto loan-token boxes and starve the
  * token-free selector). Mirror of ConsolidateBorrower: every lender box
  * in, ALL tokens burned (post-exit loan tokens are inert provenance
  * receipts — RecycleP3 burn precedent; the lender never holds RSN),
  * one clean ERG box out.
  */
object ConsolidateLender {
  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val l     = TestLib.lender(ctx)
    val lAddr = l.getEip3Addresses.get(0)
    val boxes = ctx.getBoxesById(Kit.unspentBoxIds(lAddr.toString): _*).toSeq
    require(boxes.nonEmpty, "no lender boxes")
    val toBurn = boxes.flatMap(_.getTokens.asScala)
      .groupBy(_.getId.toString).values
      .map(ts => new org.ergoplatform.sdk.ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum))
      .toSeq
    val total = boxes.map(_.getValue.toLong).sum
    val tb    = ctx.newTxBuilder()
    val out = tb.outBoxBuilder()
      .value(total - Kit.TX_FEE)
      .contract(lAddr.toErgoContract)
      .build()
    var b = tb.boxesToSpend(boxes.asJava).outputs(out).fee(Kit.TX_FEE)
    if (toBurn.nonEmpty) b = b.tokensToBurn(toBurn: _*)
    val tx   = l.sign(b.sendChangeTo(lAddr).build())
    val txId = Kit.sendSafe(ctx, tx, "consolidate-lender-p4")
    Kit.waitConfirmed(txId, "consolidate-lender-p4")
    println(f"lender consolidated: ${(total - Kit.TX_FEE) / 1e9}%.4f ERG clean, ${toBurn.size} token ids burned")
    ()
  }
}
