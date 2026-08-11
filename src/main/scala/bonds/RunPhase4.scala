package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import scala.collection.JavaConverters._

/** Phase 4 happy paths + the D4 mempool race, mainnet dust (REV3-KICKOFF
  * §5): H1 — 3-coupon carded covenant loan to completion including one
  * unhealthy-checkpoint coupon -> cure -> resume (Card A "T2-as-card",
  * the catalog's first real product, proving the copy path on-chain);
  * H2 — missed coupon #2 -> missed-payment acceleration at grace expiry;
  * D4 — late coupon vs missed-payment acceleration submitted together:
  * one winner, loser invalidates clean (B13/C11 pattern, on-chain).
  *
  * Wait-order discipline (Phase 3 tuition): events are serviced in
  * strictly increasing height order across both H bonds; H1 completes
  * before H2's acceleration; D4 runs alone at the end. Waits are always
  * checkpoint + 2 / deadline + 2, never the exact height. All terms are
  * ≡ 0 mod period with wide post-deadline windows (no maturity-edge
  * couplings).
  *
  * The walls (Phase4WallD, Phase4WallE) are separate mains, run before
  * this (they are local reduces + order posts only). This file is the
  * only place real coupons/accelerations move dust.
  */
object RunPhase4 {
  import Contracts._

  // H1 sizing: ergLeg 10M satisfies MIN_ORDER_VALUE net of escrow;
  // repayment 20M puts the 700-RSN fixture's ratio (~150%) UNDER the
  // 20000 bps threshold at origination, so coupon 1 takes the cure
  // branch by construction; the cure raises ergLeg past the boundary
  // with a +2M margin against pool drift. Installment 6M != bounty 5M
  // (decision 7).
  val H1_PRINCIPAL  = 10000000L
  val H1_REPAYMENT  = 20000000L
  val H1_COLLATERAL = 10000000L
  val H1_RSN        = 700L
  val H1_THRESHOLD  = 20000L
  val H1_PERIOD     = 8L
  val H1_TERM       = 32               // K = 3, payments = 4

  val H2_COLLATERAL = 10000000L
  val H2_PERIOD     = 8L
  val H2_TERM       = 40               // K = 4, payments = 5; accel at +26, maturity +40

  val D4_PERIOD     = 4L
  val D4_TERM       = 20               // K = 4, payments = 5; race opens at +14

  def main(args: Array[String]): Unit = {
    val lenderP2pkTree = Kit.exec { ctx =>
      TestLib.verifyWallets(ctx)
      TestLib.lender(ctx).getEip3Addresses.get(0).toErgoContract.getErgoTree
    }
    val lenderTreeBytes = lenderP2pkTree.bytes

    println("=== CARDS: mint the test catalog ===")
    val (cardABox, cardANft) = P4.mintCard("c-bonds T2", "full covenant card at compiled values",
      P4.CARD_A_R7, P4.explicitCardR8, "card-mint-A")

    println("=== H1: carded covenant installment loan ===")
    val h1Order = P4.postOrderV3(
      collateral = H1_COLLATERAL, principal = H1_PRINCIPAL, repayment = H1_REPAYMENT,
      term = H1_TERM, collTokens = Seq(new ErgoToken(P3.RSN_ID, H1_RSN)),
      period = H1_PERIOD, thresholdBps = H1_THRESHOLD, installment = P4.INSTALLMENT,
      cardPin = ErgoId.create(cardANft).getBytes, label = "H1 post-order-v3")
    val (h1Bond0, h1Maturity) = P4.doMatchV3(h1Order, lenderTreeBytes, H1_TERM,
      Some(cardABox), "H1 match-order-v3(carded, 1 data input)")
    val h1Grid1 = h1Maturity - H1_TERM + H1_PERIOD.toInt

    println("=== H2: covenant-off installment loan (missed coupon) ===")
    val h2Order = P4.postOrderV3(
      collateral = H2_COLLATERAL, term = H2_TERM, period = H2_PERIOD,
      installment = P4.INSTALLMENT, label = "H2 post-order-v3")
    val (h2Bond0, h2Maturity) = P4.doMatchV3(h2Order, lenderTreeBytes, H2_TERM,
      None, "H2 match-order-v3(card-less)")
    val h2Grid1 = h2Maturity - H2_TERM + H2_PERIOD.toInt

    // ---- events in strictly increasing height order ----

    // H1 coupon 1: the pool prices the bond UNHEALTHY at 20000 — the
    // coupon is accepted and the successor enters cure encoding
    // (payment taken, sched(2) decremented, sched(3) flipped negative).
    Kit.waitForHeight(h1Grid1 + 2)
    val h1Bond1 = P4.doCoupon(h1Bond0, lenderTreeBytes, "H1 coupon 1 (unhealthy->cure)",
      expectHealthy = false)

    // H1 cure: computed live — enough ergLeg to clear the threshold
    // with a +2M drift margin; escrow untouched; grid restored to the
    // checkpoint-2 point. (P3.doCure's compiled GRACE_BLOCKS equals
    // Card A's explicit grace — the card is the compiled values.)
    val cureAdd = Kit.exec { ctx =>
      val bond = ctx.getBoxesById(h1Bond1)(0)
      val s    = TestLib.schedOf(bond)
      val need = P3.ergLegForHealthy(P3.poolBox(ctx), H1_RSN, H1_REPAYMENT, H1_THRESHOLD)
      val cur  = bond.getValue - s(5)
      math.max(need - cur, 0L) + 2000000L
    }
    val h1Bond2 = P3.doCure(h1Bond1, cureAdd, "H1 cure (health restored, grid resumed)")

    // H2 coupon 1 (its grid runs ~2 blocks behind H1's).
    Kit.waitForHeight(h2Grid1 + 2)
    val h2Bond1 = P4.doCoupon(h2Bond0, lenderTreeBytes, "H2 coupon 1")

    // H1 coupon 2 — healthy after the cure.
    Kit.waitForHeight(h1Grid1 + H1_PERIOD.toInt + 2)
    val h1Bond2b = P4.doCoupon(h1Bond2, lenderTreeBytes, "H1 coupon 2", expectHealthy = true)

    // H1 coupon 3 — paid by the KEEPER wallet: the on-chain third-party
    // liveness proof (D15; decision 1a).
    Kit.waitForHeight(h1Grid1 + 2 * H1_PERIOD.toInt + 2)
    val h1Bond3 = P4.doCoupon(h1Bond2b, lenderTreeBytes, "H1 coupon 3 (third-party keeper, D15)",
      proverOf = TestLib.keeper, expectHealthy = true)

    // H1 final payment: sched(2) == 1 — the repay exit IS the release.
    val h1Exit = TestLib.doExit(h1Bond3, lenderP2pkTree, asRepay = true,
      "H1 final repay (installment bond, sched(2)==1)", TestLib.borrower)
    println(s"H1 complete: exit $h1Exit")

    // H2: coupon #2 is MISSED — nothing to do but wait out the grace.
    // Deadline = checkpoint 2 = grid1 + period; accel opens at +grace.
    Kit.waitForHeight(h2Grid1 + H2_PERIOD.toInt + GRACE_BLOCKS.toInt + 2)
    val h2Exit = P4.doMissedAccel(h2Bond1, lenderTreeBytes, "H2 missed-accel (grace expiry)")
    println(s"H2 complete: exit $h2Exit")

    // ---- D4: the race, on its own bond ----
    println("=== D4: late coupon vs missed-payment acceleration (mempool race) ===")
    val d4Order = P4.postOrderV3(
      collateral = H2_COLLATERAL, term = D4_TERM, period = D4_PERIOD,
      installment = P4.INSTALLMENT, label = "D4 post-order-v3")
    val (d4Bond, d4Maturity) = P4.doMatchV3(d4Order, lenderTreeBytes, D4_TERM,
      None, "D4 match-order-v3")
    val d4Deadline = d4Maturity - D4_TERM + D4_PERIOD.toInt + GRACE_BLOCKS.toInt

    Kit.waitForHeight(d4Deadline + 1)
    val (winner, succIfCoupon) = Kit.exec { ctx =>
      val bond   = ctx.getBoxesById(d4Bond)(0)
      val b      = TestLib.borrower(ctx)
      val k      = TestLib.keeper(ctx)
      val bAddr  = b.getEip3Addresses.get(0)
      val kAddr  = k.getEip3Addresses.get(0)
      val coupon = b.sign(P4.buildCoupon(ctx, bond,
        P4.honestCouponPlan(bond, lenderTreeBytes, healthyBranch = true), None, bAddr))
      val accel  = k.sign(P4.buildMissedAccel(ctx, bond,
        P4.honestMissedAccelPlan(bond, lenderTreeBytes), kAddr))
      require(accel.getSignedInputs.size == 1, "D4 accel must be self-funding")
      Kit.sendSafe(ctx, coupon, "D4 race coupon (late)")
      try { Kit.sendSafe(ctx, accel, "D4 race missed-accel") }
      catch { case e: Exception =>
        println(s"  D4 missed-accel refused at submit (mempool conflict): ${e.getMessage}") }
      // Wait until one of the two confirms.
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

    // Cleanup: if the coupon won, the schedule is live again — pay it
    // down (checkpoints are all in the past, so coupons chain
    // immediately) and take the final repay exit to the lender script
    // (rev 4: R8(0) is only its hash — the tree comes from the harness).
    if (winner == "coupon") {
      println("=== D4 cleanup: coupon won — pay down the schedule ===")
      var cur = succIfCoupon
      var s   = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(cur)(0)) }
      while (s(2) > 1L) {
        cur = P4.doCoupon(cur, lenderTreeBytes, s"D4 cleanup coupon (payments ${s(2)})")
        s   = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(cur)(0)) }
      }
      val d4Exit = TestLib.doExit(cur, lenderP2pkTree, asRepay = true,
        "D4 cleanup repay", TestLib.borrower)
      println(s"D4 cleanup complete: $d4Exit")
    }

    println("\n=== Phase 4 happy paths complete: H1, H2, D4 green ===")
  }
}
