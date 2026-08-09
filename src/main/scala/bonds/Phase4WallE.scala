package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 4 E-wall: the rev-3 STRUCTURAL adversarial wall, E1-E9 exactly as
  * specified in REV3-KICKOFF.md §5, against the signed-off layout in
  * REV3-LAYOUT.md and the register spec in CONTRACT-DELTAS.md §1/§2.2/§5.1.
  *
  * SPEC-FIRST: written before the rev-3 contracts exist (build-order rule 1).
  * This suite IS the failing spec — it must go green only when TermsBox.es
  * and the rev-3 ConformingOrder/ConformingBond land.
  *
  * Discipline (Phase 2/3 carried forward):
  *  - Match negatives: expectScriptFalse on lender.sign (the rev-3 order top
  *    level is a single sigmaProp of booleans — L10 — so a failed match is a
  *    clean reduce-to-false, no sigma residual). Match twins: local reduce
  *    only. NO match is ever submitted; every posted order is cancelled via
  *    P4.cancelOrderV3 afterwards, which doubles as the proof that malformed
  *    or unmatched orders stay cancellable (and returns RSN on its own
  *    min-value box — anti-welding).
  *  - Bond-side signatureless negatives: expectRejected on keeper.sign (the
  *    borrower arm is boolean co-spend now — no signature involved anywhere).
  *  - Card negatives (E1): expectScriptFalse on borrower.sign — the borrower
  *    only signs the P2PK funding inputs; the CARD input's refuel guard is
  *    what must reduce false. The real cards are never spent (local only).
  *  - Every fabricated box is local (convertToInputWith, never submitted).
  *  - Height-sensitive match reduces pin a pre-header at live nodeHeight so
  *    MATURITY_TOL can never be blown by confirmation-wait staleness.
  *
  * Resume mode: run(cardA/B/C = Some((cardBoxId, cardNftId))) reuses
  * already-minted cards (RunPhase4 passes them); None mints fresh.
  */
object Phase4WallE {
  import Contracts._

  val REPAY: Long   = 15000000L
  val PERIOD: Long  = 20L
  val RSN_AMT: Long = 700L
  val JUNK32: Array[Byte] = Array.fill[Byte](32)(0x77.toByte)

  // ---------------- rev-3 successor packs (suffix-preserving, clone-based:
  // work identically on 6-, 10- and 11-element R9, per L1's slice rule) ----

  def advV3(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = s(3) + s(1); r(5) = s(5) - P4.bountyOf(s); r
  }
  def cureV3(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = -(s(3) + P4.graceOf(s)); r(5) = s(5) - P4.bountyOf(s); r
  }
  def restoreV3(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = (-s(3)) - P4.graceOf(s) + s(1); r
  }

  // ---------------- local tx shapes (rev-3 register layout) ----------------

  /** Signatureless crank, Phase3Gate crankTx pattern on the rev-3 layout:
    * bond sole input, successor + keeper box, optional dataInputs(0)
    * (the pool for type-0 covenant bonds, the attester box for E7). */
  def crankV3(ctx: BlockchainContext, bond: InputBox, dataIn: Option[InputBox],
              r9succ: Array[Long], preH: Int, payTo: Address): UnsignedTransaction = {
    val rs     = bond.getRegisters
    val bounty = P4.bountyOf(TestLib.schedOf(bond))
    val tb     = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(preH).build())
    val succ = tb.outBoxBuilder()
      .value(bond.getValue - bounty)
      .contract(new ErgoTreeContract(bond.getErgoTree, NetworkType.MAINNET))
      .tokens(bond.getTokens.asScala.toSeq: _*)
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
      .build()
    val kb = tb.outBoxBuilder()
      .value(bounty - Kit.TX_FEE)
      .contract(payTo.toErgoContract)
      .build()
    var bl = tb.boxesToSpend(java.util.Arrays.asList(bond))
    dataIn.foreach(d => bl = bl.withDataInputs(java.util.Arrays.asList(d)))
    bl.outputs(succ, kb).fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  /** Repay of a rev-3 bond: exit to the R8(0) lender script with the R4
    * receipt and the loan token only. `extra` lets E5 co-spend arbitrary
    * boxes (the fab contract-borrower box); funding is selected from
    * `funderAddr` — the funder IS the co-spend under the rev-3 borrowerAuth,
    * which is exactly the mechanism E5 pins. */
  def repayV3(ctx: BlockchainContext, bond: InputBox, extra: Seq[InputBox],
              funderAddr: Address): UnsignedTransaction = {
    val funds = Kit.selectBoxes(ctx, funderAddr, Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb    = ctx.newTxBuilder()
    val exit = tb.outBoxBuilder()
      .value(P3.repaymentOf(bond))
      .contract(P4.contractFromBytes(P4.lenderTreeBytesOf(bond)))
      .tokens(new ErgoToken(bond.getTokens.get(0).getId, 1L))
      .registers(ErgoValue.of(bond.getId.getBytes))
      .build()
    tb.boxesToSpend((Seq(bond) ++ extra ++ funds).asJava)
      .outputs(exit).fee(Kit.TX_FEE).sendChangeTo(funderAddr).build()
  }

  /** Plain top-up: successor with every register verbatim, value grown. */
  def topUpV3(ctx: BlockchainContext, bond: InputBox, funderAddr: Address,
              addValue: Long): UnsignedTransaction = {
    val rs    = bond.getRegisters
    val funds = Kit.selectBoxes(ctx, funderAddr, addValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb    = ctx.newTxBuilder()
    val succ = tb.outBoxBuilder()
      .value(bond.getValue + addValue)
      .contract(new ErgoTreeContract(bond.getErgoTree, NetworkType.MAINNET))
      .tokens(bond.getTokens.asScala.toSeq: _*)
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), rs.get(5))
      .build()
    tb.boxesToSpend((Seq(bond) ++ funds).asJava)
      .outputs(succ).fee(Kit.TX_FEE).sendChangeTo(funderAddr).build()
  }

  /** Zero-delta cure of an in-cure bond: successor back on the grid, escrow
    * verbatim, pool as dataInputs(0). Funder-selectable for the E5 wall. */
  def cureTxV3(ctx: BlockchainContext, bond: InputBox, pool: InputBox,
               r9succ: Array[Long], funderAddr: Address): UnsignedTransaction = {
    val rs    = bond.getRegisters
    val funds = Kit.selectBoxes(ctx, funderAddr, Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb    = ctx.newTxBuilder()
    val succ = tb.outBoxBuilder()
      .value(bond.getValue)
      .contract(new ErgoTreeContract(bond.getErgoTree, NetworkType.MAINNET))
      .tokens(bond.getTokens.asScala.toSeq: _*)
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
      .build()
    tb.boxesToSpend((Seq(bond) ++ funds).asJava)
      .withDataInputs(java.util.Arrays.asList(pool))
      .outputs(succ).fee(Kit.TX_FEE).sendChangeTo(funderAddr).build()
  }

  /** thrH from live reserves for a 10M-ERG-leg / 700-RSN / 15M-debt bond —
    * the Phase3Suite recipe, clamped inside the protocol range. */
  def healthyThreshold(pool: InputBox): Long = {
    val ratio = P3.ratioBps(pool, MIN_ORDER_VALUE, RSN_AMT, REPAY)
    val thrH  = math.max(10000L, math.min(30000L, ratio - 3000L))
    require(P3.healthy(pool, MIN_ORDER_VALUE, RSN_AMT, REPAY, thrH),
      s"setup: thrH $thrH must price healthy against live reserves")
    thrH
  }

  /** Bond output of a locally-built match, readable as an InputBox so the
    * register asserts (E3/E9) use the same accessors as everything else. */
  def bondOutOf(tx: UnsignedTransaction): InputBox =
    tx.getOutputs.get(0).convertToInputWith(P4.DUMMY_TX, 0)

  // ---------------- the wall ----------------

  def run(cardA: Option[(String, String)] = None,
          cardB: Option[(String, String)] = None,
          cardC: Option[(String, String)] = None): Unit = Kit.exec { ctx0 =>
    println("=== Phase 4 E-wall: rev-3 structural adversarial wall (E1-E9) ===")

    // Cards: mint fresh unless resume ids were passed (REV3-KICKOFF §2).
    val (cAId, cANft) = cardA.getOrElse(P4.mintCard("c-bonds T2",
      "rev-3 card A: full covenant tier, every field explicit at compiled values",
      P4.CARD_A_R7, P4.explicitCardR8, "card-mint-A"))
    val (cBId, cBNft) = cardB.getOrElse(P4.mintCard("c-bonds sentinel",
      "rev-3 card B: every optional zeroed -> compiled defaults",
      P4.CARD_B_R7, P4.sentinelCardR8, "card-mint-B"))
    val (cCId, cCNft) = cardC.getOrElse(P4.mintCard("c-bonds bounds",
      "rev-3 card C: tightened threshold range + raised order floor",
      P4.CARD_C_R7, P4.sentinelCardR8, "card-mint-C"))
    println(s"card A $cAId  NFT $cANft")
    println(s"card B $cBId  NFT $cBNft")
    println(s"card C $cCId  NFT $cCNft")
    val pinA = ErgoId.create(cANft).getBytes
    val pinB = ErgoId.create(cBNft).getBytes
    val pinC = ErgoId.create(cCNft).getBytes

    // Context-free shared values (trees are deterministic; borrower bytes
    // are the rev-3 script-borrower identity: EIP-3 index-0 P2PK tree).
    val vault         = TestLib.vaultTree()
    val vaultBytes    = vault.bytes
    val vaultVarBytes = TestLib.vaultVariantTree().bytes
    val borrowerBytes = TestLib.borrower(ctx0).getEip3Addresses.get(0)
      .toErgoContract.getErgoTree.bytes
    val poolNftBytes  = ErgoId.create(POOL_NFT).getBytes
    val collatBytes   = ErgoId.create(COLLATERAL_TOKEN_ID).getBytes

    // Each group runs in its own Kit.exec so script evaluation always sees
    // a fresh context after the on-chain post/cancel confirmation waits
    // (Phase3Suite idiom; PHASE3-EVIDENCE wait-order lesson).

    // =====================================================================
    println("=== E1: card wall (refuel-only successor) ===")
    // LOCAL reduces on the REAL card A box; the card is never spent.
    Kit.exec { ctx =>
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val aBox  = ctx.getBoxesById(cAId)(0)
      val base  = P4.honestRefuelPlan(aBox, 0L) // grow 0: mutate ONE dimension at a time

      Kit.expectReduces("E1-twin honest refuel (value +0.001) reduces") {
        b.reduce(P4.buildRefuel(ctx, aBox, P4.honestRefuelPlan(aBox, 1000000L), b), 0).getCost
      }
      Kit.expectScriptFalse("E1a value shrink by 1 nanoERG") {
        b.sign(P4.buildRefuel(ctx, aBox, P4.honestRefuelPlan(aBox, -1L), b))
      }
      Kit.expectScriptFalse("E1b R4 name mutated") {
        b.sign(P4.buildRefuel(ctx, aBox,
          base.copy(r4 = ErgoValue.of("c-bonds T2 v2".getBytes("UTF-8"))), b))
      }
      val r7Mut = P4.CARD_A_R7.clone(); r7Mut(P4.C7.BOUNTY) += 1L
      Kit.expectScriptFalse("E1c R7 numeric mutated (crankBounty + 1)") {
        b.sign(P4.buildRefuel(ctx, aBox, base.copy(r7 = ErgoValue.of(r7Mut)), b))
      }
      Kit.expectScriptFalse("E1d R8 pool NFT swapped for the collateral id") {
        b.sign(P4.buildRefuel(ctx, aBox,
          base.copy(r8 = P4.packValue(Seq(collatBytes, collatBytes))), b))
      }
      Kit.expectScriptFalse("E1e R9 version bumped to \"2\"") {
        b.sign(P4.buildRefuel(ctx, aBox,
          base.copy(r9 = P4.packValue(P4.cardR9(borrowerBytes, "2"))), b))
      }
      // NFT dropped: tokens = Nil on the successor is balance-valid because
      // appkit's change box picks up the unallocated NFT automatically — so
      // the BUILDER accepts it and the refuel guard's `tokens == SELF.tokens`
      // leg is what must reject (NFT preservation is a script property).
      Kit.expectScriptFalse("E1f NFT dropped (rides to funder change)") {
        b.sign(P4.buildRefuel(ctx, aBox, base.copy(tokens = Nil), b))
      }
      // Extra token rider: successor carries NFT + 1 raw RSN. buildRefuel's
      // funding selector is token-free, so this one spend is built manually
      // with the borrower's RSN box routed in; the RSN remainder returns on
      // its OWN min-value box (anti-welding rule). Local sign only — the
      // borrower's RSN never moves on-chain.
      val rsA = aBox.getRegisters
      val rsnBoxes = TestLib.boxesWithToken(ctx, bAddr, COLLATERAL_TOKEN_ID)
      require(rsnBoxes.nonEmpty, "E1g needs the borrower wallet to hold RSN")
      val ergTop   = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE + 2 * Kit.MIN_BOX_VALUE)
      val riderIns = Seq(aBox) ++ rsnBoxes ++ ergTop
      val inToks = riderIns.flatMap(_.getTokens.asScala)
        .groupBy(_.getId.toString).values
        .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
      val succToks = aBox.getTokens.asScala.toSeq :+ new ErgoToken(P3.RSN_ID, 1L)
      val restToks = inToks.flatMap { t =>
        val used = succToks.filter(_.getId.toString == t.getId.toString)
          .map(_.getValue.toLong).sum
        val left = t.getValue.toLong - used
        if (left > 0) Some(new ErgoToken(t.getId, left)) else None
      }
      Kit.expectScriptFalse("E1g extra token rider (1 raw RSN) on the successor") {
        val tb = ctx.newTxBuilder()
        val succ = tb.outBoxBuilder()
          .value(aBox.getValue)
          .contract(new ErgoTreeContract(aBox.getErgoTree, NetworkType.MAINNET))
          .tokens(succToks: _*)
          .registers(rsA.get(0), rsA.get(1), rsA.get(2), rsA.get(3), rsA.get(4), rsA.get(5))
          .build()
        var outs = Seq(succ)
        if (restToks.nonEmpty)
          outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
            .contract(bAddr.toErgoContract).tokens(restToks: _*).build()
        b.sign(tb.boxesToSpend(riderIns.asJava).outputs(outs: _*)
          .fee(Kit.TX_FEE).sendChangeTo(bAddr).build())
      }
      ()
    }

    // =====================================================================
    println("=== E2: forged-card match ===")
    Kit.exec { ctx =>
      val l    = TestLib.lender(ctx)
      val aBox = ctx.getBoxesById(cAId)(0)
      val o2   = P4.postOrderV3(cardPin = pinA, label = "E2 post pinned order")
      val oBox = ctx.getBoxesById(o2)(0)
      // Right anatomy (card A's exact R7/R8), WRONG NFT vs the order's pin.
      val forged = P4.fabCard(ctx, ErgoId.create("55" * 32), P4.CARD_A_R7, P4.explicitCardR8)
      Kit.expectScriptFalse("E2a forged card (right anatomy, wrong NFT) unmatchable") {
        l.sign(P4.buildMatchV3(ctx, oBox, vaultBytes, TestLib.TERM_LONG, Some(forged),
          preHeaderHeight = Some(Kit.nodeHeight())))
      }
      Kit.expectReduces("E2-twin same order against the real card A reduces") {
        l.reduce(P4.buildMatchV3(ctx, oBox, vaultBytes, TestLib.TERM_LONG, Some(aBox),
          preHeaderHeight = Some(Kit.nodeHeight())), 0).getCost
      }
      P4.cancelOrderV3(o2, "E2 cleanup cancel")
      ()
    }

    // =====================================================================
    println("=== E3: sentinel fallback (card B -> compiled defaults) ===")
    Kit.exec { ctx =>
      val l     = TestLib.lender(ctx)
      val cBBox = ctx.getBoxesById(cBId)(0)
      // Covenant-off bullet shape: thresholdBps 0, installment 0 (defaults).
      val o3    = P4.postOrderV3(cardPin = pinB, label = "E3 post card-B order")
      val o3Box = ctx.getBoxesById(o3)(0)
      val preH3 = Kit.nodeHeight()
      val m3 = P4.buildMatchV3(ctx, o3Box, vaultBytes, TestLib.TERM_LONG, Some(cBBox),
        preHeaderHeight = Some(preH3))
      Kit.expectReduces("E3-twin sentinel-card match reduces") {
        l.reduce(m3, 0).getCost
      }
      // Register asserts on the built bond output, read back through
      // convertToInputWith so the standard accessors apply. Belt-and-braces:
      // the harness-side sentinel-resolution mirror must equal the compiled
      // defaults too (if either drifts, the wall and the contract disagree).
      val bond3 = bondOutOf(m3)
      val s3    = TestLib.schedOf(bond3)
      val t3    = TestLib.schedOf(o3Box)
      require(s3.length == 10, s"E3: carded bond R9 has ${s3.length} elements, want 10")
      require(s3(0) == t3(0) && s3(1) == t3(1) && s3(2) == t3(2) &&
              s3(3) == preH3 + t3(1) && s3(4) == t3(4) && s3(5) == t3(5),
        "E3: bond schedule elements 0-5 do not copy the order template")
      require(s3(6) == CRANK_BOUNTY && s3(7) == GRACE_BLOCKS &&
              s3(8) == LIQ_CARVEOUT && s3(9) == HAIRCUT_KEEP,
        "E3: sentinel card did not resolve to compiled defaults in R9(6..9)")
      val r83 = P4.packOf(bond3, 4)
      require(r83.size == 1 && java.util.Arrays.equals(r83.head, vaultBytes),
        "E3: covenant-off bond R8 pack != [lenderScript]")
      require(P4.resolve(P4.CARD_B_R7) == P4.COMPILED_DEFAULTS,
        "E3: harness mirror drift — resolve(CARD_B_R7) != COMPILED_DEFAULTS")
      println("  PASS E3 register assert — R9 = [sched6 | 5000000, 10, 3000000, 9800], R8 = [lenderScript]")
      P4.cancelOrderV3(o3, "E3 cleanup cancel")
      ()
    }

    // =====================================================================
    println("=== E4: bound fields (card C tightens inside the protocol range) ===")
    // Threshold OUTSIDE the protocol range [10000, 30000] is C9's job; here
    // the CARD tightens INSIDE it (L8 reading): thresholds [12000, 20000],
    // minOrderValue raised to 12,000,000 (collateral net of escrow).
    // Sequential post -> assert -> cancel keeps the borrower's RSN cycling
    // through one order at a time (funding-margin lesson).
    Kit.exec { ctx =>
      val l     = TestLib.lender(ctx)
      val cCBox = ctx.getBoxesById(cCId)(0)
      def postC(thr: Long, coll: Long, label: String): String =
        P4.postOrderV3(collateral = coll, repayment = REPAY, term = 24,
          collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)), period = 8L,
          thresholdBps = thr, cardPin = pinC, label = label)
      def matchC(orderId: String): UnsignedTransaction =
        P4.buildMatchV3(ctx, ctx.getBoxesById(orderId)(0), vaultBytes, 24, Some(cCBox),
          preHeaderHeight = Some(Kit.nodeHeight()))

      val o4a = postC(P4.CARD_C_THR_MIN - 1, P4.CARD_C_MIN_ORDER, "E4a post thr 11999")
      Kit.expectScriptFalse("E4a threshold 11999 (cardMin - 1) unmatchable") { l.sign(matchC(o4a)) }
      P4.cancelOrderV3(o4a, "E4a cleanup cancel")

      val o4b = postC(P4.CARD_C_THR_MAX + 1, P4.CARD_C_MIN_ORDER, "E4b post thr 20001")
      Kit.expectScriptFalse("E4b threshold 20001 (cardMax + 1) unmatchable") { l.sign(matchC(o4b)) }
      P4.cancelOrderV3(o4b, "E4b cleanup cancel")

      val o4c = postC(P4.CARD_C_THR_MIN, P4.CARD_C_MIN_ORDER - 1, "E4c post value 11999999")
      Kit.expectScriptFalse("E4c order value 11999999 (card floor - 1) unmatchable") { l.sign(matchC(o4c)) }
      P4.cancelOrderV3(o4c, "E4c cleanup cancel")

      // Boundary twins. The 12000 twin carries collateral exactly at the
      // raised floor, so it doubles as the at-floor value twin (this is how
      // the spec's five orders cover both bound kinds).
      val o4d = postC(P4.CARD_C_THR_MIN, P4.CARD_C_MIN_ORDER, "E4 twin thr 12000")
      Kit.expectReduces("E4-twin threshold 12000 at order value 12000000 reduces (also the at-floor twin)") {
        l.reduce(matchC(o4d), 0).getCost
      }
      P4.cancelOrderV3(o4d, "E4-twin cleanup cancel")

      val o4e = postC(P4.CARD_C_THR_MAX, P4.CARD_C_MIN_ORDER, "E4 twin thr 20000")
      Kit.expectReduces("E4-twin threshold 20000 reduces") {
        l.reduce(matchC(o4e), 0).getCost
      }
      P4.cancelOrderV3(o4e, "E4-twin cleanup cancel")
      ()
    }

    // =====================================================================
    println("=== E5: borrower-auth wall (co-spend replaces the signature) ===")
    // NOTE: every A/B/C-wall borrower negative re-asserts under co-spend
    // semantics via the Phase 1-3 re-runs against the rev-3 tree (kickoff
    // build-order step 7). This wall pins the NEW mechanism only: the
    // borrower arm rejects without a borrower-script input, rejects with a
    // wrong-script co-spend, and reduces for BOTH borrower kinds (P2PK and
    // contract) — no signature involved anywhere.
    Kit.exec { ctx =>
      val b      = TestLib.borrower(ctx)
      val bAddr  = b.getEip3Addresses.get(0)
      val kP     = TestLib.keeper(ctx)
      val kAddr  = kP.getEip3Addresses.get(0)
      val k      = Kit.noSecretProver(ctx)
      val h      = ctx.getHeight
      val pool   = P3.poolBox(ctx)
      val thrH   = healthyThreshold(pool)

      // Trivial borrower CONTRACT (the documented foot-gun class is the
      // borrower's own choice — CONTRACT-DELTAS §3.1; here it is just the
      // simplest satisfiable-by-anyone script for the twin).
      val (trivialTree, trivialContract) =
        Kit.compile(ctx, "{ sigmaProp(HEIGHT > 0) }", ConstantsBuilder.empty())
      val trivialBytes = trivialTree.bytes
      val fabTrivial = ctx.newTxBuilder().outBoxBuilder()
        .value(Kit.MIN_BOX_VALUE).contract(trivialContract)
        .build().convertToInputWith(P4.DUMMY_TX, 1)

      // Covenant-off bullet, rev-3 shape (R5 script bytes, R8 size 1).
      val schedBullet = Array[Long](0L, 500L, 0L, (h + 500).toLong, 0L, 0L)
      val p2pkBond = P4.fabBondV3(ctx, schedBullet, Seq(vaultBytes), borrowerBytes,
        TestLib.COLLATERAL, TestLib.REPAYMENT, h + 500)
      val contractBond = P4.fabBondV3(ctx, schedBullet, Seq(vaultBytes), trivialBytes,
        TestLib.COLLATERAL, TestLib.REPAYMENT, h + 500)
      // In-cure covenant bond that already prices healthy (zero-delta cure).
      val schedCure = Array[Long](0L, PERIOD, 0L, -(h + 5).toLong, thrH, CRANK_BOUNTY)
      val cureBond = P4.fabBondV3(ctx, schedCure, Seq(vaultBytes, poolNftBytes),
        borrowerBytes, MIN_ORDER_VALUE + CRANK_BOUNTY, REPAY, h + 500,
        tokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)))

      // -- negatives: no borrower-script input anywhere in the tx --
      Kit.expectRejected("E5a repay without borrower-script co-spend (keeper-funded)") {
        kP.sign(repayV3(ctx, p2pkBond, Nil, kAddr))
      }
      // Wrong-script co-spend: an explicit non-borrower CONTRACT box rides
      // as an input (satisfiable by anyone), keeper funds again — still no
      // box at the borrower's script, so borrowerAuth must stay false.
      Kit.expectRejected("E5b repay with wrong-script co-spend (trivial-contract box, no borrower box)") {
        kP.sign(repayV3(ctx, p2pkBond, Seq(fabTrivial), kAddr))
      }
      Kit.expectRejected("E5c top-up without borrower-script co-spend (keeper-funded)") {
        kP.sign(topUpV3(ctx, p2pkBond, kAddr, Kit.MIN_BOX_VALUE))
      }
      Kit.expectRejected("E5d cure without borrower-script co-spend (keeper-funded)") {
        kP.sign(cureTxV3(ctx, cureBond, pool, restoreV3(schedCure), kAddr))
      }

      // -- P2PK-borrower twins: same txs funded from the borrower wallet
      // (the borrower's own fee boxes ARE the authorization now) --
      Kit.expectReduces("E5-twin repay borrower-funded reduces") {
        b.reduce(repayV3(ctx, p2pkBond, Nil, bAddr), 0).getCost
      }
      Kit.expectReduces("E5-twin top-up borrower-funded reduces") {
        b.reduce(topUpV3(ctx, p2pkBond, bAddr, Kit.MIN_BOX_VALUE), 0).getCost
      }
      Kit.expectReduces("E5-twin cure borrower-funded reduces") {
        b.reduce(cureTxV3(ctx, cureBond, pool, restoreV3(schedCure), bAddr), 0).getCost
      }

      // -- CONTRACT-borrower twin: bond R5 = trivial script bytes, and the
      // tx co-spends a FAB box at that script. reduce(tx, 0) evaluates only
      // input 0 (the bond), so the fabricated co-spend is fine for a LOCAL
      // reduce; the no-secret prover shows no signature is involved. --
      Kit.expectReduces("E5-twin contract-borrower repay reduces (script co-spend, no key)") {
        k.reduce(repayV3(ctx, contractBond, Seq(fabTrivial), kAddr), 0).getCost
      }

      // -- order cancel under the same mechanism: a REAL tiny order; the
      // keeper's cancel-steal rejects; the genuine cancelOrderV3 (borrower
      // co-spend) is the twin AND the recovery. --
      val o5   = P4.postOrderV3(collateral = MIN_ORDER_VALUE, label = "E5 post tiny order")
      val oBox = ctx.getBoxesById(o5)(0)
      Kit.expectRejected("E5e order cancel without borrower-script co-spend (keeper steal)") {
        val funds = Kit.selectBoxes(ctx, kAddr, Kit.TX_FEE)
        val tb    = ctx.newTxBuilder()
        val out = tb.outBoxBuilder().value(oBox.getValue - Kit.TX_FEE)
          .contract(kAddr.toErgoContract).build()
        kP.sign(tb.boxesToSpend((Seq(oBox) ++ funds).asJava).outputs(out)
          .fee(Kit.TX_FEE).sendChangeTo(kAddr).build())
      }
      P4.cancelOrderV3(o5, "E5-twin real cancel (borrower co-spend)")
      ()
    }

    // =====================================================================
    println("=== E6: R8 pack wall (sizes and element order) ===")
    Kit.exec { ctx =>
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val l     = TestLib.lender(ctx)
      val k     = Kit.noSecretProver(ctx)
      val kAddr = TestLib.keeper(ctx).getEip3Addresses.get(0)
      val h     = ctx.getHeight
      val pool  = P3.poolBox(ctx)
      val thrH  = healthyThreshold(pool)

      // -- ORDER side: one covenant order, every malformed pack override --
      val o6 = P4.postOrderV3(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 8L, thresholdBps = 15000L, label = "E6 post covenant order")
      val o6Box = ctx.getBoxesById(o6)(0)
      def m6(r8: Option[Seq[Array[Byte]]]): UnsignedTransaction =
        P4.buildMatchV3(ctx, o6Box, vaultBytes, 24, None, bondR8Override = r8,
          preHeaderHeight = Some(Kit.nodeHeight()))
      Kit.expectScriptFalse("E6a empty R8 pack (size 0) unmatchable") {
        l.sign(m6(Some(Seq.empty)))
      }
      Kit.expectScriptFalse("E6b swapped pack [poolNFT, lenderScript] unmatchable") {
        l.sign(m6(Some(Seq(poolNftBytes, vaultBytes))))
      }
      Kit.expectScriptFalse("E6c oversized pack (4 elements, garbage suffix) unmatchable") {
        l.sign(m6(Some(Seq(vaultBytes, poolNftBytes, JUNK32, JUNK32))))
      }
      Kit.expectScriptFalse("E6d size-1 pack on a COVENANT order (pool NFT missing) unmatchable") {
        l.sign(m6(Some(Seq(vaultBytes))))
      }
      Kit.expectReduces("E6-twin honest covenant match (size-2 pack) reduces") {
        l.reduce(m6(None), 0).getCost
      }
      P4.cancelOrderV3(o6, "E6 cleanup cancel")

      // -- BOND side: the three legitimate sizes reduce on their paths --
      // size 1: covenant-off repay (borrower-funded co-spend).
      val schedBullet = Array[Long](0L, 500L, 0L, (h + 500).toLong, 0L, 0L)
      val size1Bond = P4.fabBondV3(ctx, schedBullet, Seq(vaultBytes), borrowerBytes,
        TestLib.COLLATERAL, TestLib.REPAYMENT, h + 500)
      Kit.expectReduces("E6e size-1 pack: covenant-off repay reduces") {
        b.reduce(repayV3(ctx, size1Bond, Nil, bAddr), 0).getCost
      }
      // size 2: covenant crank against the live pool (rev-3 reads the pool
      // NFT from its own R8(1)); 6-element covenant fab, compiled defaults.
      val schedCov = Array[Long](0L, PERIOD, 0L, (h - 5).toLong, thrH, CRANK_BOUNTY)
      val size2Bond = P4.fabBondV3(ctx, schedCov, Seq(vaultBytes, poolNftBytes),
        borrowerBytes, MIN_ORDER_VALUE + CRANK_BOUNTY, REPAY, h + 500,
        tokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)))
      Kit.expectReduces("E6f size-2 pack: covenant crank (healthy advance) reduces") {
        k.reduce(crankV3(ctx, size2Bond, Some(pool), advV3(schedCov), h, kAddr), 0).getCost
      }
      // size 3: the hooked-liquidation reduce is E8's right-preimage twin —
      // asserted there on the identical fab shape (single source of truth).
      println("  E6g size-3 pack legit reduce: covered by the E8 right-preimage twin")

      // size 0 fabrication: BRICKED BY DESIGN. The bond's eager r8(0) read
      // crashes on an empty pack — creator's loss, never a victim (AUDIT
      // bricking posture; L1/L6 safe-read applies to the EXTENDED slots,
      // not to the mandatory lender slot). expectRejected's wrong-reason
      // discipline is deliberately relaxed for this single fabrication
      // case: ANY failure (interpreter crash included) is the pass — there
      // is no honest spend of this shape, so no "right reason" exists.
      val brickedBond = P4.fabBondV3(ctx, schedBullet, Seq.empty, borrowerBytes,
        TestLib.COLLATERAL, TestLib.REPAYMENT, h + 500)
      scala.util.Try {
        // Built without reading R8 harness-side (packOf(...).head would
        // crash the HARNESS, failing before the script gets its say).
        val funds = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE + Kit.MIN_BOX_VALUE)
        val tb    = ctx.newTxBuilder()
        val exit = tb.outBoxBuilder()
          .value(TestLib.REPAYMENT)
          .contract(new ErgoTreeContract(vault, NetworkType.MAINNET))
          .tokens(new ErgoToken(brickedBond.getTokens.get(0).getId, 1L))
          .registers(ErgoValue.of(brickedBond.getId.getBytes))
          .build()
        b.sign(tb.boxesToSpend((Seq(brickedBond) ++ funds).asJava)
          .outputs(exit).fee(Kit.TX_FEE).sendChangeTo(bAddr).build())
      } match {
        case scala.util.Success(_) =>
          sys.error("[E6h] size-0 R8 fab bond was SPENDABLE — bricking posture broken")
        case scala.util.Failure(e) =>
          println("  PASS E6h size-0 R8 fab bond unspendable for ANY reason (bricked by design): " +
            Kit.causeChain(e).take(140))
      }
      ()
    }

    // =====================================================================
    println("=== E7: attestation gate (stubbed type, order-gated dead) ===")
    Kit.exec { ctx =>
      val l     = TestLib.lender(ctx)
      val k     = Kit.noSecretProver(ctx)
      val kAddr = TestLib.keeper(ctx).getEip3Addresses.get(0)
      val kTree = kAddr.toErgoContract.getErgoTree
      val h     = ctx.getHeight

      // (a) ORDER side: pin-vs-data-input equality is what matters, so a
      // FAKE pinned NFT with a fabricated card of matching id exercises the
      // aType gate without minting a fourth real card.
      val fakeNft = ErgoId.create("66" * 32)
      val o7 = P4.postOrderV3(cardPin = fakeNft.getBytes, label = "E7 post fake-pin order")
      val o7Box = ctx.getBoxesById(o7)(0)
      val r7Type1 = P4.CARD_A_R7.clone(); r7Type1(P4.C7.ATYPE) = 1L
      val keeperTreeHash: Array[Byte] = scorex.crypto.hash.Blake2b256(kTree.bytes)
      val cardType1 = P4.fabCard(ctx, fakeNft, r7Type1,
        Seq(poolNftBytes, collatBytes, keeperTreeHash))
      val cardType0 = P4.fabCard(ctx, fakeNft, P4.CARD_A_R7,
        Seq(poolNftBytes, collatBytes, keeperTreeHash))
      Kit.expectScriptFalse("E7a card with attestationType 1 unmatchable (order enforces == 0)") {
        l.sign(P4.buildMatchV3(ctx, o7Box, vaultBytes, TestLib.TERM_LONG, Some(cardType1),
          preHeaderHeight = Some(Kit.nodeHeight())))
      }
      Kit.expectReduces("E7-twin same card with attestationType 0 reduces") {
        l.reduce(P4.buildMatchV3(ctx, o7Box, vaultBytes, TestLib.TERM_LONG, Some(cardType0),
          preHeaderHeight = Some(Kit.nodeHeight())), 0).getCost
      }
      P4.cancelOrderV3(o7, "E7 cleanup cancel")

      // (b) BOND side, generic branch — LOCAL, fabricated only: no
      // conforming rev-3 bond can carry a nonzero type (the order gate
      // above), so the branch is reachable only by fabrication, exactly as
      // §5.4 flags. A type-0 bond never touches this branch: the E3/E6
      // covenant fabs already prove the pool dispatch on type 0.
      val (attTree, _) = Kit.compile(ctx, "{ sigmaProp(HEIGHT > 0) }", ConstantsBuilder.empty())
      val attHash: Array[Byte] = scorex.crypto.hash.Blake2b256(attTree.bytes)
      // R9 = 11 elements (6 sched + suffix + aType 1 at index 10, L6);
      // R8 = 4 elements [lenderScript, poolNFT, empty hook, attesterHash].
      val schedAtt = Array[Long](0L, PERIOD, 0L, (h - 5).toLong, 15000L, CRANK_BOUNTY,
        CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP, 1L)
      val attBond = P4.fabBondV3(ctx, schedAtt,
        Seq(vaultBytes, poolNftBytes, Array.emptyByteArray, attHash),
        borrowerBytes, MIN_ORDER_VALUE + CRANK_BOUNTY, REPAY, h + 500)
      val loanId  = ErgoId.create(P4.FAKE_LOAN).getBytes
      val passBox = P4.fabAttesterBox(ctx, attTree, loanId, schedAtt(3), pass = true)
      val failBox = P4.fabAttesterBox(ctx, attTree, loanId, schedAtt(3), pass = false)
      val wrongScriptBox = P4.fabAttesterBox(ctx, kTree, loanId, schedAtt(3), pass = true)

      Kit.expectReduces("E7b crank ADVANCE with pass attester box reduces (verdict 1)") {
        k.reduce(crankV3(ctx, attBond, Some(passBox), advV3(schedAtt), h, kAddr), 0).getCost
      }
      Kit.expectRejected("E7c crank ADVANCE with fail attester box (verdict 0)") {
        k.sign(crankV3(ctx, attBond, Some(failBox), advV3(schedAtt), h, kAddr))
      }
      Kit.expectReduces("E7d-twin crank CURE with fail attester box reduces") {
        k.reduce(crankV3(ctx, attBond, Some(failBox), cureV3(schedAtt), h, kAddr), 0).getCost
      }
      // Fail-closed (verdict -1): missing attestation satisfies NEITHER
      // branch — same posture as the pool read (C8d class).
      Kit.expectRejected("E7e crank ADVANCE with NO data input") {
        k.sign(crankV3(ctx, attBond, None, advV3(schedAtt), h, kAddr))
      }
      Kit.expectRejected("E7f crank CURE with NO data input") {
        k.sign(crankV3(ctx, attBond, None, cureV3(schedAtt), h, kAddr))
      }
      Kit.expectRejected("E7g crank ADVANCE with wrong-script attester box (hash mismatch)") {
        k.sign(crankV3(ctx, attBond, Some(wrongScriptBox), advV3(schedAtt), h, kAddr))
      }
      ()
    }

    // =====================================================================
    println("=== E8: liquidation hook (destination rebind via ctx var 0) ===")
    Kit.exec { ctx =>
      val kP    = TestLib.keeper(ctx)
      val k     = Kit.noSecretProver(ctx)
      val kAddr = kP.getEip3Addresses.get(0)
      val lTree = TestLib.lender(ctx).getEip3Addresses.get(0).toErgoContract.getErgoTree
      val h     = ctx.getHeight
      val matE8 = h + 100
      val hookHash: Array[Byte] = scorex.crypto.hash.Blake2b256(vaultBytes)

      // Per the §5.1 table size 3 presupposes covenant, so the fab is
      // covenant-SHAPED (threshold in R9(4), RSN token) even though the
      // hook fires on the height-gated liquidation arm. R8(0) is the
      // lender's P2PK tree — distinct from the hook script — so the rebind
      // is observable. Pre-header >= maturity opens the liquidation arm.
      val schedH8 = Array[Long](0L, PERIOD, 0L, (h + 20).toLong, 15000L, CRANK_BOUNTY)
      val lenderP2pkBytes = lTree.bytes
      val hookedBond = P4.fabBondV3(ctx, schedH8,
        Seq(lenderP2pkBytes, poolNftBytes, hookHash),
        borrowerBytes, MIN_ORDER_VALUE + CRANK_BOUNTY, REPAY, matE8,
        tokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)))

      Kit.expectReduces("E8-twin right hook preimage: exit at the hook script reduces") {
        k.reduce(P4.buildHookedLiquidation(ctx, hookedBond, vaultBytes, kAddr,
          preHeaderHeight = Some(matE8 + 1)), 0).getCost
      }
      Kit.expectRejected("E8a wrong hook preimage (one-byte variant tree, hash mismatch)") {
        k.sign(P4.buildHookedLiquidation(ctx, hookedBond, vaultVarBytes, kAddr,
          preHeaderHeight = Some(matE8 + 1)))
      }
      // Missing var: a PLAIN liquidation aimed at the hook destination —
      // no context-extension var 0 at all — must reject on a hooked bond.
      Kit.expectRejected("E8b hooked bond: plain liquidation without ctx var 0") {
        kP.sign(TestLib.buildExit(ctx, hookedBond,
          TestLib.liquidationPlan(hookedBond, vault), kP,
          preHeaderHeight = Some(matE8 + 1)))
      }
      // Hook absent (size-2 pack): standard liquidation to R8(0) unchanged.
      val plainBond = P4.fabBondV3(ctx, schedH8, Seq(lenderP2pkBytes, poolNftBytes),
        borrowerBytes, MIN_ORDER_VALUE + CRANK_BOUNTY, REPAY, matE8,
        tokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)))
      Kit.expectReduces("E8c-twin size-2 pack: plain liquidation to the R8(0) lender script reduces") {
        kP.reduce(TestLib.buildExit(ctx, plainBond,
          TestLib.liquidationPlan(plainBond, lTree), kP,
          preHeaderHeight = Some(matE8 + 1)), 0).getCost
      }
      ()
    }

    // =====================================================================
    println("=== E9: card-less match (the T0 baseline, structurally preserved) ===")
    Kit.exec { ctx =>
      val l    = TestLib.lender(ctx)
      val aBox = ctx.getBoxesById(cAId)(0)

      // Empty pin, ZERO data inputs -> wholesale compiled defaults.
      val o9a    = P4.postOrderV3(label = "E9 post card-less order")
      val o9aBox = ctx.getBoxesById(o9a)(0)
      val preH9  = Kit.nodeHeight()
      val m9 = P4.buildMatchV3(ctx, o9aBox, vaultBytes, TestLib.TERM_LONG, None,
        preHeaderHeight = Some(preH9))
      Kit.expectReduces("E9-twin card-less match with zero data inputs reduces") {
        l.reduce(m9, 0).getCost
      }
      // Rev-2 byte-equivalence: R9 stays exactly 6 elements (same
      // register-assert approach as E3, via convertToInputWith).
      val bond9 = bondOutOf(m9)
      val s9    = TestLib.schedOf(bond9)
      val t9    = TestLib.schedOf(o9aBox)
      require(s9.length == 6, s"E9: card-less bond R9 has ${s9.length} elements, want 6 (rev-2 shape)")
      require(s9(0) == t9(0) && s9(1) == t9(1) && s9(2) == t9(2) &&
              s9(3) == preH9 + t9(1) && s9(4) == t9(4) && s9(5) == t9(5),
        "E9: card-less bond schedule does not copy the order template")
      val r89 = P4.packOf(bond9, 4)
      require(r89.size == 1 && java.util.Arrays.equals(r89.head, vaultBytes),
        "E9: card-less bond R8 pack != [lenderScript]")
      println("  PASS E9 register assert — R9 size 6, R8 = [lenderScript] (rev-2 byte shape)")

      // Card-pinned order WITHOUT its data input supplied: unmatchable —
      // and per L9 the guarded cardOk lambda must make this a CLEAN
      // reduce-to-false, never a dataInputs(0) crash (rev-1 crash class).
      val o9b    = P4.postOrderV3(cardPin = pinA, label = "E9 post pinned order")
      val o9bBox = ctx.getBoxesById(o9b)(0)
      Kit.expectScriptFalse("E9a pinned order matched WITHOUT its data input unmatchable") {
        l.sign(P4.buildMatchV3(ctx, o9bBox, vaultBytes, TestLib.TERM_LONG, Some(aBox),
          dropDataInput = true, preHeaderHeight = Some(Kit.nodeHeight())))
      }
      P4.cancelOrderV3(o9a, "E9 cleanup cancel (card-less)")
      P4.cancelOrderV3(o9b, "E9 cleanup cancel (pinned)")
      ()
    }

    // =====================================================================
    println("=== Phase 4 E-wall COMPLETE ===")
    println(s"    cards untouched on-chain (refuel-only held): A $cAId  B $cBId  C $cCId")
    println("    E1 refuel guard, E2 forged card, E3 sentinel fallback, E4 card bounds,")
    println("    E5 borrower-auth co-spend, E6 R8 pack sizes, E7 attestation gate,")
    println("    E8 liquidation hook, E9 card-less baseline: all negatives cleanly")
    println("    rejected, all twins reduced; every posted order cancelled with RSN")
    println("    returned on its own min-box; no malformed transaction was submitted")
    ()
  }

  /** Optional args are (cardBoxId, cardNftId) pairs for A, B, C in order —
    * resume mode for interrupted runs / RunPhase4 hand-off. */
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.length % 2 == 0,
      "usage: Phase4WallE [cardABoxId cardANftId [cardBBoxId cardBNftId [cardCBoxId cardCNftId]]]")
    val pairs = args.toSeq.grouped(2).collect { case Seq(bx, nft) => (bx, nft) }.toSeq
    run(pairs.lift(0), pairs.lift(1), pairs.lift(2))
  }
}
