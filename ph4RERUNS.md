# Phase 4 mainnet transaction log (rev-3 tree)

Auto-generated from the suite run log (`/tmp/claude-1000/-home-cq-working-files-c-bonds/ab8d06a0-9daf-4b0e-b66e-726023556687/scratchpad/reruns-combined.log`). Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract (**BOND'** = successor box), **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.

This is the Phase 4 green-run ledger against the revision-3 tree (terms cards, card-pinned matches, coupon installments, missed-payment acceleration, cure, card refuel). **POOL** = the pinned Spectrum ERG/RSN pool box. **CARD** = terms-box contract (card NFT singleton). Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability.


## T1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 1 | `4095600f6b083325716534e179e365400365845da16091bfbec5aec44da9cb0e` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 2 | `af520d3453d388f2a09131cafc671fabe24907bc6b7e03a2a7c62b936ff387c3` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |

## T2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 3 | `538a88565a9afc73217225dd1851544b39a01ad14308759ae540fe5cc1262ea8` | borrower-signed repay: repayment+receipt to lender script | BORROWER spends BOND → VAULT | 0.011 receipt |

## T3

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 4 | `d08233d10aa29e43fe053c488e7ddb3fc4d29d083114866b0a4f50f5da6c4308` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 5 | `b3b3177e7242ad142131d853e588f0742ba6fdeddcf93f5a16c648cb18f277dd` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 6 | `db1a847c0dfd5cbc1511b9034dc4e35eef26d454a31697e397cb4e9fc861551d` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A1

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 7 | `67f6a7c08383b0077a3189ac275a983b24ef01ffca926086a6e7e9c715882a5d` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 8 | `477d90ba29fa69031ccc057e80258982f9936b7f836d257723f8be4226d1915a` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 9 | `0493b582c7b7bf2bec8591631eee7082c537daf08d9b31b836982b774d61fac5` | borrower-signed repay: repayment+receipt to lender script | BORROWER spends BOND → VAULT | 0.011 receipt |

## A2

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 10 | `a15ef60d5526fa245038da0ad11dc4a9e369055b32dd5b3f8e49cf05323f4770` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 11 | `9dbc03ae63c1e7fdffed674c0d0c5a0a63de07dd10d662b7c43fd4404c0f6490` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 12 | `46cf40e8fc97bde50700730cf0e64fd7e10a7713a4087bb9aa9a70b53cb4d596` | borrower-signed repay: repayment+receipt to lender script | BORROWER spends BOND → VAULT | 0.011 receipt |

## A3

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 13 | `90a48a1e0d4e65db531e30b9719486f4d764252ef61976e524d24a1062626927` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 14 | `187faa4f66a6e53c8f4dd1d257274e46d94f1dd4277be3a8ad46a780af3ddf03` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 15 | `653ee487c4cecc07d7a1b032927998960c0cb0531d94cec4c2361d61a47b60c2` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 16 | `5acfe4b001409555e597547a1c9f25b9488688d0f3b30e2cd89e9b811cc6e142` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 17 | `755204e399e3c533a21bed4eca6412c4fa2bca41ec6229c7453a3faa47468810` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 18 | `1e658085c6777f5c4a7f7a9723dd9161864a213fdd1710650bfd1f78eedca9eb` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A5

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 19 | `1ba08c5b5a7ee98ab3eaca22f3e349292b1492d2bb8dba643907d6de8bd86458` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 20 | `2e70cd77097dd18c83eeba882034b177fa895fb877cfd465c7a60aea6d5433b7` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 21 | `f099789af52c3dd9d296eb88d32755d9151c19b5a83e146a5f7b051e2f38f17b` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 22 | `36ece6f86cfffd68d802bc881275b4bb37c86d504e1c9885860d51685e8773e3` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A6

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 23 | `a6d16f8f617e64431c7c9844b5d4e19c819dd136f795ffada4437956b1606354` | A6: forge a bond directly at the bond address (no order) | KEEPER → BOND (forged) | 0.002 |
| 24 | `6646ece57c0f6253fdca2916b91cfef6a2250ec22a66e34c16ebdcee7e0d54e5` | A6 cleanup: forger self-repays, recovers dust | KEEPER spends forged BOND → KEEPER | 0.001 |

## A7

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 25 | `9a55ceb4898c65136458f81bf3ae1fb0d4cd52771816fa84b48ad0ee79e62b02` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 26 | `75909283f7f53c0935d7f2b42c7344bb3d731782269344196fb4590430ef78e9` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 27 | `1cd835399aaac18b0d2dda465e04d700f3e47adc3f83485d2d8d65449d59f953` | signatureless liquidation past maturity | KEEPER spends BOND → VAULT | 0.017 |

## A8

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 28 | `71f5f35fc6f102e105be84468f6d01c3f6406fc06bc54e451fb01b90f597fbc2` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 29 | `ab79e29639e4fdf1e248d066f4bffa623bc8bfa565060fa1e1018c1eb4dfde1c` | A8 pass-twin: plain cancel recovers collateral | BORROWER spends ORDER → BORROWER | 0.020 |

## A9

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 30 | `6349d57856fb3f3822b49033ea48b770de7201486e568e87bebd9dd4a3a73634` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 31 | `31150b090833e5c8e5120de1f7a3697bb456c4dbac939e8c6ed4550856969b4e` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## B1-B12,B14

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 32 | `e75315e8fc6531079f951a3db9e394c8f601d4c59419eb8cdc8bbb8ced017452` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 33 | `c0b7ab3b0336bdb100e8d2f9aea9a1c22c27c042b336a2c8d29d0a6634bcb63d` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 34 | `45740cdeb07cff3658fa96ccff3884eb7be63d227702a2a238a66b0c100c51e0` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B0

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 35 | `d2898b4a3d7be7c83efcde6758d1b214859707bf957e7bfca5b62d9fdd7c58bb` | borrower posts a deliberately non-conforming order (B0) | BORROWER → ORDER | coll + claimed escrow |
| 36 | `73aa90b868dabd676498c556a4bd1a03bfd0470963911252319648a11a32edaf` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 37 | `aab46a0be162d8b46f203b56bf500785ea530cacffd41152c28af9d447cbd161` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 38 | `0bbe9b852760057c640814a3868e28c493ea7947769e2a3367d6d7f6ec79b435` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 39 | `8af6108441d1935f571503c912cf6e720ab3bb3035b3b7961ad8209c2a18e0b2` | borrower posts a deliberately non-conforming order (B0) | BORROWER → ORDER | coll + claimed escrow |
| 40 | `10c999cdd70906051ab615e8cc16698101a5cd5358511149505ca61323cff8a9` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |

## T4

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 41 | `60641943f3134cc220714fb932e11b0fd8ee9e4273a87d438fa2597d7b050824` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 42 | `8bd01faaf8b7eb434b3320248717ead411bfb13ce21bb0a2fd1561dae168da71` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 43 | `07521585142b7df91eacdd50d09982bd00754d0a96795b39d8eeb256adda4fef` | signatureless checkpoint crank: successor + bounty, bond sole input | KEEPER cranks BOND → BOND' + bounty | 0.005 bounty |
| 44 | `a0aba950cb8c254688cc8a61a95487743144bcc3a9e0d8699fae0bfa05314e0b` | borrower self-crank (pinned decision): same crank path | BORROWER cranks BOND → BOND' + bounty | 0.005 bounty |

## T5

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 45 | `2d78787cd3156b309177ed43add652f7aec28c7c8c29ae15938782dfa25ab9d7` | borrower-signed collateral top-up, schedule frozen verbatim | BORROWER adds to BOND → BOND' | +0.005 coll |
| 46 | `ab9b3488021c5973e0f1e14acc81eadb71ec988f4b835272d39afa6dcde2a243` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B13

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 47 | `39524c1d617f8c68a6b443476d0cc4aeae3740148f2cc53eaa71b3882ce2b693` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 48 | `173cb36ce9b886daa3567cda3ee08b63fb288feb589e67532eaf08997ae203a8` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 49 | `e293a4b63ffd5547cf7e5bbc26d65d3ed8c276ac6d12b0e777461a0fb982c41b` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## B16

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 50 | `4ab2c98a36ce44a6a14957e10c4b7088a78da678bc1e1a126188a98ec92b0c37` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 51 | `d3ad38bc5798b0972d5d255d40294d0618b81786fef231daa7af5b7c47c689c3` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 52 | `00c01df6fc28fd61c6c97ceda48059d5d4b3995d77dc0da33aeec329a558c025` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 53 | `ca1f64f9bd9e00ffdf38f77d1cad51e663ccec545bfa65dff849f37148f9cb8a` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

## Phase

| # | txId | what it does | from → to | amount |
|---|------|--------------|-----------|--------|
| 54 | `b634a1a8f2fcb9cdf15c96a49b233528649ddc9b85ebfb100970932b52824df5` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 55 | `49cd3dadb97bea5c6b716777e098b64b70ad841da0e4ad7e49906fea508196bc` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 56 | `ab327f18ef69aeddb8a54c8eea7f588b86512f6715a16727fe0d562b2a7857ef` | mint token collateral for the token-wall tests | BORROWER self-mint | tok |
| 57 | `bc4c47e32f17ee8eeeb29d92ad5f23487e2034866dbdd3cdb9a6adda9dfc73a9` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 58 | `f3a1c7c09af9e9d6f18e66a481aeaa7df6f3f24ee1778b5450a14daf4ee431d9` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 59 | `8f7348b1d49b92b99ee80b68222cd37beec64a275e8d8a28382fbd7181b5da5a` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 60 | `61f9bf69d3bd40fa00b77501747e496d3edcfec599c688ebf0ef0e26b363bc38` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 61 | `af4ed0514695d12818d767b003ff2ab0c83d9042d3efe8bb2f5e6b5d74b410fe` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 62 | `b5ea68543e59217bfebaa39f06b51dbab1a4b24737ecf3c8111d68c1f44ca0cf` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 63 | `a7a8f909d1718111441fcf0be0eafb0f8276f118c097aa39c9b9af4349077858` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 64 | `1a586c145fc3273d10f49cdc7c4c27eaaa3034ec317d3972a5b600ed06b1851a` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 65 | `65fe60a187379fdba33721c30bb137d20f12a9ddce56402d6bfb6fdc65b7a539` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 66 | `86613a195c38593e786ca9d679da63f42d4bb6bad76368e9f87bac3794486dc0` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 67 | `a10390652e1fdeabf5e024c44135536edab544801e76224c01981afeca3b2001` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 68 | `0b63973da59a6beb1acd6efd2d1ec23b50baa963e49051365979dc663db820b3` | cleanup cancel: order recovered (collateral + escrow) | BORROWER spends ORDER → BORROWER | coll + escrow |
| 69 | `625cdda0c76b64517862c2150045f0f3e3f3b9f89faf25dfc11266685caae4b3` | borrower locks collateral (+ escrow) with requested terms | BORROWER → ORDER | coll + escrow |
| 70 | `3a23f1e501c1b4e30f681e045460a46218bfeef7df9c872636a97af776b2ebd7` | spend order, mint loan token, create bond, pay principal | LENDER funds; ORDER → BOND; principal → BORROWER | 0.010 principal |
| 71 | `10098a6265b2775ecfed0110c69da6896917f5ffff82800ba34be34fd62b71d8` | cleanup repay: recovers collateral + residual escrow | BORROWER spends BOND → VAULT | 0.011 receipt |

_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._
