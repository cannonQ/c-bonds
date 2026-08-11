package bonds

import org.ergoplatform.sdk.ErgoToken

/** Phase 3 happy path on mainnet (kickoff §6.5). Two covenant bonds
  * against the live pinned pool:
  *
  *   Bond B (term 24, period 8, K = 2):
  *     checkpoint 1 -> UNHEALTHY crank (enters cure state)
  *     cure by ERG top-up (back on grid)
  *     checkpoint 2 -> HEALTHY crank (Phase 2-shape advance, escrow -> 0)
  *     repay (Phase 1 exit wall across the covenant successor chain)
  *
  *   Bond C (term 30, period 8, K = 3):
  *     checkpoint 1 -> UNHEALTHY crank
  *     grace blown (no cure)
  *     ACCELERATION (signatureless early default, 100% minus carve-out
  *     to the lender script, residual escrow forfeits to the lender)
  *
  * The threshold is chosen at run time from live reserves so the real
  * pool prices the planned bonds unhealthy by a wide margin (~30% price
  * cushion each way), and every verdict the harness builds is asserted
  * against the same pool box the tx carries as its data input.
  *
  * Collateral: 500 raw RSN per bond + the ERG leg. One-time acquisition
  * swap runs first if the borrower wallet is short.
  */
object RunPhase3 {
  import Contracts._

  val RSN_PER_BOND: Long = 500L
  val ERG_LEG: Long      = MIN_ORDER_VALUE          // 0.010 ERG collateral floor
  val PRINCIPAL: Long    = 12000000L                // 0.012
  val REPAYMENT: Long    = 15000000L                // 0.015
  val PERIOD: Long       = 8L

  def main(args: Array[String]): Unit = {
    println("=== Phase 3 happy path (mainnet, live pool) ===")
    Kit.exec { ctx => TestLib.verifyWallets(ctx); () }

    // -- setup: pool state + threshold selection + RSN inventory --------
    val (threshold, cureAdd) = Kit.exec { ctx =>
      val pool = P3.poolBox(ctx)
      val (rX, rY, feeNum) = P3.reserves(pool)
      println(f"pool ${pool.getId}: rX ${rX / 1e9}%.1f ERG, rY $rY raw RSN, fee $feeNum")

      val ratio = P3.ratioBps(pool, ERG_LEG, RSN_PER_BOND, REPAYMENT)
      val thr   = math.min(30000L, math.max(10000L, ratio + 3000L))
      require(!P3.healthy(pool, ERG_LEG, RSN_PER_BOND, REPAYMENT, thr),
        s"setup: threshold $thr must price the planned bond UNHEALTHY (ratio $ratio)")
      // Cure sizing: ERG needed to clear the threshold, +0.002 cushion so
      // ordinary reserve drift cannot flip the healthy leg mid-run.
      val needLeg = P3.ergLegForHealthy(pool, RSN_PER_BOND, REPAYMENT, thr)
      val add     = (needLeg - ERG_LEG) + 2000000L
      require(P3.healthy(pool, ERG_LEG + add, RSN_PER_BOND, REPAYMENT, thr),
        "setup: planned cure must price HEALTHY")
      println(s"ratio $ratio bps -> threshold $thr; cure top-up $add nanoERG")

      val bAddr = TestLib.borrower(ctx).getEip3Addresses.get(0)
      val have  = P3.rsnBalance(ctx, bAddr)
      println(s"borrower RSN inventory: $have raw (need ${2 * RSN_PER_BOND})")
      if (have < 2 * RSN_PER_BOND) {
        println("acquiring RSN (one-time swap)...")
        P3.acquireRsn(40000000L) // 0.04 ERG -> ~1250 raw RSN
      }
      (thr, add)
    }

    val vault = TestLib.vaultTree()
    val rsn   = Seq(new ErgoToken(P3.RSN_ID, RSN_PER_BOND))

    // -- originations (both bonds up front; checkpoints land together) --
    println("--- T-P3-0: originate covenant bonds B and C ---")
    val orderB = TestLib.postOrder(collateral = ERG_LEG, principal = PRINCIPAL,
      repayment = REPAYMENT, term = 24, collTokens = rsn, period = PERIOD,
      thresholdBps = threshold)
    val (bondB, matB) = TestLib.matchOrder(orderB, vault, 24)
    val orderC = TestLib.postOrder(collateral = ERG_LEG, principal = PRINCIPAL,
      repayment = REPAYMENT, term = 30, collTokens = rsn, period = PERIOD,
      thresholdBps = threshold)
    val (bondC, matC) = TestLib.matchOrder(orderC, vault, 30)
    val chkB1 = (matB - 24) + PERIOD.toInt   // = matB - 16
    val chkB2 = chkB1 + PERIOD.toInt         // = matB - 8
    val chkC1 = (matC - 30) + PERIOD.toInt   // = matC - 22
    println(s"bond B $bondB (maturity $matB, checkpoints $chkB1, $chkB2)")
    println(s"bond C $bondC (maturity $matC, checkpoint $chkC1, K=3)")

    // -- checkpoint 1: both bonds priced unhealthy -> cure state --------
    println("--- T-P3-1: unhealthy checkpoint cranks (cure state opens) ---")
    Kit.waitForHeight(math.max(chkB1, chkC1) + 2)
    val bondB1 = P3.doCovenantCrank(bondB, expectHealthy = false,
      "P3 crank covenant UNHEALTHY (B chk1, keeper)")
    val bondC1 = P3.doCovenantCrank(bondC, expectHealthy = false,
      "P3 crank covenant UNHEALTHY (C chk1, keeper)")
    val deadlineC = chkC1 + GRACE_BLOCKS.toInt

    // -- bond B: cure inside grace --------------------------------------
    println("--- T-P3-2: cure by top-up (B, inside grace) ---")
    val bondB2 = P3.doCure(bondB1, cureAdd, "P3 cure top-up (B)")

    // -- bond B checkpoint 2 (healthy now) ------------------------------
    // Crank B in ITS OWN window, before waiting on C's deadline: B's
    // window closes at maturity and run 1 proved that coupling the two
    // waits can push the crank build past the last viable block (a tx
    // built at maturity-1 can only confirm at maturity, where the gate
    // is dead).
    println("--- T-P3-3: healthy checkpoint (B chk2) ---")
    Kit.waitForHeight(chkB2 + 2)
    val bondB3 = P3.doCovenantCrank(bondB2, expectHealthy = true,
      "P3 crank covenant HEALTHY (B chk2, keeper)")

    println("--- T-P3-4: acceleration (C, signatureless early default) ---")
    Kit.waitForHeight(deadlineC + 2)
    val exitC = P3.doAccelerate(bondC1, vault.bytes, "P3 accelerate (C, keeper)")

    // -- bond B repay: Phase 1 exit wall across the covenant chain ------
    println("--- T-P3-5: repay B (exit wall across covenant successors) ---")
    val exitB = TestLib.doExit(bondB3, vault, asRepay = true,
      "P3 repay of cured+cranked covenant bond (B)", TestLib.borrower)

    println("=== Phase 3 happy path COMPLETE ===")
    println(s"  B: $bondB -> unhealthy crank -> cure -> healthy crank -> repay $exitB")
    println(s"  C: $bondC -> unhealthy crank -> grace blown -> accelerate $exitC")
  }
}
