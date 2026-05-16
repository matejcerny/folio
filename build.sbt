ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.matejcerny"
ThisBuild / organizationName := "Matej Cerny"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.MIT)

lazy val root = project
  .in(file("."))
  .aggregate(core, example)
  .settings(
    name := "folio",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "folio-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "weaver-cats" % "0.12.0" % Test
    )
  )

lazy val example = project
  .in(file("example"))
  .dependsOn(core)
  .settings(
    name := "folio-example",
    publish / skip := true
  )
