{
  // =====================================================================
  // Terms Box — the card (rev 3)
  //
  // One box per card, minted with its own NFT, holding the fully-resolved
  // parameter set for a loan product. The catalog entry is read ONCE, as
  // dataInputs(0) of a match whose order pins this card's NFT; no bond
  // path ever reads it again. Composition happens in the builder tool
  // before mint (C4+C2+C3 verdict, TERMSBOX-VECTORS.md §A.2); the chain
  // stores what it is given — coherence is an authoring-time check
  // (§C.3 cascade duty lives in open tooling, CONTRACT-DELTAS §0a/§1).
  //
  // Registers (REV3-LAYOUT.md L2-L4; sentinel 0/empty = compiled default):
  //   R4: Coll[Byte]        EIP-4 name
  //   R5: Coll[Byte]        EIP-4 description
  //   R6: Coll[Byte]        EIP-4 decimals ("0") — the card is the one box
  //                         in the system that CAN be EIP-4 compliant
  //                         (mandatory triple only; R7 is the pack, so the
  //                         optional asset-type slot is unavailable)
  //   R7: Coll[Long]  size 11 numeric pack:
  //         [crankBounty, graceBlocks, liqCarveout, haircutKeep,   0-3 ->
  //          thresholdMin, thresholdMax,                           bond R9
  //          minOrderValue, minPeriod, minCoupon,                  6-9
  //          attestationType, flagWord]
  //         flag bits (all 0 in rev 3, reserved): b0 covenant on/off,
  //         b1 third-party pay, b2 self-crank, b3 record-vs-seize,
  //         b4 prepayment, b5 attestation-absence-fails-healthy
  //   R8: Coll[Coll[Byte]]  [poolNFT, collateralTokenId,
  //                          attesterScriptHash?, feeRecipientHash?]
  //                         sizes 2-4, suffix opt-in, empty = default
  //   R9: Coll[Coll[Byte]]  [publisherPropBytes, versionUtf8,
  //                          predecessorCardNFT] — informational only,
  //                         never read by any contract gate (§0a: trust
  //                         is market-side)
  //   tokens(0): the card NFT, amount 1 — orders pin it, matches verify it
  //
  // Guard: REFUEL-ONLY. Spendable only to a byte-identical successor —
  // same script, same registers, same tokens exactly — whose value has
  // not shrunk. This is the most load-bearing rule in the design: a
  // mutable card's owner could raise a tier's threshold and seize every
  // loan in it in one transaction (TERMSBOX-HANDOFF §2 constraint 4).
  // Signatureless by design (anyone may pay a card's storage rent; no
  // admin key anywhere, CONTRACT-DELTAS §0a). Deprecation = publish a
  // successor card, never an admin action.
  //
  // Type rule: single boolean chain, one sigmaProp at top level. All
  // successor register reads are isDefined-guarded so a malformed spend
  // attempt is a clean reduce-to-false, never a crash.
  // =====================================================================

  val succ = OUTPUTS(0)

  sigmaProp(
    succ.propositionBytes == SELF.propositionBytes &&
    succ.value >= SELF.value &&
    succ.tokens == SELF.tokens &&
    succ.R4[Coll[Byte]].isDefined &&
    succ.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get &&
    succ.R5[Coll[Byte]].isDefined &&
    succ.R5[Coll[Byte]].get == SELF.R5[Coll[Byte]].get &&
    succ.R6[Coll[Byte]].isDefined &&
    succ.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get &&
    succ.R7[Coll[Long]].isDefined &&
    succ.R7[Coll[Long]].get == SELF.R7[Coll[Long]].get &&
    succ.R8[Coll[Coll[Byte]]].isDefined &&
    succ.R8[Coll[Coll[Byte]]].get == SELF.R8[Coll[Coll[Byte]]].get &&
    succ.R9[Coll[Coll[Byte]]].isDefined &&
    succ.R9[Coll[Coll[Byte]]].get == SELF.R9[Coll[Coll[Byte]]].get
  )
}
