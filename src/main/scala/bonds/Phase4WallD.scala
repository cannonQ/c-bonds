package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 4 instalment adversarial wall, D1-D15, numbered per
  * PHASE4-DECISIONS.md "Adversarial suite skeleton". SPEC-FIRST: written
  * from PHASE4-DECISIONS.md + REV3-LAYOUT.md BEFORE the rev-3 contract
  * exists — every negative here is the failing spec the contract must
  * satisfy, not a description of code.
  *
  * Pattern (Phase 2/3 discipline carried forward):
  *  - signatureless-path attacks (coupon, missed-accel) use expectRejected
  *    with the KEEPER prover — it holds keeper secrets only, and funds the
  *    installment leg from the keeper wallet;
  *  - borrower-signed attacks (repay/cure/top-up shapes) use
  *    expectScriptFalse with the borrower prover;
  *  - every negative that the spec twins has its minimally-differing
  *    pass-twin immediately adjacent (label suffix -twin);
  *  - withheld/stolen tokens are routed to the ATTACKER's box so the
  *    SCRIPT does the rejecting, never the builder;
  *  - windows are pinned deterministically with pre-headers; boundaries
  *    are only tested AT the edge deliberately (D8-twin at exactly
  *    deadline + grace).
  *
  * Bond boxes are fabricated locally (fabBondV3, never submitted); every
  * covenant verdict prices against the LIVE pool box as its data input,
  * with the expected branch asserted by healthyV3 requires before each
  * probe (Phase3Gate discipline). Nothing malformed is ever submitted;
  * the only on-chain transactions are D9's order posts and cancels.
  */
object Phase4WallD {
  import Contracts._

  val REPAY:  Long = 15000000L
  val PERIOD: Long = 20L

  def run(): Unit = Kit.exec { ctx =>
    println("=== Phase 4 D-wall: instalment adversarial suite (D1-D15) ===")
    val h  = ctx.getHeight
    val bp = TestLib.borrower(ctx)
    val lp = TestLib.lender(ctx)
    val kp = TestLib.keeper(ctx)
    val bAddr = bp.getEip3Addresses.get(0)
    val lAddr = lp.getEip3Addresses.get(0)
    val kAddr = kp.getEip3Addresses.get(0)
    val borrowerBytes = bAddr.toErgoContract.getErgoTree.bytes
    val lenderTree    = lAddr.toErgoContract.getErgoTree
    val lenderBytes   = lenderTree.bytes
    val keeperBytes   = kAddr.toErgoContract.getErgoTree.bytes
    val poolNftBytes  = ErgoId.create(POOL_NFT).getBytes
    val pool          = P3.poolBox(ctx)
    val maturity      = h + 500
    println(s"height $h  pool ${pool.getId}")

    // ---------------- fixtures ----------------
    // Covenant-OFF instalment bond (mirror of the Phase3Gate fab, Phase 4
    // shape): 3 interior coupons + final bullet (payments = 4), escrow =
    // 3 bounties, checkpoint due 5 blocks ago -> coupon window OPEN at h
    // (sched(3) <= H < maturity), missed-accel CLOSED until h + 5.
    val schedOff = Array[Long](P4.INSTALLMENT, PERIOD, 4L, (h - 5).toLong, 0L, 15000000L)
    val bondOff  = P4.fabBondV3(ctx, schedOff, Seq(lenderBytes), borrowerBytes,
      35000000L, REPAY, maturity)
    // covenantOff coupons take the advance pack unconditionally (decision 1)
    val honestOff = P4.honestCouponPlan(bondOff, healthyBranch = true)

    // Covenant-ON instalment fixture pieces (Phase3Gate lines 52-66 recipe:
    // value 15M = 5M ERG leg + 10M escrow, 700 raw RSN, repayment 15M;
    // threshold 15000 prices healthy / 20000 unhealthy vs live reserves;
    // R9 stays the 6-element card-less form, so covenant verdicts use the
    // compiled haircut).
    val rsn700 = Seq(new ErgoToken(P3.RSN_ID, 700L))
    val covR8  = Seq(lenderBytes, poolNftBytes)

    // ---------------- D1 ----------------
    println("=== D1: coupon installment short 1 nanoERG ===")
    Kit.expectRejected("D1 coupon installment short 1 nanoERG") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(instValue = schedOff(0) - 1L), None, kAddr, Some(h)))
    }
    Kit.expectReduces("D1-twin exact-installment coupon reduces") {
      kp.reduce(P4.buildCoupon(ctx, bondOff, honestOff, None, kAddr, Some(h)), 0).getCost
    }

    // ---------------- D2 ----------------
    println("=== D2: successor paymentsRemaining discipline (whole-pack equality) ===")
    val d2Pack = P4.couponAdvancePack(schedOff); d2Pack(2) = schedOff(2)
    Kit.expectRejected("D2 successor sched(2) not decremented") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(succR9 = d2Pack), None, kAddr, Some(h)))
    }
    val d2bPack = P4.couponAdvancePack(schedOff); d2bPack(2) = schedOff(2) - 2L
    Kit.expectRejected("D2b successor sched(2) decremented twice") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(succR9 = d2bPack), None, kAddr, Some(h)))
    }
    // (pass-twin: D1-twin above is the honest whole-pack shape.)

    // ---------------- D3 ----------------
    println("=== D3: repay-shaped release gated on paymentsRemaining <= 1 ===")
    // Rev-3 fabs: TestLib.repayPlan reads a bare R8 tree, but the rev-3 R8
    // is a pack — build the ExitPlan by hand with exitTree = the lender
    // P2PK tree used at R8(0).
    def repayShapedPlan(bond: InputBox): TestLib.ExitPlan =
      TestLib.ExitPlan(lenderTree, REPAY, Some(bond.getId.getBytes),
        Seq(new ErgoToken(bond.getTokens.get(0).getId, 1L)))
    // Two coupons already serviced: value/escrow down two bounties.
    val schedS2 = Array[Long](P4.INSTALLMENT, PERIOD, 2L, (h - 5).toLong, 0L, 5000000L)
    val bondS2  = P4.fabBondV3(ctx, schedS2, Seq(lenderBytes), borrowerBytes,
      25000000L, REPAY, maturity)
    Kit.expectScriptFalse("D3 repay-shaped release at sched(2) == 2 (interior coupon still owed)") {
      bp.sign(TestLib.buildExit(ctx, bondS2, repayShapedPlan(bondS2), bp, Some(h)))
    }
    // All three interior coupons serviced: escrow exhausted, repay opens.
    val schedS1 = Array[Long](P4.INSTALLMENT, PERIOD, 1L, (h - 5).toLong, 0L, 0L)
    val bondS1  = P4.fabBondV3(ctx, schedS1, Seq(lenderBytes), borrowerBytes,
      20000000L, REPAY, maturity)
    Kit.expectReduces("D3-twin same release at sched(2) == 1 reduces (the final bullet IS the repay exit)") {
      bp.reduce(TestLib.buildExit(ctx, bondS1, repayShapedPlan(bondS1), bp, Some(h)), 0).getCost
    }

    // ---------------- D4 (local half) ----------------
    println("=== D4: late coupon vs missed-accel — both paths open past deadline+grace ===")
    // LOCAL HALF ONLY: both spends of the same bond reduce at the SAME
    // pre-header height (C11 first-confirmation-wins pattern). The
    // on-chain mempool race itself — one winner, loser invalidates clean —
    // lives in RunPhase4, not here.
    val schedLate = Array[Long](P4.INSTALLMENT, PERIOD, 4L, (h - 20).toLong, 0L, 15000000L)
    val bondLate  = P4.fabBondV3(ctx, schedLate, Seq(lenderBytes), borrowerBytes,
      35000000L, REPAY, maturity)
    Kit.expectReduces("D4-twin late coupon past deadline+grace reduces (no grace ceiling on couponOk)") {
      kp.reduce(P4.buildCoupon(ctx, bondLate,
        P4.honestCouponPlan(bondLate, healthyBranch = true), None, kAddr, Some(h)), 0).getCost
    }
    Kit.expectReduces("D4-twin missed-accel at the SAME pre-header reduces (the race is live)") {
      kp.reduce(P4.buildMissedAccel(ctx, bondLate,
        P4.honestMissedAccelPlan(bondLate), kAddr, Some(h)), 0).getCost
    }

    // ---------------- D5 ----------------
    println("=== D5: borrower self-coupon keeps the bounty (PERMITTED, decision 3) ===")
    // The freed bounty rides the borrower's own change: it is their
    // escrowed ERG returning, offsetting the tx fee (self-crank precedent).
    Kit.expectReduces("D5 borrower self-coupon reduces (freed bounty rides borrower change)") {
      bp.reduce(P4.buildCoupon(ctx, bondOff, honestOff, None, bAddr, Some(h)), 0).getCost
    }

    // ---------------- D6 ----------------
    println("=== D6: covenant coupon verdict wall (live pool picks the branch, never the builder) ===")
    val schedCovH = Array[Long](P4.INSTALLMENT, PERIOD, 4L, (h - 5).toLong, 15000L, 10000000L)
    val schedCovU = Array[Long](P4.INSTALLMENT, PERIOD, 4L, (h - 5).toLong, 20000L, 10000000L)
    val bondCovH  = P4.fabBondV3(ctx, schedCovH, covR8, borrowerBytes,
      15000000L, REPAY, maturity, tokens = rsn700)
    val bondCovU  = P4.fabBondV3(ctx, schedCovU, covR8, borrowerBytes,
      15000000L, REPAY, maturity, tokens = rsn700)
    require(!P4.healthyV3(pool, bondCovU.getValue - schedCovU(5), 700L, REPAY, 20000L, HAIRCUT_KEEP),
      "D6 setup: threshold 20000 must price UNHEALTHY against live reserves")
    Kit.expectRejected("D6a builder forces ADVANCE pack while pool prices unhealthy") {
      kp.sign(P4.buildCoupon(ctx, bondCovU,
        P4.honestCouponPlan(bondCovU, healthyBranch = true), Some(pool), kAddr, Some(h)))
    }
    Kit.expectReduces("D6a-twin unhealthy coupon with the CURE pack reduces (coupon taken, cure state entered)") {
      kp.reduce(P4.buildCoupon(ctx, bondCovU,
        P4.honestCouponPlan(bondCovU, healthyBranch = false), Some(pool), kAddr, Some(h)), 0).getCost
    }
    require(P4.healthyV3(pool, bondCovH.getValue - schedCovH(5), 700L, REPAY, 15000L, HAIRCUT_KEEP),
      "D6 setup: threshold 15000 must price HEALTHY against live reserves")
    Kit.expectRejected("D6b builder forces CURE pack while pool prices healthy") {
      kp.sign(P4.buildCoupon(ctx, bondCovH,
        P4.honestCouponPlan(bondCovH, healthyBranch = false), Some(pool), kAddr, Some(h)))
    }
    Kit.expectReduces("D6b-twin healthy coupon with the ADVANCE pack reduces") {
      kp.reduce(P4.buildCoupon(ctx, bondCovH,
        P4.honestCouponPlan(bondCovH, healthyBranch = true), Some(pool), kAddr, Some(h)), 0).getCost
    }

    // ---------------- D7 ----------------
    println("=== D7: cure-shaped spend cannot clear an OVERDUE coupon (structural) ===")
    // Overdue covenant bond: checkpoint 20 blocks ago, grace 10 -> the
    // pre-header (h) sits past sched(3) + grace. sched(3) stays POSITIVE:
    // overdue is derived, never stored (decision 2); cure requires
    // sched(3) < 0, so a top-up-shaped "cure" can never clear the coupon.
    val schedOverdue = Array[Long](P4.INSTALLMENT, PERIOD, 4L, (h - 20).toLong, 20000L, 10000000L)
    val bondOverdue  = P4.fabBondV3(ctx, schedOverdue, covR8, borrowerBytes,
      15000000L, REPAY, maturity, tokens = rsn700)
    val cureAdd = 5000000L
    require(!P4.healthyV3(pool, bondOverdue.getValue - schedOverdue(5), 700L, REPAY, 20000L, HAIRCUT_KEEP),
      "D7 setup: overdue bond must price unhealthy (a cure would otherwise be pointless)")
    require(P4.healthyV3(pool, bondOverdue.getValue - schedOverdue(5) + cureAdd, 700L, REPAY, 20000L, HAIRCUT_KEEP),
      "D7 setup: +0.005 ERG must price healthy at 20000 (only the structural gate may reject)")
    // Restore pack a cure of THIS checkpoint would write: cure restores
    // failedCheckpoint + period; an overdue coupon's failed checkpoint IS
    // sched(3), so the analog restore is sched(3) + period. Everything
    // else about the spend is a valid cure (top-up shape, pool data input,
    // escrow untouched) — only sched(3) > 0 must kill it.
    val d7Restore = schedOverdue.clone(); d7Restore(3) = schedOverdue(3) + schedOverdue(1)
    val d7Cure = {
      val rs    = bondOverdue.getRegisters
      val funds = Kit.selectBoxes(ctx, bAddr, cureAdd + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val tb    = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      val succ = tb.outBoxBuilder()
        .value(bondOverdue.getValue + cureAdd)
        .contract(new ErgoTreeContract(bondOverdue.getErgoTree, NetworkType.MAINNET))
        .tokens(bondOverdue.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(d7Restore))
        .build()
      tb.boxesToSpend((Seq(bondOverdue) ++ funds).asJava)
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(succ)
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
    }
    Kit.expectScriptFalse("D7 cure-shaped spend on a bond with an overdue coupon (cure requires sched(3) < 0)") {
      bp.sign(d7Cure)
    }

    // ---------------- D8 ----------------
    println("=== D8: missed-accel grace boundary ===")
    // bondOff checkpoint = h - 5, grace 10 -> accelerable at exactly h + 5.
    // The at-boundary twin is DELIBERATE (>= semantics pinned at the edge).
    Kit.expectRejected("D8 missed-accel one block before deadline+grace") {
      kp.sign(P4.buildMissedAccel(ctx, bondOff,
        P4.honestMissedAccelPlan(bondOff), kAddr, Some(h + 4)))
    }
    Kit.expectReduces("D8-twin missed-accel at exactly deadline+grace reduces") {
      kp.reduce(P4.buildMissedAccel(ctx, bondOff,
        P4.honestMissedAccelPlan(bondOff), kAddr, Some(h + 5)), 0).getCost
    }

    // ---------------- D9 ----------------
    println("=== D9: order wall — instalment/payments coupling (on-chain posts, local negatives, cancels) ===")
    // C9 pattern: REAL posts, LOCAL match negatives, cancel recovery
    // proving a malformed order stays cancellable. term 720 / period 240
    // keeps K = 2 (escrow 2 bounties) and payments = K + 1 = 3.
    val term9   = 720
    val period9 = 240L
    val k9      = P4.kOf(term9, period9)
    val escrow9 = P4.escrowForWith(CRANK_BOUNTY, term9, period9)
    require(k9 == 2L, s"D9 setup: K must be 2 (got $k9)")

    def d9Tmpl(installment: Long, payments: Long): Array[Long] =
      Array[Long](installment, period9, payments, 0L, 0L, escrow9)

    def d9Negative(tag: String, why: String, installment: Long, payments: Long): Unit = {
      val oid = P4.postOrderV3(term = term9, period = period9, installment = installment,
        templateOverride = Some(d9Tmpl(installment, payments)), label = s"$tag post")
      val ob = ctx.getBoxesById(oid)(0)
      Kit.expectScriptFalse(s"$tag $why unmatchable") {
        lp.sign(P4.buildMatchV3(ctx, ob, lenderBytes, term9, None))
      }
      P4.cancelOrderV3(oid, s"$tag cleanup cancel (malformed stays cancellable)")
    }

    d9Negative("D9a", s"payments == K ($k9, off by one low)", P4.INSTALLMENT, k9)
    d9Negative("D9b", s"payments == K+2 (${k9 + 2}, off by one high)", P4.INSTALLMENT, k9 + 2L)
    d9Negative("D9c", "installment > 0 with payments == 0 (coupling)", P4.INSTALLMENT, 0L)
    d9Negative("D9d", s"installment == 0 with payments == K+1 (coupling)", 0L, k9 + 1L)
    d9Negative("D9e", s"installment ${MIN_COUPON - 1} (sub-MIN_COUPON)", MIN_COUPON - 1L, k9 + 1L)

    // Boundary twin: a VALID instalment order matches locally (reduce
    // only, never submitted), then cancels like the rest.
    val oidT = P4.postOrderV3(term = term9, period = period9, installment = P4.INSTALLMENT,
      label = "D9-twin post (valid instalment order)")
    val obT = ctx.getBoxesById(oidT)(0)
    Kit.expectReduces("D9-twin valid instalment order (installment 6M, payments K+1) match reduces") {
      lp.reduce(P4.buildMatchV3(ctx, obT, lenderBytes, term9, None), 0).getCost
    }
    P4.cancelOrderV3(oidT, "D9-twin cleanup cancel")

    // ---------------- D10 ----------------
    println("=== D10: coupon escrow games (value/escrow lockstep) ===")
    val d10a = P4.couponAdvancePack(schedOff); d10a(5) = schedOff(5)
    Kit.expectRejected("D10a successor escrow kept while value drops one bounty") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(succR9 = d10a), None, kAddr, Some(h)))
    }
    val d10b = P4.couponAdvancePack(schedOff); d10b(5) = schedOff(5) - 2L * CRANK_BOUNTY
    Kit.expectRejected("D10b value down one bounty but escrow down two") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(succR9 = d10b), None, kAddr, Some(h)))
    }
    Kit.expectRejected("D10c value down two bounties with escrow down one (bounty overdraw)") {
      kp.sign(P4.buildCoupon(ctx, bondOff,
        honestOff.copy(succValue = bondOff.getValue - 2L * CRANK_BOUNTY), None, kAddr, Some(h)))
    }
    // (pass-twin: D1-twin is the honest lockstep shape.)

    // ---------------- D11 ----------------
    println("=== D11: one-register-at-a-time mask wall over the coupon successor ===")
    // Standing rule: every new/changed successor shape gets the mask wall.
    def d11Reject(label: String, plan: P4.CouponPlan): Unit =
      Kit.expectRejected(label) {
        kp.sign(P4.buildCoupon(ctx, bondOff, plan, None, kAddr, Some(h)))
      }
    d11Reject("D11 R4 mask (different 32-byte order id)",
      honestOff.copy(succR4Override = Some(ErgoValue.of(ErgoId.create("55" * 32).getBytes))))
    d11Reject("D11 R5 mask (borrower bytes -> keeper tree bytes)",
      honestOff.copy(succR5Override = Some(ErgoValue.of(keeperBytes))))
    d11Reject("D11 R6 mask (repayment + 1)",
      honestOff.copy(succR6Override = Some(ErgoValue.of(REPAY + 1L))))
    d11Reject("D11 R7 mask (maturity + 1)",
      honestOff.copy(succR7Override = Some(ErgoValue.of(maturity + 1))))
    d11Reject("D11 R8 mask (keeper tree at pack element 0)",
      honestOff.copy(succR8Override = Some(P4.packValue(Seq(keeperBytes)))))
    d11Reject("D11 script mask (successor at the ORDER contract)",
      honestOff.copy(succContractOverride = Some(Contracts.order(ctx)._2)))
    d11Reject("D11 token mask (loan token routed to the payer)",
      honestOff.copy(succTokens = bondOff.getTokens.asScala.toSeq.drop(1),
        extraTokensToPayer = Seq(new ErgoToken(bondOff.getTokens.get(0).getId, 1L))))
    // (pass-twin: D1-twin is the unmasked successor.)

    // ---------------- D12 ----------------
    println("=== D12: covenant interactions (cure owns sched(3) < 0) ===")
    // In-cure instalment bond, entered via an unhealthy coupon: that
    // coupon already took its bounty and its decrement (decision 3):
    // value 15M -> 10M, escrow 10M -> 5M, payments 4 -> 3, ERG leg
    // unchanged at 5M, deadline h + 5 still ahead (cure window live).
    val schedInCure = Array[Long](P4.INSTALLMENT, PERIOD, 3L, -(h + 5).toLong, 20000L, 5000000L)
    val bondInCure  = P4.fabBondV3(ctx, schedInCure, covR8, borrowerBytes,
      10000000L, REPAY, maturity, tokens = rsn700)
    require(!P4.healthyV3(pool, bondInCure.getValue - schedInCure(5), 700L, REPAY, 20000L, HAIRCUT_KEEP),
      "D12 setup: in-cure bond must price unhealthy (cure pack is the verdict-true branch)")
    // The attack writes a positive nextCheck back onto the successor —
    // exiting cure through the coupon path without curing health. Only
    // the coupon gate's sched(3) > 0 conjunct may reject it.
    Kit.expectRejected("D12a coupon while in cure (sched(3) < 0 closes the coupon path)") {
      kp.sign(P4.buildCoupon(ctx, bondInCure,
        P4.honestCouponPlan(bondInCure, healthyBranch = false), Some(pool), kAddr, Some(h)))
    }
    Kit.expectScriptFalse("D12b plain top-up during cure still rejects (cure is the only collateral-add)") {
      bp.sign(P2.buildTopUp(ctx, bondInCure, Kit.MIN_BOX_VALUE, bp))
    }
    require(!P4.healthyV3(pool, bondOverdue.getValue - schedOverdue(5), 700L, REPAY, 20000L, HAIRCUT_KEEP),
      "D12 setup: overdue covenant bond must price unhealthy (cure pack is the verdict branch)")
    Kit.expectReduces("D12c-twin late coupon past deadline pre-seizure reduces (couponOk has no grace ceiling)") {
      kp.reduce(P4.buildCoupon(ctx, bondOverdue,
        P4.honestCouponPlan(bondOverdue, healthyBranch = false), Some(pool), kAddr, Some(h)), 0).getCost
    }

    // ---------------- D13 ----------------
    println("=== D13: missed-accel output-shape wall ===")
    // The arm is a new signatureless exit and gets the routing negatives
    // EXPLICITLY, not by inheritance. It calls NO verdict (decision 2), so
    // no health require here; the covenant-shaped fab (bondOverdue) rides
    // only so the RSN token-withhold shape exists.
    val d13Honest = P4.honestMissedAccelPlan(bondOverdue)
    Kit.expectRejected("D13a missed-accel exit short 1 nanoERG (A2 analog)") {
      kp.sign(P4.buildMissedAccel(ctx, bondOverdue,
        d13Honest.copy(exitValue = d13Honest.exitValue - 1L), kAddr, Some(h)))
    }
    Kit.expectRejected("D13b collateral split between lender script and keeper (A4 analog)") {
      kp.sign(P4.buildMissedAccel(ctx, bondOverdue,
        d13Honest.copy(exitValue = d13Honest.exitValue - 2000000L, splitToKeeper = 2000000L),
        kAddr, Some(h)))
    }
    Kit.expectRejected("D13c RSN withheld with ERG exact — routed to the keeper so the SCRIPT rejects (A5 analog)") {
      kp.sign(P4.buildMissedAccel(ctx, bondOverdue,
        d13Honest.copy(tokens = Seq(bondOverdue.getTokens.get(0)),
          extraTokensToKeeper = Seq(new ErgoToken(P3.RSN_ID, 700L))), kAddr, Some(h)))
    }
    Kit.expectRejected("D13d settlement receipt omitted (A7 analog)") {
      kp.sign(P4.buildMissedAccel(ctx, bondOverdue,
        d13Honest.copy(receiptR4 = None), kAddr, Some(h)))
    }
    Kit.expectReduces("D13-twin honest missed-accel reduces") {
      kp.reduce(P4.buildMissedAccel(ctx, bondOverdue, d13Honest, kAddr, Some(h)), 0).getCost
    }

    // ---------------- D14 ----------------
    println("=== D14: coupon installment diverted to a one-byte-off script ===")
    // A1 analog on OUTPUTS(1): the coupon side of the core "payment cannot
    // be diverted from the lender script" invariant.
    val vaultBytes    = TestLib.vaultTree().bytes
    val vaultVarBytes = TestLib.vaultVariantTree().bytes
    val bondVault   = P4.fabBondV3(ctx, schedOff, Seq(vaultBytes), borrowerBytes,
      35000000L, REPAY, maturity)
    val honestVault = P4.honestCouponPlan(bondVault, healthyBranch = true)
    Kit.expectRejected("D14 installment paid to a script one byte off R8(0)") {
      kp.sign(P4.buildCoupon(ctx, bondVault,
        honestVault.copy(instTree = vaultVarBytes), None, kAddr, Some(h)))
    }
    Kit.expectReduces("D14-twin installment to the exact R8(0) script reduces") {
      kp.reduce(P4.buildCoupon(ctx, bondVault, honestVault, None, kAddr, Some(h)), 0).getCost
    }

    // ---------------- D15 ----------------
    println("=== D15: third-party coupon (signatureless liveness, decision 1a) ===")
    // The 1a claim by name: a servicer / Good Samaritan can pay the
    // borrower's installment — keeper wallet funds, keeper prover, honest
    // plan. The on-chain flavor rides H1's coupon 3 in RunPhase4.
    Kit.expectReduces("D15 keeper-paid coupon reduces (third party services the borrower's schedule)") {
      kp.reduce(P4.buildCoupon(ctx, bondOff, honestOff, None, kAddr, Some(h)), 0).getCost
    }

    println("=== Phase 4 D-wall COMPLETE: D1-D15 green (43 assertions: 30 negatives, 13 pass-twins/permitted) ===")
    ()
  }

  def main(args: Array[String]): Unit = run()
}
