package bonds

import scala.collection.JavaConverters._

/** Recovery repay that attaches the pool box as a DUMMY data input.
  * Exists because the first Phase 3 tree eagerly evaluates a CSE-hoisted
  * CONTEXT.dataInputs(0) (found empirically on the first repay attempt:
  * AIOOBE from ByIndex on the empty dataInputs coll), so every spend of a
  * bond under that tree needs SOME data input present. The repay path
  * never validates it — any box unbricks the spend.
  * Usage: runMain bonds.RepayWithData <bondBoxId>
  */
object RepayWithData {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "args: <bondBoxId>")
    Kit.exec { ctx =>
      val vault   = Contracts.vault(ctx, TestLib.lender(ctx).getEip3Addresses.get(0).getPublicKey)._1
      val b       = TestLib.borrower(ctx)
      val bondBox = ctx.getBoxesById(args(0))(0)
      val plan    = TestLib.repayPlan(bondBox, vault)
      val bAddr   = b.getEip3Addresses.get(0)
      val funds   = Kit.selectBoxes(ctx, bAddr,
        plan.exitValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val tb = ctx.newTxBuilder()
      var eb = tb.outBoxBuilder()
        .value(plan.exitValue)
        .contract(new org.ergoplatform.appkit.impl.ErgoTreeContract(plan.exitTree,
          org.ergoplatform.appkit.NetworkType.MAINNET))
        .tokens(plan.tokens: _*)
      plan.receiptR4.foreach { r4 => eb = eb.registers(org.ergoplatform.appkit.ErgoValue.of(r4)) }
      val unsigned = tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
        .withDataInputs(java.util.Arrays.asList(P3.poolBox(ctx)))
        .outputs(eb.build())
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
      val signed = b.sign(unsigned)
      val txId   = Kit.sendSafe(ctx, signed, "recovery-repay-with-dummy-data-input")
      Kit.waitConfirmed(txId, "recovery-repay")
      println("bond recovered (repay with dummy data input)")
      ()
    }
  }
}
