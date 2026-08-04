package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoToken
import scala.collection.JavaConverters._

/** Recover and rebalance the dust between runs.
  *
  * A full suite run leaves funds fragmented: the vault script address
  * accumulates every repayment and liquidation receipt (the "sink"), and
  * the borrower's ERG can get welded onto stray test-token boxes that the
  * token-free box selector skips. This one-shot:
  *   1. lender sweeps all vault boxes back to the lender P2PK;
  *   2. borrower consolidates all its boxes into one clean token-free box,
  *      burning worthless leftover test tokens;
  *   3. lender tops the borrower up to BORROWER_TARGET.
  * Result: borrower and lender both hold a fat, clean, token-free balance
  * for the next run. Dust only.
  */
object Recycle {
  // Kept below the borrower's typical consolidated balance so the top-up
  // rarely fires and the lender retains the swept vault funds — the lender
  // only drains during a run (principal out, nothing back to its P2PK).
  val BORROWER_TARGET: Long = 130000000L // 0.13 ERG

  private def sweep(ctx: BlockchainContext, prover: ErgoProver, boxes: Seq[InputBox],
                    toAddr: Address, label: String): Unit = {
    if (boxes.isEmpty) { println(s"  $label: nothing to sweep"); return }
    val total  = boxes.map(_.getValue.toLong).sum
    // Aggregate per token id: the same id split across boxes must be ONE
    // burn entry — appkit's burn map otherwise drops all but the last
    // amount and demands a change box for the remainder (run-2 crash).
    val tokens = boxes.flatMap(_.getTokens.asScala)
      .groupBy(_.getId.toString).values
      .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
    val tb  = ctx.newTxBuilder()
    // One explicit token-free output: all value minus fee. Stray test
    // tokens are burned, so the result is a single clean box.
    val out = tb.outBoxBuilder().value(total - Kit.TX_FEE).contract(toAddr.toErgoContract).build()
    var b   = tb.boxesToSpend(boxes.asJava).outputs(out).fee(Kit.TX_FEE).sendChangeTo(toAddr)
    if (tokens.nonEmpty) b = b.tokensToBurn(tokens: _*)
    val txId = Kit.sendSafe(ctx, prover.sign(b.build()), label)
    Kit.waitConfirmed(txId, label)
  }

  def main(args: Array[String]): Unit = {
    Kit.exec { ctx =>
      val borrower = TestLib.borrower(ctx); val bAddr = borrower.getEip3Addresses.get(0)
      val lender   = TestLib.lender(ctx);   val lAddr = lender.getEip3Addresses.get(0)
      val (vaultTree, _) = Contracts.vault(ctx, lAddr.getPublicKey)
      val vAddr = Address.fromErgoTree(vaultTree, NetworkType.MAINNET)

      println("Before:")
      Seq(("borrower", bAddr), ("lender", lAddr), ("vault", vAddr)).foreach { case (n, a) =>
        println(f"  $n%-9s ${Kit.balance(a) / 1e9}%.4f ERG") }

      // 1. lender sweeps the vault sink back to itself
      val vaultBoxes = Kit.unspentBoxIds(vAddr.toString) match {
        case Nil => Nil
        case ids => ctx.getBoxesById(ids: _*).toSeq
      }
      sweep(ctx, lender, vaultBoxes, lAddr, "sweep-vault->lender")

      // 2. borrower consolidates all its boxes, burning stray test tokens
      val borrowerBoxes = ctx.getBoxesById(Kit.unspentBoxIds(bAddr.toString): _*).toSeq
      sweep(ctx, borrower, borrowerBoxes, bAddr, "consolidate-borrower")

      // 3. lender tops the borrower up to target
      val have = Kit.balance(bAddr)
      if (have < BORROWER_TARGET) {
        val need = BORROWER_TARGET - have
        val lenderBoxes = Kit.selectBoxes(ctx, lAddr, need + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
        val tb  = ctx.newTxBuilder()
        val out = tb.outBoxBuilder().value(need).contract(bAddr.toErgoContract).build()
        val tx  = tb.boxesToSpend(lenderBoxes.asJava).outputs(out).fee(Kit.TX_FEE).sendChangeTo(lAddr).build()
        val txId = Kit.sendSafe(ctx, lender.sign(tx), "lender-topup-borrower")
        Kit.waitConfirmed(txId, "lender-topup-borrower")
      }

      println("After:")
      Seq(("borrower", bAddr), ("lender", lAddr), ("keeper", TestLib.keeper(ctx).getEip3Addresses.get(0)), ("vault", vAddr)).foreach { case (n, a) =>
        println(f"  $n%-9s ${Kit.balance(a) / 1e9}%.4f ERG") }
      ()
    }
  }
}
