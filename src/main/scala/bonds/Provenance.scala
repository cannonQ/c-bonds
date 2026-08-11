package bonds

import org.ergoplatform.appkit._
import sigma.ast.ErgoTree

/** Registry-side conforming rule.
  *
  * A conforming loan is identified by its loan token: the token id must
  * resolve to a box at the conforming order contract address. Because a
  * token's id is always the id of the minting transaction's first input,
  * and the order contract requires itself to be INPUTS(0) at match, only
  * a genuine order spend can produce a loan token whose id is an
  * order-address box. A bond box forged directly at the bond address can
  * satisfy the bond script, but its token id resolves to the forger's own
  * input box — no order provenance, not a conforming loan.
  */
object Provenance {
  /** ErgoTree hex of the box the token id points at (extra-index lookup;
    * works for spent boxes, which the order box always is post-match).
    */
  private def ergoTreeHexOfBox(boxId: String): Option[String] =
    try {
      val s = Kit.httpGet(s"/blockchain/box/byId/$boxId")
      """"ergoTree"\s*:\s*"([0-9a-f]+)"""".r.findFirstMatchIn(s).map(_.group(1))
    } catch { case _: Throwable => None }

  def isConforming(loanTokenId: String, orderTree: ErgoTree): Boolean = {
    val orderHex = orderTree.bytes.map("%02x".format(_)).mkString
    ergoTreeHexOfBox(loanTokenId).contains(orderHex)
  }
}
