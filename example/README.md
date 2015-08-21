### An example of using scala.meta APIs

This example shows how to set up dependencies and organize a project to make use of syntactic and semantic APIs of scala.meta. Consult the [documentation of scalameta/scalameta](https://github.com/scalameta/scalameta/blob/master/README.md) to learn about the fundamental notions of scala.meta and see how to make use of them to carry out typical metaprogramming tasks.

#### Compile-time metaprogramming with scala.meta

At compile time, scala.meta is currently only available to compiler plugins that run after the `convert` phase provided by [scalameta/scalahost](https://github.com/scalameta/scalahost). In the future, as per [our roadmap](https://github.com/scalameta/scalameta/blob/master/docs/roadmap.md), we plan to also expose scala.meta to macros, allowing metaprograms written against scala.meta to be called automatically by the compiler without the need for compiler plugins. This example features a compiler plugin that depends on scalahost and provides a phase that runs after `convert` and calls into custom logic from [CompileTime.scala](https://github.com/scalameta/example/blob/master/compiletime/src/main/scala/org/scalameta/example/CompileTime.scala).

```scala
package org.scalameta.example

import scala.meta.internal.ast._
import scala.meta.Scalahost

trait CompileTime {
  val global: scala.tools.nsc.Global
  implicit val c = Scalahost.mkGlobalContext(global)

  def example(sources: List[Source]): Unit = {
    // ...
  }
}
```

The infrastructure of `CompileTime` does the following:
  * `import scala.meta.internal.ast._` brings [internal representation](https://github.com/scalameta/scalameta/blob/master/scalameta/src/main/scala/scala/meta/Trees.scala#70) for trees underlying [core traits](https://github.com/scalameta/scalameta/blob/master/scalameta/src/main/scala/scala/meta/Trees.scala) and [quasiquotes](https://github.com/scalameta/scalameta/blob/master/scalameta/src/main/scala/scala/meta/package.scala). This import is necessary, because our implementation of  quasiquotes is currently a stub, so manual tree construction/deconstruction might be required to manipulate scala.meta trees.
  * `import scala.meta.Scalahost` brings core functionality of the [scalameta/scalahost](https://github.com/scalameta/scalahost) library.
  * `implicit val c = Scalahost.mkGlobalContext(global)` creates a semantic context, i.e. something that can process requests to semantic APIs. An implicit value of type `scala.meta.semantic.Context` is required to be in scope for most semantic APIs. Read more about contexts in [our documentation](https://github.com/scalameta/scalameta/blob/master/README.md).

#### Runtime metaprogramming with scala.meta

At runtime, scala.meta is available to anyone who wants to metaprogram against the classes on a given classpath. This example features a simple console application that depends on scalahost and calls into custom logic from [Runtime.scala](https://github.com/scalameta/example/blob/master/runtime/src/main/scala/org/scalameta/example/Runtime.scala).

```scala
package org.scalameta.example

import scala.meta.internal.ast._
import scala.meta.Scalahost

object Runtime extends App {
  val scalaLibraryJar = classOf[App].getProtectionDomain().getCodeSource()
  val scalaLibraryPath = scalaLibraryJar.getLocation().getFile()
  implicit val c = Scalahost.mkStandaloneContext(s"-cp $scalaLibraryPath")
  // ...
}
```

The infrastructure of `Runtime` does the following:
  * `import scala.meta.internal.ast._` is explained above.
  * `import scala.meta.internal.hosts.scalac._` is explained above.
  * `val scalaLibraryJar = classOf[App].getProtectionDomain().getCodeSource()` and `val scalaLibraryPath = scalaLibraryJar.getLocation().getFile()` detect the location of the scala-library jar file, so that it can be put on a classpath of the context that is instantiated below.
  * `implicit val c = Scalahost.mkStandaloneContext(s"-cp $scalaLibraryPath")` creates a semantic context that will see everything on a given classpath. This context creates an instance of `scala.tools.nsc.Global` under the covers.

#### Acknowledgements

Thanks to [@aghosn](https://github.com/aghosn) for coming up with a framework of using scalahost to operate on scala.meta trees. Also thanks to [@MasseGuillaume](https://github.com/MasseGuillaume) for helping me organize the SBT build of scalahost, which became a foundation for the build of this project (that was very convenient!).
