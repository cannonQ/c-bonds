# c-bonds: Conforming Bond Standard (rev 5)

Script-ownable bonds for Ergo. Both sides of a loan can be arbitrary
contracts (vaults, funds, DAOs), not just keys.

The bond stores each party as a 32-byte `blake2b256` hash and the full
scripts are revealed in the match transaction. A counterparty's script
can be any size without threatening the 4 KB box limit.

**Prior art.** Bond and order semantics descend from SigmaFi's
`BondContractERG` by [SigmaBonds](https://github.com/K-Singh/Sigma-Finance),
which proved the P2P fixed-term bond model on mainnet. The
liquidation-hook pattern follows SigmaFi's `EXP_BondContractERG` (hash on
the box, full script via context-extension var 0). The settlement-receipt
convention follows Duckpools. The terms-box side-car has prior art in
EXLE's Service Box. This is an original implementation, not a fork.

## What rev 5 changes

Rev 4 was one bond contract and one order contract covering every
product. Rev 5 splits them into three product pairs. A plain loan no
longer pays for covenant or instalment machinery it never uses.

| Product | Contracts | Covenant | Schedule | Hooks |
|---|---|---|---|---|
| Plain bullet | `PlainBulletBond` / `PlainBulletOrder` | no | no | no |
| Covenant bullet | `CovenantBulletBond` / `CovenantBulletOrder` | required | no | via card |
| Instalment | `InstalmentBond` / `InstalmentOrder` | optional | coupons | via card |

Compiled sizes:

| Contract | Bytes |
|---|---|
| PlainBulletBond | 255 |
| PlainBulletOrder | 525 |
| CovenantBulletBond | 1482 |
| CovenantBulletOrder | 1908 |
| InstalmentBond | 1609 |
| InstalmentOrder | 2088 |
| TermsBox (card) | 160 |

The card is byte-identical to rev 4, so its address is unchanged and
cards minted under rev 4 still work.

The rev-4 monolith (`ConformingBond.es`, `ConformingOrder.es`) stays in
the tree. It is what is live on mainnet today.

## Status

**Rev 4: mainnet-proven.** Phases 1 to 4 complete with dust loans.
Universal checkpoints, covenant pricing with cure and acceleration,
instalment coupons, missed-payment acceleration, the card catalog, and
card-blessed liquidation hooks. Every adversarial wall green (Phase 1
11/11, Phase 2 6/6, Phase 3 C1 to C12, Phase 4 D1 to D15 and E1 to E15),
four complete on-chain loan lifetimes including a hook-pinned bond taken
from origination through signatureless hooked liquidation, plus a live
coupon-versus-acceleration mempool race.

**Rev 5: compiled and gated, not yet on chain.** The six trees compile,
the probe suite is green, and the off-chain builders are written and
locally signed. No rev-5 transaction has been submitted to mainnet. The
adversarial walls have not been re-run against the split trees.

| Check | Result |
|---|---|
| `Rev5Gate` compile gate | 95 probes green |
| `Rev5Smoke` builder smoke | 77 checks green |
| `Phase4Gate` (rev-4, unchanged) | green |
| Heaviest path cost | about 3.5% of the per-input budget |

Toolchain: appkit 6.0.0, sigma-state 6.0.2, mainnet 6.0 active,
ErgoTree v3.

Verification records (evidence packs, cost tables, transaction ledgers)
are internal and live in `working/`, which is not published.

## Registers

Bond box, all three products:

| Reg | Type | Content |
|---|---|---|
| R4 | `Coll[Byte]` | originating order box id, equals the loan token id |
| R5 | `Coll[Byte]` | `blake2b256(borrower ErgoTree)` |
| R6 | `Long` | repayment amount, nanoERG |
| R7 | `Int` | maturity height |
| R8 | varies | lender hash, see below |
| R9 | `Coll[Long]` | schedule, absent on plain bullet |

R8 and R9 differ by product. This matters: writing the wrong shape makes
the box unspendable on every path.

| Product | R8 | R9 |
|---|---|---|
| Plain bullet | `Coll[Byte]`, the lender hash | absent |
| Covenant bullet | `Coll[Coll[Byte]]`, size 2 or 3: `[lenderHash, poolNFT]` plus `hookHash` | 6 fields, 10 if card-originated |
| Instalment | `Coll[Coll[Byte]]`, size 1 to 3: `[lenderHash]` plus `poolNFT` if covenant on, plus `hookHash` | 6 fields, 10 if card-originated |

Schedule (R9): `[installment, periodBlocks, paymentsRemaining,
nextCheckHeight, maintenanceThresholdBps, escrowBalance]`. Card-originated
bonds append `[crankBounty, graceBlocks, liqCarveout, haircutKeep]`.

Order box:

| Reg | Plain bullet | Covenant bullet and instalment |
|---|---|---|
| R4 | borrower ErgoTree, full script | same |
| R5 | principal requested | same |
| R6 | repayment amount | same |
| R7 | term in blocks | same |
| R8 | absent | `[cardPin]` or `[cardPin, hookHash]` |
| R9 | absent | schedule template, 6 fields |

The order keeps the borrower's full script in R4 because the funder has
to build the principal box from it.

### Why hashes

Say Alice borrows and Bob lends through a large fund contract. If the
bond had to carry Bob's whole contract it might not fit in a box, and
Alice's loan would be unmatchable through no fault of either party.

So the bond carries a 32-byte fingerprint of each side, and the match
transaction carries the full scripts as context extensions. Context
extensions are part of the signed transaction, so both scripts stay
on-chain: Alice can always build her payment, and any keeper can always
build the liquidation exit, by reading the match transaction.

One index, two meanings:

- At match, the ORDER input reads var 0 = lender, var 1 = hook.
- At liquidation, the BOND input reads var 0 = hook.

Builders must not share a constant between the two sites.

### Term limits

A funder picks the stamped maturity from a tolerance window after seeing
the order, so the contracts enforce a floor: a bond can never be born
liquidatable, and on scheduled products the first checkpoint must land
after the birth block.

| Product | Minimum term |
|---|---|
| Plain bullet | 2 blocks |
| Covenant bullet | 5 blocks (period 4) |
| Instalment | 5 blocks (period 4) |

Originating near the minimum still produces a very short bond. For a
comfortable window use `period >= 6` and `term >= period + 6`.

## Cards

One immutable refuel-only box per loan product, minted with its own NFT,
EIP-4 browsable. The borrower pins the card NFT in the order before
match, so the funder can never choose the tier. Anyone may publish a
card. There is no admin key.

Sentinel 0 or empty means "use the compiled protocol default". A card can
tighten floors, never loosen them.

Card R7 (`Coll[Long]`, size 11). Indices 0 to 3 copy into bond R9 6 to 9:

| idx | field | idx | field |
|---|---|---|---|
| 0 | crankBounty | 6 | minOrderValue (floor) |
| 1 | graceBlocks | 7 | minPeriod (floor) |
| 2 | liqCarveout | 8 | minCoupon (floor) |
| 3 | haircutKeep | 9 | attestationType (0 = pool price) |
| 4 | thresholdMin (bps) | 10 | flagWord (reserved) |
| 5 | thresholdMax (bps) | | |

Card R8 (`Coll[Coll[Byte]]`): `[poolNFT, collateralTokenId,
attesterScriptHash?, feeRecipientHash?, blessedHookHash...]`. Every entry
from index 4 on is a liquidation hook the publisher blesses.

Card R9: publisher, version, predecessor. Informational only.

What the terms mean:

| term | meaning |
|---|---|
| crankBounty | what a keeper earns for advancing one checkpoint. The borrower pre-pays one per checkpoint into escrow at origination |
| graceBlocks | how long after a failed health check or missed coupon before the loan becomes seizable |
| liqCarveout | slice of a liquidation kept by whoever fires it, so liquidation pays for itself and needs no funded keeper |
| haircutKeep | how much of the token collateral's simulated sale value counts, out of 10000. 9800 means a 2% slippage discount |
| thresholdMin/Max | allowed range for the maintenance threshold: collateral value over debt, in basis points. 15000 is 150% |
| minOrderValue | smallest collateral, net of escrow, the card will originate |
| minPeriod | shortest allowed gap between checkpoints, in blocks |
| minCoupon | smallest allowed instalment payment |
| attestationType | which health oracle the covenant uses. 0 is the pinned DEX pool price, the only type accepted today |
| flagWord | reserved switches for future card behaviour. All zero today |
| poolNFT | the exact DEX pool box used to price collateral |
| collateralTokenId | the only token the card accepts as collateral. Must be the pool's traded token |
| attesterScriptHash | future use: the contract allowed to attest health when attestationType is not 0 |
| feeRecipientHash | future use: where card-level fees would go |
| blessedHookHash (idx 4+) | a liquidation hook an order may pin. The publisher vetted it, and the card cannot be edited afterwards |

### Liquidation hooks

A hook redirects what happens to collateral at default. Instead of going
to the lender, the exit rebinds to a hook script: an auction, a
fair-value sale, a partial liquidation, a DEX route.

The borrower picks the hook when posting the order, so the lender has to
live with it. The card is what vets it: a hook is legal only if the
pinned card lists that hook's hash, and cards are immutable once minted.
The hook and the terms travel as one auditable bundle.

Card-less orders cannot carry hooks. They get plain liquidation to the
lender.

Decode any live card:

```bash
sbt "runMain bonds.CardInfo <cardNftId>"
```

## Conforming rule

A loan is conforming if its loan token id resolves to a box at a
conforming order address. Token ids are minting-tx first-input box ids,
and the order requires itself to be `INPUTS(0)` at match, so this
provenance cannot be forged. See `src/main/scala/bonds/Provenance.scala`.

## Layout

```
contracts/
  PlainBulletBond.es      PlainBulletOrder.es       rev-5 per-product trees
  CovenantBulletBond.es   CovenantBulletOrder.es
  InstalmentBond.es       InstalmentOrder.es
  TermsBox.es                                       card, shared
  ConformingBond.es       ConformingOrder.es        rev-4 monolith, live
  MinimalLenderVault.es                             example script owner

src/main/scala/bonds/
  Kit, Contracts, Env                               harness core
  Rev5Lib                                           rev-5 builders
  Rev5Gate, Rev5Smoke, Rev5JitCost                  rev-5 probes and costs
  Phase2-4 libs, Phase3Gate, Phase4Gate             rev-4 harness and gates
  RunPhase1-4, Phase3Suite, Phase4WallD/E           rev-4 mainnet suites
  Recycle*, Consolidate*, Phase3Tail, Phase4Tail    recovery and sweep

SKEWCOST.md    cost of shifting the pinned pool's price
working/       internal verification records, not published
```

## Running

Requires sbt, JDK 8+, and a mainnet Ergo node with `extraIndex = true`.
The harness signs locally and uses only public node endpoints. No node
API key, no node wallet.

```bash
cp .env.example .env              # set NODE_URL if not local
sbt "runMain bonds.Contracts"     # addresses and sizes for every tree
```

Rev 5, spend-free. These reduce and sign locally, nothing is submitted:

```bash
sbt "runMain bonds.Rev5Gate"      # 95 probes: paths, brick cases, cost identity
sbt "runMain bonds.Rev5Smoke"     # 77 checks over fabricated boxes
sbt "runMain bonds.Rev5JitCost"   # per-path cost table
```

Rev 4 on mainnet. These spend:

```bash
sbt "runMain bonds.GenWallets"    # fresh DUST-ONLY mnemonics -> .env
# fund the printed BORROWER address with ~0.5 ERG, then:
sbt "runMain bonds.Fund"          # distribute to lender + keeper
sbt "runMain bonds.Phase4Gate"    # permanent probes, local reduces
sbt "runMain bonds.RunPhase1"     # Phase 1 suite
sbt "runMain bonds.RunPhase2"     # Phase 2 successor wall
sbt "runMain bonds.Phase3Suite"   # Phase 3 covenant C-wall
sbt "runMain bonds.Phase4WallD"   # Phase 4 instalment wall
sbt "runMain bonds.Phase4WallE"   # structural wall, mints the cards
sbt "runMain bonds.RunPhase4"     # instalment happy paths and the race
```

**Dust only.** Test wallets never hold more than dust. `.env` is
gitignored and `GenWallets` refuses to write secrets unless that is
verified.

## License

AGPL-3.0
