# JitCost per path

Measured on mainnet with `prover.reduce(tx, 0).getCost` (exact, same units
as the node's 500,000 per-input budget). Cross-checked against the node's
DEBUG script-cost log. Raw per-run measurements accumulate below the
curated tables.

## Rev-3 tree (Phase 4: instalments + card layout, 2026-08-08)

| path | JitCost | % of 500K budget |
|---|---|---|
| carded match (1 data input) | 252,770 | 50.6% |
| card-less match (0 data inputs) | 252,443 | 50.5% |
| order cancel (incl. both malformed-order probes) | 251,554 | 50.3% |
| crank covenant HEALTHY / UNHEALTHY→cure | 311,931 / 311,943 | 62.4% |
| coupon covenant HEALTHY / UNHEALTHY→cure | 314,786 / 314,798 | 63.0% |
| coupon covenantOff (on-chain, H2) | 321,548 | 64.3% |
| cure top-up (gate / on-chain H1) | 314,496 / 317,007 | ~63% |
| covenant acceleration | 311,911 | 62.4% |
| missed-payment acceleration | 311,187 | 62.2% |
| repay, no data input | 314,072 | 62.8% |
| nonzero-installment repay (sched(2)==1) | 313,670 | 62.7% |
| plain / hooked liquidation | 311,525 / 311,544 | 62.3% |
| attestation generic branch (fabricated, local) | 311,884 | 62.4% |
| card refuel (value-grow) | 57,494 | 11.5% |

Bond paths rose from rev-2's ~40% to ~62–64%: the single-sigmaProp
boolean top level evaluates every arm's gate chain without sigma
short-circuiting, plus two new arms (coupon, missed-accel) and the
R9-suffix machinery. ~36% headroom remains on the heaviest path. The
carded match costs ~300 units over card-less — the card data input is
effectively free (CONTRACT-DELTAS §5.3 closed).

## Rev-2 tree — Phase 1 baseline

| path | JitCost | % of 500K budget |
|---|---|---|
| match (order spend + loan-token mint + supply cap) | 133,501 | 26.7% |
| repay (borrower-signed) | 52,697 | 10.5% |
| liquidate (signatureless, ERG collateral) | 52,722 | 10.5% |
| liquidate (signatureless, token collateral) | 53,145 | 10.6% |

Match rose 119,635 → 133,501 (+13,866) when the MED-O9 loan-token supply
cap was added in the hardening pass. Worst path (match) still ~27% of budget — roughly 366K of
headroom, ample for the Phase 3 additions (pool data-input read plus
swap-simulation valuation) without redesign.

The match path carries the loan-token mint plus full order→bond register
and collateral validation, which is why it is ~2.3× the exit paths. Both
exit paths are near-identical; token collateral adds ~400 units for the
extra `forall`/`exists` conservation pass.
| match(order-spend + loan-token mint) | 133501 | 1843529 | 2026-08-04T00:29:19.415586Z |
| repay(borrower-signed) | 52697 | 1843531 | 2026-08-04T00:33:04.694118Z |
| match(order-spend + loan-token mint) | 134964 | 1843576 | 2026-08-04T02:31:45.623527Z |
| B-wall cleanup repay | 130064 | 1843577 | 2026-08-04T02:37:45.992821Z |
| match(order-spend + loan-token mint) | 135397 | 1843584 | 2026-08-04T02:47:01.348212Z |
| B16 cleanup repay(token bond) | 130464 | 1843585 | 2026-08-04T02:50:01.491219Z |
| match(order-spend + loan-token mint) | 134964 | 1843593 | 2026-08-04T03:04:27.146892Z |
| match(order-spend + loan-token mint) | 134964 | 1843595 | 2026-08-04T03:10:52.378514Z |
| B-wall cleanup repay | 130064 | 1843597 | 2026-08-04T03:14:52.745336Z |
| match(order-spend + loan-token mint) | 134964 | 1843616 | 2026-08-04T03:47:24.087438Z |
| crank(keeper, signatureless) | 127696 | 1843624 | 2026-08-04T03:58:54.216759Z |
| self-crank(borrower) | 127696 | 1843630 | 2026-08-04T04:09:39.350148Z |
| top-up(borrower-signed) | 130184 | 1843633 | 2026-08-04T04:20:09.460914Z |
| T5-cleanup repay(cranked+topped bond) | 130073 | 1843635 | 2026-08-04T04:22:09.524730Z |
| match(order-spend + loan-token mint) | 134964 | 1843639 | 2026-08-04T04:31:24.779882Z |
| B13 cleanup repay | 130064 | 1843648 | 2026-08-04T04:45:10.066376Z |
| match(order-spend + loan-token mint) | 135397 | 1843655 | 2026-08-04T04:54:10.373825Z |
| B16 cleanup repay(token bond) | 130464 | 1843656 | 2026-08-04T04:55:40.494873Z |
| orphan-recovery repay | 130064 | 1843659 | 2026-08-04T04:59:46.034045Z |
| match(order-spend + loan-token mint) | 134964 | 1843668 | 2026-08-04T05:16:50.320657Z |
| repay(borrower-signed) | 130064 | 1843670 | 2026-08-04T05:17:35.625581Z |
| match(order-spend + loan-token mint) | 134964 | 1843675 | 2026-08-04T05:25:05.940121Z |
| liquidate(signatureless) | 130070 | 1843686 | 2026-08-04T05:42:21.053797Z |
| match(order-spend + loan-token mint) | 134964 | 1843690 | 2026-08-04T05:55:06.393232Z |
| A1-twin repay | 130064 | 1843692 | 2026-08-04T05:56:51.475513Z |
| match(order-spend + loan-token mint) | 134964 | 1843698 | 2026-08-04T06:09:51.791804Z |
| A2-twin repay | 130064 | 1843699 | 2026-08-04T06:12:51.871436Z |
| match(order-spend + loan-token mint) | 137456 | 1843701 | 2026-08-04T06:19:37.094515Z |
| A3-cleanup liquidate | 130070 | 1843711 | 2026-08-04T06:37:37.229357Z |
| match(order-spend + loan-token mint) | 137456 | 1843716 | 2026-08-04T06:42:07.457791Z |
| A4-twin liquidate | 130070 | 1843726 | 2026-08-04T06:56:37.564284Z |
| match(order-spend + loan-token mint) | 137889 | 1843735 | 2026-08-04T07:11:07.865124Z |
| A5-twin liquidate(token collateral) | 130491 | 1843746 | 2026-08-04T07:29:22.989110Z |
| match(order-spend + loan-token mint) | 137456 | 1843756 | 2026-08-04T07:43:53.436827Z |
| A7-twin liquidate | 130070 | 1843766 | 2026-08-04T08:03:08.551254Z |
| P3 gate: crank covenant HEALTHY (local reduce, live pool) | 202889 | 1843931 | 2026-08-04T13:31:12.323399Z |
| P3 gate: crank covenant UNHEALTHY->cure (local reduce, live pool) | 202898 | 1843931 | 2026-08-04T13:31:12.340094Z |
| P3 gate: cure top-up (local reduce, live pool) | 205372 | 1843931 | 2026-08-04T13:31:12.375146Z |
| P3 gate: acceleration (local reduce, live pool) | 202794 | 1843931 | 2026-08-04T13:31:12.388045Z |
| match(order-spend + loan-token mint) | 145520 | 1843946 | 2026-08-04T14:16:10.089369Z |
| match(order-spend + loan-token mint) | 145520 | 1843949 | 2026-08-04T14:23:55.565820Z |
| P3 crank covenant UNHEALTHY (B chk1, keeper) | 202898 | 1843959 | 2026-08-04T14:43:55.696513Z |
| P3 crank covenant UNHEALTHY (C chk1, keeper) | 202898 | 1843960 | 2026-08-04T14:48:55.776936Z |
| P3 cure top-up (B) | 205372 | 1843962 | 2026-08-04T14:50:10.834904Z |
| P3 crank covenant HEALTHY (B chk2, keeper) | 202889 | 1843965 | 2026-08-04T14:54:38.379609Z |
| P3 accelerate (C, keeper) | 202771 | 1843969 | 2026-08-04T15:01:53.528511Z |
| P3 gate: crank covenant HEALTHY (local reduce, live pool) | 199492 | 1843981 | 2026-08-04T15:18:54.254641Z |
| P3 gate: crank covenant UNHEALTHY->cure (local reduce, live pool) | 199502 | 1843981 | 2026-08-04T15:18:54.268481Z |
| P3 gate: cure top-up (local reduce, live pool) | 201970 | 1843981 | 2026-08-04T15:18:54.297302Z |
| P3 gate: acceleration (local reduce, live pool) | 199396 | 1843981 | 2026-08-04T15:18:54.306600Z |
| P3 gate: repay with NO data input (eager-eval probe) | 204053 | 1843981 | 2026-08-04T15:18:54.329042Z |
| P3 gate: covenantOff crank with NO data input (eager-eval probe) | 199239 | 1843981 | 2026-08-04T15:18:54.339495Z |
| P3 gate: top-up with NO data input (eager-eval probe) | 201712 | 1843981 | 2026-08-04T15:18:54.356744Z |
| match(order-spend + loan-token mint) | 145520 | 1843984 | 2026-08-04T15:25:39.629499Z |
| match(order-spend + loan-token mint) | 145520 | 1843987 | 2026-08-04T15:34:10.125750Z |
| P3 crank covenant UNHEALTHY (B chk1, keeper) | 199502 | 1843997 | 2026-08-04T15:56:55.262255Z |
| P3 crank covenant UNHEALTHY (C chk1, keeper) | 199502 | 1843999 | 2026-08-04T16:03:25.349072Z |
| P3 cure top-up (B) | 201970 | 1844000 | 2026-08-04T16:04:55.410225Z |
| P3 crank covenant HEALTHY (B chk2, keeper) | 199492 | 1844002 | 2026-08-04T16:06:40.467355Z |
| P3 accelerate (C, keeper) | 199375 | 1844007 | 2026-08-04T16:23:10.571490Z |
| P3 repay of cured+cranked covenant bond (B) | 201567 | 1844008 | 2026-08-04T16:25:25.625727Z |
| match(order-spend + loan-token mint) | 145520 | 1844080 | 2026-08-04T18:17:24.430726Z |
| C-wall cleanup repay (bond D) | 201553 | 1844086 | 2026-08-04T18:26:28.255683Z |
| match(order-spend + loan-token mint) | 145059 | 1844093 | 2026-08-04T18:39:04.612485Z |
| repay(borrower-signed) | 201164 | 1844094 | 2026-08-04T18:43:20.139196Z |
| match(order-spend + loan-token mint) | 145059 | 1844096 | 2026-08-04T18:51:35.674624Z |
| liquidate(signatureless) | 201137 | 1844106 | 2026-08-04T19:13:35.938242Z |
| match(order-spend + loan-token mint) | 145059 | 1844110 | 2026-08-04T19:19:51.457546Z |
| A1-twin repay | 201164 | 1844112 | 2026-08-04T19:24:06.643467Z |
| match(order-spend + loan-token mint) | 145059 | 1844115 | 2026-08-04T19:31:22.163345Z |
| A2-twin repay | 201164 | 1844116 | 2026-08-04T19:33:07.266037Z |
| match(order-spend + loan-token mint) | 145059 | 1844120 | 2026-08-04T19:43:37.791623Z |
| A3-cleanup liquidate | 201137 | 1844130 | 2026-08-04T20:15:38.089972Z |
| match(order-spend + loan-token mint) | 145059 | 1844134 | 2026-08-04T20:23:38.596472Z |
| A4-twin liquidate | 201137 | 1844144 | 2026-08-04T20:48:38.829784Z |
| match(order-spend + loan-token mint) | 145493 | 1844150 | 2026-08-04T20:55:54.347059Z |
| A5-twin liquidate(token collateral) | 201575 | 1844160 | 2026-08-04T21:18:54.566016Z |
| match(order-spend + loan-token mint) | 145059 | 1844167 | 2026-08-04T21:32:45.111479Z |
| A7-twin liquidate | 201137 | 1844177 | 2026-08-04T21:57:45.368661Z |
| match(order-spend + loan-token mint) | 147551 | 1844188 | 2026-08-04T22:23:04.214345Z |
| B-wall cleanup repay | 201164 | 1844189 | 2026-08-04T22:24:49.663652Z |
| match(order-spend + loan-token mint) | 147551 | 1844206 | 2026-08-04T22:53:22.049135Z |
| crank(keeper, signatureless) | 198798 | 1844214 | 2026-08-04T23:08:52.613990Z |
| self-crank(borrower) | 198798 | 1844220 | 2026-08-04T23:28:23.443941Z |
| top-up(borrower-signed) | 201261 | 1844222 | 2026-08-04T23:29:23.585933Z |
| T5-cleanup repay(cranked+topped bond) | 201172 | 1844224 | 2026-08-04T23:34:09.127487Z |
| match(order-spend + loan-token mint) | 150477 | 1844229 | 2026-08-04T23:43:55.577443Z |
| B16 cleanup repay(token bond) | 201553 | 1844231 | 2026-08-04T23:47:25.977521Z |
| match(order-spend + loan-token mint) | 145059 | 1844242 | 2026-08-05T00:02:59.883692Z |
| B13 cleanup repay | 201164 | 1844252 | 2026-08-05T00:29:31.012502Z |
| match(order-spend + loan-token mint) | 145059 | 1844309 | 2026-08-05T02:17:40.685588Z |
| B-wall cleanup repay | 201164 | 1844310 | 2026-08-05T02:19:11.000204Z |
| match(order-spend + loan-token mint) | 145059 | 1844326 | 2026-08-05T02:42:57.488318Z |
| crank(keeper, signatureless) | 198798 | 1844334 | 2026-08-05T03:02:57.671416Z |
| self-crank(borrower) | 198798 | 1844340 | 2026-08-05T03:16:27.840037Z |
| top-up(borrower-signed) | 201261 | 1844342 | 2026-08-05T03:22:57.919252Z |
| T5-cleanup repay(cranked+topped bond) | 201172 | 1844344 | 2026-08-05T03:26:12.978575Z |
| match(order-spend + loan-token mint) | 145059 | 1844348 | 2026-08-05T03:30:43.253097Z |
| B13 cleanup repay | 201164 | 1844358 | 2026-08-05T03:51:13.651360Z |
| match(order-spend + loan-token mint) | 145493 | 1844364 | 2026-08-05T04:05:59.007478Z |
| B16 cleanup repay(token bond) | 201553 | 1844366 | 2026-08-05T04:07:59.159268Z |
| P4 gate: crank covenant HEALTHY (local reduce, live pool) | 311931 | 1846710 | 2026-08-08T10:46:36.471125Z |
| P4 gate: crank covenant UNHEALTHY->cure (local reduce, live pool) | 311943 | 1846710 | 2026-08-08T10:46:36.691394Z |
| P4 gate: coupon covenant HEALTHY (local reduce, live pool) | 314786 | 1846710 | 2026-08-08T10:46:36.937420Z |
| P4 gate: coupon covenant UNHEALTHY->cure (local reduce, live pool) | 314798 | 1846710 | 2026-08-08T10:46:37.121741Z |
| P4 gate: cure top-up (local reduce, live pool) | 314496 | 1846710 | 2026-08-08T10:46:37.300395Z |
| P4 gate: acceleration (local reduce, live pool) | 311911 | 1846710 | 2026-08-08T10:46:37.465551Z |
| P4 gate: repay with NO data input (eager-eval probe) | 314072 | 1846710 | 2026-08-08T10:46:38.103591Z |
| P4 gate: covenantOff crank with NO data input (eager-eval probe) | 311214 | 1846710 | 2026-08-08T10:46:38.260626Z |
| P4 gate: top-up with NO data input (eager-eval probe) | 314223 | 1846710 | 2026-08-08T10:46:38.416598Z |
| P4 gate: covenantOff coupon with NO data input (eager-eval probe) | 314072 | 1846710 | 2026-08-08T10:46:38.572721Z |
| P4 gate: nonzero-installment repay at sched(2)==1 (no data input) | 313670 | 1846710 | 2026-08-08T10:46:38.729994Z |
| P4 gate: order cancel with NO data input (eager-eval probe) | 241666 | 1846710 | 2026-08-08T10:46:39.008789Z |
| P4 gate: card-less match with NO data input (eager-eval probe) | 242261 | 1846710 | 2026-08-08T10:46:39.200420Z |
| P4 gate: carded match (1 data input, fabricated card) | 242361 | 1846710 | 2026-08-08T10:46:39.360328Z |
| P4 gate: borrowerAuth eager-safety (signatureless crank, zero borrower inputs) | 311931 | 1846710 | 2026-08-08T10:46:39.500279Z |
| P4 gate: missed-payment acceleration (local reduce, no data input) | 311187 | 1846710 | 2026-08-08T10:46:39.642472Z |
| P4 gate: hooked liquidation (ctx-ext var preimage, local reduce) | 311544 | 1846710 | 2026-08-08T10:46:39.796742Z |
| P4 gate: plain liquidation past maturity (local reduce) | 311525 | 1846710 | 2026-08-08T10:46:39.935306Z |
| P4 gate: card refuel value-grow (local reduce) | 57494 | 1846710 | 2026-08-08T10:46:39.954383Z |
| P4 gate: attestation generic branch (fabricated nonzero-type bond, local) | 311884 | 1846710 | 2026-08-08T10:46:40.112957Z |
| P4 gate: crank covenant HEALTHY (local reduce, live pool) | 311931 | 1846762 | 2026-08-08T12:49:53.747755Z |
| P4 gate: crank covenant UNHEALTHY->cure (local reduce, live pool) | 311943 | 1846762 | 2026-08-08T12:49:53.985385Z |
| P4 gate: coupon covenant HEALTHY (local reduce, live pool) | 314786 | 1846762 | 2026-08-08T12:49:54.200061Z |
| P4 gate: coupon covenant UNHEALTHY->cure (local reduce, live pool) | 314798 | 1846762 | 2026-08-08T12:49:54.380066Z |
| P4 gate: cure top-up (local reduce, live pool) | 314496 | 1846762 | 2026-08-08T12:49:54.551039Z |
| P4 gate: acceleration (local reduce, live pool) | 311911 | 1846762 | 2026-08-08T12:49:54.714165Z |
| P4 gate: repay with NO data input (eager-eval probe) | 314072 | 1846762 | 2026-08-08T12:49:55.356432Z |
| P4 gate: covenantOff crank with NO data input (eager-eval probe) | 311214 | 1846762 | 2026-08-08T12:49:55.505970Z |
| P4 gate: top-up with NO data input (eager-eval probe) | 314223 | 1846762 | 2026-08-08T12:49:55.664Z |
| P4 gate: covenantOff coupon with NO data input (eager-eval probe) | 314072 | 1846762 | 2026-08-08T12:49:55.821731Z |
| P4 gate: nonzero-installment repay at sched(2)==1 (no data input) | 313670 | 1846762 | 2026-08-08T12:49:55.975089Z |
| P4 gate: order cancel with NO data input (eager-eval probe) | 251554 | 1846762 | 2026-08-08T12:49:56.244339Z |
| P4 gate: card-less match with NO data input (eager-eval probe) | 252443 | 1846762 | 2026-08-08T12:49:56.420595Z |
| P4 gate: carded match (1 data input, fabricated card) | 252770 | 1846762 | 2026-08-08T12:49:56.587348Z |
| P4 gate: borrowerAuth eager-safety (signatureless crank, zero borrower inputs) | 311931 | 1846762 | 2026-08-08T12:49:56.729504Z |
| P4 gate: missed-payment acceleration (local reduce, no data input) | 311187 | 1846762 | 2026-08-08T12:49:56.872553Z |
| P4 gate: hooked liquidation (ctx-ext var preimage, local reduce) | 311544 | 1846762 | 2026-08-08T12:49:57.027617Z |
| P4 gate: plain liquidation past maturity (local reduce) | 311525 | 1846762 | 2026-08-08T12:49:57.165496Z |
| P4 gate: card refuel value-grow (local reduce) | 57494 | 1846762 | 2026-08-08T12:49:57.184077Z |
| P4 gate: attestation generic branch (fabricated nonzero-type bond, local) | 311884 | 1846762 | 2026-08-08T12:49:57.359839Z |
| P4 gate: crank covenant HEALTHY (local reduce, live pool) | 311931 | 1846766 | 2026-08-08T12:52:20.795793Z |
| P4 gate: crank covenant UNHEALTHY->cure (local reduce, live pool) | 311943 | 1846766 | 2026-08-08T12:52:21.004414Z |
| P4 gate: coupon covenant HEALTHY (local reduce, live pool) | 314786 | 1846766 | 2026-08-08T12:52:21.203476Z |
| P4 gate: coupon covenant UNHEALTHY->cure (local reduce, live pool) | 314798 | 1846766 | 2026-08-08T12:52:21.399327Z |
| P4 gate: cure top-up (local reduce, live pool) | 314496 | 1846766 | 2026-08-08T12:52:21.574130Z |
| P4 gate: acceleration (local reduce, live pool) | 311911 | 1846766 | 2026-08-08T12:52:21.731821Z |
| P4 gate: repay with NO data input (eager-eval probe) | 314072 | 1846766 | 2026-08-08T12:52:22.362659Z |
| P4 gate: covenantOff crank with NO data input (eager-eval probe) | 311214 | 1846766 | 2026-08-08T12:52:22.507918Z |
| P4 gate: top-up with NO data input (eager-eval probe) | 314223 | 1846766 | 2026-08-08T12:52:22.672092Z |
| P4 gate: covenantOff coupon with NO data input (eager-eval probe) | 314072 | 1846766 | 2026-08-08T12:52:22.831528Z |
| P4 gate: nonzero-installment repay at sched(2)==1 (no data input) | 313670 | 1846766 | 2026-08-08T12:52:22.985320Z |
| P4 gate: order cancel with NO data input (eager-eval probe) | 251554 | 1846766 | 2026-08-08T12:52:23.255170Z |
| P4 gate: card-less match with NO data input (eager-eval probe) | 252443 | 1846766 | 2026-08-08T12:52:23.414610Z |
| P4 gate: cancel of tmpl(1)==0 order (division-hoist probe, EKB F1) | 251554 | 1846766 | 2026-08-08T12:52:23.428303Z |
| P4 gate: cancel of short-R9 order (index-hoist probe, EKB F1) | 251554 | 1846766 | 2026-08-08T12:52:23.440723Z |
| P4 gate: carded match (1 data input, fabricated card) | 252770 | 1846766 | 2026-08-08T12:52:23.599397Z |
| P4 gate: borrowerAuth eager-safety (signatureless crank, zero borrower inputs) | 311931 | 1846766 | 2026-08-08T12:52:23.733811Z |
| P4 gate: missed-payment acceleration (local reduce, no data input) | 311187 | 1846766 | 2026-08-08T12:52:23.870663Z |
| P4 gate: hooked liquidation (ctx-ext var preimage, local reduce) | 311544 | 1846766 | 2026-08-08T12:52:24.030076Z |
| P4 gate: plain liquidation past maturity (local reduce) | 311525 | 1846766 | 2026-08-08T12:52:24.168216Z |
| P4 gate: card refuel value-grow (local reduce) | 57494 | 1846766 | 2026-08-08T12:52:24.185508Z |
| P4 gate: attestation generic branch (fabricated nonzero-type bond, local) | 311884 | 1846766 | 2026-08-08T12:52:24.350983Z |
| H1 match-order-v3(carded, 1 data input) | 253265 | 1846852 | 2026-08-08T15:19:04.526369Z |
| H2 match-order-v3(card-less) | 252457 | 1846855 | 2026-08-08T15:25:50.116508Z |
| H1 coupon 1 (unhealthy->cure) | 314812 | 1846862 | 2026-08-08T15:40:35.227584Z |
| H1 cure (health restored, grid resumed) | 317007 | 1846864 | 2026-08-08T15:41:35.287735Z |
| H2 coupon 1 | 321548 | 1846867 | 2026-08-08T15:44:35.352682Z |
| H1 coupon (payments 3, third-party keeper, D15) | 314800 | 1846872 | 2026-08-08T15:50:15.020041Z |
| H1 coupon (payments 2, third-party keeper, D15) | 314800 | 1846878 | 2026-08-08T15:59:00.164591Z |
| H1 final repay (installment bond, sched(2)==1) | 314070 | 1846880 | 2026-08-08T16:04:15.264624Z |
| H2 missed-accel (grace expiry) | 311187 | 1846883 | 2026-08-08T16:15:00.363380Z |
| D4 match-order-v3 | 252457 | 1846887 | 2026-08-08T16:25:37.186304Z |
| match(order-spend + loan-token mint) | 252443 | 1846909 | 2026-08-08T17:06:46.566767Z |
| repay(borrower-signed) | 313674 | 1846911 | 2026-08-08T17:12:47.184808Z |
| match(order-spend + loan-token mint) | 252443 | 1846915 | 2026-08-08T17:24:32.730374Z |
| liquidate(signatureless) | 313579 | 1846925 | 2026-08-08T17:44:02.849370Z |
| match(order-spend + loan-token mint) | 252443 | 1846929 | 2026-08-08T17:49:33.323059Z |
| A1-twin repay | 313674 | 1846930 | 2026-08-08T17:51:03.388021Z |
| match(order-spend + loan-token mint) | 252443 | 1846933 | 2026-08-08T17:59:03.876385Z |
| A2-twin repay | 313674 | 1846935 | 2026-08-08T18:03:48.960517Z |
| match(order-spend + loan-token mint) | 252443 | 1846939 | 2026-08-08T18:12:49.416100Z |
| A3-cleanup liquidate | 313579 | 1846949 | 2026-08-08T18:36:04.568743Z |
| match(order-spend + loan-token mint) | 252443 | 1846952 | 2026-08-08T18:43:50.021934Z |
| A4-twin liquidate | 313579 | 1846962 | 2026-08-08T19:05:05.160119Z |
| match(order-spend + loan-token mint) | 252876 | 1846967 | 2026-08-08T19:14:20.642546Z |
| A5-twin liquidate(token collateral) | 314017 | 1846977 | 2026-08-08T19:39:35.778227Z |
| match(order-spend + loan-token mint) | 252443 | 1846983 | 2026-08-08T19:53:06.648881Z |
| A7-twin liquidate | 313579 | 1846993 | 2026-08-08T20:24:21.814020Z |
| match(order-spend + loan-token mint) | 252443 | 1847004 | 2026-08-08T20:42:39.733946Z |
| B-wall cleanup repay | 313674 | 1847007 | 2026-08-08T20:48:55.188287Z |
| match(order-spend + loan-token mint) | 252876 | 1847018 | 2026-08-08T21:04:57.146035Z |
| B16 cleanup repay(token bond) | 314063 | 1847020 | 2026-08-08T21:15:57.418167Z |
| match(order-spend + loan-token mint) | 252443 | 1847031 | 2026-08-08T21:39:57.873189Z |
| B-wall cleanup repay | 313674 | 1847033 | 2026-08-08T21:41:58.315360Z |
| match(order-spend + loan-token mint) | 252443 | 1847046 | 2026-08-08T22:16:15.879111Z |
| crank(keeper, signatureless) | 311214 | 1847054 | 2026-08-08T22:43:46.163145Z |
| self-crank(borrower) | 311214 | 1847060 | 2026-08-08T22:50:16.377128Z |
| top-up(borrower-signed) | 313769 | 1847062 | 2026-08-08T22:55:16.439337Z |
| T5-cleanup repay(cranked+topped bond) | 313682 | 1847064 | 2026-08-08T22:58:46.504748Z |
| match(order-spend + loan-token mint) | 252443 | 1847068 | 2026-08-08T23:08:46.999709Z |
| B13 cleanup repay | 313682 | 1847080 | 2026-08-08T23:39:02.520275Z |
| match(order-spend + loan-token mint) | 252876 | 1847086 | 2026-08-08T23:49:03.042285Z |
| B16 cleanup repay(token bond) | 314063 | 1847087 | 2026-08-08T23:55:48.294952Z |
| match(order-spend + loan-token mint) | 252924 | 1847120 | 2026-08-09T00:54:39.401844Z |
| C-wall cleanup repay (bond D) | 314063 | 1847121 | 2026-08-09T01:03:41.343264Z |
