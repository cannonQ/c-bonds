package bonds

import org.ergoplatform.appkit._
import scala.collection.JavaConverters._

/** One-off dust transfer between two test roles.
  * Usage: runMain bonds.Transfer <FROM> <TO> <ergAmount>
  * e.g.   runMain bonds.Transfer BORROWER LENDER 0.04
  */
object Transfer {
  private def prover(ctx: BlockchainContext, role: String): ErgoProver = role.toUpperCase match {
    case "BORROWER" => TestLib.borrower(ctx)
    case "LENDER"   => TestLib.lender(ctx)
    case "KEEPER"   => TestLib.keeper(ctx)
    case other      => sys.error(s"unknown role $other")
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 3, "args: <FROM> <TO> <ergAmount>")
    val amount = (args(2).toDouble * 1e9).toLong
    Kit.exec { ctx =>
      val from  = prover(ctx, args(0)); val fAddr = from.getEip3Addresses.get(0)
      val to    = prover(ctx, args(1)); val tAddr = to.getEip3Addresses.get(0)
      val ins   = Kit.selectBoxes(ctx, fAddr, amount + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val tb    = ctx.newTxBuilder()
      val out   = tb.outBoxBuilder().value(amount).contract(tAddr.toErgoContract).build()
      val tx    = tb.boxesToSpend(ins.asJava).outputs(out).fee(Kit.TX_FEE).sendChangeTo(fAddr).build()
      val txId  = Kit.sendSafe(ctx, from.sign(tx), s"transfer-${args(0)}->${args(1)}")
      Kit.waitConfirmed(txId, "transfer")
      println(f"${args(0)} ${Kit.balance(fAddr) / 1e9}%.4f  |  ${args(1)} ${Kit.balance(tAddr) / 1e9}%.4f")
      ()
    }
  }
}
