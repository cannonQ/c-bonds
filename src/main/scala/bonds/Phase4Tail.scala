package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId

/** Salvage tail for a RunPhase4 run that died mid-flow (Phase3Tail
  * precedent). Resumes from live bond ids, deriving every height from
  * the bonds' own registers:
  *   H1: pay remaining coupons (KEEPER-paid — the third-party liveness
  *   path needs no borrower funds; D15's on-chain proof either way),
  *   then the borrower's final repay.
  *   H2: wait out the missed-coupon grace, keeper missed-accel.
  *   D4: the race bond, post to cleanup, verbatim from RunPhase4.
  * Usage: Phase4Tail <h1BondId> <h2BondId>
  */
object Phase4Tail {
  import Contracts._

  def main(args: Array[String]): Unit = {
    require(args.length >= 1, "usage: Phase4Tail <h1BondId> <h2BondId> | d4only")
    val d4only = args(0) == "d4only"
    val lenderP2pkTree = Kit.exec { ctx =>
      TestLib.verifyWallets(ctx)
      TestLib.lender(ctx).getEip3Addresses.get(0).toErgoContract.getErgoTree
    }
    val lenderTreeBytes = lenderP2pkTree.bytes
    if (!d4only) runH(args(0), args(1), lenderP2pkTree)
    runD4(lenderTreeBytes, lenderP2pkTree)
  }

  def runH(h1Start: String, h2: String,
           lenderP2pkTree: sigmastate.Values.ErgoTree): Unit = {
    var h1 = h1Start
    println("=== H1: carded covenant installment loan (tail resume) ===")
    // Pay every remaining interior coupon keeper-side, waiting on each
    // checkpoint as derived from the live successor.
    var s1 = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(h1)(0)) }
    while (s1(2) > 1L) {
      require(s1(3) > 0L, s"H1 tail: bond in cure state (${s1(3)}) — cure first")
      Kit.waitForHeight(s1(3).toInt + 2)
      h1 = P4.doCoupon(h1, s"H1 coupon (payments ${s1(2)}, third-party keeper, D15)",
        proverOf = TestLib.keeper, expectHealthy = true)
      s1 = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(h1)(0)) }
    }
    val h1Exit = TestLib.doExit(h1, lenderP2pkTree, asRepay = true,
      "H1 final repay (installment bond, sched(2)==1)", TestLib.borrower)
    println(s"H1 complete: exit $h1Exit")

    println("=== H2: covenant-off installment loan (missed coupon) ===")
    val (h2chk, h2grace) = Kit.exec { ctx =>
      val s = TestLib.schedOf(ctx.getBoxesById(h2)(0)); (s(3), P4.graceOf(s))
    }
    Kit.waitForHeight((h2chk + h2grace).toInt + 2)
    val h2Exit = P4.doMissedAccel(h2, "H2 missed-accel (grace expiry)")
    println(s"H2 complete: exit $h2Exit")
  }

  def runD4(lenderTreeBytes: Array[Byte],
            lenderP2pkTree: sigmastate.Values.ErgoTree): Unit = {
    // ---- D4: the race, verbatim from RunPhase4 ----
    println("=== D4: late coupon vs missed-payment acceleration (mempool race) ===")
    val d4Order = P4.postOrderV3(
      collateral = RunPhase4.H2_COLLATERAL, term = RunPhase4.D4_TERM,
      period = RunPhase4.D4_PERIOD,
      installment = P4.INSTALLMENT, label = "D4 post-order-v3")
    val (d4Bond, d4Maturity) = P4.doMatchV3(d4Order, lenderTreeBytes, RunPhase4.D4_TERM,
      None, "D4 match-order-v3")
    val d4Deadline = d4Maturity - RunPhase4.D4_TERM + RunPhase4.D4_PERIOD.toInt + GRACE_BLOCKS.toInt

    Kit.waitForHeight(d4Deadline + 1)
    val (winner, succIfCoupon) = Kit.exec { ctx =>
      val bond   = ctx.getBoxesById(d4Bond)(0)
      val b      = TestLib.borrower(ctx)
      val k      = TestLib.keeper(ctx)
      val bAddr  = b.getEip3Addresses.get(0)
      val kAddr  = k.getEip3Addresses.get(0)
      val coupon = b.sign(P4.buildCoupon(ctx, bond,
        P4.honestCouponPlan(bond, healthyBranch = true), None, bAddr))
      val accel  = k.sign(P4.buildMissedAccel(ctx, bond,
        P4.honestMissedAccelPlan(bond), kAddr))
      require(accel.getSignedInputs.size == 1, "D4 accel must be self-funding")
      Kit.sendSafe(ctx, coupon, "D4 race coupon (late)")
      try { Kit.sendSafe(ctx, accel, "D4 race missed-accel") }
      catch { case e: Exception =>
        println(s"  D4 missed-accel refused at submit (mempool conflict): ${e.getMessage}") }
      var tries = 0
      while (!Kit.txConfirmed(coupon.getId) && !Kit.txConfirmed(accel.getId) && tries < 80) {
        Thread.sleep(15000); tries += 1
      }
      val couponWon = Kit.txConfirmed(coupon.getId)
      val accelWon  = Kit.txConfirmed(accel.getId)
      require(couponWon != accelWon, s"D4: exactly one winner expected (coupon=$couponWon accel=$accelWon)")
      println(s"  D4 winner: ${if (couponWon) "coupon" else "missed-accel"} — first confirmation wins, as pinned")
      val loser = if (couponWon) accel else coupon
      val resubmit = scala.util.Try(ctx.sendTransaction(loser))
      require(resubmit.isFailure, "D4: the losing tx must invalidate cleanly against the spent bond")
      println("  D4 loser invalidates clean (double-spend rejected) — PASS")
      (if (couponWon) "coupon" else "accel",
       if (couponWon) coupon.getOutputsToSpend.get(0).getId.toString else "")
    }

    if (winner == "coupon") {
      println("=== D4 cleanup: coupon won — pay down the schedule ===")
      var cur = succIfCoupon
      var s   = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(cur)(0)) }
      while (s(2) > 1L) {
        cur = P4.doCoupon(cur, s"D4 cleanup coupon (payments ${s(2)})")
        s   = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(cur)(0)) }
      }
      val d4Exit = TestLib.doExit(cur, lenderP2pkTree, asRepay = true,
        "D4 cleanup repay", TestLib.borrower)
      println(s"D4 cleanup complete: $d4Exit")
    }

    println("\n=== Phase 4 happy paths complete: H1, H2, D4 green ===")
  }
}
