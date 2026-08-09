package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoToken
import scala.collection.JavaConverters._

/** Un-weld the borrower wallet: all boxes in; RSN isolated on one
  * min-value box; every other token burned (test-token strays); the rest
  * one clean token-free ERG box. Exists because token-carrying changes
  * (cancels, repays) weld ERG onto token boxes and starve the token-free
  * selector (Phase 2 lesson, Phase 3 recurrence) — and plain Recycle
  * would burn the RSN.
  */
object ConsolidateBorrower {
  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val b     = TestLib.borrower(ctx)
    val bAddr = b.getEip3Addresses.get(0)
    val boxes = ctx.getBoxesById(Kit.unspentBoxIds(bAddr.toString): _*).toSeq
    val total = boxes.map(_.getValue.toLong).sum
    val tokens = boxes.flatMap(_.getTokens.asScala)
      .groupBy(_.getId.toString).values
      .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
    val (rsn, junk) = tokens.partition(_.getId.toString == Contracts.COLLATERAL_TOKEN_ID)

    val tb = ctx.newTxBuilder()
    var outs = Seq(tb.outBoxBuilder()
      .value(total - Kit.TX_FEE - (if (rsn.nonEmpty) Kit.MIN_BOX_VALUE else 0L))
      .contract(bAddr.toErgoContract).build())
    if (rsn.nonEmpty)
      outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
        .contract(bAddr.toErgoContract).tokens(rsn: _*).build()
    var bt = tb.boxesToSpend(boxes.asJava).outputs(outs: _*).fee(Kit.TX_FEE).sendChangeTo(bAddr)
    if (junk.nonEmpty) bt = bt.tokensToBurn(junk: _*)
    val txId = Kit.sendSafe(ctx, b.sign(bt.build()), "consolidate-borrower-p3")
    Kit.waitConfirmed(txId, "consolidate-borrower-p3")
    println(s"borrower consolidated: ${(total - Kit.TX_FEE - Kit.MIN_BOX_VALUE) / 1e9} ERG clean, " +
      s"${rsn.map(_.getValue.toLong).sum} raw RSN isolated, ${junk.size} junk token ids burned")
    ()
  }
}
