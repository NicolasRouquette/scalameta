import org.scalatest._
import java.net._
import java.io._

class BootstrapSuite extends ParseSuite {
  var dir = new File(new File(System.getProperty("sbt.paths.tests.source")).getAbsolutePath)
  def isProjectRoot(dir: File) = dir != null && new File(dir.getAbsolutePath + File.separatorChar + "project" + File.separatorChar + "build.scala").exists
  while (dir != null && !isProjectRoot(dir)) dir = dir.getParentFile
  test("ProjectDir (" + dir.getAbsolutePath + ")")(assert(isProjectRoot(dir)))

  if (isProjectRoot(dir)) {
    def loop(dir: File): Unit = {
      def bootstrapTest(src: File): Unit = {
        test(src.getAbsolutePath) {
          import scala.meta.syntactic.parsers._
          import scala.meta.syntactic.show._
          val toks = src.tokens
          val content = scala.io.Source.fromFile(src).mkString
          // check #1: everything's covered
          var isFail = false
          def fail(msg: String) = { isFail = true; println(msg) }
          val bitmap = new Array[Boolean](content.length)
          val tokenmap = scala.collection.mutable.Map[Int, List[Tok]]()
          toks.foreach(tok => {
            var i = tok.start
            while (i <= tok.end) {
              if (i < 0 || content.length <= i) fail("TOKEN OUT OF BOUNDS AT " + i + ": " + tok)
              else {
                tokenmap(i) = tok +: tokenmap.getOrElse(i, Nil)
                if (bitmap(i)) fail("TOKENS OVERLAP AT " + i + ": " + tokenmap(i).mkString(", "))
                bitmap(i) = true
              }
              i += 1
            }
          })
          bitmap.zipWithIndex.filter(!_._1).foreach{ case (_, i) => fail("TOKENS DON'T COVER " + i) }
          // check #2: tostring works
          if (!isFail && content != toks.map(_.show[Code]).mkString) {
            isFail = true
            println("CORRELATION FAILED")
            println("EXPECTED: \n" + content)
            println("ACTUAL: \n" + toks.map(_.show[Code]).mkString)
          }
          assert(!isFail)
        }
      }
      dir.listFiles.filter(_.isFile).filter(_.getName.endsWith(".scala")).map(bootstrapTest)
      dir.listFiles.filter(_.isDirectory).map(loop)
    }
    loop(dir)
  }
}