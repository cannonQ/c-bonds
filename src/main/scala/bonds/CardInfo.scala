package bonds

import org.ergoplatform.appkit._

/** Human-readable decoder for a terms card (the read side of the open
  * card tooling — CONTRACT-DELTAS §0a assigns interpretation to open
  * tooling, the chain only stores). Fetches the live card box by its
  * NFT id and prints every field with its meaning, resolving sentinels
  * to the compiled defaults the order contract would apply.
  *
  *   sbt "runMain bonds.CardInfo <cardNftId>"
  */
object CardInfo {
  import Contracts._

  private def utf8(b: Array[Byte]): String = new String(b, "UTF-8")
  private def hex(b: Array[Byte]): String  = b.map("%02x".format(_)).mkString
  private def erg(n: Long): String         = f"${n / 1e9}%.4f ERG"

  private def sentinel(v: Long, dflt: Long, unit: Long => String): String =
    if (v == 0L) s"0  -> default (${unit(dflt)})" else unit(v)

  private def idName(id: String): String = id match {
    case POOL_NFT            => "Spectrum ERG/RSN pool NFT (the pinned price source)"
    case COLLATERAL_TOKEN_ID => "RSN (the pinned pool's traded token)"
    case _                   => "(custom)"
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: CardInfo <cardNftId>")
    val nft = args(0)
    Kit.exec { ctx =>
      val s  = Kit.httpGet(s"/blockchain/box/unspent/byTokenId/$nft?offset=0&limit=1")
      val id = """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(s)
        .map(_.group(1)).getOrElse(sys.error(s"no unspent box carries token $nft"))
      val box = ctx.getBoxesById(id)(0)
      val rs  = box.getRegisters
      val name = utf8(rs.get(0).getValue.asInstanceOf[sigma.Coll[Byte]].toArray)
      val desc = utf8(rs.get(1).getValue.asInstanceOf[sigma.Coll[Byte]].toArray)
      val n    = rs.get(3).getValue.asInstanceOf[sigma.Coll[Long]].toArray
      val ids  = P4.packOf(box, 4)
      val meta = P4.packOf(box, 5)

      println(s"card: '$name' - $desc")
      println(s"  box $id  (${erg(box.getValue)}, refuel-only: immutable forever, anyone may top up)")
      println(s"  NFT $nft")
      println()
      println("  R7 numeric pack (0 = inherit the compiled protocol default):")
      println(s"    crank bounty     ${sentinel(n(0), CRANK_BOUNTY, erg)}")
      println(s"    grace window     ${sentinel(n(1), GRACE_BLOCKS, v => s"$v blocks (~${v * 2} min)")}")
      println(s"    liq carve-out    ${sentinel(n(2), LIQ_CARVEOUT, erg)}")
      println(s"    haircut keep     ${sentinel(n(3), HAIRCUT_KEEP, v => s"$v/10000 (${(10000 - v) / 100.0}% haircut)")}")
      println(s"    threshold min    ${sentinel(n(4), 10000L, v => s"$v bps (${v / 100.0}% collateralization floor)")}")
      println(s"    threshold max    ${sentinel(n(5), 30000L, v => s"$v bps (${v / 100.0}%)")}")
      println(s"    min order value  ${sentinel(n(6), MIN_ORDER_VALUE, erg)}  (floor: card may raise, never lower)")
      println(s"    min period       ${sentinel(n(7), MIN_PERIOD, v => s"$v blocks")}  (floor)")
      println(s"    min coupon       ${sentinel(n(8), MIN_COUPON, erg)}  (floor)")
      println(s"    attestation type ${if (n(9) == 0L) "0  -> pool-price covenant (the only matchable type)" else s"${n(9)} (NOT matchable by the current order contract)"}")
      println(s"    flag word        ${if (n(10) == 0L) "0  -> no behaviour toggles (all reserved bits clear)" else s"0b${n(10).toBinaryString}"}")
      println()
      println("  R8 id fields (empty = compiled default):")
      val pool   = if (ids.head.isEmpty) s"(empty) -> $POOL_NFT" else hex(ids.head)
      val collat = if (ids(1).isEmpty) s"(empty) -> $COLLATERAL_TOKEN_ID" else hex(ids(1))
      println(s"    pool NFT       $pool")
      println(s"                   ${idName(if (ids.head.isEmpty) POOL_NFT else hex(ids.head))}")
      println(s"    collateral id  $collat")
      println(s"                   ${idName(if (ids(1).isEmpty) COLLATERAL_TOKEN_ID else hex(ids(1)))}")
      println()
      println("  R9 provenance (informational, never a protocol gate):")
      println(s"    publisher      ${hex(meta.head)} (ErgoTree bytes)")
      println(s"    version        ${utf8(meta(1))}")
      println(s"    predecessor    ${if (meta(2).isEmpty) "(none — root card)" else hex(meta(2))}")
      println()
      println("  usage: a borrower pins this NFT id in their order's R8 before")
      println("  match; the order contract copies these values into the bond.")
      ()
    }
  }
}
