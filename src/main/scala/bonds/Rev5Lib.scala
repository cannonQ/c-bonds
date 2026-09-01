package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, ErgoToken}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import sigma.ast.ErgoTree
import scala.collection.JavaConverters._

/** Rev-5 tx builders: the harness retargeted from the rev-4 monolith
  * (Contracts.bond / Contracts.order) onto the six per-product trees.
  *
  * The rev-4 semantics port 1:1, so everything that is genuinely
  * product-agnostic is REUSED from Phase4Lib (P4) rather than copied:
  * packValue/packOf, h32, orderWithMatchVars, resolve/COMPILED_DEFAULTS,
  * buildCoupon/honestCouponPlan, buildMissedAccel/honestMissedAccelPlan,
  * buildHookedLiquidation, and P2.buildTopUp. What is NEW here is
  * everything that names a contract or a register TYPE:
  *
  *   - the compiled per-product family (each order pins its own bond
  *     tree hash, so bond and order must always be taken from the SAME
  *     Family);
  *   - B1's register shapes, which are NOT the rev-4 shapes: the ORDER
  *     has R4-R7 only (no R8 pin pack, no R9 template) and the BOND's
  *     R8 is a PLAIN Coll[Byte] lender hash with no R9 at all. Writing a
  *     rev-4 Coll[Coll[Byte]] pack into B1's R8 makes the order
  *     unmatchable and the bond unspendable — hence the separate
  *     builders and the separate ctx-var attacher below;
  *   - per-address decoding (lenderHashOfBond / carveOfBond): the same
  *     read is a different register type per product, so decoding must
  *     branch on the box's script, never on a remembered layout.
  *
  * CTX-VAR INDEX COLLISION (inherited rev-4 footgun, REV4-LAYOUT):
  * on an ORDER input at match, var 0 = the full lender script and var 1 =
  * the full hook script; on a BOND input at liquidation, var 0 = the hook
  * script. Same index, different meaning, different box. Every attacher
  * below is named for its site and no constant is shared between them.
  *
  * SPEND SAFETY: build* / fab* never SUBMIT anything — no transaction is
  * submitted from them; read-only node queries (wallet unspent lookups
  * via Kit.selectBoxes, box fetches via ctx.getBoxesById) DO occur, so
  * they are not pure in the "nothing leaves the process" sense.
  * post* / do* sign and submit. The do* wrappers were written against the
  * proven rev-4 originals but have NEVER been executed — no rev-5
  * transaction has touched the chain (workstream 1 is spend-free).
  */
object R5 {
  import Contracts._

  // ==================== the compiled family ====================

  /** One product: its bond tree and the order tree that pins it. Bond and
    * order must always come from the same Family — an order compiled
    * against a different bond tree carries a BOND_SCRIPT_HASH that no box
    * of ours can satisfy. */
  final case class Family(name: String, bondTree: ErgoTree, orderTree: ErgoTree) {
    def bondContract: ErgoTreeContract  = new ErgoTreeContract(bondTree, NetworkType.MAINNET)
    def orderContract: ErgoTreeContract = new ErgoTreeContract(orderTree, NetworkType.MAINNET)
    def bondAddress: Address  = Address.fromErgoTree(bondTree, NetworkType.MAINNET)
    def orderAddress: Address = Address.fromErgoTree(orderTree, NetworkType.MAINNET)
    /** B1 is the odd one out on every register-typed read. */
    def isPlain: Boolean = name == PLAIN
  }

  val PLAIN      = "B1/O1 plain-bullet"
  val COVENANT   = "B2/O2 covenant-bullet"
  val INSTALMENT = "B3/O3 instalment"

  // Compilation is deterministic in the sources + compiled constants, so
  // the trees are cached across contexts (a Family is a value; only
  // compileContract needs the ctx).
  private val cache = new java.util.concurrent.ConcurrentHashMap[String, Family]()

  private def familyOf(name: String, ctx: BlockchainContext,
                       compile: BlockchainContext => (ErgoTree, ErgoTree)): Family = {
    val hit = cache.get(name)
    if (hit != null) hit
    else {
      val (b, o) = compile(ctx)
      val f = Family(name, b, o)
      cache.put(name, f)
      f
    }
  }

  def plain(ctx: BlockchainContext): Family =
    familyOf(PLAIN, ctx, c => (Contracts.plainBulletBond(c)._1, Contracts.plainBulletOrder(c)._1))
  def covenant(ctx: BlockchainContext): Family =
    familyOf(COVENANT, ctx, c => (Contracts.covenantBulletBond(c)._1, Contracts.covenantBulletOrder(c)._1))
  def instalment(ctx: BlockchainContext): Family =
    familyOf(INSTALMENT, ctx, c => (Contracts.instalmentBond(c)._1, Contracts.instalmentOrder(c)._1))

  def all(ctx: BlockchainContext): Seq[Family] =
    Seq(plain(ctx), covenant(ctx), instalment(ctx))

  def cardTree(ctx: BlockchainContext): ErgoTree = Contracts.termsBox(ctx)._1

  // ==================== the frozen family pin ====================

  /** The seven P2S addresses of the frozen rev-5 family, verbatim from
    * `sbt "runMain bonds.Contracts"`. These are the pins the freeze is
    * stated in: the operator's baseline
    * `sbt "runMain bonds.Contracts" 2>&1 | grep address | sha256sum` ==
    * f15377b639806721ce54d1ac63db5cf1f4ed031df359b1f50f231ed3076155d9
    * is that same print hashed, and it cannot be reproduced in-process
    * (it hashes sbt's own log prefixes), so the addresses themselves are
    * pinned here instead — the same fact, checkable without a shell.
    *
    * REGENERATE ONLY WITH SIGN-OFF: a changed address here means a
    * changed contract, which is exactly what verifyFamily exists to
    * refuse silently shipping. */
  private object FamilyPins {
    val plainBond  = "wYTUgRXbvBvWDkMuMkjfc6yPuhu2PZA9MRDzBbWqBUt9dcs3pEKVsrnttLcubqdxNAcKxWw59pKoUbmPoT7FjiCEeeyEZ4WjrkwbHPZpUmM22Cv48ryEh5M6PA99q97moouet8N8MjrFd83FeNXv6jPS2oPcMiXMR5YAvuFimcjrhKXnRv1GiWDWdBUhMC2ZXJuJPfte76ms3aqJWZDiiLX6nw9iKgHUi2cGER3mUC455Zck8YQqMWvb1pFaEuji8KAmyhrrEtFt6zaJwJu6zYzGEFQJzkg956ZF5xddZapcE3NG2WZf4QH3sK7GKQVDMk3E4dXmCGcb7458qyX8ZnPhEb1qYFJ4Tx"
    val plainOrder = "K3bgR61th9JVtFdVgF7PyyfyTwTN4nu9gnP4SSsvFmaaN9shD26pFrXtEY5nH1PUaQYzBLqTSFzrwESHQod3SiSowEMS1HAnZ45rGjpdvFbj4C3UFmMSZWTiKuTmCAu26yAd5hDD6GUYEsswpxGvVXtoA5txiw6TaGoMsPMCm9wPY1aUF6BS1Vhboc3XbhAfb1uxL4QjA6iDz6kkoN76PpXbdcbaDpkfbgX2o3WBd64zcoz892RcR9CkgKUYyZP4qmz31QNmWJFt7s763Qx9Wc9UUYiq4szUPMV6iWigvPebXaYofAzY6UBDKhenxuuNjEmk15p15iUS7BzytEwYv56et4UaRVK5gpz6egFdCqmN2GYTkFKePy4YSUokZCnMePQtck6uvBAYYMBKXaXMpUSSZTqcfnMhsX5wFhc7ht3nN97fJ9djrtMVXEWenM5wQnH13axzjJZ1UtDcr812BD7WxN6SQfkuMzL32uxwkpHUKcdvg5tq7erXjSwGXdHPv33zpTQbD3CvsS7ZJ5PJYmDQgCC5eQF7xgwVuEw9GDHQzTdNb8Edju1DQBybUKWHbrgXTAH88qeEvkqyRXN4t3xdKHbT68CyT6ipjq4ocwgHdZeHX8jjvdCmqEzBZinty61iU1yBJ61PFASx3T1h7cNN9xpPDUTwaQrP87CH1AJ2ZP5ZBpWWgWfXLYssFzYKZcsdnV9EWDpWkViXEcd"
    val covBond    = "ErqgEBNi79RNFntyuPNvEbW3cJMdEcMfohTvxCA52KQsLc2FWr149zbJAhLzzpUS9Nj7NthHXhS3mFGtD9Dqquy2iYu6Teog98pRygsYPMsPoWMG98mVBmmCsSAxKzihxqyuSf6of2BsRLqz3EtPM8DeC1aPLJ8SxX1AjBfFePkzvvrQvyebe8PsSYgLjsv8XD2NfNpuzQN6t8hgNaRVTxNCAURumM47XbfXK8DYerJSdZtYwPzc1wZLvzBqYcvuM45bEK5W5XA2dSobDJCXZm1sUooeDv8UrSrbbQTn2pHfZ8jfpQbunnKBrf9BTS2QerugJ8av4b9rUqptmvb6JXAv4iZ2kniAkENvB8Z4UXjnFMJg3y4VBMNjdkQkZcVDRv7GQziASL9xknDAUECE6fqSJTZng4XeHFR5YXg4XyiXuoqfxCsVKisFv193rfugZAoha8F4oyRfUew1FiRZiyXT52kyoSsByKyKhamJibSfCcb9u2o9m2u1reRJDcuajpDuaUt4xhWmQYutz8wjiZCLYivZrvBhjnosVSenqXuHVcGMAdx4S9Keo8VseKpqPMUxTFMsRzfNVNurVCoVX8AGzaJCM72DvvqzLV9d9dVdUpPTEeYdQfdF6aHEkBjXf2RHVE4cfq8FFsmDAybQyKaCnrqGQbfbn1BT2YEPmBdhYNGkEtzifZPb4T6grZ929BnMnrxHagzdgSauWxp3qjKvre3kn1rSVQFoxh9Gqd5Uu47hbnzR478VFfyLUbUwSDweKenZFyxzw9UtwYXZn57iABiM4kc6CTPS8ietLoVrGkVc8xAKL6ww9LXTbJp78ZiQawNP44xbQV4oamPT2PTpu2RqRhJFgJ68DpHDoUYGwdyFtEzunMzwnfcTcafYFQaqu5fnAvgGeZm8cMPHW59kSt6TJhbJ2qhiaj5VegQzQuTDpuTMFtzEZcRLZ4TPZamjY3FSa1yRhqRMnZ8av6Rr6UdE5BgWMmNsCU19Dav9E75WS3YrQqNnWpEjZVKZRNxeeaesQTTLRt1h8LMQETQ2rA37Y9qveSCHKDVLnGsVQ8TSiKvZTzGU9vnSZUupMYit3VRj3ac5KHbg661X6wTySghgm816ju4FdinaqUKdr1MWi6JAN1EnGJF9mGmQwdZeeeMKTHjmehmBjpSEHSZqhdazWiwq4917hPYCdAN1NEKFMXgnN8nEyL4aJwSXnTSBoprN8eNYNUsmfEyD8Q2qSYSRbUKjtcSNpVimHXKNoRnxcCS75fniGQfggF4bH5birfm38CcxGqmu2LVJpJHUd8Y18nLCU4sc5EkqKV3ZAV1VRmMckCbS25qRDQcCcWW9bxfT4eYq69WZ7auTTQhC8REZf4AhCFDjRGxFbWAM3v3NR6NCTdNzzobzjgLWiQsLW5S7tsLpDKkHMusrsLxcJ97PiTLaQnVYrnAefkBNweZb8J1nueFTni641mgX7SY7geXbkAP3Gm59FLRUPsLzUpRt7h7skR28ESUzTLxCNd6qVYXTyMYbTg2TUMt52MgjUap3mgQrGtgRVw1HFw9jLeQMq1beCBLYSGbZVWBQdTKg2aJ6HXY2mnbzBPtt3ssLVZ4HyiJ75nHHpizbpoqS2f7H9YexCp7bei6z4rrdqhxzifWGZsx3jtKXiZJqCoTr6Y1Y8yJV2vLQBKor7RxFYwQvKSCdiCvSqp17HtE6ihBMMkDK3rfyekoY7T5YqJnLRrYfnrHvM1YhecPv8aLGKHzvcvBsoZGZvozcExE6MAKdZUhNExEQW7nmASvm27KEwdd1mfXkz3qhdRta3KUe9AamEezzPDqZYA2LkPxHTXHwcNESXCukm47MqnYao5KgrL49mguD8GqNQQkVJMNCaXohXejNuBzaAsKJcjjMhjdTsSBeN7bNmUvLsrFUCKz8oKxKKKsjmYuvDkH1RniqdbCubDFRvNu4XEScEiUBhsUDBypZffQTyxRLvxE8RJLPycTa1owZKE9xYgCnMomjjQYVdJfK2JoEUrLsp3F5CU"
    val covOrder   = "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"
    val instBond   = "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"
    val instOrder  = "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"
    val card       = "3jG8tiQUR6mqigJGhde4bkXU9ktM5PVRrG5f5ZGU5aYza8tx5CqmNqGs17c2xDtZSZEHppuTsQ1wtkBraDAL7wbR5zESDASCzapQL8m2RX2kHLeXA54u5ytndF3xF2dfGHrNrVH57wMK6hENMFfvjouUTuqVwfrZ3u2jkfT5TDnMNznWJmKBu4QwGS1TMcLCfCHngDgpAKCMvgwBohRGBm3ZFKZFVFfCb"
  }

  @volatile private var familyVerified = false

  /** A9: refuse to SPEND against a family that is not the frozen one.
    * Compilation is cached per JVM, so this is one address render per
    * tree the first time a submitter runs and free thereafter — it is
    * deliberately NOT on the build* path, which is exercised thousands of
    * times by the gate.
    *
    * A drifted tree here means the operator is about to post collateral
    * to an address no reviewed contract governs. That is a stop, not a
    * warning. */
  def verifyFamily(ctx: BlockchainContext): Unit = if (!familyVerified) {
    def chk(name: String, addr: Address, pin: String): Unit =
      require(addr.toString == pin,
        s"FROZEN FAMILY DRIFT: $name compiles to ${addr.toString.take(24)}... but the pinned " +
        s"rev-5 address starts ${pin.take(24)}... — the contracts under contracts/ are not the " +
        s"reviewed ones (baseline family hash " +
        s"f15377b639806721ce54d1ac63db5cf1f4ed031df359b1f50f231ed3076155d9). REFUSING to spend.")
    val p = plain(ctx); val c = covenant(ctx); val i = instalment(ctx)
    chk("plainBulletBond",     p.bondAddress,  FamilyPins.plainBond)
    chk("plainBulletOrder",    p.orderAddress, FamilyPins.plainOrder)
    chk("covenantBulletBond",  c.bondAddress,  FamilyPins.covBond)
    chk("covenantBulletOrder", c.orderAddress, FamilyPins.covOrder)
    chk("instalmentBond",      i.bondAddress,  FamilyPins.instBond)
    chk("instalmentOrder",     i.orderAddress, FamilyPins.instOrder)
    chk("termsBox",            Address.fromErgoTree(cardTree(ctx), NetworkType.MAINNET),
      FamilyPins.card)
    familyVerified = true
  }

  // ==================== token-slot hygiene (A1) ====================

  /** Merge duplicate token-id slots into one, first-appearance order.
    *
    * WHY: nothing in appkit, in the sigma interpreter, or in the node
    * rejects two slots of the SAME token id in one box (REV5-JITCOST.md
    * §3, layers 1-4), and the bonds' collateral checks are
    * `SELF.tokens.forall { t => out.tokens.exists { o => o._1 == t._1 &&
    * o._2 >= t._2 } }` — an EXISTENTIAL, which one bond-side entry can
    * satisfy for BOTH order-side slots. An order posted with (X,6) and
    * (X,4) therefore only ever needs 6 units delivered back, and the
    * other 4 are strippable at liquidation. Merging at the point of
    * construction means this harness can never post such an order. */
  private def mergeTokens(tokens: Seq[ErgoToken]): Seq[ErgoToken] = {
    val byId = tokens.groupBy(_.getId.toString)
    tokens.map(_.getId.toString).distinct.map { id =>
      val ts = byId(id)
      new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)
    }
  }

  /** The other half of A1: a box that ALREADY carries duplicate slots
    * must never be spent by these builders. Merging on the way out does
    * not help a box someone else built. */
  private def requireDistinctTokens(box: InputBox, what: String): Unit = {
    val ids = box.getTokens.asScala.map(_.getId.toString)
    require(ids.distinct.size == ids.size,
      s"$what carries duplicate token-id entries — collateral would be strippable at " +
      s"liquidation (box ${box.getId}, ids ${ids.mkString(", ")})")
  }

  /** A4: the escrow the ORDER carries must be exactly what the card it
    * pins resolves to, because the order contract checks
    * `tmpl(5) == n(0) * ((term - 1) / tmpl(1))` with n(0) = the CARD's
    * resolved bounty (CovenantBulletOrder.es:199 / InstalmentOrder.es:208).
    * Pin a card with a bigger bounty than the escrow was sized for and
    * the order is unmatchable — collateral parked until it is cancelled.
    * `cardR7` is None for a card-less order, where the compiled bounty
    * applies. */
  def escrowCrossCheck(tmpl: Array[Long], term: Int, cardR7: Option[Array[Long]]): Unit =
    // tmpl(1) == 0 is permanently unmatchable anyway (the K division sits
    // behind the MIN_PERIOD floor), so there is no escrow to cross-check.
    if (tmpl.length >= 6 && tmpl(1) > 0L) {
      val bounty = cardR7.map(r7 => P4.resolve(r7).bounty).getOrElse(P4.COMPILED_DEFAULTS.bounty)
      val k      = P4.kOf(term, tmpl(1))
      require(tmpl(5) == bounty * k,
        s"escrow/card mismatch: the order escrow tmpl(5) = ${tmpl(5)} but the " +
        s"${if (cardR7.isDefined) "PINNED CARD" else "compiled default"} resolves bounty " +
        s"$bounty over K = $k checkpoints, i.e. ${bounty * k}. Posting this order parks the " +
        s"collateral at an address that can never match it.")
    }

  // ==================== per-address decoding ====================
  // The same register index means different things at different
  // addresses, so every decode starts from the box's own script. Never
  // decode a rev-5 box from a remembered layout.

  private def sameScript(box: InputBox, t: ErgoTree): Boolean =
    java.util.Arrays.equals(box.getErgoTree.bytes, t.bytes)

  /** (family, isBond) for any box sitting at one of the six addresses. */
  def classify(ctx: BlockchainContext, box: InputBox): Option[(Family, Boolean)] =
    all(ctx).flatMap { f =>
      if (sameScript(box, f.bondTree)) Some((f, true))
      else if (sameScript(box, f.orderTree)) Some((f, false))
      else None
    }.headOption

  def label(ctx: BlockchainContext, box: InputBox): String =
    classify(ctx, box)
      .map { case (f, isBond) => s"${f.name} ${if (isBond) "bond" else "order"}" }
      .getOrElse("(not a rev-5 box)")

  def isPlainBond(ctx: BlockchainContext, box: InputBox): Boolean =
    sameScript(box, plain(ctx).bondTree)
  def isPlainOrder(ctx: BlockchainContext, box: InputBox): Boolean =
    sameScript(box, plain(ctx).orderTree)

  /** A8: every decoder below reads a register whose TYPE is fixed by the
    * box's address. Handed a box from anywhere else, the read is either a
    * wrong-type crash or — worse — a plausible-looking number off some
    * other protocol's register. Classify first, decode second. */
  private def requireRev5Bond(ctx: BlockchainContext, box: InputBox): Unit =
    require(classify(ctx, box).exists(_._2), s"not a rev-5 bond box: ${box.getId}")

  /** Bond R8 on a B1 bond: a PLAIN Coll[Byte] lender-script hash. Reading
    * it as a pack (P4.bondR8Of) throws; writing a pack into it bricks the
    * box. */
  def plainLenderHash(ctx: BlockchainContext, bondBox: InputBox): Array[Byte] = {
    requireRev5Bond(ctx, bondBox)
    P4.borrowerBytesOf(bondBox, 4)
  }

  /** The lender-script hash of ANY rev-5 bond, decoded per product. */
  def lenderHashOfBond(ctx: BlockchainContext, bondBox: InputBox): Array[Byte] = {
    requireRev5Bond(ctx, bondBox)
    if (isPlainBond(ctx, bondBox)) P4.borrowerBytesOf(bondBox, 4) else P4.lenderHashOf(bondBox)
  }

  /** The liquidation carve-out a bond's own state resolves to: B1 has no
    * R9, so its carve-out is always the compiled constant; B2/B3 read the
    * card suffix with the same sentinel fallback the contract applies. */
  def carveOfBond(ctx: BlockchainContext, bondBox: InputBox): Long = {
    requireRev5Bond(ctx, bondBox)
    if (isPlainBond(ctx, bondBox)) LIQ_CARVEOUT else P4.carveOf(TestLib.schedOf(bondBox))
  }

  /** Human-readable dump of a rev-5 bond, branching per address (the
    * CardInfo-style read side). */
  def describeBond(ctx: BlockchainContext, bondBox: InputBox): String = {
    requireRev5Bond(ctx, bondBox)
    val rs  = bondBox.getRegisters
    val hd  = s"${label(ctx, bondBox)}  box ${bondBox.getId}  ${bondBox.getValue} nanoERG"
    val r67 = s"  R6 repayment ${rs.get(2).getValue.asInstanceOf[Long]}  " +
              s"R7 maturity ${rs.get(3).getValue.asInstanceOf[Int]}"
    if (isPlainBond(ctx, bondBox))
      s"$hd\n$r67\n  R8 lenderHash ${TestLib.hex(P4.borrowerBytesOf(bondBox, 4))} (Coll[Byte], no R9)"
    else {
      val pack = P4.bondR8Of(bondBox).map(a => if (a.isEmpty) "(empty)" else TestLib.hex(a))
      s"$hd\n$r67\n  R8 pack [${pack.mkString(", ")}]\n" +
        s"  R9 ${TestLib.schedOf(bondBox).mkString("[", ", ", "]")}"
    }
  }

  // ==================== ctx-extension attachers ====================

  /** O1 match reveal: var 0 = the FULL lender script on the ORDER input.
    * B1's order has no R8, so P4.orderWithMatchVars (which reads the R8
    * pin pack to decide whether to attach var 1) must NOT be used here —
    * it throws on a register-less read. A plain-bullet order can never
    * carry a hook, so var 1 does not exist at this site. */
  def plainOrderWithLenderVar(orderBox: InputBox, lenderScriptBytes: Array[Byte],
                              dropLenderVar: Boolean = false,
                              lenderRevealOverride: Option[Array[Byte]] = None): InputBox =
    if (dropLenderVar) orderBox
    else orderBox.withContextVars(new ContextVar(0.toByte,
      ErgoValue.of(lenderRevealOverride.getOrElse(lenderScriptBytes))))

  /** O2/O3 match reveal: var 0 = lender script, var 1 = hook script when
    * the order pins one. Unchanged from rev 4 — reused, not copied. */
  def packedOrderWithMatchVars(orderBox: InputBox, lenderScriptBytes: Array[Byte],
                               hookScriptBytes: Option[Array[Byte]] = None,
                               dropLenderVar: Boolean = false,
                               dropHookVar: Boolean = false,
                               lenderRevealOverride: Option[Array[Byte]] = None): InputBox =
    P4.orderWithMatchVars(orderBox, lenderScriptBytes, hookScriptBytes,
      dropLenderVar, dropHookVar, lenderRevealOverride)

  // ==================== templates (order R9) ====================
  // The requires below mirror the contracts' own schedCommonOk /
  // conformsWith gates, so a builder mistake fails HERE with a readable
  // message instead of as an opaque reduce-to-false at signing.

  /** O2 template: bullet (installment 0, payments 0) with a MANDATORY
    * covenant. escrow == bounty * K, K = (term - 1) / period, K >= 1
    * (emergent: escrow >= one bounty plus exactness). */
  def covenantTemplate(term: Int, period: Long, thresholdBps: Long,
                       bounty: Long = CRANK_BOUNTY): Array[Long] = {
    require(term >= 1, "O2: term >= 1")
    require(period >= MIN_PERIOD, s"O2: period $period below compiled MIN_PERIOD $MIN_PERIOD")
    require(thresholdBps != 0L, "O2: the covenant is mandatory (tmpl(4) != 0)")
    val k = P4.kOf(term, period)
    require(k >= 1L, s"O2: K == $k — a covenant that can never fire is unmatchable")
    Array[Long](0L, period, 0L, 0L, thresholdBps, bounty * k)
  }

  /** O3 template: installment > 0 (MIN_COUPON floor), payments = K + 1,
    * covenant optional. The tmpl(0)/tmpl(2) coupling conjunct is
    * LOAD-BEARING in the contract — both are nonzero here by
    * construction. */
  def instalmentTemplate(term: Int, period: Long, installment: Long,
                         thresholdBps: Long = 0L,
                         bounty: Long = CRANK_BOUNTY,
                         minCoupon: Long = MIN_COUPON): Array[Long] = {
    require(term >= 1, "O3: term >= 1")
    require(period >= MIN_PERIOD, s"O3: period $period below compiled MIN_PERIOD $MIN_PERIOD")
    require(installment > 0L, "O3: installment > 0 is the product discriminator")
    require(installment >= minCoupon, s"O3: installment $installment below the coupon floor $minCoupon")
    val k = P4.kOf(term, period)
    require(k >= 1L, s"O3: K == $k — an installment order needs >= 1 interior coupon")
    Array[Long](installment, period, k + 1L, 0L, thresholdBps, bounty * k)
  }

  // ==================== B1 / O1: plain bullet ====================

  /** Fabricated O1 order box: R4-R7 ONLY. No R8 pin pack, no R9
    * template — this order has no card, no schedule and no escrow. */
  def fabPlainOrder(ctx: BlockchainContext, borrowerBytes: Array[Byte],
                    value: Long = TestLib.COLLATERAL,
                    principal: Long = TestLib.PRINCIPAL,
                    repayment: Long = TestLib.REPAYMENT,
                    term: Int = TestLib.TERM_LONG,
                    tokens: Seq[ErgoToken] = Nil,
                    txId: String = P4.DUMMY_TX, outIdx: Short = 3): InputBox = {
    var b = ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(plain(ctx).orderContract)
      .registers(
        ErgoValue.of(borrowerBytes), // R4 borrower ErgoTree BYTES
        ErgoValue.of(principal),     // R5 principal
        ErgoValue.of(repayment),     // R6 repayment
        ErgoValue.of(term))          // R7 term in blocks
    // A1: duplicate id slots are merged here and never reach the box.
    if (tokens.nonEmpty) b = b.tokens(mergeTokens(tokens): _*)
    b.build().convertToInputWith(txId, outIdx)
  }

  /** Fabricated B1 bond box (local probes only): R4-R8, R8 a PLAIN
    * Coll[Byte]. Values are written VERBATIM — a conforming fab passes
    * h32(tree) for both hashes; a malformed-shape test passes whatever it
    * means to test. */
  def fabPlainBond(ctx: BlockchainContext,
                   lenderHash: Array[Byte],
                   borrowerHash: Array[Byte],
                   value: Long,
                   repayment: Long,
                   maturity: Int,
                   tokens: Seq[ErgoToken] = Nil,
                   loanTokenId: String = P4.FAKE_LOAN,
                   r8Override: Option[ErgoValue[_]] = None,
                   txId: String = P4.DUMMY_TX, outIdx: Short = 0): InputBox = {
    val fakeId = ErgoId.create(loanTokenId)
    ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(plain(ctx).bondContract)
      .tokens((new ErgoToken(fakeId, 1L) +: tokens): _*)
      .registers(
        ErgoValue.of(fakeId.getBytes),                     // R4 order box id
        ErgoValue.of(borrowerHash),                        // R5 blake2b256(borrower tree)
        ErgoValue.of(repayment),                           // R6
        ErgoValue.of(maturity),                            // R7
        r8Override.getOrElse(ErgoValue.of(lenderHash)))    // R8 PLAIN Coll[Byte]
      .build()
      .convertToInputWith(txId, outIdx)
  }

  /** O1 match. Bond R8 is the 32-byte lender hash as a PLAIN Coll[Byte]
    * and there is NO R9; the preimage rides ctx-ext var 0 on the ORDER
    * input. Maturity is stamped build-height + term and must satisfy the
    * contract's m > HEIGHT + 1 floor — a B1 bond gets at least the whole
    * birth block with repayment open and liquidation shut, so term >= 2
    * is the minimum matchable term on this product. Every leg is
    * override-able for the wall suite; overrides are taken VERBATIM. */
  def buildPlainMatch(ctx: BlockchainContext, orderBox: InputBox,
                      lenderScriptBytes: Array[Byte], term: Int,
                      funder: ErgoProver,
                      bondR8Override: Option[ErgoValue[_]] = None,
                      bondR5Override: Option[Array[Byte]] = None,
                      maturityOverride: Option[Int] = None,
                      dropLenderVar: Boolean = false,
                      lenderRevealOverride: Option[Array[Byte]] = None,
                      preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(orderBox,
      "order carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to match")
    val fAddr     = funder.getEip3Addresses.get(0)
    val rs        = orderBox.getRegisters
    val principal = rs.get(1).getValue.asInstanceOf[Long]
    val repayment = rs.get(2).getValue.asInstanceOf[Long]
    val bBytes    = P4.borrowerBytesOf(orderBox, 0)
    // A7: the term is the ORDER's, not the caller's. Every maturity check
    // in the contract is stated against the order's own R7; a caller that
    // passes a different number stamps a maturity the order never agreed
    // to (and the match either dies at signing or, inside MATURITY_TOL,
    // does not).
    val orderTerm = rs.get(3).getValue.asInstanceOf[Int]
    require(term == orderTerm, s"term $term != order R7 $orderTerm")
    // Base the stamp on the CONTEXT height, not a fresh node query: the
    // validation height of this transaction comes from the context's
    // pre-header, and a block landing between the two reads pushes the
    // stamp one past the order's m <= HEIGHT + term ceiling (observed
    // 2026-08-31 as a flaky match). MATURITY_TOL covers the other side.
    val maturity  = maturityOverride.getOrElse(
      preHeaderHeight.getOrElse(ctx.getHeight) + orderTerm)

    val orderIn = plainOrderWithLenderVar(orderBox, lenderScriptBytes,
      dropLenderVar, lenderRevealOverride)
    val funds = Kit.selectBoxes(ctx, fAddr, principal + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val bondOut = tb.outBoxBuilder()
      .value(orderBox.getValue)
      .contract(plain(ctx).bondContract)
      .tokens((new ErgoToken(orderBox.getId, 1L) +: orderBox.getTokens.asScala.toSeq): _*)
      .registers(
        ErgoValue.of(orderBox.getId.getBytes),                       // R4 order box id
        ErgoValue.of(bondR5Override.getOrElse(P4.h32(bBytes))),      // R5 borrower hash
        ErgoValue.of(repayment),                                     // R6
        ErgoValue.of(maturity),                                      // R7
        bondR8Override.getOrElse(ErgoValue.of(P4.h32(lenderScriptBytes))) // R8 lender hash
      ).build()
    val principalOut = tb.outBoxBuilder()
      .value(principal)
      .contract(P4.contractFromBytes(bBytes))
      .build()
    tb.boxesToSpend((Seq(orderIn) ++ funds).asJava)
      .outputs(bondOut, principalOut)
      .fee(Kit.TX_FEE).sendChangeTo(fAddr).build()
  }

  /** Post an O1 order on-chain from the borrower wallet. NEVER EXECUTED
    * in workstream 1 (spend-free) — modeled on P4.postOrderV3. */
  def postPlainOrder(collateral: Long = TestLib.COLLATERAL,
                     principal: Long = TestLib.PRINCIPAL,
                     repayment: Long = TestLib.REPAYMENT,
                     term: Int = TestLib.TERM_LONG,
                     collTokens: Seq[ErgoToken] = Nil,
                     borrowerBytesOverride: Option[Array[Byte]] = None,
                     label: String = "post-order-B1"): String =
    Kit.exec { ctx =>
      verifyFamily(ctx)
      val b     = TestLib.borrower(ctx)
      val bAddr = b.getEip3Addresses.get(0)
      val bBytes = borrowerBytesOverride.getOrElse(bAddr.toErgoContract.getErgoTree.bytes)
      val outToks = mergeTokens(collTokens)   // A1
      val tokenBoxes =
        if (outToks.isEmpty) Nil
        else TestLib.boxesWithToken(ctx, bAddr, outToks.head.getId.toString)
      val tokenValue = tokenBoxes.map(_.getValue.toLong).sum
      val ergNeed    = collateral + Kit.TX_FEE + Kit.MIN_BOX_VALUE - tokenValue
      val ergBoxes   = if (ergNeed > 0) Kit.selectBoxes(ctx, bAddr, ergNeed) else Nil
      val inputs     = tokenBoxes ++ ergBoxes

      val tb = ctx.newTxBuilder()
      var ob = tb.outBoxBuilder()
        .value(collateral)
        .contract(plain(ctx).orderContract)
        .registers(ErgoValue.of(bBytes), ErgoValue.of(principal),
          ErgoValue.of(repayment), ErgoValue.of(term))
      if (outToks.nonEmpty) ob = ob.tokens(outToks: _*)
      val outs = ob.build() +: leftoverTokenBox(ctx, tb, inputs, outToks, bAddr).toSeq

      val unsigned = tb.boxesToSpend(inputs.asJava).outputs(outs: _*)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed  = b.sign(unsigned)
      val orderId = signed.getOutputsToSpend.get(0).getId.toString
      val txId    = Kit.sendSafe(ctx, signed, label)
      Kit.waitConfirmed(txId, label)
      println(s"  B1 order box: $orderId (no escrow, no card, no schedule)")
      orderId
    }

  /** Sign, submit, confirm an honest O1 match. NEVER EXECUTED in
    * workstream 1. Returns (bondBoxId, maturity). */
  def doPlainMatch(orderBoxId: String, lenderScriptBytes: Array[Byte], term: Int,
                   jitLabel: String): (String, Int) =
    Kit.exec { ctx =>
      verifyFamily(ctx)
      val l        = TestLib.lender(ctx)
      val orderBox = ctx.getBoxesById(orderBoxId)(0)
      val maturity = ctx.getHeight + term   // must match buildPlainMatch/buildPackedMatch
      val unsigned = buildPlainMatch(ctx, orderBox, lenderScriptBytes, term, l)
      Jit.record(jitLabel, l.reduce(unsigned, 0).getCost.toLong)
      val signed = l.sign(unsigned)
      val bondId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      println(s"  B1 bond box: $bondId  maturity: $maturity")
      (bondId, maturity)
    }

  // ==================== B2 / B3 orders: the packed shapes ====================

  /** Fabricated O2/O3 order box: the rev-4 shape (R4-R9, R8 = [cardPin]
    * or [cardPin, hookHash], R9 = the 6-element template). */
  def fabPackedOrder(ctx: BlockchainContext, family: Family,
                     borrowerBytes: Array[Byte],
                     tmpl: Array[Long],
                     value: Long,
                     cardPin: Array[Byte] = Array.emptyByteArray,
                     hookHash: Option[Array[Byte]] = None,
                     principal: Long = TestLib.PRINCIPAL,
                     repayment: Long = TestLib.REPAYMENT,
                     term: Int = TestLib.TERM_LONG,
                     tokens: Seq[ErgoToken] = Nil,
                     txId: String = P4.DUMMY_TX, outIdx: Short = 3): InputBox = {
    require(!family.isPlain, "fabPackedOrder: B1 orders carry no R8/R9 — use fabPlainOrder")
    val pinPack = hookHash match {
      case Some(h) => Seq(cardPin, h)
      case None    => Seq(cardPin)
    }
    var b = ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(family.orderContract)
      .registers(
        ErgoValue.of(borrowerBytes),
        ErgoValue.of(principal),
        ErgoValue.of(repayment),
        ErgoValue.of(term),
        P4.packValue(pinPack),
        ErgoValue.of(tmpl))
    // A1: duplicate id slots are merged here and never reach the box.
    if (tokens.nonEmpty) b = b.tokens(mergeTokens(tokens): _*)
    b.build().convertToInputWith(txId, outIdx)
  }

  /** Fabricated B2/B3 bond box (local probes only): the rev-4 shape with
    * the Coll[Coll[Byte]] R8 pack and the Coll[Long] R9. */
  def fabPackedBond(ctx: BlockchainContext, family: Family,
                    sched: Array[Long],
                    r8Pack: Seq[Array[Byte]],
                    borrowerHash: Array[Byte],
                    value: Long,
                    repayment: Long,
                    maturity: Int,
                    tokens: Seq[ErgoToken] = Nil,
                    loanTokenId: String = P4.FAKE_LOAN,
                    txId: String = P4.DUMMY_TX, outIdx: Short = 0): InputBox = {
    require(!family.isPlain, "fabPackedBond: B1 bonds have a plain R8 and no R9 — use fabPlainBond")
    val fakeId = ErgoId.create(loanTokenId)
    ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(family.bondContract)
      .tokens((new ErgoToken(fakeId, 1L) +: tokens): _*)
      .registers(
        ErgoValue.of(fakeId.getBytes),
        ErgoValue.of(borrowerHash),
        ErgoValue.of(repayment),
        ErgoValue.of(maturity),
        P4.packValue(r8Pack),
        ErgoValue.of(sched))
      .build()
      .convertToInputWith(txId, outIdx)
  }

  /** O2/O3 match: bond R8 = [lenderHash] / [.., poolNFT] / [.., .., hook]
    * sized by the covenant+hook shape, bond R9 = 6 elements card-less or
    * 10 with the resolved card suffix. Card rides as dataInputs(0) when
    * the order pins one; the lender (and hook) preimages ride ctx-ext
    * vars 0/1 on the ORDER input.
    *
    * O2 forces the covenant on, so its bond R8 is always size 2 or 3;
    * the size-1 shape is reachable only through O3 with thresholdBps 0.
    * Both are produced by the same shape rule — the ORDER is what
    * constrains which shapes exist per product. */
  def buildPackedMatch(ctx: BlockchainContext, family: Family, orderBox: InputBox,
                       lenderScriptBytes: Array[Byte], term: Int,
                       funder: ErgoProver,
                       card: Option[InputBox] = None,
                       dropDataInput: Boolean = false,
                       bondR8Override: Option[Seq[Array[Byte]]] = None,
                       bondSchedOverride: Option[Array[Long]] = None,
                       bondR5Override: Option[Array[Byte]] = None,
                       hookScriptBytes: Option[Array[Byte]] = None,
                       dropLenderVar: Boolean = false,
                       dropHookVar: Boolean = false,
                       lenderRevealOverride: Option[Array[Byte]] = None,
                       maturityOverride: Option[Int] = None,
                       preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    require(!family.isPlain, "buildPackedMatch: B1 matches use buildPlainMatch")
    requireDistinctTokens(orderBox,
      "order carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to match")
    val fAddr     = funder.getEip3Addresses.get(0)
    val rs        = orderBox.getRegisters
    val principal = rs.get(1).getValue.asInstanceOf[Long]
    val repayment = rs.get(2).getValue.asInstanceOf[Long]
    val bBytes    = P4.borrowerBytesOf(orderBox, 0)
    // A7: the term is the ORDER's — see buildPlainMatch.
    val orderTerm = rs.get(3).getValue.asInstanceOf[Int]
    require(term == orderTerm, s"term $term != order R7 $orderTerm")
    // Context height, not a fresh node query — see buildPlainMatch.
    val maturity  = maturityOverride.getOrElse(
      preHeaderHeight.getOrElse(ctx.getHeight) + orderTerm)
    val tmpl      = TestLib.schedOf(orderBox)
    val orderR8   = P4.packOf(orderBox, 4)
    // A6: the contracts define hookPresent as hookHash.size > 0, so an
    // R8 pack whose second slot is the EMPTY byte array pins no hook. A
    // size-only test writes a zero-length hook hash into the bond's R8
    // pack, and the bond's own liqDestOk then reads size >= 3 as "hooked"
    // against a hash nothing can preimage.
    val hook      = if (orderR8.size >= 2 && orderR8(1).nonEmpty) Some(orderR8(1)) else None

    val res = card.map(c => P4.resolve(c.getRegisters.get(3).getValue
      .asInstanceOf[sigma.Coll[Long]].toArray)).getOrElse(P4.COMPILED_DEFAULTS)
    val poolNft = card.map(c => P4.resolvePoolNft(P4.packOf(c, 4)))
      .getOrElse(ErgoId.create(POOL_NFT).getBytes)

    // A MALFORMED order (R9 shorter than 6) has no anchored grid to
    // derive, so there is nothing to build a bond R9 from — the caller
    // must say what to write. Every well-formed order takes the same
    // path it always did; this only stops the derivation from throwing
    // in SCALA before the CONTRACT ever gets a chance to reject the
    // shape, which is what the matchOk-ordering probes need to observe.
    require(tmpl.length >= 6 || bondSchedOverride.isDefined,
      s"buildPackedMatch: order R9 has ${tmpl.length} elements (< 6), so no bond schedule can " +
      "be derived from it — pass bondSchedOverride explicitly to probe this shape")
    val base  =
      if (tmpl.length >= 6)
        Array[Long](tmpl(0), tmpl(1), tmpl(2),
          (maturity - orderTerm).toLong + tmpl(1), tmpl(4), tmpl(5))
      else Array.empty[Long]
    // A3: carve-out and haircut are OUTER bounds a card may tighten and
    // never loosen. P4.resolve is the CARD's own resolution and does not
    // cap them; the order contract does
    // (CovenantBulletOrder.es:297-298 / InstalmentOrder.es:302-303), so
    // an uncapped suffix here builds a bond R9 the match refuses — or,
    // if a future contract ever stopped capping, a bond whose liquidator
    // may strip the whole collateral. Clamp to the compiled constants,
    // exactly as R5 does.
    val suffix = Array[Long](res.bounty, res.grace,
      math.min(res.carve, LIQ_CARVEOUT), math.min(res.haircut, HAIRCUT_KEEP))
    val sched = bondSchedOverride.getOrElse(
      if (card.isDefined) base ++ suffix else base)

    val covenantOn = tmpl.length >= 5 && tmpl(4) != 0L
    val lenderHash = P4.h32(lenderScriptBytes)
    val r8Pack = bondR8Override.getOrElse(
      (covenantOn, hook) match {
        case (true, Some(h)) => Seq(lenderHash, poolNft, h)
        case (true, None)    => Seq(lenderHash, poolNft)
        case (false, _)      => Seq(lenderHash)
      })

    val orderIn = packedOrderWithMatchVars(orderBox, lenderScriptBytes, hookScriptBytes,
      dropLenderVar, dropHookVar, lenderRevealOverride)
    val funds = Kit.selectBoxes(ctx, fAddr, principal + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val bondOut = tb.outBoxBuilder()
      .value(orderBox.getValue)
      .contract(family.bondContract)
      .tokens((new ErgoToken(orderBox.getId, 1L) +: orderBox.getTokens.asScala.toSeq): _*)
      .registers(
        ErgoValue.of(orderBox.getId.getBytes),
        ErgoValue.of(bondR5Override.getOrElse(P4.h32(bBytes))),
        ErgoValue.of(repayment),
        ErgoValue.of(maturity),
        P4.packValue(r8Pack),
        ErgoValue.of(sched)
      ).build()
    val principalOut = tb.outBoxBuilder()
      .value(principal)
      .contract(P4.contractFromBytes(bBytes))
      .build()
    var builder = tb.boxesToSpend((Seq(orderIn) ++ funds).asJava)
    if (card.isDefined && !dropDataInput)
      builder = builder.withDataInputs(java.util.Arrays.asList(card.get))
    builder.outputs(bondOut, principalOut)
      .fee(Kit.TX_FEE).sendChangeTo(fAddr).build()
  }

  /** Post an O2/O3 order on-chain from the borrower wallet: value =
    * collateral + the template's escrow field. NEVER EXECUTED in
    * workstream 1 — modeled on P4.postOrderV3.
    *
    * A4: when the order PINS a card, the card box id must be supplied
    * too. The escrow this order carries is checked by the order contract
    * against the CARD's resolved bounty, not against the compiled one,
    * so a pin taken on faith can park the borrower's collateral at an
    * address that will never match it. `cardPin` alone remains the
    * card-less / bytes-only path. */
  def postPackedOrder(familyOfCtx: BlockchainContext => Family,
                      tmpl: Array[Long],
                      collateral: Long = TestLib.COLLATERAL,
                      principal: Long = TestLib.PRINCIPAL,
                      repayment: Long = TestLib.REPAYMENT,
                      term: Int = TestLib.TERM_LONG,
                      collTokens: Seq[ErgoToken] = Nil,
                      cardPin: Array[Byte] = Array.emptyByteArray,
                      cardBoxId: Option[String] = None,
                      hookHash: Option[Array[Byte]] = None,
                      borrowerBytesOverride: Option[Array[Byte]] = None,
                      label: String = "post-order-rev5"): String =
    Kit.exec { ctx =>
      verifyFamily(ctx)
      val family = familyOfCtx(ctx)
      val b      = TestLib.borrower(ctx)
      val bAddr  = b.getEip3Addresses.get(0)
      val bBytes = borrowerBytesOverride.getOrElse(bAddr.toErgoContract.getErgoTree.bytes)
      val pinPack = hookHash match {
        case Some(h) => Seq(cardPin, h)
        case None    => Seq(cardPin)
      }
      // ---- A4: escrow/card cross-check, BEFORE any collateral moves ----
      require(cardPin.isEmpty || cardBoxId.isDefined,
        "postPackedOrder: this order pins a card, so the card box id is required — the escrow " +
        "must be cross-checked against the CARD's resolved bounty before the collateral is spent")
      val cardBox = cardBoxId.map(id => ctx.getBoxesById(id)(0))
      cardBox.foreach { c =>
        require(java.util.Arrays.equals(c.getTokens.get(0).getId.getBytes, cardPin),
          s"postPackedOrder: card box ${c.getId} carries NFT ${c.getTokens.get(0).getId} but the " +
          s"order pins ${TestLib.hex(cardPin)} — the order would resolve against a different card")
      }
      escrowCrossCheck(tmpl, term, cardBox.map(c =>
        c.getRegisters.get(3).getValue.asInstanceOf[sigma.Coll[Long]].toArray))
      // ------------------------------------------------------------------
      val outToks = mergeTokens(collTokens)   // A1
      val tokenBoxes =
        if (outToks.isEmpty) Nil
        else TestLib.boxesWithToken(ctx, bAddr, outToks.head.getId.toString)
      val tokenValue = tokenBoxes.map(_.getValue.toLong).sum
      val ergNeed    = collateral + tmpl(5) + Kit.TX_FEE + Kit.MIN_BOX_VALUE - tokenValue
      val ergBoxes   = if (ergNeed > 0) Kit.selectBoxes(ctx, bAddr, ergNeed) else Nil
      val inputs     = tokenBoxes ++ ergBoxes

      val tb = ctx.newTxBuilder()
      var ob = tb.outBoxBuilder()
        .value(collateral + tmpl(5))
        .contract(family.orderContract)
        .registers(
          ErgoValue.of(bBytes),
          ErgoValue.of(principal),
          ErgoValue.of(repayment),
          ErgoValue.of(term),
          P4.packValue(pinPack),
          ErgoValue.of(tmpl))
      if (outToks.nonEmpty) ob = ob.tokens(outToks: _*)
      val outs = ob.build() +: leftoverTokenBox(ctx, tb, inputs, outToks, bAddr).toSeq

      val unsigned = tb.boxesToSpend(inputs.asJava).outputs(outs: _*)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val signed  = b.sign(unsigned)
      val orderId = signed.getOutputsToSpend.get(0).getId.toString
      val txId    = Kit.sendSafe(ctx, signed, label)
      Kit.waitConfirmed(txId, label)
      println(s"  ${family.name} order box: $orderId (escrow ${tmpl(5)}, " +
        s"installment ${tmpl(0)}, threshold ${tmpl(4)}, pin ${if (cardPin.isEmpty) "none" else "set"})")
      orderId
    }

  /** Sign, submit, confirm an honest O2/O3 match. NEVER EXECUTED in
    * workstream 1. Returns (bondBoxId, maturity). */
  def doPackedMatch(familyOfCtx: BlockchainContext => Family,
                    orderBoxId: String, lenderScriptBytes: Array[Byte], term: Int,
                    cardBoxId: Option[String], jitLabel: String,
                    hookScriptBytes: Option[Array[Byte]] = None): (String, Int) =
    Kit.exec { ctx =>
      verifyFamily(ctx)
      val l        = TestLib.lender(ctx)
      val orderBox = ctx.getBoxesById(orderBoxId)(0)
      val card     = cardBoxId.map(id => ctx.getBoxesById(id)(0))
      val maturity = ctx.getHeight + term   // must match buildPlainMatch/buildPackedMatch
      val unsigned = buildPackedMatch(ctx, familyOfCtx(ctx), orderBox, lenderScriptBytes,
        term, l, card, hookScriptBytes = hookScriptBytes)
      Jit.record(jitLabel, l.reduce(unsigned, 0).getCost.toLong)
      val signed = l.sign(unsigned)
      val bondId = signed.getOutputsToSpend.get(0).getId.toString
      val txId   = Kit.sendSafe(ctx, signed, jitLabel)
      Kit.waitConfirmed(txId, jitLabel)
      println(s"  bond box: $bondId  maturity: $maturity")
      (bondId, maturity)
    }

  // ==================== exits (all three products) ====================

  /** Repay: OUTPUTS(0) pays >= repayment to the lender script with the
    * R4 == bond-id receipt and the loan token; every collateral token
    * returns to an output guarded by the borrower's own script; the
    * borrower co-spend is the authorization. Identical shape on B1, B2
    * and B3 — only B3 additionally gates it on sched(2) <= 1. */
  /** `collateralTo` overrides the collateral destination (the negatives'
    * knob). `dropCollateralReturn` omits the explicit return box, which
    * is NOT by itself a theft test: appkit's change box goes to the
    * funder, so a borrower-funded repay still lands the tokens on a
    * borrower-guarded output and the contract check passes. Route them to
    * a non-borrower address to actually test the conjunct. */
  def buildRepay(ctx: BlockchainContext, bondBox: InputBox,
                 lenderScriptBytes: Array[Byte], borrower: Address,
                 funder: ErgoProver,
                 exitValueOverride: Option[Long] = None,
                 receiptR4: Option[Array[Byte]] = None,
                 dropCollateralReturn: Boolean = false,
                 collateralTo: Option[Address] = None,
                 preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(bondBox,
      "bond carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to repay")
    val fAddr     = funder.getEip3Addresses.get(0)
    val repayment = bondBox.getRegisters.get(2).getValue.asInstanceOf[Long]
    val exitValue = exitValueOverride.getOrElse(repayment)
    val toks      = bondBox.getTokens.asScala.toSeq
    val collat    = toks.drop(1)
    // A2: the bond box is an INPUT — its own value funds the exit. Rev-4
    // semantics (TestLib.buildExit:235): ask the funder only for the
    // SHORTFALL, floored at one fee plus one min box so a fully-funded
    // repay still selects something to pay the fee and take change.
    // Without the credit a wallet that cannot cover the whole repayment
    // twice over fails selection on a repay it can actually afford.
    val need      = math.max(
      exitValue + Kit.TX_FEE + 2 * Kit.MIN_BOX_VALUE - bondBox.getValue,
      Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val funds     = Kit.selectBoxes(ctx, fAddr, need)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val exit = tb.outBoxBuilder()
      .value(exitValue)
      .contract(P4.contractFromBytes(lenderScriptBytes))
      .tokens(toks.head)
      .registers(ErgoValue.of(receiptR4.getOrElse(bondBox.getId.getBytes)))
      .build()
    val outs =
      if (collat.isEmpty || dropCollateralReturn) Seq(exit)
      else Seq(exit, tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
        .contract(collateralTo.getOrElse(borrower).toErgoContract).tokens(collat: _*).build())
    tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
      .outputs(outs: _*)
      .fee(Kit.TX_FEE).sendChangeTo(fAddr).build()
  }

  /** Liquidation past maturity: signatureless, bond sole input, the
    * carve-out funds fee + keeper box. `destScriptBytes` defaults to the
    * lender script; a HOOKED bond routes to the hook instead — use
    * P4.buildHookedLiquidation for that (it attaches the hook preimage as
    * ctx-ext var 0 ON THE BOND INPUT, which is a different site from the
    * order's var 0). */
  def buildLiquidate(ctx: BlockchainContext, bondBox: InputBox,
                     destScriptBytes: Array[Byte], payTo: Address,
                     carve: Long,
                     exitValueOverride: Option[Long] = None,
                     receiptR4: Option[Array[Byte]] = None,
                     preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(bondBox,
      "bond carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to liquidate")
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val exitValue = exitValueOverride.getOrElse(bondBox.getValue - carve)
    val exit = tb.outBoxBuilder()
      .value(exitValue)
      .contract(P4.contractFromBytes(destScriptBytes))
      .tokens(bondBox.getTokens.asScala.toSeq: _*)
      .registers(ErgoValue.of(receiptR4.getOrElse(bondBox.getId.getBytes)))
      .build()
    // A5: the keeper box is what is LEFT of the carve-out after the fee.
    // Appkit asserts on an under-min output with a bare arithmetic
    // message; name the cause instead.
    val residual = bondBox.getValue - exitValue - Kit.TX_FEE
    require(residual >= Kit.MIN_BOX_VALUE,
      s"liquidate: named shortfall: residual $residual < MIN_BOX_VALUE (${Kit.MIN_BOX_VALUE}) " +
      s"after fee; carve/bounty too small or box too thin (bond ${bondBox.getValue}, " +
      s"exit $exitValue, fee ${Kit.TX_FEE})")
    val keeper = tb.outBoxBuilder()
      .value(residual)
      .contract(payTo.toErgoContract)
      .build()
    tb.boxesToSpend(java.util.Arrays.asList(bondBox))
      .outputs(exit, keeper)
      .fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  /** Cancel: borrower-script CO-SPEND, no output may carry a token whose
    * id equals the order box id. Shape is identical across the three
    * order contracts (the auth input must not be guarded by the order
    * script itself). */
  def buildCancel(ctx: BlockchainContext, orderBox: InputBox, borrower: Address,
                  coSpend: Seq[InputBox]): UnsignedTransaction = {
    val tb   = ctx.newTxBuilder()
    val toks = orderBox.getTokens.asScala.toSeq
    val ergOut = orderBox.getValue - Kit.TX_FEE - (if (toks.nonEmpty) Kit.MIN_BOX_VALUE else 0L)
    // A5: same named-shortfall discipline as the liquidate keeper box.
    require(ergOut >= Kit.MIN_BOX_VALUE,
      s"cancel: named shortfall: residual $ergOut < MIN_BOX_VALUE (${Kit.MIN_BOX_VALUE}) " +
      s"after fee; carve/bounty too small or box too thin (order ${orderBox.getValue}, " +
      s"fee ${Kit.TX_FEE}${if (toks.nonEmpty) ", plus one min box for the token return" else ""})")
    var outs = Seq(tb.outBoxBuilder().value(ergOut).contract(borrower.toErgoContract).build())
    if (toks.nonEmpty)
      outs = outs :+ tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
        .contract(borrower.toErgoContract).tokens(toks: _*).build()
    tb.boxesToSpend((Seq(orderBox) ++ coSpend).asJava).outputs(outs: _*)
      .fee(Kit.TX_FEE).sendChangeTo(borrower).build()
  }

  /** Cancel an order on-chain (any product). NEVER EXECUTED in
    * workstream 1 — modeled on P4.cancelOrderV3. */
  def doCancel(orderId: String, label: String): Unit =
    Kit.exec { ctx =>
      verifyFamily(ctx)
      val b        = TestLib.borrower(ctx)
      val bAddr    = b.getEip3Addresses.get(0)
      val orderBox = ctx.getBoxesById(orderId)(0)
      val coSpend  = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val tx       = buildCancel(ctx, orderBox, bAddr, coSpend)
      val txId     = Kit.sendSafe(ctx, b.sign(tx), label)
      Kit.waitConfirmed(txId, label)
      println(s"  $label — order cancelled, funds recovered")
      ()
    }

  // ==================== B2 successor machinery: the crank ====================
  // B3's advance is the COUPON, which is product-agnostic in its builder:
  // reuse P4.honestCouponPlan / P4.buildCoupon (they read the R8 pack and
  // the R9 sched off the bond box and rebuild the successor at the box's
  // own script, so they retarget for free). Same for
  // P4.honestMissedAccelPlan / P4.buildMissedAccel (B3 only),
  // P4.buildHookedLiquidation (B2/B3) and P2.buildTopUp (B2/B3).

  /** Crank successor pack, healthy branch: checkpoint advances one
    * period, escrow drops one resolved bounty. B2 has no payments to
    * decrement — that is the whole difference from the coupon pack. */
  def crankAdvancePack(s: Array[Long]): Array[Long] = {
    val r9 = s.clone()
    r9(3) = s(3) + s(1)
    r9(5) = s(5) - P4.bountyOf(s)
    r9
  }

  /** Crank successor pack, unhealthy branch: the bond enters cure and
    * |nextCheck| is the grid-anchored deadline. */
  def crankCurePack(s: Array[Long]): Array[Long] = {
    val r9 = crankAdvancePack(s)
    r9(3) = -(s(3) + P4.graceOf(s))
    r9
  }

  /** Cure successor pack: back on the grid at
    * |nextCheck| - grace + period, escrow untouched. */
  def curePack(s: Array[Long]): Array[Long] = {
    val r9 = s.clone()
    r9(3) = (-s(3)) - P4.graceOf(s) + s(1)
    r9
  }

  /** Build a B2 crank: bond sole input (zero-capital keeper), pool as
    * dataInputs(0) when the covenant is on, successor at OUTPUTS(0) with
    * R4-R8 copied verbatim and the freed bounty paying fee + keeper box. */
  def buildCrank(ctx: BlockchainContext, bondBox: InputBox, succR9: Array[Long],
                 pool: Option[InputBox], payTo: Address,
                 succValueOverride: Option[Long] = None,
                 succTokensOverride: Option[Seq[ErgoToken]] = None,
                 preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(bondBox,
      "bond carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to crank")
    val s      = TestLib.schedOf(bondBox)
    val bounty = P4.bountyOf(s)
    val rs     = bondBox.getRegisters
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val succValue = succValueOverride.getOrElse(bondBox.getValue - bounty)
    val toks      = succTokensOverride.getOrElse(bondBox.getTokens.asScala.toSeq)
    var sb = tb.outBoxBuilder()
      .value(succValue)
      .contract(new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET))
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4), ErgoValue.of(succR9))
    if (toks.nonEmpty) sb = sb.tokens(toks: _*)
    // A5: the keeper box is the freed bounty less the fee.
    val residual = bondBox.getValue - succValue - Kit.TX_FEE
    require(residual >= Kit.MIN_BOX_VALUE,
      s"crank: named shortfall: residual $residual < MIN_BOX_VALUE (${Kit.MIN_BOX_VALUE}) " +
      s"after fee; carve/bounty too small or box too thin (bounty $bounty, fee ${Kit.TX_FEE})")
    val keeper = tb.outBoxBuilder()
      .value(residual)
      .contract(payTo.toErgoContract)
      .build()
    var builder = tb.boxesToSpend(java.util.Arrays.asList(bondBox))
    pool.foreach(p => builder = builder.withDataInputs(java.util.Arrays.asList(p)))
    builder.outputs(sb.build(), keeper)
      .fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  /** Build a cure (B2 and B3 share this shape): borrower co-spend adds
    * value, the pool proves post-cure health, the successor returns to
    * the grid with escrow untouched. */
  def buildCure(ctx: BlockchainContext, bondBox: InputBox, addValue: Long,
                pool: InputBox, funder: ErgoProver,
                succR9Override: Option[Array[Long]] = None,
                preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(bondBox,
      "bond carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to cure")
    val fAddr = funder.getEip3Addresses.get(0)
    val s     = TestLib.schedOf(bondBox)
    val rs    = bondBox.getRegisters
    val funds = Kit.selectBoxes(ctx, fAddr, math.max(addValue, 0L) + Kit.TX_FEE + Kit.MIN_BOX_VALUE)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val succ = tb.outBoxBuilder()
      .value(bondBox.getValue + addValue)
      .contract(new ErgoTreeContract(bondBox.getErgoTree, NetworkType.MAINNET))
      .tokens(bondBox.getTokens.asScala.toSeq: _*)
      .registers(rs.get(0), rs.get(1), rs.get(2), rs.get(3), rs.get(4),
        ErgoValue.of(succR9Override.getOrElse(curePack(s))))
      .build()
    tb.boxesToSpend((Seq(bondBox) ++ funds).asJava)
      .withDataInputs(java.util.Arrays.asList(pool))
      .outputs(succ)
      .fee(Kit.TX_FEE).sendChangeTo(fAddr).build()
  }

  /** Build a covenant acceleration (B2 and B3 share this shape): the cure
    * deadline has passed and the position prices unhealthy NOW.
    * Liquidation shape paying the PLAIN lender — the card-blessed hook
    * binds at maturity only, so a hooked bond still accelerates to the
    * lender hash. */
  def buildAccelerate(ctx: BlockchainContext, bondBox: InputBox,
                      lenderScriptBytes: Array[Byte], pool: InputBox,
                      payTo: Address,
                      exitValueOverride: Option[Long] = None,
                      preHeaderHeight: Option[Int] = None): UnsignedTransaction = {
    requireDistinctTokens(bondBox,
      "bond carries duplicate token-id entries — collateral would be strippable at " +
      "liquidation; refusing to accelerate")
    val s     = TestLib.schedOf(bondBox)
    val carve = P4.carveOf(s)
    val tb = preHeaderHeight match {
      case Some(h) => ctx.newTxBuilder().preHeader(ctx.createPreHeader().height(h).build())
      case None    => ctx.newTxBuilder()
    }
    val exitValue = exitValueOverride.getOrElse(bondBox.getValue - carve)
    val exit = tb.outBoxBuilder()
      .value(exitValue)
      .contract(P4.contractFromBytes(lenderScriptBytes))
      .tokens(bondBox.getTokens.asScala.toSeq: _*)
      .registers(ErgoValue.of(bondBox.getId.getBytes))
      .build()
    // A5: the keeper box is the carve-out less the fee.
    val residual = bondBox.getValue - exitValue - Kit.TX_FEE
    require(residual >= Kit.MIN_BOX_VALUE,
      s"accelerate: named shortfall: residual $residual < MIN_BOX_VALUE (${Kit.MIN_BOX_VALUE}) " +
      s"after fee; carve/bounty too small or box too thin (carve $carve, fee ${Kit.TX_FEE})")
    val keeper = tb.outBoxBuilder()
      .value(residual)
      .contract(payTo.toErgoContract)
      .build()
    tb.boxesToSpend(java.util.Arrays.asList(bondBox))
      .withDataInputs(java.util.Arrays.asList(pool))
      .outputs(exit, keeper)
      .fee(Kit.TX_FEE).sendChangeTo(payTo).build()
  }

  // ==================== shared plumbing ====================

  /** Token remainder rides its OWN min-value box so appkit's change stays
    * token-free (the Phase-2 welding lesson). */
  private def leftoverTokenBox(ctx: BlockchainContext, tb: UnsignedTransactionBuilder,
                               inputs: Seq[InputBox], outTokens: Seq[ErgoToken],
                               to: Address): Option[OutBox] = {
    val inTokens = inputs.flatMap(_.getTokens.asScala)
      .groupBy(_.getId.toString).values
      .map(ts => new ErgoToken(ts.head.getId, ts.map(_.getValue.toLong).sum)).toSeq
    val outMap = outTokens.groupBy(_.getId.toString).mapValues(_.map(_.getValue.toLong).sum)
    val leftover = inTokens.flatMap { t =>
      val rest = t.getValue.toLong - outMap.getOrElse(t.getId.toString, 0L)
      if (rest > 0) Some(new ErgoToken(t.getId, rest)) else None
    }
    if (leftover.isEmpty) None
    else Some(tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE)
      .contract(to.toErgoContract).tokens(leftover: _*).build())
  }
}
