import sbt._
import Keys._

object ScalaHostBuild extends Build {
  import Dependencies._
  import Settings._

  lazy val commonDependencies = Seq(
    libraryDependencies <++= (scalaVersion)(sv => Seq(
      reflect(sv) % "provided",
      compiler(sv) % "provided",
      meta
    )),
    addCompilerPlugin(paradise)
  )

  lazy val root = Project(
    id = "root",
    base = file("root"),
    settings = sharedSettings ++ commonDependencies ++ Seq(
      dontPackage
    )
  ) aggregate (dottyhost, tests) dependsOn (dottyhost)

  lazy val dotty = RootProject(new java.net.URI(sys.env.getOrElse("DOTTY_REPOSITORY_URL", sys.error("DOTTY_REPOSITORY_URL is not set"))))

  lazy val dottyhost = Project(
    id   = "dottyhost",
    base = file("dottyhost"),
    settings = publishableSettings ++ commonDependencies ++ mergeDependencies ++ Seq(
      libraryDependencies += interpreter
    )
  ) dependsOn (dotty)

  lazy val tests = Project(
    id   = "tests",
    base = file("tests"),
    settings = sharedSettings ++ commonDependencies ++ Seq(
      libraryDependencies ++= Seq(scalatest, scalacheck),
      dontPackage
    ) ++ exposePaths("tests")
  ) dependsOn (dottyhost)
}
