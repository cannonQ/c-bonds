package bonds

/** Tail of the happy path, run standalone after the batch run's wait
  * logic coupled B's final crank to C's deadline and would have missed
  * B's window (crank gate dies at maturity; the batch waited to
  * maturity-1). Correct order: crank B NOW (window open), then wait for
  * C's blown grace, accelerate, repay B. Box ids from phase3.log.
  */
object Phase3Tail {
  def main(args: Array[String]): Unit = {
    val bondB2 = args(0)  // B post-cure successor (nextCheck on grid)
    val bondC1 = args(1)  // C in-cure successor (deadline blown soon)
    val accelAt = args(2).toInt
    // Rev 4: the acceleration exit destination comes from the harness's
    // own vault tree — the bond only stores its blake2b256.
    val vault = TestLib.vaultTree()

    println("--- T-P3-3: healthy checkpoint crank (B chk2, keeper) ---")
    val bondB3 = P3.doCovenantCrank(bondB2, expectHealthy = true,
      "P3 crank covenant HEALTHY (B chk2, keeper)")

    println("--- T-P3-4: acceleration (C, signatureless early default) ---")
    Kit.waitForHeight(accelAt)
    val exitC = P3.doAccelerate(bondC1, vault.bytes, "P3 accelerate (C, keeper)")

    println("--- T-P3-5: repay B (exit wall across covenant successors) ---")
    val exitB = TestLib.doExit(bondB3, vault, asRepay = true,
      "P3 repay of cured+cranked covenant bond (B)", TestLib.borrower)

    println("=== Phase 3 happy path COMPLETE ===")
    println(s"  B: unhealthy crank -> cure -> healthy crank -> repay $exitB")
    println(s"  C: unhealthy crank -> grace blown -> accelerate $exitC")
  }
}
