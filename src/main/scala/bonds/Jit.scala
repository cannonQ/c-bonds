package bonds

/** Append-only JitCost log. `prover.reduce()` costs are exact and use the
  * same units as the node's per-input budget; the node's DEBUG log is the
  * cross-check (scorex.logging.level = "DEBUG").
  */
object Jit {
  private val file = new java.io.File("JITCOST.md")

  def record(path: String, cost: Long): Unit = synchronized {
    if (!file.exists()) {
      val fw = new java.io.FileWriter(file)
      try fw.write(
        "# JitCost per path (Phase 1)\n\n" +
        "Measured with `prover.reduce(tx, 0).getCost` against mainnet inputs.\n\n" +
        "| path | JitCost | height | timestamp (UTC) |\n|---|---|---|---|\n"
      ) finally fw.close()
    }
    val fw = new java.io.FileWriter(file, true)
    try fw.write(s"| $path | $cost | ${Kit.nodeHeight()} | ${java.time.Instant.now()} |\n")
    finally fw.close()
    println(s"  JitCost[$path] = $cost")
  }
}
