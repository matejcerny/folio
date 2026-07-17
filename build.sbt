ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.matejcerny"
ThisBuild / organizationName := "Matej Cerny"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.MIT)

// === VERSIONS ===
val CatsV = "2.13.0"
val WeaverV = "0.12.0"
val SkunkV = "1.0.0"

// === MODULES ===
lazy val root = project
  .in(file("."))
  .aggregate(core, cats, skunk, integration, example)
  .settings(
    name := "folio",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "folio-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "weaver-cats" % WeaverV % Test,
      "org.typelevel" %% "weaver-scalacheck" % WeaverV % Test
    )
  )
  .settings(scaladoc *)

lazy val cats = project
  .in(file("module/effect/cats"))
  .dependsOn(core)
  .settings(
    name := "folio-cats",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % CatsV,
      "org.typelevel" %% "weaver-cats" % WeaverV % Test
    )
  )

lazy val skunk = project
  .in(file("module/database/skunk"))
  .dependsOn(cats)
  .settings(
    name := "folio-skunk",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % SkunkV,
      "org.typelevel" %% "weaver-cats" % WeaverV % Test,
      "org.typelevel" %% "weaver-scalacheck" % WeaverV % Test
    )
  )

lazy val integration = project
  .in(file("it"))
  .dependsOn(skunk, core % "test->test")
  .settings(
    name := "folio-it",
    publish / skip := true,
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "weaver-cats" % WeaverV % Test,
      "org.typelevel" %% "weaver-scalacheck" % WeaverV % Test
    )
  )

lazy val example = project
  .in(file("example"))
  .dependsOn(core, skunk)
  .settings(
    name := "folio-example",
    publish / skip := true,
    coverageEnabled := false
  )

// === SCALADOC ===
val scaladoc = Seq(
  // sbt-typelevel sets -project to the module name; replace with the top-level project name
  Compile / doc / scalacOptions ~= (_.map { case "folio-core" => "folio"; case other => other }),
  Compile / doc / scalacOptions ++= Seq(
    "-siteroot",
    ((ThisBuild / baseDirectory).value / "site").getAbsolutePath,
    "-social-links:github::https://github.com/matejcerny/folio",
    "-project-logo",
    "site/_assets/images/logo.png",
    "-project-footer",
    "Copyright Matej Cerny",
    "-versions-dictionary-url",
    "https://matejcerny.github.io/folio/versions.json",
    "-snippet-compiler:nocompile"
  ),
  Compile / doc := {
    val output = (Compile / doc).value
    val assetsDir = (ThisBuild / baseDirectory).value / "site" / "_assets"
    //      val favicon = assetsDir / "images" / "favicon.ico"
    //      if (favicon.exists()) IO.copyFile(favicon, output / "favicon.ico")
    val customCss = assetsDir / "css" / "custom.css"
    if (customCss.exists()) IO.copyFile(customCss, output / "styles" / "staticsitestyles.css")
    output
  }
)
