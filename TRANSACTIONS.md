# Phase 1 mainnet transaction log

Auto-generated from the suite run log. Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract, **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.

This is the **run-3** ledger (the green run). Earlier runs' txIds are superseded. Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability.


## setup

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 1 | `c477d622e5a4bcc97d154b3aedcb4ded4e8935bfe7bd10c4944d31ba73c3de23` | user funds the test wallets | user wallet → BORROWER | 0.5 |
| 2 | `0eab60fbf76289cab238205c7e3e421788db9d6cbfc45a0a0a69c0755678842e` | distribute dust so each role pays its own way | BORROWER → LENDER 0.20, KEEPER 0.05 | 0.25 |

## T1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 3 | `4597c6bc2f90c6b9d24e0aef44cb2d649fadb5bda0f9a93da61b8197ea5986a0` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 4 | `b29cf6a96c882a0fa749631b2977479754fd346a835aa6f8a26800414b13d8e0` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |

## T2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 5 | `5605f1e89f88c3682e5b1464406117271ac7ad359df0f88e095d0b374e14ab4d` | borrower-signed repay: repayment+receipt to lender script | BORROWER spends BOND → VAULT | 0.011 receipt |

## T3

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 6 | `c7e8a235dc93be512578b00f3df3463ba59821a80c4ce2740aa529d64498ac09` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 7 | `9f5573e6e505c6ea423362ab2e9bf989056fc51fcc586ac1aa0e1d7a3a43c121` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 8 | `708af7262f3da67d444cc7b4cb99b48340a6318cc19a01ec0323ab73ebf8e22b` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 9 | `39a48fc945cf9b1a555f14fe93847e2fbea90052aba6a3d62c44fa167f335eff` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 10 | `6c2f9f17a9e7df483ad1a1b94de5acce6c10a55881ba47b3d06aff2f27c83eec` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 11 | `cab6a7fa381f114adc80cf47d9b76a2f634b47a2e4144abd70fa4252821a2db8` | pass-twin: honest repay of the same bond | BORROWER spends BOND → VAULT | 0.011 |

## A2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 12 | `006fba4b8a0a4cc210ede8603a0b8d090f306d40d8c1f06856b923676c5c05c5` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 13 | `88891af117e505826b0d0e51cd5ed9936b9c60a1e25e6da3c444961cf2093bf0` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 14 | `9cda92f653ccf0f7be975181306914ced139a399e15db50250b07a18f78e53fa` | pass-twin: honest repay of the same bond | BORROWER spends BOND → VAULT | 0.011 |

## A3

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 15 | `7c88b6df14b62ec876bd488d9b679a11ad4d9097ce2451ee5967ff9ad6e77182` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 16 | `167f813d2ee7292587068ba18595d58cda0305c66a7f144c2e7c983ed91e4fcc` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 17 | `b6b8095193fa85e37db56b867adc8ec30268c5d925616a6d5fcf32bb5e5a09fb` | pass-twin/cleanup: honest liquidation of the bond | KEEPER spends BOND → VAULT | 0.017 |

## A4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 18 | `cfed1bff3f4c340aa2267050df029af1bdd21a143d52bbcffec6b04beea6b319` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 19 | `dc1f7d7dcddd49651d606d3f69ef7e5e340e36e2dd0614a0ea3c694d8220ca7d` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 20 | `35b6bb7cfea32d117f9f59a66ef7e89773273877e2a3e506afb311a4bdd73aaa` | pass-twin/cleanup: honest liquidation of the bond | KEEPER spends BOND → VAULT | 0.017 |

## A5

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 21 | `ff0a3792ebd835bbbe49d5acdcbee1308a14a99987ac8ff76800547bbd69e232` | mint exactly the token collateral for A5 | BORROWER self-mint | 500 tok |
| 22 | `26c714aa3d9c3d4c56727d1dbef244cb0ba15e9827f5615e655fdda36df1d5c1` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll + 500 tok |
| 23 | `86d1187c93bb1897d346956709c30de9a06098cafe088d251f9a33678831f0b8` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 24 | `ece1f0303345ead83e1f33f0ed18ca42cd379713b923615a2572cb2b8e92d3e7` | pass-twin/cleanup: honest liquidation of the bond | KEEPER spends BOND → VAULT | 0.017 |

## A6

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 25 | `9702efe23d3ba6a1b279e49c19540f86be3a940fd9d4bf38896d0635a3e21336` | A6: forge a bond directly at the bond address (no order) | KEEPER → BOND (forged) | 0.002 |
| 26 | `517de4528609525092ce930013f925ff0ccdad93452d652d32bdd3d597a510c9` | A6 cleanup: forger self-repays, recovers dust | KEEPER spends forged BOND → KEEPER | 0.001 |

## A7

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 27 | `35b1ac5a9964a4281eb7fd6b80e8dc0cbd636583fca5385a58e5a3911673c5b6` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 28 | `470cfa70504ced2126c3744a07b939cd99be1174a55f46168bba0da2490f87d9` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 29 | `7af396525a61f59e585c553456a4e08b699c83594b2254f7058f84cdc0ad1456` | pass-twin/cleanup: honest liquidation of the bond | KEEPER spends BOND → VAULT | 0.017 |

## A8

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 30 | `379cad7422205a2ab006fe0b2df9df375f898bc88cce3263f09b5b5f3e9b6b08` | borrower locks collateral with requested terms | BORROWER → ORDER | 0.020 coll |
| 31 | `b822bbed28d1b84c147cf2fb88f17e0e4672ceae23d1e20b891e19f8444263df` | A8 pass-twin: plain cancel recovers collateral | BORROWER spends ORDER → BORROWER | 0.020 |

## Hardening re-verify (MED-O9 loan-token supply cap)

Targeted origination run after adding the supply cap to the order contract
(`runMain bonds.RunHardening`). Confirms the cap doesn't break matching and
rejects the over-mint. Order contract here is the 620-byte capped version.

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 32 | `51e38e81d579efe4a8e4b3fa222a3250243a153f3a2dcfd9c9e24adc794889a7` | happy match still works with the cap: post order | BORROWER → ORDER | 0.020 coll |
| 33 | `aaf335fe7cab11cd95a0c19b912256c7931f4f5819dd663aa5c8a2a17d099ef2` | match under the capped contract (mints exactly 1 loan token) | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 34 | `8dcf30d3737ad34b281a472fcf8dc3044d2d40afe109481838e2693f327e2ffe` | repay the capped-contract bond to the vault, receipt verified | BORROWER spends BOND → VAULT | 0.011 receipt |
| 35 | `74c55aebcea7946ba27f2d9d22739a715911eec1329034d1e967e3488f93774d` | A9 post order (for the over-mint attack) | BORROWER → ORDER | 0.020 coll |
| — | _(no txId — rejected at proving)_ | A9 attack: match minting a second loan-token unit to the funder → cap reduces to false, never reached the chain | — | — |
| 36 | `77f23def513e10b553d2e2869ed55a10b4eb74e214a75dc9ca94c063aa569f0a` | A9 cleanup: borrower cancels the untouched order, recovers collateral | BORROWER spends ORDER → BORROWER | 0.020 |

_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._
