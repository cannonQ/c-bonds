package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 3 adversarial suite, numbered per build-plan §3 Phase 3 plus the
  * audit-driven pins (AUDIT.md Phase 3 INFO items). Written from the spec
  * before the attacked paths were exercised on-chain.
  *
  * Pattern (Phase 2 discipline carried forward): signatureless-path
  * attacks use expectRejected (reduce-to-false OR unprovable borrower
  * residual); borrower-signed attacks use expectScriptFalse; every
  * negative has a minimally-differing pass-twin. Register/pack negatives
  * are pinned deterministically with pre-headers against (a) one REAL
  * on-chain covenant bond D whose only checkpoint sits ~680 blocks out
  * (A3 far-future pre-header pattern) and (b) locally fabricated bonds
  * for states that need the other verdict branch or the cure encoding —
  * every reduce, real or fabricated, prices against the LIVE pool box as
  * its data input. Nothing malformed is ever submitted; on-chain txs are
  * order posts/cancels, bond D's cycle, C2's node-level rejection probe,
  * and cleanup repay.
  */
object Phase3Suite {
  import Contracts._

  val DUMMY_TX  = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val DUMMY_TX2 = "ab" * 32
  val FAKE_LOAN = ErgoId.create("11" * 32)
  val FAKE_NFT  = ErgoId.create("22" * 32)
  val FAKE_LP   = ErgoId.create("33" * 32)
  val RSN_AMT   = 500L
  val REPAY     = 15000000L
  val PERIOD    = 20L

  // ---------------- fabricated-state helpers ----------------

  def fabBond(ctx: BlockchainContext, value: Long, sched: Array[Long],
              maturity: Int, lenderTree: Array[Byte], rsnAmt: Long = RSN_AMT,
              dummyTx: String = DUMMY_TX): InputBox = {
    val bAddr = TestLib.borrower(ctx).getEip3Addresses.get(0)
    var ob = ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(Contracts.bond(ctx)._2)
    ob = if (rsnAmt > 0)
      ob.tokens(new ErgoToken(FAKE_LOAN, 1L), new ErgoToken(P3.RSN_ID, rsnAmt))
    else
      ob.tokens(new ErgoToken(FAKE_LOAN, 1L))
    // Rev 3: R5 borrower script bytes; R8 pack sized by the covenant shape.
    val r8Pack =
      if (sched(4) != 0L) Seq(lenderTree, ErgoId.create(Contracts.POOL_NFT).getBytes)
      else Seq(lenderTree)
    ob.registers(
        ErgoValue.of(FAKE_LOAN.getBytes),
        ErgoValue.of(bAddr.toErgoContract.getErgoTree.bytes),
        ErgoValue.of(REPAY),
        ErgoValue.of(maturity),
        P4.packValue(r8Pack),
        ErgoValue.of(sched))
      .build()
      .convertToInputWith(dummyTx, 0)
  }

  /** Lookalike pool: right anatomy, wrong NFT — reserves chosen by the
    * attacker to price anything they like. Data-input scripts never
    * execute, so the guarding script is irrelevant (borrower P2PK).
    */
  def fabLookalikePool(ctx: BlockchainContext, rX: Long, rY: Long): InputBox = {
    val bAddr = TestLib.borrower(ctx).getEip3Addresses.get(0)
    ctx.newTxBuilder().outBoxBuilder()
      .value(rX)
      .contract(bAddr.toErgoContract)
      .tokens(new ErgoToken(FAKE_NFT, 1L), new ErgoToken(FAKE_LP, 1000000L),
              new ErgoToken(P3.RSN_ID, rY))
      .registers(ErgoValue.of(990))
      .build()
      .convertToInputWith(DUMMY_TX2, 0)
  }

  def buildCrank(ctx: BlockchainContext, bond: InputBox, pool: Option[InputBox],
                 r9succ: Array[Long], preH: Int, payTo: Address,
                 valueDelta: Long = CRANK_BOUNTY): UnsignedTransaction = {
    val rs = bond.getRegisters
    val tb = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(preH).build())
    val sb = tb.outBoxBuilder()
      .value(bond.getValue - valueDelta)
      .contract(new ErgoTreeContract(bond.getErgoTree, NetworkType.MAINNET))
      .tokens(bond.getTokens.asScala.toSeq: _*)
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
      .build()
    val kb = tb.outBoxBuilder()
      .value(valueDelta - Kit.TX_FEE)
      .contract(payTo.toErgoContract).build()
    var b = tb.boxesToSpend(java.util.Arrays.asList(bond))
    pool.foreach(p => b = b.withDataInputs(java.util.Arrays.asList(p)))
    b.outputs(sb, kb).fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  def buildCure(ctx: BlockchainContext, bond: InputBox, pool: Option[InputBox],
                addValue: Long, r9succ: Array[Long],
                tokensOverride: Option[Seq[ErgoToken]] = None): UnsignedTransaction = {
    val b     = TestLib.borrower(ctx)
    val bAddr = b.getEip3Addresses.get(0)
    val rs    = bond.getRegisters
    val funds = Kit.selectBoxes(ctx, bAddr, math.max(addValue, 0L) + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val toks  = tokensOverride.getOrElse(bond.getTokens.asScala.toSeq)
    val tb    = ctx.newTxBuilder()
    var sb = tb.outBoxBuilder()
      .value(bond.getValue + addValue)
      .contract(new ErgoTreeContract(bond.getErgoTree, NetworkType.MAINNET))
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
    if (toks.nonEmpty) sb = sb.tokens(toks: _*)
    var bt = tb.boxesToSpend((Seq(bond) ++ funds).asJava)
    pool.foreach(p => bt = bt.withDataInputs(java.util.Arrays.asList(p)))
    bt.outputs(sb.build()).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
  }

  def buildAccel(ctx: BlockchainContext, bond: InputBox, pool: Option[InputBox],
                 destTree: sigmastate.Values.ErgoTree, exitValue: Long, preH: Int,
                 receiptR4: Option[Array[Byte]], tokens: Seq[ErgoToken],
                 payTo: Address,
                 keeperTokens: Seq[ErgoToken] = Nil): UnsignedTransaction = {
    val tb = ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(preH).build())
    var eb = tb.outBoxBuilder()
      .value(exitValue)
      .contract(new ErgoTreeContract(destTree, NetworkType.MAINNET))
    if (tokens.nonEmpty) eb = eb.tokens(tokens: _*)
    receiptR4.foreach(r4 => eb = eb.registers(ErgoValue.of(r4)))
    var kb0 = tb.outBoxBuilder()
      .value(bond.getValue - exitValue - Kit.TX_FEE)
      .contract(payTo.toErgoContract)
    // Withheld-token attacks route the stolen tokens to the attacker —
    // without this the BUILDER fails on unallocated tokens and the test
    // fails for the wrong reason (the script must do the rejecting).
    if (keeperTokens.nonEmpty) kb0 = kb0.tokens(keeperTokens: _*)
    val kb = kb0.build()
    var b = tb.boxesToSpend(java.util.Arrays.asList(bond))
    pool.foreach(p => b = b.withDataInputs(java.util.Arrays.asList(p)))
    b.outputs(eb.build(), kb).fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  def curePackOf(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = -(s(3) + GRACE_BLOCKS); r(5) = s(5) - CRANK_BOUNTY; r
  }
  def advancePackOf(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = s(3) + s(1); r(5) = s(5) - CRANK_BOUNTY; r
  }
  def restorePackOf(s: Array[Long]): Array[Long] = {
    val r = s.clone(); r(3) = (-s(3)) - GRACE_BLOCKS + s(1); r
  }

  // ---------------- main ----------------

  def main(args: Array[String]): Unit = {
    println("=== Phase 3 adversarial suite (C-wall) ===")
    val vault      = TestLib.vaultTree()
    val vaultBytes = vault.bytes
    // Resume mode: pass <bondDId> <maturityD> to skip the already-green
    // C9 group and reuse a live bond D from an interrupted run.
    val resume: Option[(String, Int)] =
      if (args.length == 2) Some((args(0), args(1).toInt)) else None

    // Thresholds from live reserves, same recipe as the happy path.
    val (thrU, thrH) = Kit.exec { ctx =>
      val pool  = P3.poolBox(ctx)
      val ratio = P3.ratioBps(pool, MIN_ORDER_VALUE, RSN_AMT, REPAY)
      val u = math.min(30000L, ratio + 3000L)
      val h = math.max(10000L, ratio - 3000L)
      require(!P3.healthy(pool, MIN_ORDER_VALUE, RSN_AMT, REPAY, u), "setup: thrU must be unhealthy")
      require(P3.healthy(pool, MIN_ORDER_VALUE, RSN_AMT, REPAY, h), "setup: thrH must be healthy")
      println(s"live ratio $ratio bps -> thrU $u (unhealthy), thrH $h (healthy)")
      (u, h)
    }

    // ---------- C9: order-side wall (on-chain posts, local negatives, cancels) ----------
    if (resume.isEmpty) {
    println("--- C9: covenant order origination wall ---")
    Kit.exec { ctx =>
      val l = TestLib.lender(ctx)
      def matchOf(orderId: String): UnsignedTransaction = {
        val ob = ctx.getBoxesById(orderId)(0)
        P2.buildMatch(ctx, ob, vaultBytes, 24)
      }
      // C9a: covenant threshold, NO collateral token
      val o1 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, period = 8L, thresholdBps = thrU)
      Kit.expectRejected("C9a covenant order without collateral token unmatchable") {
        l.sign(matchOf(o1))
      }
      P2.cancelOrder(o1, "C9a cleanup cancel")

      // C9b: covenant threshold, WRONG token id
      val fakeTok = TestLib.mintTestToken(600L)
      val o2 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(ErgoId.create(fakeTok), RSN_AMT)),
        period = 8L, thresholdBps = thrU)
      Kit.expectRejected("C9b covenant order with wrong collateral token id unmatchable") {
        l.sign(matchOf(o2))
      }
      P2.cancelOrder(o2, "C9b cleanup cancel")

      // C9c: covenant BULLET (period == term, K = 0) — LOW-P3-O1 pin
      val o3 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 24L, thresholdBps = thrU)
      Kit.expectRejected("C9c covenant bullet (K=0, covenant could never fire) unmatchable") {
        l.sign(matchOf(o3))
      }
      P2.cancelOrder(o3, "C9c cleanup cancel")

      // C9d/C9e: threshold just outside the economic range
      val o4 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 8L, thresholdBps = 9999L)
      Kit.expectRejected("C9d threshold 9999 (below economic floor) unmatchable") {
        l.sign(matchOf(o4))
      }
      P2.cancelOrder(o4, "C9d cleanup cancel")
      val o5 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 8L, thresholdBps = 30001L)
      Kit.expectRejected("C9e threshold 30001 (above economic cap) unmatchable") {
        l.sign(matchOf(o5))
      }
      P2.cancelOrder(o5, "C9e cleanup cancel")

      // C9 twins: both boundary thresholds matchable (local reduce only)
      val o6 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 8L, thresholdBps = 10000L)
      Kit.expectReduces("C9-twin threshold 10000 match reduces") {
        l.reduce(matchOf(o6), 0).getCost
      }
      P2.cancelOrder(o6, "C9-twin cleanup cancel")
      val o7 = TestLib.postOrder(collateral = MIN_ORDER_VALUE, repayment = REPAY,
        term = 24, collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 8L, thresholdBps = 30000L)
      Kit.expectReduces("C9-twin threshold 30000 match reduces") {
        l.reduce(matchOf(o7), 0).getCost
      }
      P2.cancelOrder(o7, "C9-twin cleanup cancel")
      ()
    }
    } // end resume.isEmpty (C9)

    // ---------- bond D: the real on-chain wall anchor ----------
    val (bondD, matD) = resume.getOrElse {
      println("--- bond D: real covenant bond, checkpoint ~680 blocks out ---")
      TestLib.cycle(720, vault,
        collTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT)),
        period = 700L, thresholdBps = thrU,
        collateral = MIN_ORDER_VALUE, repayment = REPAY)
    }
    val chkD = matD - 20    // (m - 720) + 700
    println(s"bond D $bondD: maturity $matD, checkpoint $chkD (K = 1)")

    // ---------- local walls against live pool ----------
    Kit.exec { ctx =>
      val k      = Kit.noSecretProver(ctx)
      val b      = TestLib.borrower(ctx)
      val kAddr  = TestLib.keeper(ctx).getEip3Addresses.get(0)
      val pool   = P3.poolBox(ctx)
      val h      = ctx.getHeight
      val dBox   = ctx.getBoxesById(bondD)(0)
      val sD     = P3.schedOf(dBox)
      val preD   = chkD + 2
      require(!P3.healthy(pool, dBox.getValue - sD(5), RSN_AMT, REPAY, sD(4)),
        "bond D must price unhealthy for the wall")

      // ---------- C1: lookalike pool ----------
      println("--- C1: lookalike pool data input ---")
      // Attacker fabricates deep fake reserves that price bond D healthy
      // and submits the advance pack the fake verdict justifies.
      val fake = fabLookalikePool(ctx, 1000000000000L, 1000000L)
      Kit.expectRejected("C1 crank against lookalike pool (fake NFT, attacker reserves)") {
        k.sign(buildCrank(ctx, dBox, Some(fake), advancePackOf(sD), preD, kAddr))
      }
      Kit.expectReduces("C1-twin same crank against the real pool (cure pack) reduces") {
        k.reduce(buildCrank(ctx, dBox, Some(pool), curePackOf(sD), preD, kAddr), 0).getCost
      }

      // ---------- C8: verdict wall on bond D ----------
      println("--- C8: covenant crank verdict wall (bond D, real box) ---")
      Kit.expectRejected("C8a keeper submits ADVANCE pack while pool prices unhealthy (branch choice)") {
        k.sign(buildCrank(ctx, dBox, Some(pool), advancePackOf(sD), preD, kAddr))
      }
      val wrongDeadline = curePackOf(sD); wrongDeadline(3) -= 1L
      Kit.expectRejected("C8b cure pack with deadline off by one") {
        k.sign(buildCrank(ctx, dBox, Some(pool), wrongDeadline, preD, kAddr))
      }
      val keepEscrow = curePackOf(sD); keepEscrow(5) = sD(5)
      Kit.expectRejected("C8c cure pack without the escrow decrement") {
        k.sign(buildCrank(ctx, dBox, Some(pool), keepEscrow, preD, kAddr))
      }
      Kit.expectRejected("C8d covenant crank with NO data input (correct cure pack)") {
        k.sign(buildCrank(ctx, dBox, None, curePackOf(sD), preD, kAddr))
      }
      Kit.expectRejected("C8e correct cure pack but value down two bounties") {
        k.sign(buildCrank(ctx, dBox, Some(pool), curePackOf(sD), preD, kAddr,
          valueDelta = 2 * CRANK_BOUNTY))
      }
      // (C8-twin is C1-twin above: honest cure pack reduces.)

      // C8f: healthy covenant bond — cure pack must be refused
      val schedH = Array[Long](0L, PERIOD, 0L, (h - 5).toLong, thrH, 2 * CRANK_BOUNTY)
      val bondH  = fabBond(ctx, MIN_ORDER_VALUE + 2 * CRANK_BOUNTY, schedH, h + 500, vaultBytes)
      Kit.expectRejected("C8f keeper submits CURE pack while pool prices healthy (branch choice)") {
        k.sign(buildCrank(ctx, bondH, Some(pool), curePackOf(schedH), h, kAddr))
      }
      Kit.expectReduces("C8f-twin healthy advance reduces") {
        k.reduce(buildCrank(ctx, bondH, Some(pool), advancePackOf(schedH), h, kAddr), 0).getCost
      }

      // C8g: crank on an in-cure bond (gate: nextCheck > 0)
      val schedCure = Array[Long](0L, PERIOD, 0L, -(h + 5).toLong, thrU, CRANK_BOUNTY)
      val bondCure  = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedCure, h + 500, vaultBytes)
      Kit.expectRejected("C8g crank of an in-cure bond (negative nextCheck must not satisfy the gate)") {
        k.sign(buildCrank(ctx, bondCure, Some(pool), advancePackOf(schedCure), h, kAddr))
      }
      // C8h: plain top-up during cure (schedule verbatim)
      Kit.expectScriptFalse("C8h plain top-up during cure (cure is the only collateral-add)") {
        b.sign(buildCure(ctx, bondCure, None, Kit.MIN_BOX_VALUE, schedCure.clone()))
      }
      // C8i: covenantOff bond cranked WITH a stray data input present
      val schedOff = Array[Long](0L, PERIOD, 0L, (h - 5).toLong, 0L, CRANK_BOUNTY)
      val bondOff  = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedOff, h + 500, vaultBytes, rsnAmt = 0L)
      Kit.expectReduces("C8i-twin covenantOff crank ignores a stray data input (compat)") {
        k.reduce(buildCrank(ctx, bondOff, Some(pool), advancePackOf(schedOff), h, kAddr), 0).getCost
      }

      // ---------- C4: at-threshold boundary ----------
      println("--- C4: ratio exactly at threshold (>= means healthy) ---")
      val L = P3.ergLegForHealthy(pool, RSN_AMT, REPAY, thrU)
      require(P3.healthy(pool, L, RSN_AMT, REPAY, thrU) &&
              !P3.healthy(pool, L - 1, RSN_AMT, REPAY, thrU),
        "boundary setup: L must be the exact healthy floor")
      val schedB  = Array[Long](0L, PERIOD, 0L, (h - 5).toLong, thrU, CRANK_BOUNTY)
      val atB     = fabBond(ctx, L + CRANK_BOUNTY, schedB, h + 500, vaultBytes)
      val belowB  = fabBond(ctx, L - 1 + CRANK_BOUNTY, schedB, h + 500, vaultBytes)
      Kit.expectReduces("C4-twin ergLeg == healthy floor L: ADVANCE reduces (at-threshold = healthy)") {
        k.reduce(buildCrank(ctx, atB, Some(pool), advancePackOf(schedB), h, kAddr), 0).getCost
      }
      Kit.expectRejected("C4a ergLeg == L: cure pack refused (healthy branch is forced)") {
        k.sign(buildCrank(ctx, atB, Some(pool), curePackOf(schedB), h, kAddr))
      }
      Kit.expectRejected("C4b ergLeg == L-1: advance pack refused (one nanoERG below the floor)") {
        k.sign(buildCrank(ctx, belowB, Some(pool), advancePackOf(schedB), h, kAddr))
      }
      Kit.expectReduces("C4b-twin ergLeg == L-1: CURE pack reduces") {
        k.reduce(buildCrank(ctx, belowB, Some(pool), curePackOf(schedB), h, kAddr), 0).getCost
      }

      // ---------- C5: cure wall (borrower-signed) ----------
      println("--- C5: cure wall ---")
      val cureNeed = P3.ergLegForHealthy(pool, RSN_AMT, REPAY, thrU) -
        (bondCure.getValue - schedCure(5)) + 1000000L
      Kit.expectScriptFalse("C5a cure whose successor still prices unhealthy (insufficient top-up)") {
        b.sign(buildCure(ctx, bondCure, Some(pool), Kit.MIN_BOX_VALUE, restorePackOf(schedCure)))
      }
      val offGrid = restorePackOf(schedCure); offGrid(3) += 1L
      Kit.expectScriptFalse("C5b cure restoring one block off the grid") {
        b.sign(buildCure(ctx, bondCure, Some(pool), cureNeed, offGrid))
      }
      val takeEscrow = restorePackOf(schedCure); takeEscrow(5) -= CRANK_BOUNTY
      Kit.expectScriptFalse("C5c cure decrementing escrow (no bounty is owed for a cure)") {
        b.sign(buildCure(ctx, bondCure, Some(pool), cureNeed, takeEscrow))
      }
      Kit.expectScriptFalse("C5d cure withholding the collateral token") {
        b.sign(buildCure(ctx, bondCure, Some(pool), cureNeed, restorePackOf(schedCure),
          tokensOverride = Some(Seq(new ErgoToken(FAKE_LOAN, 1L)))))
      }
      Kit.expectScriptFalse("C5e cure without the pool data input") {
        b.sign(buildCure(ctx, bondCure, None, cureNeed, restorePackOf(schedCure)))
      }
      Kit.expectReduces("C5-twin sufficient cure reduces") {
        b.reduce(buildCure(ctx, bondCure, Some(pool), cureNeed, restorePackOf(schedCure)), 0).getCost
      }
      // zero-delta cure: bond in cure that the pool already prices healthy
      val schedCureH = Array[Long](0L, PERIOD, 0L, -(h + 5).toLong, thrH, CRANK_BOUNTY)
      val bondCureH  = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedCureH, h + 500, vaultBytes)
      Kit.expectReduces("C5-twin zero-delta cure of a recovered bond reduces (exit from cure for a fee)") {
        b.reduce(buildCure(ctx, bondCureH, Some(pool), 0L, restorePackOf(schedCureH)), 0).getCost
      }

      // ---------- C6: acceleration during a live cure window ----------
      println("--- C6: acceleration during live cure window ---")
      val deadline = (h + 5).toLong  // bondCure's deadline is h+5, window live at h
      val liqToks  = bondCure.getTokens.asScala.toSeq
      Kit.expectRejected("C6 acceleration before the cure deadline") {
        k.sign(buildAccel(ctx, bondCure, Some(pool), vault,
          bondCure.getValue - LIQ_CARVEOUT, h,
          Some(bondCure.getId.getBytes), liqToks, kAddr))
      }
      Kit.expectReduces("C6-twin same acceleration at the deadline reduces") {
        k.reduce(buildAccel(ctx, bondCure, Some(pool), vault,
          bondCure.getValue - LIQ_CARVEOUT, deadline.toInt,
          Some(bondCure.getId.getBytes), liqToks, kAddr), 0).getCost
      }

      // ---------- C7: acceleration routing wall ----------
      println("--- C7: acceleration routing wall (blown grace, unhealthy) ---")
      val schedBlown = Array[Long](0L, PERIOD, 0L, -(h - 3).toLong, thrU, CRANK_BOUNTY)
      val blown      = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedBlown, h + 500, vaultBytes)
      val bToks      = blown.getTokens.asScala.toSeq
      val keeperTree = kAddr.toErgoContract.getErgoTree
      Kit.expectRejected("C7a acceleration routed to the keeper instead of the lender script") {
        k.sign(buildAccel(ctx, blown, Some(pool), keeperTree,
          blown.getValue - LIQ_CARVEOUT, h, Some(blown.getId.getBytes), bToks, kAddr))
      }
      Kit.expectRejected("C7b acceleration short of the value floor by 1 nanoERG") {
        k.sign(buildAccel(ctx, blown, Some(pool), vault,
          blown.getValue - LIQ_CARVEOUT - 1, h, Some(blown.getId.getBytes), bToks, kAddr))
      }
      Kit.expectRejected("C7c acceleration without the settlement receipt") {
        k.sign(buildAccel(ctx, blown, Some(pool), vault,
          blown.getValue - LIQ_CARVEOUT, h, None, bToks, kAddr))
      }
      Kit.expectRejected("C7d acceleration withholding the collateral token") {
        k.sign(buildAccel(ctx, blown, Some(pool), vault,
          blown.getValue - LIQ_CARVEOUT, h, Some(blown.getId.getBytes),
          Seq(new ErgoToken(FAKE_LOAN, 1L)), kAddr,
          keeperTokens = Seq(new ErgoToken(P3.RSN_ID, RSN_AMT))))
      }
      Kit.expectRejected("C7e acceleration of a RECOVERED bond (blown grace but healthy now)") {
        k.sign(buildAccel(ctx, bondCureH, Some(pool), vault,
          bondCureH.getValue - LIQ_CARVEOUT, h + 10,
          Some(bondCureH.getId.getBytes), bondCureH.getTokens.asScala.toSeq, kAddr))
      }
      Kit.expectRejected("C7f acceleration without the pool data input") {
        k.sign(buildAccel(ctx, blown, None, vault,
          blown.getValue - LIQ_CARVEOUT, h, Some(blown.getId.getBytes), bToks, kAddr))
      }
      // C7g: at HEIGHT >= maturity the SAME exit is a valid PLAIN
      // LIQUIDATION — the acceleration path's H < maturity term closes it
      // but the liquidation wall (same shape, same destination) takes
      // over with no gap. The height handover is a twin, not a negative;
      // the accelerate gate's own edges are pinned by C6 (before
      // deadline) and C10 (deadline past maturity).
      val mat = h + 500
      Kit.expectReduces("C7g-twin same exit at HEIGHT >= maturity reduces as plain liquidation (clean handover)") {
        k.reduce(buildAccel(ctx, blown, Some(pool), vault,
          blown.getValue - LIQ_CARVEOUT, mat + 1, Some(blown.getId.getBytes), bToks, kAddr), 0).getCost
      }
      Kit.expectReduces("C7-twin honest acceleration reduces") {
        k.reduce(buildAccel(ctx, blown, Some(pool), vault,
          blown.getValue - LIQ_CARVEOUT, h, Some(blown.getId.getBytes), bToks, kAddr), 0).getCost
      }

      // ---------- C10: empty acceleration window (deadline >= maturity) ----------
      println("--- C10: near-maturity failed checkpoint (empty acceleration window) ---")
      val matC10   = h + 100
      val schedC10 = Array[Long](0L, PERIOD, 0L, -(matC10 + 5).toLong, thrU, CRANK_BOUNTY)
      val c10      = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedC10, matC10, vaultBytes)
      val c10Toks  = c10.getTokens.asScala.toSeq
      Kit.expectRejected("C10a acceleration just before maturity (deadline is past it)") {
        k.sign(buildAccel(ctx, c10, Some(pool), vault,
          c10.getValue - LIQ_CARVEOUT, matC10 - 1, Some(c10.getId.getBytes), c10Toks, kAddr))
      }
      // Past maturity the same exit IS a valid plain liquidation (height
      // handover, as pinned by C7g-twin) — with or without a stray data
      // input, and with no health verdict: the backstop holds even when
      // the cure deadline was never reachable.
      Kit.expectReduces("C10-twin liquidation past maturity reduces (stray data input ignored)") {
        k.reduce(buildAccel(ctx, c10, Some(pool), vault,
          c10.getValue - LIQ_CARVEOUT, matC10 + 6, Some(c10.getId.getBytes), c10Toks, kAddr), 0).getCost
      }
      Kit.expectReduces("C10-twin plain liquidation at maturity reduces (backstop holds)") {
        // Liquidation needs no data input and no health verdict.
        k.reduce(buildAccel(ctx, c10, None, vault,
          c10.getValue - LIQ_CARVEOUT, matC10 + 6, Some(c10.getId.getBytes), c10Toks, kAddr), 0).getCost
      }

      // ---------- C11: deadline-boundary race (first confirmation wins) ----------
      println("--- C11: cure vs acceleration at the exact deadline ---")
      val schedC11 = Array[Long](0L, PERIOD, 0L, -h.toLong, thrU, CRANK_BOUNTY)
      val c11      = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedC11, h + 500, vaultBytes)
      val c11Need  = P3.ergLegForHealthy(pool, RSN_AMT, REPAY, thrU) -
        (c11.getValue - schedC11(5)) + 1000000L
      Kit.expectReduces("C11-twin acceleration at HEIGHT == deadline reduces") {
        k.reduce(buildAccel(ctx, c11, Some(pool), vault,
          c11.getValue - LIQ_CARVEOUT, h, Some(c11.getId.getBytes),
          c11.getTokens.asScala.toSeq, kAddr), 0).getCost
      }
      Kit.expectReduces("C11-twin cure at HEIGHT == deadline ALSO reduces (race, first confirm wins)") {
        b.reduce(buildCure(ctx, c11, Some(pool), c11Need, restorePackOf(schedC11)), 0).getCost
      }

      // ---------- C12: late cure catch-up ----------
      println("--- C12: late cure far past the deadline, then catch-up crank ---")
      val schedC12 = Array[Long](0L, PERIOD, 0L, -(h - 200).toLong, thrH, CRANK_BOUNTY)
      val c12      = fabBond(ctx, MIN_ORDER_VALUE + CRANK_BOUNTY, schedC12, h + 500, vaultBytes)
      val restored = restorePackOf(schedC12)
      Kit.expectReduces("C12-twin late zero-delta cure reduces (restore far below HEIGHT)") {
        b.reduce(buildCure(ctx, c12, Some(pool), 0L, restored), 0).getCost
      }
      val c12succ = fabBond(ctx, c12.getValue, restored, h + 500, vaultBytes, dummyTx = DUMMY_TX2)
      Kit.expectReduces("C12-twin immediate catch-up crank of the restored successor reduces") {
        k.reduce(buildCrank(ctx, c12succ, Some(pool), advancePackOf(restored), h, kAddr), 0).getCost
      }
      ()
    }

    // ---------- C2: stale pool box (freshness rule, proved on-chain) ----------
    println("--- C2: stale pool box as data input ---")
    Kit.exec { ctx =>
      val k     = Kit.noSecretProver(ctx)
      val kAddr = TestLib.keeper(ctx).getEip3Addresses.get(0)
      val dBox  = ctx.getBoxesById(bondD)(0)
      val sD    = P3.schedOf(dBox)
      val preD  = chkD + 2

      // Reconstruct the PREVIOUS (spent) pool box bit-exactly from the
      // extra index so its boxId matches the real stale box.
      val js = Kit.httpGet(s"/blockchain/box/byTokenId/$POOL_NFT?offset=1&limit=1")
      def num(name: String): Long =
        ("\"" + name + """"\s*:\s*(\d+)""").r.findFirstMatchIn(js)
          .getOrElse(sys.error(s"stale pool JSON: no numeric field $name")).group(1).toLong
      val staleId  = """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(js).get.group(1)
      val staleTx  = """"transactionId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(js).get.group(1)
      val staleIdx = num("index").toInt
      val staleVal = num("value")
      val staleCh  = num("creationHeight").toInt
      val toks = """"tokenId"\s*:\s*"([0-9a-f]{64})"\s*,\s*"amount"\s*:\s*(\d+)""".r
        .findAllMatchIn(js).map(m => (m.group(1), m.group(2).toLong)).toSeq
      require(toks.size == 3, s"stale pool JSON parse: ${toks.size} tokens")
      val stale = ctx.newTxBuilder().outBoxBuilder()
        .value(staleVal)
        .contract(new ErgoTreeContract(ctx.getBoxesById(
          // current pool box shares the identical tree
          P3.poolBox(ctx).getId.toString)(0).getErgoTree, NetworkType.MAINNET))
        .tokens(toks.map(t => new ErgoToken(ErgoId.create(t._1), t._2)): _*)
        .registers(ErgoValue.of(990))
        .creationHeight(staleCh)
        .build()
        .convertToInputWith(staleTx, staleIdx.toShort)
      require(stale.getId.toString == staleId,
        s"stale reconstruction mismatch: ${stale.getId} != $staleId")
      println(s"  reconstructed spent pool box $staleId bit-exactly")

      // (a) the SCRIPT accepts the stale box — freshness is not a script
      // property; it comes from consensus (data inputs must be unspent).
      val staleRx = stale.getValue
      val staleRy = toks.find(_._1 == COLLATERAL_TOKEN_ID).get._2
      val verdictStale = {
        val sn = BigInt(staleRx) * BigInt(RSN_AMT) * BigInt(990)
        val sd = BigInt(staleRy) * BigInt(1000) + BigInt(RSN_AMT) * BigInt(990)
        BigInt(dBox.getValue - sD(5)) * BigInt(10000) * sd + sn * BigInt(HAIRCUT_KEEP) >=
          BigInt(REPAY) * BigInt(sD(4)) * sd
      }
      val stalePack = if (verdictStale) advancePackOf(sD) else curePackOf(sD)
      Kit.expectReduces("C2a script-level: stale pool box SATISFIES the script (freshness is consensus-level)") {
        k.reduce(buildCrank(ctx, dBox, Some(stale), stalePack, preD, kAddr), 0).getCost
      }

      // (b) the NODE refuses the stale reference: data inputs must be in
      // the UTXO set. This is where the singleton => freshness rule lives.
      val unsigned = buildCrank(ctx, dBox, Some(stale), stalePack, preD, kAddr)
      val signed   = k.sign(unsigned)
      val res = scala.util.Try(ctx.sendTransaction(signed))
      res match {
        case scala.util.Success(_) =>
          sys.error("C2b: node ACCEPTED a spent pool box as data input — freshness rule broken!")
        case scala.util.Failure(e) =>
          println(s"  PASS C2b node rejects the spent pool box as data input (${e.getMessage.take(120)})")
      }
      require(Kit.unspentBoxIds(Address.fromErgoTree(dBox.getErgoTree, NetworkType.MAINNET).toString)
        .contains(bondD), "C2b: bond D must remain unspent after the rejected send")
      println("  bond D unspent after rejection — no state was harmed")
      ()
    }

    // ---------- cleanup: repay bond D ----------
    println("--- cleanup: repay bond D (recovers RSN + escrow residual) ---")
    TestLib.doExit(bondD, vault, asRepay = true, "C-wall cleanup repay (bond D)", TestLib.borrower)

    println("=== Phase 3 adversarial suite COMPLETE ===")
  }
}
