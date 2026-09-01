package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import scala.collection.JavaConverters._

/** Rev-5 compile gate: Phase4Gate's permanent probe set ported to all six
  * per-product trees, plus the probes the rev-5 audits added. Spend-free —
  * fabricated boxes at the six real addresses, the REAL pinned pool as a
  * data input, local reduce + local sign, nothing submitted.
  *
  * WHY THIS EXISTS. The ErgoScript compiler hoists common subexpressions
  * into eager top-level values ABOVE the lazy &&/branch guards they sit
  * under in source. A read that can throw — CONTEXT.dataInputs(0) with no
  * data inputs, SELF.tokens(0) on an ERG-only box, a guarded .get, a
  * getVar[T] against a wrongly-typed variable — must therefore never be
  * reachable except under its own guard. Every probe below drives a path
  * whose ABSENT case would crash if such a read were hoisted.
  *
  * ASSERTION DISCIPLINE. `prover.reduce()` does NOT throw on a false
  * proposition, so a reduce-only probe proves "no eager-eval crash" and
  * NOTHING about whether the path is satisfied. Every row here is
  * therefore sign-based:
  *   pass   reduces (no crash) AND signs (the path is genuinely
  *          satisfied). The JitCost row comes from the reduce.
  *   reject must fail at SIGNING with a clean script rejection —
  *          reduce-to-false or an unprovable residual. An InvalidType
  *          crash is a FAILURE of this assertion, not a pass.
  *   crash  must fail at signing with sigma's InvalidType. Used only
  *          where the shape itself is a type violation (a wrongly-typed
  *          register or context variable READ ON A PATH THAT READS IT):
  *          the spend is still refused, but as a crash, and it bricks
  *          only that attempt.
  *
  * COST-IDENTITY / HOIST VERDICT. Where several probes differ ONLY in
  * bytes the exercised path must never touch — a wrongly-typed context
  * variable, a zero period, a truncated R9, an absent token — identical
  * JitCost is the evidence that the compiler did not hoist those reads
  * above their guards. A divergence is a HOIST: the gate stops and prints
  * the numbers rather than papering over it, because the contracts are
  * frozen and a hoist means a contract change needing sign-off.
  *
  * BORN-LIQUIDATABLE CLASS. All three orders carry the m > HEIGHT + 1
  * floor: a bond always gets at least the whole birth block with the
  * borrower's exit open and liquidation shut. The two SCHEDULED orders
  * additionally require the anchored first checkpoint,
  * (m - term) + tmpl(1), to land strictly after the birth block. Each
  * rule is probed at its own boundary by an enforced reject/pass twin —
  * the anchor pairs are set up so the +1 floor is slack, so they prove
  * the anchor conjunct and not merely the floor. A regression FAILS this
  * gate rather than printing a row about it. All three orders also do
  * the maturity arithmetic in Long, so a term == Int.MaxValue order is
  * unmatchable-but-cancellable rather than a crash on every path; that
  * pair is probed too, with the cancels folded into the cost-identity
  * families so a hoisted maturity read would show up as a divergence.
  *
  * Phase4Gate still targets the rev-4 monolith and stays green on its own
  * — main is live on rev 4. This is a separate runner.
  *
  *   sbt "runMain bonds.Rev5Gate"
  */
object Rev5Gate {
  import Contracts._

  private var positives = 0
  private var negatives = 0
  private val costs     = scala.collection.mutable.LinkedHashMap[String, Int]()
  private val verdicts  = scala.collection.mutable.ArrayBuffer[String]()

  /** Sign-positive: reduce (proves no eager-eval crash, yields the cost)
    * AND sign (proves the path is satisfied). Records the JitCost row. */
  private def pass(label: String, p: ErgoProver)(build: => UnsignedTransaction): Int = {
    require(!costs.contains(label), s"duplicate probe label: $label")
    val tx   = build
    val cost = p.reduce(tx, 0).getCost
    p.sign(tx)
    positives += 1
    costs.put(label, cost)
    Jit.record(s"Rev5 gate: $label", cost.toLong)
    println(f"  PASS  $label%-74s $cost%6d")
    cost
  }

  /** Sign-negative: must be refused at signing by the SCRIPT — reduced to
    * false, or a residual the signer cannot prove. An InvalidType crash
    * fails this assertion: "rejected" and "crashed" are different facts
    * and only one of them is the contract doing its job. */
  private def reject(label: String, p: ErgoProver)(build: => UnsignedTransaction): Unit =
    scala.util.Try(p.sign(build)) match {
      case scala.util.Success(_) =>
        sys.error(s"[$label] EXPECTED rejection but the shape SIGNED")
      case scala.util.Failure(e) =>
        val msg = Kit.causeChain(e)
        val clean = msg.contains("educed to false") || msg.contains("ReducedToFalse") ||
                    msg.contains("Tree root should be real") || msg.contains("UnprovenSchnorr")
        if (msg.contains("InvalidType"))
          sys.error(s"[$label] rejected as an InvalidType CRASH, wanted a clean script rejection: $msg")
        if (!clean)
          sys.error(s"[$label] failed for the WRONG reason: $msg")
        negatives += 1
        println(s"  REJ   $label — clean script rejection")
    }

  /** Sign-negative whose expected failure CLASS is the interpreter's
    * InvalidType: getVar[T]/box.RN[T] against a value of another type
    * throws in sigma 6.0.2 where an ABSENT read returns None. The spend
    * is refused either way; asserting the class is what distinguishes
    * "this attempt is malformed" from "this box is bricked". */
  private def crash(label: String, p: ErgoProver)(build: => UnsignedTransaction): Unit =
    scala.util.Try(p.sign(build)) match {
      case scala.util.Success(_) =>
        sys.error(s"[$label] EXPECTED an InvalidType crash but the shape SIGNED")
      case scala.util.Failure(e) =>
        val msg = Kit.causeChain(e)
        if (!msg.contains("InvalidType"))
          sys.error(s"[$label] failed, but NOT with InvalidType as expected: $msg")
        negatives += 1
        println(s"  REJ   $label — InvalidType crash (this attempt only)")
    }

  /** Hoist verdict over a probe family whose members differ only in bytes
    * the path must never read. Identical cost = no hoist. */
  private def noHoist(family: String, labels: String*): Unit = {
    val members = labels.map { l =>
      (l, costs.getOrElse(l, sys.error(s"noHoist[$family]: no cost recorded for '$l'")))
    }
    val distinct = members.map(_._2).distinct
    if (distinct.size == 1) {
      verdicts += s"NO HOIST  $family (${members.size} variants, all ${distinct.head})"
      println(f"  ==>   NO HOIST: $family%-56s ${members.size} variants all at ${distinct.head}")
    } else {
      members.foreach { case (l, c) => println(f"        $l%-74s $c%6d") }
      verdicts += s"HOIST DETECTED  $family (${distinct.mkString(", ")})"
      sys.error(s"HOIST DETECTED in [$family]: costs diverge (${distinct.mkString(", ")}). " +
        "STOP — the contracts are frozen; a hoist is a contract change needing sign-off.")
    }
  }

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val h         = ctx.getHeight
    val borrowerP = TestLib.borrower(ctx)
    val lenderP   = TestLib.lender(ctx)
    val keeperP   = Kit.noSecretProver(ctx)   // anonymous: signs only fully-true residuals
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

    // The two ctx-extension shapes every "wrong-typed var" probe uses. A
    // Long where the contract reads Coll[Byte] is the type mismatch that
    // throws; the honest shape is the control that isolates the type from
    // the mere presence of a variable.
    def varLong   = new ContextVar(0.toByte, ErgoValue.of(42L))
    def varLender = new ContextVar(0.toByte, ErgoValue.of(lenderTreeBytes))

    val pool = P3.poolBox(ctx)
    val (rX, rY, feeNum) = P3.reserves(pool)
    println(s"height $h  pool ${pool.getId}  rX=$rX rY=$rY feeNum=$feeNum")
    R5.all(ctx).foreach { f =>
      println(f"  ${f.name}%-22s bond ${f.bondTree.bytes.length}%5dB  order ${f.orderTree.bytes.length}%5dB")
    }

    // Phase3Gate/Phase4Gate fixture, carried so the rev-5 rows stay
    // comparable with the rev-4 monolith's same-path rows (same 6.0
    // cost scale). Both requires price against LIVE reserves, so a
    // covenant probe can never silently degrade into a no-op if the pool
    // moves — it fails here, loudly, instead of measuring nothing.
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

    // The exact live healthy floor L: accelerate/cure fixtures are priced
    // off it rather than off a hand-picked value, so "unhealthy" is true
    // by construction whatever the reserves are doing. (Phase4Gate's
    // acceleration row is a known defect for exactly this reason — its
    // fixture prices HEALTHY against the live pool, so the tx reduces to
    // false and the row measures nothing. A reduce never notices; a sign
    // does. Not copied.)
    val thrX = 20000L
    val lX   = P3.ergLegForHealthy(pool, amtRSN, repayment, thrX)
    require(lX > 1L, "fixture: healthy boundary must be positive")
    require(P3.healthy(pool, lX, amtRSN, repayment, thrX) &&
            !P3.healthy(pool, lX - 1L, amtRSN, repayment, thrX),
      "fixture: L must be the exact healthy floor")
    val escrowLive = escrow - CRANK_BOUNTY
    val blownValue = escrowLive + lX - 1L           // ergLeg == L - 1 -> unhealthy
    val healthyVal = blownValue + 1L                // ergLeg == L     -> healthy

    // One fee-input selection reused by every cancel probe: a differing
    // input COUNT would change the cost and make a hoist verdict spurious.
    val feeIn = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)

    /** Cancel batched with a match in one transaction. The cancelled order
      * is INPUTS(1), so its matchOk gets PAST bondScriptOk — OUTPUTS(0) IS
      * a conforming bond box — and stops at INPUTS(0).id == SELF.id, the
      * deepest any non-match spend reaches into the match chain. It must
      * still fall through to the cancel arm. The two orders differ in
      * value so they are distinct boxes. */
    def batched(matchTx: UnsignedTransaction, ordAWithVars: InputBox,
                ordB: InputBox, funds: Seq[InputBox]): UnsignedTransaction = {
      val tb = ctx.newTxBuilder()
      val recovery = tb.outBoxBuilder()
        .value(ordB.getValue - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend((Seq(ordAWithVars, ordB) ++ funds).asJava)
        .outputs(matchTx.getOutputs.get(0), matchTx.getOutputs.get(1), recovery)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    val matchFunds = Kit.selectBoxes(ctx, bAddr, TestLib.PRINCIPAL + Kit.TX_FEE + Kit.MIN_BOX_VALUE)

    // ================================================================
    // B1 / O1 — plain bullet. No data inputs, no cards, no schedule, no
    // escrow anywhere in this product: the order's only fallible read is
    // getVar[Coll[Byte]](0) inside the match chain, and the bond has no
    // fallible surface at all.
    // ================================================================
    println("\n--- O1 plain-bullet order ---")
    val term1 = TestLib.TERM_LONG
    def ord1(term: Int = term1, value: Long = TestLib.COLLATERAL): InputBox =
      R5.fabPlainOrder(ctx, borrowerTreeBytes, value = value, term = term)
    val o1 = ord1()

    pass("O1 cancel, no ctx vars", borrowerP) {
      R5.buildCancel(ctx, o1, bAddr, feeIn)
    }
    // The reveal lives inside the match chain; a cancel never looks at
    // var 0. If the optimizer hoisted that getVar, a cancel carrying a
    // mistyped var would stop being a cancel and start being a brick.
    pass("O1 cancel with a WRONG-TYPED ctx var 0 (Long)", borrowerP) {
      R5.buildCancel(ctx, o1.withContextVars(varLong), bAddr, feeIn)
    }
    pass("O1 cancel with the honest var-0 shape attached (control)", borrowerP) {
      R5.buildCancel(ctx, o1.withContextVars(varLender), bAddr, feeIn)
    }
    // Unmatchable-but-cancellable: a term == Int.MaxValue order is refused
    // by the maturity window, and the whole maturity block must stay
    // inside the match chain — a cancel may never touch it.
    pass("O1 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
      R5.buildCancel(ctx, ord1(term = Int.MaxValue), bAddr, feeIn)
    }
    noHoist("O1 cancel (match-chain getVar and maturity block stay lazy)",
      "O1 cancel, no ctx vars",
      "O1 cancel with a WRONG-TYPED ctx var 0 (Long)",
      "O1 cancel with the honest var-0 shape attached (control)",
      "O1 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)")

    pass("O1 cancel BATCHED with a match", borrowerP) {
      val ordA = ord1()
      val ordB = ord1(value = TestLib.COLLATERAL + 1000000L)
      val m    = R5.buildPlainMatch(ctx, ordA, lenderTreeBytes, term1, borrowerP)
      batched(m, R5.plainOrderWithLenderVar(ordA, lenderTreeBytes), ordB, matchFunds)
    }

    pass("O1 card-less match, NO data input (var-0 reveal)", lenderP) {
      R5.buildPlainMatch(ctx, o1, lenderTreeBytes, term1, lenderP)
    }
    reject("O1 match with the lender reveal DROPPED", lenderP) {
      R5.buildPlainMatch(ctx, o1, lenderTreeBytes, term1, lenderP, dropLenderVar = true)
    }
    // B1's bond R8 is a PLAIN Coll[Byte]; a rev-4 Coll[Coll[Byte]] pack
    // there does not read as "absent", it throws. The copy-paste trap,
    // caught at the type layer rather than as a false.
    crash("O1 match writing a rev-4 Coll[Coll[Byte]] PACK into bond R8", lenderP) {
      R5.buildPlainMatch(ctx, o1, lenderTreeBytes, term1, lenderP,
        bondR8Override = Some(P4.packValue(Seq(lenderHash))))
    }
    // The born-liquidatable floor at its boundary. m > HEIGHT + 1: the
    // bond gets the whole birth block with repayment open and liquidation
    // shut. One block wide — h+1 rejects, h+2 signs. B1 has no schedule,
    // so the +1 floor is the only maturity floor on this product.
    reject("O1 match stamping maturity == HEIGHT + 1 (born liquidatable)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(term = 3), lenderTreeBytes, 3, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    pass("O1 match stamping maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(term = 3), lenderTreeBytes, 3, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // Long parity: pre-fix O1 already did this arithmetic in Long and
    // rejected cleanly here, where O2/O3 threw ArithmeticException. All
    // three now match O1. The cancel twin lives in the family above.
    reject("O1 match of a term == Int.MaxValue order (Long arithmetic, clean reject)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(term = Int.MaxValue), lenderTreeBytes, Int.MaxValue, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }

    println("\n--- B1 plain-bullet bond ---")
    val b1Bond = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity)
    val b1Tok  = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity,
      tokens = Seq(rsn))
    pass("B1 repay, ERG-only (no data-input surface on this product)", borrowerP) {
      R5.buildRepay(ctx, b1Bond, lenderTreeBytes, bAddr, borrowerP)
    }
    pass("B1 repay with token collateral (collateralToBorrower)", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP)
    }
    reject("B1 repay routing the token collateral to the LENDER", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP, collateralTo = Some(lAddr))
    }
    // B1 reads no context variable on any path, so an attached var must
    // be inert. This pair is also the cross-product control for every
    // other identity family below: it isolates "a ctx var is attached"
    // from "a ctx var is read", and proves attachment alone is free.
    pass("B1 liquidate past maturity, no ctx vars", keeperP) {
      R5.buildLiquidate(ctx, b1Bond, lenderTreeBytes, bAddr, LIQ_CARVEOUT,
        preHeaderHeight = Some(maturity + 1))
    }
    pass("B1 liquidate past maturity with a WRONG-TYPED ctx var 0", keeperP) {
      R5.buildLiquidate(ctx, b1Bond.withContextVars(varLong), lenderTreeBytes, bAddr,
        LIQ_CARVEOUT, preHeaderHeight = Some(maturity + 1))
    }
    noHoist("B1 liquidate (ctx-var attachment is cost-neutral — the control)",
      "B1 liquidate past maturity, no ctx vars",
      "B1 liquidate past maturity with a WRONG-TYPED ctx var 0")
    reject("B1 liquidate BEFORE maturity", keeperP) {
      R5.buildLiquidate(ctx, b1Bond, lenderTreeBytes, bAddr, LIQ_CARVEOUT,
        preHeaderHeight = Some(maturity - 1))
    }

    // ================================================================
    // B2 / O2 — covenant bullet. Mandatory covenant, so the order's
    // conformsWith always reaches SELF.tokens(0) and the bond's three
    // verdictAt sites (crank / cure / accelerate) all read
    // CONTEXT.dataInputs.
    // ================================================================
    println("\n--- O2 covenant-bullet order ---")
    val term2 = 720
    val tmpl2 = R5.covenantTemplate(term2, 360L, 15000L)
    val val2  = TestLib.COLLATERAL + tmpl2(5)
    val maxTmpl2 = R5.covenantTemplate(Int.MaxValue, 1000000000L, 15000L)
    def ord2(tmpl: Array[Long] = tmpl2, toks: Seq[ErgoToken] = Seq(rsn),
             value: Long = val2, term: Int = term2): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl, value, tokens = toks, term = term)
    val o2 = ord2()

    pass("O2 cancel, no ctx vars", borrowerP) {
      R5.buildCancel(ctx, o2, bAddr, feeIn)
    }
    pass("O2 cancel with a WRONG-TYPED ctx var 0 (Long)", borrowerP) {
      R5.buildCancel(ctx, o2.withContextVars(varLong), bAddr, feeIn)
    }
    pass("O2 cancel with the honest var-0 shape attached (control)", borrowerP) {
      R5.buildCancel(ctx, o2.withContextVars(varLender), bAddr, feeIn)
    }
    // Unmatchable but cancellable, the EKB F1 class: the K division
    // ((term - 1) / tmpl(1)) sits inside conformsWith behind the
    // MIN_PERIOD floor, and every tmpl index sits behind tmpl.size == 6.
    // Hoist either and these cancels become divide-by-zero / index
    // crashes, and the borrower's collateral is trapped.
    pass("O2 cancel of a tmpl(1)==0 order (division-hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord2(tmpl = Array[Long](0L, 0L, 0L, 0L, 0L, 0L)), bAddr, feeIn)
    }
    pass("O2 cancel of a short-R9 order (index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord2(tmpl = Array[Long](0L, 720L)), bAddr, feeIn)
    }
    // NEW: maturityOk now reads tmpl(1) too, so the size-3 template is a
    // second index-hoist probe against a DIFFERENT read site, and it is
    // the shape the matchOk reordering exists for. A cancel must never
    // reach either read.
    pass("O2 cancel of a size-3-tmpl order (maturityOk index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord2(tmpl = Array[Long](0L, 4L, 0L), term = 5), bAddr, feeIn)
    }
    // NEW: Long parity. A term == Int.MaxValue order is unmatchable
    // (the window cannot be satisfied) but must stay cancellable — and
    // pre-fix it was neither, because HEIGHT + term threw in Int.
    pass("O2 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
      R5.buildCancel(ctx, ord2(tmpl = maxTmpl2, term = Int.MaxValue), bAddr, feeIn)
    }
    noHoist("O2 cancel, token-carrying",
      "O2 cancel, no ctx vars",
      "O2 cancel with a WRONG-TYPED ctx var 0 (Long)",
      "O2 cancel with the honest var-0 shape attached (control)",
      "O2 cancel of a tmpl(1)==0 order (division-hoist probe)",
      "O2 cancel of a short-R9 order (index-hoist probe)",
      "O2 cancel of a size-3-tmpl order (maturityOk index-hoist probe)",
      "O2 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)")

    // NEW in rev 5. conformsWith reads SELF.tokens(0)._1 behind
    // SELF.tokens.size == 1; an ERG-only order at this address makes that
    // read throw if it is ever hoisted. Such orders are unmatchable (the
    // covenant needs the pool's traded token as collateral) but must stay
    // cancellable, and the ERG-only population widened in rev 5 because
    // the split gave this product its own address. Its own family: a
    // missing token box changes the honest cost, so it cannot be compared
    // against the token-carrying rows.
    pass("O2 cancel of an ERG-only order (SELF.tokens(0) hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord2(toks = Nil), bAddr, feeIn)
    }
    pass("O2 cancel of an ERG-only order with a WRONG-TYPED ctx var 0", borrowerP) {
      R5.buildCancel(ctx, ord2(toks = Nil).withContextVars(varLong), bAddr, feeIn)
    }
    noHoist("O2 cancel, ERG-only",
      "O2 cancel of an ERG-only order (SELF.tokens(0) hoist probe)",
      "O2 cancel of an ERG-only order with a WRONG-TYPED ctx var 0")

    pass("O2 cancel BATCHED with a match", borrowerP) {
      val ordA = ord2()
      val ordB = ord2(toks = Nil, value = val2 + 1000000L)
      val m    = R5.buildPackedMatch(ctx, B2, ordA, lenderTreeBytes, term2, borrowerP)
      batched(m, R5.packedOrderWithMatchVars(ordA, lenderTreeBytes), ordB, matchFunds)
    }
    pass("O2 card-less match, NO data input (R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B2, o2, lenderTreeBytes, term2, lenderP)
    }
    reject("O2 match with the lender reveal DROPPED", lenderP) {
      R5.buildPackedMatch(ctx, B2, o2, lenderTreeBytes, term2, lenderP, dropLenderVar = true)
    }
    reject("O2 match writing a size-1 (plain-product) bond R8 pack", lenderP) {
      R5.buildPackedMatch(ctx, B2, o2, lenderTreeBytes, term2, lenderP,
        bondR8Override = Some(Seq(lenderHash)))
    }
    // The born-liquidatable floor at its boundary, same pair as O1. The
    // shortest legal covenant order is term 5 / period 4 (K == 1), which
    // sits entirely inside MATURITY_TOL. One block wide: h+1 rejects,
    // h+2 signs.
    val shortTmpl2 = R5.covenantTemplate(5, 4L, 15000L)
    def shortOrd2(): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, shortTmpl2,
        TestLib.COLLATERAL + shortTmpl2(5), tokens = Seq(rsn), term = 5)
    reject("O2 match stamping maturity == HEIGHT + 1 (born liquidatable)", lenderP) {
      R5.buildPackedMatch(ctx, B2, shortOrd2(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    pass("O2 match stamping maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B2, shortOrd2(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // The ANCHOR rule with the +1 floor deliberately SLACK, so this pair
    // can only be explained by the anchor conjunct. term 9 / period 4
    // (K == 2): the tolerance window is [h+4, h+9], and m == h+5 clears
    // m > HEIGHT + 1 by three blocks — yet the first checkpoint it
    // implies, (m - term) + period, is h itself, i.e. a crank grid
    // already at the birth block. m == h+6 puts it at h+1.
    val anchorTmpl2 = R5.covenantTemplate(9, 4L, 15000L)
    def anchorOrd2(): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, anchorTmpl2,
        TestLib.COLLATERAL + anchorTmpl2(5), tokens = Seq(rsn), term = 9)
    reject("O2 match with the first checkpoint AT the birth block (anchor rule, floor slack)",
      lenderP) {
      R5.buildPackedMatch(ctx, B2, anchorOrd2(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }
    pass("O2 match with the first checkpoint at HEIGHT + 1 (anchor pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B2, anchorOrd2(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }
    // Long parity: this was an ArithmeticException CRASH before the
    // maturity arithmetic moved to Long. A crash is not a rejection.
    reject("O2 match of a term == Int.MaxValue order (was an ArithmeticException crash)",
      lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2(tmpl = maxTmpl2, term = Int.MaxValue),
        lenderTreeBytes, Int.MaxValue, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // The matchOk ORDERING hazard, head on: maturityOk reads tmpl(1) and
    // schedCommonOk is what enforces tmpl.size == 6, so schedCommonOk was
    // moved AHEAD of maturityOk. On a size-3 template the match must be a
    // CLEAN rejection, never an index crash — `reject` (not `crash`) is
    // the assertion that says so. The cancel twin is in the family above.
    reject("O2 match of a size-3-tmpl order (schedCommonOk precedes maturityOk)", lenderP) {
      val bad = Array[Long](0L, 4L, 0L)
      R5.buildPackedMatch(ctx, B2, ord2(tmpl = bad, term = 5), lenderTreeBytes, 5, lenderP,
        bondSchedOverride = Some(bad),
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }

    println("\n--- B2 covenant-bullet bond ---")
    def b2Bond(sched: Array[Long], pack: Seq[Array[Byte]] = Seq(lenderHash, poolNftBytes),
               value: Long = bondValue, toks: Seq[ErgoToken] = Seq(rsn)): InputBox =
      R5.fabPackedBond(ctx, B2, sched, pack, borrowerHash, value, repayment, maturity, toks)

    val schedHealthy   = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow)
    val schedUnhealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 20000L, escrow)
    val schedOffB2     = Array[Long](0L, period, 0L, (h - 5).toLong, 0L, escrow)
    val crankBond      = b2Bond(schedHealthy)

    pass("B2 crank HEALTHY (live pool data input)", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankAdvancePack(schedHealthy), Some(pool), bAddr)
    }
    reject("B2 crank HEALTHY writing the CURE pack", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankCurePack(schedHealthy), Some(pool), bAddr)
    }
    pass("B2 crank UNHEALTHY -> cure (live pool data input)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedUnhealthy), R5.crankCurePack(schedUnhealthy), Some(pool), bAddr)
    }
    reject("B2 crank UNHEALTHY writing the ADVANCE pack", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedUnhealthy), R5.crankAdvancePack(schedUnhealthy), Some(pool), bAddr)
    }
    // The absent-data-input case on a bond whose covenant IS on: verdictAt
    // must return its -1 sentinel and the crank must fail CLOSED. This is
    // the exact shape a hoisted CONTEXT.dataInputs(0) would crash on.
    reject("B2 crank on a COVENANT bond with the pool data input DROPPED (fails closed)", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankAdvancePack(schedHealthy), None, bAddr)
    }
    pass("B2 covenantOff crank with NO data input (eager-eval probe)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedOffB2, Seq(lenderHash)),
        R5.crankAdvancePack(schedOffB2), None, bAddr)
    }
    // Repay and top-up both leave the crank/cure/accelerate arms unentered
    // on a COVENANT bond, so neither carries a data input: the guarded
    // exitBox.R9.get and every verdictAt call must stay unevaluated.
    pass("B2 repay with NO data input (eager-eval probe)", borrowerP) {
      R5.buildRepay(ctx, crankBond, lenderTreeBytes, bAddr, borrowerP)
    }
    pass("B2 top-up with NO data input (eager-eval probe)", borrowerP) {
      P2.buildTopUp(ctx, crankBond, Kit.MIN_BOX_VALUE, borrowerP)
    }

    // Liquidation, both destinations. Never probed in rev 4.
    // liqDestOk reads getVar[Coll[Byte]](0) only when the R8 pack commits
    // a hook (size >= 3); an unhooked bond carrying a mistyped var 0 must
    // be untouched by it.
    pass("B2 liquidate unhooked past maturity, no ctx vars", keeperP) {
      R5.buildLiquidate(ctx, crankBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, crankBond), preHeaderHeight = Some(maturity + 1))
    }
    pass("B2 liquidate unhooked with a WRONG-TYPED ctx var 0", keeperP) {
      R5.buildLiquidate(ctx, crankBond.withContextVars(varLong), lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, crankBond), preHeaderHeight = Some(maturity + 1))
    }
    noHoist("B2 liquidate unhooked (hook getVar stays under the pack-size guard)",
      "B2 liquidate unhooked past maturity, no ctx vars",
      "B2 liquidate unhooked with a WRONG-TYPED ctx var 0")
    reject("B2 liquidate BEFORE maturity", keeperP) {
      R5.buildLiquidate(ctx, crankBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, crankBond), preHeaderHeight = Some(maturity - 1))
    }
    // Hooked: the preimage rides ctx-ext var 0 of the BOND input — the
    // other end of the rev-4 var-0 index collision (the ORDER's var 0 is
    // the lender script at match).
    val hookedB2 = b2Bond(schedHealthy, Seq(lenderHash, poolNftBytes, hookHash))
    pass("B2 HOOKED liquidate (bond var-0 preimage, no data input)", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedB2, vaultBytes, bAddr,
        preHeaderHeight = Some(maturity + 1))
    }
    reject("B2 hooked liquidate paying the LENDER instead of the hook", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedB2, vaultBytes, bAddr,
        exitTreeOverride = Some(lenderTreeBytes), preHeaderHeight = Some(maturity + 1))
    }
    // The same mistyped var on the path that DOES read it: a crash, and
    // it bricks only this attempt — the box stays liquidatable by anyone
    // who supplies the preimage at the right type.
    crash("B2 hooked liquidate with a WRONG-TYPED ctx var 0", keeperP) {
      R5.buildLiquidate(ctx, hookedB2.withContextVars(varLong), vaultBytes, bAddr,
        R5.carveOfBond(ctx, hookedB2), preHeaderHeight = Some(maturity + 1))
    }

    val schedBlownB2 = Array[Long](0L, period, 0L, -((h - 3).toLong), thrX, escrowLive)
    pass("B2 covenant accelerate (deadline passed, unhealthy NOW)", keeperP) {
      R5.buildAccelerate(ctx, b2Bond(schedBlownB2, value = blownValue), lenderTreeBytes,
        pool, bAddr, preHeaderHeight = Some(h))
    }
    reject("B2 accelerate at ergLeg == L (healthy, must NOT fire) [boundary twin]", keeperP) {
      R5.buildAccelerate(ctx, b2Bond(schedBlownB2, value = healthyVal), lenderTreeBytes,
        pool, bAddr, preHeaderHeight = Some(h))
    }
    val schedCureB2 = Array[Long](0L, period, 0L, -((h + 5).toLong), thrX, escrowLive)
    pass("B2 cure (borrower top-up back onto the grid, live pool)", borrowerP) {
      R5.buildCure(ctx, b2Bond(schedCureB2, value = blownValue), Kit.MIN_BOX_VALUE, pool, borrowerP)
    }
    reject("B2 cure that does not restore health (+0 ERG)", borrowerP) {
      R5.buildCure(ctx, b2Bond(schedCureB2, value = blownValue), 0L, pool, borrowerP)
    }

    // ================================================================
    // B3 / O3 — instalment. Covenant OPTIONAL, so the natural order is
    // ERG-only and the natural bond carries an R8 pack of size 1. The
    // three verdictAt sites here are coupon / cure / accelerate.
    // ================================================================
    println("\n--- O3 instalment order ---")
    val term3 = 720
    val tmpl3 = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT)
    val val3  = TestLib.COLLATERAL + tmpl3(5)
    val maxTmpl3 = R5.instalmentTemplate(Int.MaxValue, 1000000000L, P4.INSTALLMENT)
    def ord3(tmpl: Array[Long] = tmpl3, toks: Seq[ErgoToken] = Nil,
             value: Long = val3, term: Int = term3): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl, value, tokens = toks, term = term)
    val o3 = ord3()

    pass("O3 cancel, no ctx vars", borrowerP) {
      R5.buildCancel(ctx, o3, bAddr, feeIn)
    }
    pass("O3 cancel with a WRONG-TYPED ctx var 0 (Long)", borrowerP) {
      R5.buildCancel(ctx, o3.withContextVars(varLong), bAddr, feeIn)
    }
    pass("O3 cancel with the honest var-0 shape attached (control)", borrowerP) {
      R5.buildCancel(ctx, o3.withContextVars(varLender), bAddr, feeIn)
    }
    pass("O3 cancel of a tmpl(1)==0 order (division-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        ord3(tmpl = Array[Long](P4.INSTALLMENT, 0L, 3L, 0L, 0L, tmpl3(5))), bAddr, feeIn)
    }
    pass("O3 cancel of a short-R9 order (index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord3(tmpl = Array[Long](P4.INSTALLMENT, 360L)), bAddr, feeIn)
    }
    // NEW in rev 5: tmpl(0) == 0 is a bullet template posted at the
    // instalment address. schedCommonOk's tmpl(0) > 0 discriminator makes
    // it permanently unmatchable — it must stay cancellable.
    pass("O3 cancel of a tmpl(0)==0 order (unmatchable must stay cancellable)", borrowerP) {
      R5.buildCancel(ctx,
        ord3(tmpl = Array[Long](0L, 360L, 3L, 0L, 0L, tmpl3(5))), bAddr, feeIn)
    }
    // The LOAD-BEARING coupling conjunct's other side: installment > 0
    // with paymentsRemaining == 0. Also unmatchable, also cancellable.
    pass("O3 cancel of a zero-payment order (coupling conjunct)", borrowerP) {
      R5.buildCancel(ctx,
        ord3(tmpl = Array[Long](P4.INSTALLMENT, 360L, 0L, 0L, 0L, tmpl3(5))), bAddr, feeIn)
    }
    // NEW: maturityOk now reads tmpl(1), so a size-3 template is a second
    // index-hoist probe against a DIFFERENT read site — and it is the
    // shape the matchOk reordering exists for.
    pass("O3 cancel of a size-3-tmpl order (maturityOk index-hoist probe)", borrowerP) {
      R5.buildCancel(ctx,
        ord3(tmpl = Array[Long](P4.INSTALLMENT, 4L, 2L), term = 5), bAddr, feeIn)
    }
    // NEW: Long parity. Unmatchable (the window cannot be satisfied) but
    // cancellable — pre-fix it was neither, because HEIGHT + term threw.
    pass("O3 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
      R5.buildCancel(ctx, ord3(tmpl = maxTmpl3, term = Int.MaxValue), bAddr, feeIn)
    }
    // This family is ERG-only by product default — a covenant-off
    // instalment order carries no collateral token — so it doubles as the
    // O3 SELF.tokens(0) hoist probe.
    noHoist("O3 cancel, ERG-only (also the O3 SELF.tokens(0) hoist probe)",
      "O3 cancel, no ctx vars",
      "O3 cancel with a WRONG-TYPED ctx var 0 (Long)",
      "O3 cancel with the honest var-0 shape attached (control)",
      "O3 cancel of a tmpl(1)==0 order (division-hoist probe)",
      "O3 cancel of a short-R9 order (index-hoist probe)",
      "O3 cancel of a tmpl(0)==0 order (unmatchable must stay cancellable)",
      "O3 cancel of a zero-payment order (coupling conjunct)",
      "O3 cancel of a size-3-tmpl order (maturityOk index-hoist probe)",
      "O3 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)")

    val tmpl3cov = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT, thresholdBps = 15000L)
    val val3cov  = TestLib.COLLATERAL + tmpl3cov(5)
    def ord3cov(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3cov, val3cov,
        tokens = Seq(rsn), term = term3)
    pass("O3 cancel of a covenant-ON (token-carrying) order", borrowerP) {
      R5.buildCancel(ctx, ord3cov(), bAddr, feeIn)
    }
    pass("O3 cancel BATCHED with a match", borrowerP) {
      val ordA = ord3()
      val ordB = ord3(value = val3 + 1000000L)
      val m    = R5.buildPackedMatch(ctx, B3, ordA, lenderTreeBytes, term3, borrowerP)
      batched(m, R5.packedOrderWithMatchVars(ordA, lenderTreeBytes), ordB, matchFunds)
    }
    pass("O3 card-less match, covenant OFF, NO data input (R8 pack size 1)", lenderP) {
      R5.buildPackedMatch(ctx, B3, o3, lenderTreeBytes, term3, lenderP)
    }
    pass("O3 card-less match, covenant ON (R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B3, ord3cov(), lenderTreeBytes, term3, lenderP)
    }
    reject("O3 match of a zero-payment order (coupling conjunct)", lenderP) {
      R5.buildPackedMatch(ctx, B3,
        ord3(tmpl = Array[Long](P4.INSTALLMENT, 360L, 0L, 0L, 0L, tmpl3(5))),
        lenderTreeBytes, term3, lenderP)
    }
    reject("O3 match of a tmpl(0)==0 order (bullet template at the instalment address)", lenderP) {
      R5.buildPackedMatch(ctx, B3,
        ord3(tmpl = Array[Long](0L, 360L, 3L, 0L, 0L, tmpl3(5))),
        lenderTreeBytes, term3, lenderP)
    }
    // Same floor, same boundary, on the instalment order: term 5 /
    // period 4 is legal here too (K == 1, payments == 2). h+1 rejects,
    // h+2 signs.
    val shortTmpl3 = R5.instalmentTemplate(5, 4L, P4.INSTALLMENT)
    def shortOrd3(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, shortTmpl3,
        TestLib.COLLATERAL + shortTmpl3(5), term = 5)
    reject("O3 match stamping maturity == HEIGHT + 1 (born liquidatable)", lenderP) {
      R5.buildPackedMatch(ctx, B3, shortOrd3(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    pass("O3 match stamping maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B3, shortOrd3(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // The ANCHOR rule with the +1 floor SLACK — see the O2 pair for the
    // arithmetic. On THIS product the anchor is what buys a coupon window
    // at all: the bond's couponOk needs HEIGHT >= nextCheck &&
    // HEIGHT < maturity, so a checkpoint at the birth block collapses the
    // window to nothing, repayOk's sched(2) <= 1 becomes unreachable, and
    // liquidation is the only exit left. That was the money path.
    val anchorTmpl3 = R5.instalmentTemplate(9, 4L, P4.INSTALLMENT)
    def anchorOrd3(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, anchorTmpl3,
        TestLib.COLLATERAL + anchorTmpl3(5), term = 9)
    reject("O3 match with the first checkpoint AT the birth block (anchor rule, floor slack)",
      lenderP) {
      R5.buildPackedMatch(ctx, B3, anchorOrd3(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }
    pass("O3 match with the first checkpoint at HEIGHT + 1 (anchor pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B3, anchorOrd3(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }
    reject("O3 match of a term == Int.MaxValue order (was an ArithmeticException crash)",
      lenderP) {
      R5.buildPackedMatch(ctx, B3, ord3(tmpl = maxTmpl3, term = Int.MaxValue),
        lenderTreeBytes, Int.MaxValue, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    reject("O3 match of a size-3-tmpl order (schedCommonOk precedes maturityOk)", lenderP) {
      val bad = Array[Long](P4.INSTALLMENT, 4L, 2L)
      R5.buildPackedMatch(ctx, B3, ord3(tmpl = bad, term = 5), lenderTreeBytes, 5, lenderP,
        bondSchedOverride = Some(bad),
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }

    println("\n--- B3 instalment bond ---")
    def b3Bond(sched: Array[Long], value: Long, pack: Seq[Array[Byte]] = Seq(lenderHash),
               toks: Seq[ErgoToken] = Nil): InputBox =
      R5.fabPackedBond(ctx, B3, sched, pack, borrowerHash, value, repayment, maturity, toks)

    val schedCoupOff = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 0L, escrow)
    val coupBond     = b3Bond(schedCoupOff, 35000000L)
    pass("B3 covenantOff coupon with NO data input (eager-eval probe)", borrowerP) {
      P4.buildCoupon(ctx, coupBond,
        P4.honestCouponPlan(coupBond, lenderTreeBytes, healthyBranch = true), None, bAddr)
    }
    val schedCoupH = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 15000L, escrow)
    val coupBondH  = b3Bond(schedCoupH, bondValue, Seq(lenderHash, poolNftBytes), Seq(rsn))
    pass("B3 coupon covenant HEALTHY (live pool data input)", borrowerP) {
      P4.buildCoupon(ctx, coupBondH,
        P4.honestCouponPlan(coupBondH, lenderTreeBytes, healthyBranch = true), Some(pool), bAddr)
    }
    reject("B3 coupon HEALTHY writing the CURE pack", borrowerP) {
      P4.buildCoupon(ctx, coupBondH,
        P4.honestCouponPlan(coupBondH, lenderTreeBytes, healthyBranch = false), Some(pool), bAddr)
    }
    val schedCoupU = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 20000L, escrow)
    val coupBondU  = b3Bond(schedCoupU, bondValue, Seq(lenderHash, poolNftBytes), Seq(rsn))
    pass("B3 coupon covenant UNHEALTHY -> cure (live pool data input)", borrowerP) {
      P4.buildCoupon(ctx, coupBondU,
        P4.honestCouponPlan(coupBondU, lenderTreeBytes, healthyBranch = false), Some(pool), bAddr)
    }
    reject("B3 coupon UNHEALTHY writing the ADVANCE pack", borrowerP) {
      P4.buildCoupon(ctx, coupBondU,
        P4.honestCouponPlan(coupBondU, lenderTreeBytes, healthyBranch = true), Some(pool), bAddr)
    }
    reject("B3 coupon on a COVENANT bond with the pool data input DROPPED (fails closed)", borrowerP) {
      P4.buildCoupon(ctx, coupBondH,
        P4.honestCouponPlan(coupBondH, lenderTreeBytes, healthyBranch = true), None, bAddr)
    }
    pass("B3 top-up with NO data input (eager-eval probe)", borrowerP) {
      P2.buildTopUp(ctx, coupBond, Kit.MIN_BOX_VALUE, borrowerP)
    }

    val schedMissed = Array[Long](P4.INSTALLMENT, period, 3L, (h - 15).toLong, 0L, escrow)
    val missedBond  = b3Bond(schedMissed, 35000000L)
    require((h - 15).toLong + GRACE_BLOCKS <= h.toLong, "fixture: deadline+grace must be past")
    pass("B3 missed-payment acceleration (NO data input)", keeperP) {
      P4.buildMissedAccel(ctx, missedBond,
        P4.honestMissedAccelPlan(missedBond, lenderTreeBytes), bAddr, preHeaderHeight = Some(h))
    }
    val schedFinal = Array[Long](P4.INSTALLMENT, period, 1L, (h + 100).toLong, 0L, 0L)
    val finalBond  = b3Bond(schedFinal, 20000000L)
    pass("B3 final repay at sched(2)==1 (NO data input)", borrowerP) {
      R5.buildRepay(ctx, finalBond, lenderTreeBytes, bAddr, borrowerP)
    }
    reject("B3 early repay at sched(2) > 1 (no early repayment)", borrowerP) {
      R5.buildRepay(ctx, coupBond, lenderTreeBytes, bAddr, borrowerP)
    }

    pass("B3 liquidate unhooked past maturity, no ctx vars", keeperP) {
      R5.buildLiquidate(ctx, finalBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, finalBond), preHeaderHeight = Some(maturity + 1))
    }
    pass("B3 liquidate unhooked with a WRONG-TYPED ctx var 0", keeperP) {
      R5.buildLiquidate(ctx, finalBond.withContextVars(varLong), lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, finalBond), preHeaderHeight = Some(maturity + 1))
    }
    noHoist("B3 liquidate unhooked (hook getVar stays under the pack-size guard)",
      "B3 liquidate unhooked past maturity, no ctx vars",
      "B3 liquidate unhooked with a WRONG-TYPED ctx var 0")
    reject("B3 liquidate BEFORE maturity", keeperP) {
      R5.buildLiquidate(ctx, finalBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, finalBond), preHeaderHeight = Some(maturity - 1))
    }
    val hookedB3 = b3Bond(schedFinal, 20000000L, Seq(lenderHash, poolNftBytes, hookHash))
    pass("B3 HOOKED liquidate (bond var-0 preimage, no data input)", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedB3, vaultBytes, bAddr,
        preHeaderHeight = Some(maturity + 1))
    }
    reject("B3 hooked liquidate paying the LENDER instead of the hook", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedB3, vaultBytes, bAddr,
        exitTreeOverride = Some(lenderTreeBytes), preHeaderHeight = Some(maturity + 1))
    }
    crash("B3 hooked liquidate with a WRONG-TYPED ctx var 0", keeperP) {
      R5.buildLiquidate(ctx, hookedB3.withContextVars(varLong), vaultBytes, bAddr,
        R5.carveOfBond(ctx, hookedB3), preHeaderHeight = Some(maturity + 1))
    }

    val schedBlownB3 = Array[Long](P4.INSTALLMENT, period, 3L, -((h - 3).toLong), thrX, escrowLive)
    pass("B3 covenant accelerate (deadline passed, unhealthy NOW)", keeperP) {
      R5.buildAccelerate(ctx, b3Bond(schedBlownB3, blownValue,
        Seq(lenderHash, poolNftBytes), Seq(rsn)), lenderTreeBytes, pool, bAddr,
        preHeaderHeight = Some(h))
    }
    reject("B3 accelerate at ergLeg == L (healthy, must NOT fire) [boundary twin]", keeperP) {
      R5.buildAccelerate(ctx, b3Bond(schedBlownB3, healthyVal,
        Seq(lenderHash, poolNftBytes), Seq(rsn)), lenderTreeBytes, pool, bAddr,
        preHeaderHeight = Some(h))
    }
    val schedCureB3 = Array[Long](P4.INSTALLMENT, period, 3L, -((h + 5).toLong), thrX, escrowLive)
    pass("B3 cure (borrower top-up back onto the grid, live pool)", borrowerP) {
      R5.buildCure(ctx, b3Bond(schedCureB3, blownValue, Seq(lenderHash, poolNftBytes), Seq(rsn)),
        Kit.MIN_BOX_VALUE, pool, borrowerP)
    }
    reject("B3 cure that does not restore health (+0 ERG)", borrowerP) {
      R5.buildCure(ctx, b3Bond(schedCureB3, blownValue, Seq(lenderHash, poolNftBytes), Seq(rsn)),
        0L, pool, borrowerP)
    }

    // ================================================================
    println("\n=== hoist verdicts ===")
    verdicts.foreach(v => println(s"  $v"))
    println(s"\nRev5Gate green: $positives sign-positive, $negatives sign-negative " +
      s"— ${positives + negatives} probes over six trees.")
    println("(local reduce + local sign only — nothing submitted, no contract byte touched)")
    ()
  }
}
