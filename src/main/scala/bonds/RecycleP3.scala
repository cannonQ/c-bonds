package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoToken
import scala.collection.JavaConverters._

/** Phase 3-aware vault sweep: like Recycle's vault leg, but the RSN that
  * liquidation/acceleration delivered to the vault is REAL value — it
  * goes back to the borrower's inventory for the next run instead of
  * being burned. Loan tokens (settlement-receipt markers) burn as usual.
  */
object RecycleP3 {
  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val lender = TestLib.lender(ctx);   val lAddr = lender.getEip3Addresses.get(0)
    val bAddr  = TestLib.borrower(ctx).getEip3Addresses.get(0)
    val (vaultTree, _) = Contracts.vault(ctx, lAddr.getPublicKey)
    val vAddr = Address.fromErgoTree(vaultTree, NetworkType.MAINNET)

    val boxes = Kit.unspentBoxIds(vAddr.toString) match {
      case Nil => println("vault empty — nothing to sweep"); return
      case ids => ctx.getBoxesById(ids: _*).toSeq
    }
    val total = boxes.map(_.getValue.toLong).sum
    val allTokens = boxes.flatMap(_.getTokens.asScala)
      .groupBy(_.getId.toString).values
      .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
    val (rsn, burnable) = allTokens.partition(_.getId.toString == Contracts.COLLATERAL_TOKEN_ID)

    val tb = ctx.newTxBuilder()
    var outs = Seq(tb.outBoxBuilder()
      .value(total - Kit.TX_FEE - (if (rsn.nonEmpty) Kit.MIN_BOX_VALUE else 0L))
      .contract(lAddr.toErgoContract).build())
    if (rsn.nonEmpty)
      outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
        .contract(bAddr.toErgoContract).tokens(rsn: _*).build()
    var b = tb.boxesToSpend(boxes.asJava).outputs(outs: _*).fee(Kit.TX_FEE).sendChangeTo(lAddr)
    if (burnable.nonEmpty) b = b.tokensToBurn(burnable: _*)
    val txId = Kit.sendSafe(ctx, lender.sign(b.build()), "p3-vault-sweep(rsn->borrower)")
    Kit.waitConfirmed(txId, "p3-vault-sweep")
    println(s"swept ${total / 1e9} ERG -> lender; ${rsn.map(_.getValue.toLong).sum} raw RSN -> borrower; " +
      s"${burnable.size} token ids burned")
    ()
  }
}
