import sbt._
import Keys._

object ExampleBuild extends Build {
  import Dependencies._
  import Settings._

  lazy val root = Project(
    id = "root",
    base = file("root"),
    settings = sharedSettings ++ Seq(
      dontPackage,
      run in Compile := (run in (runtime, Compile)).evaluated
    )
  ) aggregate (compiletime, runtime, tests)

  lazy val compiletime = Project(
    id   = "compiletime",
    base = file("compiletime"),
    settings = publishableSettings ++ mergeDependencies ++ Seq(
      libraryDependencies += compiler(languageVersion),
      libraryDependencies += scalameta,
      libraryDependencies += scalahost
    )
  )

  lazy val runtime = Project(
    id   = "runtime",
    base = file("runtime"),
    settings = publishableSettings ++ mergeDependencies ++ Seq(
      libraryDependencies += scalameta,
      libraryDependencies += scalahost
    ) ++ exposeClasspaths("runtime")
  )

  lazy val tests = Project(
    id   = "tests",
    base = file("tests"),
    settings = sharedSettings ++ Seq(
      usePlugin(compiletime),
      libraryDependencies ++= Seq(scalatest, scalacheck),
      dontPackage
    ) ++ exposeClasspaths("tests")
  ) dependsOn (compiletime, runtime)
}