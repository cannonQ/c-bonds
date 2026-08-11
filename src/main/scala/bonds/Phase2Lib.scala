package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 2 shared flows: successor construction for the crank and top-up
  * paths. The honest plans are built from the input box exactly as the
  * contract's register mask expects; adversarial tests mutate one field at
  * a time so every negative fails on its specific check.
  */
object P2 {
  import Contracts.CRANK_BOUNTY

  /** Successor plan for the crank path. R4-R8 are direct register copies
    * from the input box (standing rule); R9 is the one register a crank
    * rebuilds. Tokens routed away from the successor land in the cranker
    * box so every mutation stays balance-valid (rejection must come from
    * the script, not the builder).
    */
  case class CrankPlan(
    contract: ErgoTreeContract,
    value: Long,
    tokens: Seq[ErgoToken],
    r4: ErgoValue[_], r5: ErgoValue[_], r6: ErgoValue[_], r7: ErgoValue[_], r8: ErgoValue[_],
    r9: Array[Long],
    extraTokensToCranker: Seq[ErgoToken] = Nil
  )

  def honestCrankPlan(ctx: BlockchainContext, bondBox: InputBox): CrankPlan = {
    val (_, bondContract) = Contracts.bond(ctx)
    val rs = bondBox.getRegisters
    val s  = TestLib.schedOf(bondBox)
    val r9 = s.clone()
    r9(3) = s(3) + s(1)             // nextCheckHeight += periodBlocks
    r9(5) = s(5) - CRANK_BOUNTY     // escrowBalance  -= CRANK_BOUNTY
    CrankPlan(bondContract, bondBox.getValue - CRANK_BOUNTY,
      bondBox.getTokens.asScala.toSeq,
      rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), r9)
  }

  /** Build a crank tx: the bond is the ONLY input; the freed bounty pays
    * the tx fee and the cranker's box (zero-capital keeper — pinned
    * decision). The cranker box absorbs whatever the plan does not put on
    * the successor.
    */
  def buildCrank(ctx: BlockchainContext, bondBox: InputBox, plan: CrankPlan,
                 payTo: Address, preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    var sb = tb.outBoxBuilder()
      .value(plan.value)
      .contract(plan.contract)
      .registers(plan.r4, plan.r5, plan.r6, plan.r7, plan.r8, ErgoValue.of(plan.r9))
    if (plan.tokens.nonEmpty) sb = sb.tokens(plan.tokens: _*)
    var kb = tb.outBoxBuilder()
      .value(bondBox.getValue - plan.value - Kit.TX_FEE)
      .contract(payTo.toErgoContract)
    if (plan.extraTokensToCranker.nonEmpty) kb = kb.tokens(plan.extraTokensToCranker: _*)
    tb.boxesToSpend(java.util.Arrays.asList(bondBox))
      .outputs(sb.build(), kb.build())
      .fee(Kit.TX_FEE)
      .sendChangeTo(payTo)
      .build()
  }

  /** Build a top-up: the borrower adds addValue ERG (negative models an
    * attempted withdrawal; the difference lands in their change). Registers
    * copied verbatim unless overridden; extraInputs carries token boxes for
    * token-growth top-ups.
    */
  def buildTopUp(ctx: BlockchainContext, bondBox: InputBox, addValue: Long,
                 funder: ErgoProver,
                 r9Override: Option[Array[Long]] = None,
                 tokensOverride: Option[Seq[ErgoToken]] = None,
                 extraInputs: Seq[InputBox] = Nil): UnsignedTransaction = {
    val fAddr = funder.getEip3Addresses.get(0)
    val rs    = bondBox.getRegisters
    val need  = math.max(addValue, 0L) + Kit.TX_FEE + Kit.MIN_BOX_VALUE
    val funds = Kit.selectBoxes(ctx, fAddr, need)
    val toks  = tokensOverride.getOrElse(bondBox.getTokens.asScala.toSeq)
    val tb    = ctx.newTxBuilder()
    var sb = tb.outBoxBuilder()
      .value(bondBox.getValue + addValue)
      .contract(new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET))
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4),
        r9Override.map(a => ErgoValue.of(a): ErgoValue[_]).getOrElse(rs.get(5)))
    if (toks.nonEmpty) sb = sb.tokens(toks: _*)
    tb.boxesToSpend((Seq(bondBox) ++ extraInputs ++ funds).asJava)
      .outputs(sb.build())
      .fee(Kit.TX_FEE)
      .sendChangeTo(fAddr)
      .build()
  }

  /** Confirmed-successor check against chain state: same script, loan
    * token at slot 0 x1, and the path's exact deltas (crank) or frozen
    * schedule + grown value (top-up).
    */
  def verifySuccessor(ctx: BlockchainContext, succId: String, oldBox: InputBox,
                      expectCrank: Boolean): Unit = {
    val s = ctx.getBoxesById(succId)(0)
    require(java.util.Arrays.equals(s.getErgoTree.bytes, oldBox.getErgoTree.bytes),
      s"successor $succId: script changed")
    val oldS = TestLib.schedOf(oldBox); val newS = TestLib.schedOf(s)
    if (expectCrank) {
      require(s.getValue == oldBox.getValue - CRANK_BOUNTY,
        s"successor $succId: value delta != one bounty")
      require(newS(3) == oldS(3) + oldS(1),
        s"successor $succId: nextCheckHeight not advanced by exactly one period")
      require(newS(5) == oldS(5) - CRANK_BOUNTY,
        s"successor $succId: escrow not decremented by exactly one bounty")
      require(newS(0) == oldS(0) && newS(1) == oldS(1) && newS(2) == oldS(2) && newS(4) == oldS(4),
        s"successor $succId: frozen schedule element changed")
    } else {
      require(s.getValue > oldBox.getValue, s"successor $succId: value not increased")
      require(newS.sameElements(oldS), s"successor $succId: schedule pack changed on top-up")
    }
    require(s.getTokens.get(0).getId.toString == oldBox.getTokens.get(0).getId.toString &&
            s.getTokens.get(0).getValue == 1L,
      s"successor $succId: loan token not at slot 0 x1")
    println(s"  successor verified on-chain: $succId")
  }

  /** Sign, submit, confirm an honest crank; returns the successor box id. */
  def doCrank(bondBoxId: String, jitLabel: String,
              proverOf: BlockchainContext => ErgoProver,
              payToOf: BlockchainContext => Address): String =
    Kit.exec { ctx =>
      val bondBox  = ctx.getBoxesById(bondBoxId)(0)
      val plan     = honestCrankPlan(ctx, bondBox)
      val p        = proverOf(ctx)
      val unsigned = buildCrank(ctx, bondBox, plan, payToOf(ctx))
      Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
      val signed = p.sign(unsigned)
      require(signed.getSignedInputs.size == 1, "crank must be self-funding (bond as sole input)")
      val succId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      verifySuccessor(ctx, succId, bondBox, expectCrank = true)
      succId
    }

  /** Sign, submit, confirm an honest ERG top-up; returns the successor id. */
  def doTopUp(bondBoxId: String, addValue: Long, jitLabel: String): String =
    Kit.exec { ctx =>
      val bondBox  = ctx.getBoxesById(bondBoxId)(0)
      val b        = TestLib.borrower(ctx)
      val unsigned = buildTopUp(ctx, bondBox, addValue, b)
      Jit.record(jitLabel, b.reduce(unsigned, 0).getCost.toLong)
      val signed = b.sign(unsigned)
      val succId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      verifySuccessor(ctx, succId, bondBox, expectCrank = false)
      succId
    }

  /** Post an order with an explicit (possibly non-conforming) template —
    * for origination negatives. Value = collateral + the template's escrow
    * field, whatever it claims.
    */
  def postOrderRaw(collateral: Long, template: Array[Long],
                   principal: Long = TestLib.PRINCIPAL,
                   repayment: Long = TestLib.REPAYMENT,
                   term: Int = TestLib.TERM_LONG): String =
    Kit.exec { ctx =>
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val (_, orderContract) = Contracts.order(ctx)
      val value = collateral + template(5)
      val funds = Kit.selectBoxes(ctx, bAddr, value + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val tb    = ctx.newTxBuilder()
      val ob = tb.outBoxBuilder()
        .value(value)
        .contract(orderContract)
        .registers(
          ErgoValue.of(bAddr.toErgoContract.getErgoTree.bytes),
          ErgoValue.of(principal),
          ErgoValue.of(repayment),
          ErgoValue.of(term),
          P4.packValue(Seq(Array.emptyByteArray)),
          ErgoValue.of(template)
        ).build()
      val unsigned = tb.boxesToSpend(funds.asJava).outputs(ob)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed  = b.sign(unsigned)
      val orderId = signed.getOutputsToSpend.get(0).getId.toString
      val txId    = Kit.sendSafe(ctx, signed, "post-order-raw")
      Kit.waitConfirmed(txId, "post-order-raw")
      println(s"  raw order box: $orderId (template ${template.mkString("[", ",", "]")})")
      orderId
    }

  /** Borrower cancels an order, recovering collateral + escrow — and any
    * token collateral the order carries (Phase 3: covenant orders hold
    * RSN). Tokens come back on their own min-value box, SEPARATE from
    * the ERG: a merged recovery output welds ERG onto the token box and
    * starves the token-free selector a few cycles later (the Phase 2
    * welding lesson, re-learned on the first C-wall run).
    */
  def cancelOrder(orderId: String, label: String): Unit =
    Kit.exec { ctx =>
      val b        = TestLib.borrower(ctx); val bAddr = b.getEip3Addresses.get(0)
      val orderBox = ctx.getBoxesById(orderId)(0)
      // Rev 3: cancel is authorized by borrower-script CO-SPEND (a
      // borrower-wallet input in the tx), not by signature alone.
      val coSpend  = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val tb       = ctx.newTxBuilder()
      val toks     = orderBox.getTokens.asScala.toSeq
      val ergOut   = orderBox.getValue - Kit.TX_FEE - (if (toks.nonEmpty) Kit.MIN_BOX_VALUE else 0L)
      var outs = Seq(tb.outBoxBuilder().value(ergOut).contract(bAddr.toErgoContract).build())
      if (toks.nonEmpty)
        outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
          .contract(bAddr.toErgoContract).tokens(toks: _*).build()
      val tx = tb.boxesToSpend((Seq(orderBox) ++ coSpend).asJava).outputs(outs: _*)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val txId = Kit.sendSafe(ctx, b.sign(tx), label)
      Kit.waitConfirmed(txId, label)
      println(s"  $label — order cancelled, funds recovered")
      ()
    }

  /** A match transaction built from an order with a mutable bond output —
    * for origination negatives (mirrors TestLib.matchOrder, never submits
    * unless honest).
    */
  def buildMatch(ctx: BlockchainContext, orderBox: InputBox, lenderScriptBytes: Array[Byte],
                 term: Int, bondSchedOverride: Option[Array[Long]] = None): UnsignedTransaction = {
    val l     = TestLib.lender(ctx)
    val lAddr = l.getEip3Addresses.get(0)
    val bAddr = TestLib.borrower(ctx).getEip3Addresses.get(0)
    val (_, bondContract) = Contracts.bond(ctx)
    val principal = orderBox.getRegisters.get(1).getValue.asInstanceOf[Long]
    val repayment = orderBox.getRegisters.get(2).getValue.asInstanceOf[Long]
    val maturity  = Kit.nodeHeight() + term
    val tmpl      = TestLib.schedOf(orderBox)
    val sched     = bondSchedOverride.getOrElse(Array[Long](
      tmpl(0), tmpl(1), tmpl(2), (maturity - term).toLong + tmpl(1), tmpl(4), tmpl(5)))
    // Bond R8 pack sized by the covenant shape (rev 3); rev 4 puts the
    // blake2b256 of the lender tree at element 0 and reveals the preimage
    // as ctx-ext var 0 on the order input. R5 likewise hashes the order's
    // borrower bytes instead of copying the register.
    val lenderHash = P4.h32(lenderScriptBytes)
    val r8Pack =
      if (tmpl(4) != 0L) Seq(lenderHash, ErgoId.create(Contracts.POOL_NFT).getBytes)
      else Seq(lenderHash)
    val funds = Kit.selectBoxes(ctx, lAddr, principal + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb    = ctx.newTxBuilder()
    val bondOut = tb.outBoxBuilder()
      .value(orderBox.getValue)
      .contract(bondContract)
      .tokens((new ErgoToken(orderBox.getId, 1L) +: orderBox.getTokens.asScala.toSeq): _*)
      .registers(
        ErgoValue.of(orderBox.getId.getBytes),
        ErgoValue.of(P4.h32(P4.borrowerBytesOf(orderBox, 0))),
        ErgoValue.of(repayment),
        ErgoValue.of(maturity),
        P4.packValue(r8Pack),
        ErgoValue.of(sched)
      ).build()
    val principalOut = tb.outBoxBuilder().value(principal).contract(bAddr.toErgoContract).build()
    tb.boxesToSpend((Seq(P4.orderWithMatchVars(orderBox, lenderScriptBytes)) ++ funds).asJava)
      .outputs(bondOut, principalOut)
      .fee(Kit.TX_FEE).sendChangeTo(lAddr).build()
  }
}
