{
  // =====================================================================
  // Minimal script lender — Phase 1 stand-in for the Phase 5 vault.
  //
  // Owner-only spend, deliberately dumb. The point is that this address is
  // a SCRIPT (a non-P2PK ErgoTree), so the bond's R8 exit validation is
  // exercised against contract ownership, not a bare key.
  //
  // MIN_OUTS is an inert knob (every real transaction has at least one
  // output): compiling with MIN_OUTS = 2 instead of 1 yields an ErgoTree
  // that differs by exactly one constant byte, which adversarial test A1
  // uses as the "one byte off" repayment target.
  // =====================================================================

  sigmaProp(OUTPUTS.size >= MIN_OUTS) && OWNER_PK
}
