{
  // =====================================================================
  // Conforming Bond — rev 3 (script borrower · card layout · instalments)
  //
  // Phase 1 core (repay/liquidate), Phase 2 successor machinery
  // (crank/top-up) and Phase 3 covenant machinery (verdict crank, cure,
  // acceleration) keep their shapes. Rev 3 adds, in ONE revision (the
  // bond is never revised again):
  //   - script borrower: R5 is borrower ErgoTree BYTES; borrower arms
  //     authorize by CO-SPEND (an input guarded by those bytes), so
  //     contracts and DAOs can post bonds. The whole contract is a
  //     single sigmaProp of booleans.
  //   - card layout: R8 is a Coll[Coll[Byte]] suffix pack, R9 an
  //     extended Long pack carrying card numerics; compiled constants
  //     remain as the suffix-absent fallback defaults (the compiled
  //     constant IS the type-0 default card).
  //   - instalments (Phase 4): the coupon IS the crank — a signatureless
  //     coupon path advances the schedule only by paying the installment
  //     to the lender script in the same tx; a missed coupon is DERIVED
  //     state (no register write) opening a signatureless acceleration
  //     with no health test; repay becomes the final payment behind
  //     sched(2) <= 1.
  //   - liquidation hook stub: R8(2) commits a blake2b256 hash; the
  //     liquidate arm's destination rebinds to the committed script,
  //     supplied at spend via context-extension var 0 (zero data-input
  //     surface).
  //   - attestation stub: verdict dispatch on a type that no conforming
  //     bond can carry yet (order enforces type 0). Enabling a real
  //     attester later is an order-side revision only.
  //
  // Registers (layout locked, REV3-LAYOUT.md; suffix packs opt-in by
  // size, dense, append-only):
  //   R4: Coll[Byte]       originating order box id (== loan token id)
  //   R5: Coll[Byte]       borrower ErgoTree bytes
  //   R6: Long             repayment amount, nanoERG
  //   R7: Int              maturity height
  //   R8: Coll[Coll[Byte]] [lenderScript]                    plain
  //                        [lenderScript, poolNFT]           covenant
  //                        [lenderScript, poolNFT, liqHookHash]
  //                                            covenant + custom hook
  //                        (index 3, attesterScriptHash, exists only on
  //                        fabricated nonzero-type bonds — see below)
  //   R9: Coll[Long]  size 6  [installment, periodBlocks,
  //         paymentsRemaining, nextCheckHeight, thresholdBps,
  //         escrowBalance] — card-less shape, compiled defaults apply
  //                   size 10 [... + crankBounty, graceBlocks,
  //         liqCarveout, haircutKeep] — carded shape, values resolved
  //         at match; whole-pack successor equality freezes the suffix
  //                   size 11 [... + attestationType] — fabricated
  //         nonzero-type bonds only; the order's type-0 gate makes this
  //         shape unmatchable in rev 3 (audit note: the generic verdict
  //         branch below is intentionally unreachable by any conforming
  //         bond until an order-side revision enables it)
  //       nextCheckHeight > 0: normal; < 0: IN CURE, |value| = deadline.
  //       paymentsRemaining counts ALL payments = K interior coupons +
  //       1 final bullet (K+1 at origination); bullets carry 0.
  //   tokens(0): (loanTokenId, 1), minted at match, id == R4 value
  //   tokens(1): collateral token — covenant bonds only
  //
  // Paths (disjointness: crank/coupon split by sched(0); the two
  // accelerations split by sign of sched(3); coupon vs missed-accel
  // overlap past the deadline BY DESIGN — late-coupon race, first
  // confirmation wins; liquidate owns HEIGHT >= maturity; borrower arms
  // race everything borrower-side as always):
  //   Repay        borrower co-spend, any height, sched(2) <= 1: for an
  //                installment bond the final payment IS the repay exit,
  //                reachable only after every interior coupon. Bullets
  //                (sched(2) == 0) pass unchanged.
  //   Liquidate    signatureless, HEIGHT >= maturity. When the hook is
  //                committed (R8 size 3) the exit destination rebinds to
  //                the hook script, supplied via ctx-ext var 0 and
  //                verified against the hash; otherwise the R8(0) lender
  //                script, unchanged from rev 2.
  //   Crank        signatureless, INSTALLMENT-FREE bonds only
  //                (sched(0) == 0). As Phase 3, verdict-branched for
  //                covenant bonds. The verdict argument keeps the
  //                -sched(0) term: behind the sched(0) == 0 gate it is
  //                provably zero — the gate now enforces what Phase 3
  //                only argued — and it keeps this call site
  //                structurally distinct (decision 5).
  //   Coupon       signatureless, installment bonds at a checkpoint:
  //                successor advances the grid (or enters cure encoding
  //                on an unhealthy covenant verdict — coupon accepted,
  //                health owed) and pays OUTPUTS(1) >= sched(0) to the
  //                lender script with the R4 = SELF.id receipt (no loan
  //                token — it stays on the successor). Consumes the
  //                grid point's bounty (K-bound holds: one bounty per
  //                grid point, ever). No grace ceiling: a late coupon
  //                races missed-payment acceleration by design.
  //   Cure         borrower co-spend, sched(3) < 0, no deadline (limbo
  //                rule). Unchanged from Phase 3; structurally cannot
  //                clear an overdue coupon (cure needs sched(3) < 0, a
  //                missed coupon keeps sched(3) > 0).
  //   Accelerate   signatureless covenant default: blown grace, still
  //                unhealthy NOW. Verdict argument is exact (trap
  //                removed, decision 5).
  //   MissedAccel  signatureless payment default: installment bond,
  //                sched(3) > 0, sched(2) > 1, HEIGHT >= checkpoint +
  //                grace, before maturity. Liquidation shape, NO health
  //                test (payment default != collateral default), no
  //                verdict call (no fifth site). "Overdue" is derived
  //                from SELF + HEIGHT — no stored flag (MED-1).
  //   Top-up       borrower co-spend, outside cure only. Unchanged.
  //
  // borrowerAuth = an input guarded by the borrower bytes exists in the
  // tx. It reads only INPUTS (always present, never fallible), so eager
  // CSE hoisting is safe — pinned by a permanent gate probe, not by
  // this argument. A trivially-satisfiable borrower script opens the
  // borrower arms to anyone: the borrower's own choice, same class as
  // the funder self-selecting R8(0) == the bond tree (documented, not
  // prevented).
  //
  // Height rule: windows >= open / < close, never equality. Type rule:
  // every branch Boolean; ONE sigmaProp at top level. Fallible reads on
  // foreign boxes are guarded so rejection reduces cleanly. Malformed
  // SELF fabrications (R8 size 0, missing registers) brick eagerly —
  // forger's loss, never a victim (established posture).
  //
  // TOOLCHAIN RULE (rev-1 mainnet crash, LOW-P3-B1): every read that
  // touches CONTEXT.dataInputs lives inside the SINGLE verdictAt lambda;
  // each application site below uses a structurally distinct argument so
  // no shared eager node can resurrect the crash. The attestation
  // dispatch lives INSIDE the same lambda. Permanent gate probes cover
  // every data-input-less shape plus borrowerAuth eager safety.
  // =====================================================================

  val r8pack       = SELF.R8[Coll[Coll[Byte]]].get
  val lenderScript = r8pack(0)
  val repayment    = SELF.R6[Long].get
  val maturity     = SELF.R7[Int].get
  val borrower     = SELF.R5[Coll[Byte]].get
  val sched        = SELF.R9[Coll[Long]].get
  val loanTokenId  = SELF.tokens(0)._1

  val exitBox = OUTPUTS(0)

  // Extended-pack reads: size-guarded, compiled default when the suffix
  // is absent (card-less bonds). Total on every R9 size — fabricated
  // intermediate sizes read safely and brick only where shapes diverge.
  val crankBounty = if (sched.size > 6)  sched(6)  else CRANK_BOUNTY
  val graceBlocks = if (sched.size > 7)  sched(7)  else GRACE_BLOCKS
  val liqCarveout = if (sched.size > 8)  sched(8)  else LIQ_CARVEOUT
  val haircutKeep = if (sched.size > 9)  sched(9)  else HAIRCUT_KEEP
  // Attestation stub slot: explicitly size-guarded (>= 11); 0 for every
  // conforming rev-3 bond (the order enforces it).
  val attestType  = if (sched.size >= 11) sched(10) else 0L

  val toLender = exitBox.propositionBytes == lenderScript

  val receiptOk =
    exitBox.R4[Coll[Byte]].isDefined &&
    exitBox.R4[Coll[Byte]].get == SELF.id &&
    exitBox.tokens.exists { (t: (Coll[Byte], Long)) =>
      t._1 == loanTokenId && t._2 == 1L
    }

  // Repay: the final payment. sched(2) <= 1 makes the maturity-side
  // bullet (R6) the collateral release for installment bonds (decision
  // 4); bullets (sched(2) == 0) pass unchanged. No prepayment for
  // installment bonds: the gate is reachable only by servicing every
  // interior coupon (spec negative D3).
  val repayOk =
    sched(2) <= 1L &&
    toLender &&
    exitBox.value >= repayment &&
    receiptOk

  val allTokensDelivered = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    exitBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  // Liquidation hook (rev 3, REV3-LAYOUT.md L7): with a committed hash
  // the destination rebinds to the hook script — full bytes supplied by
  // the spender via context-extension var 0 (NOT a data input: zero CSE
  // surface), verified against the hash. Hook absent: standard
  // liquidation to the lender script, unchanged.
  val liqDestOk =
    if (r8pack.size >= 3) {
      val hv = getVar[Coll[Byte]](0)
      hv.isDefined &&
      blake2b256(hv.get) == r8pack(2) &&
      exitBox.propositionBytes == hv.get
    } else toLender

  val liquidateOk =
    HEIGHT >= maturity &&
    liqDestOk &&
    exitBox.value >= SELF.value - liqCarveout &&
    receiptOk &&
    allTokensDelivered

  // ------------------- successor machinery -------------------

  val periodBlocks = sched(1)
  val nextCheck    = sched(3)
  val thresholdBps = sched(4)
  val escrow       = sched(5)

  val covenantOff = thresholdBps == 0L

  // The card-numeric suffix (empty on 6-element packs) rides every
  // rebuilt successor pack verbatim: whole-pack equality freezes the
  // card values across the bond's whole life.
  val schedSuffix = sched.slice(6, sched.size)

  // Frozen wall shared by all successor paths. R5 and R8 are plain
  // byte-collection equalities in rev 3 (retires the SigmaProp-equality
  // workaround); the R8 whole-collection equality freezes pool NFT and
  // hook hash across successors automatically.
  val succFrozen =
    exitBox.propositionBytes == SELF.propositionBytes &&
    exitBox.R4[Coll[Byte]].isDefined &&
    exitBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get &&
    exitBox.R5[Coll[Byte]].isDefined &&
    exitBox.R5[Coll[Byte]].get == borrower &&
    exitBox.R6[Long].isDefined &&
    exitBox.R6[Long].get == repayment &&
    exitBox.R7[Int].isDefined &&
    exitBox.R7[Int].get == maturity &&
    exitBox.R8[Coll[Coll[Byte]]].isDefined &&
    exitBox.R8[Coll[Coll[Byte]]].get == r8pack &&
    exitBox.R9[Coll[Long]].isDefined

  // ------------------- covenant pricing -------------------

  // Verdict for the state (ergLeg, box whose tokens(1) is the priced
  // collateral): -1 = data input missing/invalid; 0 = valid but
  // UNHEALTHY (or attestation fail); 1 = HEALTHY (or attestation pass).
  // Type 0 (every conforming rev-3 bond): the pinned Spectrum pool,
  // authenticated by the R8(1) NFT (behind its size guard), fee read
  // live from pool R4, division-free BigInt inequality with the
  // card-resolved haircut. Type != 0 (fabricated only in rev 3, gate
  // probe E7): one generic check — dataInputs(0) is a box whose script
  // hashes to the pinned R8(3) attester hash, bound to this loan (R4 ==
  // loan token id) and this checkpoint (R5 == |nextCheck|), reading
  // pass (R6 == 1). Pool OR attester — never both — so every path stays
  // at one data input. Fail-closed on absence, exactly like the pool
  // read (the absence-fails-healthy variant is a reserved card flag
  // bit; semantics decided when the first real attester ships).
  val verdictAt = { (q: (Long, Box)) =>
    if (CONTEXT.dataInputs.size > 0) {
      if (attestType == 0L) {
        if (r8pack.size >= 2) {
          val pool = CONTEXT.dataInputs(0)
          if (pool.tokens.size == 3 &&
              pool.tokens(0)._1 == r8pack(1) &&
              pool.R4[Int].isDefined &&
              q._2.tokens.size >= 2 &&
              q._2.tokens(1)._1 == pool.tokens(2)._1) {
            val feeNum = pool.R4[Int].get.toBigInt
            val rX     = pool.value.toBigInt
            val rY     = pool.tokens(2)._2.toBigInt
            val amt    = q._2.tokens(1)._2.toBigInt
            val simNum = rX * amt * feeNum
            val simDen = rY * 1000L.toBigInt + amt * feeNum
            if (q._1.toBigInt * 10000L.toBigInt * simDen + simNum * haircutKeep.toBigInt >=
                repayment.toBigInt * thresholdBps.toBigInt * simDen) 1 else 0
          } else -1
        } else -1
      } else {
        if (r8pack.size >= 4) {
          val att = CONTEXT.dataInputs(0)
          if (blake2b256(att.propositionBytes) == r8pack(3) &&
              att.R4[Coll[Byte]].isDefined &&
              att.R4[Coll[Byte]].get == loanTokenId &&
              att.R5[Long].isDefined &&
              att.R5[Long].get == (if (nextCheck < 0L) 0L - nextCheck else nextCheck) &&
              att.R6[Long].isDefined) {
            if (att.R6[Long].get == 1L) 1 else 0
          } else -1
        } else -1
      }
    } else -1
  }

  // Crank: Phase 3 shape plus the sched(0) == 0 gate — the keeper crank
  // path is CLOSED for installment bonds; the coupon is the only way to
  // advance their schedule. Bounty and grace are the card-resolved
  // values; the suffix rides the rebuilt pack.
  val crankOk =
    sched(0) == 0L &&
    nextCheck > 0L &&
    HEIGHT.toLong >= nextCheck &&
    HEIGHT < maturity &&
    escrow >= crankBounty &&
    succFrozen &&
    exitBox.value == SELF.value - crankBounty &&
    exitBox.tokens == SELF.tokens &&
    {
      val advancePack = Coll(
        sched(0), sched(1), sched(2),
        nextCheck + periodBlocks,
        sched(4), escrow - crankBounty).append(schedSuffix)
      if (covenantOff)
        exitBox.R9[Coll[Long]].get == advancePack
      else {
        val curePack = Coll(
          sched(0), sched(1), sched(2),
          0L - (nextCheck + graceBlocks),
          sched(4), escrow - crankBounty).append(schedSuffix)
        val v = verdictAt((SELF.value - escrow - sched(0), SELF))
        v >= 0 &&
        exitBox.R9[Coll[Long]].get == (if (v == 1) advancePack else curePack)
      }
    }

  // Coupon (decision 1): signatureless; at a checkpoint of an
  // installment bond the schedule advances ONLY with the installment
  // paid to the lender script in the same tx. OUTPUTS(0) successor:
  // frozen wall, value and escrow down one bounty in lockstep, tokens
  // verbatim, paymentsRemaining decremented, grid advanced (healthy /
  // covenant-off) or cure-encoded (unhealthy — coupon accepted, only
  // health then owed; cure restores the exact grid point with sched(2)
  // already decremented). OUTPUTS(1) installment: lender script,
  // >= sched(0), receipt R4 == SELF.id, NO loan token. The verdict
  // prices the SUCCESSOR state (post-tx, per the outline); its
  // +crankBounty term is the structural distinctifier vs the cure site.
  // No grace ceiling: past the deadline this races missedAccelOk,
  // first confirmation wins (D4).
  val couponOk =
    sched(0) > 0L &&
    sched(2) > 1L &&
    nextCheck > 0L &&
    HEIGHT.toLong >= nextCheck &&
    HEIGHT < maturity &&
    escrow >= crankBounty &&
    succFrozen &&
    exitBox.value == SELF.value - crankBounty &&
    exitBox.tokens == SELF.tokens &&
    OUTPUTS.size >= 2 &&
    {
      val inst = OUTPUTS(1)
      inst.propositionBytes == lenderScript &&
      inst.value >= sched(0) &&
      inst.R4[Coll[Byte]].isDefined &&
      inst.R4[Coll[Byte]].get == SELF.id
    } &&
    {
      val advCouponPack = Coll(
        sched(0), sched(1), sched(2) - 1L,
        nextCheck + periodBlocks,
        sched(4), escrow - crankBounty).append(schedSuffix)
      if (covenantOff)
        exitBox.R9[Coll[Long]].get == advCouponPack
      else {
        val cureCouponPack = Coll(
          sched(0), sched(1), sched(2) - 1L,
          0L - (nextCheck + graceBlocks),
          sched(4), escrow - crankBounty).append(schedSuffix)
        val v = verdictAt((exitBox.value - (escrow - crankBounty), exitBox))
        v >= 0 &&
        exitBox.R9[Coll[Long]].get == (if (v == 1) advCouponPack else cureCouponPack)
      }
    }

  // Top-up: unchanged from Phase 3 (outside cure only); whole-pack
  // equality carries the suffix verbatim.
  val tokensGrown =
    exitBox.tokens.size == SELF.tokens.size &&
    SELF.tokens.zip(exitBox.tokens).forall { (p: ((Coll[Byte], Long), (Coll[Byte], Long))) =>
      p._1._1 == p._2._1 && p._2._2 >= p._1._2
    }

  val strictlyMore =
    exitBox.value > SELF.value ||
    SELF.tokens.zip(exitBox.tokens).exists { (p: ((Coll[Byte], Long), (Coll[Byte], Long))) =>
      p._2._2 > p._1._2
    }

  val topUpOk =
    nextCheck > 0L &&
    succFrozen &&
    exitBox.value >= SELF.value &&
    tokensGrown &&
    strictlyMore &&
    exitBox.R9[Coll[Long]].get == sched

  // Cure: unchanged from Phase 3 (borrower co-spend, in-cure only, no
  // deadline — the limbo rule); grace is the card-resolved value, the
  // suffix rides the restored pack. Structurally cannot clear an
  // overdue coupon: cure requires sched(3) < 0, a missed coupon keeps
  // sched(3) > 0 (D7).
  val cureOk =
    nextCheck < 0L &&
    succFrozen &&
    exitBox.value >= SELF.value &&
    tokensGrown &&
    verdictAt((exitBox.value - escrow, exitBox)) == 1 &&
    exitBox.R9[Coll[Long]].get == Coll(
      sched(0), sched(1), sched(2),
      (0L - nextCheck) - graceBlocks + periodBlocks,
      sched(4), sched(5)).append(schedSuffix)

  // Covenant acceleration: blown grace, still unhealthy NOW. The
  // verdict argument is the semantically exact clean form — the old
  // -sched(0) distinctifier moved to the crank, whose sched(0) == 0
  // gate makes it provably neutral there (decision 5).
  val accelerateOk =
    nextCheck < 0L &&
    HEIGHT.toLong >= (0L - nextCheck) &&
    HEIGHT < maturity &&
    toLender &&
    exitBox.value >= SELF.value - liqCarveout &&
    receiptOk &&
    allTokensDelivered &&
    verdictAt((SELF.value - escrow, SELF)) == 0

  // Missed-payment acceleration (decision 2): a payment default, not a
  // collateral default — NO health test, NO verdict call, no stored
  // flag: "overdue" derives entirely from SELF + HEIGHT (MED-1). The
  // liquidation shape to the plain lender script (the hook stays a
  // liquidate-arm feature in rev 3); residual escrow rides to the
  // lender. Deadline semantics mirror the cure deadline; the encoding
  // is distinct (derived vs sign flag).
  val missedAccelOk =
    sched(0) > 0L &&
    sched(2) > 1L &&
    nextCheck > 0L &&
    HEIGHT.toLong >= nextCheck + graceBlocks &&
    HEIGHT < maturity &&
    toLender &&
    exitBox.value >= SELF.value - liqCarveout &&
    receiptOk &&
    allTokensDelivered

  // Borrower authorization by co-spend: an input guarded by the
  // borrower bytes exists in this tx. Spending that input requires
  // satisfying its script (a P2PK borrower signs; a contract borrower
  // satisfies its own logic) — the tx-level validity is the signature.
  // Reads only INPUTS: eager-hoist safe, pinned by a gate probe.
  val borrowerAuth = INPUTS.exists { (b: Box) => b.propositionBytes == borrower }

  sigmaProp(
    liquidateOk || crankOk || couponOk || accelerateOk || missedAccelOk ||
    ((repayOk || topUpOk || cureOk) && borrowerAuth)
  )
}
