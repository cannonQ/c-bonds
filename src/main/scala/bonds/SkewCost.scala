package bonds

/** C3: the LP-manipulation cost table (kickoff §3, measured not asserted).
  *
  * Computes, from LIVE reserves of five real Spectrum N2T pools at graded
  * depths, what it costs to move the covenant's valuation of a collateral
  * token by a target percentage. Analytical from the constant-product
  * formulas — no liquidity is moved.
  *
  * Attack model (no flash loans on Ergo): to shift the pool price a
  * manipulator must hold a real position across at least one block —
  * buy the token with ERG before the checkpoint tx, sell it back after.
  * The floor cost of that round trip is two-sided slippage plus two pool
  * fees on the attacker's own size; being arbitraged during the hold only
  * adds to it. To RAISE the price by factor f, constant-product requires
  * ergIn ~= rX * (sqrt(f) - 1); the table computes the exact integer swap
  * legs both ways and reports the net loss.
  *
  * Output: printed table + SKEWCOST.md.
  */
object SkewCost {
  case class Pool(name: String, nft: String)
  val POOLS = Seq(
    Pool("ERG/SigUSD",  "9916d75132593c8b07fe18bd8d583bda1652eed7565cf41a4738ddd90fc992ec"),
    Pool("ERG/RSN",     Contracts.POOL_NFT),
    Pool("ERG/rsBTC",   "47a811c68e49f6bfa6629602037ee65f8d175ddbc7b64bdb65ad40599b812fd0"),
    Pool("ERG/Paideia", "666be5df835a48b99c40a395a8aa3ea6ce39ede2cd77c02921d629b9baad8200"),
    Pool("ERG/SigRSV",  "1d5afc59838920bb5ef2a8f9d63825a55b1d48e269d7cecee335d637c3ff5f3f"))
  val SHIFTS = Seq(0.01, 0.02, 0.05, 0.10)

  /** Exact integer swap: ergIn -> token out. */
  def buyOut(rX: Long, rY: Long, fee: Long, ergIn: Long): Long =
    ((BigInt(rY) * BigInt(ergIn) * BigInt(fee)) /
     (BigInt(rX) * BigInt(1000) + BigInt(ergIn) * BigInt(fee))).toLong

  /** Exact integer swap: token in -> erg out. */
  def sellOut(rX: Long, rY: Long, fee: Long, tokIn: Long): Long =
    ((BigInt(rX) * BigInt(tokIn) * BigInt(fee)) /
     (BigInt(rY) * BigInt(1000) + BigInt(tokIn) * BigInt(fee))).toLong

  def main(args: Array[String]): Unit = Kit.exec { ctx =>
    val sb = new StringBuilder
    sb.append("# C3 — LP-manipulation cost table (live reserves, analytical)\n\n")
    sb.append(s"Node height ${ctx.getHeight}, ${java.time.Instant.now()}. ")
    sb.append("Cost to shift a pool's covenant valuation UP by the target\n")
    sb.append("(borrower-side attack: buy before the checkpoint, sell back after —\n")
    sb.append("the no-flash-loan round trip). Round-trip loss = two-sided slippage\n")
    sb.append("+ 2x pool fee on the attacker's own size; arbitrage during the hold\n")
    sb.append("only adds. Lender-side (price-down) attacks are symmetric in token\n")
    sb.append("units. The covenant's own defenses stack on top: the HAIRCUT_KEEP\n")
    sb.append("2% haircut absorbs the first 2% of any skew, and thresholds are\n")
    sb.append("10-30% above water.\n\n")
    sb.append("| pool | depth (ERG) | fee | target shift | ERG in | round-trip cost (ERG) | cost % of position |\n")
    sb.append("|---|---|---|---|---|---|---|\n")

    POOLS.foreach { p =>
      val js = Kit.httpGet(s"/blockchain/box/unspent/byTokenId/${p.nft}?offset=0&limit=1")
      val rX = """"value"\s*:\s*(\d+)""".r.findFirstMatchIn(js).get.group(1).toLong
      val toks = """"tokenId"\s*:\s*"([0-9a-f]{64})"\s*,\s*"amount"\s*:\s*(\d+)""".r
        .findAllMatchIn(js).map(m => m.group(2).toLong).toSeq
      val rY  = toks(2)  // [NFT, LP, tokenY]
      val fee = {  // R4 raw serialized SInt, e.g. "04bc0f": zigzag varint after the 04 tag
        val hexR4 = """"R4"\s*:\s*"04([0-9a-f]+)"""".r.findFirstMatchIn(js)
          .getOrElse(sys.error(s"${p.name}: no Int R4 on pool box")).group(1)
        val bytes = hexR4.grouped(2).map(Integer.parseInt(_, 16)).toSeq
        var n = 0L; var shift = 0
        bytes.foreach { v => n |= (v & 0x7fL) << shift; shift += 7 }
        (n >>> 1) ^ -(n & 1)  // zigzag decode
      }
      SHIFTS.foreach { f =>
        // price factor target (1+f): ergIn = rX*(sqrt(1+f)-1), then exact legs
        val ergIn = (rX * (math.sqrt(1.0 + f) - 1.0)).toLong
        val got   = buyOut(rX, rY, fee, ergIn)
        val rX1   = rX + ergIn; val rY1 = rY - got
        val back  = sellOut(rX1, rY1, fee, got)
        val cost  = ergIn - back
        val shiftReal = (rX1.toDouble / rY1) / (rX.toDouble / rY) - 1.0
        sb.append(f"| ${p.name} | ${rX / 1e9}%,.0f | ${(1000 - fee) / 10.0}%.1f%% " +
          f"| ${shiftReal * 100}%.1f%% | ${ergIn / 1e9}%,.1f | ${cost / 1e9}%,.2f " +
          f"| ${cost * 100.0 / ergIn}%.2f%% |\n")
      }
    }
    val out = sb.toString
    println(out)
    val fw = new java.io.FileWriter("SKEWCOST.md")
    try fw.write(out) finally fw.close()
    println("written to SKEWCOST.md")
    ()
  }
}
