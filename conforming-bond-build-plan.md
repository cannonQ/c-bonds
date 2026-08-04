# Conforming Bond: Build Plan

Goal: a working proof on mainnet before anyone is asked to adopt anything. Contract with all lifecycle paths, a minimal script lender, and a keeper bot running unattended, with every transaction walkable on the explorer.

Scope is Option A, clean contract: R8 holds lender script bytes. No dual-register compromise, no facility, no tranches. The facility waits until this is proven.

---

## 1. Environment

Build box: the dev box (local node, port 9053, `.env` `NODE_URL`). Node, wallet, and tmux service pattern already in place. Keeper bot joins the existing tmux service launcher as a new window once Phase 5 starts. Secrets live in a repo-local .env (gitignored, with a committed .env.example), loaded by the harness and bot. No reliance on system-wide secrets, nothing hardcoded, test wallets hold dust only.

Toolchain: ergo-appkit 5.0.4, Scala 2.12.18, scrypto 2.3.0 forced via dependencyOverrides. All known appkit pitfalls apply: size bit fix on every compiled tree, explorer URL required in RestApiErgoClient, register ordering from R4, primitive arrays for ErgoValue, TX chaining inside a single execute block.

Keeper bot: Node.js, same lineage as the buyback bots. Binary submission via /transactions/bytes, BigInt end to end, fallback node list, Telegram alerts. The sniper's fee-override logic carries over for checkpoint races.

Testing is mainnet-first with dust-sized loans, Field Protocol style: real node, real confirmations, numbered test suite, nothing counts until it passes against the chain.

---

## 2. Register layout (locked before code)

R9 does not exist as spare space once the schedule is in. Layout:

- R4: originating order box ID (Coll[Byte])
- R5: borrower SigmaProp
- R6: repayment amount (Long)
- R7: maturity height (Int)
- R8: lender script bytes (Coll[Byte])
- R9: schedule pack (Coll[Long]): [installment, periodBlocks, paymentsRemaining, nextCheckHeight, maintenanceThresholdBps, escrowBalance]

Loan identity is a minted per-loan token, not the box ID. Exit outputs carry R4 = SELF.id as the receipt. A bullet loan is installment = 0 with a single principal payment at maturity. Six Longs in one pack; if a seventh is ever needed the layout is wrong, redesign rather than squeeze.

Height rule, from the TroyGold bug: no height comparison ever uses equality. Every window is >= open and < close with explicit grace constants.

Type rule, from the AetherBridge bug: no SigmaProp and Boolean mixing across branches. Every branch resolves to Boolean and the contract wraps once in sigmaProp at the top level.

---

## 3. Phases

Each phase ends with its adversarial suite green on mainnet before the next phase starts. Happy-path tests prove it works. Adversarial tests prove it only works the intended way. A phase without its adversarial suite is not done.

### Phase 1: Script lender core

Fund, repay, liquidate. Order contract validating bond creation, bond contract with R8 script validation on both exits, signatureless liquidation, receipts.

Happy path: script-owned bond funded from an order, repaid to the lender script, second bond liquidated past maturity by a third key.

Adversarial suite:
- Repayment output to a script that differs from R8 by one byte. Must fail.
- Repayment one nanoERG short. Must fail.
- Liquidation attempted one block before maturity window opens. Must fail.
- Liquidation output splitting collateral between lender script and attacker. Must fail.
- Token-collateral liquidation withholding part of the tokens while ERG value looks right. Must fail.
- Bond minted outside the order contract with mismatched registers, then presented for repayment. Confirm the registry-side rule: no order provenance, not a conforming loan.
- Receipt omitted (exit output without R4 = SELF.id). Must fail.

### Phase 2: Successor machinery

Checkpoint crank path and top-up path. This is where register preservation bugs live, so this phase gets the heaviest suite.

Happy path: keeper cranks a healthy checkpoint, successor recreated, nextCheckHeight advanced, bounty paid from escrow. Borrower tops up collateral mid-period.

Adversarial suite:
- Successor with any single register mutated: borrower key, repayment amount, maturity, lender script, any schedule element beyond the permitted advance. One test per register. All must fail.
- Successor with correct registers but reduced collateral value. Must fail.
- Successor to a different script (contract swap). Must fail.
- Top-up that nets to zero or negative after fee accounting. Must fail.
- Top-up that also touches the schedule pack. Must fail.
- Checkpoint cranked early, before nextCheckHeight. Must fail.
- Checkpoint bounty exceeding the per-crank allowance, and escrow drained faster than one bounty per period. Both must fail.
- Double crank: two keepers race the same checkpoint. First confirms, second must invalidate harmlessly. Verify mempool behavior and bot retry, buyback-bot eviction lessons apply.
- Loan token missing or duplicated on the successor. Must fail.

### Phase 3: Covenant and pool pricing

DEX pool box as data input, swap-simulation valuation, maintenance test at check heights, cure window.

Happy path: healthy ratio passes checkpoint. Deteriorated ratio opens cure window. Cure by top-up inside grace. Blown grace leads to acceleration.

Adversarial suite:
- Data input is a lookalike pool holding a fake token with the right name. Pool NFT check must fail it.
- Data input is the right pool at a stale unspent box that is not the current pool state. Confirm what the contract actually accepts and document the freshness rule.
- Pool skewed by a large swap in the prior block, then checkpoint submitted against the skewed state. Measure the cost of moving the covenant outcome at several pool depths. This is the LP manipulation number the whole pricing argument rests on, so it gets measured, not asserted.
- Ratio exactly at threshold. Boundary must resolve one way by spec, test pins it.
- Cure transaction that tops up but pays no overdue installment where one is due. Behavior per the cure-mechanics decision, test pins it.
- Acceleration attempted during a live cure window. Must fail.
- Acceleration valid but output routed anywhere except 100% to the lender script. Must fail.

### Phase 4: Installments and full lifecycle

Nonzero installments, coupon path with covenant in one transaction, final-installment collateral release, acceleration on missed payment.

Happy path: three-coupon loan serviced to completion. Loan with a missed second coupon accelerated at grace expiry.

Adversarial suite:
- Coupon paying less than installment. Must fail.
- Coupon correct but successor schedule not decremented, or decremented twice. Must fail.
- Final installment attempting collateral release with paymentsRemaining above zero. Must fail.
- Coupon and acceleration built against the same box at the grace boundary height. Race resolves to exactly one winner, loser invalidates clean.
- Borrower self-cranks a checkpoint and takes the keeper bounty. Decide whether this is permitted (it is harmless and arguably good) and pin with a test either way.
- JitCost measured per path at DEBUG logging. Full-lifecycle worst case documented against the 500K budget with headroom stated.

### Phase 5: Vault, keeper, soak

Minimal lender vault: holds funds, funds conforming orders, sweeps receipts, owner-only withdrawal. Deliberately dumb.

Keeper bot: watches all conforming bonds, cranks checkpoints at height, sweeps settlements, triggers accelerations, Telegram on every action and every failure. Runs in tmux on the build box alongside the existing services.

Soak: at least 10 self-funded dust loans covering every lifecycle shape, including at least one of each: bullet to maturity, multi-coupon to completion, top-up cure, covenant acceleration, payment acceleration. Bot runs a minimum of 4 weeks unattended. Success is zero missed checkpoints and zero manual interventions, measured from the bot log, not from memory.

### Phase 6: Audit and evidence

EKB two-pass audit (audit_contract then audit_verify) on the final contract set. Fix, re-run, repeat until clean or every finding has a written rationale.

Evidence pack: contract addresses, loan token IDs, one explorer-linked walkthrough per lifecycle path, JitCost table, soak statistics, manipulation-cost measurements from Phase 3. This pack plus the spec is what goes in front of Cheese and Alison. The ask writes itself at that point.

---

## 4. Standing rules for every phase

- Adversarial tests are written before the path they attack, from the spec, not from the code. Tests derived from the implementation inherit its blind spots.
- Every negative test asserts the specific failure, not just absence of success. A transaction that fails for the wrong reason is a bug that passed.
- Every fix reruns the full suite for that phase plus Phase 2. Successor validation is the shared load-bearing wall.
- Mempool behavior is part of the test: contention, eviction, and resubmission are exercised deliberately at least once per racing path.
- No real value beyond dust until Phase 6 is clean.
