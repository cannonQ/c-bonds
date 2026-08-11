#!/usr/bin/env python3
"""Build a rev-4 transaction ledger from harness run logs.

Every submission the harness makes prints `submitted <label>: <txid>`;
confirmations print `confirmed: <label> <txid>`. This collects them in
run order, one row per transaction, and marks whether the ledger saw the
confirmation line for it.

Usage: ledger_from_logs.py out.md log [log ...]
"""
import re
import sys

SUB = re.compile(r"submitted (.+?): ([0-9a-f]{64})")
CONF = re.compile(r"confirmed: (.+?) ([0-9a-f]{64})")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    out, logs = sys.argv[1], sys.argv[2:]
    rows, confirmed, seen = [], set(), set()
    for path in logs:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = SUB.search(line)
                if m and m.group(2) not in seen:
                    seen.add(m.group(2))
                    rows.append((path, m.group(1).strip(), m.group(2)))
                m = CONF.search(line)
                if m:
                    confirmed.add(m.group(2))
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("# rev-4 transaction ledger (mainnet dust)\n\n")
        fh.write("Generated from the run logs by `scripts/ledger_from_logs.py`.\n")
        fh.write("Every id below is verifiable on any Ergo explorer.\n\n")
        fh.write("| # | run | step | tx id | confirmed |\n|---|---|---|---|---|\n")
        for i, (src, label, tx) in enumerate(rows, 1):
            mark = "yes" if tx in confirmed else "—"
            fh.write(f"| {i} | `{src}` | {label} | `{tx}` | {mark} |\n")
        fh.write(f"\n{len(rows)} transactions, {len(confirmed & seen)} confirmed in-log.\n")
    print(f"{out}: {len(rows)} transactions from {len(logs)} log(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
