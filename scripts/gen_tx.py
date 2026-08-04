import re, sys

# Ledger generator.
#   python3 scripts/gen_tx.py                          -> phase1.log -> TRANSACTIONS.md   (Phase 1, original behavior)
#   python3 scripts/gen_tx.py phase2.log ph2TRANSACTIONS.md -> Phase 2 ledger (separate file; never rewrites the Phase 1 ledger)
LOG = sys.argv[1] if len(sys.argv) > 2 else "phase1.log"
OUT = sys.argv[2] if len(sys.argv) > 2 else "TRANSACTIONS.md"
PHASE1 = OUT == "TRANSACTIONS.md"

lines = [l.rstrip("\n").replace("[info] ", "", 1) for l in open(LOG)]

# Recovery/rebalance labels are omitted from ledgers for readability.
SKIP = ("sweep-vault", "consolidate-borrower", "lender-topup-borrower", "transfer-")

# label -> (purpose, from->to, amount)
def meta(label, section):
    l = label
    if l == "fund-distribution": return ("distribute dust so each role pays its own way","BORROWER → LENDER 0.20, KEEPER 0.05","0.25")
    if l == "post-order":        return ("borrower locks collateral (+ escrow) with requested terms","BORROWER → ORDER","coll + escrow")
    if l == "post-order-raw":    return ("borrower posts a deliberately non-conforming order (B0)","BORROWER → ORDER","coll + claimed escrow")
    if l == "match-order":       return ("spend order, mint loan token, create bond, pay principal","LENDER funds; ORDER → BOND; principal → BORROWER","0.010 principal")
    if l.startswith("crank"):    return ("signatureless checkpoint crank: successor + bounty, bond sole input","KEEPER cranks BOND → BOND' + bounty","0.005 bounty")
    if l.startswith("self-crank"): return ("borrower self-crank (pinned decision): same crank path","BORROWER cranks BOND → BOND' + bounty","0.005 bounty")
    if l.startswith("top-up"):   return ("borrower-signed collateral top-up, schedule frozen verbatim","BORROWER adds to BOND → BOND'","+0.005 coll")
    if "cleanup repay" in l:     return ("cleanup repay: recovers collateral + residual escrow","BORROWER spends BOND → VAULT","0.011 receipt")
    if l.startswith("repay") or "twin repay" in l: return ("borrower-signed repay: repayment+receipt to lender script","BORROWER spends BOND → VAULT","0.011 receipt")
    if l.startswith("liquidate") or "cleanup liquidate" in l or "twin liquidate" in l: return ("signatureless liquidation past maturity","KEEPER spends BOND → VAULT","0.017")
    if l == "mint-test-token":   return ("mint token collateral for the token-wall tests","BORROWER self-mint","tok")
    if l == "forge-bond":        return ("A6: forge a bond directly at the bond address (no order)","KEEPER → BOND (forged)","0.002")
    if l == "forged-bond-selfrepay": return ("A6 cleanup: forger self-repays, recovers dust","KEEPER spends forged BOND → KEEPER","0.001")
    if "plain cancel" in l:      return ("A8 pass-twin: plain cancel recovers collateral","BORROWER spends ORDER → BORROWER","0.020")
    if "cleanup cancel" in l or l.endswith("cancel"): return ("cleanup cancel: order recovered (collateral + escrow)","BORROWER spends ORDER → BORROWER","coll + escrow")
    return ("(tx)", "—", "—")

rows=[]  # (section, txid, purpose, fromto, amount, note)
section="setup"
if PHASE1:
    # seed with the two setup txs (known)
    rows.append(("setup","c477d622e5a4bcc97d154b3aedcb4ded4e8935bfe7bd10c4944d31ba73c3de23","user funds the test wallets","user wallet → BORROWER","0.5",""))
    rows.append(("setup","0eab60fbf76289cab238205c7e3e421788db9d6cbfc45a0a0a69c0755678842e","distribute dust so each role pays its own way","BORROWER → LENDER 0.20, KEEPER 0.05","0.25",""))

for l in lines:
    l=l.strip()
    m=re.match(r"=== ([\w,\-']+)[:\s]", l)
    if m: section=m.group(1); continue
    m=re.match(r"submitted ([^:]+): ([0-9a-f]{64})", l)
    if m:
        label,txid=m.group(1),m.group(2)
        if any(label.startswith(s) for s in SKIP): continue
        p,ft,amt=meta(label,section)
        rows.append((section,txid,p,ft,amt,label))
        continue

# emit
title = "# Phase 1 mainnet transaction log" if PHASE1 else "# Phase 2 mainnet transaction log"
blurb = ("This is the **run-3** ledger (the green run). Earlier runs' txIds are superseded. "
         if PHASE1 else
         "This is the Phase 2 green-run ledger (successor machinery: crank, self-crank, top-up, race). "
        ) + "Recovery/rebalance txs (Recycle, Transfer) are omitted here for readability."
out=[title,
"",
f"Auto-generated from the suite run log (`{LOG}`). Roles instead of addresses: **BORROWER** `9hgvr…Ah8s`, **LENDER** `9h5TP…btUw`, **KEEPER** `9gmqK…Dteo`, **ORDER** = conforming order contract, **BOND** = conforming bond contract" + ("" if PHASE1 else " (**BOND'** = successor box)") + ", **VAULT** = minimal lender-vault script (owner = LENDER). Amounts in ERG. Standard fee 0.0011.",
"",
blurb,
""]
cur=None
i=1
for (section,txid,p,ft,amt,label) in rows:
    if section!=cur:
        cur=section
        out.append("")
        out.append(f"## {section}")
        out.append("")
        out.append("| # | txId | what it does | from → to | amount |")
        out.append("|---|------|--------------|-----------|--------|")
    out.append(f"| {i} | `{txid}` | {p} | {ft} | {amt} |")
    i+=1
out.append("")
out.append("_JitCost per path in JITCOST.md. Negative-test attacks that never reach the chain (rejected at proving) have no txId and are listed in the suite log, not here._")
open(OUT,"w").write("\n".join(out)+"\n")
print(f"wrote {i-1} transactions to {OUT} from {LOG}")
