package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.sdk.ErgoId
import sigmastate.Values.ErgoTree
import sigmastate.crypto.DLogProtocol.ProveDlog

/** Contract compilation with the compiled-constant wiring. The order
  * contract pins the bond contract one-way by blake2b256 of its sized tree
  * (ePerps cross-contract rule: hash, never embed full trees).
  */
object Contracts {
  /** Keeper fee + tx fee carve-out permitted on liquidation. */
  val LIQ_CARVEOUT: Long = 3000000L
  /** Build-to-validation lag tolerance on stamped maturity (blocks). */
  val MATURITY_TOL: Int = 5
  /** Minimum order collateral NET OF ESCROW (EKB LOW-O3): keeps the bond's
    * liquidation floor (value - LIQ_CARVEOUT) binding for every conforming
    * bond, and stops dust collateral hiding behind a fat escrow. */
  val MIN_ORDER_VALUE: Long = 10000000L
  /** Per-crank bounty, 0.005 ERG: fee (0.0011) + a keeper box above
    * min-box, so a crank is self-funding with the bond as its only input
    * (zero-capital keeper). Sizing is a W4 economics item — Phase 2
    * placeholder, pinned 2026-08-03. */
  val CRANK_BOUNTY: Long = 5000000L
  /** Floor on the checkpoint period (blocks). */
  val MIN_PERIOD: Long = 4L
  /** Canonical vault knob; A1 compiles a one-byte variant with 2. */
  val MIN_OUTS_CANONICAL: Int = 1

  // ---- Phase 3: covenant constants (pinned 2026-08-04) ----
  /** Spectrum N2T ERG/RSN pool NFT — the pinned price source. Chosen for
    * depth (~208K ERG, 2nd deepest), token granularity (RSN dec 3, raw
    * unit ~0.00003 ERG — SigUSD's 1-cent unit makes dust collateral
    * impossible) and ~5x lower churn than ERG/SigUSD (data-input
    * liveness). Fee numerator is read from the pool's R4 at runtime —
    * it DIFFERS across pools (this pool 990, SigUSD 995). */
  val POOL_NFT: String =
    "cadac6db847a715e3577d8f2fbb2edfb2280f20924abf51bf83704a9ddc511b2"
  /** The pinned pool's traded token (RSN): the only collateral token id a
    * covenant order (threshold > 0) may carry — pinned at origination so
    * no conforming covenant bond can exist with unpriceable collateral. */
  val COLLATERAL_TOKEN_ID: String =
    "8b08cdd5449a9592a9e79711d7d79249d7a03c535d17efaee83e216e80a44c4b"
  /** Cure window in blocks after a failed checkpoint (deadline =
    * checkpoint + GRACE_BLOCKS, grid-anchored). Test-scale sizing (~20
    * min); real sizing is a W4 economics item like CRANK_BOUNTY. */
  val GRACE_BLOCKS: Long = 10L
  /** Slippage/execution haircut on the swap-simulated token leg only
    * (the ERG leg goes to the lender at par). Duckpools-precedent 2%.
    * The contract takes the keep-side factor. */
  val HAIRCUT_BPS: Long = 200L
  val HAIRCUT_KEEP: Long = 10000L - HAIRCUT_BPS

  // ---- Phase 4 / rev 3 (pinned 2026-08-04, layout 2026-08-07) ----
  /** Order-side floor on a nonzero installment (decision 6): keeps every
    * coupon output clear of min-box-value. Test-scale; W4 re-sizes. */
  val MIN_COUPON: Long = 5000000L

  private def hexBytes(s: String): Array[Byte] = ErgoId.create(s).getBytes

  /** Escrow a conforming order must carry: one bounty per interior
    * checkpoint, K = (term - 1) / period exactly (mirrors schedOk). */
  def escrowFor(term: Int, period: Long): Long =
    CRANK_BOUNTY * ((term.toLong - 1L) / period)

  /** Rev 3: POOL_NFT leaves the bond (read from its own R8(1) behind a
    * size guard); the four card numerics stay compiled as the R9-suffix
    * fallback defaults — the compiled constant IS the type-0 default card.
    */
  def bond(ctx: BlockchainContext): (ErgoTree, ErgoTreeContract) =
    Kit.compile(ctx, Kit.readContract("ConformingBond.es"),
      ConstantsBuilder.create()
        .item("LIQ_CARVEOUT", LIQ_CARVEOUT)
        .item("CRANK_BOUNTY", CRANK_BOUNTY)
        .item("GRACE_BLOCKS", GRACE_BLOCKS)
        .item("HAIRCUT_KEEP", HAIRCUT_KEEP)
        .build())

  /** Rev 3: the order gains the sentinel-resolution defaults (the card
    * numerics + POOL_NFT) and MIN_COUPON; the compiled floors stay as
    * protocol floors a card may raise, never lower. */
  def order(ctx: BlockchainContext): (ErgoTree, ErgoTreeContract) = {
    val (bondTree, _) = bond(ctx)
    Kit.compile(ctx, Kit.readContract("ConformingOrder.es"),
      ConstantsBuilder.create()
        .item("BOND_SCRIPT_HASH", scorex.crypto.hash.Blake2b256(bondTree.bytes))
        .item("MATURITY_TOL", MATURITY_TOL)
        .item("MIN_ORDER_VALUE", MIN_ORDER_VALUE)
        .item("CRANK_BOUNTY", CRANK_BOUNTY)
        .item("MIN_PERIOD", MIN_PERIOD)
        .item("MIN_COUPON", MIN_COUPON)
        .item("GRACE_BLOCKS", GRACE_BLOCKS)
        .item("LIQ_CARVEOUT", LIQ_CARVEOUT)
        .item("HAIRCUT_KEEP", HAIRCUT_KEEP)
        .item("POOL_NFT", hexBytes(POOL_NFT))
        .item("COLLATERAL_TOKEN_ID", hexBytes(COLLATERAL_TOKEN_ID))
        .build())
  }

  /** The card contract (rev 3, new): no compiled constants — the refuel
    * guard is pure structure. */
  def termsBox(ctx: BlockchainContext): (ErgoTree, ErgoTreeContract) =
    Kit.compile(ctx, Kit.readContract("TermsBox.es"), ConstantsBuilder.empty())

  def vault(ctx: BlockchainContext, ownerPk: ProveDlog, minOuts: Int = MIN_OUTS_CANONICAL): (ErgoTree, ErgoTreeContract) =
    Kit.compile(ctx, Kit.readContract("MinimalLenderVault.es"),
      ConstantsBuilder.create()
        .item("MIN_OUTS", minOuts)
        .item("OWNER_PK", ownerPk)
        .build())

  /** Compile everything against the live node and print addresses + sizes.
    * This is the Phase-0-style gate: run before spending a single nanoERG.
    */
  def main(args: Array[String]): Unit = {
    Kit.client().execute { ctx =>
      val (bondTree, _)  = bond(ctx)
      val (orderTree, _) = order(ctx)
      val (cardTree, _)  = termsBox(ctx)
      println("=== c-bonds rev-3 contracts (mainnet) ===")
      println(s"node height: ${ctx.getHeight}")
      println(f"bond  tree: ${bondTree.bytes.length}%5d bytes  header=0x${bondTree.header}%02x")
      println(s"bond  address: ${Address.fromErgoTree(bondTree, NetworkType.MAINNET)}")
      println(f"order tree: ${orderTree.bytes.length}%5d bytes  header=0x${orderTree.header}%02x")
      println(s"order address: ${Address.fromErgoTree(orderTree, NetworkType.MAINNET)}")
      println(f"card  tree: ${cardTree.bytes.length}%5d bytes  header=0x${cardTree.header}%02x")
      println(s"card  address: ${Address.fromErgoTree(cardTree, NetworkType.MAINNET)}")
      Seq(("bond", bondTree), ("order", orderTree), ("card", cardTree)).foreach { case (n, t) =>
        require((t.header & 0x08).toByte != 0.toByte, s"$n tree missing size bit")
        require(t.bytes.length <= 3600, s"$n tree ${t.bytes.length}B exceeds 3600B planning target")
      }
      println("size bit set on all three trees; all under the 3600B planning target")
      ()
    }
  }
}
