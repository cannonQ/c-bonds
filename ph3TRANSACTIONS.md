# Phase 3 mainnet transaction log

Auto-generated from the suite run log (`phase3.log`). Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract (**BOND'** = successor box), **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.

This is the Phase 3 green-run ledger against the revision-2 tree (covenant checkpoints priced from the live ERG/RSN pool data input, cure, acceleration, C-wall). Revision-1 lifecycle txs live in phase3-run2-rev1tree.log. **POOL** = the pinned Spectrum ERG/RSN pool box. Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability.


## Phase

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 1 | `7ddb4712cfd0e41309ba99e451c706e3047fc0cc40f6d89a0b83bf598cc02a3d` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 2 | `d3a0db55abc3adda22ec1ee601f282696986eb86116f8147aff1e0b56d55a862` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 3 | `be82813ce9b5a85fa9eb22076bf6ea85052ddd54c281b69aa83bb73b63576aec` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 4 | `9e2be58c5a73db2aa16330c8d5e7c910ae6fe76351221a4ad6b7309036128a11` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 5 | `8aac95ba1afb5cff30c58e3be757eb8267db74453e901492aa28ae5a4477f4cf` | covenant checkpoint: pool prices UNHEALTHY → successor enters cure state (deadline = checkpoint + GRACE), one bounty out | KEEPER cranks BOND → BOND'(cure) + bounty | 0.005 bounty |
| 6 | `610c24c88e48af071ed682396645c9312a89d1eedfd40fea2607a82ad2325519` | covenant checkpoint: pool prices UNHEALTHY → successor enters cure state (deadline = checkpoint + GRACE), one bounty out | KEEPER cranks BOND → BOND'(cure) + bounty | 0.005 bounty |
| 7 | `2b3b0924601b598b4564df8b6cb47253955001513a502fb09f4f8f9a2f934f3e` | borrower cure: top-up restoring health per pool data input, schedule back on grid, escrow untouched | BORROWER adds to BOND(cure) → BOND' | +cure top-up |
| 8 | `d5c8ca7725be16011581c6655f67732c56b3a3e949c5bff57d43506bb990fd0b` | covenant checkpoint: pool prices HEALTHY → Phase 2-shape advance, one bounty out | KEEPER cranks BOND → BOND' + bounty | 0.005 bounty |
| 9 | `6e89ee5eb83a0d89095dd841b6dac3c3a823c06091b71639023e108d5eda5aa4` | signatureless acceleration: blown grace + unhealthy-now, liquidation shape before maturity, residual escrow to lender | KEEPER spends BOND(cure) → VAULT | 0.017 |
| 10 | `4a6d6c466ea605b3a1d06ab787c3b4ccc432e7443c744e2442ca71402ce61d67` | repay of a cured+cranked covenant bond (exit wall across the successor chain, no data input) | BORROWER spends BOND → VAULT | 0.015 receipt |
| 11 | `5f7b90ac5e11f977da2e4cca51863c119def7901900b20d951e86a7624875650` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 12 | `a94179fdacf24fe99d167a27127a7ef503e0cfb0cd58003a297b3fccd4cd2689` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 13 | `d031ddec0e9aa0e1dd7180a203f5412f8a7ec21f37ac19af52c45145794dc906` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 14 | `770305c54c691a9698e22d508e977713ebd91cf5a36c341889ca58549bfe80bd` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 15 | `cb31022f45ca96974e7431ade99964504e477bc00807927905133789b62aa639` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 16 | `008379e8b8c44ea8792ee253c57788d44e42cab94b05dfb29ca2aaa15f23de8d` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 17 | `98c1807c8938f24167f77d4c6439188d084f0a093ca5ad8637db9fb42c0b4d91` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 18 | `30a20255505123678e70363f2227fb88b68b352a9641895310a4ba275f4d3218` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 19 | `f553a32632410b2bba4a4c1b1b13c78e5cac19893df00b2f17b77a3dfc8f49b2` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 20 | `52714aecbebcee2bb1ef026046a3c23a3f4f83fab4b9c47aa73a736deee53d9f` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 21 | `f933baccc571d02bed1b5a1ca7667f21f9e077922cec8f5a31f15d3355a76f9c` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 22 | `3ceb3b457df0429a7cb19bb99e86fb750d8c44bb1a9eb6ebec0ac5dbe0966726` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 23 | `4825a7c28cda167c7614f226cc147bfbcf9c583c0e561cf024582c56026f66c9` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 24 | `4566879d9961eea635caa42cf0e5703baafb20f4f8246a2dfe4e313fb2bd941a` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 25 | `8759d454400b1d8a1efcc46e28cad52e04cc31c2fe02d7133e23ac5beedf3d93` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 26 | `ff9bda8c54e524b742866e7f08d9bb477d619a55f3198c2c0dcc68fbe5dff7e5` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 27 | `6828c458c46c0c37f9813d15a1e84a69876efd48b7dd41fdf986fdc2a678013e` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 28 | `1d3c036487f4b9fc61ecc7c6e5160a905093349a40e342fbfe63c0106094def7` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 29 | `ad693bf7075b9c1eee8f6b3328484bac4cfba607f3f0d26b1b125e66c2b7a658` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 30 | `737c3700fb91886a50bc77cdb7a92f9c910e7496604c0f3f7789bc5ac8d44c94` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 31 | `9e4a752227abf6b184535d6b515cae618c9850f72f7ceec60bd9c2700ad41138` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 32 | `4e1d3958b420aba88f6f6c6f9356898d1f7e2da3a8685c41dd5735d20856da94` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 33 | `48b6a023bd9564dab47d6daa9ec9ca050ddc8fcf972f12554b3c2aae9eceef5a` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 34 | `53c52a237aea72f401e56344cb194d44d8c96185b4194dbc23c885d04ab12eec` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 35 | `5b27cf7654717e0cbd7b673b4df947fca4a26b618a04f9f3187926753d8b0885` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 36 | `7d4b5ca26bd3646ad6f04fe528a94186d20507f05dbff1fc61e1d03067f798f4` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 37 | `f2b3846f0b7e2af6656e9eb7bef75e03aa70e2196c1dcaab72c979f6052e7760` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 38 | `7b8e59f57034d66850af3c7268ce2d03b8632d6e29487c41b69d4718455ec890` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 39 | `ab326299dc0e9517c405452d51504827c32dd0002743631a88b0159d980f3631` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 40 | `cbca271ded443656c55e63bf51e1e40c19a53c3c42dedc5c1d67bdb0ff7f1719` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._
