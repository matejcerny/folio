import sbt.*

object Dependencies:

  private object Versions:
    val Cats = "2.13.0"
    val Weaver = "0.13.0"
    val Skunk = "1.0.0"
    val ScalaJavaTime = "2.7.0"

  private object GroupIds:
    val Typelevel = "org.typelevel"
    val Tpolecat = "org.tpolecat"
    val Cquiroz = "io.github.cquiroz"

  val Cats: Seq[ModuleID] = Seq(
    GroupIds.Typelevel %% "cats-core" % Versions.Cats
  )

  val Weaver: Seq[ModuleID] = Seq(
    GroupIds.Typelevel %% "weaver-cats" % Versions.Weaver % Test,
    GroupIds.Typelevel %% "weaver-scalacheck" % Versions.Weaver % Test
  )

  val WeaverCats: Seq[ModuleID] = Seq(
    GroupIds.Typelevel %% "weaver-cats" % Versions.Weaver % Test
  )

  val Skunk: Seq[ModuleID] = Seq(
    GroupIds.Tpolecat %% "skunk-core" % Versions.Skunk
  )

  val ScalaJavaTime: Seq[ModuleID] = Seq(
    GroupIds.Cquiroz %% "scala-java-time" % Versions.ScalaJavaTime
  )
