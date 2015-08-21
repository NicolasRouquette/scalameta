package scala.meta
package internal.hosts.dotc
package contexts

import dotty.tools.dotc.core.Contexts.{Context => DottyContext}
import org.scalameta.contexts._
import org.scalameta.invariants._
import scala.{Seq => _}
import scala.collection.immutable.Seq
import scala.meta.semantic.{Context => ScalametaSemanticContext}
import scala.{meta => mapi}
import scala.meta.internal.{ast => m}

@context(translateExceptions = true)
class SemanticContext(context: DottyContext) extends ScalametaSemanticContext {
  implicit val mc: ScalametaSemanticContext = this
  implicit val dc: DottyContext = context

  def dialect: scala.meta.dialects.Dotty.type = {
    scala.meta.dialects.Dotty
  }

  private[meta] def desugar(term: mapi.Term): mapi.Term = {
    ???
  }

  private[meta] def tpe(term: mapi.Term): mapi.Type = {
    ???
  }

  private[meta] def tpe(param: mapi.Term.Param): mapi.Type.Arg = {
    ???
  }

  private[meta] def defns(ref: mapi.Ref): Seq[mapi.Member] = {
    ???
  }

  private[meta] def members(tpe: mapi.Type): Seq[mapi.Member] = {
    ???
  }

  private[meta] def isSubType(tpe1: mapi.Type, tpe2: mapi.Type): Boolean = {
    ???
  }

  private[meta] def lub(tpes: Seq[mapi.Type]): mapi.Type = {
    ???
  }

  private[meta] def glb(tpes: Seq[mapi.Type]): mapi.Type = {
    ???
  }

  private[meta] def parents(tpe: mapi.Type): Seq[mapi.Type] = {
    ???
  }

  private[meta] def widen(tpe: mapi.Type): mapi.Type = {
    ???
  }

  private[meta] def dealias(tpe: mapi.Type): mapi.Type = {
    ???
  }

  private[meta] def parents(member: mapi.Member): Seq[mapi.Member] = {
    ???
  }

  private[meta] def children(member: mapi.Member): Seq[mapi.Member] = {
    ???
  }
}