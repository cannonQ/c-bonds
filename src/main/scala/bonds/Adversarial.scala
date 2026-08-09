package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoToken
import scala.collection.JavaConverters._

/** Phase 1 adversarial suite, numbered per conforming-bond-build-plan.md.
  *
  * Written from the spec, not from the contract source. Every negative
  * asserts the SPECIFIC failure. On the borrower-signed repay path that
  * is a clean reduce-to-false (expectScriptFalse); on the signatureless
  * liquidation path a malformed attempt is rejected either by
  * reduce-to-false or by an unprovable borrower-key residual — both mean
  * the attacker cannot spend (expectRejected). Anything else (builder or
  * type error) is a wrong-reason failure and fails the test. Where the
  * knob permits, the minimally-differing honest twin is proven to pass
  * before cleanup. Each test creates its own bond and recovers its dust.
  */

/** A1: repayment output to a script that differs from R8 by one byte. */
object A1_RepayScriptOneByte {
  def run(): Unit = {
    println("=== A1: repay to one-byte-off script ===")
    val vault   = TestLib.vaultTree()
    val variant = TestLib.vaultVariantTree()
    require(vault.bytes.length == variant.bytes.length &&
      vault.bytes.zip(variant.bytes).count { case (a, b) => a != b } == 1,
      "A1 precondition: variant tree must differ from vault tree by exactly one byte")
    println(s"  variant tree differs by exactly 1 byte of ${vault.bytes.length}")

    val (bondId, _) = TestLib.cycle(TestLib.TERM_LONG, vault)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val b       = TestLib.borrower(ctx)
      val bad     = TestLib.repayPlan(bondBox, vault).copy(exitTree = variant)
      Kit.expectScriptFalse("A1 repay to one-byte-off script") {
        b.sign(TestLib.buildExit(ctx, bondBox, bad, b))
      }
      ()
    }
    // pass-twin + cleanup: honest repay of the same bond
    TestLib.doExit(bondId, vault, asRepay = true, "A1-twin repay", TestLib.borrower)
    println("A1 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A2: repayment one nanoERG short. */
object A2_RepayOneNanoShort {
  def run(): Unit = {
    println("=== A2: repay one nanoERG short ===")
    val vault = TestLib.vaultTree()
    val (bondId, _) = TestLib.cycle(TestLib.TERM_LONG, vault)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val b       = TestLib.borrower(ctx)
      val plan    = TestLib.repayPlan(bondBox, vault)
      val bad     = plan.copy(exitValue = plan.exitValue - 1L)
      Kit.expectScriptFalse("A2 repay short by 1 nanoERG") {
        b.sign(TestLib.buildExit(ctx, bondBox, bad, b))
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = true, "A2-twin repay", TestLib.borrower)
    println("A2 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A3: liquidation attempted one block before the maturity window opens.
  * The boundary is pinned deterministically with an explicit pre-header:
  * HEIGHT == maturity - 1 must fail, HEIGHT == maturity must reduce
  * (twin). Real-chain cleanup liquidation follows once the window opens.
  */
object A3_LiquidateOneBlockEarly {
  def run(): Unit = {
    println("=== A3: liquidate one block before window opens ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_SHORT, vault)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val k       = TestLib.keeper(ctx)
      val plan    = TestLib.liquidationPlan(bondBox, vault)
      Kit.expectRejected("A3 liquidation at HEIGHT == maturity - 1") {
        k.sign(TestLib.buildExit(ctx, bondBox, plan, k, preHeaderHeight = Some(maturity - 1)))
      }
      Kit.expectReduces("A3-twin liquidation at HEIGHT == maturity") {
        k.reduce(TestLib.buildExit(ctx, bondBox, plan, k, preHeaderHeight = Some(maturity)), 0).getCost
      }
      ()
    }
    Kit.waitForHeight(maturity + 2)
    TestLib.doExit(bondId, vault, asRepay = false, "A3-cleanup liquidate", TestLib.keeper)
    println("A3 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A4: liquidation output splitting collateral between the lender script
  * and the attacker (exit underpaid beyond the carve-out; the difference
  * lands in the attacker's change).
  */
object A4_LiquidationSplit {
  def run(): Unit = {
    println("=== A4: liquidation splits collateral to attacker ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_SHORT, vault)
    Kit.waitForHeight(maturity + 2)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val k       = TestLib.keeper(ctx)
      val plan    = TestLib.liquidationPlan(bondBox, vault)
      val bad     = plan.copy(exitValue = plan.exitValue - Kit.MIN_BOX_VALUE)
      Kit.expectRejected("A4 liquidation withholding 0.001 ERG past the carve-out") {
        k.sign(TestLib.buildExit(ctx, bondBox, bad, k))
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = false, "A4-twin liquidate", TestLib.keeper)
    println("A4 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A5: token-collateral liquidation withholding part of the tokens while
  * the ERG value looks right. Also doubles as the token-collateral happy
  * path on cleanup.
  */
object A5_TokenWithhold {
  def run(): Unit = {
    println("=== A5: token-collateral liquidation withholds tokens ===")
    val vault   = TestLib.vaultTree()
    // Mint exactly the collateral amount so no leftover tokens get welded
    // onto the borrower's ERG change (that fragments spendable balance).
    val tokenId = TestLib.mintTestToken(500L)
    val coll    = Seq(new ErgoToken(tokenId, 500L))
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_SHORT, vault, collTokens = coll)
    Kit.waitForHeight(maturity + 2)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val k       = TestLib.keeper(ctx)
      val plan    = TestLib.liquidationPlan(bondBox, vault)
      val loan    = plan.tokens.head
      val bad     = plan.copy(tokens = Seq(loan, new ErgoToken(tokenId, 250L))) // half withheld
      Kit.expectRejected("A5 liquidation withholding 250 of 500 collateral tokens") {
        k.sign(TestLib.buildExit(ctx, bondBox, bad, k))
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = false, "A5-twin liquidate(token collateral)", TestLib.keeper)
    println("A5 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A6: bond minted outside the order contract. The forged box can satisfy
  * the bond script (the forger names themselves borrower and lender), but
  * its loan token id resolves to the forger's own input box, not to an
  * order-contract box: no order provenance, not a conforming loan.
  */
object A6_ForgedBondProvenance {
  def run(): Unit = {
    println("=== A6: forged bond has no order provenance ===")
    val orderT = TestLib.orderTree()

    val forgedTokenId = Kit.exec { ctx =>
      val k     = TestLib.keeper(ctx)
      val kAddr = k.getEip3Addresses.get(0)
      val (_, bondContract) = Contracts.bond(ctx)
      val funds = Kit.selectBoxes(ctx, kAddr, 3 * Kit.MIN_BOX_VALUE + Kit.TX_FEE)
      val tb    = ctx.newTxBuilder()
      val fake  = funds.head.getId // forged "loan token" id = forger's own input box
      val h     = Kit.nodeHeight()
      val kTree = kAddr.toErgoContract.getErgoTree
      val forged = tb.outBoxBuilder()
        .value(2 * Kit.MIN_BOX_VALUE)
        .contract(bondContract)
        .tokens(new ErgoToken(fake, 1L))
        .registers(
          ErgoValue.of(fake.getBytes),                  // R4 mimics an order id
          ErgoValue.of(kTree.bytes),                    // R5 forger as borrower (script bytes, rev 3)
          ErgoValue.of(1L),                             // R6 token repayment
          ErgoValue.of(h + 10000),                      // R7
          P4.packValue(Seq(kTree.bytes)),               // R8 forger as lender (pack, covenant-off)
          ErgoValue.of(Array[Long](0L, 0L, 0L, (h + 10000).toLong, 0L, 0L))
        ).build()
      val unsigned = tb.boxesToSpend(funds.asJava).outputs(forged)
        .fee(Kit.TX_FEE).sendChangeTo(kAddr).build()
      val signed = k.sign(unsigned)
      val txId   = Kit.sendSafe(ctx, signed, "forge-bond")
      Kit.waitConfirmed(txId, "forge-bond")
      val forgedBondId = signed.getOutputsToSpend.get(0).getId.toString
      println(s"  forged bond minted directly at the bond address: $forgedBondId")

      // cleanup: the forger "repays" themselves, recovering the dust —
      // proving the box is spendable per bond rules, which is exactly why
      // conforming status must come from provenance, not the script alone.
      val bondBox = signed.getOutputsToSpend.get(0)
      val plan    = TestLib.ExitPlan(kTree, Kit.MIN_BOX_VALUE,
        Some(bondBox.getId.getBytes), Seq(new ErgoToken(fake, 1L)))
      val cleanup = k.sign(TestLib.buildExit(ctx, bondBox, plan, k))
      val cleanId = Kit.sendSafe(ctx, cleanup, "forged-bond-selfrepay")
      Kit.waitConfirmed(cleanId, "forged-bond-selfrepay")
      fake.toString
    }

    require(!Provenance.isConforming(forgedTokenId, orderT),
      "A6: forged loan token WRONGLY passed the provenance check")
    println("  registry rule holds: forged token id does not resolve to the order address")
    println("A6 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A7: receipt omitted — exit output without R4 = SELF.id (absent register
  * and wrong-id variants). Exercised on the signatureless path so the
  * failure is attributable to the receipt rule alone.
  */
object A7_ReceiptOmitted {
  def run(): Unit = {
    println("=== A7: receipt omitted on exit ===")
    val vault = TestLib.vaultTree()
    val (bondId, maturity) = TestLib.cycle(TestLib.TERM_SHORT, vault)
    Kit.waitForHeight(maturity + 2)
    Kit.exec { ctx =>
      val bondBox = ctx.getBoxesById(bondId)(0)
      val k       = TestLib.keeper(ctx)
      val plan    = TestLib.liquidationPlan(bondBox, vault)
      Kit.expectScriptFalse("A7 liquidation with R4 receipt register absent") {
        k.sign(TestLib.buildExit(ctx, bondBox, plan.copy(receiptR4 = None), k))
      }
      Kit.expectScriptFalse("A7 liquidation with R4 referencing the wrong box id") {
        k.sign(TestLib.buildExit(ctx, bondBox, plan.copy(receiptR4 = Some(Array.fill[Byte](32)(0))), k))
      }
      ()
    }
    TestLib.doExit(bondId, vault, asRepay = false, "A7-twin liquidate", TestLib.keeper)
    println("A7 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A8 (EKB audit HIGH-O1): borrower cancels their own order while minting
  * a token whose id equals the order box id — a provenance-valid fake loan
  * token for a loan that never passed match validation. The cancel arm's
  * no-mint guard must reject it; a plain cancel (the minimally-differing
  * honest twin) must pass and recovers the collateral.
  */
object A8_CancelMintForgery {
  def run(): Unit = {
    println("=== A8: cancel-path loan-token mint forgery ===")
    val orderId = TestLib.postOrder(term = TestLib.TERM_LONG)
    Kit.exec { ctx =>
      val b        = TestLib.borrower(ctx)
      val bAddr    = b.getEip3Addresses.get(0)
      val orderBox = ctx.getBoxesById(orderId)(0)

      // Rev 3: cancel is authorized by borrower-script co-spend, so the
      // borrower's fee boxes ride along as inputs (order stays INPUTS(0)).
      val coSpend = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)

      // Fresh TxBuilder per transaction: appkit forbids calling
      // boxesToSpend twice on one builder ("inputs already specified").
      def cancelTx(withMint: Boolean) = {
        val tb = ctx.newTxBuilder()
        var ob = tb.outBoxBuilder()
          .value(orderBox.getValue - Kit.TX_FEE)
          .contract(bAddr.toErgoContract)
        if (withMint) ob = ob.tokens(new ErgoToken(orderBox.getId, 1L))
        tb.boxesToSpend((Seq(orderBox) ++ coSpend).asJava)
          .outputs(ob.build())
          .fee(Kit.TX_FEE)
          .sendChangeTo(bAddr)
          .build()
      }

      Kit.expectScriptFalse("A8 cancel minting token with id == order box id") {
        b.sign(cancelTx(withMint = true))
      }
      // pass-twin + cleanup: plain cancel recovers the collateral
      val signed = b.sign(cancelTx(withMint = false))
      val txId   = Kit.sendSafe(ctx, signed, "A8-twin plain cancel")
      Kit.waitConfirmed(txId, "A8-twin plain cancel")
      println("  PASS A8-twin — plain cancel confirmed, collateral recovered")
      ()
    }
    println("A8 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** A9 (EKB second-pass MED-O9): at match, the funder mints an EXTRA unit
  * of the loan token (id == order id) into their own output while the bond
  * still holds exactly 1. Ergo's mint rule permits the over-mint; matchOk's
  * loanTokenSupplyOne cap must reject it so the loan token stays a true
  * singleton identity. Cleanup: the order is untouched, so the borrower
  * cancels it to recover the collateral.
  */
object A9_LoanTokenOverMint {
  def run(): Unit = {
    println("=== A9: match mints extra loan-token supply ===")
    val vault   = TestLib.vaultTree()
    val orderId = TestLib.postOrder(term = TestLib.TERM_LONG)
    Kit.exec { ctx =>
      val l     = TestLib.lender(ctx); val lAddr = l.getEip3Addresses.get(0)
      val bAddr = TestLib.borrower(ctx).getEip3Addresses.get(0)
      val (_, bondContract) = Contracts.bond(ctx)
      val orderBox  = ctx.getBoxesById(orderId)(0)
      val principal = orderBox.getRegisters.get(1).getValue.asInstanceOf[Long]
      val repayment = orderBox.getRegisters.get(2).getValue.asInstanceOf[Long]
      val maturity  = Kit.nodeHeight() + TestLib.TERM_LONG
      val funds     = Kit.selectBoxes(ctx, lAddr, principal + 3 * Kit.MIN_BOX_VALUE + 2 * Kit.TX_FEE)
      val tb        = ctx.newTxBuilder()

      val tmpl = TestLib.schedOf(orderBox)
      val bondOut = tb.outBoxBuilder()
        .value(orderBox.getValue)
        .contract(bondContract)
        .tokens(new ErgoToken(orderBox.getId, 1L)) // bond holds exactly 1
        .registers(
          ErgoValue.of(orderBox.getId.getBytes), orderBox.getRegisters.get(0),
          ErgoValue.of(repayment), ErgoValue.of(maturity),
          P4.packValue(Seq(vault.bytes)),
          ErgoValue.of(Array[Long](tmpl(0), tmpl(1), tmpl(2),
            (maturity - TestLib.TERM_LONG).toLong + tmpl(1), tmpl(4), tmpl(5)))
        ).build()
      val principalOut = tb.outBoxBuilder().value(principal).contract(bAddr.toErgoContract).build()
      // the over-mint: a SECOND unit of the loan token to the funder
      val extraOut = tb.outBoxBuilder().value(Kit.MIN_BOX_VALUE).contract(lAddr.toErgoContract)
        .tokens(new ErgoToken(orderBox.getId, 1L)).build()

      val unsigned = tb.boxesToSpend((Seq(orderBox) ++ funds).asJava)
        .outputs(bondOut, principalOut, extraOut)
        .fee(Kit.TX_FEE).sendChangeTo(lAddr).build()
      Kit.expectRejected("A9 match minting a second loan-token unit") { l.sign(unsigned) }
      ()
    }
    // cleanup: order untouched, borrower cancels to recover collateral
    // (rev 3: borrower-script co-spend authorizes the cancel)
    Kit.exec { ctx =>
      val b        = TestLib.borrower(ctx); val bAddr = b.getEip3Addresses.get(0)
      val orderBox = ctx.getBoxesById(orderId)(0)
      val coSpend  = Kit.selectBoxes(ctx, bAddr, Kit.TX_FEE)
      val tb       = ctx.newTxBuilder()
      val out      = tb.outBoxBuilder().value(orderBox.getValue - Kit.TX_FEE).contract(bAddr.toErgoContract).build()
      val tx       = tb.boxesToSpend((Seq(orderBox) ++ coSpend).asJava).outputs(out)
        .fee(Kit.TX_FEE).sendChangeTo(bAddr).build()
      val txId = Kit.sendSafe(ctx, b.sign(tx), "A9-cleanup cancel")
      Kit.waitConfirmed(txId, "A9-cleanup cancel")
      println("  PASS A9-cleanup — order cancelled, collateral recovered")
      ()
    }
    println("A9 PASS")
  }
  def main(args: Array[String]): Unit = run()
}

/** Targeted origination re-verification for the MED-O9 hardening fix:
  * happy match still works (cap does not break matching) + A9 over-mint is
  * rejected. No liquidation paths (unaffected by the order change).
  */
object RunHardening {
  def main(args: Array[String]): Unit = {
    Kit.exec { ctx => TestLib.verifyWallets(ctx) }
    val steps: Seq[(String, () => Unit)] = Seq(
      "T1+T2 happy match & repay (cap intact)" -> { () => val id = T1_FundFromOrder.run(); T2_RepayToScript.run(Some(id)) },
      "A9 loan-token over-mint"                -> { () => A9_LoanTokenOverMint.run() }
    )
    val results = steps.map { case (name, f) =>
      val r = scala.util.Try(f())
      r.failed.foreach(e => println(s"FAIL $name: ${Kit.causeChain(e)}"))
      name -> r.isSuccess
    }
    println("\n=== Hardening re-verify summary ===")
    results.foreach { case (n, ok) => println(f"  ${if (ok) "PASS" else "FAIL"}  $n") }
    val failed = results.count(!_._2)
    println(s"${results.size - failed}/${results.size} passed")
    if (failed > 0) sys.exit(1)
  }
}

/** Full Phase 1 suite, in order, with a summary. */
object RunPhase1 {
  def main(args: Array[String]): Unit = {
    Kit.exec { ctx => TestLib.verifyWallets(ctx) }
    val steps: Seq[(String, () => Unit)] = Seq(
      "T1+T2 fund & repay" -> { () => val id = T1_FundFromOrder.run(); T2_RepayToScript.run(Some(id)) },
      "T3 liquidate"       -> { () => T3_LiquidatePastMaturity.run() },
      "A1 script byte"     -> { () => A1_RepayScriptOneByte.run() },
      "A2 short repay"     -> { () => A2_RepayOneNanoShort.run() },
      "A3 early liq"       -> { () => A3_LiquidateOneBlockEarly.run() },
      "A4 split liq"       -> { () => A4_LiquidationSplit.run() },
      "A5 token withhold"  -> { () => A5_TokenWithhold.run() },
      "A6 forged bond"     -> { () => A6_ForgedBondProvenance.run() },
      "A7 no receipt"      -> { () => A7_ReceiptOmitted.run() },
      "A8 cancel-mint"     -> { () => A8_CancelMintForgery.run() },
      "A9 loan over-mint"  -> { () => A9_LoanTokenOverMint.run() }
    )
    val results = steps.map { case (name, f) =>
      val r = scala.util.Try(f())
      r.failed.foreach(e => println(s"FAIL $name: ${Kit.causeChain(e)}"))
      name -> r.isSuccess
    }
    println("\n=== Phase 1 suite summary ===")
    results.foreach { case (n, ok) => println(f"  ${if (ok) "PASS" else "FAIL"}  $n") }
    val failed = results.count(!_._2)
    println(s"${results.size - failed}/${results.size} passed")
    if (failed > 0) sys.exit(1)
  }
}
