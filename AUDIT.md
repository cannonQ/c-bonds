# EKB Two-Pass Audit — Phase 1 contracts (2026-08-03, pre-deployment)

Early pass, run before any dust moved. The Phase 6 audit reruns both
passes on the final contract set; this document records the pre-deployment
baseline and the fixes it forced.

Method: EKB MCP `audit_contract` (Pass 1) then `audit_verify` (Pass 2,
independent-reviewer framing) per the two-pass methodology, with
intent descriptions supplied for all three contracts.

## ConformingBond.es — 9/10, no changes required

- No CRITICAL/HIGH/MEDIUM. Both exits enumerated; no OR-branch bypass;
  receipt binding makes two bonds sharing OUTPUTS(0) unsatisfiable (one
  bond per tx); token conservation on liquidation via forall/exists.
- **LOW-B1** carve-out dominance: if bond value <= LIQ_CARVEOUT the
  liquidation value floor stops binding. **Resolved at the order side**
  (MIN_ORDER_VALUE, below) so every conforming bond keeps the invariant.
- **LOW-B2** storage rent: bonds unspent ~4 years become miner-claimable;
  loan terms must stay far below the rent horizon (spec note).
- INFO: repay stays open post-maturity (races liquidation, first
  confirmation wins — intended, matches SigmaFi; pinned by test); eager
  SELF reads make forged malformed boxes unspendable (forger's loss);
  Phase 2+ registry must treat a receipt as the box created in the bond's
  spending tx, not any box holding the loan token.

## ConformingOrder.es — 6.5/10 as written → fixes applied

- **HIGH-O1 (confirmed, fixed): cancel-path loan-token forgery.** The
  bare `|| borrower` cancel arm let a borrower spend their own order as
  INPUTS(0) and mint a token with id == the order box id — a
  provenance-valid loan token for a loan that never passed match
  validation (no principal, arbitrary terms, self as lender). This
  corrupted the registry's conforming rule and any future receipt-based
  credit history (free farming of fake "repaid" outcomes).
  **Fix:** cancel arm is now `borrower && sigmaProp(noLoanTokenMinted)`
  where no output may carry a token with id == SELF.id. A loan token can
  now only be created by a transaction satisfying matchOk, making the
  registry rule sound on-chain. Pinned by adversarial test
  **A8_CancelMintForgery** (forged mint rejects; plain-cancel twin passes).
- **LOW-O3 (fixed):** no minimum collateral floor. matchOk now requires
  `SELF.value >= MIN_ORDER_VALUE` (0.01 ERG, > 3× LIQ_CARVEOUT), closing
  LOW-B1 for every conforming bond. Sub-floor orders remain cancellable.
- **LOW-O2 (accepted, documented):** maturity stamp tolerance is 5 blocks
  (OptionReserveV8 precedent); a match tx unconfirmed >5 blocks is evicted
  and must be rebuilt. Liveness-only.
- INFO: funder can shorten effective term by at most the 5-block
  tolerance (inherent); schedule template elements 1,2,4,5 are pass-through
  in Phase 1 (installment pinned to 0) — the Phase 2 order revision must
  range-validate them; eager SELF reads brick self-created malformed
  orders on both paths (creator's loss; harness always writes all six).
- Post-fix expected rating: 9/10 (Phase 6 rerun will confirm).

## MinimalLenderVault.es — 10/10 for its Phase 1 role

- Single owner-signature path; the MIN_OUTS clause is inert by
  construction (test fixture + non-P2PK tree). INFO only: owner can burn
  receipt tokens by careless sweeping; the Phase 5 vault replaces this
  contract with explicit sweep rules.

## Second pass — re-audit of the fixed order (2026-08-03, hardening)

Ran the two-pass again on ConformingOrder.es *after* the HIGH-O1 + LOW-O3
fixes were applied, to confirm the fixes were complete and introduced
nothing new. Result: cancel guard and collateral floor both confirmed
sound; one new finding.

- **MED-O9 (confirmed, fixed): loan-token supply not capped at 1 on the
  match path.** `loanTokenOk` pinned the *bond's* unit at 1, but Ergo's
  mint rule lets the funder (with the order box as INPUTS(0)) set any total
  supply for id == order id and route surplus units to their own outputs.
  Benign for Phase 1 (self-contained bond lifecycle; stray units are inert
  dust), but the loan token is the stable per-loan identity that Phase 2
  successors and Phase 3 registry/NAV key on, so the singleton guarantee is
  load-bearing. **Fix:** `loanTokenSupplyOne` — total loan-token supply
  across all OUTPUTS must equal 1 (Etcha double-exercise conservation-fold
  pattern), added to `matchOk`. Pinned by adversarial test
  **A9_LoanTokenOverMint** (over-mint → reduce-to-false; happy match
  unaffected). Verified on mainnet via `runMain bonds.RunHardening` (2/2).
  Cost: order tree 554→620 B; match JitCost 119,635→133,501 (26.7% of
  budget). Cancel guard confirmed airtight (a token with id == order id
  must land in some output to exist, and the cancel arm forbids it in all
  outputs). Post-fix order rating 9.5/10.

## Tree sizes after fixes

| contract | bytes | header |
|---|---|---|
| ConformingBond | 181 | 0x18 (size bit set) |
| ConformingOrder | 620 | 0x18 (size bit set) |

---

# EKB Two-Pass Audit — Phase 2 contracts (2026-08-03, pre-deployment)

Run on the revised pair (bond + successor machinery, order + escrow/grid
validation) after the compile gate and before any Phase 2 dust moved.
Same two-pass method; intent descriptions carried the five pinned §4
decisions plus the HEIGHT < maturity addendum and the
residual-escrow-to-lender choice so both were audited as intent, not
accident.

## ConformingBond.es (Phase 2) — 9.5/10, no changes required

- No CRITICAL/HIGH/MEDIUM. All four paths enumerated; crank/liquidate
  height-disjoint (< maturity vs >= maturity, no gap or overlap — the
  explicit nextCheck < maturity term is correctly subsumed by
  transitivity); repay/top-up disjoint unless the funder self-selects
  R8 == the bond tree (funder's own foot-gun, Phase 1 INFO).
- Two-bonds-one-output unsatisfiable on every path pair: exits bind the
  receipt to SELF.id; successors bind the loan-token singleton (crank:
  whole-collection equality; top-up: per-slot id equality), and two bonds
  can never share a singleton. Cross-pair shapes also traced closed.
- Lockstep drain guard confirmed: value AND R9(5) must both move by
  exactly one CRANK_BOUNTY; divergence in either direction is
  unspendable. Collateral (value − escrow) preservation across cranks
  follows algebraically.
- **LOW-P2-1 (liveness, intended):** a crank submitted within ~1–2
  blocks of maturity can be mempool-evicted when HEIGHT crosses
  maturity (CleanupWorker). This is the anti-grief semantics working;
  test bonds keep the final window wide (term ≡ 0 mod period).
- INFO: forged non-conforming bonds (period 0, absurd registers,
  self-funded escrow) can only drain or brick themselves — no victim;
  conforming bonds cannot reach those states (order-side pins).
- INFO: any signatureless spender (crank/liquidate) can mint an inert
  token with id == the bond's box id; provenance-harmless (resolves to
  the bond address, not the order address). Existed on the Phase 1
  liquidation path too.
- INFO: exit receipts bind R4 to the FINAL successor's box id; loan
  identity across the successor chain is the loan token id (== original
  order id, preserved in R4). Registry rule unchanged.
- INFO: minimum top-up increment is 1 nanoERG — borrower-paid churn
  only; every churn strictly improves the lender's recovery.

## ConformingOrder.es (Phase 2 revision) — 9.5/10, no changes required

- No CRITICAL/HIGH/MEDIUM. Phase 2 delta is a strict tightening of the
  match path; no new spend paths; cancel arm (HIGH-O1 guard) and the
  MED-O9 supply-1 fold untouched.
- Division in the escrow formula guarded by periodBlocks >= MIN_PERIOD
  earlier in the same lazy && chain; K-formula overflow unreachable
  (bounded by Int term / MIN_PERIOD × CRANK_BOUNTY ≈ 2.7e15 << Long.Max).
  K edge cases verified: period == term and period > term both degenerate
  to K = 0 zero-escrow bullets with a dead crank gate.
- Grid anchoring ((maturity − term) + period) confirmed slack-invariant
  under the 5-block maturity tolerance.
- Net-of-escrow floor closes the fat-escrow/dust-collateral shape and
  guarantees the bond value really contains the claimed escrow.
- Bricking posture preserved: every tmpl index (including the floor's
  tmpl(5) read) sits behind tmpl.size == 6 in schedOk's lazy chain —
  malformed templates are unmatchable but stay cancellable.
- Phase 1 INFO closed: schedule elements 1 and 2 now validated
  (installment == 0, paymentsRemaining == 0), element 4 range-checked;
  element 3 remains ignored-at-order by design (superseded at match).
- VERIFICATION FINDING (INFO): term has no upper bound — term near
  Int.MaxValue would throw inside maturityOk's isDefined-guarded block,
  making the order unmatchable (cancel unaffected; creator's loss).
  Revisit at Phase 6 whether a MAX_TERM constant is worth the bytes.
- INFO: thresholdBps range [0, 1e6] is sanity-only; Phase 3 must
  validate economically. INFO: escrow coherence depends on CRANK_BOUNTY
  compiling identically into both contracts (single Scala constant
  feeds both — deployment discipline, not an on-chain flaw).

## Tree sizes (Phase 2, compile gate 2026-08-03)

| contract | bytes | header |
|---|---|---|
| ConformingBond | 566 | 0x18 (size bit set) |
| ConformingOrder | 677 | 0x18 (size bit set) |
