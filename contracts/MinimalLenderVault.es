{
  // =====================================================================
  // Minimal script lender — a test fixture, not the production vault.
  //
  // Owner-only spend, deliberately minimal. The point is that this
  // lender is a SCRIPT (a non-P2PK ErgoTree), so the bond's hash-based
  // lender checks — blake2b256(output.propositionBytes) == R8(0) — are
  // exercised against contract ownership rather than a bare key. The
  // compiled bytes of THIS script are what a funder supplies as ctx-ext
  // var 0 on the order input at match; their blake2b256 is what lands
  // in bond R8(0) and what every lender payment is verified against.
  //
  // MIN_OUTS exists solely to mint a near-identical sibling tree:
  // compiling with MIN_OUTS = 2 instead of 1 yields an ErgoTree
  // differing by one constant byte — and therefore a completely
  // different blake2b256 — used in negative tests of the lender
  // identity checks. At MIN_OUTS = 1 the condition is vacuous.
  // =====================================================================

  sigmaProp(OUTPUTS.size >= MIN_OUTS) && OWNER_PK
}
