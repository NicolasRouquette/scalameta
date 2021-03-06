package org.scalameta.example

import scala.meta.internal.ast._
import scala.meta.Scalahost

object Runtime extends App {
  val scalaLibraryJar = classOf[App].getProtectionDomain().getCodeSource()
  val scalaLibraryPath = scalaLibraryJar.getLocation().getFile()
  implicit val c = Scalahost.mkStandaloneContext(s"-cp $scalaLibraryPath")
  // ...
}
