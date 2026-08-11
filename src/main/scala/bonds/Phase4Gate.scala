package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Rev-3 compile-gate probes (REV3-KICKOFF §4.3/§5): reduce every new or
  * changed path shape BEFORE EKB, BEFORE dust, against fabricated rev-3
  * boxes and the REAL pinned pool. Records JitCost per path for
  * comparison against the rev-2 table in PHASE3-EVIDENCE.md (the
  * single-sigmaProp top-level restructure changes proof costs — measure,
  * don't extrapolate).
  *
  * PERMANENT probe set (fails the gate on any recurrence of the
  * LOW-P3-B1 eager-CSE class):
  *   1-3. repay / covenantOff crank / top-up with NO data input (rev 2)
  *   4.   covenantOff COUPON with NO data input
  *   5.   nonzero-installment accelerate EXACTNESS (verdict flips at the
  *        true ergLeg boundary, not one installment below — decision 5)
  *   6.   nonzero-installment repay (no data input)
  *   7.   order CANCEL with NO data input (the order now reads
  *        dataInputs — the rev-1 crash class applies to it)
  *   8.   card-less MATCH with NO data input
  *   plus borrowerAuth eager-evaluation safety (INPUTS.exists hoisted
  *   above guards must not crash any path) and the attestation
  *   generic-branch probe (fabricated nonzero-type bond, local only —
  *   the branch is unreachable by any conforming rev-3 bond).
  */
object Phase4Gate {
  import Contracts._

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val h = ctx.getHeight
    val (_, bondContract)  = Contracts.bond(ctx)
    val borrowerP = TestLib.borrower(ctx)
    val lenderP   = TestLib.lender(ctx)
    val bAddr     = borrowerP.getEip3Addresses.get(0)
    val lAddr     = lenderP.getEip3Addresses.get(0)
    val keeperP   = Kit.noSecretProver(ctx)
    val lenderTreeBytes   = lAddr.toErgoContract.getErgoTree.bytes
    val borrowerTreeBytes = bAddr.toErgoContract.getErgoTree.bytes
    // Rev 4: bond R5 and R8(0) hold blake2b256 of these trees, never the
    // trees themselves; the full scripts stay here for every destination.
    val lenderHash        = P4.h32(lenderTreeBytes)
    val borrowerHash      = P4.h32(borrowerTreeBytes)
    val poolNftBytes      = ErgoId.create(POOL_NFT).getBytes

    // The real pinned pool box.
    val pool = P3.poolBox(ctx)
    val (rX, rY, feeNum) = P3.reserves(pool)
    println(s"pool ${pool.getId}  rX=$rX  rY=$rY  feeNum=$feeNum  height=$h")

    // Phase3Gate fixture, carried for table comparability.
    val bondValue = 15000000L
    val escrow    = 10000000L
    val ergLeg    = bondValue - escrow
    val repayment = 15000000L
    val amtRSN    = 700L
    val period    = 20L
    val maturity  = h + 500
    val rsn       = new ErgoToken(P3.RSN_ID, amtRSN)

    require(P3.healthy(pool, ergLeg, amtRSN, repayment, 15000L),
      "probe setup: threshold 15000 must price healthy against live reserves")
    require(!P3.healthy(pool, ergLeg, amtRSN, repayment, 20000L),
      "probe setup: threshold 20000 must price unhealthy against live reserves")

    def covBond(sched: Array[Long]): InputBox =
      P4.fabBondV3(ctx, sched, Seq(lenderHash, poolNftBytes),
        borrowerHash, bondValue, repayment, maturity, Seq(rsn))

    def crankTx(bond: InputBox, r9succ: Array[Long], withPool: Boolean): UnsignedTransaction = {
      val tb = ctx.newTxBuilder()
      val rs = bond.getRegisters
      val succ = tb.outBoxBuilder()
        .value(bond.getValue - P4.bountyOf(TestLib.schedOf(bond)))
        .contract(bondContract)
        .tokens(bond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
        .build()
      val kb = tb.outBoxBuilder()
        .value(P4.bountyOf(TestLib.schedOf(bond)) - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      var builder = tb.boxesToSpend(java.util.Arrays.asList(bond))
      if (withPool) builder = builder.withDataInputs(java.util.Arrays.asList(pool))
      builder.outputs(succ, kb).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }

    // ---- 1. Changed-path cost probes: crank (sched(0)==0 gate + moved
    // distinctifier), rev-3 tree, live pool ----
    val schedHealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow)
    val advPack      = Array[Long](0L, period, 0L, (h - 5).toLong + period, 15000L, escrow - CRANK_BOUNTY)
    Jit.record("P4 gate: crank covenant HEALTHY (local reduce, live pool)",
      keeperP.reduce(crankTx(covBond(schedHealthy), advPack, withPool = true), 0).getCost.toLong)

    val schedUnhealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 20000L, escrow)
    val curePack       = Array[Long](0L, period, 0L, -((h - 5).toLong + GRACE_BLOCKS), 20000L, escrow - CRANK_BOUNTY)
    Jit.record("P4 gate: crank covenant UNHEALTHY->cure (local reduce, live pool)",
      keeperP.reduce(crankTx(covBond(schedUnhealthy), curePack, withPool = true), 0).getCost.toLong)

    // ---- 2. NEW path cost probes: coupon, covenant both branches ----
    val schedCoupH = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 15000L, escrow)
    val coupBondH  = covBond(schedCoupH)
    val coupTxH    = P4.buildCoupon(ctx, coupBondH, P4.honestCouponPlan(coupBondH, lenderTreeBytes, healthyBranch = true),
      Some(pool), bAddr)
    Jit.record("P4 gate: coupon covenant HEALTHY (local reduce, live pool)",
      borrowerP.reduce(coupTxH, 0).getCost.toLong)

    val schedCoupU = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 20000L, escrow)
    val coupBondU  = covBond(schedCoupU)
    val coupTxU    = P4.buildCoupon(ctx, coupBondU, P4.honestCouponPlan(coupBondU, lenderTreeBytes, healthyBranch = false),
      Some(pool), bAddr)
    Jit.record("P4 gate: coupon covenant UNHEALTHY->cure (local reduce, live pool)",
      borrowerP.reduce(coupTxU, 0).getCost.toLong)

    // ---- 3. Cure and covenant-accelerate (changed verdict args) ----
    val deadlineF  = (h + 5).toLong
    val schedCure  = Array[Long](0L, period, 0L, -deadlineF, 20000L, escrow - CRANK_BOUNTY)
    val addValue   = 5000000L
    require(P3.healthy(pool, ergLeg + addValue, amtRSN, repayment, 20000L),
      "probe setup: +0.005 ERG must cure threshold 20000")
    val cureBond   = covBond(schedCure)
    val restorePack = Array[Long](0L, period, 0L, (deadlineF - GRACE_BLOCKS) + period, 20000L, escrow - CRANK_BOUNTY)
    val cureTx = {
      val tb    = ctx.newTxBuilder()
      val rs    = cureBond.getRegisters
      val funds = Kit.selectBoxes(ctx, bAddr, addValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val succ = tb.outBoxBuilder()
        .value(cureBond.getValue + addValue)
        .contract(bondContract)
        .tokens(cureBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(restorePack))
        .build()
      tb.boxesToSpend((Seq(cureBond) ++ funds).asJava)
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(succ).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: cure top-up (local reduce, live pool)",
      borrowerP.reduce(cureTx, 0).getCost.toLong)

    def accelTx(bond: InputBox, preH: Int): UnsignedTransaction = {
      val s  = TestLib.schedOf(bond)
      val tb = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(preH).build())
      val exit = tb.outBoxBuilder()
        .value(bond.getValue - P4.carveOf(s))
        .contract(P4.contractFromBytes(lenderTreeBytes)) // rev 4: R8(0) is a hash
        .tokens(bond.getTokens.asScala.toSeq: _*)
        .registers(ErgoValue.of(bond.getId.getBytes))
        .build()
      val kb = tb.outBoxBuilder()
        .value(P4.carveOf(s) - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(bond))
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(exit, kb).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    val schedBlown = Array[Long](0L, period, 0L, -((h - 3).toLong), 20000L, escrow - CRANK_BOUNTY)
    Jit.record("P4 gate: acceleration (local reduce, live pool)",
      keeperP.reduce(accelTx(covBond(schedBlown), h), 0).getCost.toLong)

    // ---- 4. PERMANENT PROBE: nonzero-installment accelerate EXACTNESS ----
    // Decision 5: the verdict argument is exact (SELF.value - escrow).
    // The rev-2 trap (- sched(0)) would false-accelerate a healthy bond
    // whose ergLeg sits inside [L, L + installment): pin the flip at the
    // TRUE boundary L, and pin L + installment - 1 as NOT accelerable.
    val thrX = 20000L
    val lX   = P3.ergLegForHealthy(pool, amtRSN, repayment, thrX)
    require(lX > 0L, "probe setup: healthy boundary must be positive")
    def blownInstBond(el: Long): InputBox =
      P4.fabBondV3(ctx,
        Array[Long](P4.INSTALLMENT, period, 3L, -((h - 3).toLong), thrX, escrow),
        Seq(lenderHash, poolNftBytes), borrowerHash,
        escrow + el, repayment, maturity, Seq(rsn))
    require(P3.healthy(pool, lX, amtRSN, repayment, thrX) &&
            !P3.healthy(pool, lX - 1L, amtRSN, repayment, thrX),
      "probe setup: L must be the exact healthy floor")
    Kit.expectRejected("P4 gate: accelerate at ergLeg == L (healthy, must NOT fire)") {
      TestLib.keeper(ctx).sign(accelTx(blownInstBond(lX), h))
    }
    Kit.expectReduces("P4 gate: accelerate at ergLeg == L-1 (unhealthy, fires)-twin") {
      keeperP.reduce(accelTx(blownInstBond(lX - 1L), h), 0).getCost
    }
    Kit.expectRejected("P4 gate: accelerate at ergLeg == L+installment-1 (trap detector, must NOT fire)") {
      TestLib.keeper(ctx).sign(accelTx(blownInstBond(lX + P4.INSTALLMENT - 1L), h))
    }

    // ---- 5. PERMANENT PROBES: the no-data-input shapes ----
    val repayBond = covBond(schedHealthy)
    val repayTx = {
      val tb    = ctx.newTxBuilder()
      val funds = Kit.selectBoxes(ctx, bAddr, repayment + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val exit = tb.outBoxBuilder()
        .value(repayment)
        .contract(lAddr.toErgoContract)
        .tokens(new ErgoToken(ErgoId.create(P4.FAKE_LOAN), 1L))
        .registers(ErgoValue.of(repayBond.getId.getBytes))
        .build()
      tb.boxesToSpend((Seq(repayBond) ++ funds).asJava)
        .outputs(exit).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: repay with NO data input (eager-eval probe)",
      borrowerP.reduce(repayTx, 0).getCost.toLong)

    val schedOff = Array[Long](0L, period, 0L, (h - 5).toLong, 0L, escrow)
    val offBond  = P4.fabBondV3(ctx, schedOff, Seq(lenderHash),
      borrowerHash, bondValue, repayment, maturity)
    val offPack  = Array[Long](0L, period, 0L, (h - 5).toLong + period, 0L, escrow - CRANK_BOUNTY)
    Jit.record("P4 gate: covenantOff crank with NO data input (eager-eval probe)",
      keeperP.reduce(crankTx(offBond, offPack, withPool = false), 0).getCost.toLong)

    val topUpBond = covBond(schedHealthy)
    val topUpTx = {
      val tb    = ctx.newTxBuilder()
      val rs    = topUpBond.getRegisters
      val funds = Kit.selectBoxes(ctx, bAddr, 2 * Kit.MIN_BOX_VALUE + Kit.TX_FEE)
      val succ = tb.outBoxBuilder()
        .value(topUpBond.getValue + Kit.MIN_BOX_VALUE)
        .contract(bondContract)
        .tokens(topUpBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4),
          ErgoValue.of(schedHealthy))
        .build()
      tb.boxesToSpend((Seq(topUpBond) ++ funds).asJava)
        .outputs(succ).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: top-up with NO data input (eager-eval probe)",
      borrowerP.reduce(topUpTx, 0).getCost.toLong)

    val schedCoupOff = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 0L, escrow)
    val coupOffBond  = P4.fabBondV3(ctx, schedCoupOff, Seq(lenderHash),
      borrowerHash, 35000000L, repayment, maturity)
    val coupOffTx    = P4.buildCoupon(ctx, coupOffBond,
      P4.honestCouponPlan(coupOffBond, lenderTreeBytes, healthyBranch = true), None, bAddr)
    Jit.record("P4 gate: covenantOff coupon with NO data input (eager-eval probe)",
      borrowerP.reduce(coupOffTx, 0).getCost.toLong)

    // ---- 6. PERMANENT PROBE: nonzero-installment repay (final payment) ----
    val schedFinal = Array[Long](P4.INSTALLMENT, period, 1L, (h + 100).toLong, 0L, 0L)
    val finalBond  = P4.fabBondV3(ctx, schedFinal, Seq(lenderHash),
      borrowerHash, 20000000L, repayment, maturity)
    val finalTx = {
      val tb    = ctx.newTxBuilder()
      val funds = Kit.selectBoxes(ctx, bAddr, repayment + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val exit = tb.outBoxBuilder()
        .value(repayment)
        .contract(lAddr.toErgoContract)
        .tokens(new ErgoToken(ErgoId.create(P4.FAKE_LOAN), 1L))
        .registers(ErgoValue.of(finalBond.getId.getBytes))
        .build()
      tb.boxesToSpend((Seq(finalBond) ++ funds).asJava)
        .outputs(exit).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: nonzero-installment repay at sched(2)==1 (no data input)",
      borrowerP.reduce(finalTx, 0).getCost.toLong)

    // ---- 7. PERMANENT PROBES: the order's new data-input surface ----
    val (_, orderContract) = Contracts.order(ctx)
    def fabOrder(pin: Array[Byte], tmpl: Array[Long], value: Long): InputBox =
      ctx.newTxBuilder().outBoxBuilder()
        .value(value)
        .contract(orderContract)
        .registers(
          ErgoValue.of(borrowerTreeBytes),
          ErgoValue.of(TestLib.PRINCIPAL),
          ErgoValue.of(TestLib.REPAYMENT),
          ErgoValue.of(720),
          P4.packValue(Seq(pin)),
          ErgoValue.of(tmpl))
        .build()
        .convertToInputWith(P4.DUMMY_TX, 3)

    val bulletTmpl = Array[Long](0L, 720L, 0L, 0L, 0L, 0L)
    val fabOrd     = fabOrder(Array.emptyByteArray, bulletTmpl, TestLib.COLLATERAL)
    val cancelTx = {
      val tb      = ctx.newTxBuilder()
      val coSpend = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val out = tb.outBoxBuilder()
        .value(fabOrd.getValue - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend((Seq(fabOrd) ++ coSpend).asJava)
        .outputs(out).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: order cancel with NO data input (eager-eval probe)",
      borrowerP.reduce(cancelTx, 0).getCost.toLong)

    val matchTx = P4.buildMatchV3(ctx, fabOrder(Array.emptyByteArray, bulletTmpl, TestLib.COLLATERAL),
      lenderTreeBytes, 720, None)
    Jit.record("P4 gate: card-less match with NO data input (eager-eval probe)",
      lenderP.reduce(matchTx, 0).getCost.toLong)

    // EKB rev-3 F1 discharge (PERMANENT): "unmatchable but cancellable"
    // must survive eager CSE. The K-division and tmpl index reads are
    // shared between schedCommonOk and the conformsWith lambda body —
    // the rev-1 crash class. If a toolchain revision ever hoists them
    // above the lazy guards, these cancels of MALFORMED orders crash
    // and the gate dies. (The all-cancel variant — a hoisted
    // bondBox.R7.get with a register-less OUTPUTS(0) — is discharged by
    // the plain cancel probe above: its recovery box carries no
    // registers at all.)
    def cancelOf(ord: InputBox): UnsignedTransaction = {
      val tb      = ctx.newTxBuilder()
      val coSpend = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val out = tb.outBoxBuilder()
        .value(ord.getValue - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend((Seq(ord) ++ coSpend).asJava)
        .outputs(out).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: cancel of tmpl(1)==0 order (division-hoist probe, EKB F1)",
      borrowerP.reduce(cancelOf(
        fabOrder(Array.emptyByteArray, Array[Long](0L, 0L, 0L, 0L, 0L, 0L), TestLib.COLLATERAL)), 0).getCost.toLong)
    Jit.record("P4 gate: cancel of short-R9 order (index-hoist probe, EKB F1)",
      borrowerP.reduce(cancelOf(
        fabOrder(Array.emptyByteArray, Array[Long](0L, 720L), TestLib.COLLATERAL)), 0).getCost.toLong)

    // Carded match (the order's first data input, 29%-budget class).
    val cardNft = ErgoId.create("77" * 32)
    val cardBox = P4.fabCard(ctx, cardNft, P4.CARD_A_R7, P4.explicitCardR8)
    val cardedOrd = fabOrder(cardNft.getBytes, bulletTmpl, TestLib.COLLATERAL)
    val cardedTx  = P4.buildMatchV3(ctx, cardedOrd, lenderTreeBytes, 720, Some(cardBox))
    Jit.record("P4 gate: carded match (1 data input, fabricated card)",
      lenderP.reduce(cardedTx, 0).getCost.toLong)

    // ---- 7b. PERMANENT PROBES (rev 4): the order's ctx-extension surface.
    // getVar[T] is FALLIBLE in a way dataInputs is not: a var present at
    // the right index with the WRONG TYPE throws InvalidType where an
    // absent var returns None (audit A-M3). The reveal lives inside the
    // twice-applied conformsWith, so if the optimizer ever hoisted that
    // read out of the lazy match chain, a cancel carrying a mistyped var
    // would stop being a cancel and start being a brick. Both shapes must
    // reduce cleanly: the cancel arm never looks at var 0.
    val varOrd = fabOrder(Array.emptyByteArray, bulletTmpl, TestLib.COLLATERAL)
    def cancelWithInput(ord: InputBox): UnsignedTransaction = {
      val tb      = ctx.newTxBuilder()
      val coSpend = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val out = tb.outBoxBuilder()
        .value(ord.getValue - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend((Seq(ord) ++ coSpend).asJava)
        .outputs(out).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: cancel with WRONG-TYPED ctx var 0 (Long, not Coll[Byte]) — must still cancel",
      borrowerP.reduce(cancelWithInput(
        varOrd.withContextVars(new ContextVar(0.toByte, ErgoValue.of(42L)))), 0).getCost.toLong)
    Jit.record("P4 gate: cancel with the honest var-0 SHAPE attached (control)",
      borrowerP.reduce(cancelWithInput(
        varOrd.withContextVars(new ContextVar(0.toByte, ErgoValue.of(lenderTreeBytes)))), 0).getCost.toLong)

    // Cancel BATCHED with a match in one transaction: the cancelled order
    // is INPUTS(1), so its matchOk gets PAST bondScriptOk (OUTPUTS(0) is a
    // real bond box) and stops at INPUTS(0).id == SELF.id — the deepest
    // any non-match spend gets into the match chain. It must still fall
    // through to the cancel arm: the loan token minted for the OTHER order
    // carries a different id, so noLoanTokenMinted holds.
    // The two fabricated orders differ in value so they are distinct boxes
    // (a fabrication carrying the same id twice is not a transaction).
    val batchOrdA = fabOrder(Array.emptyByteArray, bulletTmpl, TestLib.COLLATERAL)
    val batchOrdB = fabOrder(Array.emptyByteArray, bulletTmpl, TestLib.COLLATERAL + 1000000L)
    val batchTx = {
      val matchOnly = P4.buildMatchV3(ctx, batchOrdA, lenderTreeBytes, 720, None)
      val tb    = ctx.newTxBuilder()
      val funds = Kit.selectBoxes(ctx, bAddr, TestLib.PRINCIPAL + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val recovery = tb.outBoxBuilder()
        .value(batchOrdB.getValue - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend((Seq(P4.orderWithMatchVars(batchOrdA, lenderTreeBytes), batchOrdB) ++ funds).asJava)
        .outputs(matchOnly.getOutputs.get(0), matchOnly.getOutputs.get(1), recovery)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: cancel BATCHED with a match (order past bondScriptOk, stops at INPUTS(0).id)",
      borrowerP.reduce(batchTx, 0).getCost.toLong)

    // ---- 8. borrowerAuth eager-evaluation safety ----
    // Signatureless paths carry ZERO borrower-script inputs; if the
    // INPUTS.exists were hoisted in a crashing form, these reduces die.
    // The borrower-arm probes above (repay/top-up/cure, borrower-funded)
    // cover the true branch. Recorded as its own row so a recurrence is
    // visible in JITCOST.md, not just implied.
    Jit.record("P4 gate: borrowerAuth eager-safety (signatureless crank, zero borrower inputs)",
      keeperP.reduce(crankTx(covBond(schedHealthy), advPack, withPool = true), 0).getCost.toLong)

    // ---- 9. New-path cost rows: missed-accel, hooked liquidation,
    // plain liquidation, refuel, attestation stub ----
    val schedMissed = Array[Long](P4.INSTALLMENT, period, 3L, (h - 15).toLong, 0L, escrow)
    val missedBond  = P4.fabBondV3(ctx, schedMissed, Seq(lenderHash),
      borrowerHash, 35000000L, repayment, maturity)
    val missedTx = P4.buildMissedAccel(ctx, missedBond,
      P4.honestMissedAccelPlan(missedBond, lenderTreeBytes), bAddr, preHeaderHeight = Some(h))
    require((h - 15).toLong + GRACE_BLOCKS <= h.toLong, "probe setup: deadline+grace must be past")
    Jit.record("P4 gate: missed-payment acceleration (local reduce, no data input)",
      keeperP.reduce(missedTx, 0).getCost.toLong)

    val vaultBytes = TestLib.vaultTree().bytes
    val hookHash   = scorex.crypto.hash.Blake2b256(vaultBytes)
    val hookedBond = P4.fabBondV3(ctx,
      Array[Long](0L, period, 0L, (h + 100).toLong, 15000L, escrow),
      Seq(lenderHash, poolNftBytes, hookHash), borrowerHash,
      bondValue, repayment, maturity, Seq(rsn))
    val hookedTx = P4.buildHookedLiquidation(ctx, hookedBond, vaultBytes, bAddr,
      preHeaderHeight = Some(maturity + 1))
    Jit.record("P4 gate: hooked liquidation (ctx-ext var preimage, local reduce)",
      keeperP.reduce(hookedTx, 0).getCost.toLong)

    val liqBond = covBond(Array[Long](0L, period, 0L, (h + 100).toLong, 15000L, escrow))
    val liqTx = {
      val tb = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(maturity + 1).build())
      val exit = tb.outBoxBuilder()
        .value(liqBond.getValue - LIQ_CARVEOUT)
        .contract(lAddr.toErgoContract)
        .tokens(liqBond.getTokens.asScala.toSeq: _*)
        .registers(ErgoValue.of(liqBond.getId.getBytes))
        .build()
      val kb = tb.outBoxBuilder().value(LIQ_CARVEOUT - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(liqBond))
        .outputs(exit, kb).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: plain liquidation past maturity (local reduce)",
      keeperP.reduce(liqTx, 0).getCost.toLong)

    val refuelCard = P4.fabCard(ctx, ErgoId.create("88" * 32), P4.CARD_B_R7, P4.sentinelCardR8)
    val refuelTx   = P4.buildRefuel(ctx, refuelCard,
      P4.honestRefuelPlan(refuelCard, grow = 1000000L), borrowerP)
    Jit.record("P4 gate: card refuel value-grow (local reduce)",
      borrowerP.reduce(refuelTx, 0).getCost.toLong)

    // Attestation stub: fabricated nonzero-type bond exercises the
    // generic branch LOCALLY (unreachable by any conforming bond — the
    // order's type-0 gate; audit note in CONTRACT-DELTAS §5.4).
    val (attTree, _) = Kit.compile(ctx, "{ sigmaProp(HEIGHT > 0) }", ConstantsBuilder.empty())
    val attHash      = scorex.crypto.hash.Blake2b256(attTree.bytes)
    val schedAtt = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow,
      CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP, 1L)
    val attBond = P4.fabBondV3(ctx, schedAtt,
      Seq(lenderHash, poolNftBytes, Array.emptyByteArray, attHash),
      borrowerHash, bondValue, repayment, maturity, Seq(rsn))
    val attBox  = P4.fabAttesterBox(ctx, attTree,
      ErgoId.create(P4.FAKE_LOAN).getBytes, (h - 5).toLong, pass = true)
    val attAdvPack = Array[Long](0L, period, 0L, (h - 5).toLong + period, 15000L, escrow - CRANK_BOUNTY,
      CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP, 1L)
    val attTx = {
      val tb = ctx.newTxBuilder()
      val rs = attBond.getRegisters
      val succ = tb.outBoxBuilder()
        .value(attBond.getValue - CRANK_BOUNTY)
        .contract(bondContract)
        .tokens(attBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(attAdvPack))
        .build()
      val kb = tb.outBoxBuilder().value(CRANK_BOUNTY - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(attBond))
        .withDataInputs(java.util.Arrays.asList(attBox))
        .outputs(succ, kb).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P4 gate: attestation generic branch (fabricated nonzero-type bond, local)",
      keeperP.reduce(attTx, 0).getCost.toLong)

    println("Phase 4 gate probes complete (8 permanent probes + rev-3 path costs).")
    ()
  }
}
