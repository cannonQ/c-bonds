{
  // =====================================================================
  // Plain Bullet Order — the minimal order
  //
  // Borrower-side loan request for a PlainBulletBond. The borrower
  // locks collateral here with the requested terms; any funder — key or
  // contract — matches it by creating the bond box and paying the
  // principal to the borrower script. The only other path is cancel,
  // authorized by borrower co-spend. No cards, no covenant, no hook, no
  // schedule, no data inputs.
  //
  // Context-extension contract (on THIS input, supplied by the funder
  // at match):
  //   var 0: the full lender ErgoTree — blake2b256 of it must equal the
  //          32-byte hash the funder writes to bond R8. The bond stores
  //          only the hash; the reveal keeps the lender script
  //          recoverable from chain history, without which a hash with
  //          no known preimage would leave the borrower unable to
  //          construct the repayment and the bond unliquidatable. A
  //          reveal proves the preimage exists — not that it is
  //          constructible as a box script. A funder revealing bytes
  //          that cannot guard a box dead-ends BOTH parties: their own
  //          principal AND the borrower's collateral (the bond has no
  //          recovery path). That is a pay-to-grief attack, priced at
  //          the full principal, accepted and documented rather than
  //          prevented.
  //
  // Registers:
  //   R4: Coll[Byte] borrower ErgoTree bytes — the full script, because
  //                  the funder builds the principal box from it; the
  //                  bond gets only blake2b256(R4)
  //   R5: Long       principal requested, nanoERG
  //   R6: Long       repayment amount, nanoERG
  //   R7: Int        term in blocks (maturity = match height + term)
  //   value + tokens: the collateral
  //
  // Loan-token provenance: on cancel, no output may carry a token whose
  // id equals SELF.id, so the loan token is mintable only through
  // matchOk; on match, a fold over ALL outputs pins its total supply at
  // exactly 1, and SELF must be the first input so the mint is genuine.
  //
  // getVar reads are context-extension, not data inputs, and the .get
  // sits behind an isDefined in the same lazy chain; there is no
  // CONTEXT.dataInputs read anywhere in this contract.
  // =====================================================================

  val borrower  = SELF.R4[Coll[Byte]].get
  val principal = SELF.R5[Long].get
  val repayment = SELF.R6[Long].get
  val term      = SELF.R7[Int].get

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

  // Maturity stamped as build height + term, MATURITY_TOL blocks of lag
  // accepted (build-vs-inclusion height drift). Window bounds, never
  // equality. m > HEIGHT + 1 is the born-liquidatable floor, and it
  // buys exactly one thing: the bond gets at least one FULL block —
  // the birth block itself — in which repayment is open and liquidation
  // is not. The earlier m > HEIGHT left m == HEIGHT + 1 stampable, i.e.
  // a bond liquidatable in the very next block. This floor does NOT
  // make a short-term order safe: the funder still picks m anywhere in
  // [HEIGHT + term - MATURITY_TOL, HEIGHT + term] AFTER seeing the
  // order, so a term-2 order is a two-block bond by construction. A
  // wide repayment window is an origination choice, not a contract
  // guarantee. Arithmetic in Long: Int addition throws on overflow, and
  // a near-MaxValue R7 must leave the order unmatchable-but-cancellable,
  // not crash every path.
  val maturityOk =
    bondBox.R7[Int].isDefined && {
      val m      = bondBox.R7[Int].get.toLong
      val target = HEIGHT.toLong + term.toLong
      m > HEIGHT.toLong + 1L &&
      m >= target - MATURITY_TOL.toLong &&
      m <= target
    }

  // Bond registers: R5 is the borrower-script hash computed from our
  // own R4; R8 is the funder's 32-byte lender-script hash, and its
  // preimage must be revealed in var 0.
  val bondRegsOk =
    bondBox.R4[Coll[Byte]].isDefined &&
    bondBox.R4[Coll[Byte]].get == SELF.id &&
    bondBox.R5[Coll[Byte]].isDefined &&
    bondBox.R5[Coll[Byte]].get == blake2b256(borrower) &&
    bondBox.R6[Long].isDefined &&
    bondBox.R6[Long].get == repayment &&
    bondBox.R8[Coll[Byte]].isDefined &&
    {
      val lh = bondBox.R8[Coll[Byte]].get
      val lv = getVar[Coll[Byte]](0)
      lh.size == 32 &&
      lv.isDefined &&
      blake2b256(lv.get) == lh
    }

  // The order's whole value (the collateral) must ride into the bond.
  // The minimum keeps the bond's liquidation floor binding
  // (MIN_ORDER_VALUE > LIQ_CARVEOUT, a cross-contract invariant) — a
  // dust order can exist, but it can only be cancelled, never matched.
  val collateralOk =
    bondBox.value >= SELF.value &&
    SELF.value >= MIN_ORDER_VALUE

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
    term >= 1 &&
    collateralOk &&
    principalOk

  // Cancel authorization by co-spend: a contract borrower satisfies its
  // own script on a co-spent box; a P2PK borrower signs one of their
  // boxes. The auth input may not be guarded by THIS order script:
  // excluding by script (not merely by box id) also blocks two
  // pathological orders whose R4 is the order tree from authorizing
  // each other's cancel. Conjoined with the no-mint guard below.
  val borrowerAuth = INPUTS.exists { (b: Box) =>
    b.propositionBytes == borrower &&
    b.propositionBytes != SELF.propositionBytes
  }

  val noLoanTokenMinted = OUTPUTS.forall { (o: Box) =>
    o.tokens.forall { (t: (Coll[Byte], Long)) => t._1 != SELF.id }
  }

  sigmaProp(matchOk || (borrowerAuth && noLoanTokenMinted))
}
