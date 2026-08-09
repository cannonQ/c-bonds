# Phase 4 mainnet transaction log (rev-3 tree)

Auto-generated from the suite run log (`phase4.log`). Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract (**BOND'** = successor box), **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.

This is the Phase 4 green-run ledger against the revision-3 tree (terms cards, card-pinned matches, coupon installments, missed-payment acceleration, cure, card refuel). **POOL** = the pinned Spectrum ERG/RSN pool box. **CARD** = terms-box contract (card NFT singleton). Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability.


## D9

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 1 | `7a09b9ead723f6f4147412c089b7ff357c3b1068187abf84ae48030b33478bba` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 2 | `b9ec9c95e097b00124b086b188b64f336bf19bdf992c4b78689c39e147f17100` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 3 | `bb9f496ba06dcce41418d6f3e4620b09bb90e34fd3dbcdf9ccf763ceb11bef6f` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 4 | `30fc0694959368637e12bab3c575c298f81de7f0a9168f868d9302e184b6fed5` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 5 | `7e78553da4bb447c1ebad1469332b69be45e39920496484ed666b6af598e07d0` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 6 | `860dff58c80328acf6f8e4381d7a79ecc24285b9555068dfa39cc457377b5dc7` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 7 | `53a6ecb676a0987701f7079df194722c2b1fa5009553a3c6f8905d12e3438850` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 8 | `055cc5bd552dd44c79b7be6662a4abe0a7bfca26babdb98da7c5d6f476f3782f` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 9 | `9778d2c8b14d8091f3eb1bc3c7db4da461a6ca824e87d9893c2255e5667cd815` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 10 | `130de0528c9177366da28c8e48be5523557a27ad788935b335ba8e37d02fc998` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 11 | `6903fe9898fa5940ebd68bdab0b2c8a23ef1817bab3a04983f41109e2ec97a76` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 12 | `6770ea14ecc31cf324813f63f0966c1f0e08b109577f750d34e1c9285bf6081b` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## Phase

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 13 | `c5cda35f24d16b802a9db3ca63c8585f9e3043a873838e846561c7d836e4dcbd` | mint terms card | BORROWER → CARD | 0.001 |
| 14 | `991a834e121c074420cb6c7778a27f99a62f72579e6a0a583eb6d95dbfe161a8` | mint terms card | BORROWER → CARD | 0.001 |
| 15 | `bd550c2fdc19b7437a63110a01a3cdedb0a141e5343851b601aceb9c89b43843` | mint terms card | BORROWER → CARD | 0.001 |

## E2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 16 | `a84138c3ad516b20450ab7208265b9d4d4b787656c910b5d71280e50c4985ee9` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 17 | `ded508c2c6a00ac52092ae4b6a92d417c01f5d5f8c71d21d80840470340c8603` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E3

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 18 | `d5eabec46cf62f8d24c5d50a3c70f27432bf38e43a61c284db320806b823736d` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 19 | `83ad68131170f34de0b36e939159099e5a1128ecbc88587a97a6bed083cfc8cc` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 20 | `8d102b58e4d7edcd33added94bd6541b7809de73bdfb77c3149ff943145d05db` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 21 | `7e2cf1cc1368b7216dbe0ed679c52ca09f5bbd5be7fbf1facbe7329b15817673` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 22 | `38efe47534077ef97837fe543ef545538234fc1a67f64742761b125f3fd4f632` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 23 | `8371aa15c745a404aa5221c89a3767a641840603a27d6bc2c081ae7e10236fda` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 24 | `c560cfcd2b9f5480fb84878a806fffa0def0e1a526333e1347446a2124b9b333` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 25 | `223268e12d40e37f495f7bdb4fc239763aeed48459e8ccbe6ad5926877e0d2c8` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 26 | `c0fc732dc074ca1b7fcd34a55f5a45754a46bf78093d1f7de1055f59984c1080` | E4 boundary-twin order at a card bound | BORROWER → ORDER | coll + escrow |
| 27 | `b992f8a5696ffbcbec088753d12ce5e6a3cd346c270446d275e892e9609797ad` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 28 | `a88073854156ad4fc82cc3285ea0f59acda175b58d9b8b1bf216695eb6f599f4` | E4 boundary-twin order at a card bound | BORROWER → ORDER | coll + escrow |
| 29 | `556b68df3b6d4024c111ccd752a0f6e4f562b1d9401e5a2dae435d393c66b876` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E5

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 30 | `bbad96d344884259a2c41dc18784a0e811e7b86abf4419a76e22cb9186571a86` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 31 | `5cde7ad42603d63e31a53bc20237964245b99737e549ee5f09ad032e8e9ad03a` | cancel via borrower-script co-spend (the rev-3 authorization) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E6

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 32 | `b4447a898b8a00437ab6b20cc3251083320abf088c7ff301f8689048109cdbb8` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 33 | `fc42802bf3d4c8c21823d80771dbf5a3752ae0dc89aad532e1a27a6b6b797b40` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E7

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 34 | `5e7ef1b0b0e84970e3f0d7eceeb1d2009e4e3b9c07c3bb709aff14a8649a7530` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 35 | `ebe4091cc55f0a3386740b89f605c4cd7d4cbd247d20c420cea1a074d0519d9e` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## E9

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 36 | `ea56778b23c6e5f49b25caa11b759e89954aaf349d861715cdb6b24e02a58396` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 37 | `973d90d7f3972d090543cf1c48267798a0ba7d8ede39c7854c281181c3ffe590` | wall test order posted (unmatchable negative or boundary twin) | BORROWER → ORDER | coll + escrow |
| 38 | `708fa7ad9409aba57dd9040695d5453cb6d3f780e52f4132b4f51ed239cbe797` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 39 | `c876c94a8d32c2503c28abf9ff8a3db94164b177d207b379b948c44585122402` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## CARDS

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 40 | `67412c7f749c97bd717c129a659c7af5012db80165759707e6527b309a486ff1` | mint terms card | BORROWER → CARD | 0.001 |

## H1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 41 | `a2d985a26a8ad73356f2bd7ab9845abe6f219842cad79ed808ce5617efcfaf01` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 42 | `1289f02fad9c43741ce7978bc73f8a59e9864888ce79bae13f11ebaceb76754b` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | principal |

## H2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 43 | `2a43dbd69f60b4b58a1924b4024f3ca35dc50f2bd0c2422030aec60e9fd62441` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 44 | `1cffacbfcf5b049c5ee63d963eb3a05a939a498046a1bd10f724c87711528a11` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | principal |
| 45 | `1883d5ce6722214c6a33fa287d2a705d714e875d87e31cca3e1951b8169fc1f4` | coupon installment to lender | BOND → BOND' + LENDER | 0.006 installment |
| 46 | `4b16be4c0a410601d4d41c2a406b50f9a633df7857d422ed7c62d08a6ad730e0` | borrower cure: health restored per pool data input, grid resumed, escrow untouched | BORROWER adds to BOND(cure) → BOND' | +cure top-up |
| 47 | `937792540698f14569bdd13615b9467c69157026b38e3f437965ade4f61e6932` | coupon installment to lender | BOND → BOND' + LENDER | 0.006 installment |

## H1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 48 | `0e073280ef5f818c63c51c48e98767ee86a2eaed1b03dd86d4a6415d516d47c1` | coupon installment to lender | BOND → BOND' + LENDER | 0.006 installment |
| 49 | `d6e2714d10178dffb1954a9f75297307d2b9c0307a7d9db9e72391622ce8b883` | coupon installment to lender | BOND → BOND' + LENDER | 0.006 installment |
| 50 | `53fcd4c4ed17e408a28583725fd62e73e7d0840e6914219874509cb275cca2bd` | final payment IS the release: repayment + receipt to lender script at sched(2)==1 | BORROWER spends BOND → LENDER | 0.020 receipt |

## H2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 51 | `4c78954c00f6a055c16e26e4c423fb35377d2f58cce7f2138c8a0058bc2fec9f` | missed-payment acceleration | BOND → LENDER | coll - carve-out |

## D4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 52 | `95843abef3a1c74d860be70ebae899b2fa764a5678b43af9988df8df978c4368` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 53 | `67dfdec8764cd4fc483b46ccfa9ed703117cbc2b4f08d30ce18d0751e195f265` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | principal |
| 54 | `c274685f63e11627df47ac631f3186b7e6b74af762083b2a50671b82fca0b3ea` | coupon installment to lender | BOND → BOND' + LENDER | 0.006 installment |
| 55 | `1268061d0e2d5d6911ba0c57a5f22f005f806c763a2e8c992c11631d5fb97cc2` | missed-payment acceleration | BOND → LENDER | coll - carve-out |

## Phase

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 56 | `9a1afb101b0010bc4cfc9bacc7af523c7212b95e73da3bc0ab90b2bf0d5286c2` | lender-side unweld: receipt tokens burned, one clean box | LENDER → LENDER | — |

_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._
