package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}

/** Workstream-1 smoke: every rev-5 builder in Rev5Lib built against
  * fabricated boxes at the six real addresses, then LOCALLY REDUCED and
  * LOCALLY SIGNED. Nothing is submitted — no rev-5 transaction has
  * touched the chain.
  *
  * Both steps are needed and they prove different things:
  *   - `reduce` proves the script did not CRASH (the eager-CSE probe
  *     class) and yields the JitCost. It does NOT prove the proposition
  *     is true: a shape that reduces to false still reduces cleanly.
  *   - `sign` proves the path is actually SATISFIED — a false or
  *     unprovable residual cannot be signed. Signing is local; it
  *     produces bytes that are never sent, and the fabricated inputs do
  *     not exist on chain, so the transactions are unsubmittable by
  *     construction.
  * Negatives (mustReject) must fail at signing for the right reason.
  *
  * This is NOT the rev-5 gate — workstream 2 ports Phase4Gate's permanent
  * probe set plus the new audit probes to all six trees and records
  * JitCost rows. Costs here are printed, never written to JITCOST.md, so
  * that ledger stays clean for the gate pass.
  *
  *   sbt "runMain bonds.Rev5Smoke"
  */
object Rev5Smoke {
  import Contracts._

  private var passed = 0

  /** Reduce (no crash, cost) AND sign (proposition satisfied). */
  private def probe(label: String, p: ErgoProver)(build: => UnsignedTransaction): Unit = {
    val tx   = build
    val cost = p.reduce(tx, 0).getCost
    p.sign(tx)
    passed += 1
    println(f"  OK  $label%-58s cost $cost%6d, signs")
  }

  /** A probe only proves something if its negation fails: the shape must
    * reduce to false or leave an unprovable residual — never sign.
    *
    * `allowInvalidType` widens the accepted failure to the interpreter's
    * InvalidType exception. That is NOT a clean rejection and is allowed
    * only where the shape itself is a type violation: reading a register
    * at the wrong type (box.RN[T] on a mismatched value) THROWS in sigma
    * 6.0.2 — it does not return None the way an absent register does.
    * The transaction is still rejected, but as a script CRASH. */
  private def mustReject(label: String, p: ErgoProver, allowInvalidType: Boolean = false)
                        (build: => UnsignedTransaction): Unit = {
    scala.util.Try(p.sign(build)) match {
      case scala.util.Success(_) => sys.error(s"[$label] EXPECTED rejection but the shape SIGNED")
      case scala.util.Failure(e) =>
        val msg = Kit.causeChain(e)
        val clean = msg.contains("educed to false") || msg.contains("ReducedToFalse") ||
                    msg.contains("Tree root should be real") || msg.contains("UnprovenSchnorr")
        val typed = allowInvalidType && msg.contains("InvalidType")
        if (!(clean || typed)) sys.error(s"[$label] failed for the WRONG reason: $msg")
        passed += 1
        println(s"  OK  $label (rejected: ${if (clean) "clean script rejection" else "InvalidType CRASH"})")
    }
  }

  private def check(label: String, cond: Boolean): Unit = {
    require(cond, s"[$label] FAILED")
    passed += 1
    println(s"  OK  $label")
  }

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val h = ctx.getHeight
    val borrowerP = TestLib.borrower(ctx)
    val lenderP   = TestLib.lender(ctx)
    val keeperP   = Kit.noSecretProver(ctx)   // signs only fully-true residuals
    val bAddr     = borrowerP.getEip3Addresses.get(0)
    val lAddr     = lenderP.getEip3Addresses.get(0)
    val lenderTreeBytes   = lAddr.toErgoContract.getErgoTree.bytes
    val borrowerTreeBytes = bAddr.toErgoContract.getErgoTree.bytes
    val lenderHash        = P4.h32(lenderTreeBytes)
    val borrowerHash      = P4.h32(borrowerTreeBytes)
    val poolNftBytes      = ErgoId.create(POOL_NFT).getBytes

    val B1 = R5.plain(ctx)
    val B2 = R5.covenant(ctx)
    val B3 = R5.instalment(ctx)
    println(s"height $h")
    R5.all(ctx).foreach { f =>
      println(f"  ${f.name}%-22s bond ${f.bondTree.bytes.length}%5dB  order ${f.orderTree.bytes.length}%5dB")
    }

    // Phase3Gate/Phase4Gate fixture, carried so costs stay comparable.
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

    // ================= B1 / O1: plain bullet =================
    println("\n--- B1/O1 plain bullet ---")
    val term1 = TestLib.TERM_LONG
    def ord1(term: Int = term1): InputBox = R5.fabPlainOrder(ctx, borrowerTreeBytes, term = term)

    check("O1 order classified by address",
      R5.classify(ctx, ord1()).exists { case (f, isBond) => f.name == R5.PLAIN && !isBond })
    probe("O1 cancel (borrower co-spend, no vars)", borrowerP) {
      R5.buildCancel(ctx, ord1(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    // R8 written as a PLAIN Coll[Byte]; lender preimage in var 0 of the
    // ORDER input; no data inputs anywhere on this product.
    probe("O1 match (plain R8 hash, var-0 reveal, no data input)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(), lenderTreeBytes, term1, lenderP)
    }
    mustReject("O1 match with the lender reveal DROPPED", lenderP) {
      R5.buildPlainMatch(ctx, ord1(), lenderTreeBytes, term1, lenderP, dropLenderVar = true)
    }
    // The copy-paste trap, and the exact mechanism: O1 reads bond R8 as
    // Coll[Byte], so a rev-4 pack there does not read as "absent" — the
    // interpreter throws InvalidType and the match dies as a crash. A B1
    // BOND carrying such an R8 would be unspendable on every path for the
    // same reason (its creator's loss).
    mustReject("O1 match writing a rev-4 Coll[Coll[Byte]] PACK into bond R8", lenderP,
      allowInvalidType = true) {
      R5.buildPlainMatch(ctx, ord1(), lenderTreeBytes, term1, lenderP,
        bondR8Override = Some(P4.packValue(Seq(lenderHash))))
    }
    // The born-liquidatable floor at its NEW boundary: m > HEIGHT + 1, so
    // the bond always gets the whole birth block with repayment open and
    // liquidation shut. One block wide — h+1 rejects, h+2 signs.
    mustReject("O1 match with maturity == HEIGHT + 1 (born liquidatable, new floor)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(3), lenderTreeBytes, 3, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    probe("O1 match with maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPlainMatch(ctx, ord1(3), lenderTreeBytes, 3, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // Why B1 needs its own ctx-var attacher: the rev-4 helper reads the
    // order's R8 pin pack, which a B1 order does not have.
    check("P4.orderWithMatchVars is unusable on a B1 order (no R8 to read)",
      scala.util.Try(P4.orderWithMatchVars(ord1(), lenderTreeBytes)).isFailure)

    val b1Bond = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity)
    check("B1 bond classified by address",
      R5.classify(ctx, b1Bond).exists { case (f, isBond) => f.name == R5.PLAIN && isBond })
    check("B1 R8 decodes as a PLAIN Coll[Byte] lender hash",
      java.util.Arrays.equals(R5.lenderHashOfBond(ctx, b1Bond), lenderHash))
    check("B1 carve-out resolves to the compiled constant (no R9 to read)",
      R5.carveOfBond(ctx, b1Bond) == LIQ_CARVEOUT)
    probe("B1 repay (borrower co-spend, ERG-only)", borrowerP) {
      R5.buildRepay(ctx, b1Bond, lenderTreeBytes, bAddr, borrowerP)
    }
    // A2: the bond box is an INPUT, so its own value funds the exit. The
    // funder is asked only for the SHORTFALL (floored at fee + one min
    // box), which is what rev 4 did — a fat bond against a smaller
    // repayment now selects almost nothing from the wallet instead of
    // demanding the full repayment a second time.
    val fatBond = R5.fabPlainBond(ctx, lenderHash, borrowerHash, 40000000L, repayment, maturity)
    probe("B1 repay funded by the bond's own value (A2 shortfall-only selection)", borrowerP) {
      R5.buildRepay(ctx, fatBond, lenderTreeBytes, bAddr, borrowerP)
    }
    val b1Tok = R5.fabPlainBond(ctx, lenderHash, borrowerHash, bondValue, repayment, maturity,
      tokens = Seq(rsn))
    probe("B1 repay with token collateral (collateralToBorrower)", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP)
    }
    // No explicit return box is NOT theft: appkit's change goes to the
    // funder, so a borrower-funded repay still lands the tokens on a
    // borrower-guarded output. Routing them elsewhere is the real test.
    probe("B1 repay with collateral riding appkit CHANGE to the borrower", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP, dropCollateralReturn = true)
    }
    mustReject("B1 repay routing the token collateral to the LENDER", borrowerP) {
      R5.buildRepay(ctx, b1Tok, lenderTreeBytes, bAddr, borrowerP, collateralTo = Some(lAddr))
    }
    probe("B1 liquidate past maturity (signatureless, no data input)", keeperP) {
      R5.buildLiquidate(ctx, b1Bond, lenderTreeBytes, bAddr, LIQ_CARVEOUT,
        preHeaderHeight = Some(maturity + 1))
    }
    mustReject("B1 liquidate BEFORE maturity", keeperP) {
      R5.buildLiquidate(ctx, b1Bond, lenderTreeBytes, bAddr, LIQ_CARVEOUT,
        preHeaderHeight = Some(maturity - 1))
    }

    // ================= B2 / O2: covenant bullet =================
    println("\n--- B2/O2 covenant bullet ---")
    // The hook preimage and its hash: used by the carded hook-pinned
    // match below (var 1 on the ORDER input) and by the hooked
    // liquidation further down (var 0 on the BOND input).
    val vaultBytes = TestLib.vaultTree().bytes
    val hookHash   = P4.h32(vaultBytes)
    val term2 = 720
    val tmpl2 = R5.covenantTemplate(term2, 360L, 15000L)
    val val2  = TestLib.COLLATERAL + tmpl2(5)
    def ord2(toks: Seq[ErgoToken] = Seq(rsn)): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2, tokens = toks, term = term2)

    check("O2 order classified by address",
      R5.classify(ctx, ord2()).exists { case (f, isBond) => f.name == R5.COVENANT && !isBond })
    probe("O2 cancel (token collateral recovered)", borrowerP) {
      R5.buildCancel(ctx, ord2(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    probe("O2 cancel of an ERG-only order (SELF.tokens(0) hoist probe)", borrowerP) {
      R5.buildCancel(ctx, ord2(Nil), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    probe("O2 card-less match (mandatory covenant, R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2(), lenderTreeBytes, term2, lenderP)
    }
    mustReject("O2 match with the lender reveal DROPPED", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2(), lenderTreeBytes, term2, lenderP, dropLenderVar = true)
    }
    mustReject("O2 match writing a size-1 (plain-product) bond R8 pack", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2(), lenderTreeBytes, term2, lenderP,
        bondR8Override = Some(Seq(lenderHash)))
    }

    // The born-liquidatable floor at its NEW boundary on O2. The shortest
    // legal covenant order is term 5 / period 4 (K == 1), entirely inside
    // MATURITY_TOL. One block wide: h+1 rejects, h+2 signs.
    val shortTmpl2 = R5.covenantTemplate(5, 4L, 15000L)
    def shortOrd2(): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, shortTmpl2,
        TestLib.COLLATERAL + shortTmpl2(5), tokens = Seq(rsn), term = 5)
    mustReject("O2 match with maturity == HEIGHT + 1 (born liquidatable, new floor)", lenderP) {
      R5.buildPackedMatch(ctx, B2, shortOrd2(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    probe("O2 match with maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B2, shortOrd2(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // The ANCHOR rule, isolated from the +1 floor. term 9 / period 4
    // (K == 2): the tolerance window is [h+4, h+9], so m == h+5 clears
    // m > HEIGHT + 1 comfortably, yet the first checkpoint it implies —
    // (m - term) + period == h — is already at the birth block. Only the
    // anchor conjunct rejects it. m == h+6 puts the checkpoint at h+1.
    val anchorTmpl2 = R5.covenantTemplate(9, 4L, 15000L)
    def anchorOrd2(): InputBox =
      R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, anchorTmpl2,
        TestLib.COLLATERAL + anchorTmpl2(5), tokens = Seq(rsn), term = 9)
    mustReject("O2 match with first checkpoint AT the birth block (anchor rule, floor is slack)",
      lenderP) {
      R5.buildPackedMatch(ctx, B2, anchorOrd2(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }
    probe("O2 match with first checkpoint at HEIGHT + 1 (anchor pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B2, anchorOrd2(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }

    // ---- carded O2 matches (rev-5 audit A3/A4/A6 coverage) ----
    // A carded match carries the card as dataInputs(0) and writes its
    // RESOLVED numerics into bond R9 indices 6-9, making the schedule
    // size 10. Card A is the "compiled defaults, every field explicit"
    // card, so the suffix it resolves to is the compiled constant set.
    val cardNftA   = ErgoId.create("aa" * 32)
    val cardBoxA   = P4.fabCard(ctx, cardNftA, P4.CARD_A_R7, P4.explicitCardR8)
    val ord2Carded = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2,
      cardPin = cardNftA.getBytes, tokens = Seq(rsn), term = term2)
    probe("O2 CARDED match (card A as data input, size-10 bond R9)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2Carded, lenderTreeBytes, term2, lenderP,
        card = Some(cardBoxA), preHeaderHeight = Some(h))
    }
    check("O2 carded match writes the resolved suffix [bounty, grace, carve, haircut]", {
      val tx = R5.buildPackedMatch(ctx, B2, ord2Carded, lenderTreeBytes, term2, lenderP,
        card = Some(cardBoxA), preHeaderHeight = Some(h))
      val r9 = tx.getOutputs.get(0).getRegisters.get(5).getValue
        .asInstanceOf[sigma.Coll[Long]].toArray
      r9.length == 10 && r9.drop(6).sameElements(
        Array[Long](CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP))
    })
    // A3: carve-out and haircut are OUTER bounds — a card may tighten
    // them, never loosen them. The builder now clamps to the compiled
    // constants, so the over-carve card no longer produces this shape;
    // written by hand, the contract refuses it (the order recomputes the
    // suffix from its own clamped resolution and compares for equality).
    // preHeaderHeight pins the stamp so the hand-built base is exact.
    val cardedBase = Array[Long](tmpl2(0), tmpl2(1), tmpl2(2),
      h.toLong + tmpl2(1), tmpl2(4), tmpl2(5))
    mustReject("O2 carded match writing an UNCLAMPED carve (4e6 > LIQ_CARVEOUT) into bond R9",
      lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2Carded, lenderTreeBytes, term2, lenderP,
        card = Some(cardBoxA), preHeaderHeight = Some(h),
        bondSchedOverride = Some(cardedBase ++
          Array[Long](CRANK_BOUNTY, GRACE_BLOCKS, 4000000L, HAIRCUT_KEEP)))
    }
    // Hook-pinned carded match: the order's R8 pack is [cardPin, hookHash]
    // and the card's own R8 blesses that hook. Lender preimage on var 0,
    // HOOK preimage on var 1 — both on the ORDER input.
    val hookCardNft = ErgoId.create("bb" * 32)
    val hookCardR8  = P4.cardR8WithHooks(poolNftBytes,
      ErgoId.create(COLLATERAL_TOKEN_ID).getBytes, Seq(hookHash))
    val hookCardBox = P4.fabCard(ctx, hookCardNft, P4.CARD_A_R7, hookCardR8)
    val ord2Hooked  = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, tmpl2, val2,
      cardPin = hookCardNft.getBytes, hookHash = Some(hookHash), tokens = Seq(rsn), term = term2)
    probe("O2 HOOK-PINNED carded match (var-1 hook reveal, card blesses the hook)", lenderP) {
      R5.buildPackedMatch(ctx, B2, ord2Hooked, lenderTreeBytes, term2, lenderP,
        card = Some(hookCardBox), hookScriptBytes = Some(vaultBytes))
    }
    // A4: the escrow an order carries must equal the PINNED CARD's
    // resolved bounty times K — the order contract checks exactly that
    // (CovenantBulletOrder.es:199). Pin a card with a fatter bounty than
    // the escrow was sized for and the order can never match; the
    // builder-level require now says so before the collateral moves.
    val fatBountyCard = P4.CARD_A_R7.clone()
    fatBountyCard(P4.C7.BOUNTY) = CRANK_BOUNTY * 2
    check("A4 escrow/card cross-check FIRES when the pinned card resizes the bounty",
      scala.util.Try(R5.escrowCrossCheck(tmpl2, term2, Some(fatBountyCard))).isFailure)
    check("A4 escrow/card cross-check passes for the matching card (control)",
      scala.util.Try(R5.escrowCrossCheck(tmpl2, term2, Some(P4.CARD_A_R7))).isSuccess)

    def b2Bond(sched: Array[Long], pack: Seq[Array[Byte]] = Seq(lenderHash, poolNftBytes),
               value: Long = bondValue): InputBox =
      R5.fabPackedBond(ctx, B2, sched, pack, borrowerHash, value, repayment, maturity, Seq(rsn))

    val schedHealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow)
    val crankBond    = b2Bond(schedHealthy)
    check("B2 bond classified by address",
      R5.classify(ctx, crankBond).exists { case (f, isBond) => f.name == R5.COVENANT && isBond })
    probe("B2 crank HEALTHY (live pool data input)", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankAdvancePack(schedHealthy), Some(pool), bAddr)
    }
    mustReject("B2 crank HEALTHY writing the CURE pack", keeperP) {
      R5.buildCrank(ctx, crankBond, R5.crankCurePack(schedHealthy), Some(pool), bAddr)
    }
    val schedUnhealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 20000L, escrow)
    probe("B2 crank UNHEALTHY -> cure (live pool data input)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedUnhealthy), R5.crankCurePack(schedUnhealthy), Some(pool), bAddr)
    }
    val schedOffB2 = Array[Long](0L, period, 0L, (h - 5).toLong, 0L, escrow)
    probe("B2 covenantOff crank with NO data input (eager-eval probe)", keeperP) {
      R5.buildCrank(ctx, b2Bond(schedOffB2, Seq(lenderHash)),
        R5.crankAdvancePack(schedOffB2), None, bAddr)
    }
    probe("B2 repay with NO data input", borrowerP) {
      R5.buildRepay(ctx, crankBond, lenderTreeBytes, bAddr, borrowerP)
    }
    probe("B2 top-up with NO data input", borrowerP) {
      P2.buildTopUp(ctx, crankBond, Kit.MIN_BOX_VALUE, borrowerP)
    }
    probe("B2 liquidate past maturity (unhooked, no data input)", keeperP) {
      R5.buildLiquidate(ctx, crankBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, crankBond), preHeaderHeight = Some(maturity + 1))
    }
    // Hooked liquidation: preimage on ctx-ext var 0 of the BOND input —
    // the other end of the var-0 collision (the order's var 0 is the
    // lender script).
    val hookedBond = b2Bond(schedHealthy, Seq(lenderHash, poolNftBytes, hookHash))
    probe("B2 hooked liquidate (bond var-0 preimage, no data input)", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedBond, vaultBytes, bAddr,
        preHeaderHeight = Some(maturity + 1))
    }
    mustReject("B2 hooked liquidate paying the LENDER instead of the hook", keeperP) {
      P4.buildHookedLiquidation(ctx, hookedBond, vaultBytes, bAddr,
        exitTreeOverride = Some(lenderTreeBytes), preHeaderHeight = Some(maturity + 1))
    }
    // Accelerate and cure both hinge on the verdict, so the fixture is
    // pinned to the live healthy boundary L rather than to a guessed
    // value: L - 1 is unhealthy by construction, whatever the reserves
    // are doing. (A bond value picked by hand can silently drift onto the
    // healthy side and turn the probe into a false-reducing no-op — a
    // reduce alone would never notice, only the sign does.)
    val thrX = 20000L
    val lX   = P3.ergLegForHealthy(pool, amtRSN, repayment, thrX)
    require(lX > 1L, "fixture: healthy boundary must be positive")
    require(P3.healthy(pool, lX, amtRSN, repayment, thrX) &&
            !P3.healthy(pool, lX - 1L, amtRSN, repayment, thrX),
      "fixture: L must be the exact healthy floor")
    val escrowLive = escrow - CRANK_BOUNTY
    val blownValue = escrowLive + lX - 1L            // ergLeg == L - 1 -> unhealthy
    val schedBlown = Array[Long](0L, period, 0L, -((h - 3).toLong), thrX, escrowLive)
    probe("B2 covenant accelerate (deadline passed, unhealthy now)", keeperP) {
      R5.buildAccelerate(ctx, b2Bond(schedBlown, value = blownValue), lenderTreeBytes, pool, bAddr,
        preHeaderHeight = Some(h))
    }
    mustReject("B2 accelerate at ergLeg == L (healthy, must NOT fire)", keeperP) {
      R5.buildAccelerate(ctx, b2Bond(schedBlown, value = blownValue + 1L), lenderTreeBytes,
        pool, bAddr, preHeaderHeight = Some(h))
    }
    val deadlineF = (h + 5).toLong
    val schedCure = Array[Long](0L, period, 0L, -deadlineF, thrX, escrowLive)
    val addValue  = Kit.MIN_BOX_VALUE                // L - 1 + 1e6 clears L
    probe("B2 cure (borrower top-up back onto the grid, live pool)", borrowerP) {
      R5.buildCure(ctx, b2Bond(schedCure, value = blownValue), addValue, pool, borrowerP)
    }
    mustReject("B2 cure that does not restore health (+0 ERG)", borrowerP) {
      R5.buildCure(ctx, b2Bond(schedCure, value = blownValue), 0L, pool, borrowerP)
    }

    // ================= B3 / O3: instalment =================
    println("\n--- B3/O3 instalment ---")
    val term3 = 720
    val tmpl3 = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT)
    val val3  = TestLib.COLLATERAL + tmpl3(5)
    def ord3(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3, val3, term = term3)

    check("O3 order classified by address",
      R5.classify(ctx, ord3()).exists { case (f, isBond) => f.name == R5.INSTALMENT && !isBond })
    probe("O3 cancel (covenant-off instalment order)", borrowerP) {
      R5.buildCancel(ctx, ord3(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }
    probe("O3 card-less match, covenant OFF (R8 pack size 1)", lenderP) {
      R5.buildPackedMatch(ctx, B3, ord3(), lenderTreeBytes, term3, lenderP)
    }
    // Same floor as O2, at the same NEW boundary: term 5 / period 4 is a
    // legal instalment order (K == 1, payments == 2). h+1 rejects,
    // h+2 signs.
    val shortTmpl3 = R5.instalmentTemplate(5, 4L, P4.INSTALLMENT)
    def shortOrd3(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, shortTmpl3,
        TestLib.COLLATERAL + shortTmpl3(5), term = 5)
    mustReject("O3 match with maturity == HEIGHT + 1 (born liquidatable, new floor)", lenderP) {
      R5.buildPackedMatch(ctx, B3, shortOrd3(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    probe("O3 match with maturity == HEIGHT + 2 (pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B3, shortOrd3(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
    }
    // The ANCHOR rule isolated from the +1 floor — see the O2 pair for the
    // arithmetic. On THIS product the anchor is what buys a coupon window
    // at all: with the checkpoint at the birth block, couponOk's
    // HEIGHT >= nextCheck && HEIGHT < maturity is empty, repayOk's
    // sched(2) <= 1 is never reachable, and liquidation is the only exit.
    val anchorTmpl3 = R5.instalmentTemplate(9, 4L, P4.INSTALLMENT)
    def anchorOrd3(): InputBox =
      R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, anchorTmpl3,
        TestLib.COLLATERAL + anchorTmpl3(5), term = 9)
    mustReject("O3 match with first checkpoint AT the birth block (anchor rule, floor is slack)",
      lenderP) {
      R5.buildPackedMatch(ctx, B3, anchorOrd3(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
    }
    probe("O3 match with first checkpoint at HEIGHT + 1 (anchor pass twin)", lenderP) {
      R5.buildPackedMatch(ctx, B3, anchorOrd3(), lenderTreeBytes, 9, lenderP,
        maturityOverride = Some(h + 6), preHeaderHeight = Some(h))
    }
    // ---- THE CLOSED EXPLOIT ----
    // Pre-fix this exact shape SIGNED (Rev5Smoke run at height 1863915,
    // cost 16788): a term-5 / period-4 instalment order stamped at
    // m == HEIGHT + 1, whose bond had an empty coupon window, an
    // unreachable repay and a liquidation opening one block later.
    mustReject("O3 EXPLOIT (pre-fix this SIGNED): m == HEIGHT + 1 on term 5 / period 4", lenderP) {
      R5.buildPackedMatch(ctx, B3, shortOrd3(), lenderTreeBytes, 5, lenderP,
        maturityOverride = Some(h + 1), preHeaderHeight = Some(h))
    }
    probe("O3 EXPLOIT: the bond geometry itself is unchanged — such a bond, if it could " +
          "be born, still liquidates at HEIGHT + 1 (the ORDER is the only gate)", keeperP) {
      // The R9 the pre-fix match wrote: anchor == (m - term) + period == h.
      val bornSched = Array[Long](P4.INSTALLMENT, 4L, shortTmpl3(2), h.toLong, 0L, shortTmpl3(5))
      val bornBond  = R5.fabPackedBond(ctx, B3, bornSched, Seq(lenderHash), borrowerHash,
        TestLib.COLLATERAL + shortTmpl3(5), TestLib.REPAYMENT, h + 1)
      R5.buildLiquidate(ctx, bornBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, bornBond), preHeaderHeight = Some(h + 1))
    }
    val tmpl3cov = R5.instalmentTemplate(term3, 360L, P4.INSTALLMENT, thresholdBps = 15000L)
    val val3cov  = TestLib.COLLATERAL + tmpl3cov(5)
    probe("O3 card-less match, covenant ON (R8 pack size 2)", lenderP) {
      R5.buildPackedMatch(ctx, B3,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, tmpl3cov, val3cov,
          tokens = Seq(rsn), term = term3),
        lenderTreeBytes, term3, lenderP)
    }
    // The zero-payment order is the LOAD-BEARING coupling conjunct in O3:
    // installment > 0 with paymentsRemaining 0 must never match.
    mustReject("O3 match of a zero-payment order (coupling conjunct)", lenderP) {
      val bad = Array[Long](P4.INSTALLMENT, 360L, 0L, 0L, 0L, tmpl3(5))
      R5.buildPackedMatch(ctx, B3,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, bad, val3, term = term3),
        lenderTreeBytes, term3, lenderP)
    }
    probe("O3 cancel of a zero-payment order (unmatchable stays cancellable)", borrowerP) {
      val bad = Array[Long](P4.INSTALLMENT, 360L, 0L, 0L, 0L, tmpl3(5))
      R5.buildCancel(ctx,
        R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, bad, val3, term = term3),
        bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
    }

    def b3Bond(sched: Array[Long], value: Long, pack: Seq[Array[Byte]] = Seq(lenderHash),
               toks: Seq[ErgoToken] = Nil): InputBox =
      R5.fabPackedBond(ctx, B3, sched, pack, borrowerHash, value, repayment, maturity, toks)

    val schedCoupOff = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 0L, escrow)
    val coupBond     = b3Bond(schedCoupOff, 35000000L)
    check("B3 bond classified by address",
      R5.classify(ctx, coupBond).exists { case (f, isBond) => f.name == R5.INSTALMENT && isBond })
    probe("B3 covenantOff coupon with NO data input (eager-eval probe)", borrowerP) {
      P4.buildCoupon(ctx, coupBond,
        P4.honestCouponPlan(coupBond, lenderTreeBytes, healthyBranch = true), None, bAddr)
    }
    val schedCoupCov = Array[Long](P4.INSTALLMENT, period, 4L, (h - 5).toLong, 15000L, escrow)
    val coupCovBond  = b3Bond(schedCoupCov, bondValue, Seq(lenderHash, poolNftBytes), Seq(rsn))
    probe("B3 coupon covenant HEALTHY (live pool data input)", borrowerP) {
      P4.buildCoupon(ctx, coupCovBond,
        P4.honestCouponPlan(coupCovBond, lenderTreeBytes, healthyBranch = true), Some(pool), bAddr)
    }
    val schedMissed = Array[Long](P4.INSTALLMENT, period, 3L, (h - 15).toLong, 0L, escrow)
    val missedBond  = b3Bond(schedMissed, 35000000L)
    require((h - 15).toLong + GRACE_BLOCKS <= h.toLong, "fixture: deadline+grace must be past")
    probe("B3 missed-payment acceleration (no data input)", keeperP) {
      P4.buildMissedAccel(ctx, missedBond,
        P4.honestMissedAccelPlan(missedBond, lenderTreeBytes), bAddr, preHeaderHeight = Some(h))
    }
    val schedFinal = Array[Long](P4.INSTALLMENT, period, 1L, (h + 100).toLong, 0L, 0L)
    val finalBond  = b3Bond(schedFinal, 20000000L)
    probe("B3 final repay at sched(2)==1 (no data input)", borrowerP) {
      R5.buildRepay(ctx, finalBond, lenderTreeBytes, bAddr, borrowerP)
    }
    mustReject("B3 early repay at sched(2) > 1 (no early repayment)", borrowerP) {
      R5.buildRepay(ctx, coupBond, lenderTreeBytes, bAddr, borrowerP)
    }
    probe("B3 liquidate past maturity (no data input)", keeperP) {
      R5.buildLiquidate(ctx, finalBond, lenderTreeBytes, bAddr,
        R5.carveOfBond(ctx, finalBond), preHeaderHeight = Some(maturity + 1))
    }

    // ---- LONG PARITY: term == Int.MaxValue on each order ----
    // Pre-fix, O1 (already in Long) rejected cleanly while O2/O3 threw
    // java.lang.ArithmeticException: integer overflow on HEIGHT + term —
    // a script CRASH, not a rejection. All three now do the maturity
    // arithmetic in Long, so a near-MaxValue R7 leaves the order
    // unmatchable BUT CANCELLABLE on every product.
    println("\n--- Long parity: term == Int.MaxValue ---")
    locally {
      val maxT      = Int.MaxValue
      val bigPeriod = 1000000000L
      val maxOrd1   = R5.fabPlainOrder(ctx, borrowerTreeBytes, term = maxT)
      val t2        = R5.covenantTemplate(maxT, bigPeriod, 15000L)
      val maxOrd2   = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, t2,
        TestLib.COLLATERAL + t2(5), tokens = Seq(rsn), term = maxT)
      val t3        = R5.instalmentTemplate(maxT, bigPeriod, P4.INSTALLMENT)
      val maxOrd3   = R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, t3,
        TestLib.COLLATERAL + t3(5), term = maxT)

      mustReject("O1 match of a term == Int.MaxValue order (clean reject, no overflow)", lenderP) {
        R5.buildPlainMatch(ctx, maxOrd1, lenderTreeBytes, maxT, lenderP,
          maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
      }
      mustReject("O2 match of a term == Int.MaxValue order (was an ArithmeticException CRASH)",
        lenderP) {
        R5.buildPackedMatch(ctx, B2, maxOrd2, lenderTreeBytes, maxT, lenderP,
          maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
      }
      mustReject("O3 match of a term == Int.MaxValue order (was an ArithmeticException CRASH)",
        lenderP) {
        R5.buildPackedMatch(ctx, B3, maxOrd3, lenderTreeBytes, maxT, lenderP,
          maturityOverride = Some(h + 2), preHeaderHeight = Some(h))
      }
      probe("O1 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
        R5.buildCancel(ctx, maxOrd1, bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
      }
      probe("O2 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
        R5.buildCancel(ctx, maxOrd2, bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
      }
      probe("O3 cancel of a term == Int.MaxValue order (unmatchable stays cancellable)", borrowerP) {
        R5.buildCancel(ctx, maxOrd3, bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
      }
    }

    // ---- MALFORMED-TMPL ORDERS: the matchOk reordering hazard ----
    // maturityOk now reads tmpl(1), and it is schedCommonOk that enforces
    // tmpl.size == 6, so schedCommonOk was moved AHEAD of maturityOk in
    // both packed matchOk chains. A size-3 template is the shape that
    // proves it: the match must be a CLEAN rejection (schedCommonOk sees
    // the wrong size and stops) rather than an index crash, and the
    // borrower must still be able to cancel and recover the collateral.
    println("\n--- malformed-tmpl (size-3 R9) orders ---")
    locally {
      val badTmpl2 = Array[Long](0L, 4L, 0L)
      val badTmpl3 = Array[Long](P4.INSTALLMENT, 4L, 2L)
      def badOrd2(): InputBox = R5.fabPackedOrder(ctx, B2, borrowerTreeBytes, badTmpl2,
        TestLib.COLLATERAL, tokens = Seq(rsn), term = 5)
      def badOrd3(): InputBox = R5.fabPackedOrder(ctx, B3, borrowerTreeBytes, badTmpl3,
        TestLib.COLLATERAL, term = 5)
      mustReject("O2 match of a size-3-tmpl order (clean reject, NOT an index crash)", lenderP) {
        R5.buildPackedMatch(ctx, B2, badOrd2(), lenderTreeBytes, 5, lenderP,
          bondSchedOverride = Some(badTmpl2),
          maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
      }
      mustReject("O3 match of a size-3-tmpl order (clean reject, NOT an index crash)", lenderP) {
        R5.buildPackedMatch(ctx, B3, badOrd3(), lenderTreeBytes, 5, lenderP,
          bondSchedOverride = Some(badTmpl3),
          maturityOverride = Some(h + 5), preHeaderHeight = Some(h))
      }
      probe("O2 cancel of a size-3-tmpl order (collateral recoverable)", borrowerP) {
        R5.buildCancel(ctx, badOrd2(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
      }
      probe("O3 cancel of a size-3-tmpl order (collateral recoverable)", borrowerP) {
        R5.buildCancel(ctx, badOrd3(), bAddr, Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE))
      }
    }

    println(s"\nrev-5 builder smoke complete: $passed checks/probes, all green.")
    println("(local reduce + local sign only — nothing submitted)")
    ()
  }
}
