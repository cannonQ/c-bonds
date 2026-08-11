name := "c-bonds"
version := "0.1.0"
scalaVersion := "2.12.18"

libraryDependencies ++= Seq(
  "org.ergoplatform" %% "ergo-appkit"  % "6.0.0",
  "org.slf4j"         % "slf4j-jdk14"  % "1.7.36",
  "javax.xml.bind"    % "jaxb-api"     % "2.4.0-b180830.0359"
)

resolvers ++= Seq(
  "New Sonatype Releases" at "https://s01.oss.sonatype.org/content/repositories/releases/",
  "Sonatype Releases"     at "https://oss.sonatype.org/content/repositories/releases/",
  "Sonatype Snapshots"    at "https://oss.sonatype.org/content/repositories/snapshots/"
)

fork := true
javaOptions ++= Seq("-Xmx2g")
