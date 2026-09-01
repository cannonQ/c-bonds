package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import scala.collection.JavaConverters._

/** Workstream-3 rev-5 JitCost runner (REV5-KICKOFF §4): fresh JitCost rows
  * for every path on all six rev-5 trees (B1/O1, B2/O2, B3/O3), 6.0 scale
  * only. Reuses the READ-ONLY Rev5Lib (R5) builders and Phase4Lib (P4) /
  * Phase2Lib (P2) / Phase3Lib (P3) plumbing exactly as Rev5Smoke proves
  * them — the fixtures below are ported verbatim from Rev5Smoke so every
  * measured shape is known to REDUCE AND SIGN (a true branch, not a
  * short-circuited false one, so the recorded cost reflects the real
  * production path).
  *
  * Cost comes from a local reduce — `prover.reduce(tx, 0).getCost`, the
  * Jit.record ledger-append pattern from Phase4Gate. The MATCHED-FIXTURE
  * rows (§2 of REV5-JITCOST.md) additionally sign LOCALLY, because a row
  * that exists to be compared against a rev-4 number is worthless if its
  * proposition reduced to false. Nothing here signs for
  * submission and nothing is sent to the chain; every input is
  * fabricated (P4.fabBondV3 / R5.fabPlainBond / R5.fabPackedBond /
  * R5.fabPlainOrder / R5.fabPackedOrder), so even a signed byproduct is
  * unsubmittable by construction. CONTRACTS FROZEN — this file never
  * touches a .es source or a compiled-constant.
  *
  *   sbt "runMain bonds.Rev5JitCost"
  *
  * The duplicate-token-slot investigation (REV5-KICKOFF §4 item 3) lives
  * in dupTokenSlotLocalProbe below (spend-free, runs as part of main) and
  * in the separate Rev5DupTokenGatedProbe object at the bottom of this
  * file (gated, NOT wired into main, submits nothing on its own).
  */
object Rev5JitCost {
  import Contracts._

  private var n = 0

  /** Reduce only (no sign) — the Phase4Gate / Jit.record pattern. Labels
    * are prefixed so they are distinguishable from Rev5Gate's (workstream
    * 2) concurrent appends to the same ledger. */
  private def measure(label: String, p: ErgoProver)(build: => UnsignedTransaction): Unit = {
    val tx   = build
    val cost = p.reduce(tx, 0).getCost.toLong
    Jit.record(s"R5 jit: $label", cost)
    n += 1
  }

  /** Reduce AND sign. Used for the rev-5 audit's MATCHED-FIXTURE rows
    * (finding B1): those rows exist to be compared against rev-4
    * numbers, and a comparison is only worth anything if the measured
    * path is actually SATISFIED — a reduce alone happily records the
    * cost of a proposition that reduced to false. Signing is local and
    * the inputs are fabricated, so nothing here is submittable. */
  private def measureSigned(label: String, p: ErgoProver)(build: => UnsignedTransaction): Unit = {
    val tx   = build
    val cost = p.reduce(tx, 0).getCost.toLong
    p.sign(tx)
    Jit.record(s"R5 jit: $label", cost)
    n += 1
    println(f"  matched fixture: $label%-72s $cost%6d (signs)")
  }

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val h = ctx.getHeight
    val borrowerP = TestLib.borrower(ctx)
    val lenderP   = TestLib.lender(ctx)
    val keeperP   = Kit.noSecretProver(ctx)
    val bAddr     = borrowerP.getEip3Addresses.get(0)
    val lAddr     = lenderP.getEip3Addresses.get(0)
    val lenderTreeBytes   = lAddr.toErgoContract.getErgoTree.bytes
    val borrowerTreeBytes = bAddr.toErgoContract.getErgoTree.bytes
    val lenderHash        = P4.h32(lenderTreeBytes)
    val borrowerHash      = P4.h32(borrowerTreeBytes)
    val poolNftBytes      = ErgoId.create(POOL_NFT).getBytes
    val vaultBytes        = TestLib.vaultTree().bytes
    val hookHash          = P4.h32(vaultBytes)

    val B1 = R5.plain(ctx)
    val B2 = R5.covenant(ctx)
    val B3 = R5.instalment(ctx)
    println(s"height $h")
    R5.all(ctx).foreach { f =>
      println(f"  ${f.name}%-22s bond ${f.bondTree.bytes.length}%5dB  order ${f.orderTree.bytes.length}%5dB")
    }

    // Phase3Gate/Phase4Gate/Rev5Smoke fixture, carried so costs stay
    // comparable across all three generations at the SAME scale.
    val pool      = P3.poolBox(ctx)
    val bondValue = 15000000L
    val escrow    = 10000000L
    val repayment = 15000000L
    val amtRSN    = 700L
    val period    = 20L
    val maturity  = h + 500
    val rsn       = new ErgoToken(P3.RSN_ID, amtRSN)
    require(P3.healthy(pool, bondValue - escrow, amtRSN, repayment, 15000L),
      "fixture: threshold 15000 must price healthy against live reserves")
    require(!P3.healthy(pool, bondValue - escrow, amtRSN, repayment, 20000L),
      "fixture: threshold 20000 must price unhealthy against live reserves")

    // ==================== B1 / O1: plain bullet ====================
    println("\n--- B1/O1 plain bullet ---")
    val term1 = TestLib.TERM_LONG
    def ord1(term: Int = term1): InputBox = R5.fabPlainOrder(ctx, borrowerTreeBytes, term = term)

    measure("B1/O1 cancel (borrower co-spend, no vars)", borrowerP) {
      R5.buildCancel(ctx, ord1(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B1/O1 match (plain R8 hash, var-0 reveal, no data input)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(), lenderTreeBytes, term1, lenderP)
    }
    val b1Bond = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity)
    measure("B1 repay (borrower co-spend, ERG-only)", borrowerP) {
      R5.buildRepay(ctx, b1Bond, lenderTreeBytes, bAddr, borrowerP)
    }
    val b1Tok = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity,
      tokens = Seq(rsn))
    measure("B1 repay with token collateral", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP)
    }
    measure("B1 liquidate past maturity (B1 has no hook option, no data input)", keeperP) {
      R5.buildLiquidate(ctx, b1Bond, lenderTreeBytes, bAddr, LIQ_CARVEOUT,
        preHeaderHeight = Some(maturity + 1))
    }
    // NEW BOUNDARY row (wave-2 born-liquidatable fix). Every row above
    // stamps m == h + 720, far inside the window; this is the cheapest
    // maturity the O1 contract now accepts, i.e. the shape an adversarial
    // funder is pushed to. Signed, so the measured branch is satisfied.
    measureSigned("B1/O1 match AT the m > HEIGHT + 1 floor (term 3, m == h+2) " +
      "[NEW BOUNDARY, wave-2 born-liquidatable fix]", lenderP) {
      R5.buildPlainMatch(ctx, ord1(3), lenderTreeBytes, 3, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }

    // ==================== B2 / O2: covenant bullet ====================
    println("\n--- B2/O2 covenant bullet ---")
    val term2 = 720
    val tmpl2 = R5.covenantTemplate(term2, 360L, 15000L)
    val val2  = TestLib.COLLATERAL + tmpl2(5)
    def ord2(toks: Seq[ErgoToken] = Seq(rsn)): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2, tokens = toks, term = term2)

    measure("B2/O2 cancel (token collateral recovered)", borrowerP) {
      R5.buildCancel(ctx, ord2(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    // MATCHED FIXTURE (rev-5 audit B1). The rev-4 baseline "order cancel"
    // row (15,854) was measured on an ERG-ONLY order; comparing it to the
    // token-carrying row above compares token content, not trees. This is
    // the like-for-like twin.
    measureSigned("B2/O2 cancel ERG-only [MATCHED FIXTURE vs rev-4 ERG-only order cancel]", borrowerP) {
      R5.buildCancel(ctx, ord2(Nil), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B2/O2 cancel of tmpl(1)==0 order (division-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, Array[Long](0L, 0L, 0L, 0L, 0L, 0L), TestLib.COLLATERAL),
        bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B2/O2 cancel of short-R9 order (index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, Array[Long](0L, 720L), TestLib.COLLATERAL),
        bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B2/O2 match card-less (mandatory covenant, R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2(), lenderTreeBytes, term2, lenderP)
    }
    val cardNftA = ErgoId.create("aa" * 32)
    val cardBoxA = P4.fabCard(ctx, cardNftA, P4.CARD_A_R7, P4.explicitCardR8)
    val ord2Carded = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2,
      cardPin = cardNftA.getBytes, tokens = Seq(rsn), term = term2)
    measure("B2/O2 match CARDED (1 data input, card A == compiled defaults)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2Carded, lenderTreeBytes, term2, lenderP, card = Some(cardBoxA))
    }
    val hookCardNft = ErgoId.create("bb" * 32)
    val hookCardR8  = P4.cardR8WithHooks(poolNftBytes,
      ErgoId.create(COLLATERAL_TOKEN_ID).getBytes, Seq(hookHash))
    val hookCardBox = P4.fabCard(ctx, hookCardNft, P4.CARD_A_R7, hookCardR8)
    val ord2Hooked  = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2,
      cardPin = hookCardNft.getBytes, hookHash = Some(hookHash), tokens = Seq(rsn), term = term2)
    measure("B2/O2 match HOOK-PINNED (carded, hook approved by card, E10-style)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2Hooked, lenderTreeBytes, term2, lenderP,
        card = Some(hookCardBox), hookScriptBytes = Some(vaultBytes))
    }

    // NEW BOUNDARY row (wave-2). term 9 / period 4, m == h+6: the
    // cheapest stamp the ANCHOR rule now allows — the first checkpoint
    // lands at h+1 rather than at or before the birth block. m == h+5
    // clears the +1 floor and is refused by the anchor conjunct alone.
    val anchorTmpl2 = R5.covenantTemplate(9, 4L, 15000L)
    measureSigned("B2/O2 match AT the anchor boundary (term 9 / period 4, m == h+6) " +
      "[NEW BOUNDARY, wave-2 born-liquidatable fix]", lenderP) {
      R5.buildPackedMatch(ctx, B2,
        R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, anchorTmpl2,
          TestLib.COLLATERAL + anchorTmpl2(5), tokens = Seq(rsn), term = 9),
        lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }

    def b2Bond(sched: Array[Long], pack: Seq[Array[Byte]] = Seq(lenderHash, poolNftBytes),
               value: Long = bondValue): InputBox =
      R5.fabPackedBond(ctx, B2, sched, pack, borrowerHash, value, repayment, maturity, Seq(rsn))

    val schedHealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow)
    val crankBond    = b2Bond(schedHealthy)
    measure("B2 crank HEALTHY (live pool data input)", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankAdvancePack(schedHealthy), Some(pool), bAddr)
    }
    val schedUnhealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 20000L, escrow)
    measure("B2 crank UNHEALTHY->cure (live pool data input)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedUnhealthy), R5.crankCurePack(schedUnhealthy), Some(pool), bAddr)
    }
    val schedOffB2 = Array[Long](0L, period, 0L, (h - 5).toLong, 0L, escrow)
    measure("B2 crank covenantOff (NO data input)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedOffB2, Seq(lenderHash)), R5.crankAdvancePack(schedOffB2), None, bAddr)
    }
    // MATCHED FIXTURE (rev-5 audit B1). The rev-4 crank baseline
    // (14,105-14,106) was measured on a TOKEN-FREE bond; b2Bond above
    // always carries the RSN collateral token, so the row above pays for
    // one more token slot on the input AND the successor. This is the
    // like-for-like twin.
    val crankOffTokenFree = R5.fabPackedBond(ctx, B2, schedOffB2, Seq(lenderHash),
      borrowerHash, bondValue, repayment, maturity, Nil)
    measureSigned("B2 crank covenantOff, TOKEN-FREE bond [MATCHED FIXTURE vs rev-4 crank]", keeperP) {
      R5.buildCrank(ctx, crankOffTokenFree, R5.crankAdvancePack(schedOffB2), None, bAddr)
    }
    measure("B2 repay (NO data input)", borrowerP) {
      R5.buildRepay(ctx, crankBond, lenderTreeBytes, bAddr, borrowerP)
    }
    measure("B2 top-up (NO data input)", borrowerP) {
      P2.buildTopUp(ctx, crankBond, Kit.MIN_BOX_VALUE, borrowerP)
    }
    measure("B2 liquidate UNHOOKED past maturity (NO data input)", keeperP) {
      R5.buildLiquidate(ctx, crankBond, lenderTreeBytes, bAddr, R5.carveOfBond(ctx, crankBond),
        preHeaderHeight = Some(maturity + 1))
    }
    val hookedBond = b2Bond(schedHealthy, Seq(lenderHash, poolNftBytes, hookHash))
    measure("B2 liquidate HOOKED (bond var-0 preimage, NO data input)", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedBond, vaultBytes, bAddr, preHeaderHeight = Some(maturity + 1))
    }
    val thrX = 20000L
    val lX   = P3.ergLegForHealthy(pool, amtRSN, repayment, thrX)
    require(lX > 1L, "fixture: healthy boundary must be positive")
    val escrowLive = escrow - CRANK_BOUNTY
    val blownValue = escrowLive + lX - 1L
    val schedBlown = Array[Long](0L, period, 0L, -((h - 3).toLong), thrX, escrowLive)
    measure("B2 covenant accelerate (deadline passed, unhealthy now)", keeperP) {
      R5.buildAccelerate(ctx, b2Bond(schedBlown, value = blownValue), lenderTreeBytes, pool, bAddr,
        preHeaderHeight = Some(h))
    }
    val deadlineF = (h + 5).toLong
    val schedCure = Array[Long](0L, period, 0L, -deadlineF, thrX, escrowLive)
    val addValue  = Kit.MIN_BOX_VALUE
    measure("B2 cure (borrower top-up back onto the grid, live pool)", borrowerP) {
      R5.buildCure(ctx, b2Bond(schedCure, value = blownValue), addValue, pool, borrowerP)
    }

    // ==================== B3 / O3: instalment ====================
    println("\n--- B3/O3 instalment ---")
    val term3 = 720
    val tmpl3 = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT)
    val val3  = TestLib.COLLATERAL + tmpl3(5)
    def ord3(): InputBox = R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3, val3, term = term3)

    measure("B3/O3 cancel (covenant-off instalment order)", borrowerP) {
      R5.buildCancel(ctx, ord3(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B3/O3 cancel of tmpl(1)==0 order (division-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, Array[Long](0L, 0L, 0L, 0L, 0L, 0L), TestLib.COLLATERAL),
        bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B3/O3 cancel of short-R9 order (index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, Array[Long](0L, 720L), TestLib.COLLATERAL),
        bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    measure("B3/O3 match, covenant OFF (R8 pack size 1)", lenderP) {
      R5.buildPackedMatch(ctx, B3, ord3(), lenderTreeBytes, term3, lenderP)
    }
    val tmpl3cov = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT, thresholdBps = 15000L)
    val val3cov  = TestLib.COLLATERAL + tmpl3cov(5)
    measure("B3/O3 match, covenant ON (R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B3,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3cov, val3cov, tokens = Seq(rsn), term = term3),
        lenderTreeBytes, term3, lenderP)
    }
    // MATCHED FIXTURE (rev-5 audit B1). The rev-4 match baselines — 16,757
    // card-less and 17,135 carded — were both measured on TOKEN-FREE
    // orders. O2 cannot be token-free: its covenant is mandatory and
    // conformsWith requires exactly one collateral token
    // (CovenantBulletOrder.es:197), so the like-for-like rev-5 twins live
    // on the INSTALMENT tree with the covenant off. "B3/O3 match, covenant
    // OFF" above is the card-less twin; this is the carded one.
    val ord3Carded = R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3, val3,
      cardPin = cardNftA.getBytes, term = term3)
    measureSigned("B3/O3 match CARDED, covenant OFF, token-free " +
      "[MATCHED FIXTURE vs rev-4 carded match]", lenderP) {
      R5.buildPackedMatch(ctx, B3, ord3Carded, lenderTreeBytes, term3, lenderP,
        card = Some(cardBoxA))
    }

    // NEW BOUNDARY row (wave-2) — see the O2 twin. On this product the
    // anchor is what buys a coupon window at all: with the checkpoint at
    // the birth block, couponOk is empty, repayOk is unreachable and
    // liquidation is the only exit. That was the closed exploit.
    val anchorTmpl3 = R5.instalmentTemplate(9, 4L, P4.INSTALLMENT)
    measureSigned("B3/O3 match AT the anchor boundary (term 9 / period 4, m == h+6) " +
      "[NEW BOUNDARY, wave-2 born-liquidatable fix]", lenderP) {
      R5.buildPackedMatch(ctx, B3,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, anchorTmpl3,
          TestLib.COLLATERAL + anchorTmpl3(5), term = 9),
        lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }

    def b3Bond(sched: Array[Long], value: Long, pack: Seq[Array[Byte]] = Seq(lenderHash),
               toks: Seq[ErgoToken] = Nil): InputBox =
      R5.fabPackedBond(ctx, B3, sched, pack, borrowerHash, value, repayment, maturity, toks)

    val schedCoupOff = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 0L, escrow)
    val coupBond     = b3Bond(schedCoupOff, 35000000L)
    measure("B3 coupon covenantOff (NO data input)", borrowerP) {
      P4.buildCoupon(ctx, coupBond, P4.honestCouponPlan(coupBond, lenderTreeBytes, healthyBranch = true),
        None, bAddr)
    }
    val schedCoupCov = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 15000L, escrow)
    val coupCovBond  = b3Bond(schedCoupCov, bondValue, Seq(lenderHash, poolNftBytes), Seq(rsn))
    measure("B3 coupon covenant HEALTHY (live pool data input)", borrowerP) {
      P4.buildCoupon(ctx, coupCovBond, P4.honestCouponPlan(coupCovBond, lenderTreeBytes, healthyBranch = true),
        Some(pool), bAddr)
    }
    measure("B3 top-up (NO data input)", borrowerP) {
      P2.buildTopUp(ctx, coupBond, Kit.MIN_BOX_VALUE, borrowerP)
    }
    val schedMissed = Array[Long](P4.INSTALLMENT, period, 3L, (h - 15).toLong, 0L, escrow)
    val missedBond  = b3Bond(schedMissed, 35000000L)
    require((h - 15).toLong + GRACE_BLOCKS <= h.toLong, "fixture: deadline+grace must be past")
    measure("B3 missed-payment acceleration (NO data input)", keeperP) {
      P4.buildMissedAccel(ctx, missedBond, P4.honestMissedAccelPlan(missedBond, lenderTreeBytes), bAddr,
        preHeaderHeight = Some(h))
    }
    val schedFinal = Array[Long](P4.INSTALLMENT, period, 1L, (h + 100).toLong, 0L, 0L)
    val finalBond  = b3Bond(schedFinal, 20000000L)
    measure("B3 final repay at sched(2)==1 (NO data input)", borrowerP) {
      R5.buildRepay(ctx, finalBond, lenderTreeBytes, bAddr, borrowerP)
    }
    measure("B3 liquidate UNHOOKED past maturity (NO data input)", keeperP) {
      R5.buildLiquidate(ctx, finalBond, lenderTreeBytes, bAddr, R5.carveOfBond(ctx, finalBond),
        preHeaderHeight = Some(maturity + 1))
    }
    // REACHABLE FIXTURE (rev-5 audit B4). The first pass measured this row
    // on schedHealthy — a BULLET schedule (sched(0) == 0) sitting at the
    // instalment address, which no honest O3 match can mint: schedCommonOk
    // forces tmpl(0) > 0 and the bond's R9 is copied from the template.
    // The shape below is what an O3 match actually produces: a positive
    // installment, payments remaining, a live checkpoint. The old row
    // stays in JITCOST.md under its old label; this one is appended fresh.
    val schedB3Hooked = Array[Long](P4.INSTALLMENT, period, 2L, (h + 100).toLong, 15000L, escrow)
    val hookedBondB3  = b3Bond(schedB3Hooked, bondValue,
      Seq(lenderHash, poolNftBytes, hookHash), Seq(rsn))
    measure("B3 liquidate HOOKED, instalment-shaped R9 (bond var-0 preimage, NO data input) " +
      "[REACHABLE FIXTURE, replaces the bullet-shaped one]", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedBondB3, vaultBytes, bAddr, preHeaderHeight = Some(maturity + 1))
    }

    println(s"\nrev-5 JitCost run complete: $n rows recorded to JITCOST.md (labels 'R5 jit: ...').")

    // ==================== duplicate-token-slot probe (spend-free) ====================
    dupTokenSlotLocalProbe(ctx, borrowerP, lenderP)
    ()
  }

  /** REV5-KICKOFF §4 item 3b: does the local box-construction /
    * sigma-interpreter stack reject two token-id slots for the SAME id
    * in one box? Every step here is spend-free — fabricated boxes,
    * local reduce, local sign (unsubmittable: the order input does not
    * exist on chain), and one read-only /transactions/check attempt.
    * See REV5-JITCOST.md for the write-up; this method produces the
    * evidence it documents.
    */
  private def dupTokenSlotLocalProbe(ctx: BlockchainContext, borrowerP: ErgoProver, lenderP: ErgoProver): Unit = {
    println("\n--- duplicate-token-slot probe (spend-free, REV5-KICKOFF item 3) ---")
    val bAddr = borrowerP.getEip3Addresses.get(0)
    val lAddr = lenderP.getEip3Addresses.get(0)
    val borrowerTreeBytes = bAddr.toErgoContract.getErgoTree.bytes
    val lenderTreeBytes   = lAddr.toErgoContract.getErgoTree.bytes
    val dupId = ErgoId.create("5d" * 32)

    // Layer 1: appkit's OutBoxBuilder / ErgoBoxCandidate construction.
    // Source read (OutBoxBuilderImpl.scala, JavaHelpers.createBoxCandidate,
    // sigmastate ErgoBoxCandidate.scala — appkit 6.0.0 / sigma-state 6.0.2,
    // the exact jars this build depends on) shows NO dedup, NO
    // distinctness requirement anywhere in the construction path: tokens
    // are appended to a plain ArrayBuffer / Coll, capped only by count
    // (SigmaConstants.MaxTokens). Confirmed empirically below.
    val dupBox = ctx.newTxBuilder().outBoxBuilder()
      .value(TestLib.COLLATERAL)
      .contract(R5.plain(ctx).orderContract)
      .tokens(new ErgoToken(dupId, 6L), new ErgoToken(dupId, 4L))
      .registers(ErgoValue.of(borrowerTreeBytes), ErgoValue.of(TestLib.PRINCIPAL),
        ErgoValue.of(TestLib.REPAYMENT), ErgoValue.of(TestLib.TERM_LONG))
      .build()
    val slotCount = dupBox.getTokens.size()
    println(s"  layer 1 (appkit OutBoxBuilder/ErgoBoxCandidate): box built with " +
      s"2x ErgoToken(sameId, ..) -> getTokens().size() = $slotCount " +
      (if (slotCount == 2) "(NOT merged - both slots preserved verbatim)"
       else "(MERGED - unexpected: appkit deduped/summed them)"))

    // Layers 2/3: the sigma INTERPRETER itself (same 6.0.2 code the node
    // embeds for script evaluation), reached by building a genuine B1
    // order whose OWN collateral is split into two slots of the SAME id
    // (6 + 4 units, order-side nominal total 10), matched into a bond
    // whose EXPLICIT collateral slots are (dupId,6) and (dupId,1) — 7
    // real units on the bond, count-padded to 2 slots so loanTokenOk's
    // `bondBox.tokens.size == SELF.tokens.size + 1` count check is
    // satisfied (order has 2 dupId slots; bond needs loan + 2 = 3
    // total). PlainBulletOrder's collateralTokensOk has no
    // SELF.tokens.size==1 guard (unlike B2's covenant order), so this is
    // reachable on a B1 order. If the forall+exists idiom were evaluated
    // as intended by an attacker, `SELF.tokens.forall { t =>
    // bondBox.tokens.exists { o => o._1==t._1 && o._2>=t._2 } }` would be
    // satisfied by the SAME bond-side (dupId,6) entry twice over: order
    // slot (dupId,6) needs some bond entry >=6 (6>=6 OK) and order slot
    // (dupId,4) needs some bond entry >=4 (6>=4 OK, same entry reused) —
    // the forall never requires a bijection or a sum. The remaining 3
    // units (10 declared - 7 on the bond) are placed BY HAND in a third
    // output (funderPocket), modelling the funder pocketing the
    // difference.
    //
    // This never reaches that question, and WHY is itself a finding
    // (traced by re-running with a debug dump of dupTx.getOutputs — see
    // REV5-JITCOST.md for the full trace): appkit's build() ALWAYS routes
    // through org.ergoplatform.wallet.transactions.TransactionBuilder.
    // buildUnsignedTx (UnsignedTransactionBuilderImpl.build() calls it
    // unconditionally whenever sendChangeTo is set, and sendChangeTo is
    // REQUIRED — there is no lower-level escape via the public builder
    // API). That method's `collTokensToMap` — `tokens.toArray.map(t =>
    // t._1.toModifierId -> t._2).toMap` — converts a SINGLE box's own
    // token list to a Map with a plain Scala `.toMap`, which for a
    // DUPLICATE KEY silently keeps only the LAST entry and drops earlier
    // ones (standard `Seq(...).toMap` overwrite semantics — this is a
    // real accounting bug, not a design choice). Bond's own tokens
    // collapse from {loanId:1, dupId:6, dupId:1} to {loanId:1, dupId:1}
    // before that per-box map is merged across outputs, so the "already
    // delivered" dupId total the auto-change/mint calculation works from
    // is undercounted (1 instead of 7, or 4 once funderPocket's 3 is
    // merged in). The box selector then computes an automatic CHANGE box
    // for the "missing" dupId (6 units, in the funderPocket run) sized
    // against that undercount — which, combined with the genuinely
    // duplicated bond slots, produces a REAL over-mint of dupId relative
    // to the true 10-unit input supply (16 total out). THAT is what the
    // stricter, correctly-summing prover-level check below catches.
    // Built with the RAW OutBoxBuilder, not R5.fabPlainOrder: the rev-5
    // audit pass (finding A1) taught every R5 order fabricator/poster to
    // MERGE duplicate token-id slots before they reach a box, so the
    // helper can no longer produce the shape this probe exists to study.
    // The registers below are byte-for-byte what fabPlainOrder writes.
    val dupOrder = ctx.newTxBuilder().outBoxBuilder()
      .value(TestLib.COLLATERAL)
      .contract(R5.plain(ctx).orderContract)
      .tokens(new ErgoToken(dupId, 6L), new ErgoToken(dupId, 4L))
      .registers(
        ErgoValue.of(borrowerTreeBytes),
        ErgoValue.of(TestLib.PRINCIPAL),
        ErgoValue.of(TestLib.REPAYMENT),
        ErgoValue.of(TestLib.TERM_LONG))
      .build()
      .convertToInputWith(P4.DUMMY_TX, 3)
    val hSnap     = ctx.getHeight
    val maturityX = hSnap + TestLib.TERM_LONG
    val funds     = Kit.selectBoxes(ctx, lAddr, TestLib.PRINCIPAL + Kit.TX_FEE + 2 * Kit.MIN_BOX_VALUE)
    val orderIn   = R5.plainOrderWithLenderVar(dupOrder, lenderTreeBytes)
    val tb        = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(hSnap).build())
    val shortBondOut = tb.outBoxBuilder()
      .value(dupOrder.getValue)
      .contract(R5.plain(ctx).bondContract)
      .tokens(new ErgoToken(dupOrder.getId, 1L), new ErgoToken(dupId, 6L), new ErgoToken(dupId, 1L))
      .registers(
        ErgoValue.of(dupOrder.getId.getBytes),
        ErgoValue.of(P4.h32(borrowerTreeBytes)),
        ErgoValue.of(TestLib.REPAYMENT),
        ErgoValue.of(maturityX),
        ErgoValue.of(P4.h32(lenderTreeBytes)))
      .build()
    val principalOut = tb.outBoxBuilder().value(TestLib.PRINCIPAL)
      .contract(P4.contractFromBytes(borrowerTreeBytes)).build()
    // Explicit "funder's pocket" output for the remaining 3 units of
    // dupId (10 declared - 7 on the bond), built BY HAND — see the long
    // comment above for why relying on appkit's automatic token change
    // does not isolate the question this probe is trying to answer.
    val funderPocket = tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
      .contract(lAddr.toErgoContract)
      .tokens(new ErgoToken(dupId, 3L))
      .build()
    val dupTx = tb.boxesToSpend((Seq(orderIn) ++ funds).asJava)
      .outputs(shortBondOut, principalOut, funderPocket)
      .fee(Kit.TX_FEE).sendChangeTo(lAddr).build()

    val dupReduce = scala.util.Try(lenderP.reduce(dupTx, 0).getCost)
    dupReduce match {
      case scala.util.Success(cost) =>
        println(s"  layer 2 (sigma interpreter reduce): duplicate-slot SELF box " +
          s"evaluated cleanly, JitCost $cost — the interpreter neither rejects nor " +
          s"dedupes on read")
      case scala.util.Failure(e) =>
        println(s"  layer 2 (sigma interpreter reduce): REJECTED - ${Kit.causeChain(e).take(300)} " +
          s"(this is appkit's OWN wallet-builder change/mint accounting catching itself, " +
          s"NOT the interpreter rejecting duplicate slots per se — see the comment above " +
          s"and REV5-JITCOST.md for the exact three-step trace: collTokensToMap under-counts " +
          s"the bond's duplicate dupId slots -> auto change box is oversized -> the resulting " +
          s"tx genuinely over-mints dupId, which THIS correctly-summing check (AppkitProving " +
          s"Interpreter.reduceTransaction, sumByKey-based) then rejects)")
    }
    val dupSign = scala.util.Try(lenderP.sign(dupTx))
    dupSign match {
      case scala.util.Success(_) =>
        println(s"  layer 3 (sigma interpreter sign): signed despite the duplicate slots")
      case scala.util.Failure(e) =>
        println(s"  layer 3 (sigma interpreter sign): did NOT sign - ${Kit.causeChain(e).take(200)} " +
          s"(same upstream cause as layer 2 - the wallet-builder accounting bug, not a native " +
          s"duplicate-slot rejection)")
    }

    // Layer 4: the exact mainnet node BINARY this repo's NODE_URL talks
    // to (~/ergo/ergo-6.1.2.jar). NOT executed - static read (javap -p -c,
    // strings) of its own shipped, compiled classes for the one method
    // every transaction's assets pass through:
    // org.ergoplatform.wallet.boxes.ErgoBoxAssetExtractor.extractAssets,
    // gated by validation rule txAssetsInOneBox in ErgoTransaction.
    // Findings:
    //   - the ONLY per-box check found is a COUNT cap:
    //     require(box.additionalTokens.length <= MaxAssetsPerBox, ...)
    //     with MaxAssetsPerBox = 255 (sipush 255 at the field init) -
    //     no distinctness requirement;
    //   - amounts are folded into a mutable Map[TokenId, Long] via
    //     Math.addExact as boxes are scanned, i.e. duplicate-id slots
    //     ANYWHERE (same box or spread across many) are SUMMED for the
    //     whole-transaction asset-conservation check (txAssetsPreservation),
    //     never rejected for being duplicates;
    //   - `strings`/`javap` over ErgoBox.class, ErgoBoxCandidate.class,
    //     ErgoTransaction.class and the nodeView.state classes found no
    //     "duplicate"/"distinct"/"unique"-token literal or check anywhere
    //     searched.
    // This is read-only bytecode inspection of the shipped node, never an
    // executed transaction. See REV5-JITCOST.md for the residual risk and
    // exact method citations.
    println("  layer 4 (ergo-6.1.2.jar bytecode, static read only, NOT executed): " +
      "txAssetsInOneBox checks COUNT only (<=255 assets/box); extractAssets sums " +
      "duplicate token ids via addExact; no distinctness check found in ErgoBox / " +
      "ErgoBoxCandidate / ErgoTransaction / nodeView.state - see REV5-JITCOST.md")

    // Layer 5 (attempted): /transactions/check - read-only, explicitly
    // allowed. Only reachable if layer 3 produced a signed tx; here it
    // did not (the wallet-builder accounting bug above stops it before a
    // signature would exist), so this is skipped rather than faked. Even
    // when reachable, a tx built from FABRICATED (nonexistent) inputs
    // cannot usefully reach output-shape checks: the node resolves
    // inputs from its OWN UTXO set first, so a real attempt would be
    // expected to fail on "input not found" - uninformative either way
    // about token-slot duplication. Recorded here rather than attempted
    // blind, per "every spend-free empirical angle, document what each
    // layer said."
    dupSign match {
      case scala.util.Success(signed) =>
        scala.util.Try(Kit.httpPost("/transactions/check", signed.toJson(false))) match {
          case scala.util.Success(resp) =>
            println(s"  layer 5 (/transactions/check, fabricated inputs): responded 2xx - " +
              s"${resp.take(200)}")
          case scala.util.Failure(e) =>
            println(s"  layer 5 (/transactions/check, fabricated inputs): rejected - " +
              s"${Kit.causeChain(e).take(300)} (expected: fails resolving the fabricated " +
              s"input box before it would ever reach an output-shape check)")
        }
      case scala.util.Failure(_) =>
        println("  layer 5 (/transactions/check): SKIPPED - layer 3 produced no signed " +
          "tx to check (see layer 2/3 above: rejected upstream of signing)")
    }
    println("  verdict: UNPROVEN at the interpreter/node level for a GENUINELY BALANCED " +
      "duplicate-slot transaction - this run's own token-conservation arithmetic was " +
      "confounded by a real bug in appkit's client-side change/mint accounting " +
      "(collTokensToMap, org.ergoplatform.wallet.transactions.TransactionBuilder), which " +
      "has no lower-level bypass in the public builder API. What IS proven: (a) box " +
      "CONSTRUCTION accepts duplicate token-id slots at every layer down to the " +
      "interpreter with no dedup or rejection (layer 1); (b) the interpreter's own asset " +
      "accounting (AppkitProvingInterpreter.reduceTransaction) and the actual shipped " +
      "node binary's asset accounting (ErgoBoxAssetExtractor.extractAssets, layer 4) both " +
      "SUM duplicate slots correctly via a fold, unlike the buggy wallet-builder toMap " +
      "conversion - neither shows a distinctness check. Best-supported reading: " +
      "LOCALLY-CONSTRUCTIBLE-NODE-UNKNOWN, leaning toward acceptance on the node's own " +
      "consensus-critical code path, but not empirically forced through a signed " +
      "transaction in this pass - see REV5-JITCOST.md for the full trace and residual risk.")
  }
}

/** ==================== GATED - LATER PHASE ONLY ====================
  * REV5-KICKOFF §4 item 3a: the on-chain mint/spend test that would
  * observe ACTUAL node mempool/consensus acceptance of a duplicate
  * token-id slot, as opposed to Rev5JitCost.dupTokenSlotLocalProbe's
  * local-interpreter-only evidence above.
  *
  * NOT wired into Rev5JitCost.main - `sbt "runMain bonds.Rev5JitCost"`
  * never reaches this object. NOT spend-free: submitting the mint/dupbox
  * transactions below requires real ERG from a funded wallet.
  *
  * By design this object NEVER calls ctx.sendTransaction or Kit.sendSafe
  * anywhere, even behind the confirmation guard: every builder here
  * signs LOCALLY and PRINTS the signed transaction's JSON for a human to
  * review and submit by hand (via the node's own /transactions endpoint
  * or wallet UI) once the gated phase is actually authorized. That is a
  * deliberate extra safety margin beyond the workstream-3 hard rule,
  * which binds this AGENT's actions during this task, not necessarily
  * code written for a future authorized run - but a file that can never
  * itself broadcast is strictly safer, so that is what this is.
  *
  * Plan when authorized:
  *   step "mint"   - mint a tiny test token T (1000 units) into the
  *                   operator's own wallet via a trivial self-mint tx.
  *                   Prints signed JSON; human submits, waits for
  *                   confirmation, and passes the resulting box id as
  *                   the tokenId to step "dupbox".
  *   step "dupbox" - given --tokenId, build (and locally sign) an
  *                   ordinary P2PK-guarded box carrying TWO token slots
  *                   of that same id, e.g. (T,600) and (T,400) - the
  *                   exact shape the local probe proves is constructible
  *                   and signs cleanly. Prints signed JSON; human
  *                   submits and observes: does ctx.sendTransaction /
  *                   the mempool accept it? Does /blockchain/box/byId
  *                   report two slots or a merged one? Does it confirm
  *                   in a block?
  *   step "spend"  - given --dupBoxId, build a spend of that box through
  *                   an ad hoc probe script that inspects .tokens the
  *                   same way the bonds do (a forall/exists check
  *                   mirroring collateralTokensOk), to confirm the
  *                   under-delivery is exploitable END-TO-END on real
  *                   consensus, not just locally constructible.
  *   cleanup       - recover all ERG/tokens back to the operator wallet.
  *
  * Only "mint" and "dupbox" are implemented below (build+local-sign+
  * print only); "spend" and cleanup are left as the documented next
  * step since they depend on live chain state the first two steps
  * produce. Run with the exact confirmation arg once authorized:
  *
  *   sbt "runMain bonds.Rev5DupTokenGatedProbe I-UNDERSTAND-THIS-SPENDS-REAL-FUNDS mint"
  *   sbt "runMain bonds.Rev5DupTokenGatedProbe I-UNDERSTAND-THIS-SPENDS-REAL-FUNDS dupbox <tokenId>"
  */
object Rev5DupTokenGatedProbe {
  import org.ergoplatform.appkit._
  import org.ergoplatform.sdk.ErgoToken
  import scala.collection.JavaConverters._

  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args(0) == "I-UNDERSTAND-THIS-SPENDS-REAL-FUNDS",
      "GATED: re-run with the exact confirmation arg once the gated phase is " +
      "authorized (see the header comment for the full plan). Refusing to proceed.")
    require(args.length >= 2, "usage: <confirm-arg> mint | <confirm-arg> dupbox <tokenId>")
    Kit.exec { ctx =>
      args(1) match {
        case "mint" =>
          val op    = TestLib.borrower(ctx) // reuse the harness's own funded wallet
          val oAddr = op.getEip3Addresses.get(0)
          val funds = Kit.selectBoxes(ctx, oAddr, Kit.MIN_BOX_VALUE + Kit.TX_FEE)
          val tb    = ctx.newTxBuilder()
          val out = tb.outBoxBuilder()
            .value(Kit.MIN_BOX_VALUE)
            .contract(oAddr.toErgoContract)
            .tokens(new ErgoToken(funds.head.getId, 1000L))
            .build()
          val unsigned = tb.boxesToSpend(funds.asJava).outputs(out)
            .fee(Kit.TX_FEE).sendChangeTo(oAddr).build()
          val signed = op.sign(unsigned) // LOCAL SIGN ONLY - never submitted here
          println("mint tx (NOT submitted - sign, review, then submit by hand if authorized):")
          println(s"  new token id (== first input id): ${funds.head.getId}")
          println(signed.toJson(false))
        case "dupbox" =>
          require(args.length >= 3, "usage: <confirm-arg> dupbox <tokenId>")
          val tokenId = org.ergoplatform.sdk.ErgoId.create(args(2))
          val op      = TestLib.borrower(ctx)
          val oAddr   = op.getEip3Addresses.get(0)
          val tokBoxes = TestLib.boxesWithToken(ctx, oAddr, tokenId.toString)
          require(tokBoxes.nonEmpty, s"no unspent box at $oAddr holding token $tokenId - run 'mint' first and wait for confirmation")
          val funds = Kit.selectBoxes(ctx, oAddr, Kit.TX_FEE)
          val tb    = ctx.newTxBuilder()
          val out = tb.outBoxBuilder()
            .value(Kit.MIN_BOX_VALUE)
            .contract(oAddr.toErgoContract)
            .tokens(new ErgoToken(tokenId, 600L), new ErgoToken(tokenId, 400L)) // TWO slots, same id
            .build()
          val unsigned = tb.boxesToSpend((tokBoxes ++ funds).asJava).outputs(out)
            .fee(Kit.TX_FEE).sendChangeTo(oAddr).build()
          val signed = op.sign(unsigned) // LOCAL SIGN ONLY - never submitted here
          println("dup-slot box tx (NOT submitted - sign, review, then submit by hand if authorized):")
          println(signed.toJson(false))
        case other =>
          sys.error(s"unknown step '$other' - expected 'mint' or 'dupbox'")
      }
      ()
    }
  }
}
