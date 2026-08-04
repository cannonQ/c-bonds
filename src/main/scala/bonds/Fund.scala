package bonds

import org.ergoplatform.appkit._
import scala.collection.JavaConverters._

/** Distribute dust from the funded borrower wallet to lender and keeper. */
object Fund {
  val LENDER_AMT: Long = 200000000L // 0.20 ERG
  val KEEPER_AMT: Long = 50000000L  // 0.05 ERG

  def main(args: Array[String]): Unit = {
    Kit.client().execute { ctx =>
      val borrower = Kit.prover(ctx, Env.die("BORROWER_MNEMONIC"))
      val lender   = Kit.prover(ctx, Env.die("LENDER_MNEMONIC"))
      val keeper   = Kit.prover(ctx, Env.die("KEEPER_MNEMONIC"))
      val from     = borrower.getEip3Addresses.get(0)

      val need   = LENDER_AMT + KEEPER_AMT + Kit.TX_FEE
      val inputs = Kit.selectBoxes(ctx, from, need)

      val tb = ctx.newTxBuilder()
      val outLender = tb.outBoxBuilder()
        .value(LENDER_AMT)
        .contract(lender.getEip3Addresses.get(0).toErgoContract)
        .build()
      val outKeeper = tb.outBoxBuilder()
        .value(KEEPER_AMT)
        .contract(keeper.getEip3Addresses.get(0).toErgoContract)
        .build()

      val unsigned = tb
        .boxesToSpend(inputs.asJava)
        .outputs(outLender, outKeeper)
        .fee(Kit.TX_FEE)
        .sendChangeTo(from)
        .build()

      val signed = borrower.sign(unsigned)
      val txId   = Kit.sendSafe(ctx, signed, "fund-distribution")
      Kit.waitConfirmed(txId, "fund-distribution")
      println(s"lender funded ${LENDER_AMT / 1e9} ERG, keeper funded ${KEEPER_AMT / 1e9} ERG")
      ()
    }
  }
}
