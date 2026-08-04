{
  // =====================================================================
  // Conforming Bond — Phase 2 (bullet loans, script lender, successors)
  //
  // Phase 1 core (repay/liquidate) is unchanged. Phase 2 adds the
  // successor machinery: a signatureless checkpoint crank and a
  // borrower-signed collateral top-up, both recreating the bond at
  // OUTPUTS(0) behind a shared register mask that freezes everything
  // except the path's permitted deltas.
  //
  // Registers (layout locked in conforming-bond-build-plan.md §2):
  //   R4: Coll[Byte]  originating order box id (== loan token id)
  //   R5: SigmaProp   borrower
  //   R6: Long        repayment amount, nanoERG
  //   R7: Int         maturity height
  //   R8: Coll[Byte]  lender ErgoTree bytes
  //   R9: Coll[Long]  schedule pack [installment, periodBlocks,
  //         paymentsRemaining, nextCheckHeight, maintenanceThresholdBps,
  //         escrowBalance]
  //   tokens(0): (loanTokenId, 1), minted at match, id == R4 value
  //
  // Paths:
  //   Repay      borrower-signed, any height. OUTPUTS(0) pays >= R6 to the
  //              R8 script and carries the settlement receipt.
  //   Liquidate  signatureless, HEIGHT >= maturity. OUTPUTS(0) delivers all
  //              collateral minus LIQ_CARVEOUT to the R8 script. Residual
  //              escrow rides along to the lender: forfeiture by the
  //              defaulting borrower (deliberate economic choice, Phase 2
  //              sign-off — uncranked bounties become the lender's, not a
  //              refund to the borrower who benefited from the gap).
  //   Crank      signatureless, nextCheckHeight <= HEIGHT < maturity.
  //              OUTPUTS(0) is the successor: same script, same tokens,
  //              R4-R8 frozen, schedule advanced by exactly one period and
  //              escrowBalance down by exactly CRANK_BOUNTY, box value down
  //              by the same CRANK_BOUNTY in lockstep. The freed bounty is
  //              deliberately unconstrained — the cranker routes it (fee +
  //              own box), so a keeper needs no capital and the borrower
  //              may self-crank. HEIGHT < maturity keeps crank and
  //              liquidate disjoint by height: a stale checkpoint can never
  //              be cranked to delay a liquidation; a skipped crank is a
  //              bounty forfeited to the counterparty.
  //   Top-up     borrower-signed, any height. OUTPUTS(0) is the successor:
  //              same script, R4-R9 frozen verbatim (schedule untouched),
  //              strictly more collateral — ERG and/or existing token
  //              amounts; no new token ids (unpriceable by the Phase 3
  //              valuation, attack surface for zero benefit). Collateral
  //              removal is never permitted.
  //
  // Checkpoint grid: nextCheckHeight starts at (maturity - term) + period
  // (order contract) and advances by exact period deltas here. The escrow
  // pre-funds one bounty per interior checkpoint, so the crank gate goes
  // dead exactly as escrowBalance reaches zero; period >= term degenerates
  // to a Phase-1-shape bullet (no checkpoints, zero escrow).
  //
  // One bond per tx: two bonds cannot share one successor or exit box —
  // the loan-token singleton (and on exits the receipt's R4 == SELF.id)
  // can only match one input.
  //
  // Height rule: windows are >= open / < close, never equality.
  // Type rule: every branch is Boolean; sigma composition happens once at
  // the top level. Fallible reads on foreign boxes are isDefined-guarded so
  // rejection is a clean reduce-to-false, not a crash.
  // =====================================================================

  val lenderScript = SELF.R8[Coll[Byte]].get
  val repayment    = SELF.R6[Long].get
  val maturity     = SELF.R7[Int].get
  val borrower     = SELF.R5[SigmaProp].get
  val sched        = SELF.R9[Coll[Long]].get
  val loanTokenId  = SELF.tokens(0)._1

  val exitBox = OUTPUTS(0)

  val toLender = exitBox.propositionBytes == lenderScript

  val receiptOk =
    exitBox.R4[Coll[Byte]].isDefined &&
    exitBox.R4[Coll[Byte]].get == SELF.id &&
    exitBox.tokens.exists { (t: (Coll[Byte], Long)) =>
      t._1 == loanTokenId && t._2 == 1L
    }

  val repayOk =
    toLender &&
    exitBox.value >= repayment &&
    receiptOk

  // Liquidation must deliver every token the bond holds (collateral tokens
  // and the loan token itself) — value alone is not enough.
  val allTokensDelivered = SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
    exitBox.tokens.exists { (o: (Coll[Byte], Long)) =>
      o._1 == t._1 && o._2 >= t._2
    }
  }

  val liquidateOk =
    HEIGHT >= maturity &&
    toLender &&
    exitBox.value >= SELF.value - LIQ_CARVEOUT &&
    receiptOk &&
    allTokensDelivered

  // ------------------- Phase 2: successor machinery -------------------

  val periodBlocks = sched(1)
  val nextCheck    = sched(3)
  val escrow       = sched(5)

  // Frozen wall shared by both successor paths: same contract, R4-R8
  // byte-identical (R5 via propBytes — raw SigmaProp equality is not a
  // reliable comparison), R9 presence guarded here so each path's pack
  // check is a clean read. Every expected value derives from SELF, never
  // from the successor's own shape (V8 MED-1 discipline).
  val succFrozen =
    exitBox.propositionBytes == SELF.propositionBytes &&
    exitBox.R4[Coll[Byte]].isDefined &&
    exitBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get &&
    exitBox.R5[SigmaProp].isDefined &&
    exitBox.R5[SigmaProp].get.propBytes == borrower.propBytes &&
    exitBox.R6[Long].isDefined &&
    exitBox.R6[Long].get == repayment &&
    exitBox.R7[Int].isDefined &&
    exitBox.R7[Int].get == maturity &&
    exitBox.R8[Coll[Byte]].isDefined &&
    exitBox.R8[Coll[Byte]].get == lenderScript &&
    exitBox.R9[Coll[Long]].isDefined

  // Crank: the only permitted deltas are the schedule's two elements —
  // compared as one whole rebuilt pack, never element-by-element flags —
  // and the box value down by exactly one bounty in lockstep with the
  // escrow register (divergence in either direction is unspendable).
  // escrow >= CRANK_BOUNTY is unreachable for a conforming bond (exact
  // pre-funding), kept as a defensive underflow guard.
  val crankOk =
    HEIGHT.toLong >= nextCheck &&
    HEIGHT < maturity &&
    escrow >= CRANK_BOUNTY &&
    succFrozen &&
    exitBox.value == SELF.value - CRANK_BOUNTY &&
    exitBox.tokens == SELF.tokens &&
    exitBox.R9[Coll[Long]].get == Coll(
      sched(0), sched(1), sched(2),
      nextCheck + periodBlocks,
      sched(4), escrow - CRANK_BOUNTY)

  // Top-up: same token ids in the same slots, every amount >=, and the
  // whole strictly greater somewhere (net-zero or negative "top-ups" are
  // unspendable); schedule pack frozen verbatim.
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
    succFrozen &&
    exitBox.value >= SELF.value &&
    tokensGrown &&
    strictlyMore &&
    exitBox.R9[Coll[Long]].get == sched

  sigmaProp(liquidateOk || crankOk) || (borrower && sigmaProp(repayOk || topUpOk))
}
