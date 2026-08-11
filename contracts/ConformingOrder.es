{
  // =====================================================================
  // Conforming Open Order — rev 4 (script actors by hash + reveal-at-match)
  //
  // Rev 4 deltas (REV4-DECISIONS D1-D3 + audit fixes): bond R5 gets
  // blake2b256(borrower R4 bytes); bond R8(0) must be a 32-byte lender
  // hash; the match REQUIRES ctx-ext vars on THIS order input — var 0 =
  // full lender script (always), var 1 = full hook script (iff pinned) —
  // each verified against its hash. Card numerics: flagWord gated 0,
  // carveout/haircutKeep capped at compiled outer bounds, byte fields
  // 32-or-empty. Cancel co-spend excludes SELF.
  //
  // Borrower-side loan request. The borrower locks collateral here with
  // the requested terms; any funder (key or contract) matches it by
  // creating a conforming bond box and paying the principal to the
  // borrower script. Semantics descend from SigmaFi's order/bond pair.
  //
  // Rev 3 deltas (CONTRACT-DELTAS §2, REV3-LAYOUT.md):
  //   - R4 is borrower ErgoTree BYTES (script borrower — contracts and
  //     DAOs can post orders); principal pays to those bytes directly;
  //     cancel authorizes by CO-SPEND of a borrower-script input.
  //   - R8 is the pin pack [cardPin, liqHookHash?]: the terms-box NFT id
  //     the borrower pinned at creation (pin-before-match — the funder
  //     can never choose the tier), plus the optional liquidation-hook
  //     hash (borrower-pinned pre-match for the same reason, L12).
  //     EMPTY pin = card-less match: ZERO data inputs, wholesale
  //     compiled defaults — the T0 baseline, structurally preserved.
  //     With a pin, dataInputs(0) must be the card, validated by NFT id
  //     == the pin. All checks are ours: data-input scripts do not
  //     execute (same discipline as the bond's pool read).
  //   - schedOk generalizes: bond registers == values resolved from
  //     (card fields, sentinel fallback to compiled defaults,
  //     order-supplied values where the card bounds them). The compiled
  //     constants stay as protocol floors/outer bounds a card may
  //     tighten, never loosen.
  //   - Phase 4 (decision 6): MIN_COUPON floor on nonzero installments;
  //     bullet coupling (installment iff payments); paymentsRemaining ==
  //     K+1 exactly, with K >= 1 for installment orders (an installment
  //     order with zero interior coupons is a bullet wearing a costume).
  //     Escrow formula unchanged in shape, priced at the RESOLVED bounty.
  //   - Attestation gate: a pinned card must carry attestationType == 0
  //     — no nonzero-type bond is matchable in rev 3. Enabling a type
  //     later is an order-side revision only; the bond never changes.
  //
  // Registers:
  //   R4: Coll[Byte]       borrower ErgoTree bytes
  //   R5: Long             principal requested, nanoERG
  //   R6: Long             repayment amount, nanoERG
  //   R7: Int              term in blocks (maturity = match height + term)
  //   R8: Coll[Coll[Byte]] [cardPin] or [cardPin, liqHookHash];
  //                        pin empty = card-less. MANDATORY register
  //                        (write the empty coll for card-less): the
  //                        element reads are total size-conditioned
  //                        expressions, but the .get itself is eager —
  //                        an absent or wrong-TYPE R8 bricks all paths
  //                        like any malformed SELF register (tooling's
  //                        duty, creator's loss — EKB rev-3 F3).
  //   R9: Coll[Long]       schedule template [installment, periodBlocks,
  //         paymentsRemaining, ignored-at-order, maintenanceThresholdBps,
  //         escrowBalance]
  //   value + tokens: the collateral, plus escrowBalance in the value
  //
  // TOOLCHAIN RULE (LOW-P3-B1 — the order reads a data input for the
  // first time, so the rev-1 crash class applies here now): every
  // CONTEXT.dataInputs read lives inside the SINGLE cardOk lambda,
  // applied exactly once, behind the pin-presence branch. The shared
  // template/pack validation is a SECOND, dataInputs-FREE lambda
  // (conformsWith) applied twice — compiled defaults vs card-resolved
  // values — with structurally distinct argument graphs, so no CSE
  // merge can schedule an eager dataInputs node. Cancel and card-less
  // match never evaluate cardOk; both carry permanent no-data-input
  // gate probes.
  //
  // Cancel keeps the HIGH-O1 guard verbatim: no output may carry a
  // token whose id equals SELF.id — the loan token remains mintable
  // only through matchOk (provenance rule). MED-O9's whole-outputs
  // supply fold keeps the loan token a true singleton.
  // =====================================================================

  val borrower  = SELF.R4[Coll[Byte]].get
  val principal = SELF.R5[Long].get
  val repayment = SELF.R6[Long].get
  val term      = SELF.R7[Int].get
  val tmpl      = SELF.R9[Coll[Long]].get

  // Pin pack: the element reads are TOTAL expressions (size-conditioned
  // with empty-coll fallback), so the compiler may hoist them anywhere
  // without a crash path — no guarded .get for CSE to lift above its
  // guard (LOW-P3-B1 class). A size-0 pack reads as card-less and stays
  // cancellable; a wrong-TYPE R8 bricks like any malformed SELF
  // register (tooling's job, creator's loss — the rev-2 posture).
  val pinPack     = SELF.R8[Coll[Coll[Byte]]].get
  val cardPin     = if (pinPack.size >= 1) pinPack(0) else Coll[Byte]()
  val hookHash    = if (pinPack.size >= 2) pinPack(1) else Coll[Byte]()
  val hasPin      = cardPin.size > 0
  val hookPresent = hookHash.size > 0

  val bondBox = OUTPUTS(0)

  val bondScriptOk = blake2b256(bondBox.propositionBytes) == BOND_SCRIPT_HASH

  // Freshly minted loan token at slot 0: exactly 1 unit, id == SELF.id,
  // and SELF is the first input so the mint is genuine.
  val loanTokenOk =
    INPUTS(0).id == SELF.id &&
    bondBox.tokens.size == SELF.tokens.size + 1 &&
    bondBox.tokens(0)._1 == SELF.id &&
    bondBox.tokens(0)._2 == 1L

  // Loan-token supply cap (MED-O9): total across ALL outputs exactly 1.
  val loanId = SELF.id
  val loanTokenSupplyOne =
    OUTPUTS.fold(0L, { (acc: Long, o: Box) =>
      o.tokens.fold(acc, { (a: Long, t: (Coll[Byte], Long)) =>
        if (t._1 == loanId) a + t._2 else a
      })
    }) == 1L

  // Every collateral token carried into the bond in full.
  val collateralTokensOk = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    bondBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  // Maturity stamped as buildHeight + term, MATURITY_TOL blocks of lag
  // accepted. Window bounds, never equality.
  val maturityOk =
    bondBox.R7[Int].isDefined && {
      val m = bondBox.R7[Int].get
      m >= HEIGHT + term - MATURITY_TOL && m <= HEIGHT + term
    }

  val bondRegsOk =
    bondBox.R4[Coll[Byte]].isDefined &&
    bondBox.R4[Coll[Byte]].get == SELF.id &&
    bondBox.R5[Coll[Byte]].isDefined &&
    bondBox.R5[Coll[Byte]].get == blake2b256(borrower) &&
    bondBox.R6[Long].isDefined &&
    bondBox.R6[Long].get == repayment

  // Card-independent template gates (Phase 4 decision 6). Every tmpl
  // index sits behind tmpl.size == 6 in this lazy chain; the K division
  // sits behind the compiled MIN_PERIOD floor (the card can only RAISE
  // the floor — conformsWith re-checks the resolved value). Coupling:
  // installment iff payments; paymentsRemaining counts K interior
  // coupons + 1 final bullet; K >= 1 for installment orders.
  val schedCommonOk =
    tmpl.size == 6 &&
    term >= 1 &&
    tmpl(1) >= MIN_PERIOD &&
    ((tmpl(0) == 0L) == (tmpl(2) == 0L)) &&
    {
      val k = (term.toLong - 1L) / tmpl(1)
      tmpl(2) == 0L || (tmpl(2) == k + 1L && k >= 1L)
    }

  // The generalized conformance check — dataInputs-FREE by construction.
  // Applied twice: once with wholesale compiled defaults (card-less arm)
  // and once with card-resolved values (inside cardOk); the two argument
  // graphs are structurally distinct (constant literals vs card reads),
  // so the applications cannot be merged into a shared eager node.
  //   p._1 numeric pack: [crankBounty, graceBlocks, liqCarveout,
  //     haircutKeep, thrMin, thrMax, minOrderValue, minPeriod,
  //     minCoupon, carded(0/1)]
  //   p._2: (poolNFT, collateralTokenId)
  // Checks: the resolved floors and ranges over the template; escrow ==
  // resolvedBounty * K exactly (lockstep-drain guard base); the
  // net-of-escrow collateral floor; whole-pack bond R9 equality against
  // the anchored grid — 6 elements card-less, 10 with the card suffix
  // (the resolved numerics land at bond R9 indices 6-9, REV3-LAYOUT
  // L1); and the structured bond R8 pack — outer size by shape, element
  // 0 (funder's lender script) nonempty, element 1 == the resolved pool
  // NFT when the covenant is on, element 2 == the borrower-pinned hook
  // hash when present (hook presupposes covenant: the size-3 shape
  // carries the pool NFT at index 1). A covenant order still needs
  // exactly one collateral token — the resolved pool's traded token —
  // and escrow >= one resolved bounty (LOW-P3-O1: protection that
  // cannot fire is unmatchable).
  val conformsWith = { (p: (Coll[Long], (Coll[Byte], Coll[Byte]))) =>
    val n        = p._1
    val poolNft  = p._2._1
    val collatId = p._2._2
    val carded   = n(9) == 1L
    tmpl(1) >= n(7) &&
    (tmpl(0) == 0L || tmpl(0) >= n(8)) &&
    (tmpl(4) == 0L ||
      (tmpl(4) >= n(4) && tmpl(4) <= n(5) &&
       SELF.tokens.size == 1 &&
       SELF.tokens(0)._1 == collatId &&
       tmpl(5) >= n(0))) &&
    tmpl(5) == n(0) * ((term.toLong - 1L) / tmpl(1)) &&
    SELF.value - tmpl(5) >= n(6) &&
    bondBox.R9[Coll[Long]].isDefined &&
    bondBox.R7[Int].isDefined &&
    bondBox.R8[Coll[Coll[Byte]]].isDefined &&
    {
      val s = bondBox.R9[Coll[Long]].get
      val m = bondBox.R7[Int].get.toLong
      val base = Coll(tmpl(0), tmpl(1), tmpl(2),
                      (m - term.toLong) + tmpl(1),
                      tmpl(4), tmpl(5))
      s == (if (carded) base.append(Coll(n(0), n(1), n(2), n(3))) else base)
    } &&
    {
      // Rev 4 (D1/D3): br8(0) is the 32-byte blake2b256 of the funder's
      // lender script, and the match must REVEAL the preimage via ctx-ext
      // var 0 on this order input (var 1 for the hook when pinned) — a
      // hash nobody can open would leave the borrower unable to build any
      // lender payment (forced default, bricked exits) or the lender
      // without a post-maturity claim. Reveal proves the preimage EXISTS
      // on-chain, not that it is a spendable script (funder/borrower
      // self-burn stays the documented posture). getVar is ctx-ext, not a
      // data input — zero D-I surface (same class as the bond's liq hook).
      val br8   = bondBox.R8[Coll[Coll[Byte]]].get
      val covOn = tmpl(4) != 0L
      val lv    = getVar[Coll[Byte]](0)
      br8.size >= 1 &&
      br8(0).size == 32 &&
      lv.isDefined &&
      blake2b256(lv.get) == br8(0) &&
      (if (hookPresent)
        covOn && br8.size == 3 && br8(1) == poolNft && br8(2) == hookHash && {
          val hv = getVar[Coll[Byte]](1)
          hv.isDefined && blake2b256(hv.get) == hookHash
        }
      else if (covOn)
        br8.size == 2 && br8(1) == poolNft
      else
        br8.size == 1)
    }
  }

  // THE data-input lambda — every CONTEXT.dataInputs read in the
  // contract lives here, and it is applied exactly once, only on the
  // pinned-card branch. The card is authenticated by NFT id == the pin
  // (amount exactly 1: the terms NFT is a singleton), its packs are
  // size-guarded, its attestationType must be 0 (the rev-3 gate that
  // keeps the bond's generic verdict branch unreachable), and its
  // sentinel fields resolve IN-CONTRACT to the compiled defaults before
  // the shared conformance check runs.
  val cardOk = { (go: Boolean) =>
    go &&
    CONTEXT.dataInputs.size > 0 &&
    {
      val card = CONTEXT.dataInputs(0)
      card.tokens.size >= 1 &&
      card.tokens(0)._1 == cardPin &&
      card.tokens(0)._2 == 1L &&
      // Rev-4 audit (A-M5, user-approved): the pinned NFT proves WHICH
      // box; this proves it is governed by the refuel-only card script —
      // a look-alike box holding the NFT under a mutable guard cannot
      // reprice a posted order.
      blake2b256(card.propositionBytes) == TERMS_BOX_HASH &&
      card.R7[Coll[Long]].isDefined &&
      card.R8[Coll[Coll[Byte]]].isDefined &&
      {
        val c7 = card.R7[Coll[Long]].get
        val c8 = card.R8[Coll[Coll[Byte]]].get
        c7.size >= 11 &&
        c8.size >= 2 &&
        c7(9) == 0L &&
        // Rev-4 audit: flagWord gated to zero like attestationType — no
        // reserved bit ships matchable before its semantics exist.
        c7(10) == 0L &&
        // Rev-4 audit: card byte fields are token ids — 32 bytes or the
        // empty sentinel, nothing else.
        (c8(0).size == 0 || c8(0).size == 32) &&
        (c8(1).size == 0 || c8(1).size == 32) &&
        // Free-set card numerics must be non-negative (EKB rev-3 F2): a
        // negative bounty would flip the escrow-exactness sign and
        // inflate the net-of-escrow collateral floor; negative grace
        // would arm instant acceleration. Sentinel 0 = default; the
        // clamped/max()ed fields below are sign-safe by construction.
        // Rev-4 (A-H1/B-H2, user direction): a hook is legal ONLY if the
        // pinned card lists its hash (card R8 indices 4+ = blessed hooks,
        // frozen by the refuel-only guard). The hook and the terms travel
        // as one immutable, auditable bundle — no free-floating hook
        // hashes. Card-less orders cannot carry hooks at all (matchOk).
        (!hookPresent || c8.slice(4, c8.size).exists { (h: Coll[Byte]) =>
          h == hookHash
        }) &&
        c7(0) >= 0L && c7(1) >= 0L && c7(2) >= 0L && c7(3) >= 0L &&
        {
          val bounty  = if (c7(0) == 0L) CRANK_BOUNTY else c7(0)
          val grace   = if (c7(1) == 0L) GRACE_BLOCKS else c7(1)
          // Rev-4 audit: carveout and haircutKeep are OUTER bounds a card
          // may tighten, never loosen — cap at the compiled values (an
          // uncapped carveout lets a liquidator strip the collateral; an
          // inflated haircutKeep nullifies the covenant).
          val carve   = if (c7(2) == 0L || c7(2) > LIQ_CARVEOUT) LIQ_CARVEOUT else c7(2)
          val haircut = if (c7(3) == 0L || c7(3) > HAIRCUT_KEEP) HAIRCUT_KEEP else c7(3)
          val thrMin  = if (c7(4) < 10000L) 10000L else c7(4)
          val thrMax  = if (c7(5) <= 0L) 30000L
                        else if (c7(5) > 30000L) 30000L else c7(5)
          val minOrd  = if (c7(6) < MIN_ORDER_VALUE) MIN_ORDER_VALUE else c7(6)
          val minPer  = if (c7(7) < MIN_PERIOD) MIN_PERIOD else c7(7)
          val minCoup = if (c7(8) < MIN_COUPON) MIN_COUPON else c7(8)
          val poolNft  = if (c8(0).size == 0) POOL_NFT else c8(0)
          val collatId = if (c8(1).size == 0) COLLATERAL_TOKEN_ID else c8(1)
          conformsWith((Coll(bounty, grace, carve, haircut, thrMin, thrMax,
            minOrd, minPer, minCoup, 1L), (poolNft, collatId)))
        }
      }
    }
  }

  // Full order value (collateral + escrow) must ride into the bond.
  val collateralOk =
    bondBox.value >= SELF.value

  val principalOk =
    OUTPUTS.size >= 2 && {
      val principalBox = OUTPUTS(1)
      principalBox.propositionBytes == borrower &&
      principalBox.value >= principal
    }

  val matchOk =
    bondScriptOk &&
    loanTokenOk &&
    loanTokenSupplyOne &&
    collateralTokensOk &&
    maturityOk &&
    bondRegsOk &&
    schedCommonOk &&
    collateralOk &&
    principalOk &&
    (if (hasPin)
      cardOk(true)
    else
      !hookPresent &&
      conformsWith((Coll(CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP,
        10000L, 30000L, MIN_ORDER_VALUE, MIN_PERIOD, MIN_COUPON, 0L),
        (POOL_NFT, COLLATERAL_TOKEN_ID))))

  // Cancel: borrower-script co-spend (a contract borrower satisfies its
  // own script on a co-spent box; a P2PK borrower signs one of their
  // boxes), conjoined with the untouched HIGH-O1 mint guard.
  // Rev-4 audit: SELF excluded — an order whose R4 equals the order tree
  // itself must not self-authorize its own cancel (anyone-spendable).
  val borrowerAuth = INPUTS.exists { (b: Box) =>
    b.propositionBytes == borrower && b.id != SELF.id
  }

  val noLoanTokenMinted = OUTPUTS.forall { (o: Box) =>
    o.tokens.forall { (t: (Coll[Byte], Long)) => t._1 != SELF.id }
  }

  sigmaProp(matchOk || (borrowerAuth && noLoanTokenMinted))
}
