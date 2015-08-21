import scala.meta.internal.ast._, Defn.Class

class PackageSuite extends ParseSuite {
  test("class C") {
    val Source(Class(Nil, Type.Name("C"), Nil,
                       Ctor.Primary(Nil, Nil),
                       EmptyTemplate()) :: Nil) = source("class C")
  }

  test("package foo; class C") {
    val Source((pkgfoo @ Pkg(Term.Name("foo"),
                               Class(Nil, Type.Name("C"), Nil,
                                     Ctor.Primary(Nil, Nil),
                                     EmptyTemplate()) :: Nil)) :: Nil) =
      source("package foo; class C")
    assert(pkgfoo.hasBraces === false)
  }

  test("package foo { class C }") {
    val Source((pkgfoo @Pkg(Term.Name("foo"),
                              Class(Nil, Type.Name("C"), Nil,
                                    Ctor.Primary(Nil, Nil),
                                    EmptyTemplate()) :: Nil)) :: Nil) =
      source("package foo { class C }")
    assert(pkgfoo.hasBraces === true)
  }

  test("package foo.bar; class C") {
    val Source((pkgfoobar @ Pkg(Term.Select(Term.Name("foo"), Term.Name("bar")),
                                  Class(Nil, Type.Name("C"), Nil,
                                        Ctor.Primary(Nil, Nil),
                                        EmptyTemplate()) :: Nil)) :: Nil) =
      source("package foo.bar; class C")
    assert(pkgfoobar.hasBraces === false)
  }

  test("package foo.bar { class C }") {
    val Source((pkgfoobar @ Pkg(Term.Select(Term.Name("foo"), Term.Name("bar")),
                                  Class(Nil, Type.Name("C"), Nil,
                                        Ctor.Primary(Nil, Nil),
                                        EmptyTemplate()) :: Nil)) :: Nil) =
      source("package foo.bar { class C }")
    assert(pkgfoobar.hasBraces === true)
  }

  test("package foo; package bar; class C") {
    val Source((pkgfoo @ Pkg(Term.Name("foo"),
                               (pkgbar @ Pkg(Term.Name("bar"),
                                             Class(Nil, Type.Name("C"), Nil,
                                                   Ctor.Primary(Nil, Nil),
                                                   EmptyTemplate()) :: Nil)) :: Nil)) :: Nil) =
      source("package foo; package bar; class C")
    assert(pkgfoo.hasBraces === false)
    assert(pkgbar.hasBraces === false)
  }

  test("package foo { package bar { class C } }") {
    val Source((pkgfoo @ Pkg(Term.Name("foo"),
                               (pkgbar @ Pkg(Term.Name("bar"),
                                             Class(Nil, Type.Name("C"), Nil,
                                                   Ctor.Primary(Nil, Nil),
                                                   EmptyTemplate()) :: Nil)) :: Nil)) :: Nil) =
      source("package foo { package bar { class C } }")
    assert(pkgfoo.hasBraces === true)
    assert(pkgbar.hasBraces === true)
  }

  test("package foo {}; package bar {}") {
    val Source((pkgfoo @ Pkg(Term.Name("foo"), Nil)) ::
                 (pkgbar @ Pkg(Term.Name("bar"), Nil)) :: Nil) =
      source("package foo {}; package bar {}")
    assert(pkgfoo.hasBraces === true)
    assert(pkgbar.hasBraces === true)
  }

  test("package object foo") {
    val Source(Pkg.Object(Nil, Term.Name("foo"),
                            EmptyTemplate()) :: Nil) = source("package object foo")
  }

  test("import foo.bar; package object baz") {
    val Source(Import(Import.Clause(Term.Name("foo"), Import.Name("bar") :: Nil) :: Nil) ::
                 Pkg.Object(Nil, Term.Name("baz"), EmptyTemplate()) :: Nil) =
      source("import foo.bar; package object baz")
  }

  test("package foo; package bar; package baz") {
    val Source(List(Pkg(Term.Name("foo"), List(Pkg(Term.Name("bar"), List(Pkg(Term.Name("baz"), List()))))))) =
      source("package foo; package bar; package baz")
  }
}
