# Phase 2 mainnet transaction log

Auto-generated from the suite run log (`phase2.log`). Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract (**BOND'** = successor box), **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.

This is the Phase 2 green-run ledger (successor machinery: crank, self-crank, top-up, race). Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability.


## B1-B12,B14

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 1 | `db4b9edd75e4761bbab45df8ef3aa72854627df4c0364136196191882bcd1b48` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 2 | `6e19a03dd1f855fbe0ae930aff8865252996e131ad91e9bc2b906e896948cd8f` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 3 | `5b83f502f44b6555d6a8705914ed62551bade1bb0a7828f6b4476cd1f1673a72` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B0

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 4 | `dfcdfb6f9f5333a22a18e219d66e3a6dac74a8acc28ac76c42caa830dcef3ed2` | borrower posts a deliberately non-conforming order (B0) | BORROWER → ORDER | coll + claimed escrow |
| 5 | `1623113e32b07d62c1312fabb92ae75d1345fedf4f5bf0f3a1ca788ed4b41076` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 6 | `ee4fbdb7e53ef719eec8c82ab57d28adb1cc0245bf6e1717ef5d6b6796211d14` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 7 | `548bd8bd06a850f1524d987192ca190ead96dd9a7a6db945b75842c22bc7b20b` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 8 | `78ad042a1199b97b6e49de6439a19fe57da158d33623694e3f4e9efe3e747ed0` | borrower posts a deliberately non-conforming order (B0) | BORROWER → ORDER | coll + claimed escrow |
| 9 | `f7d5e84c4573aa2bec3bc6a13be0ff63314e2c6b0735cfa5b7e5c74da2fb5d20` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## T4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 10 | `44e077397974677de4e0a97ae055e264db376e17009b94bc946f0a7b26cff184` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 11 | `b205d4648166685bb8625122a9be1bff50140c34ab54d026b7b018ce7c810327` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 12 | `a8651a8ce98613bc137cfbdfd90afb5354531ba12f91f828ff41196e768ba7b4` | signatureless checkpoint crank: successor + bounty, bond sole input | KEEPER cranks BOND → BOND' + bounty | 0.005 bounty |
| 13 | `06867126d5d7067099a55c116be3c227194c820bf8f220a253c12cfe55444774` | borrower self-crank (pinned decision): same crank path | BORROWER cranks BOND → BOND' + bounty | 0.005 bounty |

## T5

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 14 | `c65c99de6ebe4e0d6655f49ed0ae21e3c41d4cfa336e8b99c900a8225d0cb50c` | borrower-signed collateral top-up, schedule frozen verbatim | BORROWER adds to BOND → BOND' | +0.005 coll |
| 15 | `09c811b7761b56887816a75d4e4f31812ee6b517b332f9e5f7e3f973bbe13b69` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B13

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 16 | `56e0d1886e9325eb3074442e3b1d46f1ebd97121cd396b22b9e036b2e19ae9f5` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 17 | `f57f5c57793224da7686babb26ff56d7015ec29998203353159fa6eb2a36363a` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 18 | `4eff7a5046687470582b19730c8f6a95d8920cb6893fb675f254428e092b4b5a` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B16

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 19 | `c2e5e40b3f0a3296abcd226740980f359d5dcfa6570241eaca9a20ea2840f394` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 20 | `f426881c2646c18945a4bc6a309f2a107165eb1db44a173485f81399b8aa3ca7` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 21 | `c4abd3a2d2aac743fe2b0d40d4f527b66618f4cbd5f88921b7e3efcddb5cf26a` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 22 | `b0e259ae5ca96ce779400d75f4dc46091a83b410ad6779fdc34ab8179b4e6835` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._
