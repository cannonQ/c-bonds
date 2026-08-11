# c-bonds — Conforming Bond Standard (rev 4)

A script-ownable bond standard for Ergo: BOTH sides of a loan can be
arbitrary contracts — vaults, funds, DAOs — not just keys. The bond
stores each party as a 32-byte `blake2b256` hash (`R8(0)` lender, `R5`
borrower) and the full scripts are revealed in the match transaction, so
**a counterparty's script can be any size without ever threatening the
4 KB box limit**. Instalment schedules, a maintenance covenant priced
from a pinned DEX pool, an immutable on-chain product catalog ("cards")
that can also bless liquidation hooks, signatureless keeper paths
(crank, coupon, liquidation, two accelerations), on-chain settlement
receipts, and a per-loan token identity.

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

Phases 1–4 complete on the **rev-4 contract tree**, mainnet-proven with
dust loans: order + bond + terms-box contracts, universal checkpoints,
covenant pricing with cure and acceleration, instalment coupons,
missed-payment acceleration, the card catalog, and card-blessed
liquidation hooks. Every adversarial wall is green against this tree
(Phase 1 11/11 · Phase 2 6/6 · Phase 3 C-wall C1–C12 · Phase 4 D-wall
D1–D15 + E-wall E1–E15), plus four complete on-chain loan lifetimes —
including a hook-pinned bond carried from origination through a
signatureless hooked liquidation at maturity — and a live
coupon-vs-acceleration mempool race. Transaction ledgers with
verifiable tx ids: `TRANSACTIONS.md`, `ph2/ph3/ph4TRANSACTIONS.md`,
`ph4RERUNS.md`, `rev4TRANSACTIONS.md`.

Toolchain: appkit 6.0.0 / sigma-state 6.0.2, mainnet 6.0 active,
ErgoTree v3. JitCost per path is measured in `JITCOST.md`; the heaviest
observed path uses about 4% of the per-input budget.

The bond contract is final: later products (attester-based covenants,
facilities, registries) compose at the order/card layer with zero bond
changes — the attestation slot ships stubbed and order-gated dead.

## Register layout (locked, rev 4)

Bond box:

| Reg | Type | Content |
|---|---|---|
| R4 | `Coll[Byte]` | originating order box id (== loan token id) |
| R5 | `Coll[Byte]` | `blake2b256(borrower ErgoTree)` — arms authorize by co-spend |
| R6 | `Long`       | repayment amount (nanoERG) |
| R7 | `Int`        | maturity height |
| R8 | `Coll[Coll[Byte]]` | suffix pack: `[lenderHash]` · `[lenderHash, poolNFT]` (covenant) · `[…, liqHookHash]` (custom hook) |
| R9 | `Coll[Long]` | schedule `[installment, periodBlocks, paymentsRemaining, nextCheckHeight, maintenanceThresholdBps, escrowBalance]`; size 10 when card-originated (+ `[crankBounty, graceBlocks, liqCarveout, haircutKeep]`) |

**Why hashes, and where the scripts live (rev 4).** Say Alice borrows
and Bob lends through a big fund contract. If the bond box had to carry
Bob's whole contract, a large enough fund would not fit in a box and
Alice's loan would simply be unmatchable — through no fault of either
party. So the bond carries only a 32-byte fingerprint of each side, and
the match transaction carries the full scripts as context extensions:
var 0 = the lender script, var 1 = the liquidation hook when the order
pins one. Context extensions are part of the signed transaction, so both
scripts are on-chain forever: Alice can always build her coupon payment,
and any keeper Charlie can always build the liquidation exit, by reading
the match transaction. The bond box's size no longer depends on how big
either party's contract is.

The order box keeps the borrower's FULL script in `R4` — it is the
public offer, and the funder has to construct the principal box from it,
which no reveal trick can replace.

> One index, two meanings: at match the ORDER input reads var 0 =
> lender and var 1 = hook; at liquidation the BOND input reads var 0 =
> hook. Builders must not share a constant between the two sites.

A bullet is `installment = 0`; an instalment loan carries
`paymentsRemaining = K+1` and its schedule only advances by paying the
lender in the same transaction (the coupon IS the crank). A missed
coupon is derived state — no register write — opening a signatureless
acceleration after grace. Exit outputs carry `R4 = SELF.id` plus the
loan token as the settlement receipt.

**Cards (`TermsBox.es`):** one immutable refuel-only box per loan
product, minted with its own NFT, EIP-4 browsable. The borrower pins
the card NFT in the order before match; the funder can never choose
the tier. Anyone may publish a card; there is no admin key anywhere.
Sentinel 0/empty = compiled protocol default throughout.

Card R7 (`Coll[Long]`, size 11) — indices 0–3 copy into bond R9 6–9:

| idx | field | idx | field |
|---|---|---|---|
| 0 | crankBounty | 6 | minOrderValue (floor) |
| 1 | graceBlocks | 7 | minPeriod (floor) |
| 2 | liqCarveout | 8 | minCoupon (floor) |
| 3 | haircutKeep | 9 | attestationType (0 = pool-price) |
| 4 | thresholdMin (bps) | 10 | flagWord (reserved) |
| 5 | thresholdMax (bps) | | |

Card R8 (`Coll[Coll[Byte]]`): `[poolNFT, collateralTokenId,
attesterScriptHash?, feeRecipientHash?, blessedHookHash…]` — every entry
from index 4 on is a liquidation hook the publisher blesses (rev 4).
Card R9: publisher / version / predecessor (informational only).

**Card-blessed liquidation hooks (rev 4).** A liquidation hook redirects
what happens to the collateral at default: instead of shipping it to the
lender, the exit rebinds to a hook script — an auction, a fair-value
sale, a partial liquidation, a DEX route. Alice picks the hook when she
posts the order, which means Bob has to live with it, so the question is
who vets it. A hook whose script nobody can produce, or one that simply
burns the collateral, would leave Bob with nothing after maturity. Rev 4
answers it structurally: a hook is legal only if the card Alice pinned
lists that hook's hash, and cards are immutable once minted. Charlie the
publisher vets the hook script once, and from then on the hook and the
terms travel as one auditable bundle. Card-less orders cannot carry
hooks at all — they get plain SigmaFi-style liquidation.

What the terms mean:

| term | meaning |
|---|---|
| crankBounty | what a keeper earns for advancing one checkpoint; the borrower pre-pays one per checkpoint into escrow at origination |
| graceBlocks | how long after a failed health check (or missed coupon) the borrower has before the loan becomes seizable |
| liqCarveout | slice of a liquidation kept by whoever fires it, so liquidation pays for itself and needs no funded keeper |
| haircutKeep | how much of the token collateral's simulated sale value counts, out of 10000 — 9800 means a 2% slippage discount |
| thresholdMin/Max | allowed range for a loan's maintenance threshold: collateral value / debt, in basis points (15000 = 150%); below it the loan is unhealthy and can be cured or seized |
| minOrderValue | smallest collateral (net of escrow) the card will originate |
| minPeriod | shortest allowed gap between checkpoints, in blocks |
| minCoupon | smallest allowed instalment payment |
| attestationType | which health oracle the covenant uses; 0 = the pinned DEX pool's price, the only type the order contract accepts today |
| flagWord | reserved on/off switches for future card behaviours; all zero in rev 4 |
| poolNFT | the exact DEX pool box (identified by its NFT) used to price collateral |
| collateralTokenId | the only token the card accepts as collateral — it must be the pool's traded token or the covenant couldn't price it |
| attesterScriptHash | future use: the contract allowed to attest health when attestationType != 0 |
| feeRecipientHash | future use: where card-level fees would go |
| blessedHookHash (idx 4+) | a liquidation hook script this card allows an order to pin — the publisher has vetted it, and the card cannot be edited afterwards |

Any value set to 0/empty means "use the protocol default" — the
compiled constants are the implicit base product, and a card can only
tighten the floors, never loosen them.

Decode any live card to plain english:

```bash
sbt "runMain bonds.CardInfo <cardNftId>"
```

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
JITCOST.md            measured JitCost per path (rev-4 table; older tables kept
                      for history, NOT comparable across toolchains)
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
sbt "runMain bonds.Phase4WallE"          # structural wall (mints the cards)
sbt "runMain bonds.Phase4WallE10"        # hooked bond end-to-end (E10)
sbt "runMain bonds.RunPhase4"            # instalment happy paths + the race
```

**Dust only.** Test wallets never hold more than dust; nothing above
dust moves until the Phase 6 audit is clean. `.env` is gitignored and
`GenWallets` refuses to write secrets unless that is verified.

## License

AGPL-3.0
