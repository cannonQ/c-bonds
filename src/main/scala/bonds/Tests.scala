package bonds

import org.ergoplatform.appkit._

/** Happy-path suite (Phase 1). Every assertion runs against confirmed
  * mainnet state, never against locally simulated boxes.
  */

/** T1: script-owned bond funded from an order.
  * Verifies bond anatomy on-chain (address, loan token, registers) and the
  * registry-side provenance rule on the freshly minted loan token.
  * Leaves the bond open; T2 repays it.
  */
object T1_FundFromOrder {
  def run(): String = {
    println("=== T1: fund from order (script lender) ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_LONG, vault)

    val s = Kit.httpGet(s"/blockchain/box/byId/$bondId")
    val bondTreeHex = Kit.exec { ctx => TestLib.hex(Contracts.bond(ctx)._1.bytes) }
    require(s.contains(bondTreeHex), "T1: bond box is not at the bond contract address")
    val loanTokenId = """"tokenId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(s).map(_.group(1))
      .getOrElse(sys.error("T1: no token on bond box"))
    require(s.contains("0e20" + loanTokenId), "T1: R4 does not reference the order box id")
    require(s.contains(TestLib.hex(vault.bytes)), "T1: R8 does not hold the vault script bytes")
    println(s"  bond anatomy verified on-chain (loan token $loanTokenId)")

    require(Provenance.isConforming(loanTokenId, TestLib.orderTree()),
      "T1: provenance check failed for a genuine order-minted loan token")
    println("  provenance: loan token resolves to the conforming order address")
    println(s"T1 PASS (bond $bondId, maturity $maturity)")
    bondId
  }
  def main(args: Array[String]): Unit = { run(); () }
}

/** T2: repayment to the lender script, receipt verified on-chain. */
object T2_RepayToScript {
  def run(existingBondId: Option[String]): Unit = {
    println("=== T2: repay to script lender ===")
    val vault  = TestLib.vaultTree()
    val bondId = existingBondId.getOrElse(TestLib.cycle(TestLib.TERM_LONG, vault)._1)
    TestLib.doExit(bondId, vault, asRepay = true, "repay(borrower-signed)", TestLib.borrower)
    println("T2 PASS")
  }
  def main(args: Array[String]): Unit = run(args.headOption)
}

/** T3: signatureless liquidation past maturity by a third key (keeper),
  * receipt verified on-chain.
  */
object T3_LiquidatePastMaturity {
  def run(): Unit = {
    println("=== T3: liquidate past maturity (third key) ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_SHORT, vault)
    Kit.waitForHeight(maturity + 2)
    TestLib.doExit(bondId, vault, asRepay = false, "liquidate(signatureless)", TestLib.keeper)
    println("T3 PASS")
  }
  def main(args: Array[String]): Unit = run()
}
