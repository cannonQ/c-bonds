{
  // =====================================================================
  // Conforming Bond
  //
  // A live loan: collateral (value + tokens) locked under the terms in
  // R4-R9 until repaid, liquidated, or accelerated. Two kinds of arm:
  //   - signatureless: liquidate, crank, coupon, accelerate, missedAccel.
  //     Anyone may execute; the conditions fully determine the outcome.
  //   - borrower-authorized: repay, top-up, cure. Authorization is by
  //     CO-SPEND: some input's propositionBytes must hash to R5.
  //     Spending that input satisfies its own script, and that is the
  //     transaction's authorization — a P2PK borrower signs, a contract
  //     or DAO borrower authorizes through its own spending logic.
  // Every branch is Boolean; the contract is one sigmaProp at top level.
  //
  // Registers. Suffix packs are dense and append-only: presence is
  // detected by size, and a longer pack always extends a shorter one.
  //   R4: Coll[Byte]       originating order box id (== loan token id)
  //   R5: Coll[Byte]       blake2b256 of the borrower's ErgoTree
  //   R6: Long             repayment amount, nanoERG
  //   R7: Int              maturity height
  //   R8: Coll[Coll[Byte]] size 1 [lenderHash]                  plain
  //                        size 2 [.., poolNFT]                 covenant
  //                        size 3 [.., .., liqHookHash]         + hook
  //                        size 4 [.., .., .., attesterHash]    dead: no
  //                               currently originated bond carries it
  //                               (see verdictAt)
  //     lenderHash  = blake2b256 of the lender's ErgoTree
  //     liqHookHash = blake2b256 of a liquidation script
  //   R9: Coll[Long] size 6  [installment, periodBlocks,
  //                           paymentsRemaining, nextCheckHeight,
  //                           thresholdBps, escrowBalance]
  //                  size 10 [.. + crankBounty, graceBlocks,
  //                           liqCarveout, haircutKeep]
  //                  size 11 [.. + attestationType] — dead, as R8 size 4
  //     Absent suffix elements fall back to the compiled constants of
  //     the same names. nextCheckHeight > 0: next checkpoint height;
  //     < 0: the bond is in cure and |nextCheckHeight| is the cure
  //     deadline. paymentsRemaining counts every payment, K interior
  //     coupons + 1 final payment (K+1 at origination); bullet loans
  //     carry 0. thresholdBps == 0 disables the covenant entirely.
  //   tokens(0): (loanTokenId, 1) — minted at match; id == R4 value
  //   tokens(1): the collateral token — covenant bonds only
  //
  // R5 and R8(0) hold script HASHES, not script bytes, so bond box size
  // is independent of counterparty script size (boxes cap at 4 KB).
  // Payment destinations are verified by hashing the OUTPUT's own
  // propositionBytes. The matching order contract requires both
  // preimages in its context extension at match (its var 0 = lender
  // script, var 1 = hook script), so the full scripts are always
  // recoverable from chain history. NOTE the var-index overload: var 0
  // on the ORDER input at match is the lender script; var 0 on THIS
  // input at liquidation is the hook script.
  //
  // Paths:
  //   Repay        borrower co-spend, any height, sched(2) <= 1. An
  //                installment bond reaches this only after every
  //                interior coupon — early repayment of the remaining
  //                schedule is deliberately impossible. Bullets
  //                (sched(2) == 0) repay at any time. Collateral tokens
  //                must return to the borrower (collateralToBorrower).
  //   Liquidate    signatureless, HEIGHT >= maturity. With a committed
  //                hook (R8 size >= 3) the exit box's script must BE the
  //                hook script, revealed in context-extension var 0 of
  //                this input and verified against R8(2). Without a
  //                hook, the exit pays the lender.
  //   Crank        signatureless checkpoint advance for bonds with no
  //                installment (sched(0) == 0); verdict-branched when
  //                the covenant is on.
  //   Coupon       signatureless checkpoint advance for installment
  //                bonds, valid only when OUTPUTS(1) pays the
  //                installment to the lender in the same transaction.
  //                No upper height bound: past the grace deadline a
  //                late coupon and missedAccel are BOTH valid and the
  //                first confirmation wins.
  //   Cure         borrower co-spend while in cure (sched(3) < 0);
  //                verdict must be healthy. No deadline: past the cure
  //                window this stays valid and races accelerate.
  //   Accelerate   signatureless covenant default: cure deadline passed
  //                and the verdict is unhealthy NOW.
  //   MissedAccel  signatureless payment default: an installment bond
  //                whose checkpoint passed graceBlocks ago unpaid.
  //                Liquidation shape; no health test (a payment default
  //                is not a collateral default).
  //   Top-up       borrower co-spend, outside cure only: value and
  //                token amounts may only grow, terms frozen.
  //
  // Path disjointness:
  //   - crank vs coupon: sched(0) == 0 vs sched(0) > 0
  //   - accelerate vs missedAccel: sched(3) < 0 vs sched(3) > 0
  //   - liquidate requires HEIGHT >= maturity; crank, coupon,
  //     accelerate and missedAccel all require HEIGHT < maturity
  //   - coupon vs missedAccel overlap past the grace deadline BY
  //     DESIGN (the late-coupon race); all other signatureless pairs
  //     are exclusive
  //   - borrower arms may race signatureless arms; every such race is
  //     between valid outcomes and the first confirmation wins
  //
  // Rules that hold everywhere:
  //   - height windows are >= open and < close, never equality
  //   - fallible reads on foreign boxes (outputs, data inputs) are
  //     guarded, so a non-conforming transaction reduces cleanly to
  //     false instead of throwing
  //   - a malformed SELF (wrong register type, missing register, short
  //     R8) is unspendable on every path; only the box's creator can
  //     produce such a box, so the loss is theirs alone
  //
  // COMPILER CONSTRAINT — do not refactor away. The ErgoScript compiler
  // hoists common subexpressions into eager top-level values, ABOVE the
  // lazy &&/branch guards they sit under in source. An expression that
  // can throw — CONTEXT.dataInputs(0) in a transaction with no data
  // inputs — must therefore never appear as a subexpression shared
  // between arms. Discipline in this file:
  //   - every CONTEXT.dataInputs read lives inside the single verdictAt
  //     lambda, including its attestation branch;
  //   - the four application sites (crank, coupon, cure, accelerate)
  //     each pass a structurally distinct argument, so no two sites can
  //     merge into one shared eager node. The crank's argument keeps a
  //     - sched(0) term for exactly this reason: behind the crank's
  //     sched(0) == 0 gate it is provably zero, and it keeps that call
  //     site distinct;
  //   - borrowerAuth and the hash comparisons read only INPUTS/OUTPUTS
  //     fields that always exist — total expressions, safe to hoist.
  // Compile-gate probes assert every data-input-less path still
  // reduces cleanly.
  // =====================================================================

  val r8pack     = SELF.R8[Coll[Coll[Byte]]].get
  val lenderHash = r8pack(0)
  val repayment    = SELF.R6[Long].get
  val maturity     = SELF.R7[Int].get
  val borrower     = SELF.R5[Coll[Byte]].get
  val sched        = SELF.R9[Coll[Long]].get
  val loanTokenId  = SELF.tokens(0)._1

  val exitBox = OUTPUTS(0)

  // Suffix reads: size-conditioned with compiled-constant fallback.
  // TOTAL expressions on every R9 size — safe under eager hoisting (see
  // COMPILER CONSTRAINT). A pack of unexpected intermediate size reads
  // safely and fails only where path conditions diverge.
  val crankBounty = if (sched.size > 6)  sched(6)  else CRANK_BOUNTY
  val graceBlocks = if (sched.size > 7)  sched(7)  else GRACE_BLOCKS
  val liqCarveout = if (sched.size > 8)  sched(8)  else LIQ_CARVEOUT
  val haircutKeep = if (sched.size > 9)  sched(9)  else HAIRCUT_KEEP
  // Attestation type: size-guarded, 0 whenever the slot is absent. The
  // matching order originates only type-0 bonds, so the generic
  // attester branch of verdictAt is currently unreachable.
  val attestType  = if (sched.size >= 11) sched(10) else 0L

  val toLender = blake2b256(exitBox.propositionBytes) == lenderHash

  val receiptOk =
    exitBox.R4[Coll[Byte]].isDefined &&
    exitBox.R4[Coll[Byte]].get == SELF.id &&
    exitBox.tokens.exists { (t: (Coll[Byte], Long)) =>
      t._1 == loanTokenId && t._2 == 1L
    }

  // Collateral return on repay: every collateral token must land in an
  // output guarded by the borrower's own script. Without this, any
  // transaction the borrower's script happens to co-authorize could
  // route the collateral anywhere as a side effect of "repaying" the
  // loan. The ERG residual is deliberately unconstrained: the borrower
  // script authorized this transaction, so directing ERG is its
  // responsibility. tokens(0) is the loan token (it rides to the lender
  // receipt); slice(1, _) is the collateral. Per-token check is >=, and
  // different tokens may land in different outputs. The empty slice of
  // an ERG-only bond is vacuously true.
  val collateralToBorrower =
    SELF.tokens.slice(1, SELF.tokens.size).forall { (t: (Coll[Byte], Long)) =>
      OUTPUTS.exists { (o: Box) =>
        blake2b256(o.propositionBytes) == borrower &&
        o.tokens.exists { (ot: (Coll[Byte], Long)) =>
          ot._1 == t._1 && ot._2 >= t._2
        }
      }
    }

  val repayOk =
    sched(2) <= 1L &&
    toLender &&
    exitBox.value >= repayment &&
    receiptOk &&
    collateralToBorrower

  val allTokensDelivered = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    exitBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  // Liquidation destination: with a committed hook (R8 size >= 3) the
  // exit box's script must equal the revealed preimage of R8(2). The
  // preimage arrives in context-extension var 0 of THIS input — a
  // context variable, not a data input, so the liquidate path keeps its
  // zero-data-input property. Hook absent: the exit pays the lender.
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

  // Successor invariant shared by crank, coupon, top-up and cure: the
  // script and R4-R8 are byte-equal — the R8 whole-collection equality
  // freezes lender hash, pool NFT and hook hash together. R9 need only
  // be PRESENT here; each path constrains its exact value.
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

  // Health verdict for a proposed state (ergLeg, box whose tokens(1) is
  // the priced collateral). Returns -1 = data input missing or invalid;
  // 0 = valid but unhealthy (or attestation fail); 1 = healthy (or
  // attestation pass).
  // attestationType == 0: price against a Spectrum N2T pool.
  // dataInputs(0) must RUN the real pool script (hash check — an NFT on
  // a fake three-token box is not a price source) AND carry the R8(1)
  // pool NFT. The swap fee is read live from pool R4 because it differs
  // per pool. The test simulates selling the whole collateral leg into
  // the pool, discounts the proceeds by haircutKeep, and compares
  // against repayment * thresholdBps — rearranged into a division-free
  // BigInt inequality.
  // attestationType != 0: dataInputs(0) must be a box whose script
  // hashes to R8(3), bound to this loan (R4 == loan token id) and this
  // checkpoint (R5 == |nextCheck|), reporting pass/fail in R6. No
  // currently originated bond carries a nonzero type, so this branch is
  // unreachable until the order contract permits other types.
  // Pool OR attester, never both: every path uses at most one data
  // input. Absence fails closed (-1) on both branches.
  val verdictAt = { (q: (Long, Box)) =>
    if (CONTEXT.dataInputs.size > 0) {
      if (attestType == 0L) {
        if (r8pack.size >= 2) {
          val pool = CONTEXT.dataInputs(0)
          if (pool.tokens.size == 3 &&
              blake2b256(pool.propositionBytes) == SPECTRUM_POOL_HASH &&
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

  // Crank: closed for installment bonds (sched(0) == 0) — the coupon is
  // their only schedule advance. Bounty and grace are the resolved
  // values from the suffix; the suffix itself rides the rebuilt pack
  // unchanged.
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

  // Coupon: the schedule advances ONLY when OUTPUTS(1) pays the
  // installment to the lender in the same transaction. OUTPUTS(0) is
  // the successor: terms frozen, value and escrow down exactly one
  // bounty in lockstep, tokens verbatim (which keeps the loan token on
  // the successor), paymentsRemaining decremented, checkpoint advanced
  // (healthy or covenant-off) or cure-encoded (unhealthy: the coupon is
  // accepted, only health is then owed — a later cure restores the
  // grid point with sched(2) already decremented). OUTPUTS(1) pays
  // >= sched(0) to the lender and carries the R4 == SELF.id receipt.
  // The verdict prices the SUCCESSOR (post-transaction) state; its
  // escrow term also keeps this verdictAt call site structurally
  // distinct from the cure site (see COMPILER CONSTRAINT). No upper
  // height bound: past the grace deadline this races missedAccelOk and
  // the first confirmation wins. Each serviced checkpoint consumes
  // exactly one bounty; the order escrows bounty * K at origination, so
  // escrow reaches zero exactly when the last interior coupon is paid.
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
      blake2b256(inst.propositionBytes) == lenderHash &&
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

  // Token-growth helpers shared by top-up and cure: same token ids and
  // count, amounts may only grow; strictlyMore additionally requires an
  // actual increase somewhere.
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

  // Top-up: outside cure only (nextCheck > 0); schedule byte-equal.
  val topUpOk =
    nextCheck > 0L &&
    succFrozen &&
    exitBox.value >= SELF.value &&
    tokensGrown &&
    strictlyMore &&
    exitBox.R9[Coll[Long]].get == sched

  // Cure: borrower co-spend while in cure (nextCheck < 0); the verdict
  // must be healthy at the restored state. No deadline: past the cure
  // window this stays valid and simply races accelerateOk. The restored
  // checkpoint is grid-anchored — |nextCheck| - graceBlocks is the
  // checkpoint that failed, + periodBlocks is the next grid point. A
  // missed COUPON cannot be cured here: a missed coupon keeps
  // sched(3) > 0 and cure requires sched(3) < 0.
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

  // Covenant acceleration: the cure deadline has passed and the bond is
  // unhealthy NOW. The verdict argument is the exact pre-state; the
  // call-site-distinctness burden is carried by the crank's -sched(0)
  // term (see COMPILER CONSTRAINT).
  val accelerateOk =
    nextCheck < 0L &&
    HEIGHT.toLong >= (0L - nextCheck) &&
    HEIGHT < maturity &&
    toLender &&
    exitBox.value >= SELF.value - liqCarveout &&
    receiptOk &&
    allTokensDelivered &&
    verdictAt((SELF.value - escrow, SELF)) == 0

  // Missed-payment acceleration: a payment default, not a collateral
  // default — no health test, no verdictAt call (holding the verdict
  // call-site count at four), and no stored flag: "overdue" derives
  // entirely from SELF and HEIGHT, so there is nothing for an attacker
  // to forge. Liquidation shape paying the PLAIN lender — the hook
  // applies only to the liquidate arm. Residual escrow rides to the
  // lender.
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

  // Borrower authorization by co-spend: some input's script must hash
  // to R5. Spending that input satisfies its script, and that is the
  // transaction's authorization (a P2PK borrower signs; a contract
  // borrower's own spending conditions apply). Reads only INPUTS, which
  // always exists — a total expression, safe under eager hoisting.
  // A trivially-satisfiable borrower script opens the borrower arms to
  // anyone: the borrower's own choice, documented rather than
  // prevented, like a funder self-selecting a lender hash they cannot
  // spend.
  val borrowerAuth = INPUTS.exists { (b: Box) => blake2b256(b.propositionBytes) == borrower }

  sigmaProp(
    liquidateOk || crankOk || couponOk || accelerateOk || missedAccelOk ||
    ((repayOk || topUpOk || cureOk) && borrowerAuth)
  )
}
