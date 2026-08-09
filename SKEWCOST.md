# C3 — LP-manipulation cost table (live reserves, analytical)

Node height 1843948, 2026-08-04T14:23:19.419261Z. Cost to shift a pool's covenant valuation UP by the target
(borrower-side attack: buy before the checkpoint, sell back after —
the no-flash-loan round trip). Round-trip loss = two-sided slippage
+ 2x pool fee on the attacker's own size; arbitrage during the hold
only adds. Lender-side (price-down) attacks are symmetric in token
units. The covenant's own defenses stack on top: the HAIRCUT_KEEP
2% haircut absorbs the first 2% of any skew, and thresholds are
10-30% above water.

| pool | depth (ERG) | fee | target shift | ERG in | round-trip cost (ERG) | cost % of position |
|---|---|---|---|---|---|---|
| ERG/SigUSD | 331,610 | 0.5% | 1.0% | 1,653.9 | 16.46 | 1.00% |
| ERG/SigUSD | 331,610 | 0.5% | 2.0% | 3,299.7 | 32.61 | 0.99% |
| ERG/SigUSD | 331,610 | 0.5% | 5.0% | 8,189.1 | 79.77 | 0.97% |
| ERG/SigUSD | 331,610 | 0.5% | 10.0% | 16,185.5 | 154.03 | 0.95% |
| ERG/RSN | 208,611 | 1.0% | 1.0% | 1,040.5 | 20.60 | 1.98% |
| ERG/RSN | 208,611 | 1.0% | 2.0% | 2,075.8 | 40.91 | 1.97% |
| ERG/RSN | 208,611 | 1.0% | 5.0% | 5,151.7 | 100.10 | 1.94% |
| ERG/RSN | 208,611 | 1.0% | 9.9% | 10,182.1 | 193.37 | 1.90% |
| ERG/rsBTC | 45,472 | 1.0% | 1.0% | 226.8 | 4.49 | 1.98% |
| ERG/rsBTC | 45,472 | 1.0% | 2.0% | 452.5 | 8.92 | 1.97% |
| ERG/rsBTC | 45,472 | 1.0% | 5.0% | 1,122.9 | 21.82 | 1.94% |
| ERG/rsBTC | 45,472 | 1.0% | 9.9% | 2,219.4 | 42.15 | 1.90% |
| ERG/Paideia | 27,203 | 0.3% | 1.0% | 135.7 | 0.81 | 0.60% |
| ERG/Paideia | 27,203 | 0.3% | 2.0% | 270.7 | 1.61 | 0.59% |
| ERG/Paideia | 27,203 | 0.3% | 5.0% | 671.8 | 3.93 | 0.58% |
| ERG/Paideia | 27,203 | 0.3% | 10.0% | 1,327.8 | 7.59 | 0.57% |
| ERG/SigRSV | 18,601 | 0.5% | 1.0% | 92.8 | 0.92 | 0.99% |
| ERG/SigRSV | 18,601 | 0.5% | 2.0% | 185.1 | 1.83 | 0.99% |
| ERG/SigRSV | 18,601 | 0.5% | 5.0% | 459.4 | 4.47 | 0.97% |
| ERG/SigRSV | 18,601 | 0.5% | 10.0% | 907.9 | 8.64 | 0.95% |
