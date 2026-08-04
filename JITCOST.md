# JitCost per path — Phase 1

Measured on mainnet with `prover.reduce(tx, 0).getCost` (exact, same units
as the node's 500,000 per-input budget). Cross-checked against the node's
DEBUG script-cost log. Raw per-run measurements in JITCOST.raw.md.

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
