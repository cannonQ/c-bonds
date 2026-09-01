{
  // =====================================================================
  // Plain Bullet Bond — the minimal product
  //
  // A live loan with no covenant, no schedule, no hook: collateral
  // (value + tokens) locked until the borrower repays or, past
  // maturity, anyone liquidates to the lender. Two paths, no successor
  // machinery, no data inputs on any path, no context variables.
  //
  // Registers:
  //   R4: Coll[Byte] originating order box id (== loan token id) —
  //                  provenance only: written and enforced by the
  //                  order, never read by this contract (the loan
  //                  token id is read positionally from tokens(0))
  //   R5: Coll[Byte] blake2b256 of the borrower's ErgoTree
  //   R6: Long       repayment amount, nanoERG
  //   R7: Int        maturity height
  //   R8: Coll[Byte] blake2b256 of the lender's ErgoTree
  //   tokens(0): (loanTokenId, 1) — minted at match; id == R4 value
  //   tokens(1..): optional token collateral
  //
  // R5 and R8 hold script HASHES, not script bytes, so bond box size is
  // independent of counterparty script size (boxes cap at 4 KB). The
  // lender payment is verified by hashing the OUTPUT's own
  // propositionBytes; borrower authorization is by CO-SPEND — some
  // input's propositionBytes must hash to R5, and spending that input
  // satisfies its own script, which is the transaction's authorization
  // (a P2PK borrower signs, a contract or DAO borrower authorizes
  // through its own spending logic). The matching order requires the
  // lender preimage revealed in its context extension at match, so the
  // payment destination is always recoverable from chain history.
  //
  // Paths:
  //   Repay      borrower co-spend, any height: OUTPUTS(0) pays
  //              >= repayment to the lender with the receipt, and every
  //              collateral token returns to the borrower.
  //   Liquidate  signatureless, HEIGHT >= maturity: OUTPUTS(0) delivers
  //              the collateral to the lender, minus at most the
  //              compiled carve-out that funds the liquidation
  //              transaction itself.
  // Both paths may be valid past maturity; either outcome is correct
  // and the first confirmation wins.
  //
  // Rules: height windows are >= open, never equality; fallible reads
  // on foreign boxes are guarded so a non-conforming transaction
  // reduces cleanly to false; every branch is Boolean under one
  // sigmaProp. All reads below touch only fields that always exist —
  // there is no data-input or context-variable surface in this
  // contract at all.
  // =====================================================================

  val lenderHash = SELF.R8[Coll[Byte]].get
  val repayment  = SELF.R6[Long].get
  val maturity   = SELF.R7[Int].get
  val borrower   = SELF.R5[Coll[Byte]].get
  val loanTokenId = SELF.tokens(0)._1

  val exitBox = OUTPUTS(0)

  val toLender = blake2b256(exitBox.propositionBytes) == lenderHash

  // Receipt: binds the exit to THIS spend (R4 == the spent box id) and
  // carries the loan token, so one transaction cannot satisfy two bonds
  // and the loan token's history is the loan's history.
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
  // responsibility. Per-token check is >=, and different tokens may
  // land in different outputs; the empty slice of an ERG-only bond is
  // vacuously true.
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
    toLender &&
    exitBox.value >= repayment &&
    receiptOk &&
    collateralToBorrower

  val allTokensDelivered = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    exitBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  // The carve-out floor is binding for every conforming bond because
  // the order enforces value >= MIN_ORDER_VALUE > LIQ_CARVEOUT — a
  // cross-contract invariant; keep it if either constant changes.
  val liquidateOk =
    HEIGHT >= maturity &&
    toLender &&
    exitBox.value >= SELF.value - LIQ_CARVEOUT &&
    receiptOk &&
    allTokensDelivered

  // Borrower authorization by co-spend: reads only INPUTS, which always
  // exists — a total expression, safe under eager hoisting. A
  // trivially-satisfiable borrower script opens repay to anyone: the
  // borrower's own choice, documented rather than prevented.
  val borrowerAuth = INPUTS.exists { (b: Box) =>
    blake2b256(b.propositionBytes) == borrower
  }

  sigmaProp(liquidateOk || (repayOk && borrowerAuth))
}
