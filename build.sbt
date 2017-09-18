name := "ledger-wallet-daemon"
version := "0.1-SNAPSHOT"

lazy val commonSettings = Seq(
  organization := "co.ledger",
  scalaVersion := "2.12.2"
)

lazy val root = (project in file("."))
  .aggregate(binding, daemon)

lazy val binding = (project in file("ledger-core-binding"))
    .settings(commonSettings)

lazy val daemon = (project in file("daemon"))
  .dependsOn(binding)
  .settings(commonSettings)