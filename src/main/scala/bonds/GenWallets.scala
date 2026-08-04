package bonds

import org.ergoplatform.appkit._

/** Generate fresh DUST-ONLY test mnemonics into .env and print the EIP-3
  * index-0 addresses. Refuses to run unless .env is confirmed gitignored
  * (kickoff rule: verify ignore status before any secret is written).
  * Never overwrites an existing mnemonic and never prints one.
  */
object GenWallets {
  private val roles = Seq("BORROWER_MNEMONIC", "LENDER_MNEMONIC", "KEEPER_MNEMONIC")

  def main(args: Array[String]): Unit = {
    val envFile = new java.io.File(".env")

    val check = new ProcessBuilder("git", "check-ignore", ".env").directory(new java.io.File(".")).start()
    require(check.waitFor() == 0, ".env is NOT gitignored — refusing to write secrets")
    println(".env confirmed gitignored")

    if (!envFile.exists()) {
      val fw = new java.io.FileWriter(envFile)
      try fw.write("NODE_URL=http://127.0.0.1:9053\n") finally fw.close()
    }

    val existing = Env.all()
    val toGen = roles.filter(r => !existing.get(r).exists(_.nonEmpty))
    if (toGen.isEmpty) println("all mnemonics already present; nothing generated")
    else {
      val fw = new java.io.FileWriter(envFile, true)
      try toGen.foreach { r =>
        val m = Mnemonic.generateEnglishMnemonic()
        fw.write(s"$r=$m\n")
        println(s"generated $r (written to .env, not printed)")
      } finally fw.close()
    }

    Kit.client().execute { ctx =>
      println("\nAddresses (EIP-3 index 0):")
      roles.foreach { r =>
        val p = Kit.prover(ctx, Env.die(r))
        val a = p.getEip3Addresses.get(0)
        val status = Env.get(r.stripSuffix("_MNEMONIC") + "_EXPECTED_ADDRESS") match {
          case Some(expected) =>
            require(expected == a.toString, s"$r derived address $a does not match expected $expected")
            "verified"
          case None => "no expected address set"
        }
        println(f"  ${r.stripSuffix("_MNEMONIC")}%-10s $a  (${Kit.balance(a) / 1e9} ERG)  [$status]")
      }
      println("\nFund the BORROWER address with ~0.5 ERG of dust, then run:")
      println("  sbt \"runMain bonds.Fund\"   # distributes to lender + keeper")
      ()
    }
  }
}
