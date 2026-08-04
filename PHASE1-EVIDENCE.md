# Phase 1 — Evidence Pack

**Status: complete. Adversarial suite 10/10 green on mainnet, JitCost
table recorded.** (2026-08-03, node height ~1,843,5xx.)

Phase 1 of `conforming-bond-build-plan.md`: order contract, bond contract
with a script lender slot (R8 = `Coll[Byte]` ErgoTree bytes), signatureless
post-maturity liquidation, settlement receipts, deployed to mainnet with
dust loans, plus the full Phase 1 adversarial suite.

## Contracts (mainnet, size bit set)

| contract | tree bytes | file |
|---|---|---|
| ConformingBond | 181 | contracts/ConformingBond.es |
| ConformingOrder | 554 | contracts/ConformingOrder.es |
| MinimalLenderVault | (owner-gated) | contracts/MinimalLenderVault.es |

Bond address: `joBXWhFyRTHQ8rvTC9kGF…` (full address printed by
`runMain bonds.Contracts`). Register layout exactly as locked in the build
plan; R9 schedule pack ships in Phase 1 (installment = 0 bullets) so no
redeployment is needed for Phase 2.

## Suite result (run 3, the green run)

| test | result | proves |
|---|---|---|
| T1 fund from order | PASS | script-owned bond created from an order; loan token minted (id = order id); provenance rule holds on-chain |
| T2 repay to script | PASS | borrower repays to the R8 vault script; receipt (R4 = bond id + loan token) verified on-chain |
| T3 liquidate past maturity | PASS | third key (keeper) cranks a matured bond signaturelessly; collateral − carve-out to the vault, receipt verified |
| A1 repay to one-byte-off script | PASS | repayment to a script differing from R8 by one byte → rejected; honest twin repay succeeds |
| A2 repay one nanoERG short | PASS | underpayment → clean reduce-to-false; honest twin succeeds |
| A3 liquidate one block early | PASS | liquidation at HEIGHT == maturity−1 → signatureless path unavailable; boundary twin reduces at exactly maturity |
| A4 liquidation split | PASS | withholding 0.001 ERG past the carve-out → attacker cannot spend |
| A5 token-collateral withhold | PASS | withholding 250/500 collateral tokens while ERG looks right → attacker cannot spend; honest token-collateral liquidation succeeds |
| A6 forged bond provenance | PASS | bond minted outside the order contract → its loan-token id does not resolve to the order address; registry rejects it |
| A7 receipt omitted | PASS | exit without R4 = SELF.id (absent, and wrong-id) → rejected; honest twin succeeds |
| A8 cancel-mint forgery | PASS | borrower cancelling while minting a token with id = order id → rejected (EKB HIGH-O1 fix); plain cancel succeeds |

Every negative asserts its specific failure: borrower-signed repay attacks
reduce to false; signatureless liquidation attacks are rejected because the
attacker cannot satisfy the residual (no unauthorized party can spend). A
transaction that failed for the wrong reason would fail the test.

## JitCost

See JITCOST.md. Worst path (match) 119,635 — 23.9% of the 500K budget.

## Transaction ledger

See TRANSACTIONS.md — 31 on-chain transactions, role-named, generated from
the run log by `scripts/gen_tx.py`. Negative-test attacks are rejected at
proving and never reach the chain (by design), so they carry no txId.

## Pre-deployment audit

EKB two-pass audit (`audit_contract` → `audit_verify`) on all three
contracts before any dust moved. One HIGH (cancel-path loan-token forgery)
found and fixed, plus a collateral-floor hardening. Full findings in
AUDIT.md; pinned by test A8. Phase 6 reruns the audit on the final set.

## Run history (honest record)

- **Run 1: 5/10.** Contract refused all 10 attacks correctly; 3 harness
  bugs (assertion too narrow for the signatureless-path rejection mode,
  maturity off-by-one, appkit TxBuilder reuse). Fixed.
- **Run 2:** harness bugs gone; failed on fragmented/exhausted dust
  (leftover test tokens welded onto the borrower's ERG change; vault sink
  never recycled). Added Recycle + Transfer tooling; A5 now mints exact
  collateral.
- **Run 3: 10/10 green**, 3h42m wall clock (dominated by four on-chain
  maturity waits).

No contract change was ever required by a test failure — every red mark
was harness or plumbing, which is what the "fails for the wrong reason"
discipline is meant to surface.
