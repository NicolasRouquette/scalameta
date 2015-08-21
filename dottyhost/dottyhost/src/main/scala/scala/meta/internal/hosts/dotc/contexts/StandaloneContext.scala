package scala.meta
package internal.hosts.dotc
package contexts

import org.scalameta.contexts._
import org.scalameta.invariants._
import scala.meta.semantic.{Context => ScalametaSemanticContext}
import scala.meta.internal.hosts.dotc.contexts.{SemanticContext => DottySemanticContext}
import scala.{meta => mapi}
import scala.meta.internal.{ast => m}

@context(translateExceptions = false)
class StandaloneContext(options: String) extends DottySemanticContext(???) {
  def define(code: String): mapi.Source = ???
}
