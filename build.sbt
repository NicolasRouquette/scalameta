// === Projects === //

lazy val root = project.in(file("."))
  .settings(commonSettings : _*)
  .settings(
    // TODO ? test in Test := (test in scalametaTests in Test).value,
    replIntegration,
    usePlugin(scalahostPlugin),
    dontPackage)
  .aggregate(scalameta, scalametaFoundation, scalametaTests) // TODO ...
  .dependsOn(scalameta, scalametaFoundation, scalametaTests)

// == Scala Meta == //

lazy val scalametaFoundation = project
  .settings(moduleName := "scalameta-foundation")
  .settings(publishableSettings: _*)
  .settings(scalaReflect: _*)

lazy val scalameta = project
  .settings(publishableSettings: _*)
  .settings(scalaReflect: _*)
  .dependsOn(scalametaFoundation)

// lazy val sandbox = project
//   .settings(commonSettings: _*)
//   .settings(flatStructure)
//   .dependsOn(scalameta)

lazy val scalametaTests = project
  .settings(commonSettings: _*)
  .settings(scalaReflect: _*)
  .settings(testLibs: _*)
  .settings(
    packagedArtifacts := Map.empty,
    sourceDirectory in Test := {
      val defaultValue = (sourceDirectory in Test).value
      System.setProperty("sbt.paths.tests.source", defaultValue.getAbsolutePath)
      defaultValue
    }
  )
  .dependsOn(scalameta)

// == Interpreter == //

// lazy val interpreter = project
//   .settings(commonSettings: _*)
//   .settings(flatStructure)
//   .dependsOn(scalameta)

// lazy val interpreterTests = project
//   .settings(commonSettings: _*)
//   .settings(testLibs: _*)
//   .settings(
//     libraryDependencies +="org.scala-lang" % "scala-compiler" % scalaVersion.value,
//     testOptions in Test += Tests.Argument("-oDF"),
//     packagedArtifacts := Map.empty
//   )
//   .settings(exposeClasspaths: _*)
//   .dependsOn(interpreter, scalameta, scalahost % "test" cross CrossVersion.full)

// == Scala Host == //

lazy val scalahostFoundation = project
  .settings(moduleName := "scalahost-foundation")
  .settings(publishableSettings: _*)
  .settings(dontPackage) // merged in plugin
  .settings(scalaReflect: _*)
  .dependsOn(scalametaFoundation)

lazy val scalahostInterface = project
  .settings(moduleName := "scalahost-interface")
  .settings(publishableSettings)
  .settings(dontPackage) // merged in plugin
  .dependsOn(scalahostFoundation)

lazy val scalahostPlugin = project
  .settings(moduleName := "scalahost")
  .settings(publishableSettings: _*)
  .settings(mergeDependencies: _*)
  .dependsOn(
    scalahostFoundation % "optional" // not really optional. merged with assembly
    // scalahostInterface % "optional" // needed ?
  )

lazy val scalahostTest = project
  .settings(commonSettings : _*)
  .settings(testLibs: _*)
  .settings(dontPackage, usePlugin(scalahostPlugin))

// === Settings === //

lazy val flatStructure = (scalaSource in Compile <<= (baseDirectory in Compile)(base => base))
lazy val commonSettings = Seq(
  scalaVersion := "2.11.7",
  crossVersion := CrossVersion.binary,
  version := "0.1.0-SNAPSHOT",
  organization := "org.scalameta",
  description := "Metaprogramming and hosting APIs of scala.meta",
  // description := "Typed AST interpreter for scala.meta",
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.sonatypeRepo("releases"),
  publishMavenStyle := true,
  publishArtifact in Compile := false,
  publishArtifact in Test := false,
  scalacOptions ++= Seq("-deprecation", "-feature", "-optimise", "-unchecked"),
  parallelExecution in Test := false, // hello, reflection sync!!
  logBuffered := false,
  traceLevel := 0,
  commands += cls,
  // FIXME: Not used
  // scalaHome := {
  //   val scalaHome = System.getProperty("core.scala.home")
  //   if (scalaHome != null) {
  //     println(s"Going for custom scala home at $scalaHome")
  //     Some(file(scalaHome))
  //   } else None
  // },
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
    <developers>
        <developer>
          <id>vjovanov</id>
          <name>Vojin Jovanovic</name>
          <url>http://github.com/vjovanov</url>
        </developer>
        <developer>
          <id>dedoz</id>
          <name>Mikhail Mutcianko</name>
          <url>http://github.com/dedoz</url>
        </developer>
        <developer>
          <id>xeno-by</id>
          <name>Eugene Burmako</name>
          <url>http://xeno.by</url>
        </developer>
      </developers>
  ),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full),
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

def cls = Command.command("cls") { state =>
  // For iTerm2
  // kudos to http://superuser.com/questions/576410/how-can-i-partially-clear-my-terminal-scrollback
  println("\u001b]50;ClearScrollback\u0007")
  // For Terminal & Xterm: https://stackoverflow.com/a/29876027/1009693
  println("\u001bc\u001b[3J")
  state
}

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

lazy val scalaReflect = 
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"

lazy val testLibs = libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "2.1.3" % "test",
    "org.scalacheck" %% "scalacheck" % "1.11.3" % "test"
  )


def exposeClasspaths = Seq(
  fullClasspath in Test := {
    val defaultValue = (fullClasspath in Test).value
    val classpath = defaultValue.files.map(_.getAbsolutePath)
    val scalaLibrary = classpath.map(_.toString).find(_.contains("scala-library")).get
    System.setProperty("sbt.paths.scala-library.jar", scalaLibrary)
    System.setProperty("sbt.paths.tests.classpath", classpath.mkString(java.io.File.pathSeparatorChar.toString))
    defaultValue
  },
  resourceDirectory in Test := {
    val defaultValue = (resourceDirectory in Test).value
    System.setProperty("sbt.paths.tests.resources", defaultValue.getAbsolutePath)
    defaultValue
  }
)

lazy val dontPackage = packagedArtifacts := Map.empty

// Thanks Jason for this cool idea (taken from https://github.com/retronym/boxer)
// add plugin timestamp to compiler options to trigger recompile of
// main after editing the plugin. (Otherwise a 'clean' is needed.)
def usePlugin(plugin: ProjectReference) =
  scalacOptions <++= (Keys.`package` in (plugin, Compile)) map { (jar: File) =>
    System.setProperty("sbt.paths.plugin.jar", jar.getAbsolutePath)
    Seq("-Xplugin:" + jar.getAbsolutePath, "-Jdummy=" + jar.lastModified)
  }

lazy val replIntegration = 
  initialCommands in console := """
    import scala.meta._
    import scala.meta.internal.{ast => impl}
    import scala.meta.Scalahost
    val options = "-Xplugin:" + sys.props("sbt.paths.plugin.jar") + " -Xplugin-require:scalahost"
    implicit val c = Scalahost.mkStandaloneContext(options = options)
  """
lazy val mergeDependencies: Seq[sbt.Def.Setting[_]] = Seq(
  test in assembly := {},
  logLevel in assembly := Level.Error,
  jarName in assembly := name.value + "_" + scalaVersion.value + "-" + version.value + "-assembly.jar",
  assemblyOption in assembly ~= { _.copy(includeScala = false) },
  Keys.`package` in Compile := {
    val slimJar = (Keys.`package` in Compile).value
    val fatJar = new File(crossTarget.value + "/" + (jarName in assembly).value)
    val _ = assembly.value
    IO.copy(List(fatJar -> slimJar), overwrite = true)
    slimJar
  },
  packagedArtifact in Compile in packageBin := {
    val temp = (packagedArtifact in Compile in packageBin).value
    val (art, slimJar) = temp
    val fatJar = new File(crossTarget.value + "/" + (jarName in assembly).value)
    val _ = assembly.value
    IO.copy(List(fatJar -> slimJar), overwrite = true)
    (art, slimJar)
  }
)
