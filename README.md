# c-bonds — Conforming Bond Standard (rev 3)

A script-ownable bond standard for Ergo: BOTH slots hold arbitrary
ErgoTree bytes — the lender at `R8(0)`, the borrower at `R5` — so
contracts, vaults, funds and DAOs can sit on either side of a loan.
Instalment schedules, a maintenance covenant priced from a pinned DEX
pool, an immutable on-chain product catalog ("cards"), signatureless
keeper paths (crank, coupon, liquidation, two accelerations), on-chain
settlement receipts, and a per-loan token identity.

**Prior art.** The bond/order semantics descend from SigmaFi's
`BondContractERG` by [SigmaBonds](https://github.com/K-Singh/Sigma-Finance),
whose deployed contract proved the P2P fixed-term bond model on mainnet;
the liquidation-hook pattern follows SigmaFi's `EXP_BondContractERG`
(hash committed on the box, full script via context-extension var 0).
This repository is an original implementation (new lineage, no forked
code). The settlement-receipt convention follows Duckpools' repayment
pattern; the terms-box side-car pattern has prior art in EXLE's Service
Box.

## Status

Phases 1–4 complete on the **rev-3 contract tree**, mainnet-proven with
dust loans: order + bond + terms-box contracts, universal checkpoints,
covenant pricing with cure and acceleration, instalment coupons,
missed-payment acceleration, the card catalog, and the liquidation
hook. Every phase's adversarial wall is green against this tree
(Phase 1 11/11 · Phase 2 6/6 · Phase 3 C-wall · Phase 4 D-wall D1–D15
+ E-wall E1–E9), plus three complete on-chain loan lifetimes and a
live coupon-vs-acceleration mempool race. Transaction ledgers with
verifiable tx ids: `TRANSACTIONS.md`, `ph2/ph3/ph4TRANSACTIONS.md`,
`ph4RERUNS.md`.

The bond contract is final: later products (attester-based covenants,
facilities, registries) compose at the order/card layer with zero bond
changes — the attestation slot ships stubbed and order-gated dead.

## Register layout (locked, rev 3)

Bond box:

| Reg | Type | Content |
|---|---|---|
| R4 | `Coll[Byte]` | originating order box id (== loan token id) |
| R5 | `Coll[Byte]` | borrower ErgoTree bytes (arms authorize by co-spend) |
| R6 | `Long`       | repayment amount (nanoERG) |
| R7 | `Int`        | maturity height |
| R8 | `Coll[Coll[Byte]]` | suffix pack: `[lenderScript]` · `[lenderScript, poolNFT]` (covenant) · `[…, liqHookHash]` (custom hook) |
| R9 | `Coll[Long]` | schedule `[installment, periodBlocks, paymentsRemaining, nextCheckHeight, maintenanceThresholdBps, escrowBalance]`; size 10 when card-originated (+ `[crankBounty, graceBlocks, liqCarveout, haircutKeep]`) |

A bullet is `installment = 0`; an instalment loan carries
`paymentsRemaining = K+1` and its schedule only advances by paying the
lender in the same transaction (the coupon IS the crank). A missed
coupon is derived state — no register write — opening a signatureless
acceleration after grace. Exit outputs carry `R4 = SELF.id` plus the
loan token as the settlement receipt.

**Cards (`TermsBox.es`):** one immutable refuel-only box per loan
product, minted with its own NFT, EIP-4 browsable. R7 numeric pack +
flag word, R8 id fields, sentinel 0/empty = compiled default. The
borrower pins the card NFT in the order before match; the funder can
never choose the tier. Anyone may publish a card; there is no admin
key anywhere.

**Conforming rule (registry side):** a loan is conforming iff its loan
token id resolves to a box at the conforming order address. Token ids
are minting-tx first-input box ids, and the order contract requires
itself to be `INPUTS(0)` at match, so this provenance cannot be forged
(`src/main/scala/bonds/Provenance.scala`).

## Layout

```
contracts/            ConformingBond.es, ConformingOrder.es, TermsBox.es,
                      MinimalLenderVault.es
src/main/scala/bonds/ harness (Kit, Contracts, TestLib, Phase2-4 libs),
                      compile gates (Phase3Gate, Phase4Gate), suites
                      (RunPhase1-4, Phase3Suite, Phase4WallD/E),
                      recovery + sweep tools (Recycle*, Consolidate*,
                      Phase3Tail, Phase4Tail)
JITCOST.md            measured JitCost per path (rev-2 and rev-3 tables)
SKEWCOST.md           cost of shifting the pinned pool's price (C3)
```

## Running

Requires sbt, JDK 8+, and a mainnet Ergo node with `extraIndex = true`.
The harness signs locally and uses only public node endpoints — no node
API key, no node wallet.

```bash
cp .env.example .env                     # then set NODE_URL if not local
sbt "runMain bonds.Contracts"            # compile gate: addresses + sizes
sbt "runMain bonds.GenWallets"           # fresh DUST-ONLY mnemonics -> .env
# fund the printed BORROWER address with ~0.5 ERG, then:
sbt "runMain bonds.Fund"                 # distribute to lender + keeper
sbt "runMain bonds.Phase4Gate"           # permanent probes + JitCost (local reduces)
sbt "runMain bonds.RunPhase1"            # Phase 1 suite on mainnet
sbt "runMain bonds.RunPhase2"            # Phase 2 successor wall
sbt "runMain bonds.Phase3Suite"          # Phase 3 covenant C-wall
sbt "runMain bonds.Phase4WallD"          # Phase 4 instalment wall
sbt "runMain bonds.Phase4WallE"          # rev-3 structural wall (mints cards)
sbt "runMain bonds.RunPhase4"            # instalment happy paths + the race
```

**Dust only.** Test wallets never hold more than dust; nothing above
dust moves until the Phase 6 audit is clean. `.env` is gitignored and
`GenWallets` refuses to write secrets unless that is verified.

## License

AGPL-3.0
