package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 2 suite, numbered per conforming-bond-build-plan.md §3 Phase 2.
  *
  * Written from the spec before the attacked paths. Crank negatives are
  * signatureless-path attacks (expectRejected: reduce-to-false OR
  * unprovable borrower residual — both mean no anonymous party can spend);
  * top-up negatives are borrower-signed (expectScriptFalse: clean
  * reduce-to-false). Register-wall negatives are pinned deterministically
  * with a pre-header inside the crank window (A3 pattern) against a bond
  * whose only checkpoint is ~716 blocks out, so nothing malformed is ever
  * submitted; on-chain txs are creations, honest cranks/top-ups, the B13
  * race, and repay cleanups (each test recovers its dust).
  */

/** T4: keeper cranks a checkpoint signaturelessly and with ZERO capital
  * (the bond is the tx's only input; the bounty pays the fee and the
  * keeper box). Second checkpoint is cranked by the borrower — pinning
  * the borrower-self-crank-permitted decision. Successor identity and
  * exact deltas verified on-chain after each crank.
  */
object T4_CrankCheckpoint {
  val TERM = 24; val PERIOD = 6L   // checkpoints at +6,+12,+18; K = 23/6 = 3
  /** Returns the twice-cranked successor id for T5. */
  def run(): String = {
    println("=== T4: checkpoint crank (keeper, then borrower self-crank) ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TERM, vault, period = PERIOD)
    val (firstCheck, escrow0) = Kit.exec { ctx =>
      val s = TestLib.schedOf(ctx.getBoxesById(bondId)(0)); (s(3).toInt, s(5))
    }
    require(escrow0 == 3L * Contracts.CRANK_BOUNTY,
      s"T4: escrow $escrow0 != 3 bounties (K = (24-1)/6 = 3)")
    println(s"  bond $bondId: maturity $maturity, first checkpoint $firstCheck, escrow $escrow0")

    Kit.waitForHeight(firstCheck + 2)
    val succ1 = P2.doCrank(bondId, "crank(keeper, signatureless)", TestLib.keeper,
      ctx => TestLib.keeper(ctx).getEip3Addresses.get(0))

    Kit.waitForHeight(firstCheck + PERIOD.toInt + 2)
    val succ2 = P2.doCrank(succ1, "self-crank(borrower)", TestLib.borrower,
      ctx => TestLib.borrower(ctx).getEip3Addresses.get(0))

    println("T4 PASS (keeper crank + borrower self-crank, zero keeper capital)")
    succ2
  }
  def main(args: Array[String]): Unit = { run(); () }
}

/** T5: borrower tops up collateral mid-period on the cranked successor;
  * schedule pack frozen verbatim, value strictly up. Cleanup repay proves
  * the Phase 1 exit wall holds on a twice-cranked, topped-up bond, and
  * returns the residual (uncranked third checkpoint) escrow to the
  * borrower via the full-value sweep.
  */
object T5_TopUpCollateral {
  def run(bondId: String): Unit = {
    println("=== T5: borrower tops up collateral mid-period ===")
    val succ = P2.doTopUp(bondId, 5000000L, "top-up(borrower-signed)")
    TestLib.doExit(succ, TestLib.vaultTree(), asRepay = true,
      "T5-cleanup repay(cranked+topped bond)", TestLib.borrower)
    println("T5 PASS")
  }
  def main(args: Array[String]): Unit = args.headOption match {
    case Some(id) => run(id)
    case None     => sys.error("usage: T5_TopUpCollateral <bondBoxId>")
  }
}

/** B0: origination-side escrow + grid validation (the K coherence is
  * established at match; these pin it). All negatives are local builds by
  * the lender against real orders; every order is cancelled after.
  */
object B0_OriginationEscrow {
  def run(): Unit = {
    println("=== B0: origination escrow + checkpoint-grid validation ===")
    val vaultBytes = TestLib.vaultTree().bytes

    // B0a: real grid (period 8, term 720 => needs 89 bounties), escrow 0.
    val badEscrow = P2.postOrderRaw(TestLib.COLLATERAL,
      Array[Long](0L, 8L, 0L, 0L, 0L, 0L), term = 720)
    Kit.exec { ctx =>
      val ob = ctx.getBoxesById(badEscrow)(0)
      Kit.expectRejected("B0a match of an order claiming zero escrow for an 89-checkpoint grid") {
        TestLib.lender(ctx).sign(P2.buildMatch(ctx, ob, vaultBytes, 720))
      }
      ()
    }
    P2.cancelOrder(badEscrow, "B0a-cleanup cancel")

    // B0b: honest order; the funder stamps a wrong grid.
    val order = TestLib.postOrder(term = 24, period = 8L)   // K = 2, escrow = 2 bounties
    Kit.exec { ctx =>
      val ob   = ctx.getBoxesById(order)(0)
      val l    = TestLib.lender(ctx)
      val tmpl = TestLib.schedOf(ob)
      val m    = Kit.nodeHeight() + 24
      Kit.expectRejected("B0b match stamping the first checkpoint two periods in") {
        l.sign(P2.buildMatch(ctx, ob, vaultBytes, 24, bondSchedOverride = Some(Array[Long](
          tmpl(0), tmpl(1), tmpl(2), (m - 24).toLong + 2L * tmpl(1), tmpl(4), tmpl(5)))))
      }
      Kit.expectRejected("B0b match stamping nextCheckHeight = maturity (Phase 1 shape)") {
        l.sign(P2.buildMatch(ctx, ob, vaultBytes, 24, bondSchedOverride = Some(Array[Long](
          tmpl(0), tmpl(1), tmpl(2), m.toLong, tmpl(4), tmpl(5)))))
      }
      Kit.expectReduces("B0b-twin honest match reduces") {
        l.reduce(P2.buildMatch(ctx, ob, vaultBytes, 24), 0).getCost
      }
      ()
    }
    P2.cancelOrder(order, "B0b-cleanup cancel")

    // B0c: net-of-escrow collateral floor — 0.008 ERG collateral behind a
    // fully-funded escrow is below MIN_ORDER_VALUE, unmatchable.
    val thin = P2.postOrderRaw(8000000L,
      Array[Long](0L, 8L, 0L, 0L, 0L, Contracts.escrowFor(24, 8L)), term = 24)
    Kit.exec { ctx =>
      val ob = ctx.getBoxesById(thin)(0)
      Kit.expectRejected("B0c match of sub-floor net collateral hiding behind escrow") {
        TestLib.lender(ctx).sign(P2.buildMatch(ctx, ob, vaultBytes, 24))
      }
      ()
    }
    P2.cancelOrder(thin, "B0c-cleanup cancel")
    println("B0 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** B1-B12, B14: the successor register wall. One local-wall bond (term
  * 720, period 716 => single checkpoint at ~anchor+716, escrow = 1
  * bounty); every crank negative is built at a pre-header inside the
  * crank window and must be rejected for its one mutated field. Top-up
  * negatives run at real height (gate closed => failures attributable to
  * the top-up wall alone). Nothing malformed is submitted.
  */
object B_SuccessorWall {
  def run(): Unit = {
    println("=== B1-B12,B14: successor register wall (local, pre-header pinned) ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(720, vault, period = 716L)
    Kit.exec { ctx =>
      val bondBox  = ctx.getBoxesById(bondId)(0)
      val k        = TestLib.keeper(ctx); val kAddr = k.getEip3Addresses.get(0)
      val b        = TestLib.borrower(ctx)
      val s        = TestLib.schedOf(bondBox)
      val inWindow = Some(s(3).toInt + 1)   // nextCheck <= H < maturity
      val honest   = P2.honestCrankPlan(ctx, bondBox)

      def rejectCrank(label: String, plan: P2.CrankPlan, preH: Option[Int] = inWindow): Unit =
        Kit.expectRejected(label) { k.sign(P2.buildCrank(ctx, bondBox, plan, kAddr, preH)) }
      def mutSched(i: Int, v: Long): Array[Long] = { val a = honest.r9.clone(); a(i) = v; a }

      Kit.expectReduces("B-twin honest crank reduces in window") {
        k.reduce(P2.buildCrank(ctx, bondBox, honest, kAddr, inWindow), 0).getCost
      }

      // one register at a time
      rejectCrank("B1 successor R4 (order id) mutated",
        honest.copy(r4 = ErgoValue.of(Array.fill[Byte](32)(0))))
      // Rev 4: R5 is the borrower script HASH, so the mask writes the
      // keeper's script hash — a well-formed value for the WRONG party.
      rejectCrank("B2 successor R5 (borrower hash) swapped to the keeper",
        honest.copy(r5 = ErgoValue.of(P4.h32(kAddr.toErgoContract.getErgoTree.bytes))))
      rejectCrank("B3 successor R6 (repayment) reduced by 1",
        honest.copy(r6 = ErgoValue.of(
          bondBox.getRegisters.get(2).getValue.asInstanceOf[Long] - 1L)))
      rejectCrank("B4 successor R7 (maturity) extended by 100",
        honest.copy(r7 = ErgoValue.of(maturity + 100)))
      // Rev 4: R8(0) is the lender script HASH — "one byte off" becomes
      // the hash OF the one-byte-off vault variant (same attack, hashed).
      rejectCrank("B5 successor R8(0) = hash of the one-byte-off lender script",
        honest.copy(r8 = P4.packValue(Seq(P4.h32(TestLib.vaultVariantTree().bytes)))))

      // every schedule element beyond the permitted advance
      rejectCrank("B6a schedule installment mutated",       honest.copy(r9 = mutSched(0, 1L)))
      rejectCrank("B6b schedule periodBlocks mutated",      honest.copy(r9 = mutSched(1, s(1) + 1L)))
      rejectCrank("B6c schedule paymentsRemaining mutated", honest.copy(r9 = mutSched(2, 1L)))
      rejectCrank("B6d nextCheckHeight not advanced",       honest.copy(r9 = mutSched(3, s(3))))
      rejectCrank("B6d' nextCheckHeight advanced twice",    honest.copy(r9 = mutSched(3, s(3) + 2L * s(1))))
      rejectCrank("B6e maintenanceThresholdBps mutated",    honest.copy(r9 = mutSched(4, 1L)))
      rejectCrank("B6f escrow not decremented",             honest.copy(r9 = mutSched(5, s(5))))

      // collateral and script
      rejectCrank("B7 successor value short beyond the bounty (correct registers)",
        honest.copy(value = honest.value - Kit.MIN_BOX_VALUE))
      rejectCrank("B8 successor at a different script (contract swap to vault)",
        honest.copy(contract = new ErgoTreeContract(vault, NetworkType.MAINNET)))

      // window edges: early crank, and the liquidation-delay grief
      rejectCrank("B11 crank one block before the checkpoint", honest, Some(s(3).toInt - 1))
      rejectCrank("B11' stale checkpoint cranked at maturity (post-maturity grief)",
        honest, Some(maturity))

      // bounty / escrow drain, register and value sides isolated
      rejectCrank("B12a bounty overdrawn (value down 2x, register honest)",
        honest.copy(value = honest.value - Contracts.CRANK_BOUNTY))
      rejectCrank("B12b escrow register double-decremented (value honest)",
        honest.copy(r9 = mutSched(5, s(5) - 2L * Contracts.CRANK_BOUNTY)))

      // loan-token integrity (protocol forbids true duplication of a
      // supply-1 token; these are the protocol-legal analogs)
      rejectCrank("B14a loan token missing from successor (routed to cranker)",
        honest.copy(tokens = Nil, extraTokensToCranker = honest.tokens))
      rejectCrank("B14b counterfeit successor token (fresh mint, id == bond box id)",
        honest.copy(tokens = Seq(new ErgoToken(bondBox.getId, 1L)),
                    extraTokensToCranker = honest.tokens))

      // top-up wall (borrower-signed => clean reduce-to-false)
      Kit.expectScriptFalse("B9a top-up netting zero") {
        b.sign(P2.buildTopUp(ctx, bondBox, 0L, b))
      }
      Kit.expectScriptFalse("B9b top-up netting negative (withdrawal attempt)") {
        b.sign(P2.buildTopUp(ctx, bondBox, -Kit.MIN_BOX_VALUE, b))
      }
      Kit.expectScriptFalse("B10 top-up touching the schedule pack (threshold element)") {
        b.sign(P2.buildTopUp(ctx, bondBox, Kit.MIN_BOX_VALUE, b,
          r9Override = Some(mutSched(4, 1L))))
      }
      Kit.expectScriptFalse("B10' top-up smuggling a crank advance") {
        b.sign(P2.buildTopUp(ctx, bondBox, Kit.MIN_BOX_VALUE, b,
          r9Override = Some(honest.r9)))
      }
      Kit.expectReduces("B-twin honest top-up reduces") {
        b.reduce(P2.buildTopUp(ctx, bondBox, Kit.MIN_BOX_VALUE, b), 0).getCost
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = true, "B-wall cleanup repay", TestLib.borrower)
    println("B1-B12,B14 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** B16: the successor wall under token collateral. 600 test tokens
  * minted; 500 collateralized, 100 kept for the growth twin.
  */
object B16_TokenCollateralWall {
  def run(): Unit = {
    println("=== B16: token-collateral successor wall (local) ===")
    val vault   = TestLib.vaultTree()
    val tokenId = TestLib.mintTestToken(600L)
    val coll    = Seq(new ErgoToken(tokenId, 500L))
    val (bondId, _) = TestLib.cycle(720, vault, collTokens = coll, period = 716L)
    Kit.exec { ctx =>
      val bondBox  = ctx.getBoxesById(bondId)(0)
      val k        = TestLib.keeper(ctx); val kAddr = k.getEip3Addresses.get(0)
      val b        = TestLib.borrower(ctx); val bAddr = b.getEip3Addresses.get(0)
      val s        = TestLib.schedOf(bondBox)
      val inWindow = Some(s(3).toInt + 1)
      val honest   = P2.honestCrankPlan(ctx, bondBox)
      val loan     = honest.tokens.head
      val collTok  = honest.tokens(1)

      Kit.expectRejected("B16a crank dropping collateral tokens (ERG exact)") {
        k.sign(P2.buildCrank(ctx, bondBox,
          honest.copy(tokens = Seq(loan), extraTokensToCranker = Seq(collTok)), kAddr, inWindow))
      }
      Kit.expectRejected("B16b crank with token slots reordered") {
        k.sign(P2.buildCrank(ctx, bondBox,
          honest.copy(tokens = Seq(collTok, loan)), kAddr, inWindow))
      }
      Kit.expectScriptFalse("B16c top-up withholding collateral tokens") {
        b.sign(P2.buildTopUp(ctx, bondBox, Kit.MIN_BOX_VALUE, b,
          tokensOverride = Some(Seq(loan, new ErgoToken(tokenId, 499L)))))
      }
      val tokenBoxes = TestLib.boxesWithToken(ctx, bAddr, tokenId)
      Kit.expectReduces("B16-twin token-growth top-up (500 -> 600) reduces") {
        b.reduce(P2.buildTopUp(ctx, bondBox, 0L, b,
          tokensOverride = Some(Seq(loan, new ErgoToken(tokenId, 600L))),
          extraInputs = tokenBoxes), 0).getCost
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = true, "B16 cleanup repay(token bond)", TestLib.borrower)
    println("B16 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** B13: two keepers race the same checkpoint on-chain. First submission
  * enters the mempool, the conflicting one is rejected (or races — the
  * winner is detected either way); the singleton invariant is asserted on
  * confirmed chain state, and the loser's bot retry against the advanced
  * successor is cleanly rejected (gate not yet due).
  */
object B13_DoubleCrankRace {
  val TERM = 24; val PERIOD = 6L
  def run(): Unit = {
    println("=== B13: double-crank mempool race + bot retry ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TERM, vault, period = PERIOD)
    val firstCheck = Kit.exec { ctx => TestLib.schedOf(ctx.getBoxesById(bondId)(0))(3).toInt }
    Kit.waitForHeight(firstCheck + 2)

    val (tx1Id, tx2Id, succ1Id, succ2Id) = Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val k = TestLib.keeper(ctx); val kAddr = k.getEip3Addresses.get(0)
      val l = TestLib.lender(ctx); val lAddr = l.getEip3Addresses.get(0)
      val plan    = P2.honestCrankPlan(ctx, bondBox)
      val signed1 = k.sign(P2.buildCrank(ctx, bondBox, plan, kAddr))
      val signed2 = l.sign(P2.buildCrank(ctx, bondBox, plan, lAddr))
      val id1 = ctx.sendTransaction(signed1).replace("\"", "")
      println(s"  keeper crank submitted: $id1")
      val id2 = try {
        val accepted = ctx.sendTransaction(signed2).replace("\"", "")
        println(s"  NOTE: node accepted the conflicting crank too: $accepted (pool race)")
        accepted
      } catch {
        case e: Exception =>
          println(s"  conflicting crank rejected by the mempool (expected): ${
            Option(e.getMessage).getOrElse("").take(160)}")
          signed2.getId
      }
      (id1, id2, signed1.getOutputsToSpend.get(0).getId.toString,
        signed2.getOutputsToSpend.get(0).getId.toString)
    }

    var winner: Option[String] = None
    var tries  = 0
    while (winner.isEmpty && tries < 80) {
      if (Kit.txConfirmed(tx1Id)) winner = Some(succ1Id)
      else if (tx2Id != tx1Id && Kit.txConfirmed(tx2Id)) winner = Some(succ2Id)
      else { Thread.sleep(15000); tries += 1 }
    }
    val succId = winner.getOrElse(sys.error("B13: neither crank confirmed"))
    println(s"  first-confirmed successor: $succId (second invalidated harmlessly)")

    Kit.exec { ctx =>
      val succ = ctx.getBoxesById(succId)(0)
      val ns   = TestLib.schedOf(succ)
      require(ns(3) == firstCheck.toLong + PERIOD, "B13: nextCheckHeight advanced != one period")
      require(ns(5) == 2L * Contracts.CRANK_BOUNTY, "B13: escrow decremented != one bounty")
      val loanId   = succ.getTokens.get(0).getId.toString
      val bondAddr = Address.fromErgoTree(Contracts.bond(ctx)._1, NetworkType.MAINNET)
      val holders  = TestLib.boxesWithToken(ctx, bondAddr, loanId)
      require(holders.size == 1, s"B13: loan token in ${holders.size} unspent bond boxes, want 1")
      println("  singleton invariant holds: exactly one successor carries the loan token")

      // bot retry (only meaningful while the next checkpoint is not due)
      if (Kit.nodeHeight() < firstCheck + PERIOD.toInt) {
        val k = TestLib.keeper(ctx); val kAddr = k.getEip3Addresses.get(0)
        Kit.expectRejected("B13 loser bot retry against the advanced successor") {
          k.sign(P2.buildCrank(ctx, succ, P2.honestCrankPlan(ctx, succ), kAddr))
        }
      } else println("  NOTE: next checkpoint already due; retry-rejection assert skipped")
      ()
    }
    TestLib.doExit(succId, vault, asRepay = true, "B13 cleanup repay", TestLib.borrower)
    println("B13 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** Full Phase 2 suite, in order, with a summary. Local walls first (no
  * block waits), then origination negatives, then the timed happy paths
  * and the race.
  */
object RunPhase2 {
  def main(args: Array[String]): Unit = {
    Kit.exec { ctx => TestLib.verifyWallets(ctx) }
    var t4Succ: Option[String] = None
    // B16 runs LAST: its cleanup repay welds the test-token collateral onto
    // the borrower's ERG change, which the token-free selector then skips —
    // running it earlier starves every later test of clean funds (learned
    // the hard way on the first Phase 2 run; Recycle burns the strays).
    val steps: Seq[(String, () => Unit)] = Seq(
      "B1-B12,B14 successor wall" -> { () => B_SuccessorWall.run() },
      "B0 origination escrow/grid" -> { () => B0_OriginationEscrow.run() },
      "T4 crank + self-crank"     -> { () => t4Succ = Some(T4_CrankCheckpoint.run()) },
      "T5 top-up + repay"         -> { () => T5_TopUpCollateral.run(
        t4Succ.getOrElse(sys.error("T4 did not produce a successor"))) },
      "B13 double-crank race"     -> { () => B13_DoubleCrankRace.run() },
      "B16 token-collateral wall" -> { () => B16_TokenCollateralWall.run() }
    )
    val results = steps.map { case (name, f) =>
      val r = scala.util.Try(f())
      r.failed.foreach(e => println(s"FAIL $name: ${Kit.causeChain(e)}"))
      name -> r.isSuccess
    }
    println("\n=== Phase 2 suite summary ===")
    results.foreach { case (n, ok) => println(f"  ${if (ok) "PASS" else "FAIL"}  $n") }
    val failed = results.count(!_._2)
    println(s"${results.size - failed}/${results.size} passed")
    if (failed > 0) sys.exit(1)
  }
}
