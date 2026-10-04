import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class SuccessionFlowIndex(
  val context: ExtractionContextIndex,
  val bindings: BindingFactIndex,
  val lifecycle: ValueLifecycleFlowIndex,
  val followers: FollowerFlowIndex,
  val collections: CollectionFlowIndex
) {
  import IteratorParsing.*
  import context.*
  import bindings.*
  import lifecycle.*
  import followers.*
  import collections.*
  def bindingPartitionFor(
    declaration: VarDecl,
    writes: List[WriteInfo],
    fixedFlow: FixedValueFlow
  ): BindingPartition = {
    val contextualWrites = writes.map { write =>
      iteratorContextByMethod.get(write.method) match
        case Some(contextKey) => write.copy(insideLoop = true, loopKey = Some(contextKey))
        case None => write
    }
    val ordered = contextualWrites.sortBy(write => (write.method, write.line, write.column, write.eventId))
    val explicitIntroductionIds = ordered.filter(write =>
      write.declarationInitializer && write.loopKey.forall(!_.startsWith("iterator:"))
    ).map(_.eventId).toSet
    val constructorInitializationIds =
      if declaration.kind != "member" then Set.empty[Long]
      else memberOwnerById.get(declaration.id).toSet.flatMap { owner =>
        ordered.filter(write => isInitializationMethodFullName(write.method, owner))
          .map(_.eventId)
      }
    profiler.increment("constructor_initialization_writes", constructorInitializationIds.size.toLong)
    val provedIds = fixedFlow.initializationEventIds.filter(eventId =>
      ordered.find(_.eventId == eventId).forall(write => !write.loopKey.exists(_.startsWith("iterator:")))
    )
    val firstSourceBackedInitialization = ordered.headOption.filter { first =>
      declaration.kind == "local" && first.directWrite &&
        first.operator == "<operator>.assignment" && !first.selfRef && !first.insideLoop &&
        first.line > 0 && !followerReadsByDecl.getOrElse(declaration.id, Nil).exists { read =>
          read.method == first.method &&
            (read.line < first.line || (read.line == first.line && read.column < first.column))
        }
    }.map(_.eventId).toSet
    val initializationIds =
      if declaration.kind == "parameter" then Set.empty[Long]
      else if constructorInitializationIds.nonEmpty then constructorInitializationIds ++ explicitIntroductionIds
      else if provedIds.nonEmpty then provedIds
      else if explicitIntroductionIds.nonEmpty then explicitIntroductionIds
      else firstSourceBackedInitialization
    BindingPartition(
      initializationIds,
      ordered.filterNot(write => initializationIds.contains(write.eventId)),
      analysisComplete = declaration.kind == "parameter" || initializationIds.nonEmpty ||
        (declaration.kind == "member" && memberOwnerById.contains(declaration.id)) ||
        (declaration.kind == "local" && ordered.nonEmpty && ordered.forall(_.loopKey.exists(_.startsWith("iterator:"))))
    )
  }

  val stepperEventContextCache = scala.collection.mutable.Map.empty[Long, StepperEventContext]
  def progressionEventContext(eventId: Long, method: String, fallbackLoopKey: Option[String]): StepperEventContext =
    stepperEventContextCache.getOrElseUpdate(eventId, {
      callById.get(eventId) match
        case None => StepperEventContext(fallbackLoopKey, analysisComplete = fallbackLoopKey.nonEmpty)
        case Some(call) =>
          var current = scala.util.Try(call.astParent).toOption
          val seen = scala.collection.mutable.Set[Long]()
          val enclosing = scala.collection.mutable.ListBuffer[ControlStructure]()
          var nearest: Option[ControlStructure] = None
          var reachedMethod = false
          while current.nonEmpty && !reachedMethod && seen.size < 128 && !seen.contains(current.get.id) do
            val node = current.get
            seen += node.id
            node match
              case _: Method => reachedMethod = true
              case control: ControlStructure if isLoopType(control.controlStructureType) =>
                enclosing += control
                if nearest.isEmpty then nearest = Some(control)
              case _ => ()
            if !reachedMethod then current = scala.util.Try(node.astParent).toOption
          val astKey = nearest.filter(control => cachedScopeOf(control) == method).flatMap { control =>
            control.lineNumber.map(_.toInt).filter(_ > 0).map { start =>
              s"${method}:$start:${controlStructureEnd(control, start)}"
            }
          }
          if astKey.nonEmpty && astKey != fallbackLoopKey then profiler.increment("progression_contexts_recovered_ast")
          // A complete callable-local AST with no loop is authoritative. This
          // prevents a lambda or a following statement inheriting a source range.
          val fallback = if reachedMethod && nearest.isEmpty then
            fallbackLoopKey.filter(_.startsWith("iterator:")) else fallbackLoopKey
          StepperEventContext(astKey.orElse(fallback),
            analysisComplete = reachedMethod && (nearest.isEmpty || astKey.nonEmpty),
            enclosingLoopKeys = enclosing.filter(control => cachedScopeOf(control) == method).flatMap(control =>
              control.lineNumber.map(_.toInt).filter(_ > 0).map(start =>
                s"$method:$start:${controlStructureEnd(control, start)}")).toSet ++ astKey.orElse(fallback))
    })


  def stepperEventContext(write: WriteInfo): StepperEventContext =
    progressionEventContext(write.eventId, write.method, write.loopKey)

  def selectionIndexFingerprint(expression: RhsExpr): Option[String] = expression match
    case RhsLiteral(code) => Some(s"literal:${code.trim}")
    case RhsDeclarationRef(id) => Some(s"declaration:$id")
    case RhsSelfRef(id) => Some(s"self:$id")
    case RhsUnary(operator, operand) => selectionIndexFingerprint(operand).map(value => s"$operator($value)")
    case RhsBinary(operator, left, right) => selectionIndexFingerprint(left).flatMap { leftKey =>
      selectionIndexFingerprint(right).map(rightKey => s"$operator($leftKey,$rightKey)")
    }
    case _ => None

  def selectionPath(expression: RhsExpr): Option[(Long, List[String])] = expression match
    case RhsDeclarationRef(id) => Some((id, Nil))
    case RhsSelfRef(id) => Some((id, Nil))
    case RhsMemberAccess(receiver, memberId, memberName, _) =>
      selectionPath(receiver).map { case (root, path) =>
        (root, path :+ memberId.map(id => s"id:$id").getOrElse(s"name:$memberName"))
      }
    case RhsIndexAccess(receiver, index) => selectionPath(receiver).flatMap { case (root, path) =>
      selectionIndexFingerprint(index).map(indexKey => (root, path :+ s"index:$indexKey"))
    }
    case RhsDereference(operand) => selectionPath(operand)
    case _ => None

  def projectionOf(base: RhsExpr, projected: RhsExpr): Boolean =
    (selectionPath(base), selectionPath(projected)) match
      case (Some((baseRoot, basePath)), Some((projectedRoot, projectedPath))) =>
        baseRoot == projectedRoot && basePath.size <= projectedPath.size &&
          basePath.zip(projectedPath).forall(_ == _)
      case _ => false

  val pureMostWantedCalls = Set("abs", "fabs", "labs", "llabs")
  val candidateFingerprintCache = scala.collection.mutable.Map.empty[RhsExpr, CandidateFingerprint]
  def candidateFingerprint(expression: RhsExpr): CandidateFingerprint =
    candidateFingerprintCache.getOrElseUpdate(expression, {
      def build(operator: String, children: List[RhsExpr], literal: Option[String] = None,
                path: List[String] = Nil, pure: Boolean = true): CandidateFingerprint = {
        val childFingerprints = children.map(candidateFingerprint)
        CandidateFingerprint(operator, rhsDependencies(expression).toList.sorted, childFingerprints,
          literal, path, complete = isCompleteRhs(expression) && childFingerprints.forall(_.complete),
          pure = pure && childFingerprints.forall(_.pure))
      }
      expression match
        case RhsLiteral(code) => build("literal", Nil, Some(code.trim))
        case RhsStandardConstant(code) => build("standard_constant", Nil, Some(code.trim.toLowerCase))
        case RhsDeclarationRef(id) => build(s"declaration:$id", Nil)
        case RhsSelfRef(id) => build(s"self:$id", Nil)
        case RhsExternalReference(name) => build(s"external:$name", Nil, pure = false)
        case RhsUnary(operator, operand) => build(s"unary:$operator", List(operand))
        case RhsBinary(operator, left, right) =>
          val ordered = if Set("+", "*", "==", "!=").contains(operator) then
            List(left, right).sortBy(child => candidateFingerprint(child).toString)
          else List(left, right)
          build(s"binary:$operator", ordered)
        case RhsCall(name, arguments) =>
          val resolved = name.trim.toLowerCase.replace("::", ".").split("[.]").lastOption.getOrElse("")
          build(s"call:$resolved", arguments, pure = pureMostWantedCalls.contains(resolved))
        case member @ RhsMemberAccess(receiver, memberId, memberName, _) =>
          if memberId.isEmpty && memberName.nonEmpty then profiler.increment("mwh_member_name_fallback_paths")
          build("projection", List(receiver), path = selectionPath(member).map(_._2).getOrElse(Nil),
            pure = memberId.nonEmpty || (memberName.nonEmpty && selectionPath(receiver).nonEmpty))
        case indexed @ RhsIndexAccess(receiver, index) =>
          profiler.increment("mwh_indexed_candidate_paths")
          build("index", List(receiver, index), path = selectionPath(indexed).map(_._2).getOrElse(Nil), pure = true)
        case RhsDereference(operand) => build("dereference", List(operand))
        case RhsUnknown(kind) => build(s"unknown:$kind", Nil, pure = false)
    })

  def equivalentPureCandidate(left: RhsExpr, right: RhsExpr): Boolean = {
    val leftFingerprint = candidateFingerprint(left)
    val rightFingerprint = candidateFingerprint(right)
    leftFingerprint.complete && rightFingerprint.complete && leftFingerprint.pure &&
      rightFingerprint.pure && leftFingerprint == rightFingerprint
  }

  def nullSentinelExpression(expression: RhsExpr): Boolean = expression match
    case RhsLiteral(code) => Set("none", "null", "nullptr", "nil").contains(code.trim.toLowerCase)
    case _ => false

  def bootstrapCondition(holderId: Long, expression: RhsExpr): Boolean = expression match
    case RhsBinary("==", left, right) =>
      (projectionOf(RhsSelfRef(holderId), left) && nullSentinelExpression(right)) ||
        (projectionOf(RhsSelfRef(holderId), right) && nullSentinelExpression(left))
    case _ => false

  def candidateRelation(
    holderId: Long,
    assigned: RhsAnalysis,
    left: RhsExpr,
    right: RhsExpr,
    operator: String
  ): Option[(CandidateRelation, SelectionDirection)] = {
    def normalizedScoreShape(expression: RhsExpr, varyingRootId: Long): String = expression match
      case RhsDeclarationRef(id) if id == varyingRootId => "$selection"
      case RhsSelfRef(id) if id == varyingRootId => "$selection"
      case RhsDeclarationRef(id) => s"decl:$id"
      case RhsSelfRef(id) => s"self:$id"
      case RhsLiteral(code) => s"lit:${code.trim}"
      case RhsStandardConstant(code) => s"constant:${code.trim.toLowerCase}"
      case RhsExternalReference(name) => s"external:$name"
      case RhsUnary(op, operand) => s"unary:$op(${normalizedScoreShape(operand, varyingRootId)})"
      case RhsBinary(op, lhs, rhs) =>
        s"binary:$op(${normalizedScoreShape(lhs, varyingRootId)},${normalizedScoreShape(rhs, varyingRootId)})"
      case RhsCall(name, arguments) =>
        s"call:${name.trim.toLowerCase}(${arguments.map(normalizedScoreShape(_, varyingRootId)).mkString(",")})"
      case RhsMemberAccess(receiver, memberId, memberName, indirect) =>
        s"member:${memberId.map(_.toString).getOrElse(memberName)}:$indirect(${normalizedScoreShape(receiver, varyingRootId)})"
      case RhsIndexAccess(receiver, index) =>
        s"index(${normalizedScoreShape(receiver, varyingRootId)},${normalizedScoreShape(index, varyingRootId)})"
      case RhsDereference(operand) => s"deref(${normalizedScoreShape(operand, varyingRootId)})"
      case RhsUnknown(kind) => s"unknown:$kind"
    def symmetricScore(candidateScore: RhsExpr, holderScore: RhsExpr,
                       direction: SelectionDirection): Option[(CandidateRelation, SelectionDirection)] = {
      val assignedRoots = assigned.declarationDependencies.filter(_ != holderId)
      val candidateOnlyRoots = rhsDependencies(candidateScore) -- rhsDependencies(holderScore)
      val varyingRoots = assignedRoots.intersect(candidateOnlyRoots)
      val candidateFingerprintValue = candidateFingerprint(candidateScore)
      val holderFingerprintValue = candidateFingerprint(holderScore)
      varyingRoots.toList.sorted match
        case varyingRoot :: Nil if rhsDependencies(holderScore).contains(holderId) &&
            !rhsDependencies(candidateScore).contains(holderId) &&
            normalizedScoreShape(candidateScore, varyingRoot) == normalizedScoreShape(holderScore, holderId) &&
            candidateFingerprintValue.complete && holderFingerprintValue.complete &&
            candidateFingerprintValue.pure && holderFingerprintValue.pure =>
          val candidateStable = rhsDependencies(candidateScore) - varyingRoot
          val holderStable = rhsDependencies(holderScore) - holderId
          Option.when(candidateStable == holderStable &&
            (assigned.expression == RhsDeclarationRef(varyingRoot) || assigned.expression == RhsSelfRef(varyingRoot)))(
            CandidateRelation(assigned,
              RhsAnalysis(candidateScore, rhsDependencies(candidateScore), assigned.sourceBacked, complete = true),
              RhsAnalysis(holderScore, rhsDependencies(holderScore), sourceBacked = true, complete = true),
              SymmetricScoreCandidate) -> direction
          )
        case _ => None
    }
    def relation(comparedCandidate: RhsExpr, holderProjection: RhsExpr,
                 direction: SelectionDirection): Option[(CandidateRelation, SelectionDirection)] = {
      val kind =
        if assigned.expression == comparedCandidate && candidateFingerprint(assigned.expression).pure then
          Some(DirectCandidateValue)
        else if equivalentPureCandidate(assigned.expression, comparedCandidate) then {
          profiler.increment("mwh_equivalent_fingerprints")
          Some(EquivalentCandidateExpression)
        } else if projectionOf(assigned.expression, comparedCandidate) then Some(CandidateSelectedByProjection)
        else {
          val fingerprints = List(candidateFingerprint(assigned.expression), candidateFingerprint(comparedCandidate))
          if fingerprints.exists(!_.pure) then profiler.increment("mwh_rejected_impure_call")
          None
        }
      kind.map(relationKind => CandidateRelation(
        assigned,
        RhsAnalysis(comparedCandidate, rhsDependencies(comparedCandidate), assigned.sourceBacked,
          isCompleteRhs(comparedCandidate)),
        RhsAnalysis(holderProjection, rhsDependencies(holderProjection), sourceBacked = true,
          isCompleteRhs(holderProjection)),
        relationKind
      ) -> direction)
    }
    val holderOnLeft = projectionOf(RhsSelfRef(holderId), left)
    val holderOnRight = projectionOf(RhsSelfRef(holderId), right)
    val holderDependsOnLeft = rhsDependencies(left).contains(holderId)
    val holderDependsOnRight = rhsDependencies(right).contains(holderId)
    if holderDependsOnRight && !holderDependsOnLeft then
      Option.when(holderOnRight)(()).flatMap(_ => relation(left, right,
        if Set(">", ">=").contains(operator) then SelectMaximum else SelectMinimum))
      .orElse(symmetricScore(left, right,
        if Set(">", ">=").contains(operator) then SelectMaximum else SelectMinimum))
    else if holderDependsOnLeft && !holderDependsOnRight then
      Option.when(holderOnLeft)(()).flatMap(_ => relation(right, left,
        if Set("<", "<=").contains(operator) then SelectMaximum else SelectMinimum))
      .orElse(symmetricScore(right, left,
        if Set("<", "<=").contains(operator) then SelectMaximum else SelectMinimum))
    else None
  }

  def selectionGuardAnalysis(holderId: Long, assigned: RhsAnalysis, guard: GuardInfo): SelectionGuardAnalysis = {
    def selection(expression: RhsExpr): Option[(CandidateRelation, SelectionDirection)] = expression match
      case RhsBinary(operator, left, right) if Set("<", "<=", ">", ">=").contains(operator) =>
        candidateRelation(holderId, assigned, left, right, operator)
      case call: RhsCall => comparatorSelection(holderId, assigned, call)
      case _ => None
    def flattenAnd(expression: RhsExpr): List[RhsExpr] = expression match
      case RhsBinary("&&", left, right) => flattenAnd(left) ++ flattenAnd(right)
      case other => List(other)
    def expressionContains(root: RhsExpr, child: RhsExpr): Boolean =
      root == child || (root match
        case RhsUnary(_, operand) => expressionContains(operand, child)
        case RhsBinary(_, left, right) => expressionContains(left, child) || expressionContains(right, child)
        case RhsCall(_, arguments) => arguments.exists(expressionContains(_, child))
        case RhsMemberAccess(receiver, _, _, _) => expressionContains(receiver, child)
        case RhsIndexAccess(receiver, index) => expressionContains(receiver, child) || expressionContains(index, child)
        case RhsDereference(operand) => expressionContains(operand, child)
        case _ => false)
    def analysis(expression: RhsExpr): SelectionGuardAnalysis = expression match
      case RhsBinary("||", left, right) =>
        val leftSelection = selection(left)
        val rightSelection = selection(right)
        val leftBootstrap = bootstrapCondition(holderId, left)
        val rightBootstrap = bootstrapCondition(holderId, right)
        val valid = (leftBootstrap && rightSelection.nonEmpty) || (rightBootstrap && leftSelection.nonEmpty)
        if valid then profiler.increment("mwh_none_bootstraps")
        val selected = if leftSelection.nonEmpty then leftSelection else rightSelection
        SelectionGuardAnalysis(selected.map(_._1), selected.map(_._2),
          Option.when(valid)(RhsAnalysis(if leftBootstrap then left else right,
            rhsDependencies(if leftBootstrap then left else right), guard.sourceBacked, complete = true)),
          Nil, if valid then Nil else List(RhsAnalysis(expression, rhsDependencies(expression),
            guard.sourceBacked, isCompleteRhs(expression))), guard.complete && guard.sourceBacked && valid)
      case RhsBinary("&&", left, right) =>
        val clauses = flattenAnd(expression)
        val selections = clauses.flatMap(selection)
        val eligibility = clauses.filter(clause => selection(clause).isEmpty).map(clause =>
          RhsAnalysis(clause, rhsDependencies(clause), guard.sourceBacked, isCompleteRhs(clause)))
        val completeEligibility = eligibility.forall { item =>
          val fingerprint = candidateFingerprint(item.expression)
          item.complete && item.sourceBacked && !item.declarationDependencies.contains(holderId) &&
            fingerprint.complete && fingerprint.pure
        }
        if selections.size == 1 && eligibility.nonEmpty && completeEligibility then
          profiler.increment("mwh_conjunctions_with_eligibility")
        SelectionGuardAnalysis(selections.headOption.map(_._1), selections.headOption.map(_._2), None,
          eligibility, if selections.size == 1 && completeEligibility then Nil else eligibility,
          guard.complete && guard.sourceBacked && selections.size == 1 && completeEligibility)
      case other =>
        val selected = selection(other)
        SelectionGuardAnalysis(selected.map(_._1), selected.map(_._2), None, Nil,
          if selected.nonEmpty then Nil else List(RhsAnalysis(other, rhsDependencies(other),
            guard.sourceBacked, isCompleteRhs(other))),
          guard.complete && guard.sourceBacked && selected.nonEmpty)
    val distinct = guard.expressions.distinctBy(item => candidateFingerprint(item.expression))
    val roots = distinct.filterNot(candidate => distinct.exists(other =>
      other != candidate && expressionContains(other.expression, candidate.expression)))
    if guard.expressions.size > 1 then profiler.increment("mwh_multi_expression_guards")
    if roots.size != 1 then SelectionGuardAnalysis(None, None, None, Nil, roots, analysisComplete = false)
    else analysis(roots.head.expression)
  }

  def exactMinMaxName(name: String): Option[SelectionDirection] = {
    val normalized = name.trim.toLowerCase.replace("::", ".")
    normalized.split("[.]").lastOption.flatMap {
      case "min" => Some(SelectMinimum)
      case "max" => Some(SelectMaximum)
      case _ => None
    }
  }

  lazy val comparatorSummaryByCallName: Map[String, ComparatorSummary] = profiler.timed("mwh_comparator_index") {
    allMethodNodes.filter(method => optInt(method.lineNumber, 0) > 0).flatMap { method =>
      val parameters = method.parameter.l.filterNot(parameter => Set("this", "self").contains(parameter.name))
        .sortBy(_.order)
      val returns = method.ast.isReturn.l
      val parameterIds = parameters.map(_.id).toSet
      val parameterMutated = allWrites.exists(write => parameterIds.contains(write.declId))
      val comparisons = returns.flatMap(_.ast.isCall.l.filter(call => guardBinaryOperators.contains(call.name)))
        .flatMap { comparison =>
          comparison.argument.l.filter(_.argumentIndex >= 0).sortBy(_.argumentIndex) match
            case left :: right :: Nil =>
              val normalizedLeft = normalizedRhsExpression(left, -1L, method.fullName)
              val normalizedRight = normalizedRhsExpression(right, -1L, method.fullName)
              (normalizedLeft, normalizedRight) match
                case (RhsDeclarationRef(leftId), RhsDeclarationRef(rightId))
                    if parameters.size == 2 && leftId == parameters.head.id && rightId == parameters(1).id =>
                  guardBinaryOperators.get(comparison.name).flatMap {
                    case ">" | ">=" => Some(SelectMinimum)
                    case "<" | "<=" => Some(SelectMaximum)
                    case _ => None
                  }
                case _ => None
            case _ => None
        }
      val directions = comparisons.distinct
      val complete = parameters.size == 2 && returns.nonEmpty && comparisons.size == returns.size &&
        directions.size == 1 && !parameterMutated
      if complete then {
        profiler.increment("mwh_comparators_summarized")
        Some(method.name -> ComparatorSummary(method.fullName, parameters.head.id, parameters(1).id,
          directions.head, analysisComplete = true))
      } else {
        if parameters.size == 2 && returns.nonEmpty then profiler.increment("mwh_comparators_rejected")
        None
      }
    }.groupBy(_._1).collect { case (name, List((_, summary))) => name -> summary }
  }

  def comparatorSelection(holderId: Long, assigned: RhsAnalysis, expression: RhsExpr)
  : Option[(CandidateRelation, SelectionDirection)] = expression match
    case RhsCall(name, first :: second :: Nil) => comparatorSummaryByCallName.get(name).flatMap { summary =>
      val firstHolder = projectionOf(RhsSelfRef(holderId), first)
      val secondHolder = projectionOf(RhsSelfRef(holderId), second)
      val compared = if firstHolder then Some(second) else if secondHolder then Some(first) else None
      compared.filter(equivalentPureCandidate(assigned.expression, _)).map { candidate =>
        val direction = if firstHolder then summary.direction else summary.direction match
          case SelectMinimum => SelectMaximum
          case SelectMaximum => SelectMinimum
          case other => other
        CandidateRelation(assigned, RhsAnalysis(candidate, rhsDependencies(candidate), sourceBacked = true,
          isCompleteRhs(candidate)), RhsAnalysis(if firstHolder then first else second,
          Set(holderId), sourceBacked = true, complete = true), EquivalentCandidateExpression) -> direction
      }
    }
    case _ => None

  def minMaxReplacement(write: WriteInfo, rhs: RhsAnalysis): Option[MostWantedReplacement] =
    rhs.expression match
      case RhsCall(name, arguments) if rhs.complete && rhs.sourceBacked =>
        exactMinMaxName(name).flatMap { direction =>
          arguments match
            case first :: second :: Nil =>
              val firstSelf = first == RhsSelfRef(write.declId)
              val secondSelf = second == RhsSelfRef(write.declId)
              val candidate = if firstSelf && !rhsDependencies(second).contains(write.declId) then Some(second)
                else if secondSelf && !rhsDependencies(first).contains(write.declId) then Some(first)
                else None
              candidate.map { expression =>
                val analysis = RhsAnalysis(expression, rhsDependencies(expression), rhs.sourceBacked,
                  isCompleteRhs(expression))
                MostWantedReplacement(write, analysis, MinMaxSelection, direction, Nil,
                  write.loopKey.get, sourceBacked = true)
              }
            case _ => None
        }
      case _ => None

  def extremeSentinel(expression: RhsExpr, write: WriteInfo): Option[SelectionDirection] = {
    def namedDirection(code: String): Option[SelectionDirection] = code.trim.toLowerCase match
      case "infinity" | "int_max" | "long_max" | "llong_max" | "dbl_max" | "flt_max" =>
        Some(SelectMinimum)
      case "int_min" | "long_min" | "llong_min" => Some(SelectMaximum)
      case _ => None
    expression match
      case RhsStandardConstant(code) => namedDirection(code)
      case _: RhsDeclarationRef =>
        val directions = write.sourceNames.flatMap(namedDirection)
        if directions.size == 1 then directions.headOption else None
      case RhsUnary("-", RhsStandardConstant(code)) if code.trim.equalsIgnoreCase("infinity") =>
        Some(SelectMaximum)
      case RhsCall(name, List(RhsLiteral(code))) if name.equalsIgnoreCase("float") &&
          Set("\"inf\"", "'inf'").contains(code.trim.toLowerCase) => Some(SelectMinimum)
      case RhsUnary("-", RhsCall(name, List(RhsLiteral(code)))) if name.equalsIgnoreCase("float") &&
          Set("\"inf\"", "'inf'").contains(code.trim.toLowerCase) => Some(SelectMaximum)
      case RhsCall(name, _) if Set("max", "lowest", "min").contains(name.toLowerCase.split("[.:]").last) &&
          write.code.matches("(?s).*numeric_limits\\s*<[^>]{1,160}>\\s*::\\s*(?:max|min|lowest)\\s*\\(\\s*\\).*" ) =>
        if write.code.matches("(?s).*::\\s*max\\s*\\(.*") then Some(SelectMinimum)
        else Some(SelectMaximum)
      case _ => None
  }

  def mostWantedSeed(write: WriteInfo): Option[MostWantedSeed] =
    write.rhs.filter(rhs => write.directWrite && write.operator == "<operator>.assignment" &&
      !write.selfRef && rhs.sourceBacked && rhs.complete).map { rhs =>
      val (kind, hint) =
        if nullSentinelExpression(rhs.expression) then (NullSentinel, None)
        else extremeSentinel(rhs.expression, write) match
          case Some(direction) => (ExtremeSentinel, Some(direction))
          case None => rhs.expression match
            case _: RhsLiteral => (LiteralSentinel, None)
            case RhsDeclarationRef(id) if !allWrites.exists(other =>
                other.eventId != write.eventId && other.declId == id) &&
                declById.get(id).forall { declaration =>
                  declaration.kind == "local" && declaration.pos.line == write.line &&
                    sourceLine(declaration.pos.path, declaration.pos.line).exists { line =>
                      val tokenColumn = math.max(0, declaration.pos.column - 1)
                      tokenColumn <= line.length && line.take(tokenColumn).contains("=")
                    }
                } =>
              profiler.increment("mwh_stable_external_seeds")
              (StableExternalSentinel, None)
            case _: RhsDeclarationRef => (FixedSentinel, None)
            case RhsExternalReference(name) if !allWrites.exists(other =>
                other.eventId != write.eventId && other.name == name) =>
              profiler.increment("mwh_stable_external_seeds")
              (StableExternalSentinel, None)
            case _ => (FirstCandidateSeed, None)
      val context = mwhLoopContextsByWriteEventId.get(write.eventId).flatMap(_.headOption).orElse(write.loopKey)
      MostWantedSeed(Some(write), kind, Some(rhs), context, hint, sourceBacked = true)
    }

  def loopRangeFor(method: String, key: String): Option[(Int, Int)] =
    loopRangesByMethod.getOrElse(method, Nil).find(_._4 == key).map(range => (range._2, range._3))

  def seedCanOpenEpoch(seed: MostWantedSeed, replacement: MostWantedReplacement): Boolean =
    seed.write.forall { write =>
      val ordered = write.method == replacement.write.method &&
        eventBefore(write.line, write.column, replacement.write.line, replacement.write.column)
      val contextCompatible = write.loopKey match
        case None => true
        case Some(seedKey) if seedKey == replacement.contextKey => false
        case Some(seedKey) =>
          if mwhLoopContextsByWriteEventId.getOrElse(replacement.write.eventId, Nil).drop(1).contains(seedKey) then {
            profiler.increment("mwh_nested_loops_recovered")
            true
          } else (loopRangeFor(write.method, seedKey), loopRangeFor(replacement.write.method, replacement.contextKey)) match
            case (Some((seedStart, seedEnd)), Some((selectionStart, selectionEnd))) =>
              seedStart <= selectionStart && seedEnd >= selectionEnd
            case _ => false
      ordered && contextCompatible
    }

  val controlById = allControlStructureNodes.iterator.map(control => control.id -> control).toMap
  def terminalExitAfter(write: WriteInfo, guard: GuardInfo): Boolean =
    controlById.get(guard.controlId).exists { control =>
      val breakAfter = control.ast.isControlStructure.l.exists(exit =>
        exit.controlStructureType.toUpperCase == "BREAK" && optInt(exit.lineNumber, 0) >= write.line)
      val returnAfter = control.ast.isReturn.l.exists(ret => optInt(ret.lineNumber, 0) >= write.line)
      breakAfter || returnAfter
    }

  def terminalGuardDependsOnCandidate(write: WriteInfo, candidate: RhsAnalysis, guard: GuardInfo): Boolean = {
    val candidateIds = candidate.declarationDependencies
    guard.declarationDependencies.exists(candidateIds.contains) ||
      guard.declarationDependencies.exists { derivedId =>
        writesByDecl.getOrElse(derivedId, Nil).exists { definition =>
          definition.method == write.method && definition.line <= write.line &&
            definition.rhs.exists(rhs => rhs.declarationDependencies.exists(candidateIds.contains))
        }
      }
  }

  def terminalReplacement(write: WriteInfo, candidate: RhsAnalysis): Option[MostWantedReplacement] =
    directGuardByWriteEventId.get(write.eventId).filter { guard =>
      guard.sourceBacked && guard.complete && terminalGuardDependsOnCandidate(write, candidate, guard) &&
        terminalExitAfter(write, guard)
    }.map { guard =>
      profiler.increment("mwh_terminal_selections")
      MostWantedReplacement(write, candidate, FirstSatisfyingCandidateReplacement,
        SelectByTerminalPredicate, List(guard), write.loopKey.get, sourceBacked = true)
    }.orElse {
      if directGuardByWriteEventId.get(write.eventId).exists(guard =>
          guard.sourceBacked && guard.complete && terminalGuardDependsOnCandidate(write, candidate, guard)) then
        profiler.increment("mwh_rejected_missing_terminal_exit")
      None
    }

  def bootstrapOnlyGuard(holderId: Long, write: WriteInfo): Option[SelectionGuardAnalysis] =
    directGuardByWriteEventId.get(write.eventId).flatMap { guard =>
      val roots = guard.expressions.map(_.expression).filter(bootstrapCondition(holderId, _))
      Option.when(roots.size == 1 && guard.complete && guard.sourceBacked)(SelectionGuardAnalysis(
        None, None, Some(RhsAnalysis(roots.head, rhsDependencies(roots.head), sourceBacked = true, complete = true)),
        Nil, Nil, analysisComplete = true))
    }

  def mostWantedFlowFor(
    declaration: VarDecl,
    partition: BindingPartition,
    writes: List[WriteInfo],
    observedStateMutation: Boolean
  ): MostWantedFlow = {
    val orderedWrites = writes.sortBy(write => (write.method, write.line, write.column, write.eventId))
    val writeGuardControlIds = orderedWrites.map(write => write.eventId ->
      guardsByWriteEventId.getOrElse(write.eventId, Nil).map(_.controlId).toSet).toMap
    val cheapCandidate = orderedWrites.size >= (if declaration.kind == "parameter" then 1 else 2) &&
      !observedStateMutation &&
      orderedWrites.forall(write => write.directWrite && write.rhs.nonEmpty)
    if !cheapCandidate then {
      if orderedWrites.nonEmpty then profiler.increment("mwh_prefilter_rejections")
      return MostWantedFlow(Nil, Nil, Set.empty, orderedWrites.map(_.eventId).toSet,
        analysisComplete = false, writeGuardControlIds)
    }
    profiler.increment("mwh_prefiltered_candidates")
    val seeds = orderedWrites.flatMap(mostWantedSeed)
    profiler.increment("mwh_null_sentinels", seeds.count(_.kind == NullSentinel).toLong)
    profiler.increment("mwh_extreme_sentinels", seeds.count(_.kind == ExtremeSentinel).toLong)
    val implicitSeed = Option.when(declaration.kind == "parameter")(
      MostWantedSeed(None, ImplicitParameterSeed, None, None, None, sourceBacked = true)
    )
    val replacements = orderedWrites.flatMap { write =>
      write.rhs.toList.flatMap { rhs =>
        val loopContexts = mwhLoopContextsByWriteEventId.getOrElse(write.eventId, write.loopKey.toList)
        val primaryLoop = loopContexts.headOption
        Option.when(primaryLoop.nonEmpty)(minMaxReplacement(write.copy(loopKey = primaryLoop), rhs)).flatten.orElse {
          val candidateValid = write.operator == "<operator>.assignment" && !write.selfRef &&
            rhs.sourceBacked && rhs.complete && !rhs.declarationDependencies.contains(declaration.id) &&
            !rhs.expression.isInstanceOf[RhsLiteral]
          if !candidateValid then None
          else {
            val directGuard = directGuardByWriteEventId.get(write.eventId)
            val analysis = directGuard.map(selectionGuardAnalysis(declaration.id, rhs, _))
            if primaryLoop.nonEmpty && analysis.exists(item => item.analysisComplete && item.relation.nonEmpty &&
                item.direction.nonEmpty && item.incompatibleConditions.isEmpty) then {
              if analysis.exists(_.bootstrapCondition.nonEmpty) then profiler.increment("mwh_bootstrap_selection_guards")
              Some(MostWantedReplacement(write, rhs, GuardedCandidateReplacement,
                analysis.flatMap(_.direction).get, directGuard.toList, primaryLoop.get, sourceBacked = true))
            } else if primaryLoop.nonEmpty then terminalReplacement(write.copy(loopKey = primaryLoop), rhs).orElse {
              if directGuard.exists(guard => !guard.complete || !guard.sourceBacked) then
                profiler.increment("mwh_rejected_incomplete_guard")
              else profiler.increment("mwh_rejected_candidate_mismatch")
              None
            } else None
          }
        }
      }
    }
    replacements.foreach { replacement =>
      profiler.increment(if replacement.kind == MinMaxSelection then "mwh_minmax_replacements" else
        "mwh_guarded_replacements")
    }
    val replacementIds = replacements.map(_.write.eventId).toSet
    val semanticSeeds = seeds.filterNot(seed => seed.write.exists(write => replacementIds.contains(write.eventId)))
    val epochs = replacements.groupBy(replacement => (replacement.write.method, replacement.contextKey)).toList
      .sortBy(_._1).flatMap { case ((_, contextKey), contextReplacements) =>
        val orderedReplacements = contextReplacements.sortBy(replacement =>
          (replacement.write.line, replacement.write.column, replacement.write.eventId))
        val first = orderedReplacements.head
        val seed = (semanticSeeds.filter(seedCanOpenEpoch(_, first)) ++ implicitSeed)
          .sortBy(seed => seed.write.map(write => (write.line, write.column, write.eventId)).getOrElse((-1, -1, -1L)))
          .lastOption
        seed.flatMap { selectedSeed =>
          val directions = orderedReplacements.map(_.direction).distinct
          val hasBootstrapSelection = orderedReplacements.exists { replacement =>
            directGuardByWriteEventId.get(replacement.write.eventId)
              .map(selectionGuardAnalysis(declaration.id, replacement.candidate, _))
              .exists(analysis => analysis.analysisComplete && analysis.bootstrapCondition.nonEmpty)
          }
          val terminalSelection = directions == List(SelectByTerminalPredicate)
          val directionCompatible = directions.size == 1 &&
            (if terminalSelection then selectedSeed.kind == NullSentinel
             else selectedSeed.directionHint.forall(_ == directions.head) &&
               (selectedSeed.kind != NullSentinel || hasBootstrapSelection))
          if selectedSeed.contextKey.nonEmpty then profiler.increment("mwh_outer_loop_seeds")
          if !directionCompatible then profiler.increment("mwh_rejected_direction_mismatch")
          Option.when(directionCompatible)(MostWantedSelectionEpoch(
            selectedSeed, contextKey, orderedReplacements,
            selectedSeed.write.map(_.eventId).toSet ++ orderedReplacements.map(_.write.eventId),
            analysisComplete = true
          ))
        }
      }
    profiler.increment("mwh_epochs_built", epochs.size.toLong)
    val epochDirections = epochs.flatMap(_.replacements.map(_.direction)).distinct
    val epochExplained = epochs.iterator.flatMap(_.explainedEventIds).toSet
    val terminalEpochs = epochs.filter(_.replacements.forall(_.direction == SelectByTerminalPredicate))
    val fallbackWrites = orderedWrites.filterNot(write => epochExplained.contains(write.eventId)).flatMap { write =>
      val afterTerminal = terminalEpochs.exists(epoch => epoch.replacements.lastOption.exists(replacement =>
        replacement.write.method == write.method && eventBefore(replacement.write.line, replacement.write.column,
          write.line, write.column)))
      bootstrapOnlyGuard(declaration.id, write).filter(_ => afterTerminal && write.loopKey.isEmpty).map { guard =>
        profiler.increment("mwh_fallbacks_explained")
        MostWantedFallback(write, guard, sourceBacked = true)
      }
    }
    val explained = epochExplained ++ fallbackWrites.map(_.write.eventId)
    val incompatible = orderedWrites.map(_.eventId).toSet -- explained
    val resetInSelectionContext = semanticSeeds.exists(seed => seed.write.exists(write =>
      epochs.exists(epoch => write.loopKey.contains(epoch.selectionContextKey) &&
        epoch.replacements.exists(replacement => eventBefore(replacement.write.line, replacement.write.column,
          write.line, write.column)))))
    if resetInSelectionContext then profiler.increment("mwh_rejected_intra_epoch_reset")
    if incompatible.nonEmpty then profiler.increment("mwh_rejected_incompatible_writes", incompatible.size.toLong)
    MostWantedFlow(epochs, replacements, explained, incompatible,
      analysisComplete = epochs.nonEmpty && epochDirections.size == 1 && incompatible.isEmpty &&
        !resetInSelectionContext && epochs.forall(_.analysisComplete), writeGuardControlIds)
  }

  val structuralUseContextsByDecl: Map[Long, Set[(String, String)]] = profiler.timed("walker_use_index") {
    val accessUses = allCallNodes.filter(call => Set(
      "<operator>.indexAccess", "<operator>.indirectIndexAccess",
      "<operator>.fieldAccess", "<operator>.indirectFieldAccess", "<operator>.indirection"
    ).contains(call.name)).flatMap { call =>
      val method = cachedScopeOf(call)
      val line = optInt(call.lineNumber, 0)
      loopAt(method, line).toList.flatMap { case (loopKey, _, _, _) =>
        call.argument.l.headOption.toList.flatMap(_.ast.isIdentifier.l)
          .flatMap(declarationId).map(id => id -> (method, loopKey))
      }
    }
    val controlUses = allControlStructureNodes.flatMap { control =>
      val method = cachedScopeOf(control)
      val line = optInt(control.lineNumber, 0)
      loopAt(method, line).toList.flatMap { case (loopKey, _, _, _) =>
        control.ast.isIdentifier.l.flatMap(declarationId).map(id => id -> (method, loopKey))
      }
    }
    (accessUses ++ controlUses).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
  }

  val pointerTypedCursorIds = (sourceLocalNodes.filter(_.typeFullName.contains("*" )).map(_.id) ++
    sourceParameterNodes.filter(_.typeFullName.contains("*" )).map(_.id)).toSet
  // Only the direct operand can establish a cursor. Indexing and arithmetic
  // are never traversed for descendant identifiers. Arithmetic accepts only a
  // unique pointer-typed base; untyped operands and numeric offsets prove nothing.
  def directCursorDeclaration(node: Expression, depth: Int = 0): Option[Long] =
    if depth >= 16 then None
    else node match
      case identifier: Identifier => declarationId(identifier)
      case call: Call if Set(
          "<operator>.preIncrement", "<operator>.postIncrement",
          "<operator>.preDecrement", "<operator>.postDecrement"
        ).contains(call.name) =>
        call.argument.l match
          case operand :: Nil => directCursorDeclaration(operand, depth + 1)
          case _ => None
      case call: Call if Set("<operator>.parenthesis", "<operator>.cast").contains(call.name) =>
        call.argument.l.lastOption.flatMap(directCursorDeclaration(_, depth + 1))
      case call: Call if Set("<operator>.addition", "<operator>.subtraction").contains(call.name) =>
        call.argument.l match
          case left :: right :: Nil =>
            val leftPointer = directCursorDeclaration(left, depth + 1).filter(pointerTypedCursorIds.contains)
            val rightPointer = directCursorDeclaration(right, depth + 1).filter(pointerTypedCursorIds.contains)
            if leftPointer.nonEmpty && rightPointer.isEmpty then leftPointer
            else if call.name == "<operator>.addition" && leftPointer.isEmpty then rightPointer
            else None
          case _ => None
      case _ => None

  val directCursorUseContextsByDecl: Map[Long, Set[(String, String)]] =
    profiler.timed("walker_iterator_access_index") {
      allCallNodes.filter(call => Set(
        "<operator>.indirection", "<operator>.indirectFieldAccess"
      ).contains(call.name)).flatMap { call =>
        val method = cachedScopeOf(call)
        for {
          operand <- call.argument.l.headOption.toList
          id <- directCursorDeclaration(operand).toList
          context = progressionEventContext(call.id, method, loopAt(method, optInt(call.lineNumber, 0)).map(_._1))
          if context.analysisComplete
          loopKey <- (context.enclosingLoopKeys ++ context.loopKey).toList.sorted
          if nodePath(call) != "<unknown>" && optInt(call.lineNumber, 0) > 0
        } yield id -> (method, loopKey)
      }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    }

  def structuredNavigationSource(expression: RhsExpr, targetDeclId: Long): Option[NavigationSource] = expression match
    case RhsMemberAccess(RhsSelfRef(id), memberId, _, _) if id == targetDeclId =>
      Some(MemberNavigationSource(memberId))
    case RhsMemberAccess(RhsDereference(RhsSelfRef(id)), memberId, _, _) if id == targetDeclId =>
      Some(MemberNavigationSource(memberId))
    case RhsIndexAccess(RhsDeclarationRef(collectionId), RhsSelfRef(id)) if id == targetDeclId && collectionId != id =>
      Some(IndexedNavigationSource(collectionId))
    case RhsCall(name, arguments) if arguments.exists {
        case RhsSelfRef(id) => id == targetDeclId
        case _ => false
      } && Set("next", "nextnode", "previous", "prev", "parent").contains(
        name.trim.toLowerCase.replace("::", ".").split("[.]").lastOption.getOrElse("")
      ) =>
      Some(NavigationMethodSource(targetDeclId, name))
    case RhsMemberAccess(RhsCall(_, arguments), memberId, _, _) if arguments.exists {
        case RhsSelfRef(id) => id == targetDeclId
        case _ => false
      } => Some(MemberNavigationSource(memberId))
    case _ => None

  def walkerFlowFor(declaration: VarDecl, partition: BindingPartition): WalkerFlow = {
    val transitions = partition.postInitializationWrites.flatMap { write =>
      val structured = write.loopKey.toList.flatMap { loopKey =>
        write.rhs.toList.filter(rhs => rhs.complete && rhs.sourceBacked).flatMap { rhs =>
          structuredNavigationSource(rhs.expression, declaration.id).map { source =>
            WalkerTransition(write, source, loopKey,
              usesPreviousPosition = rhs.declarationDependencies.contains(declaration.id),
              usedForAccessOrControl = structuralUseContextsByDecl.getOrElse(declaration.id, Set.empty)
                .contains((write.method, loopKey)))
          }
        }
      }
      // Only cursor increments use the callable-local event context here.
      // Other navigation sources keep their existing loop/navigation contract.
      val iteratorProgression = if !Set(
          "<operator>.preIncrement", "<operator>.postIncrement",
          "<operator>.preDecrement", "<operator>.postDecrement"
        ).contains(write.operator) then Nil
        else {
          val context = stepperEventContext(write)
          context.loopKey.toList.filter(loopKey => context.analysisComplete &&
            directCursorUseContextsByDecl.getOrElse(declaration.id, Set.empty).contains((write.method, loopKey)))
            .map { loopKey =>
              WalkerTransition(write, IteratorProgressionSource(declaration.id, None,
                if write.operator.toLowerCase.contains("decrement") then "backward" else "forward"),
                loopKey, usesPreviousPosition = true, usedForAccessOrControl = true)
            }
        }
      structured ++ iteratorProgression
    }

    WalkerFlow(
      implicitElementWalker = implicitIteratorIds.contains(declaration.id),
      implicitNumericIterator = implicitNumericIteratorDeclIds.contains(declaration.id),
      transitions = transitions,
      analysisComplete = declaration.pos.line > 0 && declaration.pos.path != "<unknown>"
    )
  }

  def containsRhsCall(expression: RhsExpr): Boolean = expression match
    case _: RhsCall => true
    case RhsUnary(_, operand) => containsRhsCall(operand)
    case RhsBinary(_, left, right) => containsRhsCall(left) || containsRhsCall(right)
    case RhsMemberAccess(receiver, _, _, _) => containsRhsCall(receiver)
    case RhsIndexAccess(receiver, index) => containsRhsCall(receiver) || containsRhsCall(index)
    case RhsDereference(operand) => containsRhsCall(operand)
    case _ => false

  def containsIndexAccess(expression: RhsExpr): Boolean = expression match
    case _: RhsIndexAccess => true
    case RhsUnary(_, operand) => containsIndexAccess(operand)
    case RhsBinary(_, left, right) => containsIndexAccess(left) || containsIndexAccess(right)
    case RhsMemberAccess(receiver, _, _, _) => containsIndexAccess(receiver)
    case RhsDereference(operand) => containsIndexAccess(operand)
    case _ => false

  def projectionRootDeclarationIds(expression: RhsExpr): Set[Long] = expression match
    case RhsDeclarationRef(id) => Set(id)
    case RhsSelfRef(id) => Set(id)
    case RhsMemberAccess(receiver, _, _, _) => projectionRootDeclarationIds(receiver)
    case RhsIndexAccess(receiver, _) => projectionRootDeclarationIds(receiver)
    case RhsDereference(operand) => projectionRootDeclarationIds(operand)
    case RhsUnary(_, operand) => projectionRootDeclarationIds(operand)
    case _ => Set.empty

  def acquisitionKindFor(declaration: VarDecl, write: WriteInfo): Option[AcquisitionKind] =
    write.rhs.filter(rhs => rhs.complete && rhs.sourceBacked &&
      !rhs.declarationDependencies.contains(declaration.id)).flatMap { rhs =>
      if declaration.kind == "member" && (rhs.expression match
          case RhsDeclarationRef(sourceId) => declById.get(sourceId).exists(_.kind == "parameter")
          case _ => false)
      then Some(ParameterToMemberAcquisition)
      else if declaration.kind == "member" && containsRhsCall(rhs.expression) then
        Some(ExternalCallToMemberAcquisition)
      else if declaration.kind == "member" && containsIndexAccess(rhs.expression) then
        Some(CollectionElementToMemberAcquisition)
      else if declaration.kind == "member" && projectionRootDeclarationIds(rhs.expression)
          .exists(implicitIteratorIds.contains) then Some(CurrentWalkerElementToMemberAcquisition)
      else if containsRhsCall(rhs.expression) then Some(CallAcquisition)
      else if containsIndexAccess(rhs.expression) then Some(CollectionAcquisition)
      else rhs.expression match
        case RhsDeclarationRef(sourceId) if implicitIteratorIds.contains(sourceId) => Some(CurrentElementAcquisition)
        case _ => None
    }

  def arithmeticCorrection(write: WriteInfo): Boolean =
    !write.insideControl && write.directWrite && write.rhs.exists { rhs =>
      def allowed(expression: RhsExpr): Boolean = expression match
        case _: RhsLiteral | _: RhsDeclarationRef | _: RhsSelfRef => true
        case RhsUnary(operator, operand) => Set("+", "-").contains(operator) && allowed(operand)
        case RhsBinary(operator, left, right) => Set("+", "-", "*", "/").contains(operator) &&
          allowed(left) && allowed(right)
        case _ => false
      rhs.complete && rhs.sourceBacked && rhs.declarationDependencies.contains(write.declId) &&
        allowed(rhs.expression)
    }

  val (bindingWriteEventIdsByDecl, dynamicWritesByDeclMethod) = profiler.timed("dynamic_role_fact_indexes") {
    val bindingIds = (allWrites ++ normalizedFieldAssignmentWrites ++ fixedMemberBindingWrites)
      .groupMap(_.declId)(_.eventId).view.mapValues(_.toSet).toMap
    val writesByDeclMethod = (allWrites ++ normalizedFieldAssignmentWrites ++ fixedMemberBindingWrites)
      .groupBy(write => (write.declId, write.method))
      .view.mapValues(_.distinctBy(_.eventId).sortBy(write => (write.line, write.column, write.eventId))).toMap
    (bindingIds, writesByDeclMethod)
  }
  // A self-read in the holder's own correction is not its final semantic use.
  // Reads inside an unrelated assignment (for example a named argument in a
  // collection-element write) remain genuine consumers and must not be removed.
  val identifierNodeById = allIdentifierNodes.iterator.map(identifier => identifier.id -> identifier).toMap
  val readOwningWriteEventId = profiler.timed("semantic_read_ownership_index") {
    followerReadsByDecl.valuesIterator.flatten.flatMap { read =>
      identifierNodeById.get(read.nodeId).flatMap { identifier =>
        astAncestors(identifier).collectFirst {
          case call: Call if writeOperators.contains(call.name) => read.nodeId -> call.id
        }
      }
    }.toMap
  }
  val semanticReadsByDecl = profiler.timed("semantic_read_index") {
    followerReadsByDecl.map { case (declarationId, reads) =>
      val ownWriteIds = bindingWriteEventIdsByDecl.getOrElse(declarationId, Set.empty)
      declarationId -> reads.filterNot(read =>
        readOwningWriteEventId.get(read.nodeId).exists(ownWriteIds.contains)
      )
    }
  }
  val semanticReadIdsByDeclMethod = semanticReadsByDecl.iterator.flatMap { case (declarationId, reads) =>
    reads.groupBy(_.method).map { case (method, methodReads) =>
      (declarationId, method) -> methodReads.map(_.nodeId).toSet
    }
  }.toMap

  // Python source methods are children of the class-body METHOD, whereas
  // adapters are direct TYPE_DECL children. Resolve that one explicit wrapper
  // for Stepper only; do not change other roles' owner/initialization indexes.
  val stepperMethodsByOwner = allMethodNodes.flatMap { method =>
    val owner = scala.util.Try(method.astParent).toOption.flatMap {
      case value: TypeDecl => Some(value)
      case body: Method if isDeclarationBody(body) =>
        scala.util.Try(body.astParent).toOption.collect { case value: TypeDecl => value }
      case _ => None
    }
    owner.filter(_ => !method.isExternal &&
      !method.name.contains("<metaClassAdapter>") && !isFileOrWrapperMethodName(method.name) &&
      scopeContextsByMethod.contains((nodePath(method), method.fullName)))
      .map(value => value.fullName -> method)
  }.groupMap(_._1)(_._2)

  // Python lowers `self.counter.method()` through a synthetic receiver local.
  // Recover only one same-line binding, with exact REF identity and matching
  // source receiver; never follow arbitrary aliases or chains.
  val stepperSyntheticReceiverBindings = assignmentCalls.flatMap { call =>
    call.argument.l.headOption.collect { case identifier: Identifier => identifier }
      .flatMap(rawLocalDeclarationId).filterNot(declarationIds.contains).map(_ -> call)
  }.groupMap(_._1)(_._2)
  def stepperReceiverTarget(receiver: Expression, call: Call): Option[CollectionTarget] = {
    val direct = collectionTarget(receiver, cachedScopeOf(call)).filter(_.isInstanceOf[MemberCollection])
    direct.orElse {
      receiver match
        case identifier: Identifier if isPythonLanguage(language) =>
          rawLocalDeclarationId(identifier).toList.flatMap(id =>
            stepperSyntheticReceiverBindings.getOrElse(id, Nil)) match
            case binding :: Nil if cachedScopeOf(binding) == cachedScopeOf(call) &&
                binding.lineNumber == call.lineNumber =>
              binding.argument.l.drop(1) match
                case (source: Call) :: Nil if source.name == "<operator>.fieldAccess" &&
                    call.code.trim.startsWith(source.code.trim + ".") =>
                  collectionTarget(source, cachedScopeOf(call))
                case _ => None
            case _ => None
        case _ => None
    }
  }
  // A receiver call on the counter itself has no proved numeric state effect.
  // Reject it conservatively; arguments of free calls are not receivers.
  val stepperMemberReceiverCallIds = allCallNodes.iterator
    .filterNot(_.name.startsWith("<operator>"))
    .flatMap { call =>
      call.argument.l.filter(_.argumentIndex == 0) match
        case receiver :: Nil =>
          stepperReceiverTarget(receiver, call).collect { case member: MemberCollection => member.declarationId }
        case _ => None
    }.toSet

  def persistentStepperContextFor(
    declaration: VarDecl, partition: BindingPartition, fixedFlow: FixedValueFlow
  ): Option[PersistentStepperContext] = {
    if declaration.kind != "member" || !fixedFlow.analysisComplete ||
        stepperMemberReceiverCallIds.contains(declaration.id) ||
        resolvedStateMutationsByDecl.getOrElse(declaration.id, Nil).exists(mutation =>
          !fixedFlow.bindingWrites.exists(write => write.method == mutation.method &&
            write.line == mutation.line && write.column == mutation.column)) ||
        fixedFlow.observedStateMutation || fixedFlow.observedExternalRebinding ||
        partition.postInitializationWrites.isEmpty then return None
    memberOwnerById.get(declaration.id).filter { owner =>
      memberByPathName.getOrElse((declaration.pos.path, declaration.name), Nil)
        .count(candidate => memberOwnerById.get(candidate.id).contains(owner)) == 1
    }.flatMap { owner =>
      val ownerMethods = stepperMethodsByOwner.getOrElse(owner._2, Nil)
      val constructors = ownerMethods.filter(isConstructor(_, owner))
      val constructorNames = constructors.map(_.fullName).toSet
      val initialization = fixedFlow.bindingWrites.filter(write =>
        partition.initializationEventIds.contains(write.eventId))
      val sourceConstructorInitialization = constructors.nonEmpty && initialization.nonEmpty &&
        constructors.forall(method => initialization.count(_.method == method.fullName) == 1) &&
        initialization.forall(write => constructorNames.contains(write.method) &&
          write.directWrite && write.operator == "<operator>.assignment" && !write.selfRef &&
          !write.insideControl && stepperEventContext(write).analysisComplete &&
          stepperEventContext(write).loopKey.isEmpty && write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete))
      val verifiedInitialization = (fixedFlow.verifiedInitialization &&
        fixedFlow.initializationEventIds == partition.initializationEventIds) || sourceConstructorInitialization
      val methods = partition.postInitializationWrites.map(_.method).toSet
      val ordinaryNames = ownerMethods.filterNot(isInitializationMethod(_, owner)).map(_.fullName).toSet
      val ordinaryMethods = methods.subsetOf(ordinaryNames)
      val semanticUse = semanticReadsByDecl.getOrElse(declaration.id, Nil).exists(read =>
        read.line > 0 && methods.contains(read.method))
      Option.when(verifiedInitialization && ordinaryMethods && semanticUse)(PersistentStepperContext(owner._2, methods))
    }
  }

  def stepperFlowFor(declaration: VarDecl, partition: BindingPartition, fixedFlow: FixedValueFlow): StepperFlow = {
    val persistent = persistentStepperContextFor(declaration, partition, fixedFlow)
    val updates = partition.postInitializationWrites.flatMap(write =>
      write.rhs.map { rhs =>
        val guards = guardsByWriteEventId.getOrElse(write.eventId, Nil)
        StepperUpdate(write, rhs, guards,
          (rhs.declarationDependencies ++ guards.iterator.flatMap(_.declarationDependencies)) - write.declId)
      }
    )
    val progressions = updates.groupBy(update => stepperEventContext(update.write).loopKey
        .getOrElse(s"method:${update.write.method}"))
      .toList.sortBy(_._1).map { case (contextKey, grouped) =>
        val ordered = grouped.sortBy(update => (update.write.line, update.write.column, update.write.eventId))
        val repeated = ordered.exists(update => stepperEventContext(update.write).loopKey.nonEmpty) ||
          ordered.size >= 2 || persistent.nonEmpty
        StepperProgression(
          contextKey,
          ordered.map(_.write.eventId),
          repeatedContext = repeated,
          implicitIteration = contextKey.startsWith("iterator:"),
          correctionLike = !repeated,
          analysisComplete = ordered.forall(update => update.write.line > 0 && update.rhs.complete &&
            stepperEventContext(update.write).analysisComplete)
        )
      }
    StepperFlow(partition, updates, progressions,
      partition.analysisComplete && updates.size == partition.postInitializationWrites.size &&
        updates.forall(update => stepperEventContext(update.write).analysisComplete) &&
        (declaration.kind != "member" || persistent.nonEmpty), persistent)
  }

  val mrhCandidateMethods = scala.collection.mutable.Set.empty[String]
  val mrhMaxDepth = 64
  val mrhMaxVisitedNodes = 10000
  val mrhMaxCorrections = 5
  val mrhMaxActiveBranches = 128

  /** Traverse the declaration's event graph once from one acquisition.
    *
    * Each CFG path stops at the first semantic read or incompatible definition.
    * Compatible corrections extend the path state. A proof is accepted only
    * when every live branch reaches a read through the same linear correction
    * chain; conditional corrections and mixed read/write frontiers are therefore
    * rejected conservatively.
    */
  def mostRecentTraversal(
    declaration: VarDecl,
    acquisition: AcquisitionEvent,
    writes: List[WriteInfo],
    acquisitionIds: Set[Long]
  ): MostRecentTraversalSummary = {
    profiler.increment("mrh_cfg_searches")
    profiler.increment("mrh_cfg_queries")
    val method = acquisition.write.method
    val start = acquisition.write.cfgNodeId
    val indexedMethod = start.flatMap(cfgMethodByNodeId.get)
    val methodNodes = indexedMethod.map(cfgNodeIdsByMethod.getOrElse(_, Set.empty)).getOrElse(Set.empty)
    val contextNodes =
      if acquisition.contextKey.startsWith("loop:") then
        followerLoopCfgByKey.get(acquisition.contextKey.stripPrefix("loop:")).map(_.nodeIds)
      else None
    val allowed = contextNodes.map(methodNodes.intersect).getOrElse(methodNodes)
    val writesAtNode = dynamicWritesByDeclMethod.getOrElse((declaration.id, method), Nil).iterator
      .flatMap(write => write.cfgNodeId.map(_ -> write))
      .toList.groupMap(_._1)(_._2)
    val semanticReadIds = semanticReadIdsByDeclMethod.getOrElse((declaration.id, method), Set.empty)
    val queue = scala.collection.mutable.Queue.empty[(Long, Int, List[Long])]
    start.foreach(nodeId => queue.enqueue((nodeId, 0, Nil)))
    val visitedStates = scala.collection.mutable.Set.empty[(Long, List[Long])]
    start.foreach(nodeId => visitedStates += ((nodeId, Nil)))
    val visitedNodes = scala.collection.mutable.Set.empty[Long] ++ start
    val successfulChains = scala.collection.mutable.ListBuffer.empty[List[Long]]
    val blockingDefinitions = scala.collection.mutable.Set.empty[Long]
    var ambiguous = start.isEmpty || allowed.isEmpty || start.exists(nodeId => !allowed.contains(nodeId))
    var boundedOut = false

    while queue.nonEmpty && !boundedOut do
      if queue.size > mrhMaxActiveBranches || visitedNodes.size > mrhMaxVisitedNodes then
        boundedOut = true
      else {
        val (current, depth, corrections) = queue.dequeue()
        if depth >= mrhMaxDepth then boundedOut = true
        else {
          val successors = cfgNextSortedById.getOrElse(current, Nil)
          if successors.isEmpty then ambiguous = true
          successors.foreach { next =>
            if allowed.contains(next) then {
              val nodeWrites = writesAtNode.getOrElse(next, Nil).distinctBy(_.eventId)
              if nodeWrites.nonEmpty then {
                if nodeWrites.size != 1 then ambiguous = true
                else {
                  val write = nodeWrites.head
                  if acquisitionIds.contains(write.eventId) || !arithmeticCorrection(write) then
                    blockingDefinitions += write.eventId
                  else if corrections.contains(write.eventId) || corrections.size >= mrhMaxCorrections then
                    ambiguous = true
                  else {
                    val nextCorrections = corrections :+ write.eventId
                    val state = (next, nextCorrections)
                    if !visitedStates.contains(state) then {
                      visitedStates += state
                      visitedNodes += next
                      queue.enqueue((next, depth + 1, nextCorrections))
                    } else ambiguous = true
                  }
                }
              } else if semanticReadIds.contains(next) then {
                successfulChains += corrections
                visitedNodes += next
              } else {
                val state = (next, corrections)
                if !visitedStates.contains(state) then {
                  visitedStates += state
                  visitedNodes += next
                  queue.enqueue((next, depth + 1, corrections))
                }
              }
            }
          }
        }
      }
    ambiguous ||= boundedOut
    val distinctChains = successfulChains.distinct.toList
    if distinctChains.size > 1 then ambiguous = true
    profiler.increment("mrh_cfg_visited_volume", visitedNodes.size.toLong)
    if sys.env.get("SAJANIEMI_DEBUG_MRH_CFG").contains("1") &&
        (distinctChains.isEmpty || blockingDefinitions.nonEmpty || ambiguous) then
      System.err.println(
        s"SAJANIEMI_MRH_CFG_DEBUG method=$method start=$start context=${acquisition.contextKey} " +
          s"chains=$distinctChains blocked=$blockingDefinitions ambiguous=$ambiguous visited=${visitedNodes.size}"
      )
    MostRecentTraversalSummary(
      acquisition.write.eventId,
      distinctChains.headOption.getOrElse(Nil),
      reachableSemanticRead = distinctChains.nonEmpty,
      blockingDefinitions.toSet,
      ambiguous,
      visitedNodes.size
    )
  }

  def mostRecentFlowFor(
    declaration: VarDecl,
    partition: BindingPartition,
    ownerUnambiguous: Boolean,
    hasIndependentStateMutation: Boolean,
    strongImplicitWalker: Boolean
  ): MostRecentFlow = {
    val writes = partition.postInitializationWrites
    val acquisitions = writes.flatMap { write =>
      acquisitionKindFor(declaration, write).flatMap { kind =>
        val context = if declaration.kind == "member" then Some(s"method:${write.method}")
          else write.loopKey.map(key => if key.startsWith("iterator:") then key else s"loop:$key")
        context.map(contextKey => AcquisitionEvent(
          write,
          kind,
          contextKey,
          write.rhs.map(rhs => projectionRootDeclarationIds(rhs.expression)).getOrElse(Set.empty),
          guardsByWriteEventId.getOrElse(write.eventId, Nil)
        ))
      }
    }
    if acquisitions.isEmpty then profiler.increment("mrh_rejected_before_cfg")
    else {
      profiler.increment("mrh_candidate_declarations")
      profiler.increment("mrh_acquisitions", acquisitions.size.toLong)
      profiler.maximum("mrh_max_writes_per_candidate", writes.size.toLong)
      profiler.maximum("mrh_max_reads_per_candidate", semanticReadsByDecl.getOrElse(declaration.id, Nil).size.toLong)
      profiler.maximum("mrh_max_acquisitions_per_candidate", acquisitions.size.toLong)
      mrhCandidateMethods ++= acquisitions.map(_.write.method)
      profiler.increment("mrh_semantic_reads", semanticReadsByDecl.getOrElse(declaration.id, Nil).size.toLong)
    }
    val acquisitionIds = acquisitions.map(_.write.eventId).toSet
    val incompatibleWriteIds = writes.iterator.filterNot(write =>
      acquisitionIds.contains(write.eventId) || arithmeticCorrection(write)
    ).map(_.eventId).toSet
    val holderControlled = acquisitions.exists(acquisition =>
      acquisition.guards.exists(_.declarationDependencies.contains(declaration.id))
    )
    val plausibleSourceRead =
      if declaration.kind == "member" then memberOwnerById.get(declaration.id).exists { case (_, ownerFullName) =>
        semanticReadsByDecl.getOrElse(declaration.id, Nil).exists(read =>
          methodBelongsToOwner(read.method, ownerFullName)
        )
      }
      else acquisitions.forall(acquisition =>
        semanticReadIdsByDeclMethod.getOrElse((declaration.id, acquisition.write.method), Set.empty).nonEmpty
      )
    val prefilterReasons = List(
      Option.when(acquisitions.nonEmpty && incompatibleWriteIds.nonEmpty)("incompatible_write"),
      Option.when(acquisitions.nonEmpty && hasIndependentStateMutation)("state_mutation"),
      Option.when(acquisitions.nonEmpty && strongImplicitWalker)("strong_walker"),
      Option.when(acquisitions.nonEmpty && holderControlled)("holder_guard"),
      Option.when(acquisitions.nonEmpty && declaration.kind == "member" && !ownerUnambiguous)("ambiguous_member_owner"),
      Option.when(acquisitions.nonEmpty && !plausibleSourceRead)("no_plausible_read")
    ).flatten
    if prefilterReasons.nonEmpty then {
      profiler.increment("mrh_rejected_before_cfg")
      prefilterReasons.foreach(reason => profiler.increment(s"mrh_rejected_$reason"))
    }
    val persistentKinds = Set[AcquisitionKind](ParameterToMemberAcquisition,
      ExternalCallToMemberAcquisition, CollectionElementToMemberAcquisition,
      CurrentWalkerElementToMemberAcquisition)
    val persistent = declaration.kind == "member" && ownerUnambiguous && acquisitions.nonEmpty &&
      memberOwnerById.get(declaration.id).exists { owner =>
        acquisitions.forall(acquisition => persistentKinds.contains(acquisition.kind) &&
          methodBelongsToOwner(acquisition.write.method, owner._2) &&
          !isInitializationMethodFullName(acquisition.write.method, owner))
      }
    val persistentMemberRead = persistent && memberOwnerById.get(declaration.id).exists { case (_, ownerFullName) =>
      semanticReadsByDecl.getOrElse(declaration.id, Nil).exists(read =>
        methodBelongsToOwner(read.method, ownerFullName)
      )
    }
    val traversalSummaries =
      if persistentMemberRead || prefilterReasons.nonEmpty then Nil
      else acquisitions.map(mostRecentTraversal(declaration, _, writes, acquisitionIds))
    val summariesByAcquisition = traversalSummaries.map(summary => summary.acquisitionEventId -> summary).toMap
    val corrections = acquisitions.flatMap { acquisition =>
      summariesByAcquisition.get(acquisition.write.eventId).toList.flatMap { summary =>
        Option.when(summary.nearestCorrectionEventIds.nonEmpty)(
          CorrectionSpan(acquisition.write.eventId, summary.nearestCorrectionEventIds)
        )
      }
    }
    profiler.increment("mrh_corrections", corrections.iterator.map(_.correctionEventIds.size.toLong).sum)
    val correctionIds = corrections.iterator.flatMap(_.correctionEventIds).toSet
    val acquisitionsUsed = acquisitions.nonEmpty && (persistentMemberRead ||
      traversalSummaries.size == acquisitions.size && traversalSummaries.forall(summary =>
        summary.reachableSemanticRead && summary.blockingDefinitionEventIds.isEmpty && !summary.ambiguous
      ))
    if acquisitions.nonEmpty && (!acquisitionsUsed || !partition.analysisComplete) then
      profiler.increment("mrh_rejected_after_cfg")
    MostRecentFlow(
      partition,
      acquisitions,
      corrections,
      acquisitionIds ++ correctionIds,
      allAcquisitionsUsed = acquisitionsUsed,
      persistentMember = persistent && persistentMemberRead,
      analysisComplete = prefilterReasons.isEmpty && partition.analysisComplete &&
        writes.forall(_.rhs.exists(_.sourceBacked))
    )
  }

  def controlContextsFor(
    d: VarDecl,
    occurrences: List[Occurrence],
    writes: List[WriteInfo],
    mutations: List[StateMutation],
    implicitPosition: Option[Position]
  ): List[ControlContext] = {
    val bindingWrites = annotationBindingWrites(d, writes).filter(_.directWrite)
    controlContextRangesByMethod.getOrElse(d.method, Nil)
      .flatMap { case (method, path, start, end, header, contextType, subtype) =>
        val relations = scala.collection.mutable.ListBuffer[String]()
        val headerHasReference = occurrences.exists(o => !o.isDeclaration && o.pos.line == start)
        if headerHasReference || implicitPosition.exists(_.line == start) then
          if implicitPosition.exists(_.line == start) then relations += "contains_declaration"
          else relations += "contains_read"
        val bodyWrites = bindingWrites.filter(w => w.line > start && w.line <= end)
        val bodyMutations = mutations.filter(m => m.line > start && m.line <= end)
        val relationPrefix = if contextType == "loop" then "contains_" else "guards_"
        if bodyWrites.exists(_.selfRef) then relations += s"${relationPrefix}update"
        if bodyWrites.exists(w => !w.selfRef) then relations += s"${relationPrefix}write"
        if bodyMutations.nonEmpty then relations += s"${relationPrefix}state_mutation"
        relations.distinct.map(relation => ControlContext(
          path,
          method,
          start,
          1,
          header,
          contextType,
          subtype,
          relation
        ))
      }
      .distinctBy(c => (c.path, c.line, c.contextType, c.subtype, c.relation))
      .sortBy(c => (c.path, c.line, c.relation))
      .toList
  }

  def booleanLiteral(expression: RhsExpr): Option[Boolean] = expression match
    case RhsLiteral(code) => code.trim.toLowerCase match
      case "true" | "1" => Some(true)
      case "false" | "0" => Some(false)
      case _ => None
    case _ => None

  def booleanWriteLiteral(write: WriteInfo): Option[Boolean] =
    write.literalValue.flatMap(value => booleanLiteral(RhsLiteral(value)))
      .orElse(write.rhs.flatMap(rhs => booleanLiteral(rhs.expression)))
      .orElse {
        if write.operator != "<operator>.assignment" || write.selfRef then None
        else {
          val separator = write.code.indexOf('=')
          Option.when(separator >= 0)(write.code.substring(separator + 1).trim)
            .flatMap(value => booleanLiteral(RhsLiteral(value)))
        }
      }

  case class OneWayFlagIdentity(
    canonicalDeclarationId: Long,
    declarationIds: Set[Long],
    writes: List[WriteInfo],
    ownerIdentityComplete: Boolean
  )

  val pythonGlobalDeclarationsByPathName: Map[(String, String), List[VarDecl]] =
    if !languageUpper.contains("PYTHON") then Map.empty
    else declarations.filter { declaration =>
      pythonGlobalsDeclaredByMethod.getOrElse(declaration.method, Set.empty).contains(declaration.name)
    }.groupBy(declaration => (declaration.pos.path, declaration.name))

  val oneWayFlagIdentityByDecl: Map[Long, OneWayFlagIdentity] = profiler.timed("one_way_flag_identity_index") {
    val localIdentities = declarations.flatMap { declaration =>
      val key = (declaration.pos.path, declaration.name)
      val sameSource = sourceBindingsByPathName.getOrElse(key, Nil)
      val wrapperBindings = sameSource.filter(isWrapperDeclaration)
      val declaredGlobals = pythonGlobalDeclarationsByPathName.getOrElse(key, Nil)
      val globalBindings = (wrapperBindings ++ declaredGlobals).distinctBy(_.id)
      if globalBindings.nonEmpty && globalBindings.exists(_.id == declaration.id) then {
        val canonical = wrapperBindings.sortBy(candidate => (candidate.pos.line, candidate.pos.column, candidate.id))
          .headOption.orElse(globalBindings.sortBy(candidate => (candidate.pos.line, candidate.pos.column, candidate.id)).headOption).get
        val writes = globalBindings.flatMap(candidate => writesByDecl.getOrElse(candidate.id, Nil))
          .distinctBy(_.eventId).sortBy(write => (write.line, write.column, write.eventId))
        Some(declaration.id -> OneWayFlagIdentity(canonical.id, globalBindings.map(_.id).toSet, writes,
          ownerIdentityComplete = declaration.id == canonical.id))
      } else if globalBindings.isEmpty then
        Some(declaration.id -> OneWayFlagIdentity(declaration.id, Set(declaration.id),
          writesByDecl.getOrElse(declaration.id, Nil),
          ownerIdentityComplete = sourceScopeContextByDecl.getOrElse(declaration.id, None).nonEmpty))
      else None
    }
    val memberIdentities = memberDeclarations.map { declaration =>
      val ownerComplete = memberOwnerById.get(declaration.id).exists { owner =>
        memberDeclarations.count(candidate => candidate.name == declaration.name &&
          memberOwnerById.get(candidate.id).contains(owner)) == 1
      }
      declaration.id -> OneWayFlagIdentity(
        declaration.id,
        Set(declaration.id),
        fixedMemberWritesByDecl.getOrElse(declaration.id, fieldWritesByDecl.getOrElse(declaration.id, Nil)),
        ownerComplete
      )
    }
    (localIdentities ++ memberIdentities).toMap
  }

  def oneWayFlagPartitionFor(
    declaration: VarDecl,
    ordinaryPartition: BindingPartition
  ): (BindingPartition, Boolean) =
    oneWayFlagIdentityByDecl.get(declaration.id) match
      case Some(identity) if identity.ownerIdentityComplete && identity.canonicalDeclarationId == declaration.id &&
          identity.writes.map(_.eventId).toSet !=
            (ordinaryPartition.initializationEventIds ++ ordinaryPartition.postInitializationWrites.map(_.eventId)) =>
        val ordered = identity.writes.sortBy(write => (write.line, write.column, write.eventId))
        val initializationIds = ordered.headOption.filter(write => write.directWrite &&
          write.operator == "<operator>.assignment" && !write.selfRef &&
          write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete) && booleanWriteLiteral(write).nonEmpty
        ).map(_.eventId).toSet
        BindingPartition(
          initializationIds,
          ordered.filterNot(write => initializationIds.contains(write.eventId)),
          analysisComplete = initializationIds.nonEmpty
        ) -> true
      case Some(identity) if identity.ownerIdentityComplete && identity.canonicalDeclarationId == declaration.id =>
        ordinaryPartition -> true
      case _ => ordinaryPartition -> false

  def oneWayFlagFlowFor(
    declaration: VarDecl,
    partition: BindingPartition,
    ownerIdentityComplete: Boolean,
    observedMutation: Boolean
  ): OneWayFlagFlow = {
    val identity = oneWayFlagIdentityByDecl.get(declaration.id)
    val identityDeclarationIds = identity.map(_.declarationIds).getOrElse(Set(declaration.id))
    val identityWrites = identity.map(_.writes).getOrElse(
      writesByDecl.getOrElse(declaration.id, fieldWritesByDecl.getOrElse(declaration.id, Nil))
    )
    val identityWritesByEvent = identityWrites.map(write => write.eventId -> write).toMap
    val initializationWrites = partition.initializationEventIds.toList.flatMap(eventId =>
      identityWritesByEvent.get(eventId)
    )
    val initializationValues = initializationWrites.flatMap(booleanWriteLiteral).distinct
    val initializationValue = Option.when(initializationWrites.nonEmpty &&
      initializationWrites.forall(write => write.directWrite && write.rhs.exists(rhs =>
        rhs.sourceBacked && rhs.complete
      ) && booleanWriteLiteral(write).nonEmpty) && initializationValues.size == 1)(initializationValues.head)
    val transitions = partition.postInitializationWrites.flatMap { write =>
      write.rhs.toList.flatMap { rhs =>
        booleanWriteLiteral(write).map(value => AssignTerminal(value, write.eventId)).orElse(rhs.expression match
          case RhsBinary("||", RhsSelfRef(id), _) if identityDeclarationIds.contains(id) =>
            Some(MonotoneOr(rhs, write.eventId))
          case RhsBinary("||", _, RhsSelfRef(id)) if identityDeclarationIds.contains(id) =>
            Some(MonotoneOr(rhs, write.eventId))
          case RhsBinary("&&", RhsSelfRef(id), _) if identityDeclarationIds.contains(id) =>
            Some(MonotoneAnd(rhs, write.eventId))
          case RhsBinary("&&", _, RhsSelfRef(id)) if identityDeclarationIds.contains(id) =>
            Some(MonotoneAnd(rhs, write.eventId))
          case _ => None
        )
      }
    }
    val explainedIds = transitions.map(_.eventId).toSet
    val writesExplained = explainedIds == partition.postInitializationWrites.map(_.eventId).toSet
    val writesSourceComplete = partition.postInitializationWrites.forall(write =>
      write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete))
    val writesDirect = partition.postInitializationWrites.forall(_.directWrite)
    if !partition.analysisComplete then profiler.increment("one_way_flag_reject_partition")
    if !ownerIdentityComplete then profiler.increment("one_way_flag_reject_identity")
    if observedMutation then profiler.increment("one_way_flag_reject_mutation")
    if initializationValue.isEmpty then profiler.increment("one_way_flag_reject_initialization")
    if partition.postInitializationWrites.isEmpty then profiler.increment("one_way_flag_reject_no_transition")
    if !writesExplained then profiler.increment("one_way_flag_reject_unexplained_write")
    if !writesSourceComplete then profiler.increment("one_way_flag_reject_incomplete_rhs")
    if !writesDirect then profiler.increment("one_way_flag_reject_indirect_write")
    OneWayFlagFlow(
      initializationValue,
      partition.initializationEventIds,
      transitions,
      identityDeclarationIds,
      ownerIdentityComplete,
      analysisComplete = partition.analysisComplete && ownerIdentityComplete && !observedMutation &&
        initializationValue.nonEmpty && partition.postInitializationWrites.nonEmpty &&
        writesExplained && writesDirect && writesSourceComplete
    )
  }

  def gathererContribution(declarationId: Long, rhs: RhsAnalysis): Option[RhsAnalysis] = {
    val contribution = rhs.expression match
      case RhsBinary(operator @ ("+" | "-" | "*" | "/"), RhsSelfRef(id), other)
          if id == declarationId => Some(other)
      case RhsBinary(operator @ ("+" | "*"), other, RhsSelfRef(id))
          if id == declarationId => Some(other)
      case _ => None
    contribution.map(expression => RhsAnalysis(
      expression,
      rhsDependencies(expression),
      rhs.sourceBacked,
      rhs.complete && isCompleteRhs(expression)
    ))
  }

  val addressingUseDeclarationIds: Set[Long] = profiler.timed("gatherer_target_uses") {
    allIdentifierNodes.flatMap { identifier =>
      val declarationIds = identifier.refsTo.l.collect {
        case local: Local if declById.contains(local.id) || canonicalMemberIdentityByPseudoLocalId.contains(local.id) =>
          canonicalDeclarationId(local.id)
        case parameter: MethodParameterIn if declById.contains(parameter.id) => parameter.id
      }
      val addressing = scala.util.Try(identifier.astParent).toOption.exists {
        case call: Call if Set(
          "<operator>.indexAccess", "<operator>.indirectIndexAccess",
          "<operator>.indirection", "<operator>.addressOf", "<operator>.pointerShift"
        ).contains(call.name) => true
        case _ => false
      }
      if addressing then declarationIds else Nil
    }.toSet
  }
  def textProducingExpression(expression: RhsExpr): Boolean = expression match
    case RhsLiteral(code) => code.matches("(?s).*\"([^\"\\\\]|\\\\.)*\".*") ||
      code.matches("(?s).*'([^'\\\\]|\\\\.)*'.*")
    case RhsCall(name, arguments) =>
      Set("str", "string", "tostring", "to_string", "sprintf", "snprintf", "format", "join")
        .contains(name.toLowerCase.split("[.:]").lastOption.getOrElse("")) ||
        arguments.exists(textProducingExpression)
    case RhsUnary(_, operand) => textProducingExpression(operand)
    case RhsBinary(_, left, right) => textProducingExpression(left) || textProducingExpression(right)
    case _ => false
  def gathererAdmissibilityFor(
    declaration: VarDecl,
    partition: BindingPartition,
    walkerFlow: WalkerFlow
  ): GathererAdmissibility = {
    val declaredType = declarationTypeById.getOrElse(declaration.id, "").toLowerCase
    val pointerOrAddress = declaredType.matches(raw"(?s).*(?:\*|\bpointer\b|\buintptr_t\b|\bintptr_t\b).*")
    val explicitlyTextual = declaredType.matches(
      raw"(?s).*(?:\bstring\b|\bstr\b|\bchar\s*(?:\*|\[)|\bbytes?\b|\bstringbuilder\b).*"
    )
    val textualGrowth = explicitlyTextual || partition.postInitializationWrites.exists { write =>
      write.selfRef && write.operator == "<operator>.assignmentPlus" &&
        write.rhs.exists(rhs => textProducingExpression(rhs.expression))
    }
    val implicitTraversalElement = walkerFlow.implicitElementWalker && !walkerFlow.implicitNumericIterator
    val usedForAddressing = addressingUseDeclarationIds.contains(declaration.id)
    GathererAdmissibility(
      admitted = !implicitTraversalElement && !pointerOrAddress && !usedForAddressing && !textualGrowth,
      implicitTraversalElement,
      pointerOrAddress,
      usedForAddressing,
      textualGrowth
    )
  }
  val emptyGathererFlow = GathererFlow(None, Nil, Nil, Nil, Nil, Nil, Set.empty, analysisComplete = false)

  def gathererFlowFor(declaration: VarDecl, partition: BindingPartition): GathererFlow = {
    val allBindingWrites = (partition.initializationEventIds.toList.flatMap(eventId =>
      writesByDecl.getOrElse(declaration.id, Nil).find(_.eventId == eventId)
        .orElse(fieldWritesByDecl.getOrElse(declaration.id, Nil).find(_.eventId == eventId))
    ) ++ partition.postInitializationWrites).distinctBy(_.eventId)
    val initializationWrites = allBindingWrites.filter(write => partition.initializationEventIds.contains(write.eventId))
    val initialization =
      if declaration.kind == "parameter" then
        Some(InitializationSeed(None, None, implicitParameter = true, dynamicFirstContribution = false))
      else initializationWrites match
        case write :: Nil if write.directWrite && write.operator == "<operator>.assignment" &&
            !write.selfRef && write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete) =>
          val seed = write.rhs.get
          Some(InitializationSeed(Some(write), Some(seed), implicitParameter = false,
            dynamicFirstContribution = booleanLiteral(seed.expression).isEmpty &&
              !seed.expression.isInstanceOf[RhsLiteral]))
        case _ => None

    val postIndependentWrites = partition.postInitializationWrites.filter(write =>
      write.directWrite && write.operator == "<operator>.assignment" && !write.selfRef &&
        write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete)
    )
    val resets = postIndependentWrites.flatMap(write => write.rhs.map(rhs => GathererReset(write, rhs)))
    val arithmeticUpdates = partition.postInitializationWrites.flatMap { write =>
      write.rhs.filter(rhs => write.directWrite && write.selfRef && rhs.sourceBacked && rhs.complete)
        .flatMap(rhs => gathererContribution(declaration.id, rhs).map(contribution => (write, contribution)))
    }
    val loopArithmetic = arithmeticUpdates.filter(_._1.loopKey.nonEmpty)
    val firstLoopUpdateByMethod = loopArithmetic.groupBy(_._1.method).view.mapValues(_.map(_._1)
      .minBy(write => (write.line, write.column, write.eventId))).toMap
    val finalizationCandidates = arithmeticUpdates.filter { case (write, _) =>
      write.loopKey.isEmpty && firstLoopUpdateByMethod.get(write.method).exists(first =>
        eventBefore(first.line, first.column, write.line, write.column)
      )
    }
    def structurallyStableOperand(expression: RhsExpr): Boolean = expression match
      case _: RhsLiteral | _: RhsDeclarationRef => true
      case RhsUnary("+" | "-", operand) => structurallyStableOperand(operand)
      case RhsBinary("+" | "-" | "*" | "/", left, right) =>
        structurallyStableOperand(left) && structurallyStableOperand(right)
      case _ => false
    val stableTransforms = loopArithmetic.collect {
      case (write, contribution) if structurallyStableOperand(contribution.expression) =>
        StableAccumulatorTransform(write, contribution)
    }
    val stableTransformIds = stableTransforms.map(_.write.eventId).toSet
    val dataUpdates = loopArithmetic.collect {
      case (write, contribution) if !stableTransformIds.contains(write.eventId) =>
        DataAccumulationUpdate(write, contribution)
    }
    val finalizations = finalizationCandidates.map { case (write, contribution) =>
      GathererFinalization(write, contribution)
    }
    val explainedIds = initialization.flatMap(_.write.map(_.eventId)).toSet ++
      resets.map(_.write.eventId) ++ dataUpdates.map(_.write.eventId) ++
      stableTransforms.map(_.write.eventId) ++ finalizations.map(_.write.eventId)
    val incompatible = allBindingWrites.map(_.eventId).toSet -- explainedIds
    val spanEvents = (dataUpdates.map(update => (update.write, true)) ++
      stableTransforms.map(transform => (transform.write, false)))
    val spans = spanEvents.groupBy(_._1.loopKey).toList.sortBy(_._1.getOrElse("" )).flatMap {
      case (Some(loopKey), contextEvents) =>
        val method = contextEvents.head._1.method
        val loopRange = loopRangesByMethod.getOrElse(method, Nil).find(_._4 == loopKey)
        val relevantResets = resets.filter(reset => reset.write.method == method &&
          (reset.write.loopKey.isEmpty || reset.write.loopKey.contains(loopKey) ||
            loopRange.exists { case (_, start, end, _, _) =>
              reset.write.line <= start && reset.write.loopKey.exists(resetKey =>
                loopRangesByMethod.getOrElse(method, Nil).find(_._4 == resetKey)
                  .exists { case (_, resetStart, resetEnd, _, _) => resetStart <= start && resetEnd >= end }
              )
            }))
        val events = (relevantResets.map(reset => (reset.write, true)) ++
          contextEvents.map { case (write, _) => (write, false) })
          .sortBy { case (write, _) => (write.line, write.column, write.eventId) }
        val result = scala.collection.mutable.ListBuffer[AccumulationSpan]()
        var resetEventId = Option.empty[Long]
        var data = List.empty[Long]
        var transforms = List.empty[Long]
        val dataIds = dataUpdates.map(_.write.eventId).toSet
        val transformIds = stableTransforms.map(_.write.eventId).toSet
        events.foreach { case (write, isReset) =>
          if isReset then
            if data.nonEmpty || transforms.nonEmpty then
              result += AccumulationSpan(loopKey, resetEventId, data, transforms)
            resetEventId = Some(write.eventId)
            data = Nil
            transforms = Nil
          else if dataIds.contains(write.eventId) then data = data :+ write.eventId
          else if transformIds.contains(write.eventId) then transforms = transforms :+ write.eventId
        }
        if data.nonEmpty || transforms.nonEmpty then
          result += AccumulationSpan(loopKey, resetEventId, data, transforms)
        result.toList
      case (None, _) => Nil
    }
    GathererFlow(
      initialization,
      resets.sortBy(reset => (reset.write.line, reset.write.column, reset.write.eventId)),
      dataUpdates.sortBy(update => (update.write.line, update.write.column, update.write.eventId)),
      stableTransforms.sortBy(transform => (transform.write.line, transform.write.column, transform.write.eventId)),
      finalizations.sortBy(finalization => (finalization.write.line, finalization.write.column, finalization.write.eventId)),
      spans,
      incompatible,
      analysisComplete = partition.analysisComplete && initialization.nonEmpty && incompatible.isEmpty && allBindingWrites.forall(write =>
        write.rhs.exists(rhs => rhs.sourceBacked && rhs.complete)
      )
    )
  }


  val result: SuccessionFlows = SuccessionFlows(annotationImplicitIteratorIds)
}

def extractSuccessionFlows(
  context: ExtractionContextIndex,
  bindings: BindingFactIndex,
  lifecycle: ValueLifecycleFlowIndex,
  followers: FollowerFlowIndex,
  collections: CollectionFlowIndex
): SuccessionFlowIndex =
  new SuccessionFlowIndex(context, bindings, lifecycle, followers, collections)
