package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 3 shared flows: the pinned pool as a data input, the covenant
  * crank's verdict-branch successor, cure, acceleration, and the one-time
  * RSN acquisition swap. Every verdict the harness expects is computed
  * from the SAME live pool box the transaction carries as its data input,
  * so harness and contract can never disagree about reserves.
  */
object P3 {
  import Contracts._

  val RSN_ID: ErgoId = ErgoId.create(COLLATERAL_TOKEN_ID)

  /** The one unspent pool box (NFT singleton), fresh from the node. */
  def poolBox(ctx: BlockchainContext): InputBox = {
    val s  = Kit.httpGet(s"/blockchain/box/unspent/byTokenId/$POOL_NFT?offset=0&limit=1")
    val id = """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(s)
      .map(_.group(1)).getOrElse(sys.error("pinned pool box not found on node"))
    ctx.getBoxesById(id)(0)
  }

  def reserves(pool: InputBox): (Long, Long, Int) =
    (pool.getValue, pool.getTokens.get(2).getValue,
     pool.getRegisters.get(0).getValue.asInstanceOf[Int])

  /** Exact mirror of the contract's division-free BigInt health check. */
  def healthy(pool: InputBox, ergLeg: Long, amt: Long,
              repayment: Long, thresholdBps: Long): Boolean = {
    val (rX, rY, feeNum) = reserves(pool)
    val sn = BigInt(rX) * BigInt(amt) * BigInt(feeNum)
    val sd = BigInt(rY) * BigInt(1000) + BigInt(amt) * BigInt(feeNum)
    BigInt(ergLeg) * BigInt(10000) * sd + sn * BigInt(HAIRCUT_KEEP) >=
      BigInt(repayment) * BigInt(thresholdBps) * sd
  }

  /** Collateral ratio in bps (floor; display + threshold selection only —
    * verdict decisions always go through healthy()). */
  def ratioBps(pool: InputBox, ergLeg: Long, amt: Long, repayment: Long): Long = {
    val (rX, rY, feeNum) = reserves(pool)
    val sn = BigInt(rX) * BigInt(amt) * BigInt(feeNum)
    val sd = BigInt(rY) * BigInt(1000) + BigInt(amt) * BigInt(feeNum)
    ((BigInt(ergLeg) * BigInt(10000) * sd + sn * BigInt(HAIRCUT_KEEP)) /
      (BigInt(repayment) * sd)).toLong
  }

  /** Minimum ERG leg that prices healthy at the given threshold (ceil),
    * from the same inequality solved for ergLeg. */
  def ergLegForHealthy(pool: InputBox, amt: Long, repayment: Long, thresholdBps: Long): Long = {
    val (rX, rY, feeNum) = reserves(pool)
    val sn  = BigInt(rX) * BigInt(amt) * BigInt(feeNum)
    val sd  = BigInt(rY) * BigInt(1000) + BigInt(amt) * BigInt(feeNum)
    val num = BigInt(repayment) * BigInt(thresholdBps) * sd - sn * BigInt(HAIRCUT_KEEP)
    val den = BigInt(10000) * sd
    if (num <= 0) 0L else ((num + den - 1) / den).toLong
  }

  def schedOf(box: InputBox): Array[Long] = TestLib.schedOf(box)

  /** R6 repayment of a bond box (register list index 2). */
  def repaymentOf(box: InputBox): Long =
    box.getRegisters.get(2).getValue.asInstanceOf[Long]

  /** RSN balance (raw units) of an address. */
  def rsnBalance(ctx: BlockchainContext, addr: Address): Long =
    ctx.getBoxesById(Kit.unspentBoxIds(addr.toString): _*).toSeq
      .flatMap(_.getTokens.asScala)
      .filter(_.getId.toString == COLLATERAL_TOKEN_ID)
      .map(_.getValue.toLong).sum

  // ---------------- one-time RSN acquisition swap ----------------

  /** Direct AMM swap against the pool box: ergIn from the borrower buys
    * RSN at the pool's own R4 fee. The successor pool preserves script,
    * R4, NFT and LP verbatim; output amount is the executor formula
    * floor minus a 2-unit safety margin (strictly inside the invariant).
    * The pool's script executes as an input here, so a mistake rejects at
    * local signing, never on-chain. Retries refetch the pool (race with
    * other pool spenders).
    */
  def acquireRsn(ergIn: Long, attempts: Int = 3): Long = {
    var got = -1L
    var tries = 0
    while (got < 0 && tries < attempts) {
      tries += 1
      try {
        got = Kit.exec { ctx =>
          val b     = TestLib.borrower(ctx)
          val bAddr = b.getEip3Addresses.get(0)
          val pool  = poolBox(ctx)
          val (rX, rY, feeNum) = reserves(pool)
          val out = ((BigInt(rY) * BigInt(ergIn) * BigInt(feeNum)) /
                     (BigInt(rX) * BigInt(1000) + BigInt(ergIn) * BigInt(feeNum))).toLong - 2L
          require(out >= 100L, s"swap of $ergIn nanoERG yields only $out raw RSN")

          val funds = Kit.selectBoxes(ctx, bAddr, ergIn + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
          val tb    = ctx.newTxBuilder()
          val poolOut = tb.outBoxBuilder()
            .value(pool.getValue + ergIn)
            .contract(new ErgoTreeContract(pool.getErgoTree, NetworkType.MAINNET))
            .tokens(
              new ErgoToken(pool.getTokens.get(0).getId, 1L),
              new ErgoToken(pool.getTokens.get(1).getId, pool.getTokens.get(1).getValue),
              new ErgoToken(pool.getTokens.get(2).getId, rY - out))
            .registers(pool.getRegisters.get(0))
            .build()
          val reward = tb.outBoxBuilder()
            .value(Kit.MIN_BOX_VALUE)
            .contract(bAddr.toErgoContract)
            .tokens(new ErgoToken(RSN_ID, out))
            .build()
          val unsigned = tb.boxesToSpend((Seq(pool) ++ funds).asJava)
            .outputs(poolOut, reward)
            .fee(Kit.TX_FEE)
            .sendChangeTo(bAddr)
            .build()
          val signed = b.sign(unsigned)
          val txId   = Kit.sendSafe(ctx, signed, s"rsn-swap($ergIn nanoERG -> $out RSN)")
          Kit.waitConfirmed(txId, "rsn-swap")
          println(s"  acquired $out raw RSN for ${ergIn / 1e9} ERG (pool fee $feeNum)")
          out
        }
      } catch {
        case e: Exception if tries < attempts =>
          println(s"  rsn-swap attempt $tries failed (${e.getMessage}); refetching pool and retrying")
          Thread.sleep(15000)
      }
    }
    require(got >= 0, s"rsn-swap failed after $attempts attempts")
    got
  }

  // ---------------- covenant crank ----------------

  /** Crank a covenant bond: the verdict (healthy -> advance, unhealthy ->
    * cure state) is computed from the same pool box supplied as the data
    * input; expectHealthy asserts the test exercises the branch it claims.
    * Bond is the sole signed input (zero-capital keeper), pool rides as
    * dataInputs(0). Returns the successor box id.
    */
  def doCovenantCrank(bondBoxId: String, expectHealthy: Boolean, jitLabel: String,
                      proverOf: BlockchainContext => ErgoProver = TestLib.keeper,
                      attempts: Int = 3): String = {
    var succ: String = null
    var tries = 0
    while (succ == null && tries < attempts) {
      tries += 1
      try {
        succ = Kit.exec { ctx =>
          val bondBox = ctx.getBoxesById(bondBoxId)(0)
          val pool    = poolBox(ctx)
          val s       = schedOf(bondBox)
          val ergLeg  = bondBox.getValue - s(5)
          val amt     = bondBox.getTokens.get(1).getValue
          val isHealthy = healthy(pool, ergLeg, amt, repaymentOf(bondBox), s(4))
          require(isHealthy == expectHealthy,
            s"$jitLabel: pool verdict $isHealthy but test expects $expectHealthy — reserves moved?")

          val r9 = s.clone()
          r9(3) = if (isHealthy) s(3) + s(1) else -(s(3) + GRACE_BLOCKS)
          r9(5) = s(5) - CRANK_BOUNTY

          val p     = proverOf(ctx)
          val payTo = p.getEip3Addresses.get(0)
          val rs    = bondBox.getRegisters
          val tb    = ctx.newTxBuilder()
          val sb = tb.outBoxBuilder()
            .value(bondBox.getValue - CRANK_BOUNTY)
            .contract(new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET))
            .tokens(bondBox.getTokens.asScala.toSeq: _*)
            .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9))
            .build()
          val kb = tb.outBoxBuilder()
            .value(CRANK_BOUNTY - Kit.TX_FEE)
            .contract(payTo.toErgoContract)
            .build()
          val unsigned = tb.boxesToSpend(java.util.Arrays.asList(bondBox))
            .withDataInputs(java.util.Arrays.asList(pool))
            .outputs(sb, kb)
            .fee(Kit.TX_FEE)
            .sendChangeTo(payTo)
            .build()
          Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
          val signed = p.sign(unsigned)
          require(signed.getSignedInputs.size == 1, "crank must be self-funding (bond sole input)")
          val succId = signed.getOutputsToSpend.get(0).getId.toString
          val txId   = Kit.sendSafe(ctx, signed, jitLabel)
          Kit.waitConfirmed(txId, jitLabel)

          val conf = ctx.getBoxesById(succId)(0)
          val ns   = schedOf(conf)
          require(conf.getValue == bondBox.getValue - CRANK_BOUNTY, "value != -1 bounty")
          require(ns(5) == s(5) - CRANK_BOUNTY, "escrow != -1 bounty")
          require(ns(3) == r9(3), s"successor nextCheck ${ns(3)} != expected ${r9(3)}")
          require(ns(0) == s(0) && ns(1) == s(1) && ns(2) == s(2) && ns(4) == s(4),
            "frozen schedule element changed")
          println(s"  successor verified on-chain: $succId (nextCheck ${ns(3)}, " +
            (if (isHealthy) "advanced" else s"IN CURE, deadline ${-ns(3)}") + ")")
          succId
        }
      } catch {
        case e: Exception if tries < attempts &&
            Kit.causeChain(e).toLowerCase.contains("data") =>
          println(s"  $jitLabel attempt $tries hit a data-input race; retrying")
          Thread.sleep(15000)
      }
    }
    require(succ != null, s"$jitLabel failed after $attempts attempts")
    succ
  }

  // ---------------- cure ----------------

  /** Borrower cures an in-cure bond by adding addValue ERG (0 allowed if
    * the pool recovered). Successor returns to the grid: nextCheck =
    * (deadline - GRACE) + period, escrow verbatim. Pool data input proves
    * post-cure health. Returns the successor box id.
    */
  def doCure(bondBoxId: String, addValue: Long, jitLabel: String): String =
    Kit.exec { ctx =>
      val b       = TestLib.borrower(ctx)
      val bAddr   = b.getEip3Addresses.get(0)
      val bondBox = ctx.getBoxesById(bondBoxId)(0)
      val pool    = poolBox(ctx)
      val s       = schedOf(bondBox)
      require(s(3) < 0L, s"$jitLabel: bond not in cure state (nextCheck ${s(3)})")
      val newValue = bondBox.getValue + addValue
      require(healthy(pool, newValue - s(5), bondBox.getTokens.get(1).getValue,
        repaymentOf(bondBox), s(4)),
        s"$jitLabel: planned cure (+$addValue) does not price healthy")

      val r9 = s.clone()
      r9(3) = (-s(3)) - GRACE_BLOCKS + s(1)

      val funds = Kit.selectBoxes(ctx, bAddr, math.max(addValue, 0L) + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val rs    = bondBox.getRegisters
      val tb    = ctx.newTxBuilder()
      val sb = tb.outBoxBuilder()
        .value(newValue)
        .contract(new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET))
        .tokens(bondBox.getTokens.asScala.toSeq: _*)
        .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(r9))
        .build()
      val unsigned = tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(sb)
        .fee(Kit.TX_FEE)
        .sendChangeTo(bAddr)
        .build()
      Jit.record(jitLabel, b.reduce(unsigned, 0).getCost.toLong)
      val signed = b.sign(unsigned)
      val succId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)

      val conf = ctx.getBoxesById(succId)(0)
      val ns   = schedOf(conf)
      require(ns(3) == r9(3) && ns(3) > 0L, s"cure successor not back on grid: ${ns(3)}")
      require(ns(5) == s(5), "cure must not touch escrow")
      require(conf.getValue == newValue, "cure value mismatch")
      println(s"  cured on-chain: $succId (back on grid, nextCheck ${ns(3)}, escrow ${ns(5)})")
      succId
    }

  // ---------------- acceleration ----------------

  /** Signatureless acceleration of a grace-blown, still-unhealthy bond:
    * liquidation shape to the R8 script with the pool data input. The
    * carve-out funds fee + keeper box (zero-capital). Returns exit box id.
    */
  def doAccelerate(bondBoxId: String, jitLabel: String,
                   proverOf: BlockchainContext => ErgoProver = TestLib.keeper): String =
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondBoxId)(0)
      val pool    = poolBox(ctx)
      val s       = schedOf(bondBox)
      require(s(3) < 0L, s"$jitLabel: bond not in cure state")
      require(!healthy(pool, bondBox.getValue - s(5), bondBox.getTokens.get(1).getValue,
        repaymentOf(bondBox), s(4)),
        s"$jitLabel: bond prices healthy — acceleration must not fire")

      val lenderTree = P4.lenderTreeBytesOf(bondBox)   // rev 3: R8 pack element 0
      val p     = proverOf(ctx)
      val payTo = p.getEip3Addresses.get(0)
      val tb    = ctx.newTxBuilder()
      val exit = tb.outBoxBuilder()
        .value(bondBox.getValue - LIQ_CARVEOUT)
        .contract(new ErgoTreeContract(
          sigmastate.serialization.ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(lenderTree),
          NetworkType.MAINNET))
        .tokens(bondBox.getTokens.asScala.toSeq: _*)
        .registers(ErgoValue.of(bondBox.getId.getBytes))
        .build()
      val kb = tb.outBoxBuilder()
        .value(LIQ_CARVEOUT - Kit.TX_FEE)
        .contract(payTo.toErgoContract)
        .build()
      val unsigned = tb.boxesToSpend(java.util.Arrays.asList(bondBox))
        .withDataInputs(java.util.Arrays.asList(pool))
        .outputs(exit, kb)
        .fee(Kit.TX_FEE)
        .sendChangeTo(payTo)
        .build()
      Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
      val signed = p.sign(unsigned)
      require(signed.getSignedInputs.size == 1, "acceleration must be self-funding (bond sole input)")
      val exitId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      println(s"  accelerated on-chain: exit $exitId (100% minus carve-out to lender script)")
      exitId
    }
}
