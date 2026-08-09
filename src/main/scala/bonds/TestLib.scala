package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import sigmastate.Values.ErgoTree
import scala.collection.JavaConverters._

/** Shared flows for the Phase 1 suite. Dust sizes only. Steps that span
  * block waits run in separate execute blocks with boxes refetched by id,
  * so script evaluation always sees a fresh context.
  */
object TestLib {
  val PRINCIPAL: Long  = 10000000L // 0.010 ERG
  val REPAYMENT: Long  = 11000000L // 0.011 ERG
  val COLLATERAL: Long = 20000000L // 0.020 ERG
  val TERM_LONG: Int   = 720       // repay-path bonds: far maturity
  val TERM_SHORT: Int  = 8         // liquidation-path bonds: ~16 min

  def borrower(ctx: BlockchainContext): ErgoProver = Kit.prover(ctx, Env.die("BORROWER_MNEMONIC"))
  def lender(ctx: BlockchainContext): ErgoProver   = Kit.prover(ctx, Env.die("LENDER_MNEMONIC"))
  def keeper(ctx: BlockchainContext): ErgoProver   = Kit.prover(ctx, Env.die("KEEPER_MNEMONIC"))

  /** Abort early if any wallet's derived EIP-3 index-0 address differs from
    * its *_EXPECTED_ADDRESS in .env (when set).
    */
  def verifyWallets(ctx: BlockchainContext): Unit =
    Seq("BORROWER" -> borrower(ctx), "LENDER" -> lender(ctx), "KEEPER" -> keeper(ctx)).foreach {
      case (role, p) =>
        val a = p.getEip3Addresses.get(0)
        Env.get(s"${role}_EXPECTED_ADDRESS") match {
          case Some(expected) =>
            require(expected == a.toString, s"$role derived address $a does not match expected $expected")
            println(s"  $role wallet verified: $a")
          case None =>
            println(s"  $role: no expected address set")
        }
    }

  /** Canonical lender vault tree (owner = lender key). */
  def vaultTree(): ErgoTree = Kit.exec { ctx =>
    Contracts.vault(ctx, lender(ctx).getEip3Addresses.get(0).getPublicKey)._1
  }

  /** One-constant-byte variant of the vault tree, for A1. */
  def vaultVariantTree(): ErgoTree = Kit.exec { ctx =>
    Contracts.vault(ctx, lender(ctx).getEip3Addresses.get(0).getPublicKey, minOuts = 2)._1
  }

  def orderTree(): ErgoTree = Kit.exec { ctx => Contracts.order(ctx)._1 }

  def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  // ---------------- origination ----------------

  def boxesWithToken(ctx: BlockchainContext, address: Address, tokenId: String): Seq[InputBox] =
    ctx.getBoxesById(Kit.unspentBoxIds(address.toString): _*).toSeq
      .filter(_.getTokens.asScala.exists(_.getId.toString == tokenId))

  /** R9 schedule pack of a box as Array[Long]. */
  def schedOf(box: InputBox): Array[Long] =
    box.getRegisters.get(5).getValue.asInstanceOf[sigma.Coll[Long]].toArray

  /** Post an order from the borrower wallet; returns the confirmed order
    * box id. Phase 2: `period` defines the checkpoint grid; the default
    * (period == term) is the degenerate bullet — no interior checkpoints,
    * zero escrow — which keeps every Phase 1 call site semantically
    * unchanged. The order box carries collateral + escrow in its value.
    */
  def postOrder(collateral: Long = COLLATERAL, principal: Long = PRINCIPAL,
                repayment: Long = REPAYMENT, term: Int = TERM_LONG,
                collTokens: Seq[ErgoToken] = Nil, period: Long = 0L,
                thresholdBps: Long = 0L): String =
    Kit.exec { ctx =>
      val b     = borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val (_, orderContract) = Contracts.order(ctx)
      val p      = if (period > 0L) period else term.toLong
      val escrow = Contracts.escrowFor(term, p)

      val tokenBoxes =
        if (collTokens.isEmpty) Nil
        else boxesWithToken(ctx, bAddr, collTokens.head.getId.toString)
      val tokenValue = tokenBoxes.map(_.getValue.toLong).sum
      val ergNeed    = collateral + escrow + Kit.TX_FEE + Kit.MIN_BOX_VALUE - tokenValue
      val ergBoxes   = if (ergNeed > 0) Kit.selectBoxes(ctx, bAddr, ergNeed) else Nil
      val inputs     = tokenBoxes ++ ergBoxes

      val tb = ctx.newTxBuilder()
      var ob = tb.outBoxBuilder()
        .value(collateral + escrow)
        .contract(orderContract)
        .registers(
          ErgoValue.of(bAddr.toErgoContract.getErgoTree.bytes), // R4 borrower script bytes (rev 3)
          ErgoValue.of(principal),                      // R5 principal
          ErgoValue.of(repayment),                      // R6 repayment
          ErgoValue.of(term),                           // R7 term (blocks)
          P4.packValue(Seq(Array.emptyByteArray)),      // R8 [cardPin] (empty pin, card-less)
          ErgoValue.of(Array[Long](0L, p, 0L, 0L, thresholdBps, escrow)) // R9 template
        )
      if (collTokens.nonEmpty) ob = ob.tokens(collTokens: _*)

      // Token remainder rides on its OWN min-value box so appkit's change
      // stays token-free — otherwise every order post welds the leftover
      // RSN onto the ERG change and starves the token-free selector.
      val inTokens = inputs.flatMap(_.getTokens.asScala)
        .groupBy(_.getId.toString).values
        .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
      val outMap   = collTokens.groupBy(_.getId.toString).mapValues(_.map(_.getValue.toLong).sum)
      val leftover = inTokens.flatMap { t =>
        val rest = t.getValue.toLong - outMap.getOrElse(t.getId.toString, 0L)
        if (rest > 0) Some(new ErgoToken(t.getId, rest)) else None
      }
      val outs =
        if (leftover.isEmpty) Seq(ob.build())
        else Seq(ob.build(), tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
          .contract(bAddr.toErgoContract).tokens(leftover: _*).build())

      val unsigned = tb.boxesToSpend(inputs.asJava).outputs(outs: _*)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed  = b.sign(unsigned)
      val orderId = signed.getOutputsToSpend.get(0).getId.toString
      val txId    = Kit.sendSafe(ctx, signed, "post-order")
      Kit.waitConfirmed(txId, "post-order")
      println(s"  order box: $orderId (period $p, escrow $escrow)")
      orderId
    }

  /** Lender matches an order: mints the loan token (id == order box id),
    * creates the bond, pays principal to the borrower.
    * Returns (bondBoxId, maturity).
    */
  def matchOrder(orderBoxId: String, lenderScriptTree: ErgoTree, term: Int): (String, Int) =
    Kit.exec { ctx =>
      val l     = lender(ctx)
      val lAddr = l.getEip3Addresses.get(0)
      val bAddr = borrower(ctx).getEip3Addresses.get(0)
      val (_, bondContract) = Contracts.bond(ctx)

      val orderBox  = ctx.getBoxesById(orderBoxId)(0)
      val principal = orderBox.getRegisters.get(1).getValue.asInstanceOf[Long]
      val repayment = orderBox.getRegisters.get(2).getValue.asInstanceOf[Long]
      val maturity  = Kit.nodeHeight() + term

      val funds = Kit.selectBoxes(ctx, lAddr, principal + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
      val tb    = ctx.newTxBuilder()

      val loanToken = new ErgoToken(orderBox.getId, 1L)
      val collToks  = orderBox.getTokens.asScala.toSeq

      // Bond schedule from the order template: grid anchored to
      // maturity - term, first checkpoint one period in (schedOk mirror).
      val tmpl = schedOf(orderBox)
      val bondSched = Array[Long](
        tmpl(0), tmpl(1), tmpl(2),
        (maturity - term).toLong + tmpl(1),
        tmpl(4), tmpl(5))

      // Bond R8 pack sized by the covenant shape (rev 3): covenant bonds
      // carry the resolved poolNFT at index 1, covenant-off just the lender.
      val r8Pack =
        if (tmpl(4) != 0L) Seq(lenderScriptTree.bytes, ErgoId.create(Contracts.POOL_NFT).getBytes)
        else Seq(lenderScriptTree.bytes)

      val bondOut = tb.outBoxBuilder()
        .value(orderBox.getValue)
        .contract(bondContract)
        .tokens((loanToken +: collToks): _*)
        .registers(
          ErgoValue.of(orderBox.getId.getBytes),        // R4 order box id
          orderBox.getRegisters.get(0),                 // R5 borrower (direct register copy)
          ErgoValue.of(repayment),                      // R6 repayment
          ErgoValue.of(maturity),                       // R7 maturity height
          P4.packValue(r8Pack),                         // R8 suffix pack [lender, poolNFT?]
          ErgoValue.of(bondSched)                       // R9 pack
        ).build()

      val principalOut = tb.outBoxBuilder().value(principal).contract(bAddr.toErgoContract).build()

      // Order box MUST be INPUTS(0): both the contract and the token-mint rule key on it.
      val unsigned = tb.boxesToSpend((Seq(orderBox) ++ funds).asJava)
        .outputs(bondOut, principalOut)
        .fee(Kit.TX_FEE).sendChangeTo(lAddr).build()

      Jit.record("match(order-spend + loan-token mint)", l.reduce(unsigned, 0).getCost.toLong)
      val signed = l.sign(unsigned)
      val bondId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, "match-order")
      Kit.waitConfirmed(txId, "match-order")
      println(s"  bond box: $bondId  maturity: $maturity")
      (bondId, maturity)
    }

  /** Order + match in one call. */
  def cycle(term: Int, lenderScriptTree: ErgoTree, collTokens: Seq[ErgoToken] = Nil,
            period: Long = 0L, thresholdBps: Long = 0L, collateral: Long = COLLATERAL,
            repayment: Long = REPAYMENT): (String, Int) = {
    val orderId = postOrder(collateral = collateral, repayment = repayment, term = term,
      collTokens = collTokens, period = period, thresholdBps = thresholdBps)
    matchOrder(orderId, lenderScriptTree, term)
  }

  // ---------------- exits ----------------

  case class ExitPlan(
    exitTree: ErgoTree,             // OUTPUTS(0) destination script
    exitValue: Long,                // OUTPUTS(0) value
    receiptR4: Option[Array[Byte]], // None = registers omitted entirely
    tokens: Seq[ErgoToken]          // tokens on OUTPUTS(0)
  )

  def repayPlan(bondBox: InputBox, tree: ErgoTree): ExitPlan = {
    val repayment = bondBox.getRegisters.get(2).getValue.asInstanceOf[Long]
    ExitPlan(tree, repayment, Some(bondBox.getId.getBytes),
      Seq(new ErgoToken(bondBox.getTokens.get(0).getId, 1L)))
  }

  def liquidationPlan(bondBox: InputBox, tree: ErgoTree): ExitPlan =
    ExitPlan(tree, bondBox.getValue - Contracts.LIQ_CARVEOUT, Some(bondBox.getId.getBytes),
      bondBox.getTokens.asScala.toSeq)

  /** Build an exit spend of the bond box. The funder covers any shortfall
    * and receives all change (including withheld tokens, which is exactly
    * what an attacker would do).
    */
  def buildExit(ctx: BlockchainContext, bondBox: InputBox, plan: ExitPlan,
                funder: ErgoProver, preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val fAddr     = funder.getEip3Addresses.get(0)
    val needExtra = plan.exitValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE - bondBox.getValue
    val funds     = Kit.selectBoxes(ctx, fAddr, math.max(needExtra, Kit.TX_FEE + Kit.MIN_BOX_VALUE))

    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    var eb = tb.outBoxBuilder()
      .value(plan.exitValue)
      .contract(new ErgoTreeContract(plan.exitTree, NetworkType.MAINNET))
    if (plan.tokens.nonEmpty) eb = eb.tokens(plan.tokens: _*)
    plan.receiptR4.foreach { r4 => eb = eb.registers(ErgoValue.of(r4)) }

    tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
      .outputs(eb.build())
      .fee(Kit.TX_FEE)
      .sendChangeTo(fAddr)
      .build()
  }

  /** Sign, submit, confirm an honest exit; verify the receipt on-chain. */
  def doExit(bondBoxId: String, tree: ErgoTree, asRepay: Boolean, jitLabel: String,
             proverOf: BlockchainContext => ErgoProver): String =
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondBoxId)(0)
      val plan    = if (asRepay) repayPlan(bondBox, tree) else liquidationPlan(bondBox, tree)
      val p       = proverOf(ctx)
      val unsigned = buildExit(ctx, bondBox, plan, p)
      Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
      val signed = p.sign(unsigned)
      val exitId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      verifyReceipt(exitId, bondBoxId, bondBox.getTokens.get(0).getId.toString, tree)
      exitId
    }

  /** Receipt check against the confirmed chain state: destination script,
    * R4 bond reference, and the loan token all present in the exit box.
    */
  def verifyReceipt(exitBoxId: String, bondId: String, loanTokenId: String, expectedTree: ErgoTree): Unit = {
    val s = Kit.httpGet(s"/blockchain/box/byId/$exitBoxId")
    require(s.contains(hex(expectedTree.bytes)), s"receipt $exitBoxId: wrong destination script")
    require(s.contains("0e20" + bondId), s"receipt $exitBoxId: R4 bond reference missing")
    require(s.contains(loanTokenId), s"receipt $exitBoxId: loan token missing")
    println(s"  receipt verified on-chain: $exitBoxId (R4 -> $bondId, loan token present)")
  }

  // ---------------- token collateral (A5) ----------------

  /** Mint a plain test token to the borrower wallet; returns token id. */
  def mintTestToken(amount: Long): String =
    Kit.exec { ctx =>
      val b     = borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val funds = Kit.selectBoxes(ctx, bAddr, 2 * Kit.MIN_BOX_VALUE + Kit.TX_FEE)
      val tb    = ctx.newTxBuilder()
      val tokenId = funds.head.getId
      // .tokens + no EIP-4 registers on purpose: mintToken() would overwrite
      // R4-R6 (Etcha SKILL.md pitfall); the protocol only requires
      // tokenId == INPUTS(0).id.
      val out = tb.outBoxBuilder()
        .value(2 * Kit.MIN_BOX_VALUE)
        .contract(bAddr.toErgoContract)
        .tokens(new ErgoToken(tokenId, amount))
        .build()
      val unsigned = tb.boxesToSpend(funds.asJava).outputs(out)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed = b.sign(unsigned)
      val txId   = Kit.sendSafe(ctx, signed, "mint-test-token")
      Kit.waitConfirmed(txId, "mint-test-token")
      println(s"  test token: ${tokenId.toString} x$amount")
      tokenId.toString
    }
}
