package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import scala.collection.JavaConverters._

/** Phase 4 / rev-3 shared flows: the card (terms box) lifecycle, the
  * rev-3 register layouts (R5 borrower script bytes, R8 Coll[Coll[Byte]]
  * suffix pack, R9 extended Long pack), the coupon and missed-payment
  * paths, and the card-resolution mirror. Layout per REV3-LAYOUT.md
  * (signed off 2026-08-07); gates per PHASE4-DECISIONS.md.
  *
  * Every resolved value the harness expects is computed by the SAME
  * sentinel-fallback rules the order contract applies, so harness and
  * contract can never disagree about a card's meaning.
  */
object P4 {
  import Contracts._

  /** Test installment (decision 7): >= MIN_COUPON and deliberately !=
    * CRANK_BOUNTY so a swapped-leg bug cannot hide behind equal values. */
  val INSTALLMENT: Long = 6000000L

  // ---------------- rev-3 register encoding ----------------

  /** Coll[Coll[Byte]] register value from byte arrays (R8 packs, card R9). */
  def packValue(items: Seq[Array[Byte]]): ErgoValue[_] = {
    val inner = items.map(a => ErgoValue.of(a).getValue).toArray
    ErgoValue.of(inner, ErgoType.collType(ErgoType.byteType()))
  }

  /** Decode a Coll[Coll[Byte]] register (0-indexed from R4). */
  def packOf(box: InputBox, regIdx: Int): Seq[Array[Byte]] = {
    val coll = box.getRegisters.get(regIdx).getValue
      .asInstanceOf[sigma.Coll[sigma.Coll[Byte]]]
    coll.toArray.map(_.toArray).toSeq
  }

  /** Bond R8 suffix pack [lenderScript, poolNFT?, liqHookHash?, attesterHash?]. */
  def bondR8Of(box: InputBox): Seq[Array[Byte]] = packOf(box, 4)

  /** Bond R5 / order R4 borrower script bytes. */
  def borrowerBytesOf(box: InputBox, regIdx: Int): Array[Byte] =
    box.getRegisters.get(regIdx).getValue.asInstanceOf[sigma.Coll[Byte]].toArray

  def treeFromBytes(bytes: Array[Byte]): sigmastate.Values.ErgoTree =
    sigmastate.serialization.ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(bytes)

  def contractFromBytes(bytes: Array[Byte]): ErgoTreeContract =
    new ErgoTreeContract(treeFromBytes(bytes), NetworkType.MAINNET)

  /** Lender destination of a rev-3 bond: R8 pack element 0. */
  def lenderTreeBytesOf(box: InputBox): Array[Byte] = bondR8Of(box).head

  // ---------------- R9 extended-pack accessors (sentinel fallback) ----------------

  def bountyOf(s: Array[Long]): Long  = if (s.length > 6) s(6) else CRANK_BOUNTY
  def graceOf(s: Array[Long]): Long   = if (s.length > 7) s(7) else GRACE_BLOCKS
  def carveOf(s: Array[Long]): Long   = if (s.length > 8) s(8) else LIQ_CARVEOUT
  def haircutOf(s: Array[Long]): Long = if (s.length > 9) s(9) else HAIRCUT_KEEP
  def aTypeOf(s: Array[Long]): Long   = if (s.length > 10) s(10) else 0L

  /** Health mirror with card-resolved haircut (P3.healthy hardcodes the
    * compiled keep factor). */
  def healthyV3(pool: InputBox, ergLeg: Long, amt: Long, repayment: Long,
                thresholdBps: Long, haircutKeep: Long): Boolean = {
    val (rX, rY, feeNum) = P3.reserves(pool)
    val sn = BigInt(rX) * BigInt(amt) * BigInt(feeNum)
    val sd = BigInt(rY) * BigInt(1000) + BigInt(amt) * BigInt(feeNum)
    BigInt(ergLeg) * BigInt(10000) * sd + sn * BigInt(haircutKeep) >=
      BigInt(repayment) * BigInt(thresholdBps) * sd
  }

  // ---------------- card anatomy (REV3-LAYOUT.md L2-L4) ----------------

  /** Card R7 indices. 0-3 map positionally onto bond R9 indices 6-9. */
  object C7 {
    val BOUNTY = 0; val GRACE = 1; val CARVE = 2; val HAIRCUT = 3
    val THR_MIN = 4; val THR_MAX = 5
    val MIN_ORDER = 6; val MIN_PERIOD_I = 7; val MIN_COUPON_I = 8
    val ATYPE = 9; val FLAGS = 10
    val SIZE = 11
  }

  /** Resolved numerics from a card R7 pack, exactly as the order contract
    * resolves them (sentinel 0 = compiled default; floors max()ed;
    * thresholds clamped to the protocol outer bound). */
  case class Resolved(bounty: Long, grace: Long, carve: Long, haircut: Long,
                      thrMin: Long, thrMax: Long, minOrder: Long,
                      minPeriod: Long, minCoupon: Long, aType: Long) {
    /** Bond R9 suffix a carded match writes (indices 6-9). */
    def suffix: Array[Long] = Array(bounty, grace, carve, haircut)
  }

  val COMPILED_DEFAULTS: Resolved = Resolved(
    CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP,
    10000L, 30000L, MIN_ORDER_VALUE, MIN_PERIOD, MIN_COUPON, 0L)

  def resolve(r7: Array[Long]): Resolved = Resolved(
    bounty  = if (r7(C7.BOUNTY) == 0L) CRANK_BOUNTY else r7(C7.BOUNTY),
    grace   = if (r7(C7.GRACE) == 0L) GRACE_BLOCKS else r7(C7.GRACE),
    carve   = if (r7(C7.CARVE) == 0L) LIQ_CARVEOUT else r7(C7.CARVE),
    haircut = if (r7(C7.HAIRCUT) == 0L) HAIRCUT_KEEP else r7(C7.HAIRCUT),
    thrMin  = math.max(10000L, r7(C7.THR_MIN)),
    thrMax  = if (r7(C7.THR_MAX) == 0L) 30000L else math.min(30000L, r7(C7.THR_MAX)),
    minOrder  = math.max(MIN_ORDER_VALUE, r7(C7.MIN_ORDER)),
    minPeriod = math.max(MIN_PERIOD, r7(C7.MIN_PERIOD_I)),
    minCoupon = math.max(MIN_COUPON, r7(C7.MIN_COUPON_I)),
    aType     = r7(C7.ATYPE))

  /** Card byte fields with sentinel fallback (empty = compiled). */
  def resolvePoolNft(r8: Seq[Array[Byte]]): Array[Byte] =
    if (r8.head.isEmpty) ErgoId.create(POOL_NFT).getBytes else r8.head
  def resolveCollateralId(r8: Seq[Array[Byte]]): Array[Byte] =
    if (r8(1).isEmpty) ErgoId.create(COLLATERAL_TOKEN_ID).getBytes else r8(1)

  /** Escrow a conforming order must carry under a resolved bounty. */
  def escrowForWith(bounty: Long, term: Int, period: Long): Long =
    bounty * ((term.toLong - 1L) / period)

  /** Interior checkpoint count. */
  def kOf(term: Int, period: Long): Long = (term.toLong - 1L) / period

  // The three test cards (REV3-KICKOFF §2).
  /** Card A "T2-as-card": full covenant card at today's compiled values,
    * every field EXPLICIT — proves the copy path reproduces rev-2 behavior. */
  val CARD_A_R7: Array[Long] = Array(
    CRANK_BOUNTY, GRACE_BLOCKS, LIQ_CARVEOUT, HAIRCUT_KEEP,
    10000L, 30000L, MIN_ORDER_VALUE, MIN_PERIOD, MIN_COUPON, 0L, 0L)
  /** Card B sentinel card: every optional zeroed -> compiled defaults (E3). */
  val CARD_B_R7: Array[Long] = Array.fill(C7.SIZE)(0L)
  /** Card C bounds card: tightened threshold range + raised order floor (E4). */
  val CARD_C_THR_MIN   = 12000L
  val CARD_C_THR_MAX   = 20000L
  val CARD_C_MIN_ORDER = 12000000L
  val CARD_C_R7: Array[Long] = Array(
    0L, 0L, 0L, 0L, CARD_C_THR_MIN, CARD_C_THR_MAX, CARD_C_MIN_ORDER, 0L, 0L, 0L, 0L)

  def explicitCardR8: Seq[Array[Byte]] =
    Seq(ErgoId.create(POOL_NFT).getBytes, ErgoId.create(COLLATERAL_TOKEN_ID).getBytes)
  def sentinelCardR8: Seq[Array[Byte]] =
    Seq(Array.emptyByteArray, Array.emptyByteArray)

  def cardR9(publisher: Array[Byte], version: String = "1",
             predecessor: Array[Byte] = Array.emptyByteArray): Seq[Array[Byte]] =
    Seq(publisher, version.getBytes("UTF-8"), predecessor)

  // ---------------- card lifecycle ----------------

  /** Mint a card on-chain from the borrower wallet: min-value box at the
    * TermsBox script carrying its own freshly minted NFT (id == first
    * input id) with EIP-4 R4-R6. Returns (cardBoxId, cardNftId). */
  def mintCard(name: String, description: String, r7: Array[Long],
               r8Fields: Seq[Array[Byte]], label: String): (String, String) =
    Kit.exec { ctx =>
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val (_, cardContract) = Contracts.termsBox(ctx)
      val funds  = Kit.selectBoxes(ctx, bAddr, Kit.MIN_BOX_VALUE + Kit.TX_FEE)
      val nftId  = funds.head.getId
      val tb     = ctx.newTxBuilder()
      val out = tb.outBoxBuilder()
        .value(Kit.MIN_BOX_VALUE)
        .contract(cardContract)
        .tokens(new ErgoToken(nftId, 1L))
        .registers(
          ErgoValue.of(name.getBytes("UTF-8")),
          ErgoValue.of(description.getBytes("UTF-8")),
          ErgoValue.of("0".getBytes("UTF-8")),
          ErgoValue.of(r7),
          packValue(r8Fields),
          packValue(cardR9(bAddr.toErgoContract.getErgoTree.bytes)))
        .build()
      val unsigned = tb.boxesToSpend(funds.asJava).outputs(out)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed = b.sign(unsigned)
      val cardId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, label)
      Kit.waitConfirmed(txId, label)
      println(s"  card box: $cardId  NFT: $nftId")
      (cardId, nftId.toString)
    }

  /** Fabricated card box (local reduce tests only, never submitted). */
  def fabCard(ctx: BlockchainContext, nftId: ErgoId, r7: Array[Long],
              r8Fields: Seq[Array[Byte]], value: Long = Kit.MIN_BOX_VALUE): InputBox = {
    val (_, cardContract) = Contracts.termsBox(ctx)
    ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(cardContract)
      .tokens(new ErgoToken(nftId, 1L))
      .registers(
        ErgoValue.of("fab".getBytes("UTF-8")),
        ErgoValue.of("fab".getBytes("UTF-8")),
        ErgoValue.of("0".getBytes("UTF-8")),
        ErgoValue.of(r7),
        packValue(r8Fields),
        packValue(cardR9(Array.emptyByteArray)))
      .build()
      .convertToInputWith("f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809", 1)
  }

  /** Refuel plan: every mutation the E1 wall needs is one override away
    * from the byte-identical successor. */
  case class RefuelPlan(
    value: Long,
    tokens: Seq[ErgoToken],
    r4: ErgoValue[_], r5: ErgoValue[_], r6: ErgoValue[_],
    r7: ErgoValue[_], r8: ErgoValue[_], r9: ErgoValue[_],
    contractOverride: Option[ErgoTreeContract] = None)

  def honestRefuelPlan(card: InputBox, grow: Long): RefuelPlan = {
    val rs = card.getRegisters
    RefuelPlan(card.getValue + grow, card.getTokens.asScala.toSeq,
      rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), rs.get(5))
  }

  /** Build a refuel spend of the card (successor at OUTPUTS(0), funder
    * covers the growth + fee). */
  def buildRefuel(ctx: BlockchainContext, card: InputBox, plan: RefuelPlan,
                  funder: ErgoProver): UnsignedTransaction = {
    val fAddr = funder.getEip3Addresses.get(0)
    val need  = math.max(plan.value - card.getValue, 0L) + Kit.TX_FEE + Kit.MIN_BOX_VALUE
    val funds = Kit.selectBoxes(ctx, fAddr, need)
    val tb    = ctx.newTxBuilder()
    var sb = tb.outBoxBuilder()
      .value(plan.value)
      .contract(plan.contractOverride.getOrElse(
        new ErgoTreeContract(card.getErgoTree, NetworkType.MAINNET)))
      .registers(plan.r4, plan.r5, plan.r6, plan.r7, plan.r8, plan.r9)
    if (plan.tokens.nonEmpty) sb = sb.tokens(plan.tokens: _*)
    tb.boxesToSpend((Seq(card) ++ funds).asJava)
      .outputs(sb.build())
      .fee(Kit.TX_FEE)
      .sendChangeTo(fAddr)
      .build()
  }

  // ---------------- rev-3 order lifecycle ----------------

  /** Post a rev-3 order. R4 = borrower SCRIPT BYTES, R8 = [cardPin] or
    * [cardPin, liqHookHash] (pin empty for card-less), R9 template with
    * Phase 4 fields (installment, payments = K+1 when installment > 0).
    * Escrow is sized with the card-resolved bounty. */
  def postOrderV3(collateral: Long = TestLib.COLLATERAL,
                  principal: Long = TestLib.PRINCIPAL,
                  repayment: Long = TestLib.REPAYMENT,
                  term: Int = TestLib.TERM_LONG,
                  collTokens: Seq[ErgoToken] = Nil,
                  period: Long = 0L,
                  thresholdBps: Long = 0L,
                  installment: Long = 0L,
                  cardPin: Array[Byte] = Array.emptyByteArray,
                  hookHash: Option[Array[Byte]] = None,
                  bounty: Long = CRANK_BOUNTY,
                  borrowerBytesOverride: Option[Array[Byte]] = None,
                  templateOverride: Option[Array[Long]] = None,
                  label: String = "post-order-v3"): String =
    Kit.exec { ctx =>
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val (_, orderContract) = Contracts.order(ctx)
      val p        = if (period > 0L) period else term.toLong
      val escrow   = escrowForWith(bounty, term, p)
      val payments = if (installment > 0L) kOf(term, p) + 1L else 0L
      val tmpl     = templateOverride.getOrElse(
        Array[Long](installment, p, payments, 0L, thresholdBps, escrow))
      val bBytes   = borrowerBytesOverride.getOrElse(bAddr.toErgoContract.getErgoTree.bytes)
      val r8Pack   = hookHash match {
        case Some(h) => Seq(cardPin, h)
        case None    => Seq(cardPin)
      }

      val tokenBoxes =
        if (collTokens.isEmpty) Nil
        else TestLib.boxesWithToken(ctx, bAddr, collTokens.head.getId.toString)
      val tokenValue = tokenBoxes.map(_.getValue.toLong).sum
      val ergNeed    = collateral + tmpl(5) + Kit.TX_FEE + Kit.MIN_BOX_VALUE - tokenValue
      val ergBoxes   = if (ergNeed > 0) Kit.selectBoxes(ctx, bAddr, ergNeed) else Nil
      val inputs     = tokenBoxes ++ ergBoxes

      val tb = ctx.newTxBuilder()
      var ob = tb.outBoxBuilder()
        .value(collateral + tmpl(5))
        .contract(orderContract)
        .registers(
          ErgoValue.of(bBytes),          // R4 borrower script bytes (rev 3)
          ErgoValue.of(principal),       // R5 principal
          ErgoValue.of(repayment),       // R6 repayment
          ErgoValue.of(term),            // R7 term
          packValue(r8Pack),             // R8 [cardPin, liqHookHash?]
          ErgoValue.of(tmpl))            // R9 template
      if (collTokens.nonEmpty) ob = ob.tokens(collTokens: _*)

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
      val txId    = Kit.sendSafe(ctx, signed, label)
      Kit.waitConfirmed(txId, label)
      println(s"  order box: $orderId (period $p, escrow ${tmpl(5)}, installment $installment, " +
        s"pin ${if (cardPin.isEmpty) "none" else "set"})")
      orderId
    }

  /** Rev-3 match: bond R5 = borrower bytes copied from order R4, R8 =
    * suffix pack sized by covenant/hook shape, R9 = 6 (card-less) or 10
    * (carded, resolved values) elements. Card rides as dataInputs(0) when
    * pinned. Every field is override-able for the E-wall. */
  def buildMatchV3(ctx: BlockchainContext, orderBox: InputBox,
                   lenderScriptBytes: Array[Byte], term: Int,
                   card: Option[InputBox],
                   dropDataInput: Boolean = false,
                   bondR8Override: Option[Seq[Array[Byte]]] = None,
                   bondSchedOverride: Option[Array[Long]] = None,
                   bondR5Override: Option[Array[Byte]] = None,
                   preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val l     = TestLib.lender(ctx)
    val lAddr = l.getEip3Addresses.get(0)
    val principal = orderBox.getRegisters.get(1).getValue.asInstanceOf[Long]
    val repayment = orderBox.getRegisters.get(2).getValue.asInstanceOf[Long]
    val (_, bondContract) = Contracts.bond(ctx)
    val maturity = preHeaderHeight.getOrElse(Kit.nodeHeight()) + term
    val tmpl     = TestLib.schedOf(orderBox)
    val orderR8  = packOf(orderBox, 4)
    val hook     = if (orderR8.size >= 2) Some(orderR8(1)) else None

    val res = card.map(c => resolve(c.getRegisters.get(3).getValue
      .asInstanceOf[sigma.Coll[Long]].toArray)).getOrElse(COMPILED_DEFAULTS)
    val poolNft = card.map(c => resolvePoolNft(packOf(c, 4)))
      .getOrElse(ErgoId.create(POOL_NFT).getBytes)

    val base = Array[Long](tmpl(0), tmpl(1), tmpl(2),
      (maturity - term).toLong + tmpl(1), tmpl(4), tmpl(5))
    val sched = bondSchedOverride.getOrElse(
      if (card.isDefined) base ++ res.suffix else base)

    val covenantOn = tmpl(4) != 0L
    val r8Pack = bondR8Override.getOrElse(
      (covenantOn, hook) match {
        case (true, Some(h)) => Seq(lenderScriptBytes, poolNft, h)
        case (true, None)    => Seq(lenderScriptBytes, poolNft)
        case (false, _)      => Seq(lenderScriptBytes)
      })

    val bBytes = bondR5Override.getOrElse(borrowerBytesOf(orderBox, 0))
    val funds  = Kit.selectBoxes(ctx, lAddr, principal + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val bondOut = tb.outBoxBuilder()
      .value(orderBox.getValue)
      .contract(bondContract)
      .tokens((new ErgoToken(orderBox.getId, 1L) +: orderBox.getTokens.asScala.toSeq): _*)
      .registers(
        ErgoValue.of(orderBox.getId.getBytes), // R4 order box id
        ErgoValue.of(bBytes),                  // R5 borrower script bytes
        ErgoValue.of(repayment),               // R6
        ErgoValue.of(maturity),                // R7
        packValue(r8Pack),                     // R8 suffix pack
        ErgoValue.of(sched)                    // R9 (6 or 10 elements)
      ).build()
    val principalOut = tb.outBoxBuilder().value(principal)
      .contract(contractFromBytes(borrowerBytesOf(orderBox, 0))).build()
    var builder = tb.boxesToSpend((Seq(orderBox) ++ funds).asJava)
    if (card.isDefined && !dropDataInput)
      builder = builder.withDataInputs(java.util.Arrays.asList(card.get))
    builder.outputs(bondOut, principalOut)
      .fee(Kit.TX_FEE).sendChangeTo(lAddr).build()
  }

  /** Sign, submit, confirm an honest rev-3 match. Returns (bondId, maturity). */
  def doMatchV3(orderBoxId: String, lenderScriptBytes: Array[Byte], term: Int,
                cardBoxId: Option[String], jitLabel: String): (String, Int) =
    Kit.exec { ctx =>
      val l        = TestLib.lender(ctx)
      val orderBox = ctx.getBoxesById(orderBoxId)(0)
      val card     = cardBoxId.map(id => ctx.getBoxesById(id)(0))
      val maturity = Kit.nodeHeight() + term
      val unsigned = buildMatchV3(ctx, orderBox, lenderScriptBytes, term, card)
      Jit.record(jitLabel, l.reduce(unsigned, 0).getCost.toLong)
      val signed = l.sign(unsigned)
      val bondId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      println(s"  bond box: $bondId  maturity: $maturity")
      (bondId, maturity)
    }

  /** Rev-3 cancel: borrower-script CO-SPEND (the borrower's fee boxes are
    * the authorization) + tokens on their own min-value box. */
  def cancelOrderV3(orderId: String, label: String): Unit =
    Kit.exec { ctx =>
      val b        = TestLib.borrower(ctx); val bAddr = b.getEip3Addresses.get(0)
      val orderBox = ctx.getBoxesById(orderId)(0)
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

  // ---------------- rev-3 bond fabrication (local walls / gate) ----------------

  val DUMMY_TX  = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val FAKE_LOAN = "44" * 32

  /** Fabricated rev-3 bond box: R5 borrower bytes, R8 pack, extended R9.
    * Defaults model a conforming covenant-off bullet; every field is
    * override-able so the walls can fabricate any shape. */
  def fabBondV3(ctx: BlockchainContext,
                sched: Array[Long],
                r8Pack: Seq[Array[Byte]],
                borrowerBytes: Array[Byte],
                value: Long,
                repayment: Long,
                maturity: Int,
                tokens: Seq[ErgoToken] = Nil,
                loanTokenId: String = FAKE_LOAN): InputBox = {
    val (_, bondContract) = Contracts.bond(ctx)
    val fakeId = ErgoId.create(loanTokenId)
    val toks   = new ErgoToken(fakeId, 1L) +: tokens
    ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(bondContract)
      .tokens(toks: _*)
      .registers(
        ErgoValue.of(fakeId.getBytes),
        ErgoValue.of(borrowerBytes),
        ErgoValue.of(repayment),
        ErgoValue.of(maturity),
        packValue(r8Pack),
        ErgoValue.of(sched))
      .build()
      .convertToInputWith(DUMMY_TX, 0)
  }

  // ---------------- coupon path (decision 1) ----------------

  /** Successor packs for a coupon at the current checkpoint, suffix
    * preserved whole (frozen across successors by whole-pack equality). */
  def couponAdvancePack(s: Array[Long]): Array[Long] = {
    val r9 = s.clone()
    r9(2) = s(2) - 1L
    r9(3) = s(3) + s(1)
    r9(5) = s(5) - bountyOf(s)
    r9
  }
  def couponCurePack(s: Array[Long]): Array[Long] = {
    val r9 = couponAdvancePack(s)
    r9(3) = -(s(3) + graceOf(s))
    r9
  }

  /** Coupon plan: OUTPUTS(0) successor + OUTPUTS(1) installment to the
    * lender script with the R4 = SELF.id receipt (NO loan token — that
    * stays on the successor). Every leg override-able for D1/D2/D6/D10/
    * D11/D14. */
  case class CouponPlan(
    succValue: Long,
    succTokens: Seq[ErgoToken],
    succR9: Array[Long],
    instValue: Long,
    instTree: Array[Byte],
    instR4: Option[Array[Byte]],
    extraTokensToPayer: Seq[ErgoToken] = Nil,
    // D11 mask wall: one register mutated at a time on the successor.
    succR4Override: Option[ErgoValue[_]] = None,
    succR5Override: Option[ErgoValue[_]] = None,
    succR6Override: Option[ErgoValue[_]] = None,
    succR7Override: Option[ErgoValue[_]] = None,
    succR8Override: Option[ErgoValue[_]] = None,
    succContractOverride: Option[ErgoTreeContract] = None)

  def honestCouponPlan(bondBox: InputBox, healthyBranch: Boolean): CouponPlan = {
    val s = TestLib.schedOf(bondBox)
    CouponPlan(
      succValue  = bondBox.getValue - bountyOf(s),
      succTokens = bondBox.getTokens.asScala.toSeq,
      succR9     = if (healthyBranch) couponAdvancePack(s) else couponCurePack(s),
      instValue  = s(0),
      instTree   = lenderTreeBytesOf(bondBox),
      instR4     = Some(bondBox.getId.getBytes))
  }

  /** Build a coupon tx: bond + payer funds in, successor + installment
    * out; pool as dataInputs(0) for covenant bonds. The payer's change
    * absorbs the freed bounty (D5: borrower-as-payer keeps it). */
  def buildCoupon(ctx: BlockchainContext, bondBox: InputBox, plan: CouponPlan,
                  pool: Option[InputBox], payer: Address,
                  preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val rs   = bondBox.getRegisters
    val need = plan.instValue + Kit.TX_FEE + Kit.MIN_BOX_VALUE
    val funds = Kit.selectBoxes(ctx, payer, need)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    var sb = tb.outBoxBuilder()
      .value(plan.succValue)
      .contract(plan.succContractOverride.getOrElse(
        new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET)))
      .registers(
        plan.succR4Override.getOrElse(rs.get(0)),
        plan.succR5Override.getOrElse(rs.get(1)),
        plan.succR6Override.getOrElse(rs.get(2)),
        plan.succR7Override.getOrElse(rs.get(3)),
        plan.succR8Override.getOrElse(rs.get(4)),
        ErgoValue.of(plan.succR9))
    if (plan.succTokens.nonEmpty) sb = sb.tokens(plan.succTokens: _*)
    var ib = tb.outBoxBuilder()
      .value(plan.instValue)
      .contract(contractFromBytes(plan.instTree))
    plan.instR4.foreach { r4 => ib = ib.registers(ErgoValue.of(r4)) }
    var outs = Seq(sb.build(), ib.build())
    if (plan.extraTokensToPayer.nonEmpty)
      outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
        .contract(payer.toErgoContract).tokens(plan.extraTokensToPayer: _*).build()
    var builder = tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
    pool.foreach(p => builder = builder.withDataInputs(java.util.Arrays.asList(p)))
    builder.outputs(outs: _*)
      .fee(Kit.TX_FEE)
      .sendChangeTo(payer)
      .build()
  }

  /** Sign, submit, confirm an honest coupon; verifies the successor and
    * the installment receipt on-chain. Payer defaults to the borrower;
    * D15 passes the keeper (third-party liveness proof). */
  def doCoupon(bondBoxId: String, jitLabel: String,
               proverOf: BlockchainContext => ErgoProver = TestLib.borrower,
               expectHealthy: Boolean = true,
               attempts: Int = 3): String = {
    var succ: String = null
    var tries = 0
    while (succ == null && tries < attempts) {
      tries += 1
      try {
        succ = Kit.exec { ctx =>
          val bondBox = ctx.getBoxesById(bondBoxId)(0)
          val s       = TestLib.schedOf(bondBox)
          val covenantOn = s(4) != 0L
          val pool    = if (covenantOn) Some(P3.poolBox(ctx)) else None
          val isHealthy = if (!covenantOn) true else {
            val h = healthyV3(pool.get, bondBox.getValue - s(5),
              bondBox.getTokens.get(1).getValue, P3.repaymentOf(bondBox), s(4), haircutOf(s))
            require(h == expectHealthy,
              s"$jitLabel: pool verdict $h but test expects $expectHealthy — reserves moved?")
            h
          }
          val p     = proverOf(ctx)
          val payer = p.getEip3Addresses.get(0)
          val plan  = honestCouponPlan(bondBox, isHealthy)
          val unsigned = buildCoupon(ctx, bondBox, plan, pool, payer)
          Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
          val signed = p.sign(unsigned)
          val succId = signed.getOutputsToSpend.get(0).getId.toString
          val instId = signed.getOutputsToSpend.get(1).getId.toString
          val txId   = Kit.sendSafe(ctx, signed, jitLabel)
          Kit.waitConfirmed(txId, jitLabel)

          val conf = ctx.getBoxesById(succId)(0)
          val ns   = TestLib.schedOf(conf)
          require(conf.getValue == bondBox.getValue - bountyOf(s), "value != -1 bounty")
          require(ns(5) == s(5) - bountyOf(s), "escrow != -1 bounty")
          require(ns(2) == s(2) - 1L, "paymentsRemaining not decremented")
          require(ns(3) == plan.succR9(3), s"successor nextCheck ${ns(3)} != expected")
          val inst = Kit.httpGet(s"/blockchain/box/byId/$instId")
          require(inst.contains(TestLib.hex(plan.instTree)), "installment: wrong destination")
          require(inst.contains("0e20" + bondBoxId), "installment: R4 receipt missing")
          println(s"  coupon paid on-chain: succ $succId (payments ${ns(2)}, " +
            (if (isHealthy) s"nextCheck ${ns(3)}" else s"IN CURE, deadline ${-ns(3)}") + ")")
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

  // ---------------- missed-payment acceleration (decision 2) ----------------

  /** Missed-accel plan: the liquidation shape (toLender, value >= SELF -
    * carve-out, receipt, all tokens) with every leg override-able for
    * D8/D13. */
  case class MissedAccelPlan(
    exitValue: Long,
    exitTree: Array[Byte],
    receiptR4: Option[Array[Byte]],
    tokens: Seq[ErgoToken],
    extraTokensToKeeper: Seq[ErgoToken] = Nil,
    // Documentation-only marker for split attacks: the keeper box always
    // absorbs bond - exitValue - fee, so lowering exitValue IS the split.
    splitToKeeper: Long = 0L)

  def honestMissedAccelPlan(bondBox: InputBox): MissedAccelPlan = {
    val s = TestLib.schedOf(bondBox)
    MissedAccelPlan(
      exitValue = bondBox.getValue - carveOf(s),
      exitTree  = lenderTreeBytesOf(bondBox),
      receiptR4 = Some(bondBox.getId.getBytes),
      tokens    = bondBox.getTokens.asScala.toSeq)
  }

  /** Build a missed-payment acceleration: signatureless, bond sole input,
    * carve-out funds fee + keeper box (zero-capital), NO data input. */
  def buildMissedAccel(ctx: BlockchainContext, bondBox: InputBox,
                       plan: MissedAccelPlan, payTo: Address,
                       preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    var eb = tb.outBoxBuilder()
      .value(plan.exitValue)
      .contract(contractFromBytes(plan.exitTree))
    if (plan.tokens.nonEmpty) eb = eb.tokens(plan.tokens: _*)
    plan.receiptR4.foreach { r4 => eb = eb.registers(ErgoValue.of(r4)) }
    var kb = tb.outBoxBuilder()
      .value(bondBox.getValue - plan.exitValue - Kit.TX_FEE)
      .contract(payTo.toErgoContract)
    if (plan.extraTokensToKeeper.nonEmpty) kb = kb.tokens(plan.extraTokensToKeeper: _*)
    tb.boxesToSpend(java.util.Arrays.asList(bondBox))
      .outputs(eb.build(), kb.build())
      .fee(Kit.TX_FEE)
      .sendChangeTo(payTo)
      .build()
  }

  /** Sign, submit, confirm an honest missed-payment acceleration. */
  def doMissedAccel(bondBoxId: String, jitLabel: String,
                    proverOf: BlockchainContext => ErgoProver = TestLib.keeper): String =
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondBoxId)(0)
      val s       = TestLib.schedOf(bondBox)
      require(s(0) > 0L && s(3) > 0L && s(2) > 1L, s"$jitLabel: not a missed-coupon state")
      val p        = proverOf(ctx)
      val plan     = honestMissedAccelPlan(bondBox)
      val unsigned = buildMissedAccel(ctx, bondBox, plan, p.getEip3Addresses.get(0))
      Jit.record(jitLabel, p.reduce(unsigned, 0).getCost.toLong)
      val signed = p.sign(unsigned)
      require(signed.getSignedInputs.size == 1, "missed-accel must be self-funding (bond sole input)")
      val exitId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      println(s"  missed-payment acceleration on-chain: exit $exitId")
      exitId
    }

  // ---------------- hook-aware liquidation (E8) ----------------

  /** Build a liquidation of a hooked bond (R8 size 3): the hook script
    * bytes ride context-extension var 0 and the exit box sits AT the hook
    * script (destination rebind, REV3-LAYOUT.md L7). hookBytesOverride
    * models the wrong-preimage negative. */
  def buildHookedLiquidation(ctx: BlockchainContext, bondBox: InputBox,
                             hookBytes: Array[Byte],
                             payTo: Address,
                             exitTreeOverride: Option[Array[Byte]] = None,
                             preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    val s  = TestLib.schedOf(bondBox)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val withVar = bondBox.withContextVars(
      new ContextVar(0.toByte, ErgoValue.of(hookBytes)))
    val exit = tb.outBoxBuilder()
      .value(bondBox.getValue - carveOf(s))
      .contract(contractFromBytes(exitTreeOverride.getOrElse(hookBytes)))
      .tokens(bondBox.getTokens.asScala.toSeq: _*)
      .registers(ErgoValue.of(bondBox.getId.getBytes))
      .build()
    val kb = tb.outBoxBuilder()
      .value(carveOf(s) - Kit.TX_FEE)
      .contract(payTo.toErgoContract)
      .build()
    tb.boxesToSpend(java.util.Arrays.asList(withVar))
      .outputs(exit, kb)
      .fee(Kit.TX_FEE)
      .sendChangeTo(payTo)
      .build()
  }

  // ---------------- attestation stub fabrication (E7) ----------------

  /** A box at an arbitrary script modeling an attester verdict:
    * R4 = loan token id, R5 = |checkpoint|, R6 = 1 pass / 0 fail. */
  def fabAttesterBox(ctx: BlockchainContext, attesterTree: sigmastate.Values.ErgoTree,
                     loanId: Array[Byte], checkpoint: Long, pass: Boolean): InputBox =
    ctx.newTxBuilder().outBoxBuilder()
      .value(Kit.MIN_BOX_VALUE)
      .contract(new ErgoTreeContract(attesterTree, NetworkType.MAINNET))
      .registers(
        ErgoValue.of(loanId),
        ErgoValue.of(checkpoint),
        ErgoValue.of(if (pass) 1L else 0L))
      .build()
      .convertToInputWith(DUMMY_TX, 2)
}
