// NOTE: has to be this package or otherwise we won't be able to access Context.issue
package scala.tools.nsc.typechecker

import scala.tools.nsc.Global
import scala.tools.nsc.typechecker.{Analyzer => NscAnalyzer}
import org.scalameta.invariants._
import org.scalameta.reflection._
import scala.reflect.internal.Mode
import scala.reflect.internal.Mode._
import scala.reflect.internal.util.{Statistics, ListOfNil}
import scala.tools.nsc.typechecker.TypersStats._
import scala.reflect.internal.Flags
import scala.reflect.internal.Flags._
import scala.collection.mutable

trait ScalahostAnalyzer extends NscAnalyzer with GlobalToolkit {
  val global: Global
  import global._
  import definitions._
  val stableCurrentRun = global.currentRun
  import stableCurrentRun.runDefinitions._

  override def newTyper(context: Context) = new ScalahostTyper(context)
  class ScalahostTyper(context0: Context) extends Typer(context0) {
    import infer._
    import TyperErrorGen._
    import typeDebug._
    private def typedParentType(encodedtpt: Tree, templ: Template, inMixinPosition: Boolean): Tree = {
      val app = treeInfo.dissectApplied(encodedtpt)
      val (treeInfo.Applied(core, _, argss), decodedtpt) = ((app, app.callee))
      val argssAreTrivial = argss == Nil || argss == ListOfNil

      // we cannot avoid cyclic references with `initialize` here, because when type macros arrive,
      // we'll have to check the probe for isTypeMacro anyways.
      // therefore I think it's reasonable to trade a more specific "inherits itself" error
      // for a generic, yet understandable "cyclic reference" error
      var probe = typedTypeConstructor(core.duplicate).tpe.typeSymbol
      if (probe == null) probe = NoSymbol
      probe.initialize

      if (probe.isTrait || inMixinPosition) {
        if (!argssAreTrivial) {
          if (probe.isTrait) ConstrArgsInParentWhichIsTraitError(encodedtpt, probe)
          else () // a class in a mixin position - this warrants an error in `validateParentClasses`
                  // therefore here we do nothing, e.g. don't check that the # of ctor arguments
                  // matches the # of ctor parameters or stuff like that
        }
        typedType(decodedtpt)
      } else {
        val supertpt = typedTypeConstructor(decodedtpt)
        val supertparams = if (supertpt.hasSymbolField) supertpt.symbol.typeParams else Nil
        def inferParentTypeArgs: Tree = {
          typedPrimaryConstrBody(templ) {
            val supertpe = PolyType(supertparams, appliedType(supertpt.tpe, supertparams map (_.tpeHK)))
            val supercall = New(supertpe, mmap(argss)(_.duplicate))
            val treeInfo.Applied(Select(ctor, nme.CONSTRUCTOR), _, _) = supercall
            ctor setType supertpe // this is an essential hack, otherwise it will occasionally fail to typecheck
            atPos(supertpt.pos.focus)(supercall)
          } match {
            case EmptyTree => MissingTypeArgumentsParentTpeError(supertpt); supertpt
            // NOTE: this is a meaningful difference from the code in Typers.scala
            //-case tpt       => TypeTree(tpt.tpe) setPos supertpt.pos  // SI-7224: don't .focus positions of the TypeTree of a parent that exists in source
            case tpt       => TypeTree(tpt.tpe) setOriginal supertpt setPos supertpt.pos  // SI-7224: don't .focus positions of the TypeTree of a parent that exists in source
          }
        }

        val supertptWithTargs = if (supertparams.isEmpty || context.unit.isJava) supertpt else inferParentTypeArgs

        // this is the place where we tell the typer what argss should be used for the super call
        // if argss are nullary or empty, then (see the docs for `typedPrimaryConstrBody`)
        // the super call dummy is already good enough, so we don't need to do anything
        if (argssAreTrivial) supertptWithTargs else supertptWithTargs updateAttachment SuperArgsAttachment(argss)
      }
    }
    private def normalizeFirstParent(parents: List[Tree]): List[Tree] = {
      @annotation.tailrec
      def explode0(parents: List[Tree]): List[Tree] = {
        val supertpt :: rest = parents // parents is always non-empty here - it only grows
        if (supertpt.tpe.typeSymbol == AnyClass) {
          supertpt setType AnyRefTpe
          parents
        } else if (treeInfo isTraitRef supertpt) {
          val supertpt1  = typedType(supertpt)
          def supersuper = TypeTree(supertpt1.tpe.firstParent) setPos supertpt.pos.focus
          if (supertpt1.isErrorTyped) rest
          else explode0(supersuper :: supertpt1 :: rest)
        } else parents
      }

      def explode(parents: List[Tree]) =
        if (treeInfo isTraitRef parents.head) explode0(parents)
        else parents

      if (parents.isEmpty) Nil else explode(parents)
    }
    private def fixDuplicateSyntheticParents(parents: List[Tree]): List[Tree] = parents match {
      case Nil      => Nil
      case x :: xs  =>
        val sym = x.symbol
        x :: fixDuplicateSyntheticParents(
          if (isPossibleSyntheticParent(sym)) xs filterNot (_.symbol == sym)
          else xs
        )
    }
    override def typedParentTypes(templ: Template): List[Tree] = templ.parents match {
      case Nil =>
        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-List(atPos(templ.pos)(TypeTree(AnyRefTpe)))
        templ.appendMetadata("originalParents" -> Nil)
        List(atPos(templ.pos)(TypeTree(AnyRefTpe)))
      case first :: rest =>
        try {
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-val supertpts = fixDuplicateSyntheticParents(normalizeFirstParent(
          //-  typedParentType(first, templ, inMixinPosition = false) +:
          //-  (rest map (typedParentType(_, templ, inMixinPosition = true)))))
          val supertpts = fixDuplicateSyntheticParents(normalizeFirstParent({
            val result =
              typedParentType(first, templ, inMixinPosition = false) +:
              (rest map (typedParentType(_, templ, inMixinPosition = true)))
            // TODO: using isPossibleSyntheticParent is not 100% precise, because the user could've specified the parent themselves
            // this can happen in just one case to the best of my knowledge: with explicit inheritance from synthetic parents of `case`
            // however these situations are extremely rare, so I'm letting it slip for the time being
            // NOTE: this differs from Definitions.isPossibleSyntheticParent, because it doesn't include ProductX traits
            // which are no longer synthetically put into parents of case classes (I guess, that happened somewhere on the road to 2.11.0)
            val isPossibleSyntheticParent = Set[Symbol](ProductRootClass, SerializableClass)
            var originals = if (context.owner.isCase) result.filter(p => !isPossibleSyntheticParent(p.symbol)) else result
            originals = originals.filter(_.tpe.typeSymbol != ObjectClass)
            templ.appendMetadata("originalParents" -> originals)
            result
          }))

          // if that is required to infer the targs of a super call
          // typedParentType calls typedPrimaryConstrBody to do the inferring typecheck
          // as a side effect, that typecheck also assigns types to the fields underlying early vals
          // however if inference is not required, the typecheck doesn't happen
          // and therefore early fields have their type trees not assigned
          // here we detect this situation and take preventive measures
          if (treeInfo.hasUntypedPreSuperFields(templ.body))
            typedPrimaryConstrBody(templ)(EmptyTree)

          supertpts mapConserve (tpt => checkNoEscaping.privates(context.owner, tpt))
        }
        catch {
          case ex: TypeError =>
            // fallback in case of cyclic errors
            // @H none of the tests enter here but I couldn't rule it out
            // upd. @E when a definition inherits itself, we end up here
            // because `typedParentType` triggers `initialize` for parent types symbols
            log("Type error calculating parents in template " + templ)
            log("Error: " + ex)
            ParentTypesError(templ, ex)
            List(TypeTree(AnyRefTpe))
        }
    }
    private def typedPrimaryConstrBody(templ: Template)(actualSuperCall: => Tree): Tree = {
      treeInfo.firstConstructor(templ.body) match {
        case ctor @ DefDef(_, _, _, vparamss, _, cbody @ Block(cstats, cunit)) =>
            val (preSuperStats, superCall) = {
              val (stats, rest) = cstats span (x => !treeInfo.isSuperConstrCall(x))
              (stats map (_.duplicate), if (rest.isEmpty) EmptyTree else rest.head.duplicate)
            }
          val superCall1 = (superCall match {
            case global.pendingSuperCall => actualSuperCall
            case EmptyTree => EmptyTree
          }) orElse cunit
          val cbody1 = treeCopy.Block(cbody, preSuperStats, superCall1)
          val clazz = context.owner
            assert(clazz != NoSymbol, templ)
          // SI-9086 The position of this symbol is material: implicit search will avoid triggering
          //         cyclic errors in an implicit search in argument to the super constructor call on
          //         account of the "ignore symbols without complete info that succeed the implicit search"
          //         in this source file. See `ImplicitSearch#isValid` and `ImplicitInfo#isCyclicOrErroneous`.
          val dummy = context.outer.owner.newLocalDummy(context.owner.pos)
          val cscope = context.outer.makeNewScope(ctor, dummy)
          if (dummy.isTopLevel) currentRun.symSource(dummy) = currentUnit.source.file
          val cbody2 = { // called both during completion AND typing.
            val typer1 = newTyper(cscope)
            // XXX: see about using the class's symbol....
            clazz.unsafeTypeParams foreach (sym => typer1.context.scope.enter(sym))
            typer1.namer.enterValueParams(vparamss map (_.map(_.duplicate)))
            typer1.typed(cbody1)
            }

            val preSuperVals = treeInfo.preSuperFields(templ.body)
            if (preSuperVals.isEmpty && preSuperStats.nonEmpty)
            devWarning("Wanted to zip empty presuper val list with " + preSuperStats)
            else
            map2(preSuperStats, preSuperVals)((ldef, gdef) => gdef.tpt setType ldef.symbol.tpe)

          if (superCall1 == cunit) EmptyTree
          else cbody2 match {
            case Block(_, expr) => expr
            case tree => tree
          }
          case _ =>
          EmptyTree
        }
    }
    private def makeAccessible(tree: Tree, sym: Symbol, pre: Type, site: Tree): (Tree, Type) = {
      if (context.isInPackageObject(sym, pre.typeSymbol)) {
        if (pre.typeSymbol == ScalaPackageClass && sym.isTerm) {
          // short cut some aliases. It seems pattern matching needs this
          // to notice exhaustiveness and to generate good code when
          // List extractors are mixed with :: patterns. See Test5 in lists.scala.
          //
          // TODO SI-6609 Eliminate this special case once the old pattern matcher is removed.
          def dealias(sym: Symbol) = {
            // NOTE: this is a meaningful difference from the code in Typers.scala
            //-(atPos(tree.pos.makeTransparent) {gen.mkAttributedRef(sym)} setPos tree.pos, sym.owner.thisType)
            val result @ Select(qual, _) = atPos(tree.pos.makeTransparent) {gen.mkAttributedRef(sym)} setPos tree.pos
            qual.appendMetadata("original" -> site)
            (result, sym.owner.thisType)
          }
          sym.name match {
            case nme.List => return dealias(ListModule)
            case nme.Seq  => return dealias(SeqModule)
            case nme.Nil  => return dealias(NilModule)
            case _ =>
          }
        }
        val qual = typedQualifier { atPos(tree.pos.makeTransparent) {
          tree match {
            case Ident(_) => Ident(rootMirror.getPackageObjectWithMember(pre, sym))
            case Select(qual, _) => Select(qual, nme.PACKAGEkw)
            case SelectFromTypeTree(qual, _) => Select(qual, nme.PACKAGEkw)
          }
        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-}}
        }}.appendMetadata("original" -> site)
        val tree1 = atPos(tree.pos) {
          tree match {
            case Ident(name) => Select(qual, name)
            case Select(_, name) => Select(qual, name)
            case SelectFromTypeTree(_, name) => SelectFromTypeTree(qual, name)
          }
        }
        (checkAccessible(tree1, sym, qual.tpe, qual), qual.tpe)
      } else {
        (checkAccessible(tree, sym, pre, site), pre)
      }
    }
    private def typedSelectOrSuperQualifier(qual: Tree) =
      context withinSuperInit typed(qual, PolyQualifierModes)
    override protected def typedTypeApply(tree: Tree, mode: Mode, fun: Tree, args: List[Tree]): Tree = fun.tpe match {
      // NOTE: this is a meaningful difference from the code in Typers.scala
      case PolyType(tparams, restpe) if tparams.nonEmpty && sameLength(tparams, args) && isPredefClassOf(fun.symbol) =>
        typedClassOf(tree, args.head, noGen = true).appendMetadata("originalClassOf" -> treeCopy.TypeApply(tree, fun, args))
      case _ =>
        super.typedTypeApply(tree, mode, fun, args)
    }
    override def doTypedApply(tree: Tree, fun0: Tree, args: List[Tree], mode: Mode, pt: Type): Tree = {
      val result = super.doTypedApply(tree, fun0, args, mode, pt)
      if (args.isEmpty && result.symbol == NilModule) result.appendMetadata("original" -> Apply(fun0, args).setType(result.tpe))
      result
    }
    override protected def adapt(tree: Tree, mode: Mode, pt: Type, original: Tree = EmptyTree): Tree = {
      def hasUndets           = context.undetparams.nonEmpty
      def hasUndetsInMonoMode = hasUndets && !mode.inPolyMode

      def adaptToImplicitMethod(mt: MethodType): Tree = {
        if (hasUndets) { // (9) -- should revisit dropped condition `hasUndetsInMonoMode`
          // dropped so that type args of implicit method are inferred even if polymorphic expressions are allowed
          // needed for implicits in 2.8 collection library -- maybe once #3346 is fixed, we can reinstate the condition?
            context.undetparams = inferExprInstance(tree, context.extractUndetparams(), pt,
              // approximate types that depend on arguments since dependency on implicit argument is like dependency on type parameter
              mt.approximate,
              keepNothings = false,
              useWeaklyCompatible = true) // #3808
        }

        // avoid throwing spurious DivergentImplicit errors
        if (context.reporter.hasErrors)
          setError(tree)
        else
          withCondConstrTyper(treeInfo.isSelfOrSuperConstrCall(tree))(typer1 =>
            if (original != EmptyTree && pt != WildcardType) (
              typer1 silent { tpr =>
                val withImplicitArgs = tpr.applyImplicitArgs(tree)
                if (tpr.context.reporter.hasErrors) tree // silent will wrap it in SilentTypeError anyway
                else tpr.typed(withImplicitArgs, mode, pt)
              }
              orElse { _ =>
                val resetTree = resetAttrs(original)
                resetTree match {
                  case treeInfo.Applied(fun, targs, args) =>
                    if (fun.symbol != null && fun.symbol.isError)
                      // SI-9041 Without this, we leak error symbols past the typer!
                      // because the fallback typechecking notices the error-symbol,
                      // refuses to re-attempt typechecking, and presumes that someone
                      // else was responsible for issuing the related type error!
                      fun.setSymbol(NoSymbol)
                  case _ =>
                }
                debuglog(s"fallback on implicits: ${tree}/$resetTree")
                val tree1 = typed(resetTree, mode)
                // Q: `typed` already calls `pluginsTyped` and `adapt`. the only difference here is that
                // we pass `EmptyTree` as the `original`. intended? added in 2009 (53d98e7d42) by martin.
                tree1 setType pluginsTyped(tree1.tpe, this, tree1, mode, pt)
                if (tree1.isEmpty) tree1 else adapt(tree1, mode, pt, EmptyTree)
              }
            )
            else
              typer1.typed(typer1.applyImplicitArgs(tree), mode, pt)
          )
      }

      def instantiateToMethodType(mt: MethodType): Tree = {
        val meth = tree match {
          // a partial named application is a block (see comment in EtaExpansion)
          case Block(_, tree1) => tree1.symbol
          case _               => tree.symbol
        }
        if (!meth.isConstructor && (isFunctionType(pt) || samOf(pt).exists)) { // (4.2)
          debuglog(s"eta-expanding $tree: ${tree.tpe} to $pt")
          checkParamsConvertible(tree, tree.tpe)
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-val tree0 = etaExpand(context.unit, tree, this)
          val tree0 = etaExpand(context.unit, tree, this).appendMetadata("originalAutoEta" -> tree)

          // #2624: need to infer type arguments for eta expansion of a polymorphic method
          // context.undetparams contains clones of meth.typeParams (fresh ones were generated in etaExpand)
          // need to run typer on tree0, since etaExpansion sets the tpe's of its subtrees to null
          // can't type with the expected type, as we can't recreate the setup in (3) without calling typed
          // (note that (3) does not call typed to do the polymorphic type instantiation --
          //  it is called after the tree has been typed with a polymorphic expected result type)
          if (hasUndets)
            instantiate(typed(tree0, mode), mode, pt)
          else
            typed(tree0, mode, pt)
        }
        else if (!meth.isConstructor && mt.params.isEmpty) { // (4.3)
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-adapt(typed(Apply(tree, Nil) setPos tree.pos), mode, pt, original)
          adapt(typed(Apply(tree, Nil).appendMetadata("originalParenless" -> true) setPos tree.pos), mode, pt, original)
        } else if (context.implicitsEnabled)
          MissingArgsForMethodTpeError(tree, meth)
        else
          setError(tree)
      }

      def adaptType(): Tree = {
        // @M When not typing a type constructor (!context.inTypeConstructorAllowed)
        // or raw type, types must be of kind *,
        // and thus parameterized types must be applied to their type arguments
        // @M TODO: why do kind-* tree's have symbols, while higher-kinded ones don't?
        def properTypeRequired = (
             tree.hasSymbolField
          && !context.inTypeConstructorAllowed
          && !context.unit.isJava
        )
        // @M: don't check tree.tpe.symbol.typeParams. check tree.tpe.typeParams!!!
        // (e.g., m[Int] --> tree.tpe.symbol.typeParams.length == 1, tree.tpe.typeParams.length == 0!)
        // @M: removed check for tree.hasSymbolField and replace tree.symbol by tree.tpe.symbol
        // (TypeTree's must also be checked here, and they don't directly have a symbol)
        def kindArityMismatch = (
             context.inTypeConstructorAllowed
          && !sameLength(tree.tpe.typeParams, pt.typeParams)
        )
        // Note that we treat Any and Nothing as kind-polymorphic.
        // We can't perform this check when typing type arguments to an overloaded method before the overload is resolved
        // (or in the case of an error type) -- this is indicated by pt == WildcardType (see case TypeApply in typed1).
        def kindArityMismatchOk = tree.tpe.typeSymbol match {
          case NothingClass | AnyClass => true
          case _                       => pt == WildcardType
        }

        // todo. It would make sense when mode.inFunMode to instead use
        //    tree setType tree.tpe.normalize
        // when typechecking, say, TypeApply(Ident(`some abstract type symbol`), List(...))
        // because otherwise Ident will have its tpe set to a TypeRef, not to a PolyType, and `typedTypeApply` will fail
        // but this needs additional investigation, because it crashes t5228, gadts1 and maybe something else
        if (mode.inFunMode)
          tree
        else if (properTypeRequired && tree.symbol.typeParams.nonEmpty)  // (7)
          MissingTypeParametersError(tree)
        else if (kindArityMismatch && !kindArityMismatchOk)  // (7.1) @M: check kind-arity
          KindArityMismatchError(tree, pt)
        else tree match { // (6)
          case TypeTree() => tree
          case _          => TypeTree(tree.tpe) setOriginal tree
        }
      }

      def insertApply(): Tree = {
        assert(!context.inTypeConstructorAllowed, mode) //@M
        val adapted = adaptToName(tree, nme.apply)
        def stabilize0(pre: Type): Tree = stabilize(adapted, pre, MonoQualifierModes, WildcardType)

        // TODO reconcile the overlap between Typers#stablize and TreeGen.stabilize
        val qual = adapted match {
          case This(_) =>
            gen.stabilize(adapted)
          case Ident(_) =>
            val owner = adapted.symbol.owner
            val pre =
              if (owner.isPackageClass) owner.thisType
              else if (owner.isClass) context.enclosingSubClassContext(owner).prefix
              else NoPrefix
            stabilize0(pre)
          case Select(qualqual, _) =>
            stabilize0(qualqual.tpe)
          case other =>
            other
        }
        typedPos(tree.pos, mode, pt) {
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-Select(qual setPos tree.pos.makeTransparent, nme.apply)
          Select(qual setPos tree.pos.makeTransparent, nme.apply).appendMetadata("originalApplee" -> qual)
        }
      }
      def adaptConstant(value: Constant): Tree = {
        val sym = tree.symbol
        if (sym != null && sym.isDeprecated)
          context.deprecationWarning(tree.pos, sym)

        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-treeCopy.Literal(tree, value)
        val result = treeCopy.Literal(tree, value)
        if (result.hasMetadata("originalConstant")) result
        else result.appendMetadata("originalConstant" -> tree)
      }

      // Ignore type errors raised in later phases that are due to mismatching types with existential skolems
      // We have lift crashing in 2.9 with an adapt failure in the pattern matcher.
      // Here's my hypothesis why this happens. The pattern matcher defines a variable of type
      //
      //   val x: T = expr
      //
      // where T is the type of expr, but T contains existential skolems ts.
      // In that case, this value definition does not typecheck.
      // The value definition
      //
      //   val x: T forSome { ts } = expr
      //
      // would typecheck. Or one can simply leave out the type of the `val`:
      //
      //   val x = expr
      //
      // SI-6029 shows another case where we also fail (in uncurry), but this time the expected
      // type is an existential type.
      //
      // The reason for both failures have to do with the way we (don't) transform
      // skolem types along with the trees that contain them. We'd need a
      // radically different approach to do it. But before investing a lot of time to
      // to do this (I have already sunk 3 full days with in the end futile attempts
      // to consistently transform skolems and fix 6029), I'd like to
      // investigate ways to avoid skolems completely.
      //
      // upd. The same problem happens when we try to typecheck the result of macro expansion against its expected type
      // (which is the return type of the macro definition instantiated in the context of expandee):
      //
      //   Test.scala:2: error: type mismatch;
      //     found   : $u.Expr[Class[_ <: Object]]
      //     required: reflect.runtime.universe.Expr[Class[?0(in value <local Test>)]] where type ?0(in value <local Test>) <: Object
      //     scala.reflect.runtime.universe.reify(new Object().getClass)
      //                                         ^
      // Therefore following Martin's advice I use this logic to recover from skolem errors after macro expansions
      // (by adding the ` || tree.attachments.get[MacroExpansionAttachment].isDefined` clause to the conditional above).
      //
      def adaptMismatchedSkolems() = {
        def canIgnoreMismatch = (
             !context.reportErrors && isPastTyper
          || tree.hasAttachment[MacroExpansionAttachment]
        )
        def bound = pt match {
          case ExistentialType(qs, _) => qs
          case _                      => Nil
        }
        def msg = sm"""
          |Recovering from existential or skolem type error in
          |  $tree
          |with type: ${tree.tpe}
          |       pt: $pt
          |  context: ${context.tree}
          |  adapted
          """.trim

        val boundOrSkolems = if (canIgnoreMismatch) bound ++ pt.skolemsExceptMethodTypeParams else Nil
        boundOrSkolems match {
          case Nil => AdaptTypeError(tree, tree.tpe, pt) ; setError(tree)
          case _   => logResult(msg)(adapt(tree, mode, deriveTypeWithWildcards(boundOrSkolems)(pt)))
        }
      }

      def fallbackAfterVanillaAdapt(): Tree = {
        def isPopulatedPattern = {
          if ((tree.symbol ne null) && tree.symbol.isModule)
            inferModulePattern(tree, pt)

          isPopulated(tree.tpe, approximateAbstracts(pt))
        }
        if (mode.inPatternMode && isPopulatedPattern)
          return tree

        val tree1 = constfold(tree, pt) // (10) (11)
        if (tree1.tpe <:< pt)
          return adapt(tree1, mode, pt, original)

        if (mode.typingExprNotFun) {
          // The <: Any requirement inhibits attempts to adapt continuation types
          // to non-continuation types.
          if (tree.tpe <:< AnyTpe) pt.dealias match {
            case TypeRef(_, UnitClass, _) => // (12)
              if (settings.warnValueDiscard)
                context.warning(tree.pos, "discarded non-Unit value")
              // NOTE: this is a meaningful difference from the code in Typers.scala
              //-return typedPos(tree.pos, mode, pt)(Block(List(tree), Literal(Constant(()))))
              return typedPos(tree.pos, mode, pt)(Block(List(tree), Literal(Constant(())).appendMetadata("insertedUnit" -> true)))
            case TypeRef(_, sym, _) if isNumericValueClass(sym) && isNumericSubType(tree.tpe, pt) =>
              if (settings.warnNumericWiden)
                context.warning(tree.pos, "implicit numeric widening")
              return typedPos(tree.pos, mode, pt)(Select(tree, "to" + sym.name))
            case _ =>
          }
          if (pt.dealias.annotations.nonEmpty && canAdaptAnnotations(tree, this, mode, pt)) // (13)
            return typed(adaptAnnotations(tree, this, mode, pt), mode, pt)

          if (hasUndets)
            return instantiate(tree, mode, pt)

          if (context.implicitsEnabled && !pt.isError && !tree.isErrorTyped) {
            // (14); the condition prevents chains of views
            debuglog("inferring view from " + tree.tpe + " to " + pt)
            inferView(tree, tree.tpe, pt, reportAmbiguous = true) match {
              case EmptyTree =>
              case coercion  =>
                def msg = "inferred view from " + tree.tpe + " to " + pt + " = " + coercion + ":" + coercion.tpe
                if (settings.logImplicitConv)
                  context.echo(tree.pos, msg)

                debuglog(msg)
                val silentContext = context.makeImplicit(context.ambiguousErrors)
                val res = newTyper(silentContext).typed(
                  new ApplyImplicitView(coercion, List(tree)) setPos tree.pos, mode, pt)
                silentContext.reporter.firstError match {
                  case Some(err) => context.issue(err)
                  case None      => return res
                }
            }
          }
        }

        debuglog("error tree = " + tree)
        if (settings.debug && settings.explaintypes)
          explainTypes(tree.tpe, pt)

        if (tree.tpe.isErroneous || pt.isErroneous)
          setError(tree)
        else
          adaptMismatchedSkolems()
      }

      def vanillaAdapt(tree: Tree) = {
        def applyPossible = {
          def applyMeth = member(adaptToName(tree, nme.apply), nme.apply)
          def hasPolymorphicApply = applyMeth.alternatives exists (_.tpe.typeParams.nonEmpty)
          def hasMonomorphicApply = applyMeth.alternatives exists (_.tpe.paramSectionCount > 0)

          dyna.acceptsApplyDynamic(tree.tpe) || (
            if (mode.inTappMode)
              tree.tpe.typeParams.isEmpty && hasPolymorphicApply
            else
              hasMonomorphicApply
          )
        }
        def shouldInsertApply(tree: Tree) = mode.typingExprFun && {
          tree.tpe match {
            case _: MethodType | _: OverloadedType | _: PolyType => false
            case _                                               => applyPossible
          }
        }
        if (tree.isType)
          adaptType()
        else if (mode.typingExprNotFun && treeInfo.isMacroApplication(tree) && !isMacroExpansionSuppressed(tree))
          macroExpand(this, tree, mode, pt)
        else if (mode.typingConstructorPattern)
          typedConstructorPattern(tree, pt)
        else if (shouldInsertApply(tree))
          insertApply()
        else if (hasUndetsInMonoMode) { // (9)
          assert(!context.inTypeConstructorAllowed, context) //@M
          instantiatePossiblyExpectingUnit(tree, mode, pt)
        }
        else if (tree.tpe <:< pt)
          tree
        else
          fallbackAfterVanillaAdapt()
      }

      // begin adapt
      if (isMacroImplRef(tree)) {
        if (treeInfo.isMacroApplication(tree)) adapt(unmarkMacroImplRef(tree), mode, pt, original)
        else tree
      } else tree.tpe match {
        case atp @ AnnotatedType(_, _) if canAdaptAnnotations(tree, this, mode, pt) => // (-1)
          adaptAnnotations(tree, this, mode, pt)
        case ct @ ConstantType(value) if mode.inNone(TYPEmode | FUNmode) && (ct <:< pt) && canAdaptConstantTypeToLiteral => // (0)
          // NOTE: need to guard against subsequent adaptations that might mess up the original
          adaptConstant(value)
        case OverloadedType(pre, alts) if !mode.inFunMode => // (1)
          inferExprAlternative(tree, pt)
          adaptAfterOverloadResolution(tree, mode, pt, original)
        case NullaryMethodType(restpe) => // (2)
          adapt(tree setType restpe, mode, pt, original)
        case TypeRef(_, ByNameParamClass, arg :: Nil) if mode.inExprMode => // (2)
          adapt(tree setType arg, mode, pt, original)
        case tp if mode.typingExprNotLhs && isExistentialType(tp) =>
          adapt(tree setType tp.dealias.skolemizeExistential(context.owner, tree), mode, pt, original)
        case PolyType(tparams, restpe) if mode.inNone(TAPPmode | PATTERNmode) && !context.inTypeConstructorAllowed => // (3)
          // assert((mode & HKmode) == 0) //@M a PolyType in HKmode represents an anonymous type function,
          // we're in HKmode since a higher-kinded type is expected --> hence, don't implicitly apply it to type params!
          // ticket #2197 triggered turning the assert into a guard
          // I guess this assert wasn't violated before because type aliases weren't expanded as eagerly
          //  (the only way to get a PolyType for an anonymous type function is by normalisation, which applies eta-expansion)
          // -- are we sure we want to expand aliases this early?
          // -- what caused this change in behaviour??
          val tparams1 = cloneSymbols(tparams)
          val tree1 = (
            if (tree.isType) tree
            else TypeApply(tree, tparams1 map (tparam => TypeTree(tparam.tpeHK) setPos tree.pos.focus)) setPos tree.pos
          )
          context.undetparams ++= tparams1
          notifyUndetparamsAdded(tparams1)
          adapt(tree1 setType restpe.substSym(tparams, tparams1), mode, pt, original)

        case mt: MethodType if mode.typingExprNotFunNotLhs && mt.isImplicit => // (4.1)
          adaptToImplicitMethod(mt)
        case mt: MethodType if mode.typingExprNotFunNotLhs && !hasUndetsInMonoMode && !treeInfo.isMacroApplicationOrBlock(tree) =>
          instantiateToMethodType(mt)
        case _ =>
          vanillaAdapt(tree)
      }
    }
    override protected def typedExistentialTypeTree(tree: ExistentialTypeTree, mode: Mode): Tree = {
      for (wc <- tree.whereClauses)
        if (wc.symbol == NoSymbol) { namer enterSym wc; wc.symbol setFlag EXISTENTIAL }
        else context.scope enter wc.symbol
      val whereClauses1 = typedStats(tree.whereClauses, context.owner)
      for (vd @ ValDef(_, _, _, _) <- whereClauses1)
        if (vd.symbol.tpe.isVolatile)
          AbstractionFromVolatileTypeError(vd)
      val tpt1 = typedType(tree.tpt, mode)
      existentialTransform(whereClauses1 map (_.symbol), tpt1.tpe)((tparams, tp) => {
        val original = (tpt1 match {
          case tpt : TypeTree => atPos(tree.pos)(ExistentialTypeTree(tpt.original, tree.whereClauses))
          case _ => {
            debuglog(s"cannot reconstruct the original for $tree, because $tpt1 is not a TypeTree")
            tree
          }
        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-}
        }).appendMetadata("typedExistentialTypeTree" -> treeCopy.ExistentialTypeTree(tree, tpt1, whereClauses1.require[List[MemberDef]]))
        TypeTree(newExistentialType(tparams, tp)) setOriginal original
      }
      )
    }
    override def typedTemplate(templ0: Template, parents1: List[Tree]): Template = {
      val Template(_, self0 @ ValDef(_, _, selftpt0, _), _) = templ0
      val result = super.typedTemplate(templ0, parents1)
      // NOTE: I wouldn't be surprised if `typedType` that we have to do here corrupts the typer, and everything blows up
      if (self0 != noSelfType && selftpt0.nonEmpty) result.self.appendMetadata("originalSelf" -> copyValDef(result.self)(tpt = typedType(selftpt0)))
      result
    }
    override def typedAnnotation(ann: Tree, mode: Mode = EXPRmode): AnnotationInfo = {
      var hasError: Boolean = false
      val pending = mutable.ListBuffer[AbsTypeError]()

      def finish(res: AnnotationInfo): AnnotationInfo = {
        if (hasError) {
          pending.foreach(ErrorUtils.issueTypeError)
          ErroneousAnnotation
        }
        else res
      }

      def reportAnnotationError(err: AbsTypeError) = {
        pending += err
        hasError = true
        ErroneousAnnotation
      }

      /* Calling constfold right here is necessary because some trees (negated
       * floats and literals in particular) are not yet folded.
       */
      def tryConst(tr: Tree, pt: Type): Option[LiteralAnnotArg] = {
        // The typed tree may be relevantly different than the tree `tr`,
        // e.g. it may have encountered an implicit conversion.
        val ttree = typed(constfold(tr), pt)
        val const: Constant = ttree match {
          case l @ Literal(c) if !l.isErroneous => c
          case tree => tree.tpe match {
            case ConstantType(c)  => c
            case tpe              => null
          }
        }

        if (const == null) {
          reportAnnotationError(AnnotationNotAConstantError(ttree)); None
        } else if (const.value == null) {
          reportAnnotationError(AnnotationArgNullError(tr)); None
        } else
          Some(LiteralAnnotArg(const))
      }

      /* Converts an untyped tree to a ClassfileAnnotArg. If the conversion fails,
       * an error message is reported and None is returned.
       */
      def tree2ConstArg(tree: Tree, pt: Type): Option[ClassfileAnnotArg] = tree match {
        case Apply(Select(New(tpt), nme.CONSTRUCTOR), args) if (pt.typeSymbol == ArrayClass) =>
          reportAnnotationError(ArrayConstantsError(tree)); None

        case ann @ Apply(Select(New(tpt), nme.CONSTRUCTOR), args) =>
          val annInfo = typedAnnotation(ann, mode)
          val annType = annInfo.tpe

          if (!annType.typeSymbol.isSubClass(pt.typeSymbol))
            reportAnnotationError(AnnotationTypeMismatchError(tpt, annType, annType))
          else if (!annType.typeSymbol.isSubClass(ClassfileAnnotationClass))
            reportAnnotationError(NestedAnnotationError(ann, annType))

          if (annInfo.atp.isErroneous) { hasError = true; None }
          else Some(NestedAnnotArg(annInfo))

        // use of Array.apply[T: ClassTag](xs: T*): Array[T]
        // and    Array.apply(x: Int, xs: Int*): Array[Int]       (and similar)
        case Apply(fun, args) =>
          val typedFun = typed(fun, mode.forFunMode)
          if (typedFun.symbol.owner == ArrayModule.moduleClass && typedFun.symbol.name == nme.apply)
            pt match {
              case TypeRef(_, ArrayClass, targ :: _) =>
                trees2ConstArg(args, targ)
              case _ =>
                // For classfile annotations, pt can only be T:
                //   BT = Int, .., String, Class[_], JavaAnnotClass
                //   T = BT | Array[BT]
                // So an array literal as argument can only be valid if pt is Array[_]
                reportAnnotationError(ArrayConstantsTypeMismatchError(tree, pt))
                None
            }
          else tryConst(tree, pt)

        case Typed(t, _) =>
          tree2ConstArg(t, pt)

        case tree =>
          tryConst(tree, pt)
      }
      def trees2ConstArg(trees: List[Tree], pt: Type): Option[ArrayAnnotArg] = {
        val args = trees.map(tree2ConstArg(_, pt))
        if (args.exists(_.isEmpty)) None
        else Some(ArrayAnnotArg(args.flatten.toArray))
      }

      // begin typedAnnotation
      val treeInfo.Applied(fun0, targs, argss) = ann
      if (fun0.isErroneous)
        return finish(ErroneousAnnotation)
      val typedFun0 = typed(fun0, mode.forFunMode)
      val typedFunPart = (
        // If there are dummy type arguments in typeFun part, it suggests we
        // must type the actual constructor call, not only the select. The value
        // arguments are how the type arguments will be inferred.
        if (targs.isEmpty && typedFun0.exists(t => t.tpe != null && isDummyAppliedType(t.tpe)))
          logResult(s"Retyped $typedFun0 to find type args")(typed(argss.foldLeft(fun0)(Apply(_, _))))
        else
          typedFun0
      )
      val treeInfo.Applied(typedFun @ Select(New(annTpt), _), _, _) = typedFunPart
      val annType = annTpt.tpe

      finish(
        if (typedFun.isErroneous)
          ErroneousAnnotation
        else if (annType.typeSymbol isNonBottomSubClass ClassfileAnnotationClass) {
          // annotation to be saved as java classfile annotation
          val isJava = typedFun.symbol.owner.isJavaDefined
          if (argss.length > 1) {
            reportAnnotationError(MultipleArgumentListForAnnotationError(ann))
          }
          else {
            val annScope = annType.decls
                .filter(sym => sym.isMethod && !sym.isConstructor && sym.isJavaDefined)
            val names = mutable.Set[Symbol]()
            names ++= (if (isJava) annScope.iterator
                       else typedFun.tpe.params.iterator)

            def hasValue = names exists (_.name == nme.value)
            val args = argss match {
              // NOTE: this is a meaningful difference from the code in Typers.scala
              //-case (arg :: Nil) :: Nil if !isNamedArg(arg) && hasValue => gen.mkNamedArg(nme.value, arg) :: Nil
              case (arg :: Nil) :: Nil if !isNamedArg(arg) && hasValue => gen.mkNamedArg(nme.value, arg).appendMetadata("insertedValue" -> arg) :: Nil
              case args :: Nil                                         => args
            }

            val nvPairs = args map {
              case arg @ AssignOrNamedArg(Ident(name), rhs) =>
                val sym = if (isJava) annScope.lookup(name)
                          else findSymbol(typedFun.tpe.params)(_.name == name)
                if (sym == NoSymbol) {
                  reportAnnotationError(UnknownAnnotationNameError(arg, name))
                  (nme.ERROR, None)
                } else if (!names.contains(sym)) {
                  reportAnnotationError(DuplicateValueAnnotationError(arg, name))
                  (nme.ERROR, None)
                } else {
                  names -= sym
                  if (isJava) sym.cookJavaRawInfo() // #3429
                  val annArg = tree2ConstArg(rhs, sym.tpe.resultType)
                  (sym.name, annArg)
                }
              case arg =>
                reportAnnotationError(ClassfileAnnotationsAsNamedArgsError(arg))
                (nme.ERROR, None)
            }
            for (sym <- names) {
              // make sure the flags are up to date before erroring (jvm/t3415 fails otherwise)
              sym.initialize
              if (!sym.hasAnnotation(AnnotationDefaultAttr) && !sym.hasDefault)
                reportAnnotationError(AnnotationMissingArgError(ann, annType, sym))
            }

            if (hasError) ErroneousAnnotation
            else AnnotationInfo(annType, List(), nvPairs map {p => (p._1, p._2.get)}).setOriginal(Apply(typedFun, args).setPos(ann.pos))
          }
        }
        else {
          val typedAnn: Tree = {
            // local dummy fixes SI-5544
            val localTyper = newTyper(context.make(ann, context.owner.newLocalDummy(ann.pos)))
            localTyper.typed(ann, mode, annType)
          }
          def annInfo(t: Tree): AnnotationInfo = t match {
            case Apply(Select(New(tpt), nme.CONSTRUCTOR), args) =>
              AnnotationInfo(annType, args, List()).setOriginal(typedAnn).setPos(t.pos)

            case Block(stats, expr) =>
              context.warning(t.pos, "Usage of named or default arguments transformed this annotation\n"+
                                "constructor call into a block. The corresponding AnnotationInfo\n"+
                                "will contain references to local values and default getters instead\n"+
                                "of the actual argument trees")
              annInfo(expr)

            case Apply(fun, args) =>
              context.warning(t.pos, "Implementation limitation: multiple argument lists on annotations are\n"+
                                     "currently not supported; ignoring arguments "+ args)
              annInfo(fun)

            case _ =>
              reportAnnotationError(UnexpectedTreeAnnotationError(t, typedAnn))
          }

          if (annType.typeSymbol == DeprecatedAttr && argss.flatten.size < 2)
            context.deprecationWarning(ann.pos, DeprecatedAttr, "@deprecated now takes two arguments; see the scaladoc.")

          if ((typedAnn.tpe == null) || typedAnn.tpe.isErroneous) ErroneousAnnotation
          else annInfo(typedAnn)
        }
      )
    }
    override def typed1(tree: Tree, mode: Mode, pt: Type): Tree = {
      def lookupInOwner(owner: Symbol, name: Name): Symbol = if (mode.inQualMode) rootMirror.missingHook(owner, name) else NoSymbol
      def lookupInRoot(name: Name): Symbol  = lookupInOwner(rootMirror.RootClass, name)
      def lookupInEmpty(name: Name): Symbol = rootMirror.EmptyPackageClass.info member name
      def lookupInQualifier(qual: Tree, name: Name): Symbol = (
        if (name == nme.ERROR || qual.tpe.widen.isErroneous)
          NoSymbol
        else lookupInOwner(qual.tpe.typeSymbol, name) orElse {
          NotAMemberError(tree, qual, name)
          NoSymbol
        }
      )
      def typedSelect(tree: Tree, qual: Tree, name: Name): Tree = {
        val t = typedSelectInternal(tree, qual, name)
        // Checking for OverloadedTypes being handed out after overloading
        // resolution has already happened.
        if (isPastTyper) t.tpe match {
          case OverloadedType(pre, alts) =>
            if (alts forall (s => (s.owner == ObjectClass) || (s.owner == AnyClass) || isPrimitiveValueClass(s.owner))) ()
            else if (settings.debug) printCaller(
              s"""|Select received overloaded type during $phase, but typer is over.
                  |If this type reaches the backend, we are likely doomed to crash.
                  |$t has these overloads:
                  |${alts map (s => "  " + s.defStringSeenAs(pre memberType s)) mkString "\n"}
                  |""".stripMargin
            )("")
          case _ =>
        }
        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-t
        t.appendMetadata("originalName" -> name)
      }
      def typedSelectInternal(tree: Tree, qual: Tree, name: Name): Tree = {
        def asDynamicCall = dyna.mkInvoke(context, tree, qual, name) map { t =>
          dyna.wrapErrors(t, (_.typed1(t, mode, pt)))
        }

        val sym = tree.symbol orElse member(qual, name) orElse {
          // symbol not found? --> try to convert implicitly to a type that does have the required
          // member.  Added `| PATTERNmode` to allow enrichment in patterns (so we can add e.g., an
          // xml member to StringContext, which in turn has an unapply[Seq] method)
          if (name != nme.CONSTRUCTOR && mode.inAny(EXPRmode | PATTERNmode)) {
            val qual1 = adaptToMemberWithArgs(tree, qual, name, mode, reportAmbiguous = true, saveErrors = true)
            if ((qual1 ne qual) && !qual1.isErrorTyped) {
              // NOTE: this is a meaningful difference from the code in Typers.scala
              //-return typed(treeCopy.Select(tree, qual1, name), mode, pt)
              return typed(treeCopy.Select(tree, qual1.appendMetadata("original" -> qual), name), mode, pt)
            }
          }
          NoSymbol
        }
        if (phase.erasedTypes && qual.isInstanceOf[Super] && tree.symbol != NoSymbol)
          qual setType tree.symbol.owner.tpe

        if (!reallyExists(sym)) {
          def handleMissing: Tree = {
            def errorTree = missingSelectErrorTree(tree, qual, name)
            def asTypeSelection = (
              if (context.unit.isJava && name.isTypeName) {
                // SI-3120 Java uses the same syntax, A.B, to express selection from the
                // value A and from the type A. We have to try both.
                atPos(tree.pos)(gen.convertToSelectFromType(qual, name)) match {
                  case EmptyTree => None
                  case tree1     => Some(typed1(tree1, mode, pt))
                }
              }
              else None
            )
            debuglog(s"""
              |qual=$qual:${qual.tpe}
              |symbol=${qual.tpe.termSymbol.defString}
              |scope-id=${qual.tpe.termSymbol.info.decls.hashCode}
              |members=${qual.tpe.members mkString ", "}
              |name=$name
              |found=$sym
              |owner=${context.enclClass.owner}
              """.stripMargin)

            // 1) Try converting a term selection on a java class into a type selection.
            // 2) Try expanding according to Dynamic rules.
            // 3) Try looking up the name in the qualifier.
            asTypeSelection orElse asDynamicCall getOrElse (lookupInQualifier(qual, name) match {
              case NoSymbol => setError(errorTree)
              case found    => typed1(tree setSymbol found, mode, pt)
            })
          }
          handleMissing
        }
        else {
          val tree1 = tree match {
            case Select(_, _) => treeCopy.Select(tree, qual, name)
            case SelectFromTypeTree(_, _) => treeCopy.SelectFromTypeTree(tree, qual, name)
          }
          val (result, accessibleError) = silent(_.require[ScalahostTyper].makeAccessible(tree1, sym, qual.tpe, qual)) match {
            case SilentTypeError(err: AccessTypeError) =>
              (tree1, Some(err))
            case SilentTypeError(err) =>
              SelectWithUnderlyingError(tree, err)
              return tree
            case SilentResultValue(treeAndPre) =>
              (stabilize(treeAndPre._1, treeAndPre._2, mode, pt), None)
          }

          result match {
            // could checkAccessible (called by makeAccessible) potentially have skipped checking a type application in qual?
            case SelectFromTypeTree(qual@TypeTree(), name) if qual.tpe.typeArgs.nonEmpty => // TODO: somehow the new qual is not checked in refchecks
              treeCopy.SelectFromTypeTree(
                result,
                (TypeTreeWithDeferredRefCheck(){ () => val tp = qual.tpe; val sym = tp.typeSymbolDirect
                  // will execute during refchecks -- TODO: make private checkTypeRef in refchecks public and call that one?
                  checkBounds(qual, tp.prefix, sym.owner, sym.typeParams, tp.typeArgs, "")
                  qual // you only get to see the wrapped tree after running this check :-p
                }) setType qual.tpe setPos qual.pos,
                name)
            case _ if accessibleError.isDefined =>
              // don't adapt constructor, SI-6074
              val qual1 = if (name == nme.CONSTRUCTOR) qual
                          else adaptToMemberWithArgs(tree, qual, name, mode, reportAmbiguous = false, saveErrors = false)
              if (!qual1.isErrorTyped && (qual1 ne qual))
                typed(Select(qual1, name) setPos tree.pos, mode, pt)
              else
                // before failing due to access, try a dynamic call.
                asDynamicCall getOrElse {
                  context.issue(accessibleError.get)
                  setError(tree)
                }
            case _ =>
              result
          }
        }
      }
      def tryWithFilterAndFilter(tree: Select, qual: Tree): Tree = {
        def warn(sym: Symbol) = context.deprecationWarning(tree.pos, sym, s"`withFilter' method does not yet exist on ${qual.tpe.widen}, using `filter' method instead")
        silent(_ => typedSelect(tree, qual, nme.withFilter)) orElse { _ =>
          silent(_ => typed1(Select(qual, nme.filter) setPos tree.pos, mode, pt)) match {
            case SilentResultValue(res) => warn(res.symbol) ; res
            case SilentTypeError(err)   => WithFilterError(tree, err)
          }
        }
      }
      def typedSelectOrSuperCall(tree: Select) = tree match {
        case Select(qual @ Super(_, _), nme.CONSTRUCTOR) =>
          // the qualifier type of a supercall constructor is its first parent class
          typedSelect(tree, typedSelectOrSuperQualifier(qual), nme.CONSTRUCTOR)
        case Select(qual, name) =>
          if (Statistics.canEnable) Statistics.incCounter(typedSelectCount)
          val qualTyped = checkDead(typedQualifier(qual, mode))
          val qualStableOrError = (
            if (qualTyped.isErrorTyped || !name.isTypeName || treeInfo.admitsTypeSelection(qualTyped))
              qualTyped
            else
              UnstableTreeError(qualTyped)
          )
          val tree1 = name match {
            case nme.withFilter if !settings.future => tryWithFilterAndFilter(tree, qualStableOrError)
            case _              => typedSelect(tree, qualStableOrError, name)
          }
          def sym = tree1.symbol
          if (tree.isInstanceOf[PostfixSelect])
            checkFeature(tree.pos, PostfixOpsFeature, name.decode)
          if (sym != null && sym.isOnlyRefinementMember && !sym.isMacro)
            checkFeature(tree1.pos, ReflectiveCallsFeature, sym.toString)

          qualStableOrError.symbol match {
            case s: Symbol if s.isRootPackage => treeCopy.Ident(tree1, name)
            case _                            => tree1
          }
      }
      def typedSingletonTypeTree(tree: SingletonTypeTree) = {
        val refTyped =
          context.withImplicitsDisabled {
            typed(tree.ref, MonoQualifierModes | mode.onlyTypePat, AnyRefTpe)
          }

        if (refTyped.isErrorTyped) {
          setError(tree)
        } else {
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-tree setType refTyped.tpe.resultType
          tree setType refTyped.tpe.resultType appendMetadata ("originalRef" -> refTyped)
          if (refTyped.isErrorTyped || treeInfo.admitsTypeSelection(refTyped)) tree
          else UnstableTreeError(tree)
        }
      }
      def typedCompoundTypeTree(tree: CompoundTypeTree) = {
        val templ = tree.templ
        val parents1 = templ.parents mapConserve (typedType(_, mode))

        // NOTE: this is a meaningful difference from the code in Typers.scala
        // TODO: figure out how to discern automatically inserted and explicitly written AnyRefs
        var originals = parents1.filter(_.tpe.typeSymbol != ObjectClass)
        templ.appendMetadata("originalParents" -> originals)

        // This is also checked later in typedStats, but that is too late for SI-5361, so
        // we eagerly check this here.
        for (stat <- templ.body if !treeInfo.isDeclarationOrTypeDef(stat))
          OnlyDeclarationsError(stat)

        if ((parents1 ++ templ.body) exists (_.isErrorTyped)) tree setType ErrorType
        else {
          val decls = newScope
          //Console.println("Owner: " + context.enclClass.owner + " " + context.enclClass.owner.id)
          val self = refinedType(parents1 map (_.tpe), context.enclClass.owner, decls, templ.pos)
          newTyper(context.make(templ, self.typeSymbol, decls)).typedRefinement(templ)
          templ updateAttachment CompoundTypeTreeOriginalAttachment(parents1, Nil) // stats are set elsewhere
          tree setType (if (templ.exists(_.isErroneous)) ErrorType else self) // Being conservative to avoid SI-5361
        }
      }
      def qualifies(sym: Symbol) = (
           sym.hasRawInfo
        && reallyExists(sym)
        && !(mode.typingConstructorPattern && sym.isMethod && !sym.isStable)
      )
      def typedIdent(tree: Tree, name: Name): Tree = {
        // setting to enable unqualified idents in empty package (used by the repl)
        def inEmptyPackage = if (settings.exposeEmptyPackage) lookupInEmpty(name) else NoSymbol

        def issue(err: AbsTypeError) = {
          // Avoiding some spurious error messages: see SI-2388.
          val suppress = reporter.hasErrors && (name startsWith tpnme.ANON_CLASS_NAME)
          if (!suppress)
            ErrorUtils.issueTypeError(err)

          setError(tree)
        }
          // ignore current variable scope in patterns to enforce linearity
        val startContext = if (mode.typingPatternOrTypePat) context.outer else context
        val nameLookup   = tree.symbol match {
          case NoSymbol   => startContext.lookupSymbol(name, qualifies)
          case sym        => LookupSucceeded(EmptyTree, sym)
        }
        import InferErrorGen._
        nameLookup match {
          case LookupAmbiguous(msg)         => issue(AmbiguousIdentError(tree, name, msg))
          case LookupInaccessible(sym, msg) => issue(AccessError(tree, sym, context, msg))
          case LookupNotFound               =>
            inEmptyPackage orElse lookupInRoot(name) match {
              case NoSymbol => issue(SymbolNotFoundError(tree, name, context.owner, startContext))
              case sym      => typed1(tree setSymbol sym, mode, pt)
                }
          case LookupSucceeded(qual, sym)   =>
            (// this -> Foo.this
            if (sym.isThisSym)
              typed1(This(sym.owner) setPos tree.pos, mode, pt)
          // Inferring classOf type parameter from expected type.  Otherwise an
          // actual call to the stubbed classOf method is generated, returning null.
            else if (isPredefClassOf(sym) && pt.typeSymbol == ClassClass && pt.typeArgs.nonEmpty) {
            // NOTE: this is a meaningful difference from the code in Typers.scala
            tree.appendMetadata("originalClassOf" -> Ident(name).setSymbol(sym))
            typedClassOf(tree, TypeTree(pt.typeArgs.head))
          }
          else {
              val pre1  = if (sym.isTopLevel) sym.owner.thisType else if (qual == EmptyTree) NoPrefix else qual.tpe
              val tree1 = if (qual == EmptyTree) tree else atPos(tree.pos)(Select(atPos(tree.pos.focusStart)(qual), name))
              val (tree2, pre2) = makeAccessible(tree1, sym, pre1, qual)
            // SI-5967 Important to replace param type A* with Seq[A] when seen from from a reference, to avoid
            //         inference errors in pattern matching.
              stabilize(tree2, pre2, mode, pt) modifyType dropIllegalStarTypes
            }) setAttachments tree.attachments
          }
        }
      def typedIdentOrWildcard(tree: Ident) = {
        val name = tree.name
        if (Statistics.canEnable) Statistics.incCounter(typedIdentCount)
        if ((name == nme.WILDCARD && mode.typingPatternNotConstructor) ||
            (name == tpnme.WILDCARD && mode.inTypeMode))
          tree setType makeFullyDefined(pt)
        else
          typedIdent(tree, name)
      }
      def typedAssign(lhs: Tree, rhs: Tree): Tree = {
        // see SI-7617 for an explanation of why macro expansion is suppressed
        def typedLhs(lhs: Tree) = typed(lhs, EXPRmode | LHSmode)
        val lhs1    = unsuppressMacroExpansion(typedLhs(suppressMacroExpansion(lhs)))
        val varsym  = lhs1.symbol

        // see #2494 for double error message example
        def fail() =
          if (lhs1.isErrorTyped) lhs1
          else AssignmentError(tree, varsym)

        if (varsym == null)
          return fail()

        if (treeInfo.mayBeVarGetter(varsym)) {
          lhs1 match {
            case treeInfo.Applied(core @ Select(qual, name), _, _) =>
              val sel = Select(qual, name.setterName) setPos lhs.pos
              val app = Apply(sel, List(rhs)) setPos tree.pos
              // NOTE: this is a meaningful difference from the code in Typers.scala
              //-return typed(app, mode, pt)
              val result @ treeInfo.Applied(_, _, List(typedRhs) :: _) = typed(app, mode, pt)
              return result.appendMetadata("originalAssign" -> (core, nme.EQL, List(typedRhs)))

            case _ =>
          }
        }
//      if (varsym.isVariable ||
//        // setter-rewrite has been done above, so rule out methods here, but, wait a minute, why are we assigning to non-variables after erasure?!
//        (phase.erasedTypes && varsym.isValue && !varsym.isMethod)) {
        if (varsym.isVariable || varsym.isValue && phase.erasedTypes) {
          val rhs1 = typedByValueExpr(rhs, lhs1.tpe)
          treeCopy.Assign(tree, lhs1, checkDead(rhs1)) setType UnitTpe
        }
        else if(dyna.isDynamicallyUpdatable(lhs1)) {
          val rhs1 = typedByValueExpr(rhs)
          val t = atPos(lhs1.pos.withEnd(rhs1.pos.end)) {
            Apply(lhs1, List(rhs1))
          }
          dyna.wrapErrors(t, _.typed1(t, mode, pt))
        }
        else fail()
      }
      def functionTypeWildcard(tree: Tree, arity: Int): Type = {
        val tp = functionType(List.fill(arity)(WildcardType), WildcardType)
        if (tp == NoType) MaxFunctionArityError(tree)
        tp
      }
      def typedEta(expr1: Tree): Tree = expr1.tpe match {
        case TypeRef(_, ByNameParamClass, _) =>
          val expr2 = Function(List(), expr1) setPos expr1.pos
          new ChangeOwnerTraverser(context.owner, expr2.symbol).traverse(expr2)
          typed1(expr2, mode, pt)
        case NullaryMethodType(restpe) =>
          val expr2 = Function(List(), expr1) setPos expr1.pos
          new ChangeOwnerTraverser(context.owner, expr2.symbol).traverse(expr2)
          typed1(expr2, mode, pt)
        case PolyType(_, MethodType(formals, _)) =>
          if (isFunctionType(pt)) expr1
          else adapt(expr1, mode, functionTypeWildcard(expr1, formals.length))
        case MethodType(formals, _) =>
          if (isFunctionType(pt)) expr1
          else adapt(expr1, mode, functionTypeWildcard(expr1, formals.length))
        case ErrorType =>
          expr1
        case _ =>
          UnderscoreEtaError(expr1)
      }
      def typedTyped(tree: Typed) = {
        if (treeInfo isWildcardStarType tree.tpt)
          typedStarInPattern(tree, mode.onlySticky, pt)
        else if (mode.inPatternMode)
          typedInPattern(tree, mode.onlySticky, pt)
        else tree match {
          // find out whether the programmer is trying to eta-expand a macro def
          // to do that we need to typecheck the tree first (we need a symbol of the eta-expandee)
          // that typecheck must not trigger macro expansions, so we explicitly prohibit them
          // however we cannot do `context.withMacrosDisabled`
          // because `expr` might contain nested macro calls (see SI-6673)
          //
          // Note: apparently `Function(Nil, EmptyTree)` is the secret parser marker
          // which means trailing underscore.
          case Typed(expr, Function(Nil, EmptyTree)) =>
            typed1(suppressMacroExpansion(expr), mode, pt) match {
              case macroDef if treeInfo.isMacroApplication(macroDef) =>
                MacroEtaError(macroDef)
              case exprTyped =>
                // NOTE: this is a meaningful difference from the code in Typers.scala
                //-typedEta(checkDead(exprTyped))
                typedEta(checkDead(exprTyped)).appendMetadata("originalManualEta" -> exprTyped)
            }
          case Typed(expr, tpt) =>
            val tpt1  = typedType(tpt, mode)                           // type the ascribed type first
            val expr1 = typed(expr, mode.onlySticky, tpt1.tpe.deconst) // then type the expression with tpt1 as the expected type
            treeCopy.Typed(tree, expr1, tpt1) setType tpt1.tpe
        }
      }
      def tryTypedArgs(args: List[Tree], mode: Mode): Option[List[Tree]] = {
        val c = context.makeSilent(reportAmbiguousErrors = false)
        c.retyping = true
        try {
          val res = newTyper(c).typedArgs(args, mode)
          if (c.reporter.hasErrors) None else Some(res)
        } catch {
          case ex: CyclicReference =>
            throw ex
          case te: TypeError =>
            // @H some of typer errors can still leak,
            // for instance in continuations
            None
        }
      }
      // convert new Array[T](len) to evidence[ClassTag[T]].newArray(len)
      // convert new Array^N[T](len) for N > 1 to evidence[ClassTag[Array[...Array[T]...]]].newArray(len)
      // where Array HK gets applied (N-1) times
      object ArrayInstantiation {
        def unapply(tree: Apply) = tree match {
          case Apply(Select(New(tpt), name), arg :: Nil) if tpt.tpe != null && tpt.tpe.typeSymbol == ArrayClass =>
            Some(tpt.tpe) collect {
              case erasure.GenericArray(level, componentType) =>
                val tagType = (1 until level).foldLeft(componentType)((res, _) => arrayType(res))

                resolveClassTag(tree.pos, tagType) match {
                  case EmptyTree => MissingClassTagError(tree, tagType)
                  case tag       => atPos(tree.pos)(new ApplyToImplicitArgs(Select(tag, nme.newArray), arg :: Nil))
                }
            }
          case _ => None
        }
      }
      def convertToAssignment(fun: Tree, qual: Tree, name: Name, args: List[Tree]): Tree = {
        val prefix = name.toTermName stripSuffix nme.EQL
        def mkAssign(vble: Tree): Tree =
          Assign(
            vble,
            Apply(
              Select(vble.duplicate, prefix) setPos fun.pos.focus, args) setPos tree.pos.makeTransparent
          ) setPos tree.pos

        def mkUpdate(table: Tree, indices: List[Tree]) = {
          gen.evalOnceAll(table :: indices, context.owner, context.unit) {
            case tab :: is =>
              def mkCall(name: Name, extraArgs: Tree*) = (
                Apply(
                  Select(tab(), name) setPos table.pos,
                  is.map(i => i()) ++ extraArgs
                ) setPos tree.pos
              )
              mkCall(
                nme.update,
                Apply(Select(mkCall(nme.apply), prefix) setPos fun.pos, args) setPos tree.pos
              )
            case _ => EmptyTree
          }
        }

        val tree1 = qual match {
          case Ident(_) =>
            mkAssign(qual)

          case Select(qualqual, vname) =>
            gen.evalOnce(qualqual, context.owner, context.unit) { qq =>
              val qq1 = qq()
              mkAssign(Select(qq1, vname) setPos qual.pos)
            }

          case Apply(fn, indices) =>
            fn match {
              case treeInfo.Applied(Select(table, nme.apply), _, _) => mkUpdate(table, indices)
              case _  => UnexpectedTreeAssignmentConversionError(qual)
            }
        }
        // NOTE: this is a meaningful difference from the code in Typers.scala
        //-typed1(tree1, mode, pt)
        typed1(tree1, mode, pt).appendMetadata("originalAssign" -> (qual, name, args)) // TODO: args here might be untyped!!
      }
      def tryTypedApply(fun: Tree, args: List[Tree]): Tree = {
        val start = if (Statistics.canEnable) Statistics.startTimer(failedApplyNanos) else null

        def onError(typeErrors: Seq[AbsTypeError], warnings: Seq[(Position, String)]): Tree = {
          if (Statistics.canEnable) Statistics.stopTimer(failedApplyNanos, start)

          // If the problem is with raw types, copnvert to existentials and try again.
          // See #4712 for a case where this situation arises,
          if ((fun.symbol ne null) && fun.symbol.isJavaDefined) {
            val newtpe = rawToExistential(fun.tpe)
            if (fun.tpe ne newtpe) {
              // println("late cooking: "+fun+":"+fun.tpe) // DEBUG
              return tryTypedApply(fun setType newtpe, args)
            }
          }
          def treesInResult(tree: Tree): List[Tree] = tree :: (tree match {
            case Block(_, r)                        => treesInResult(r)
            case Match(_, cases)                    => cases
            case CaseDef(_, _, r)                   => treesInResult(r)
            case Annotated(_, r)                    => treesInResult(r)
            case If(_, t, e)                        => treesInResult(t) ++ treesInResult(e)
            case Try(b, catches, _)                 => treesInResult(b) ++ catches
            case Typed(r, Function(Nil, EmptyTree)) => treesInResult(r)
            case Select(qual, name)                 => treesInResult(qual)
            case Apply(fun, args)                   => treesInResult(fun) ++ args.flatMap(treesInResult)
            case TypeApply(fun, args)               => treesInResult(fun) ++ args.flatMap(treesInResult)
            case _                                  => Nil
          })
          def errorInResult(tree: Tree) = treesInResult(tree) exists (err => typeErrors.exists(_.errPos == err.pos))

          val retry = (typeErrors.forall(_.errPos != null)) && (fun :: tree :: args exists errorInResult)
          typingStack.printTyping({
            val funStr = ptTree(fun) + " and " + (args map ptTree mkString ", ")
            if (retry) "second try: " + funStr
            else "no second try: " + funStr + " because error not in result: " + typeErrors.head.errPos+"!="+tree.pos
          })
          if (retry) {
            val Select(qual, name) = fun
            tryTypedArgs(args, forArgMode(fun, mode)) match {
              case Some(args1) if !args1.exists(arg => arg.exists(_.isErroneous)) =>
                val qual1 =
                  if (!pt.isError) adaptToArguments(qual, name, args1, pt, reportAmbiguous = true, saveErrors = true)
                  else qual
                if (qual1 ne qual) {
                  val tree1 = Apply(Select(qual1, name) setPos fun.pos, args1) setPos tree.pos
                  return context withinSecondTry typed1(tree1, mode, pt)
                }
              case _ => ()
            }
          }
          typeErrors foreach context.issue
          warnings foreach { case (p, m) => context.warning(p, m) }
          setError(treeCopy.Apply(tree, fun, args))
        }

        silent(_.doTypedApply(tree, fun, args, mode, pt)) match {
          case SilentResultValue(value) => value
          case e: SilentTypeError => onError(e.errors, e.warnings)
        }
      }
      def normalTypedApply(tree: Tree, fun: Tree, args: List[Tree]) = {
        // TODO: replace `fun.symbol.isStable` by `treeInfo.isStableIdentifierPattern(fun)`
        val stableApplication = (fun.symbol ne null) && fun.symbol.isMethod && fun.symbol.isStable
        val funpt = if (mode.inPatternMode) pt else WildcardType
        val appStart = if (Statistics.canEnable) Statistics.startTimer(failedApplyNanos) else null
        val opeqStart = if (Statistics.canEnable) Statistics.startTimer(failedOpEqNanos) else null

        def onError(reportError: => Tree): Tree = fun match {
          case Select(qual, name) if !mode.inPatternMode && nme.isOpAssignmentName(newTermName(name.decode)) =>
            val qual1 = typedQualifier(qual)
            if (treeInfo.isVariableOrGetter(qual1)) {
              if (Statistics.canEnable) Statistics.stopTimer(failedOpEqNanos, opeqStart)
              convertToAssignment(fun, qual1, name, args)
            }
            else {
              if (Statistics.canEnable) Statistics.stopTimer(failedApplyNanos, appStart)
                reportError
            }
          case _ =>
            if (Statistics.canEnable) Statistics.stopTimer(failedApplyNanos, appStart)
            reportError
        }
        val silentResult = silent(
          op                    = _.typed(fun, mode.forFunMode, funpt),
          reportAmbiguousErrors = !mode.inExprMode && context.ambiguousErrors,
          newtree               = if (mode.inExprMode) tree else context.tree
        )
        silentResult match {
          case SilentResultValue(fun1) =>
            val fun2 = if (stableApplication) stabilizeFun(fun1, mode, pt) else fun1
            if (Statistics.canEnable) Statistics.incCounter(typedApplyCount)
            val noSecondTry = (
                 isPastTyper
              || context.inSecondTry
              || (fun2.symbol ne null) && fun2.symbol.isConstructor
              || isImplicitMethodType(fun2.tpe)
            )
            val isFirstTry = fun2 match {
              case Select(_, _) => !noSecondTry && mode.inExprMode
              case _            => false
            }
            if (isFirstTry)
              tryTypedApply(fun2, args)
            else
              doTypedApply(tree, fun2, args, mode, pt)
          case err: SilentTypeError =>
            onError({
              err.reportableErrors foreach context.issue
              err.warnings foreach { case (p, m) => context.warning(p, m) }
              args foreach (arg => typed(arg, mode, ErrorType))
              setError(tree)
            })
        }
      }
      def typedApply(tree: Apply) = tree match {
        case Apply(Block(stats, expr), args) =>
          // NOTE: this is a meaningful difference from the code in Typers.scala
          //-typed1(atPos(tree.pos)(Block(stats, Apply(expr, args) setPos tree.pos.makeTransparent)), mode, pt)
          typed1(atPos(tree.pos)(Block(stats, treeCopy.Apply(tree, expr, args) setPos tree.pos.makeTransparent)), mode, pt)
        case Apply(fun, args) =>
          normalTypedApply(tree, fun, args) match {
            // NOTE: this is a meaningful difference from the code in Typers.scala
            //-case ArrayInstantiation(tree1)                                           => typed(tree1, mode, pt)
            case tree1 @ ArrayInstantiation(tree2)                                   => typed(tree2, mode, pt).appendMetadata("originalArray" -> tree1)
            case Apply(Select(fun, nme.apply), _) if treeInfo.isSuperConstrCall(fun) => TooManyArgumentListsForConstructor(tree) //SI-5696
            case tree1                                                               => tree1
          }
      }
      def typedAnnotated(atd: Annotated): Tree = {
        val ann = atd.annot
        val arg1 = typed(atd.arg, mode, pt)
        /* mode for typing the annotation itself */
        val annotMode = (mode &~ TYPEmode) | EXPRmode

        def resultingTypeTree(tpe: Type) = {
          // we need symbol-ful originals for reification
          // hence we go the extra mile to hand-craft tis guy
          val original = arg1 match {
            // NOTE: this is a meaningful difference from the code in Typers.scala
            //-case tt @ TypeTree() if tt.original != null => Annotated(ann, tt.original)
            case tt @ TypeTree() if tt.original != null => Annotated(ann, tt.original.appendMetadata("originalAnnottee" -> arg1))
            // this clause is needed to correctly compile stuff like "new C @D" or "@(inline @getter)"
            // NOTE: this is a meaningful difference from the code in Typers.scala
            //-case _ => Annotated(ann, arg1.appendMetadata("originalAnnottee" -> arg1))
            case _ => Annotated(ann, arg1.appendMetadata("originalAnnottee" -> arg1))
          }
          original setType ann.tpe
          TypeTree(tpe) setOriginal original setPos tree.pos.focus
        }

        if (arg1.isType) {
          // make sure the annotation is only typechecked once
          if (ann.tpe == null) {
            val ainfo = typedAnnotation(ann, annotMode)
            val atype = arg1.tpe.withAnnotation(ainfo)

            if (ainfo.isErroneous)
              // Erroneous annotations were already reported in typedAnnotation
              arg1  // simply drop erroneous annotations
            else {
              ann setType atype
              resultingTypeTree(atype)
            }
          } else {
            // the annotation was typechecked before
            resultingTypeTree(ann.tpe)
          }
        }
        else {
          if (ann.tpe == null) {
            val annotInfo = typedAnnotation(ann, annotMode)
            ann setType arg1.tpe.withAnnotation(annotInfo)
          }
          val atype = ann.tpe
          Typed(arg1, resultingTypeTree(atype)) setPos tree.pos setType atype
        }
      }
      def typedBind(tree: Bind): Tree = {
        val name = tree.name
        val body = tree.body
        name match {
          case name: TypeName  => assert(body == EmptyTree, context.unit + " typedBind: " + name.debugString + " " + body + " " + body.getClass)
            val sym =
              if (tree.symbol != NoSymbol) tree.symbol
              else {
                val result = {
                  if (isFullyDefined(pt)) context.owner.newAliasType(name, tree.pos) setInfo pt
                  else context.owner.newAbstractType(name, tree.pos) setInfo TypeBounds.empty
                }
                // NOTE: this is a meaningful difference from the code in Typers.scala
                //-result
                result.appendMetadata("isPatternVariable" -> true)
              }

            if (name != tpnme.WILDCARD) namer.enterInScope(sym)
            else context.scope.enter(sym)

            tree setSymbol sym setType sym.tpeHK

          case name: TermName  =>
            val sym =
              if (tree.symbol != NoSymbol) tree.symbol
              else {
                // NOTE: this is a meaningful difference from the code in Typers.scala
                //-context.owner.newValue(name, tree.pos)
                context.owner.newValue(name, tree.pos).appendMetadata("isPatternVariable" -> true)
              }

            if (name != nme.WILDCARD) {
              if (context.inPatAlternative)
                VariableInPatternAlternativeError(tree)

              namer.enterInScope(sym)
            }

            val body1 = typed(body, mode, pt)
            val impliedType = patmat.binderTypeImpliedByPattern(body1, pt, sym) // SI-1503, SI-5204
            val symTp =
              if (treeInfo.isSequenceValued(body)) seqType(impliedType)
              else impliedType
            sym setInfo symTp

            // have to imperatively set the symbol for this bind to keep it in sync with the symbols used in the body of a case
            // when type checking a case we imperatively update the symbols in the body of the case
            // those symbols are bound by the symbols in the Binds in the pattern of the case,
            // so, if we set the symbols in the case body, but not in the patterns,
            // then re-type check the casedef (for a second try in typedApply for example -- SI-1832),
            // we are no longer in sync: the body has symbols set that do not appear in the patterns
            // since body1 is not necessarily equal to body, we must return a copied tree,
            // but we must still mutate the original bind
            tree setSymbol sym
            treeCopy.Bind(tree, name, body1) setSymbol sym setType body1.tpe
        }
      }
      // ========================
      // NOTE: The code above is almost completely copy/pasted from Typers.scala.
      // The changes there are mostly mechanical (indentation), but those, which are non-trivial (e.g. appending metadata to trees)
      // are denoted with //- and //+ comments that designate diffs from the original code.
      // I would gladly do away with the copy/paste, which is not only plain ugly, but also imposes high maintainability tax,
      // but I can't do that, because `typedSelect` needs to remember the original qualifier, and I can't override it, because it's a local method.
      // ========================
      if (isPastTyper) super.typed1(tree, mode, pt)
      else {
        val result = tree match {
          case tree @ Ident(name) => typedIdentOrWildcard(tree)
          case tree @ Select(qual, name) => typedSelectOrSuperCall(tree)
          case tree @ SingletonTypeTree(ref) => typedSingletonTypeTree(tree)
          case tree @ CompoundTypeTree(templ) => typedCompoundTypeTree(tree)
          case tree @ Assign(lhs, rhs) => typedAssign(lhs, rhs)
          case tree @ Typed(expr, tpt) => typedTyped(tree)
          case tree @ Apply(fun, args) => typedApply(tree)
          case tree @ Annotated(annot, arg) => typedAnnotated(tree)
          case tree @ Bind(name, body) => typedBind(tree)
          case _ => super.typed1(tree, mode, pt)
        }
        // TODO: wat do these methods even mean, and how do they differ?
        if (result.isErroneous || result.isErrorTyped) result
        else tree match {
          case Ident(_) => result.appendMetadata("originalIdent" -> tree)
          case Super(qual @ This(_), _) => result.appendMetadata("originalThis" -> qual)
          case Apply(_, _) => result.appendMetadata("originalApply" -> tree)
          case _ => result
        }
      }
    }
  }
}