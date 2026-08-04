{
  // =====================================================================
  // Conforming Open Order — Phase 2
  //
  // Borrower-side loan request. The borrower locks collateral here with
  // the requested terms; any funder (key or contract) matches it by
  // creating a conforming bond box and paying the principal to the
  // borrower. Semantics descend from SigmaFi's order/bond pair (prior
  // art: SigmaBonds); original implementation.
  //
  // Phase 2: checkpoints are universal. The order box carries the escrow
  // on top of the collateral (value = collateral + escrowBalance) and the
  // match validates the full checkpoint grid: escrow sized to exactly one
  // CRANK_BOUNTY per interior checkpoint, first nextCheckHeight anchored
  // to (maturity - term) so the 5-block maturity tolerance shifts the
  // grid and the maturity clamp together (slack-invariant, no drift).
  //
  // Registers:
  //   R4: SigmaProp   borrower
  //   R5: Long        principal requested, nanoERG
  //   R6: Long        repayment amount, nanoERG
  //   R7: Int         term in blocks (maturity = match height + term)
  //   R8: Coll[Byte]  reserved, empty in Phase 2 (no register gaps)
  //   R9: Coll[Long]  schedule template [installment, periodBlocks,
  //         paymentsRemaining, ignored-at-order, maintenanceThresholdBps,
  //         escrowBalance] — element 3 (nextCheckHeight) is computed at
  //         match; installment == 0 until Phase 4 (bullets only)
  //   value + tokens: the collateral, plus escrowBalance in the value
  //
  // Paths:
  //   Match   OUTPUTS(0) is a bond at BOND_SCRIPT_HASH carrying this box's
  //           full collateral and the freshly minted loan token
  //           (id == SELF.id). OUTPUTS(1) pays principal to the borrower.
  //   Cancel  borrower signature, PLUS no output may carry a token whose id
  //           equals SELF.id (EKB audit HIGH-O1): without this, a borrower
  //           could cancel-and-mint a provenance-valid loan token for a
  //           loan that never passed match validation, corrupting the
  //           registry's conforming rule and any receipt-based history.
  //           With the guard, a token bearing this order's id can only be
  //           created by a transaction satisfying matchOk.
  //
  // Loan-token provenance: a token whose id equals SELF.id can only be
  // minted in a transaction where SELF is INPUTS(0) (Ergo token-mint
  // rule). That makes this order box the bond's unforgeable provenance:
  // the registry-side conforming rule is "loan token id resolves to a box
  // at the conforming order address".
  // =====================================================================

  val borrower  = SELF.R4[SigmaProp].get
  val principal = SELF.R5[Long].get
  val repayment = SELF.R6[Long].get
  val term      = SELF.R7[Int].get
  val tmpl      = SELF.R9[Coll[Long]].get

  val bondBox = OUTPUTS(0)

  val bondScriptOk = blake2b256(bondBox.propositionBytes) == BOND_SCRIPT_HASH

  // Freshly minted loan token at slot 0: exactly 1 unit, id == SELF.id,
  // and SELF is the first input so the mint is genuine.
  val loanTokenOk =
    INPUTS(0).id == SELF.id &&
    bondBox.tokens.size == SELF.tokens.size + 1 &&
    bondBox.tokens(0)._1 == SELF.id &&
    bondBox.tokens(0)._2 == 1L

  // Loan-token supply cap (EKB second-pass MED-O9): the Ergo mint rule
  // lets the funder set ANY total supply for id == SELF.id when SELF is
  // INPUTS(0), so pinning only the bond's unit is not enough — the funder
  // could route extra units elsewhere. Require the total across ALL outputs
  // to be exactly 1, so the loan token is a true singleton identity across
  // the loan's whole life (load-bearing for the Phase 2/3 successor and
  // registry keying). Mirrors the Etcha double-exercise conservation fold.
  val loanId = SELF.id
  val loanTokenSupplyOne =
    OUTPUTS.fold(0L, { (acc: Long, o: Box) =>
      o.tokens.fold(acc, { (a: Long, t: (Coll[Byte], Long)) =>
        if (t._1 == loanId) a + t._2 else a
      })
    }) == 1L

  // Every collateral token carried into the bond in full
  val collateralTokensOk = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    bondBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  // Maturity is stamped by the builder as buildHeight + term; accept up to
  // MATURITY_TOL blocks of build-to-validation lag. Window bounds, never
  // equality.
  val maturityOk =
    bondBox.R7[Int].isDefined && {
      val m = bondBox.R7[Int].get
      m >= HEIGHT + term - MATURITY_TOL && m <= HEIGHT + term
    }

  val bondRegsOk =
    bondBox.R4[Coll[Byte]].isDefined &&
    bondBox.R4[Coll[Byte]].get == SELF.id &&
    bondBox.R5[SigmaProp].isDefined &&
    bondBox.R5[SigmaProp].get.propBytes == borrower.propBytes &&
    bondBox.R6[Long].isDefined &&
    bondBox.R6[Long].get == repayment &&
    bondBox.R8[Coll[Byte]].isDefined &&
    bondBox.R8[Coll[Byte]].get.size >= 1

  // Schedule pack (Phase 2): template range-validated, escrow sized
  // exactly, grid anchored. The number of crankable checkpoints is
  //   K = (term - 1) / periodBlocks    (integer division)
  // — exactly the j >= 1 with j*periodBlocks < term, so after K cranks
  // the bond's escrow hits zero as its crank gate goes dead: the escrow
  // is neither stranded nor short. periodBlocks >= term degenerates to
  // K = 0 (a Phase-1-shape bullet: no checkpoints, zero escrow).
  // periodBlocks >= MIN_PERIOD also guards the division; every tmpl
  // index sits behind tmpl.size == 6 in the same lazy chain so a
  // malformed template stays cancellable.
  // The collateral floor (EKB audit LOW-O3) applies NET of escrow, so a
  // bond cannot carry dust collateral behind a fat escrow.
  val schedOk =
    tmpl.size == 6 &&
    tmpl(0) == 0L &&
    tmpl(2) == 0L &&
    tmpl(1) >= MIN_PERIOD &&
    tmpl(4) >= 0L && tmpl(4) <= 1000000L &&
    term >= 1 &&
    tmpl(5) == CRANK_BOUNTY * ((term.toLong - 1L) / tmpl(1)) &&
    SELF.value - tmpl(5) >= MIN_ORDER_VALUE &&
    bondBox.R9[Coll[Long]].isDefined &&
    bondBox.R7[Int].isDefined && {
      val s = bondBox.R9[Coll[Long]].get
      val m = bondBox.R7[Int].get.toLong
      s == Coll(tmpl(0), tmpl(1), tmpl(2),
                (m - term.toLong) + tmpl(1),
                tmpl(4), tmpl(5))
    }

  // Full order value (collateral + escrow) must ride into the bond; the
  // net-of-escrow collateral floor lives in schedOk behind the size guard.
  val collateralOk =
    bondBox.value >= SELF.value

  val principalOk =
    OUTPUTS.size >= 2 && {
      val principalBox = OUTPUTS(1)
      principalBox.propositionBytes == borrower.propBytes &&
      principalBox.value >= principal
    }

  val matchOk =
    bondScriptOk &&
    loanTokenOk &&
    loanTokenSupplyOne &&
    collateralTokensOk &&
    maturityOk &&
    bondRegsOk &&
    schedOk &&
    collateralOk &&
    principalOk

  // Cancel guard (EKB audit HIGH-O1): the loan token must be impossible to
  // mint outside the match path.
  val noLoanTokenMinted = OUTPUTS.forall { (o: Box) =>
    o.tokens.forall { (t: (Coll[Byte], Long)) => t._1 != SELF.id }
  }

  sigmaProp(matchOk) || (borrower && sigmaProp(noLoanTokenMinted))
}
