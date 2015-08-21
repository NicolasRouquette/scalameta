lazy val root = project.in(file("."))
  .settings(commonSettings : _*)
  .settings(test in Test := (test in scalametaTests in Test).value,
            packagedArtifacts := Map.empty)
  .aggregate(scalameta, foundation, scalametaTests) // ...

lazy val foundation = project
  .settings(moduleName := "scalameta-foundation")
  .settings(publishableSettings: _*)
  .settings(libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided")

lazy val scalameta = project
  .settings(publishableSettings: _*)
  .settings(libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided")
  .dependsOn(foundation)

// lazy val sandbox = project
//   .settings(commonSettings: _*)
//   .settings(scalaSource in Compile <<= (baseDirectory in Compile)(base => base))
//   .dependsOn(scalameta)

lazy val scalametaTests = project
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "org.scalatest" %% "scalatest" % "2.1.3" % "test",
      "org.scalacheck" %% "scalacheck" % "1.11.3" % "test"
    ),
    packagedArtifacts := Map.empty,
    sourceDirectory in Test := {
      val defaultValue = (sourceDirectory in Test).value
      System.setProperty("sbt.paths.tests.source", defaultValue.getAbsolutePath)
      defaultValue
    }
  )
  .dependsOn(scalameta)

lazy val commonSettings = Seq(
  scalaVersion := "2.11.5",
  crossVersion := CrossVersion.binary,
  version := "0.1.0-SNAPSHOT",
  organization := "org.scalameta",
  description := "Metaprogramming and hosting APIs of scala.meta",
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.sonatypeRepo("releases"),
  publishMavenStyle := true,
  publishArtifact in Compile := false,
  publishArtifact in Test := false,
  scalacOptions ++= Seq("-deprecation", "-feature", "-optimise", "-unchecked"),
  parallelExecution in Test := false, // hello, reflection sync!!
  logBuffered := false,
  scalaHome := {
    val scalaHome = System.getProperty("core.scala.home")
    if (scalaHome != null) {
      println(s"Going for custom scala home at $scalaHome")
      Some(file(scalaHome))
    } else None
  },
  publishMavenStyle := true,
  publishOnlyWhenOnMaster := publishOnlyWhenOnMasterImpl.value,
  publishTo <<= version { v: String =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { x => false },
  pomExtra := (
    <url>https://github.com/scalameta/scalameta</url>
    <inceptionYear>2014</inceptionYear>
    <licenses>
      <license>
        <name>BSD-like</name>
        <url>http://www.scala-lang.org/downloads/license.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git://github.com/scalameta/scalameta.git</url>
      <connection>scm:git:git://github.com/scalameta/scalameta.git</connection>
    </scm>
    <issueManagement>
      <system>GitHub</system>
      <url>https://github.com/scalameta/scalameta/issues</url>
    </issueManagement>
  ),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M1" cross CrossVersion.full),
  publishArtifact in (Compile, packageDoc) := false
)

lazy val publishableSettings = commonSettings ++ Seq(
  publishArtifact in Compile := true,
  publishArtifact in Test := false,
  credentials ++= {
    val mavenSettingsFile = System.getProperty("maven.settings.file")
    if (mavenSettingsFile != null) {
      println("Loading Sonatype credentials from " + mavenSettingsFile)
      try {
        import scala.xml._
        val settings = XML.loadFile(mavenSettingsFile)
        def readServerConfig(key: String) = (settings \\ "settings" \\ "servers" \\ "server" \\ key).head.text
        Some(Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          readServerConfig("username"),
          readServerConfig("password")
        ))
      } catch {
        case ex: Exception =>
          println("Failed to load Maven settings from " + mavenSettingsFile + ": " + ex)
          None
      }
    } else {
      for {
        realm <- sys.env.get("SCALAMETA_MAVEN_REALM")
        domain <- sys.env.get("SCALAMETA_MAVEN_DOMAIN")
        user <- sys.env.get("SCALAMETA_MAVEN_USER")
        password <- sys.env.get("SCALAMETA_MAVEN_PASSWORD")
      } yield {
        println("Loading Sonatype credentials from environment variables")
        Credentials(realm, domain, user, password)
      }
    }
  }.toList
)

// http://stackoverflow.com/questions/20665007/how-to-publish-only-when-on-master-branch-under-travis-and-sbt-0-13
val publishOnlyWhenOnMaster = taskKey[Unit]("publish task for Travis (don't publish when building pull requests, only publish when the build is triggered by merge into master)")
def publishOnlyWhenOnMasterImpl = Def.taskDyn {
  import scala.util.Try
  val travis   = Try(sys.env("TRAVIS")).getOrElse("false") == "true"
  val pr       = Try(sys.env("TRAVIS_PULL_REQUEST")).getOrElse("false") != "false"
  val branch   = Try(sys.env("TRAVIS_BRANCH")).getOrElse("??")
  val snapshot = version.value.trim.endsWith("SNAPSHOT")
  (travis, pr, branch, snapshot) match {
    case (true, false, "master", true) => publish
    case _                             => Def.task ()
  }
}
