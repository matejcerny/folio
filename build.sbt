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
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "folio-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "weaver-cats" % "0.12.0" % Test
    ),
    // SCALADOC
    // sbt-typelevel sets -project to the module name; replace with the top-level project name
    Compile / doc / scalacOptions ~= (_.map { case "folio-core" => "folio"; case other => other }),
    Compile / doc / scalacOptions ++= Seq(
      "-siteroot",
      ((ThisBuild / baseDirectory).value / "docs").getAbsolutePath,
      "-social-links:github::https://github.com/matejcerny/folio",
      "-project-logo", "docs/_assets/images/logo.png",
      "-project-footer",
      "Copyright Matej Cerny",
      "-versions-dictionary-url",
      "https://matejcerny.github.io/folio/versions.json",
      "-snippet-compiler:nocompile"
    ),
    Compile / doc := {
      val output = (Compile / doc).value
      val assetsDir = (ThisBuild / baseDirectory).value / "docs" / "_assets"
//      val favicon = assetsDir / "images" / "favicon.ico"
//      if (favicon.exists()) IO.copyFile(favicon, output / "favicon.ico")
      val customCss = assetsDir / "css" / "custom.css"
      if (customCss.exists()) IO.copyFile(customCss, output / "styles" / "staticsitestyles.css")
      output
    }
  )

lazy val example = project
  .in(file("example"))
  .dependsOn(core)
  .settings(
    name := "folio-example",
    publish / skip := true,
    coverageEnabled := false
  )
