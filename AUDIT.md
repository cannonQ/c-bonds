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

---

# EKB Two-Pass Audit — Phase 3 contracts (2026-08-04, pre-deployment)

Run on the revised pair (bond + covenant/cure/acceleration, order +
threshold-range/collateral-binding) after the compile gate and before any
Phase 3 dust moved. Intent descriptions carried all six pinned §4
decisions including the cure-gate limbo-trap fix (cure = in-cure +
post-cure-healthy, NO deadline; deadline gates acceleration only) so the
design change was audited as intent, not accident. JitCost was measured
BEFORE the audit via local reduce against the live pool (all four
covenant shapes 202,794–205,372; ~41% of the 500K budget).

## ConformingBond.es (Phase 3) — 9.5/10, no changes required

- No CRITICAL/HIGH/MEDIUM. All six paths enumerated; signatureless arm
  branches pairwise disjoint (crank vs accelerate by sign of sched(3);
  both vs liquidate by HEIGHT < maturity vs >=). The crank verdict is a
  single whole-pack equality against if(healthy) advancePack else
  curePack, both derived exclusively from SELF + the NFT-authenticated
  pool — the keeper cannot choose the branch (V8 MED-1 held on all three
  successor pack shapes: advance, cure-enter, cure-restore).
- Data-input trust boundary (first in the project): dataInputs(0)
  authenticated by POOL_NFT singleton at tokens(0), index hard-pinned to
  0, collateral token bound to pool.tokens(2)._1, R4 fee isDefined-
  guarded, pool shape pinned (tokens.size == 3). Stale pool boxes are
  spent boxes and cannot be data inputs — singleton => freshness free.
- BigInt sim: worst-case adversarial magnitude ~1.6e66 << 2^255; no
  BigInt.toLong/.toBytes anywhere. thresholdBps == 0 short-circuits
  before any pool read (Phase 1/2 bonds stay data-input-free).
- INFO-1 (document + test): a failed checkpoint whose deadline
  -(sched(3)) >= maturity has an EMPTY acceleration window — the bond
  rides to plain liquidation at maturity (strictly easier for the
  lender; no harm). Pin by test.
- INFO-2: forged bonds with absurd registers (negative threshold =
  always-healthy; Long.MinValue nextCheck = cure/accelerate arithmetic
  throw) only brick or drain themselves; order-side pins keep conforming
  bonds out of these states. Existing posture.
- INFO-3: a late cure restores nextCheck = checkpoint + period which may
  be far below HEIGHT — the successor is then crankable in immediate
  succession until the grid catches up, one bounty per grid point
  (catch-up, not drain; K-bound preserved).
- VERIFICATION FINDINGS (Pass 2): deadline-boundary (HEIGHT ==
  -sched(3)) is a cure-vs-accelerate race resolved first-confirm-wins by
  design — pin by test; short-period (MIN_PERIOD 4 < GRACE 10)
  interaction traced sound (grid points inside a cure window are simply
  the next restore targets; no bounty stranding in any exit:
  repay -> borrower, liquidate/accelerate -> lender, catch-up -> keeper).

## ConformingOrder.es (Phase 3 revision) — 9.5/10 after one fix

- Delta audited: the tmpl(4) clause. Cancel path untouched (clause lives
  in schedOk behind tmpl.size == 6; wrong-collateral covenant orders are
  unmatchable but cancellable). Slot placement proof: covenant order has
  exactly 1 token, loanTokenOk forces bond.tokens.size == 2 with the
  loan token at slot 0, so RSN necessarily lands at bond slot 1 —
  exactly what the bond's poolDataOk reads.
- **LOW-P3-O1 (confirmed, fixed): covenant bullet.** threshold in
  [10000,30000] with periodBlocks >= term gave K = 0, zero escrow and
  nextCheck >= maturity: crank gate dead by height, covenant NEVER
  tested, cure/acceleration unreachable — maintenance protection that
  cannot fire, the same trap class the collateral binding closes.
  **Fix:** covenant branch now also requires tmpl(5) >= CRANK_BOUNTY;
  with the exact escrow equality in the same chain this forces K >= 1
  (at least one live checkpoint). Bullets stay valid at threshold 0.
  Cost: order tree 748 -> 765 B. Pin by adversarial test (covenant-
  bullet order unmatchable; threshold-0 bullet twin matchable).
- INFO: ERG-only covenant orders (no token) are unmatchable but
  cancellable — intended: par-ERG collateral cannot deteriorate, so a
  covenant on it is dead weight by construction.
- INFO: COLLATERAL_TOKEN_ID hard-couples this revision to one collateral
  class (RSN) — matches the pinned single-pool Phase 3 scope; the
  facility whitelist generalizes later.

## Tree sizes (Phase 3, compile gate 2026-08-04)

| contract | bytes | header |
|---|---|---|
| ConformingBond | 1076 | 0x18 (size bit set) |
| ConformingOrder | 765 | 0x18 (size bit set) |

## Revision 2 re-audit (2026-08-04, post-mainnet finding)

- **LOW-P3-B1 (toolchain, found ON-CHAIN, fixed + gated): eager CSE
  hoisting of dataInputs reads.** The revision-1 tree crashed every
  data-input-less spend (repay/top-up/liquidate/covenantOff crank) with
  ArrayIndexOutOfBounds: the compiler's CSE hoisted the
  `CONTEXT.dataInputs(0)` node — shared between the guarded poolDataOk
  val and the healthAt lambda — into an eagerly-evaluated top-level
  ValDef, above its lazy-&& guards. Found by the happy path's first
  repay; mechanism CONFIRMED by recovering the bond with a dummy data
  input attached (tx 43e85ff1). **Fix (revision 2):** all dataInputs
  reads live in ONE `verdictAt` lambda returning -1/0/1 (invalid pool /
  unhealthy / healthy); the three application sites use structurally
  distinct arguments (accelerate subtracts sched(0), pinned 0 for all
  conforming bonds) so the applications cannot be CSE-merged into a
  shared eager node. Delta re-audit (audit_verify): semantically
  equivalent on every path, -1 verdict == old poolDataOk-false, no new
  suppliable values, keeper still cannot pick the crank branch.
- **Gate hardened:** three permanent no-data-input reduce probes (repay
  204,053; covenantOff crank 199,239; top-up 201,712) fail the compile
  gate on any recurrence. Covenant shapes on revision 2: 199,396-201,970.
- Standing toolchain rule (Phase 4+ — the coupon path adds verdict call
  sites): every dataInputs-touching read stays inside the single verdict
  lambda; every new application site gets a structurally distinct
  argument; the no-data-input probes stay in the gate.

## Tree sizes (Phase 3 revision 2, compile gate 2026-08-04)

| contract | bytes | header |
|---|---|---|
| ConformingBond | 1111 | 0x18 (size bit set) |
| ConformingOrder | 765 | 0x18 (size bit set) |

---

# EKB Two-Pass Audit — revision 4 contracts (2026-08-10, pre-deployment)

Revision 4 stores the script actors (borrower, lender, liquidation hook)
as blake2b256 hashes and reveals their preimages in the match
transaction. Both contracts were re-audited two-pass, independently, on
the changed tree; the findings below are the adjudicated set. Every
accepted finding was implemented before any rev-4 dust moved, and each
carries at least one adversarial wall test.

## ConformingOrder.es (revision 4)

Fixed in-contract, same day:

- **MED: card numerics were loosenable.** `liqCarveout` and
  `haircutKeep` arrived from the card unbounded — an inflated carveout
  lets a liquidator strip collateral, an inflated haircut nullifies the
  covenant. Both are now capped at the compiled outer bounds: a card may
  TIGHTEN a protocol bound, never loosen it. (Wall: card-bounds group.)
- **MED: card authenticated by NFT id alone.** A publisher could mint
  the pinned NFT into a mutable look-alike box and reprice orders that
  were posted but not yet matched. The card must now also hash to the
  compiled `TERMS_BOX_HASH`, so card immutability is structural rather
  than conventional. (Wall: look-alike card.)
- **LOW:** reserved `flagWord` gated to zero (no reserved bit ships
  matchable before its semantics exist); card byte fields must be 32
  bytes or the empty sentinel; threshold-max sentinel made sign-safe;
  the cancel co-spend now excludes SELF, so an order whose borrower
  field is the order tree itself cannot self-authorize its own cancel.

Adjudicated and implemented after review:

- **HIGH: a revealed hook preimage is not a USABLE hook.** The
  reveal-at-match rule proves a hook script exists on-chain; it cannot
  prove the script is spendable. Past maturity the hook is the lender's
  only claim, so a borrower could still pin a hook that burns. Fixed by
  **card-blessed hooks**: a pinned hook must appear in the pinned card's
  blessed-hook list, and card-less orders may not carry hooks at all.
  Hook and terms now travel as one immutable, publisher-vetted bundle.
  (Walls: blessed/unblessed hook, card-less hook ban, plus the
  end-to-end hooked-bond lifecycle on mainnet.)

Pinned, not fixed (posture, documented):

- The lender-side analog — a funder who reveals bytes that hash
  correctly but are not a spendable script — remains possible and
  remains the funder's own loss, unchanged from revisions 2 and 3. One
  wall test confirms the match succeeds, by design.
- The reveal read is the revision's only fallible node. It sits inside
  the match chain behind lazy guards; two permanent gate probes hold the
  line, including one that attaches a WRONG-TYPED context variable
  (a type mismatch throws where an absent variable returns None) and
  confirms an order still cancels cleanly.

## ConformingBond.es (revision 4)

- **MED: the price source was authenticated by NFT alone.** The covenant
  verdict accepted any three-token box carrying the pinned NFT — a
  self-minted NFT on a fabricated box could price a loan healthy at
  will. The verdict now also requires the data input to run the pinned
  DEX pool's script. (Wall: fabricated price source, built to price
  healthy by a wide margin so only the pin can reject it.)
- **MED: repay did not pin the collateral's destination.** A loose
  contract borrower could have its collateral routed to a stranger as a
  side effect of a transaction that "repays" its bond. Repay now
  requires every collateral token to land in an output whose script
  hashes to the borrower field; the ERG residual stays free. (Wall:
  repay collateral pin.)
- **Closed by the order-side fixes:** the unclamped-carveout theft path
  and the covenant-nullifying haircut both close at origination.
- Hash-compare correctness was the focus of the second pass: all six
  sites verified, no type or length confusion, and every hash is taken
  over a total expression on INPUTS/OUTPUTS proposition bytes — no new
  fallible node enters the bond, so the revision-2 eager-evaluation
  discipline is unchanged.

Logged, no change (economics or inherent):

- AMM spot-price manipulation remains the ecosystem-standard posture,
  buffered by the haircut and by two-sample acceleration; it escalates
  on thin pools and is tracked as an economics item.
- The party paying a coupon picks the sampling moment; this is inherent
  to a signatureless coupon and only bites in combination with the item
  above.
- Servicing an unhealthy position late writes an already-expired cure
  window — an emergent consequence of two intended rules
  (grid-anchored deadlines, no grace ceiling), pinned here as intended:
  it is a race the borrower chose to enter.
- Builders must not submit a coupon or crank within a few blocks of
  maturity: such a transaction can be mempool-evicted rather than merely
  fail. Harness rule, not a contract change.
