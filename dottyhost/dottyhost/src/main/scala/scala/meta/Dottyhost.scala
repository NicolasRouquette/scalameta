package scala.meta

import dotty.tools.dotc.core.Contexts.{Context => DottyContext}
import scala.meta.semantic.{Context => ScalametaSemanticContext}
import scala.meta.internal.hosts.dotc.contexts.{StandaloneContext => ScalametaStandaloneContext}
import scala.meta.internal.hosts.dotc.contexts.{SemanticContext => DottyhostSemanticContext}
import scala.meta.internal.hosts.dotc.contexts.{StandaloneContext => DottyhostStandaloneContext}

object Dottyhost {
  def mkSemanticContext(context: DottyContext): ScalametaSemanticContext = new DottyhostSemanticContext(context)
  def mkStandaloneContext(options: String = ""): ScalametaStandaloneContext = new DottyhostStandaloneContext(options)
}
