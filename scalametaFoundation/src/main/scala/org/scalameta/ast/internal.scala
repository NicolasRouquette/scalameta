package org.scalameta.ast

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.reflect.macros.blackbox.Context

object internal {
  trait Ast extends org.scalameta.adt.Internal.Adt
  class root extends StaticAnnotation
  class branch extends StaticAnnotation
  class astClass extends StaticAnnotation
  class astCompanion extends StaticAnnotation

  def productPrefix[T]: String = macro Macros.productPrefix[T]
  def loadField[T](f: T): Unit = macro Macros.loadField
  def storeField[T](f: T, v: T): Unit = macro Macros.storeField
  def initField[T](f: T): T = macro Macros.initField
  def initParam[T](f: T): T = macro Macros.initField

  class Macros(val c: Context) extends org.scalameta.adt.AdtReflection {
    val u: c.universe.type = c.universe
    import c.universe._
    import definitions._
    def productPrefix[T](implicit T: c.WeakTypeTag[T]) = {
      q"${T.tpe.typeSymbol.asLeaf.prefix}"
    }
    def loadField(f: c.Tree) = {
      val q"this.$finternalName" = f
      def uncapitalize(s: String) = if (s.length == 0) "" else { val chars = s.toCharArray; chars(0) = chars(0).toLower; new String(chars) }
      val fname = TermName(finternalName.toString.stripPrefix("_"))
      def lazyLoad(fn: c.Tree => c.Tree) = {
        val assertionMessage = s"internal error when initializing ${c.internal.enclosingOwner.owner.name}.$fname"
        q"""
          if ($f == null) {
            // there's not much sense in using org.scalameta.invariants.require here
            // because when the assertion trips, the tree is most likely in inconsistent state
            // which will either lead to useless printouts or maybe even worse errors
            _root_.scala.Predef.require(this.internalPrototype != null, $assertionMessage)
            $f = ${fn(q"this.internalPrototype.$fname")}
          }
        """
      }
      f.tpe.finalResultType match {
        case Primitive(tpe) => q""
        case Tree(tpe) => lazyLoad(pf => q"$pf.internalCopy(prototype = $pf, parent = this)")
        case OptionTree(tpe) => lazyLoad(pf => q"$pf.map(el => el.internalCopy(prototype = el, parent = this))")
        case SeqTree(tpe) => lazyLoad(pf => q"$pf.map(el => el.internalCopy(prototype = el, parent = this))")
        case SeqSeqTree(tpe) => lazyLoad(pf => q"$pf.map(_.map(el => el.internalCopy(prototype = el, parent = this)))")
      }
    }
    def storeField(f: c.Tree, v: c.Tree) = {
      f.tpe.finalResultType match {
        case Primitive(tpe) => q""
        case Tree(tpe) => q"$f = $v.internalCopy(prototype = $v, parent = node)"
        case OptionTree(tpe) => q"$f = $v.map(el => el.internalCopy(prototype = el, parent = node))"
        case SeqTree(tpe) => q"$f = $v.map(el => el.internalCopy(prototype = el, parent = node))"
        case SeqSeqTree(tpe) => q"$f = $v.map(_.map(el => el.internalCopy(prototype = el, parent = node)))"
        case tpe => c.abort(c.enclosingPosition, s"unsupported field type $tpe")
      }
    }
    def initField(f: c.Tree) = {
      f.tpe.finalResultType match {
        case Primitive(tpe) => q"$f"
        case Tree(tpe) => q"null"
        case OptionTree(tpe) => q"null"
        case SeqTree(tpe) => q"null"
        case SeqSeqTree(tpe) => q"null"
        case tpe => c.abort(c.enclosingPosition, s"unsupported field type $tpe")
      }
    }
    private object Primitive {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe =:= typeOf[String] ||
            tpe =:= typeOf[scala.Symbol] ||
            ScalaPrimitiveValueClasses.contains(tpe.typeSymbol)) Some(tpe)
        else if (tpe.typeSymbol == OptionClass && Primitive.unapply(tpe.typeArgs.head).nonEmpty) Some(tpe)
        else None
      }
    }
    private object Tree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe <:< c.mirror.staticClass("scala.meta.Tree").asType.toType) Some(tpe)
        else None
      }
    }
    private object SeqTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.collection.immutable.Seq")) {
          tpe.typeArgs match {
            case Tree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
    private object SeqSeqTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.collection.immutable.Seq")) {
          tpe.typeArgs match {
            case SeqTree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
    private object OptionTree {
      def unapply(tpe: Type): Option[Type] = {
        if (tpe.typeSymbol == c.mirror.staticClass("scala.Option")) {
          tpe.typeArgs match {
            case Tree(tpe) :: Nil => Some(tpe)
            case _ => None
          }
        } else None
      }
    }
  }
}
