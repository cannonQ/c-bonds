package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import scala.collection.JavaConverters._

/** Phase 3 compile-gate JitCost probe (kickoff §6.3): reduce every
  * covenant-branch shape BEFORE the suite exists or a nanoERG moves.
  * Bond boxes are fabricated locally (convertToInputWith — never
  * submitted); the pool data input is the REAL pinned pool box read from
  * the node, so the BigInt sim runs against live reserves. The covenant
  * branch is the first real cost risk of the project — measure early
  * against the 500K/input budget (standing rule).
  */
object Phase3Gate {
  import Contracts._

  /** Exact Scala mirror of the contract's division-free BigInt health
    * inequality — used to pick thresholds and assert each probe exercises
    * the branch it claims to.
    */
  def healthy(ergLeg: Long, amt: Long, rX: Long, rY: Long, feeNum: Int,
              repayment: Long, thresholdBps: Long): Boolean = {
    val sn = BigInt(rX) * BigInt(amt) * BigInt(feeNum)
    val sd = BigInt(rY) * BigInt(1000) + BigInt(amt) * BigInt(feeNum)
    BigInt(ergLeg) * BigInt(10000) * sd + sn * BigInt(HAIRCUT_KEEP) >=
      BigInt(repayment) * BigInt(thresholdBps) * sd
  }

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val h = ctx.getHeight
    val (_, bondContract) = Contracts.bond(ctx)
    val borrowerP = TestLib.borrower(ctx)
    val bAddr     = borrowerP.getEip3Addresses.get(0)
    val lAddr     = TestLib.lender(ctx).getEip3Addresses.get(0)
    val keeperP   = Kit.noSecretProver(ctx)
    val lenderTreeBytes = lAddr.toErgoContract.getErgoTree.bytes

    // The real pinned pool box, straight from the node's extra index.
    val poolJson  = Kit.httpGet(s"/blockchain/box/unspent/byTokenId/$POOL_NFT?offset=0&limit=1")
    val poolBoxId = """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(poolJson)
      .map(_.group(1)).getOrElse(sys.error("pinned pool box not found"))
    val pool   = ctx.getBoxesById(poolBoxId)(0)
    val rX     = pool.getValue
    val rY     = pool.getTokens.get(2).getValue
    val feeNum = pool.getRegisters.get(0).getValue.asInstanceOf[Int]
    println(s"pool $poolBoxId  rX=$rX  rY=$rY  feeNum=$feeNum  height=$h")

    // Fabricated covenant bond: 0.005 ERG collateral leg + 0.010 escrow,
    // 700 raw RSN token leg (~0.021 ERG simmed), debt 0.015 ERG.
    // Ratio ~ 176%: threshold 15000 -> healthy, 20000 -> unhealthy.
    val bondValue = 15000000L
    val escrow    = 10000000L
    val ergLeg    = bondValue - escrow
    val repayment = 15000000L
    val amtRSN    = 700L
    val period    = 20L
    val maturity  = h + 500
    val fakeId    = ErgoId.create("11" * 32)
    val rsnId     = ErgoId.create(COLLATERAL_TOKEN_ID)
    val dummyTx   = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"

    require(healthy(ergLeg, amtRSN, rX, rY, feeNum, repayment, 15000L),
      "probe setup: threshold 15000 must price healthy against live reserves")
    require(!healthy(ergLeg, amtRSN, rX, rY, feeNum, repayment, 20000L),
      "probe setup: threshold 20000 must price unhealthy against live reserves")

    def mkBond(sched: Array[Long]): InputBox = {
      // Rev 3: R5 borrower script bytes; R8 pack sized by the covenant shape.
      val r8Pack =
        if (sched(4) != 0L) Seq(lenderTreeBytes, ErgoId.create(POOL_NFT).getBytes)
        else Seq(lenderTreeBytes)
      ctx.newTxBuilder().outBoxBuilder()
        .value(bondValue)
        .contract(bondContract)
        .tokens(new ErgoToken(fakeId, 1L), new ErgoToken(rsnId, amtRSN))
        .registers(
          ErgoValue.of(fakeId.getBytes),
          ErgoValue.of(bAddr.toErgoContract.getErgoTree.bytes),
          ErgoValue.of(repayment),
          ErgoValue.of(maturity),
          P4.packValue(r8Pack),
          ErgoValue.of(sched))
        .build()
        .convertToInputWith(dummyTx, 0)
    }

    def crankTx(bond: InputBox, r9succ: Array[Long]): UnsignedTransaction = {
      val tb = ctx.newTxBuilder()
      val rs = bond.getRegisters
      val succ = tb.outBoxBuilder()
        .value(bond.getValue - CRANK_BOUNTY)
        .contract(bondContract)
        .tokens(bond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9succ))
        .build()
      val kb = tb.outBoxBuilder()
        .value(CRANK_BOUNTY - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(bond))
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(succ, kb)
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
    }

    // 1. Covenant crank, healthy verdict -> advance pack.
    val schedHealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 15000L, escrow)
    val advPack      = Array[Long](0L, period, 0L, (h - 5).toLong + period, 15000L, escrow - CRANK_BOUNTY)
    Jit.record("P3 gate: crank covenant HEALTHY (local reduce, live pool)",
      keeperP.reduce(crankTx(mkBond(schedHealthy), advPack), 0).getCost.toLong)

    // 2. Covenant crank, unhealthy verdict -> cure pack (negative sched(3)).
    val schedUnhealthy = Array[Long](0L, period, 0L, (h - 5).toLong, 20000L, escrow)
    val curePack       = Array[Long](0L, period, 0L, -((h - 5).toLong + GRACE_BLOCKS), 20000L, escrow - CRANK_BOUNTY)
    Jit.record("P3 gate: crank covenant UNHEALTHY->cure (local reduce, live pool)",
      keeperP.reduce(crankTx(mkBond(schedUnhealthy), curePack), 0).getCost.toLong)

    // 3. Cure: bond in cure state (future deadline, but cure has no
    // deadline), borrower tops up 0.005 to clear threshold 20000, pack
    // returns to the grid: (deadline - GRACE) + period.
    val deadlineF  = (h + 5).toLong
    val schedCure  = Array[Long](0L, period, 0L, -deadlineF, 20000L, escrow - CRANK_BOUNTY)
    val addValue   = 5000000L
    require(healthy(ergLeg + addValue, amtRSN, rX, rY, feeNum, repayment, 20000L),
      "probe setup: +0.005 ERG must cure threshold 20000")
    val cureBond   = mkBond(schedCure)
    val restorePack = Array[Long](0L, period, 0L, (deadlineF - GRACE_BLOCKS) + period, 20000L, escrow - CRANK_BOUNTY)
    val cureTx = {
      val tb    = ctx.newTxBuilder()
      val rs    = cureBond.getRegisters
      val funds = Kit.selectBoxes(ctx, bAddr, addValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val succ = tb.outBoxBuilder()
        .value(cureBond.getValue + addValue)
        .contract(bondContract)
        .tokens(cureBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(restorePack))
        .build()
      tb.boxesToSpend((Seq(cureBond) ++ funds).asJava)
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(succ)
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
    }
    Jit.record("P3 gate: cure top-up (local reduce, live pool)",
      borrowerP.reduce(cureTx, 0).getCost.toLong)

    // 4. Acceleration: blown deadline, still unhealthy now. Liquidation
    // shape to the lender script with receipt + all tokens.
    val schedBlown = Array[Long](0L, period, 0L, -((h - 3).toLong), 20000L, escrow - CRANK_BOUNTY)
    val accelBond  = mkBond(schedBlown)
    val accelTx = {
      val tb = ctx.newTxBuilder()
      val exit = tb.outBoxBuilder()
        .value(accelBond.getValue - LIQ_CARVEOUT)
        .contract(lAddr.toErgoContract)
        .tokens(accelBond.getTokens.asScala.toSeq: _*)
        .registers(ErgoValue.of(accelBond.getId.getBytes))
        .build()
      val kb = tb.outBoxBuilder()
        .value(LIQ_CARVEOUT - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(accelBond))
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(exit, kb)
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
    }
    Jit.record("P3 gate: acceleration (local reduce, live pool)",
      keeperP.reduce(accelTx, 0).getCost.toLong)

    // ---- eager-evaluation probes (NO data input) ----
    // The first deployed Phase 3 tree crashed every data-input-less
    // spend: compiler CSE hoisted a dataInputs(0) shared across guarded
    // vals into an eager top-level ValDef. These probes reduce the three
    // data-input-less shapes and FAIL THE GATE on any recurrence.
    val repayBond = mkBond(schedHealthy)
    val repayTx = {
      val tb    = ctx.newTxBuilder()
      val funds = Kit.selectBoxes(ctx, bAddr, repayment + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val exit = tb.outBoxBuilder()
        .value(repayment)
        .contract(lAddr.toErgoContract)
        .tokens(new ErgoToken(fakeId, 1L))
        .registers(ErgoValue.of(repayBond.getId.getBytes))
        .build()
      tb.boxesToSpend((Seq(repayBond) ++ funds).asJava)
        .outputs(exit).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P3 gate: repay with NO data input (eager-eval probe)",
      borrowerP.reduce(repayTx, 0).getCost.toLong)

    val schedOff = Array[Long](0L, period, 0L, (h - 5).toLong, 0L, escrow)
    val offBond  = mkBond(schedOff)
    val offPack  = Array[Long](0L, period, 0L, (h - 5).toLong + period, 0L, escrow - CRANK_BOUNTY)
    val offCrank = {
      val tb = ctx.newTxBuilder()
      val rs = offBond.getRegisters
      val succ = tb.outBoxBuilder()
        .value(offBond.getValue - CRANK_BOUNTY)
        .contract(bondContract)
        .tokens(offBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(offPack))
        .build()
      val kb = tb.outBoxBuilder().value(CRANK_BOUNTY - Kit.TX_FEE)
        .contract(bAddr.toErgoContract).build()
      tb.boxesToSpend(java.util.Arrays.asList(offBond))
        .outputs(succ, kb).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P3 gate: covenantOff crank with NO data input (eager-eval probe)",
      keeperP.reduce(offCrank, 0).getCost.toLong)

    val topUpBond = mkBond(schedHealthy)
    val topUpTx = {
      val tb    = ctx.newTxBuilder()
      val rs    = topUpBond.getRegisters
      val funds = Kit.selectBoxes(ctx, bAddr, 2 * Kit.MIN_BOX_VALUE + Kit.TX_FEE)
      val succ = tb.outBoxBuilder()
        .value(topUpBond.getValue + Kit.MIN_BOX_VALUE)
        .contract(bondContract)
        .tokens(topUpBond.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4),
          ErgoValue.of(schedHealthy))
        .build()
      tb.boxesToSpend((Seq(topUpBond) ++ funds).asJava)
        .outputs(succ).fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
    }
    Jit.record("P3 gate: top-up with NO data input (eager-eval probe)",
      borrowerP.reduce(topUpTx, 0).getCost.toLong)

    println("Phase 3 gate probes complete (incl. no-data-input eager-eval probes).")
    ()
  }
}
