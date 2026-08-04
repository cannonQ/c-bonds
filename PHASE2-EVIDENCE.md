# Phase 2 Evidence — Successor Machinery (checkpoint crank + top-up)

Date: 2026-08-03/04. Scope locked from build-plan §3 Phase 2: the two
successor spend paths only — no price read, no covenant branch (Phase 3).
Suite green on mainnet: **6/6** (`RunPhase2`, log `phase2.log`); on-chain
ledger in `ph2TRANSACTIONS.md` (Phase 1's `TRANSACTIONS.md` untouched).

## Decisions pinned before code (all user-confirmed)

1. **Escrow at origination** — borrower pre-funds `CRANK_BOUNTY × K`,
   `K = (term − 1) / periodBlocks` (integer division; exactly the j ≥ 1
   with j·period < term). Verified end-to-end: escrow hits zero exactly
   as the crank gate goes dead; neither stranded nor short.
2. **`CRANK_BOUNTY = 0.005 ERG`** (compiled constant, W4 placeholder) —
   sized so a crank is self-funding with the bond as the tx's ONLY input:
   fee 0.0011 + keeper box 0.0039. Zero-capital keeper proven on-chain
   (T4 asserts `signedInputs.size == 1`).
3. **Advance rule** — successor `nextCheckHeight == self + periodBlocks`
   exact; clamp-as-gate, not `min()`. **Addendum (user-insisted): crank
   gate is `nextCheckHeight <= HEIGHT < maturity`** — post-maturity
   cranking of a stale checkpoint is impossible, killing the
   liquidation-delay grief; skipped crank = bounty forfeited to the
   counterparty. The explicit `nextCheck < maturity` term is subsumed by
   transitivity. Pinned by B11 (early) and B11′ (at maturity).
4. **Drain guard** — value and R9(5) move in lockstep, exact equality
   both sides; once-per-period follows from the advance. Pinned by B12a
   (value side isolated) and B12b (register side isolated). Collateral
   invariant `value − escrow` preserved across every crank (algebraic).
5. **Register mask** — one shared frozen wall (`propositionBytes`, R4,
   R5-via-propBytes, R6, R7, R8) + whole-pack R9 equality: crank compares
   against a rebuilt 6-element pack, top-up against SELF's verbatim. All
   expected values derive from SELF (V8 MED-1 held). Tokens: crank pins
   the whole collection structurally (loan-token missing/displaced/
   counterfeit all break one equality); top-up pins per-slot ids with
   ≥ amounts, ≥1 strict increase, **no new token ids**.

Also pinned: borrower self-crank permitted (T4 second crank, on-chain);
**residual escrow forfeits to the lender on liquidation** (explicit
economic choice — uncranked bounties are a transfer to the lender, not a
refund to the borrower who benefited from the gap; rides the existing
full-value sweep, zero new code). On repay the residual returns to the
borrower (T5 cleanup, on-chain).

## Contracts (compile gate + EKB two-pass, pre-dust)

| contract | bytes | header | address |
|---|---|---|---|
| ConformingBond (P2) | 566 | 0x18 | `JQUqnrfAzmQ9B6ky…osV4up` |
| ConformingOrder (P2) | 677 | 0x18 | `4GWqqM7UGycyShcp…Rq2djS` |

EKB two-pass on both revised contracts before any dust moved: **no
CRITICAL/HIGH/MEDIUM**; one intended-liveness LOW (near-maturity crank
mempool eviction — the anti-grief boundary working) + INFO items, all in
`AUDIT.md` Phase 2 section. Phase 6 re-audits the final set.

## Suite (green run, 6/6)

- **B1–B8, B11, B12, B14** — successor register wall, pre-header-pinned
  inside the crank window (A3 pattern): every single-register mutation
  (R4, R5, R6, R7, R8; all six schedule elements incl. no-advance and
  double-advance), reduced collateral, contract swap, early crank,
  at-maturity crank, bounty overdraw, escrow double-decrement, loan token
  missing, counterfeit successor token (fresh mint id == bond box id) —
  **all reject** (reduce-to-false / unprovable residual). Honest twins
  reduce. One test per register, each asserting its specific failure.
- **B9, B10** — top-up net-zero, net-negative, schedule-touching, and
  crank-mimicking top-ups all reject with clean reduce-to-false
  (borrower-signed path).
- **B0** — origination: zero-escrow-claim order unmatchable (a/89-crank
  grid), wrong grid stamps rejected (two-periods-in; Phase-1-shape
  `nextCheck = maturity`), sub-floor net collateral behind a fat escrow
  rejected; honest match twin reduces. Orders cancelled, funds recovered.
- **T4** — keeper cranks checkpoint 1 signaturelessly with the bond as
  sole input (zero capital); borrower self-cranks checkpoint 2. Both
  successors verified on-chain: exact value/escrow deltas, exact one-
  period advance, frozen elements intact, loan token at slot 0 ×1.
- **T5** — borrower top-up mid-period (schedule frozen verbatim, value
  strictly up), then repay of the twice-cranked+topped bond — the Phase 1
  exit wall holding across the successor chain; receipt verified;
  residual escrow back to the borrower.
- **B13** — double-crank race: conflicting crank rejected by the mempool
  (400 double-spend), winner confirmed, **singleton invariant asserted on
  chain state** (exactly one unspent bond box carries the loan token),
  loser's bot retry against the advanced successor cleanly rejected.
- **B16** — token-collateral wall: crank dropping collateral tokens,
  token slots reordered, top-up withholding tokens — all reject; token
  growth 500→600 via existing id reduces (twin).

## JitCost per path (500K/input budget; full log in JITCOST.md)

| path | JitCost | % of budget |
|---|---|---|
| match (ERG collateral, full schedule validation) | 134,964 | 27.0% |
| match (token collateral) | 135,397 | 27.1% |
| crank (keeper, signatureless) | 127,696 | 25.5% |
| self-crank (borrower) | 127,696 | 25.5% |
| top-up (borrower-signed) | 130,184 | 26.0% |
| top-up (token growth, local twin) | 133,211 | 26.6% |
| repay of a Phase 2 bond | 130,064–130,464 | ~26.1% |

Phase 1 match was 133,501 — the entire escrow/grid validation added
~1.5K. Repay rose from 52,697 (Phase 1 tree) to ~130K because the repay
path now evaluates against the 566-byte four-path tree; all paths sit at
about a quarter of budget, ample headroom for Phase 3's swap-sim.

## Harness lessons (this phase's tuition)

- **Token-welding starves the selector**: a repay change welds token
  collateral onto the borrower's ERG; the token-free selector then skips
  it. Run token-collateral tests LAST in a suite (B16 moved; run 1 went
  2/6 from this alone — walls were green, funding wasn't).
- **Recycle burn entries must aggregate per token id**: the same id split
  across boxes makes appkit's burn map drop all but one amount and demand
  an impossible change box (run-2 crash; fixed in `Recycle.sweep`).
- **Long mainnet runs: bare `java` off the exported classpath, modest
  heap** — the sbt(1 GB)+forked-JVM stack got OOM-killed mid-run on a
  busy box, and the pipeline masked it as exit 0 (`tail` exits clean).
  The killed run's live bond was recovered via `bonds.RepayBond` (box
  `1024d4d8…aeab`, orphan-recovery repay in JITCOST.md).
- Far-future pre-headers (~716 blocks ahead) work fine for local
  window-pinning; the whole register wall runs without waits.

## Phase 1 re-run (shared load-bearing wall) — GREEN

Full `RunPhase1` (T1–T3, A1–A9) against the Phase 2 contracts — scheduled
bonds now carry real periods; the bullet degenerate is `period == term`
(K = 0, zero escrow, dead crank gate). Log: `phase1-rerun.log`.

- Batch run: **10/11** — T1–T3 and A1–A8 all green. Every Phase 1
  invariant holds on the four-path tree: exits, receipts, boundaries,
  token conservation, forged-bond provenance (A6 also implicitly proves
  garbage schedule packs evaluate harmlessly through the crank vals),
  and the HIGH-O1 cancel-mint guard.
- A9's batch attempt aborted on LENDER funding (needed 0.0152 clean,
  had 0.013 after eight matches), not on logic. After a keeper→lender
  rebalance and recovery of its stranded order, **A9 re-ran standalone
  and passed** (over-mint reduce-to-false; cleanup cancel confirmed) —
  same targeted-rerun precedent as Phase 1's `RunHardening`.
- Net: all 11 Phase 1 tests green on the Phase 2 contract set.
- Liquidation JitCost on the new tree: 130,070 (was 52K-class on the
  two-path Phase 1 tree; same ~26%-of-budget band as every other path).
- Funding note for future full runs: a complete Phase 1 suite needs the
  LENDER at ≥ ~0.14 ERG clean (nine originations; mid-run repayments
  accumulate in the vault, not the lender wallet). Recycle currently
  tops up only the borrower — worth a LENDER_TARGET knob later.
