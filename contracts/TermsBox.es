{
  // =====================================================================
  // Terms Box — the card
  //
  // One box per card, minted with its own NFT, holding the resolved
  // parameter set for a loan product. The card is read ONCE, as
  // dataInputs(0) of a match whose order pins this card's NFT; no bond
  // path ever reads it again. The chain stores what it is given — field
  // coherence is an authoring-time concern with no on-chain check.
  //
  // Registers. Sentinel 0 / empty means "use the order contract's
  // compiled constant"; note the order additionally CAPS liqCarveout
  // and haircutKeep at its compiled values — a card may tighten those,
  // never loosen them.
  //   R4: Coll[Byte]        EIP-4 name
  //   R5: Coll[Byte]        EIP-4 description
  //   R6: Coll[Byte]        EIP-4 decimals ("0") — the card is the one
  //                         box in the system that can be EIP-4
  //                         compliant (mandatory triple only; R7 holds
  //                         the pack, so the optional asset-type slot
  //                         is unavailable)
  //   R7: Coll[Long]  size 11 numeric pack:
  //         [crankBounty, graceBlocks, liqCarveout, haircutKeep,   0-3 ->
  //          thresholdMin, thresholdMax,                           bond R9
  //          minOrderValue, minPeriod, minCoupon,                  6-9
  //          attestationType, flagWord]
  //         attestationType and flagWord must both be 0 or the card is
  //         unmatchable (the order gates them). Reserved flag bits:
  //         b0 covenant on/off, b1 third-party pay, b2 self-crank,
  //         b3 record-vs-seize, b4 prepayment,
  //         b5 attestation-absence-fails-healthy
  //   R8: Coll[Coll[Byte]]  [poolNFT, collateralTokenId,
  //                          attesterScriptHash?, feeRecipientHash?,
  //                          blessedHookHash...]
  //                         indices 0-1: 32 bytes or empty = default;
  //                         2-3: optional suffix, unread today;
  //                         4 and up: blake2b256 hashes of liquidation
  //                         hook scripts this card approves — an order
  //                         may pin a hook only if its hash is here
  //   R9: Coll[Coll[Byte]]  [publisherPropBytes, versionUtf8,
  //                          predecessorCardNFT] — informational; no
  //                         contract derives behavior from it, though
  //                         the refuel guard freezes it like every
  //                         other register
  //   tokens(0): the card NFT, amount 1 — orders pin it, matches
  //              verify it together with this script's hash
  //
  // Guard: REFUEL-ONLY. Spendable only to a successor with identical
  // script, registers and tokens whose value has not shrunk. Rationale:
  // a mutable card's owner could raise a tier's threshold and seize
  // every loan in that tier in one transaction, or reprice
  // posted-but-unmatched orders. Signatureless by design: anyone may
  // pay a card's storage rent, and there is no admin key anywhere.
  // Deprecation means publishing a successor card, never editing.
  //
  // Type rule: single boolean chain, one sigmaProp at top level.
  // Successor (foreign-box) register reads are isDefined-guarded so a
  // malformed spend attempt reduces cleanly to false; SELF reads are
  // deliberately unguarded — a card minted with malformed registers is
  // unspendable, and only its own creator can mint one.
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
