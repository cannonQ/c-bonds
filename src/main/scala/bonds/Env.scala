package bonds

import java.io.File

/** Repo-local .env loader. Real environment variables take precedence so a
  * shell override works, but nothing in this project ever sources
  * system-wide secret files.
  */
object Env {
  private def parse(f: File): Map[String, String] =
    if (!f.exists) Map.empty
    else {
      val src = scala.io.Source.fromFile(f)
      try {
        src.getLines().flatMap { line =>
          val t = line.trim
          if (t.isEmpty || t.startsWith("#")) None
          else {
            val i = t.indexOf('=')
            if (i > 0) Some(t.take(i).trim -> t.drop(i + 1).trim) else None
          }
        }.toMap
      } finally src.close()
    }

  /** Re-read on every call so GenWallets output is visible immediately. */
  def all(): Map[String, String] = parse(new File(".env"))

  def get(k: String): Option[String] =
    sys.env.get(k).filter(_.nonEmpty).orElse(all().get(k).filter(_.nonEmpty))

  def or(k: String, d: String): String = get(k).getOrElse(d)

  def die(k: String): String =
    get(k).getOrElse(sys.error(s".env is missing $k — run: sbt runMain bonds.GenWallets, or edit .env"))
}
