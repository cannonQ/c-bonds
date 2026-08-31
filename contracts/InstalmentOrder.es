{
  // =====================================================================
  // Instalment Open Order
  //
  // Borrower-side loan request for an InstalmentBond. The borrower locks
  // collateral here with the requested terms; any funder — key or
  // contract — matches it by creating a conforming bond box and paying
  // the principal to the borrower script. The only other path is cancel,
  // authorized by borrower co-spend.
  //
  // This order originates ONE product: an instalment loan
  // (installment > 0), whose paymentsRemaining counts K interior coupons
  // plus 1 final payment. The covenant stays OPTIONAL — thresholdBps 0
  // is a schedule-only loan, nonzero adds the maintenance covenant on
  // the same bond script.
  //
  // Context-extension contract (variables on THIS input, supplied by
  // the funder at match):
  //   var 0: the full lender ErgoTree — required on every match;
  //          blake2b256 of it must equal bond R8(0)
  //   var 1: the full liquidation-hook script — required iff a hook
  //          hash is pinned; blake2b256 of it must equal that hash
  // The bond stores only 32-byte hashes, so the reveals keep both
  // scripts recoverable from chain history. Without them, a hash with
  // no known preimage would leave the borrower unable to construct any
  // lender payment, or the lender without a post-maturity claim. A
  // reveal proves the preimage exists — not that it is a spendable
  // script; a funder revealing garbage burns only their own claim.
  // NOTE the var-index overload: var 0 on this ORDER input is the
  // lender script; var 0 on the BOND input at liquidation is the hook
  // script. Builders must not share a constant across the two.
  //
  // Registers:
  //   R4: Coll[Byte]       borrower ErgoTree bytes — the full script,
  //                        because the funder builds the principal box
  //                        from it; the bond gets only blake2b256(R4)
  //   R5: Long             principal requested, nanoERG
  //   R6: Long             repayment amount, nanoERG
  //   R7: Int              term in blocks (maturity = match height + term)
  //   R8: Coll[Coll[Byte]] [cardPin] or [cardPin, liqHookHash];
  //                        empty pin = card-less. MANDATORY — write the
  //                        empty coll for card-less. Element reads are
  //                        total size-conditioned expressions, but the
  //                        .get itself is eager: a box with an absent
  //                        or wrong-typed R8 is unspendable on every
  //                        path including cancel, and only its own
  //                        creator can produce such a box.
  //   R9: Coll[Long]       schedule template [installment (must be > 0),
  //         periodBlocks, paymentsRemaining, ignored-at-order,
  //         maintenanceThresholdBps, escrowBalance]
  //   value + tokens: the collateral, plus escrowBalance in the value
  //
  // Card and hook are pinned by the BORROWER at creation, before match:
  // the funder can never choose the tier or the hook. An empty pin is
  // the card-less arm — it evaluates no data-input read at all and
  // resolves every parameter to the compiled constants. With a pin,
  // dataInputs(0) must be the pinned card, authenticated by NFT id AND
  // by script hash; card values resolve with sentinel fallback (0 or
  // empty = compiled default), and the compiled constants act as
  // floors/outer bounds a card may tighten but never loosen. A pinned
  // card must carry attestationType == 0 — no other type can currently
  // be originated.
  //
  // COMPILER CONSTRAINT — do not refactor away. The compiler hoists
  // common subexpressions into eager top-level values above their lazy
  // guards, and CONTEXT.dataInputs(0) throws in a transaction with no
  // data inputs. Discipline here: every CONTEXT.dataInputs read lives
  // inside the single cardOk lambda, applied exactly once, behind the
  // pin-presence branch; the shared template/pack validation is a
  // second, dataInputs-FREE lambda (conformsWith) applied twice with
  // structurally distinct argument graphs (constant literals vs card
  // reads), so no CSE merge can schedule an eager dataInputs node.
  // Cancel and card-less match never evaluate cardOk. getVar reads are
  // context-extension, not data inputs, and every .get sits behind an
  // isDefined in the same lazy chain.
  //
  // Loan-token provenance: on cancel, no output may carry a token whose
  // id equals SELF.id, so the loan token is mintable only through
  // matchOk; on match, a fold over ALL outputs pins its total supply at
  // exactly 1.
  // =====================================================================

  val borrower  = SELF.R4[Coll[Byte]].get
  val principal = SELF.R5[Long].get
  val repayment = SELF.R6[Long].get
  val term      = SELF.R7[Int].get
  val tmpl      = SELF.R9[Coll[Long]].get

  // Pin pack: the element reads are TOTAL expressions (size-conditioned
  // with empty-coll fallback), so the compiler may hoist them anywhere
  // without creating a crash path — there is no guarded .get for CSE to
  // lift above its guard. A size-0 pack reads as card-less and stays
  // cancellable.
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

  // Match conjunct: loan-token total across ALL outputs is exactly 1.
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

  // Card-independent template gates. Every tmpl index sits behind
  // tmpl.size == 6 in this lazy chain; the K division sits behind the
  // compiled MIN_PERIOD floor (a card can only RAISE the floor —
  // conformsWith re-checks the resolved value). tmpl(0) > 0 is the
  // product discriminator: the paired bond advances only by coupon, so
  // an instalment is mandatory. Coupling: installment iff payments;
  // paymentsRemaining counts K interior coupons + 1 final payment;
  // K >= 1 for installment orders — an installment order with zero
  // interior coupons must be posted as a bullet instead.
  val schedCommonOk =
    tmpl.size == 6 &&
    term >= 1 &&
    tmpl(0) > 0L &&
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
  // resolvedBounty * K exactly, so escrow drains to zero in lockstep
  // with the serviced checkpoints; the net-of-escrow collateral floor;
  // whole-pack bond R9 equality against the anchored grid — 6 elements
  // card-less, 10 with the card suffix (resolved numerics at bond R9
  // indices 6-9); and the structured bond R8 pack — outer size by
  // shape, element 0 the 32-byte lender-script hash with its preimage
  // revealed in var 0, element 1 == the resolved pool NFT when the
  // covenant is on, element 2 == the borrower-pinned hook hash when
  // present (hook presupposes covenant: the size-3 shape carries the
  // pool NFT at index 1). A covenant order needs exactly one collateral
  // token — the resolved pool's traded token — and escrow >= one
  // resolved bounty: maintenance protection that cannot fire even once
  // is unmatchable.
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
      // The reveal checks: br8(0) is the 32-byte blake2b256 of the
      // funder's lender script, and its preimage must be supplied in
      // ctx-ext var 0 of this input (var 1 for the hook when pinned).
      // See the context-extension contract in the header for why the
      // reveals are required.
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
  // (amount exactly 1: the terms NFT is a singleton) AND by script
  // hash, its packs are size-guarded, its attestationType must be 0,
  // and its sentinel fields resolve IN-CONTRACT to the compiled
  // defaults before the shared conformance check runs.
  val cardOk = { (go: Boolean) =>
    go &&
    CONTEXT.dataInputs.size > 0 &&
    {
      val card = CONTEXT.dataInputs(0)
      card.tokens.size >= 1 &&
      card.tokens(0)._1 == cardPin &&
      card.tokens(0)._2 == 1L &&
      // The pinned NFT proves WHICH box; this proves it is governed by
      // the refuel-only card script — a look-alike box holding the NFT
      // under a mutable guard cannot reprice a posted order.
      blake2b256(card.propositionBytes) == TERMS_BOX_HASH &&
      card.R7[Coll[Long]].isDefined &&
      card.R8[Coll[Coll[Byte]]].isDefined &&
      {
        val c7 = card.R7[Coll[Long]].get
        val c8 = card.R8[Coll[Coll[Byte]]].get
        c7.size >= 11 &&
        c8.size >= 2 &&
        // flagWord and attestationType gated to zero: no reserved bit
        // or type is matchable before its semantics exist.
        c7(9) == 0L &&
        c7(10) == 0L &&
        // Card byte fields are token ids — 32 bytes or the empty
        // sentinel, nothing else.
        (c8(0).size == 0 || c8(0).size == 32) &&
        (c8(1).size == 0 || c8(1).size == 32) &&
        // A hook is legal ONLY if the pinned card lists its hash: card
        // R8 indices 4 and up are the card's approved hook hashes,
        // frozen by the refuel-only guard. Hook and terms are audited
        // as one immutable unit — there are no free-floating hook
        // hashes, and card-less orders cannot carry hooks at all.
        (!hookPresent || c8.slice(4, c8.size).exists { (h: Coll[Byte]) =>
          h == hookHash
        }) &&
        // Free-set card numerics must be non-negative: a negative
        // bounty would flip the sign of the escrow-exactness equation
        // and inflate the net-of-escrow collateral floor; a negative
        // grace would arm instant acceleration. The clamped fields
        // below are sign-safe by construction.
        c7(0) >= 0L && c7(1) >= 0L && c7(2) >= 0L && c7(3) >= 0L &&
        {
          val bounty  = if (c7(0) == 0L) CRANK_BOUNTY else c7(0)
          val grace   = if (c7(1) == 0L) GRACE_BLOCKS else c7(1)
          // carveout and haircutKeep are OUTER bounds a card may
          // tighten, never loosen — capped at the compiled values. An
          // uncapped carveout would let a liquidator strip the
          // collateral; an inflated haircutKeep would make every
          // position price healthy and nullify the covenant.
          val carve   = if (c7(2) == 0L || c7(2) > LIQ_CARVEOUT) LIQ_CARVEOUT else c7(2)
          val haircut = if (c7(3) == 0L || c7(3) > HAIRCUT_KEEP) HAIRCUT_KEEP else c7(3)
          // Maintenance-threshold bounds in basis points of repayment:
          // the floor is 10000 (100% — a threshold below full coverage
          // is not maintenance protection) and the cap 30000 (300%).
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

  // Cancel authorization by co-spend: a contract borrower satisfies its
  // own script on a co-spent box; a P2PK borrower signs one of their
  // boxes. SELF is excluded so an order whose R4 equals the order tree
  // itself cannot self-authorize its own cancel — that would make it
  // spendable by anyone. Conjoined with the no-mint guard below.
  val borrowerAuth = INPUTS.exists { (b: Box) =>
    b.propositionBytes == borrower && b.id != SELF.id
  }

  val noLoanTokenMinted = OUTPUTS.forall { (o: Box) =>
    o.tokens.forall { (t: (Coll[Byte], Long)) => t._1 != SELF.id }
  }

  sigmaProp(matchOk || (borrowerAuth && noLoanTokenMinted))
}
