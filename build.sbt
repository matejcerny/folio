ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "io.github.matejcerny"
ThisBuild / organizationName := "Matej Cerny"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.MIT)
// Scala Native 0.5.12 vs older test-interface pulled by scalacheck/weaver — safe for pre-alpha.
ThisBuild / evictionErrorLevel := Level.Warn

val Scala3 = "3.3.8"

// === MODULES ===
lazy val jsNativeSettings = Seq(
  coverageEnabled := false
)

lazy val root = project
  .in(file("."))
  .aggregate(
    core.jvm(Scala3),
    core.js(Scala3),
    core.native(Scala3),
    cats.jvm(Scala3),
    cats.js(Scala3),
    cats.native(Scala3),
    skunk.jvm(Scala3),
    skunk.js(Scala3),
    skunk.native(Scala3),
    integration.jvm(Scala3),
    integration.native(Scala3),
    example
  )
  .settings(
    name := "folio",
    publish / skip := true
  )

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "folio-core",
    libraryDependencies ++= Dependencies.Weaver
  )
  .jvmPlatform(
    scalaVersions = Seq(Scala3),
    settings = scaladoc
  )
  .jsPlatform(
    scalaVersions = Seq(Scala3),
    settings = jsNativeSettings ++ Seq(
      libraryDependencies ++= Dependencies.ScalaJavaTime
    )
  )
  .nativePlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)

lazy val cats = (projectMatrix in file("module/effect/cats"))
  .dependsOn(core)
  .settings(
    name := "folio-cats",
    libraryDependencies ++= Dependencies.Cats ++ Dependencies.WeaverCats
  )
  .jvmPlatform(scalaVersions = Seq(Scala3))
  .jsPlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)
  .nativePlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)

lazy val skunk = (projectMatrix in file("module/database/skunk"))
  .dependsOn(cats)
  .settings(
    name := "folio-skunk",
    libraryDependencies ++= Dependencies.Skunk ++ Dependencies.Weaver
  )
  .jvmPlatform(scalaVersions = Seq(Scala3))
  .jsPlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)
  .nativePlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)

// No `core % "test->test"`: the integration suites use only folio's public API and own their fixtures (it/.../Rows.scala),
// and core's unit fixtures declare a top-level `folio.Row` that would shadow this module's row model under `import folio.*`.
lazy val integration = (projectMatrix in file("it"))
  .dependsOn(skunk)
  .settings(
    name := "folio-it",
    publish / skip := true,
    Test / parallelExecution := false,
    libraryDependencies ++= Dependencies.Weaver
  )
  .jvmPlatform(scalaVersions = Seq(Scala3))
  .nativePlatform(scalaVersions = Seq(Scala3), settings = jsNativeSettings)

lazy val example = project
  .in(file("example"))
  .dependsOn(core.jvm(Scala3), skunk.jvm(Scala3))
  .settings(
    name := "folio-example",
    publish / skip := true,
    coverageEnabled := false
  )

// === SCALADOC ===
val scaladoc = Seq(
  // Replace the module name with the top-level project name in generated docs
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
