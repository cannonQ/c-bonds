package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.ErgoTreeContract
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

  /** Escrow a conforming order must carry: one bounty per interior
    * checkpoint, K = (term - 1) / period exactly (mirrors schedOk). */
  def escrowFor(term: Int, period: Long): Long =
    CRANK_BOUNTY * ((term.toLong - 1L) / period)

  def bond(ctx: BlockchainContext): (ErgoTree, ErgoTreeContract) =
    Kit.compile(ctx, Kit.readContract("ConformingBond.es"),
      ConstantsBuilder.create()
        .item("LIQ_CARVEOUT", LIQ_CARVEOUT)
        .item("CRANK_BOUNTY", CRANK_BOUNTY)
        .build())

  def order(ctx: BlockchainContext): (ErgoTree, ErgoTreeContract) = {
    val (bondTree, _) = bond(ctx)
    Kit.compile(ctx, Kit.readContract("ConformingOrder.es"),
      ConstantsBuilder.create()
        .item("BOND_SCRIPT_HASH", scorex.crypto.hash.Blake2b256(bondTree.bytes))
        .item("MATURITY_TOL", MATURITY_TOL)
        .item("MIN_ORDER_VALUE", MIN_ORDER_VALUE)
        .item("CRANK_BOUNTY", CRANK_BOUNTY)
        .item("MIN_PERIOD", MIN_PERIOD)
        .build())
  }

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
      println("=== c-bonds Phase 2 contracts (mainnet) ===")
      println(s"node height: ${ctx.getHeight}")
      println(f"bond  tree: ${bondTree.bytes.length}%5d bytes  header=0x${bondTree.header}%02x")
      println(s"bond  address: ${Address.fromErgoTree(bondTree, NetworkType.MAINNET)}")
      println(f"order tree: ${orderTree.bytes.length}%5d bytes  header=0x${orderTree.header}%02x")
      println(s"order address: ${Address.fromErgoTree(orderTree, NetworkType.MAINNET)}")
      require((bondTree.header & 0x08).toByte != 0.toByte, "bond tree missing size bit")
      require((orderTree.header & 0x08).toByte != 0.toByte, "order tree missing size bit")
      require(bondTree.bytes.length <= 3600, s"bond tree ${bondTree.bytes.length}B exceeds 3600B planning target")
      require(orderTree.bytes.length <= 3600, s"order tree ${orderTree.bytes.length}B exceeds 3600B planning target")
      println("size bit set on both trees; both under the 3600B planning target")
      ()
    }
  }
}
