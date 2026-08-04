package bonds

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.ErgoTreeContract
import sigmastate.Values.ErgoTree

/** Shared harness plumbing: node client, size-bit fix, provers, node HTTP
  * helpers (public endpoints only), confirmation waits, and the
  * negative-test assertion that demands the SPECIFIC failure.
  */
object Kit {
  val NODE_URL: String     = Env.or("NODE_URL", "http://127.0.0.1:9053")
  val EXPLORER_URL: String = Env.or("EXPLORER_URL", RestApiErgoClient.getDefaultExplorerUrl(NetworkType.MAINNET))
  val API_KEY: String      = Env.or("NODE_API_KEY", "")

  val TX_FEE: Long        = 1100000L
  val MIN_BOX_VALUE: Long = 1000000L

  def client(): ErgoClient =
    RestApiErgoClient.create(NODE_URL, NetworkType.MAINNET, API_KEY, EXPLORER_URL)

  /** Typed execute — Scala's SAM inference on ErgoClient.execute yields Any. */
  def exec[T](f: BlockchainContext => T): T =
    client().execute(new java.util.function.Function[BlockchainContext, T] {
      def apply(ctx: BlockchainContext): T = f(ctx)
    })

  /** Mainnet size-bit fix: set bit 3 of the tree header on every compiled
    * tree. Applied to every tree we deploy or hash, so on-chain
    * propositionBytes always equal these bytes.
    */
  def sized(tree: ErgoTree): ErgoTree =
    if ((tree.header & 0x08).toByte != 0.toByte) tree
    else new ErgoTree((tree.header | 0x08).toByte, tree.constants, tree.root)

  def compile(ctx: BlockchainContext, source: String, constants: Constants): (ErgoTree, ErgoTreeContract) = {
    val raw = ctx.compileContract(constants, source).getErgoTree
    val t   = sized(raw)
    (t, new ErgoTreeContract(t, NetworkType.MAINNET))
  }

  def readContract(name: String): String = {
    val src = scala.io.Source.fromFile(s"contracts/$name")
    try src.mkString finally src.close()
  }

  def prover(ctx: BlockchainContext, mnemonic: String): ErgoProver =
    ctx.newProverBuilder()
      .withMnemonic(Mnemonic.create(mnemonic.toCharArray, Array.emptyCharArray), false)
      .withEip3Secret(0)
      .build()

  // ---------------- node HTTP (public endpoints only) ----------------

  private def readBody(c: java.net.HttpURLConnection): (Int, String) = {
    val code = c.getResponseCode
    val is   = if (code >= 400) c.getErrorStream else c.getInputStream
    val body = if (is == null) "" else {
      val s = scala.io.Source.fromInputStream(is)
      try s.mkString finally s.close()
    }
    (code, body)
  }

  def httpGet(path: String): String = {
    val c = new java.net.URL(NODE_URL + path).openConnection().asInstanceOf[java.net.HttpURLConnection]
    c.setConnectTimeout(10000); c.setReadTimeout(30000)
    val (code, body) = readBody(c)
    if (code >= 400) throw new RuntimeException(s"GET $path -> $code: ${body.take(300)}")
    body
  }

  def httpPost(path: String, jsonBody: String): String = {
    val c = new java.net.URL(NODE_URL + path).openConnection().asInstanceOf[java.net.HttpURLConnection]
    c.setConnectTimeout(10000); c.setReadTimeout(30000)
    c.setRequestMethod("POST")
    c.setRequestProperty("Content-Type", "application/json")
    c.setDoOutput(true)
    val os = c.getOutputStream
    try { os.write(jsonBody.getBytes("UTF-8")); os.flush() } finally os.close()
    val (code, body) = readBody(c)
    if (code >= 400) throw new RuntimeException(s"POST $path -> $code: ${body.take(300)}")
    body
  }

  def nodeHeight(): Int = {
    val s = httpGet("/blocks/lastHeaders/1")
    """"height"\s*:\s*(\d+)""".r.findFirstMatchIn(s).map(_.group(1).toInt)
      .getOrElse(sys.error("no height in /blocks/lastHeaders/1 response"))
  }

  /** Confirmed-transaction check via the extra-index. */
  def txConfirmed(txId: String): Boolean =
    try {
      val s = httpGet(s"/blockchain/transaction/byId/$txId")
      """"inclusionHeight"\s*:\s*(\d+)""".r.findFirstMatchIn(s).isDefined
    } catch { case _: Throwable => false }

  def waitConfirmed(txId: String, label: String = "tx", maxTries: Int = 80): Unit = {
    var i = 0
    while (i < maxTries) {
      if (txConfirmed(txId)) { println(s"  confirmed: $label $txId"); return }
      Thread.sleep(15000); i += 1
      if (i % 4 == 0) println(s"  waiting on $label $txId ($i/$maxTries)")
    }
    sys.error(s"timeout waiting for confirmation of $label $txId")
  }

  def waitForHeight(h: Int): Unit = {
    var cur = nodeHeight()
    while (cur < h) {
      println(s"  height $cur, waiting for $h (~${(h - cur) * 2} min)")
      Thread.sleep(30000)
      cur = nodeHeight()
    }
  }

  /** Unspent box ids for an address, straight from the local node's extra
    * index (no explorer lag).
    */
  def unspentBoxIds(address: String, limit: Int = 100): Seq[String] = {
    val s = httpPost(s"/blockchain/box/unspent/byAddress?offset=0&limit=$limit", "\"" + address + "\"")
    """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findAllMatchIn(s).map(_.group(1)).toSeq
  }

  /** Load spendable P2PK boxes for an address until `need` nanoERG is
    * covered. tokenFree excludes token-carrying boxes so fee/funding inputs
    * can never burn or smuggle tokens (dexy-bots lesson).
    */
  def selectBoxes(ctx: BlockchainContext, address: Address, need: Long, tokenFree: Boolean = true): Seq[InputBox] = {
    val ids = unspentBoxIds(address.toString)
    require(ids.nonEmpty, s"no unspent boxes at $address — is it funded?")
    val boxes = ctx.getBoxesById(ids: _*).toSeq
      .filter(b => !tokenFree || b.getTokens.isEmpty)
      .sortBy(-_.getValue)
    var acc = 0L
    val picked = boxes.takeWhile { b => val take = acc < need; acc += b.getValue; take }
    require(acc >= need, s"insufficient funds at $address: have $acc, need $need")
    picked
  }

  def balance(address: Address): Long = {
    val s = httpPost("/blockchain/balance", "\"" + address.toString + "\"")
    """"nanoErgs"\s*:\s*(\d+)""".r.findFirstMatchIn(s).map(_.group(1).toLong).getOrElse(0L)
  }

  def sendSafe(ctx: BlockchainContext, tx: SignedTransaction, label: String): String =
    try {
      val id = ctx.sendTransaction(tx).replace("\"", "")
      println(s"  submitted $label: $id")
      id
    } catch {
      case e: Exception if e.getMessage != null && e.getMessage.toLowerCase.contains("mempool") =>
        println(s"  $label already in mempool: ${tx.getId}")
        tx.getId
    }

  // ---------------- assertions ----------------

  def causeChain(e: Throwable): String = {
    var t = e; val sb = new StringBuilder
    var depth = 0
    while (t != null && depth < 10) { sb.append(t.toString).append(" | "); t = t.getCause; depth += 1 }
    sb.toString
  }

  /** Negative-test assertion: the action must fail with a clean script
    * reduce-to-false — the specific failure — not a crash, type error, or
    * builder error. A transaction that fails for the wrong reason is a bug
    * that passed (build-plan standing rule).
    */
  def expectScriptFalse(label: String)(f: => Any): Unit = {
    scala.util.Try(f) match {
      case scala.util.Success(_) =>
        sys.error(s"[$label] EXPECTED script rejection but the action SUCCEEDED")
      case scala.util.Failure(e) =>
        val msg = causeChain(e)
        val clean = msg.contains("educed to false") || msg.contains("ReducedToFalse")
        if (!clean) sys.error(s"[$label] failed for the WRONG reason (wanted reduce-to-false): $msg")
        println(s"  PASS $label — clean script rejection")
    }
  }

  /** A prover holding no secrets — models any anonymous cranker. It can
    * satisfy a signatureless path (residual reduces to true) but nothing
    * that requires a signature. Used for liquidation-guard negatives so the
    * assertion is "no anonymous party can execute this malformed spend."
    */
  def noSecretProver(ctx: BlockchainContext): ErgoProver =
    ctx.newProverBuilder().build()

  /** Negative-test assertion for the SIGNATURELESS liquidation path.
    *
    * A malformed liquidation is rejected in one of two legitimate ways,
    * both meaning "the attacker cannot spend the box this way":
    *   - reduce-to-false: both branches of the contract are false; or
    *   - unprovable residual (UnprovenSchnorr / "Tree root should be real"):
    *     the malformed liquidation output coincidentally satisfies the
    *     repay-branch SHAPE, so the reduced proposition is the borrower's
    *     key — which a non-borrower attacker cannot sign.
    * Both are the contract correctly refusing. Anything else (builder
    * error, type error, index error) is failing for the WRONG reason and
    * is itself a bug — asserted against, per the build-plan standing rule.
    * If the attacker's prover SUCCEEDS in signing, that is a real spend and
    * the assertion fails loudly.
    */
  def expectRejected(label: String)(f: => Any): Unit = {
    scala.util.Try(f) match {
      case scala.util.Success(_) =>
        sys.error(s"[$label] EXPECTED rejection but the attacker SPENT the box")
      case scala.util.Failure(e) =>
        val msg = causeChain(e)
        val reduceFalse = msg.contains("educed to false") || msg.contains("ReducedToFalse")
        val unprovable  = msg.contains("Tree root should be real") || msg.contains("UnprovenSchnorr")
        if (!(reduceFalse || unprovable))
          sys.error(s"[$label] failed for the WRONG reason (wanted reduce-to-false or unprovable residual): $msg")
        val mode = if (reduceFalse) "reduce-to-false" else "signatureless path unavailable"
        println(s"  PASS $label — $mode")
    }
  }

  /** Pass-twin assertion: the minimally-differing honest version of a
    * negative test must reduce successfully (parity proof, ePerps rule).
    */
  def expectReduces(label: String)(cost: => Int): Int = {
    val c = cost
    println(s"  PASS $label — reduces, JitCost $c")
    c
  }
}
