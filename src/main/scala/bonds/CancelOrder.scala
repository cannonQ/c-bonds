package bonds

/** Recover a stray order by borrower cancel (collateral + escrow back).
  * Usage: runMain bonds.CancelOrder <orderBoxId>
  * Exists because a run that dies between post-order and its cleanup
  * leaves a live order on-chain (first use: the A9 lender-funding abort).
  */
object CancelOrder {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "args: <orderBoxId>")
    P2.cancelOrder(args(0), "stray-order cancel")
  }
}
