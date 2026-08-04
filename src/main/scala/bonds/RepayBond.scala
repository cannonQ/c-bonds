package bonds

/** Recover a stray bond by honest borrower repay (collateral + residual
  * escrow ride back as change; repayment + receipt to the vault).
  * Usage: runMain bonds.RepayBond <bondBoxId>
  * Exists because a run that dies between match and cleanup leaves a live
  * bond on-chain (first use: the OOM-killed run-2's local-wall bond).
  */
object RepayBond {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "args: <bondBoxId>")
    TestLib.doExit(args(0), TestLib.vaultTree(), asRepay = true,
      "orphan-recovery repay", TestLib.borrower)
    println("orphan bond recovered")
  }
}
