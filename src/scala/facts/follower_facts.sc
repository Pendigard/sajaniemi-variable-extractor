import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class FollowerFlowIndex(
  val context: ExtractionContextIndex,
  val bindings: BindingFactIndex,
  val lifecycle: ValueLifecycleFlowIndex
) {
  import context.*
  import bindings.*
  import lifecycle.*
  val writeCallById = writeCalls.map(call => call.id -> call).toMap
  val declarationIds = declById.keySet ++ memberDeclarations.map(_.id)

  def canonicalRubyClosureDeclaration(identifier: Identifier): Option[Long] =
    if !languageUpper.contains("RUBY") then None
    else
      val method = cachedScopeOf(identifier)
      val candidates = declarations.filter { declaration =>
        declaration.name == identifier.name && declaration.pos.path == nodePath(identifier) &&
          method.startsWith(declaration.method + ".<lambda>")
      }.sortBy(declaration => -declaration.method.length)
      candidates.headOption.filter(candidate =>
        candidates.drop(1).headOption.forall(_.method.length < candidate.method.length)
      ).map(_.id)

  def referencedDeclarationId(identifier: Identifier): Option[Long] =
    identifier.refsTo.l.collectFirst {
      case local: Local if declarationIds.contains(local.id) => local.id
      case parameter: MethodParameterIn if declarationIds.contains(parameter.id) => parameter.id
      case member: Member if declarationIds.contains(member.id) => member.id
    }.orElse(canonicalRubyClosureDeclaration(identifier))

  def implicitOwnerSource(method: String, expectedOwner: String): Option[MasterSource] =
    Option.when(methodBelongsToOwner(method, expectedOwner))(
      MasterSource(ImplicitOwnerRoot(expectedOwner), Nil)
    )

  def memberSourceFromIdentifier(identifier: Identifier, method: String): Option[MasterSource] =
    referencedDeclarationId(identifier).flatMap { declarationId =>
      memberOwnerById.get(declarationId).flatMap { owner =>
        implicitOwnerSource(method, owner._2)
          .map(source => source.copy(path = source.path :+ MemberAccess(declarationId, owner._2)))
      }
    }

  def declarationSource(identifier: Identifier): Option[MasterSource] =
    referencedDeclarationId(identifier).filter(declById.contains)
      .map(declarationId => MasterSource(DeclarationRoot(declarationId), Nil))

  def directFieldIdentifier(call: Call): Option[FieldIdentifier] = {
    val direct = call.argument.l.drop(1).flatMap {
      case field: FieldIdentifier => List(field)
      case expression => expression.ast.isFieldIdentifier.l
    }.distinctBy(_.id)
    direct match
      case List(single) => Some(single)
      case _ => None
  }

  def directMasterSource(expression: Expression, method: String): Option[MasterSource] = expression match
    case identifier: Identifier =>
      referencedDeclarationId(identifier).flatMap { declarationId =>
        if declById.contains(declarationId) then declarationSource(identifier)
        else memberSourceFromIdentifier(identifier, method)
      }
    case call: Call if Set("<operator>.fieldAccess", "<operator>.indirectFieldAccess").contains(call.name) =>
      val arguments = call.argument.l
      for
        rootExpression <- arguments.headOption
        fieldIdentifier <- directFieldIdentifier(call)
        member <- resolvedMemberFor(fieldIdentifier, call, method)
        owner <- memberOwnerById.get(member.id)
        rootSource <- rootExpression match
          case rootIdentifier: Identifier if Set("self", "this").contains(rootIdentifier.name) =>
            implicitOwnerSource(method, owner._2)
          case other => directMasterSource(other, method)
      yield rootSource.copy(path = rootSource.path :+ MemberAccess(member.id, owner._2))
    case call: Call if call.name == "<operator>.indexAccess" =>
      call.argument.l match
        case List(rootExpression, indexExpression) =>
          for
            rootSource <- directMasterSource(rootExpression, method)
            index <- allowedIndex(indexExpression)
          yield rootSource.copy(path = rootSource.path :+ IndexAccess(index))
        case _ => None
    case _ => None

  def normalizedLiteral(expression: Expression): Option[String] = expression match
    case literal: Literal => Some(literal.code.trim)
    case call: Call if call.name == "<operator>.minus" =>
      call.argument.l match
        case List(literal: Literal) => Some("-" + literal.code.trim)
        case _ => None
    case _ => None

  def allowedIndex(expression: Expression): Option[AllowedIndex] =
    normalizedLiteral(expression).map(LiteralIndex.apply).orElse(expression match
      case identifier: Identifier =>
        referencedDeclarationId(identifier).map(FixedVariableIndex.apply)
      case _ => None
    )

  def fixedOffset(expression: Expression): Option[FixedOffset] =
    normalizedLiteral(expression).map(LiteralOffset.apply).orElse(expression match
      case identifier: Identifier =>
        referencedDeclarationId(identifier).map(VariableOffset.apply)
      case _ => None
    )

  // Preserve both structural interpretations of addition until raw Fixed Value
  // proofs are available in role_predicates.sc. This avoids a classification cycle.
  def classifyFollowerExpression(expression: Expression, method: String): List[FollowerRhs] = expression match
    case call: Call if call.name == "<operator>.addition" =>
      call.argument.l match
        case List(left, right) =>
          List(
            for
              source <- directMasterSource(left, method)
              offset <- fixedOffset(right)
            yield FollowerRhs(source, Some(offset)),
            for
              offset <- fixedOffset(left)
              source <- directMasterSource(right, method)
            yield FollowerRhs(source, Some(offset))
          ).flatten.distinct
        case _ => Nil
    case call: Call if call.name == "<operator>.subtraction" =>
      call.argument.l match
        case List(left, right) =>
          (for
            source <- directMasterSource(left, method)
            offset <- fixedOffset(right)
          yield FollowerRhs(source, Some(offset))).toList
        case _ => Nil
    case other => directMasterSource(other, method).map(source => FollowerRhs(source, None)).toList

  def classifyFollowerRhs(write: WriteInfo): List[FollowerRhs] =
    writeCallById.get(write.eventId).toList.flatMap { call =>
      if call.name != "<operator>.assignment" then Nil
      else call.argument.l.drop(1) match
        case List(rhs) => classifyFollowerExpression(rhs, write.method)
        case _ => Nil
    }.distinct

  // Validate only the source shape needed to corroborate a structurally recovered
  // flat parallel assignment. The CPG tuple/index mapping remains authoritative.
  def flatParallelSourceArity(code: String): Option[Int] = {
    var round = 0
    var square = 0
    var curly = 0
    var quote: Char = 0.toChar
    var escaped = false
    var assignment = -1
    var invalid = false
    code.zipWithIndex.foreach { case (character, index) =>
      if quote != 0.toChar then
        if escaped then escaped = false
        else if character == '\\' then escaped = true
        else if character == quote then quote = 0.toChar
      else character match
        case '\'' | '"' => quote = character
        case '(' => round += 1
        case ')' => round -= 1
        case '[' => square += 1
        case ']' => square -= 1
        case '{' => curly += 1
        case '}' => curly -= 1
        case '=' if round == 0 && square == 0 && curly == 0 &&
            code.lift(index - 1).forall(ch => !"=!<>:".contains(ch)) &&
            code.lift(index + 1).forall(_ != '=') =>
          if assignment >= 0 then invalid = true else assignment = index
        case _ => ()
      if round < 0 || square < 0 || curly < 0 then invalid = true
    }
    if invalid || quote != 0.toChar || round != 0 || square != 0 || curly != 0 || assignment < 0 then None
    else {
      def flatParts(text: String): Option[List[String]] = {
        var r = 0; var s = 0; var c = 0; var q: Char = 0.toChar; var e = false
        val starts = scala.collection.mutable.ListBuffer(0)
        text.zipWithIndex.foreach { case (ch, index) =>
          if q != 0.toChar then
            if e then e = false else if ch == '\\' then e = true else if ch == q then q = 0.toChar
          else ch match
            case '\'' | '"' => q = ch
            case '(' => r += 1
            case ')' => r -= 1
            case '[' => s += 1
            case ']' => s -= 1
            case '{' => c += 1
            case '}' => c -= 1
            case ',' if r == 0 && s == 0 && c == 0 => starts += index + 1
            case _ => ()
        }
        val ends = starts.drop(1).map(_ - 1).toList :+ text.length
        val parts = starts.toList.zip(ends).map { case (start, end) => text.substring(start, end).trim }
        Option.when(parts.nonEmpty && parts.forall(_.nonEmpty))(parts)
      }
      for
        targets <- flatParts(code.substring(0, assignment))
        values <- flatParts(code.substring(assignment + 1))
        if targets.size == values.size && targets.size >= 2
        if targets.forall(_.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
        if !targets.exists(_.contains("*")) && !values.exists(_.trim.startsWith("*"))
      yield targets.size
    }
  }

  def parallelLogicalDeclarationIds(expression: Expression): Set[Long] =
    expression.ast.isIdentifier.l.flatMap(referencedDeclarationId).map(canonicalDeclarationId).toSet

  def rawLocalDeclarationId(identifier: Identifier): Option[Long] =
    identifier.refsTo.l.collectFirst { case local: Local => local.id }

  // pysrc2cpg lowers `a, b = x, y` to `tmp = (x, y)`, followed by
  // `a = tmp[0]`, `b = tmp[1]`. Reconstruct that one-statement positional map
  // once; synthetic locals never become final sources or annotatable targets.
  val parallelAssignmentGroups = profiler.timed("parallel_assignment_groups") {
    val assignmentsBySyntheticTarget = assignmentCalls.flatMap { call =>
      call.argument.l.headOption.collect { case identifier: Identifier => identifier }.flatMap { identifier =>
        rawLocalDeclarationId(identifier).map(_ -> call)
      }
    }.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
    assignmentCalls.flatMap { loadCall =>
      val arguments = loadCall.argument.l
      val temporary = arguments.headOption.collect { case identifier: Identifier => identifier }
        .flatMap(rawLocalDeclarationId)
      val tuple = arguments.drop(1) match
        case List(call: Call) if call.name == "<operator>.tupleLiteral" => Some(call)
        case _ => None
      (temporary, tuple) match
        case (Some(temporaryId), Some(tupleCall)) if !declarationIds.contains(temporaryId) &&
            assignmentsBySyntheticTarget.getOrElse(temporaryId, Nil).map(_.id) == List(loadCall.id) =>
          val method = cachedScopeOf(loadCall)
          val line = optInt(loadCall.lineNumber, 0)
          val path = nodePath(loadCall)
          val logicalValues = tupleCall.argument.l.sortBy(_.argumentIndex)
          val componentCalls = callsByMethodLine.getOrElse((method, line), Nil).filter { component =>
            component.name == "<operator>.assignment" && component.argument.l.drop(1).exists {
                case index: Call if index.name == "<operator>.indexAccess" =>
                  index.argument.l match
                    case List(identifier: Identifier, literal: Literal) =>
                      rawLocalDeclarationId(identifier).contains(temporaryId) && literal.code.matches("[0-9]+")
                    case _ => false
                case _ => false
              }
          }
          val components = componentCalls.flatMap { component =>
            val componentArguments = component.argument.l
            for
              target <- componentArguments.headOption.collect { case identifier: Identifier => identifier }.toList
              targetId <- referencedDeclarationId(target).filter(declarationIds.contains).toList
              indexCall <- componentArguments.drop(1).collectFirst {
                case call: Call if call.name == "<operator>.indexAccess" => call
              }.toList
              literal <- indexCall.argument.l.drop(1).collectFirst { case value: Literal => value }.toList
              position <- literal.code.toIntOption.toList
              logicalRhs <- logicalValues.lift(position).toList
            yield ParallelAssignmentComponent(
              position, targetId, component.id,
              classifyFollowerExpression(logicalRhs, method), parallelLogicalDeclarationIds(logicalRhs)
            )
          }.sortBy(_.position)
          val sourceCode = sourceLine(path, line)
          val complete = logicalValues.size >= 2 && components.size == logicalValues.size &&
            components.map(_.position) == logicalValues.indices.toList &&
            components.map(_.targetDeclarationId).distinct.size == components.size &&
            sourceCode.flatMap(flatParallelSourceArity).contains(logicalValues.size)
          Option.when(complete)(ParallelAssignmentGroup(
            loadCall.id, method, path, line, sourceCode.get, temporaryId, loadCall.id,
            components.last.targetWriteEventId, components, sourceBacked = true, simultaneous = true
          ))
        case _ => None
    }.distinctBy(_.groupId)
  }
  val parallelComponentByWriteEventId = parallelAssignmentGroups.flatMap(group =>
    group.components.map(component => component.targetWriteEventId -> (group, component))
  ).toMap

  def sourceDeclarationIds(source: MasterSource): Set[Long] = {
    val rootIds = source.root match
      case DeclarationRoot(declarationId) => Set(declarationId)
      case _: ImplicitOwnerRoot => Set.empty[Long]
    source.path.foldLeft(rootIds) {
      case (ids, MemberAccess(memberDeclarationId, _)) => ids + memberDeclarationId
      case (ids, IndexAccess(FixedVariableIndex(declarationId))) => ids + declarationId
      case (ids, _: IndexAccess) => ids
    }
  }

  def accessPathPrefix(prefix: List[MasterAccess], path: List[MasterAccess]): Boolean =
    prefix.size <= path.size && prefix.zip(path).forall(_ == _)

  // Frontends expose an unqualified member either as DeclarationRoot(member)
  // or as ImplicitOwnerRoot(owner) / MemberAccess(member). Canonicalize both
  // declaration-backed forms before comparing exact/ancestor/descendant paths.
  def canonicalMasterSource(source: MasterSource): MasterSource = source.root match
    case DeclarationRoot(declarationId) => memberOwnerById.get(declarationId) match
      case Some((_, ownerFullName)) => MasterSource(
        ImplicitOwnerRoot(ownerFullName),
        MemberAccess(declarationId, ownerFullName) :: source.path
      )
      case None => source
    case _ => source

  // Rebinding an ancestor and mutating an exact source or descendant all evolve
  // the selected value. Structurally distinct siblings never match.
  def affectsMasterSource(updated: MasterSource, selected: MasterSource): Boolean = {
    val canonicalUpdated = canonicalMasterSource(updated)
    val canonicalSelected = canonicalMasterSource(selected)
    canonicalUpdated.root == canonicalSelected.root && (
      accessPathPrefix(canonicalUpdated.path, canonicalSelected.path) ||
        accessPathPrefix(canonicalSelected.path, canonicalUpdated.path)
    )
  }

  def resolvedMemberId(field: FieldIdentifier, method: String): Option[Long] =
    astAncestors(field).collectFirst {
      case call: Call if Set("<operator>.fieldAccess", "<operator>.indirectFieldAccess").contains(call.name) => call
    }.flatMap(call => resolvedMemberFor(field, call, method).map(_.id))

  def referencedDeclarationIds(node: AstNode, method: String): Set[Long] = {
    val identifiers = node.ast.isIdentifier.l.flatMap(referencedDeclarationId)
    val members = node.ast.isFieldIdentifier.l.flatMap(resolvedMemberId(_, method))
    (identifiers ++ members).toSet
  }

  val writeLhsAstNodeIds = writeCalls.flatMap(_.argument.l.headOption.toList.flatMap(_.ast.l.map(_.id))).toSet

  def followerLoopKeys(method: String, line: Int, nodeId: Long): Set[String] =
    if followerRubyIterationClosureMethods.contains(method) then Set(s"ruby_iterator:$method")
    else
      followerLoopCfgInfosByMethod.getOrElse(method, Nil)
        .filter(info => info.nodeIds.contains(nodeId) || lineInside(line, info.start, info.end))
        .sortBy(info => info.end - info.start)
        .headOption.map(_.key).toSet

  // Normalize follower reads independently from TemporaryFlow. In particular,
  // exclude every identifier/field token on an assignment LHS.
  val followerLocalReadsByDecl = allIdentifierNodes
    .filterNot(identifier => writeLhsAstNodeIds.contains(identifier.id))
    .flatMap { identifier =>
      val method = cachedScopeOf(identifier)
      val canonicalRubyOwner = Option.when(followerRubyIterationClosureMethods.contains(method))(
        canonicalRubyClosureDeclaration(identifier)
      ).flatten
      (referencedDeclarationId(identifier).toSet ++ canonicalRubyOwner)
        .filter(declById.contains).map { declarationId =>
          val line = optInt(identifier.lineNumber, 0)
          declarationId -> FollowerRead(
            identifier.id, method, followerLoopKeys(method, line, identifier.id),
            line, optInt(identifier.columnNumber, 0)
          )
        }
    }
  val followerMemberReadsByDecl = allFieldIdentifierNodes
    .filterNot(field => writeLhsAstNodeIds.contains(field.id))
    .flatMap { field =>
      val method = cachedScopeOf(field)
      resolvedMemberId(field, method).map { declarationId =>
        val line = optInt(field.lineNumber, 0)
        declarationId -> FollowerRead(
          field.id, method, followerLoopKeys(method, line, field.id),
          line, optInt(field.columnNumber, 0)
        )
      }
    }
  val followerReadsByDecl = (followerLocalReadsByDecl ++ followerMemberReadsByDecl)
    .groupMap(_._1)(_._2).view
    .mapValues(_.distinctBy(_.nodeId).sortBy(read => (read.line, read.column, read.nodeId))).toMap

  case class MasterUpdateCandidate(
    nodeId: Long,
    cfgNodeId: Option[Long],
    method: String,
    nearestLoopKey: Option[String],
    affectedSource: MasterSource,
    updateKind: String,
    dependencyRoot: Option[AstNode]
  )

  def nearestFollowerLoopKey(method: String, line: Int, nodeId: Long): Option[String] =
    followerLoopKeys(method, line, nodeId).headOption

  val parallelComponentWriteIds = parallelComponentByWriteEventId.keySet
  val bindingMasterUpdates = writeCalls.filterNot(call => parallelComponentWriteIds.contains(call.id)).flatMap { call =>
    val method = cachedScopeOf(call)
    val line = optInt(call.lineNumber, 0)
    call.argument.l.headOption.flatMap(target => directMasterSource(target, method)).map { source =>
      MasterUpdateCandidate(
        call.id, Some(call.id), method, nearestFollowerLoopKey(method, line, call.id),
        source, if call.name == "<operator>.assignment" then "rebinding" else "update", Some(call)
      )
    }
  }

  val parallelMasterUpdates = parallelAssignmentGroups.flatMap { group =>
    group.components.map { component =>
      MasterUpdateCandidate(
        component.targetWriteEventId, Some(group.completionCfgNodeId), group.method,
        nearestFollowerLoopKey(group.method, group.line, group.completionCfgNodeId),
        MasterSource(DeclarationRoot(component.targetDeclarationId), Nil), "atomic_rebinding",
        writeCallById.get(component.targetWriteEventId).flatMap(_.argument.l.drop(1).headOption)
      )
    }
  }

  val rubyShiftMutationCalls =
    if languageUpper.contains("RUBY") then callsByName.getOrElse("<<", Nil) else Nil
  val receiverMasterUpdates = (lengthMutationCalls ++ inPlaceMutationCalls ++ rubyShiftMutationCalls)
    .distinctBy(_.id).flatMap { call =>
      val method = cachedScopeOf(call)
      val line = optInt(call.lineNumber, 0)
      call.argument.l.headOption.flatMap(receiver => directMasterSource(receiver, method)).map { source =>
        MasterUpdateCandidate(
          call.id, Some(call.id), method, nearestFollowerLoopKey(method, line, call.id),
          source, "state_mutation", Some(call)
        )
      }
    }

  val implicitIteratorMasterUpdates = allControlStructureNodes.flatMap { control =>
    val method = cachedScopeOf(control)
    val line = optInt(control.lineNumber, 0)
    implicitIteratorHeaderIds(control).flatMap { identifier =>
      referencedDeclarationId(identifier).filter(declById.contains).map { declarationId =>
        MasterUpdateCandidate(
          control.id, Some(control.id), method, nearestFollowerLoopKey(method, line, control.id),
          MasterSource(DeclarationRoot(declarationId), Nil), "implicit_iterator", None
        )
      }
    }
  }

  val allMasterUpdateCandidates = (bindingMasterUpdates ++ parallelMasterUpdates ++ receiverMasterUpdates ++ implicitIteratorMasterUpdates)
    .distinctBy(update => (update.nodeId, update.affectedSource, update.updateKind))

  def conditionReadsDeclaration(control: ControlStructure, declaration: VarDecl): Boolean =
    (control.condition.l ++ control.astChildren.headOption.toList)
      .distinctBy(_.id)
      .exists { condition =>
        val method = cachedScopeOf(control)
        referencedDeclarationIds(condition, method).contains(declaration.id)
      }

  def guardedFollowerWriteIds(
    declarationId: Long,
    writes: List[WriteInfo],
    reads: List[FollowerRead]
  ): Set[Long] =
    writes.flatMap(write => writeCallById.get(write.eventId)).filter { call =>
      val declaration = declById.get(declarationId).orElse(memberDeclarations.find(_.id == declarationId))
      val method = cachedScopeOf(call)
      val line = optInt(call.lineNumber, 0)
      val astControls = astAncestors(call).collect {
        case control: ControlStructure if isControlType(control.controlStructureType) =>
        val start = optInt(control.lineNumber, 0)
        (start, controlStructureEnd(control, start), control)
      }
      val dependencyGuards = call.controlledBy.l.collect {
        case control: ControlStructure if isControlType(control.controlStructureType) => control: AstNode
      }
      val rangeControls = followerGuardControlsByMethod.getOrElse(method, Nil)
        .collect { case (_, start, end, control) if line > start && line <= end => (start, end, control) }
      declaration.exists(candidate =>
        dependencyGuards.exists(guard =>
          referencedDeclarationIds(guard, method).contains(candidate.id) || reads.exists(read =>
            read.method == method && guard.ast.l.exists(_.id == read.nodeId)
          )
        ) || (astControls ++ rangeControls).distinctBy(_._3.id).exists { case (start, _, control) =>
          val conditionNodeIds = (control.condition.l ++ control.astChildren.headOption.toList)
            .flatMap(_.ast.l.map(_.id)).toSet
          conditionReadsDeclaration(control, candidate) || reads.exists(read =>
            read.method == method && (conditionNodeIds.contains(read.nodeId) || read.line == start)
          )
        }
      )
    }.map(_.id).toSet

  def methodEntryIds(method: String, nodeIds: Set[Long]): Set[Long] =
    nodeIds.filter(id => cfgPrevSortedById.getOrElse(id, Nil).forall(previous => !nodeIds.contains(previous)))

  def methodExitIds(method: String, nodeIds: Set[Long]): Set[Long] =
    nodeIds.filter(id => cfgNextSortedById.getOrElse(id, Nil).forall(next => !nodeIds.contains(next)))

  val cfgNodeById = cfgNodes.map(node => node.id -> node).toMap

  // pysrc2cpg models `yield` as a RETURN without its resume edge. Restore only
  // the unique next source position inside the same loop; ordinary returns,
  // breaks, and positionally ambiguous continuations remain disconnected.
  def pythonYieldResumeEdges(info: LoopCfgInfo): Map[Long, Set[Long]] =
    if !languageUpper.contains("PYTHON") then Map.empty
    else info.nodeIds.toList.flatMap(cfgNodeById.get).collect {
      case result: Return if result.code.trim.startsWith("yield ") &&
          cfgNextSortedById.getOrElse(result.id, Nil).forall(next => !info.nodeIds.contains(next)) => result
    }.flatMap { result =>
      val line = optInt(result.lineNumber, 0)
      val later = info.nodeIds.toList.flatMap(cfgNodeById.get).filter { node =>
        val candidateLine = optInt(node.lineNumber, 0)
        candidateLine > line && candidateLine <= info.end
      }
      val firstLine = later.map(node => optInt(node.lineNumber, Int.MaxValue)).minOption
      val firstLineIds = firstLine.toSet.flatMap(nextLine =>
        later.filter(node => optInt(node.lineNumber, Int.MaxValue) == nextLine).map(_.id)
      )
      val candidates = later.filter(node => firstLineIds.contains(node.id) &&
        cfgPrevSortedById.getOrElse(node.id, Nil).forall(previous => !firstLineIds.contains(previous))
      ).distinctBy(_.id)
      Option.when(candidates.size == 1)(result.id -> Set(candidates.head.id))
    }.toMap

  val pythonYieldResumeEdgesByLoopKey = followerLoopCfgByKey.view
    .mapValues(pythonYieldResumeEdges).toMap

  // Follower paths use a separate bounded may-reachability search. Ruby iterator
  // closure METHODs receive one explicit virtual invocation back-edge; all other
  // loops rely on their real CPG CFG back-edge.
  def followerPathExists(
    starts: Set[Long],
    target: Long,
    blocked: Set[Long],
    allowed: Set[Long],
    virtualBackEdges: Map[Long, Set[Long]] = Map.empty
  ): Boolean = {
    val initial = starts.filter(id => allowed.contains(id) && !blocked.contains(id))
    val queue = scala.collection.mutable.Queue[(Long, Boolean)]()
    initial.toSeq.sorted.foreach(id => queue.enqueue((id, false)))
    val visited = scala.collection.mutable.Set[(Long, Boolean)]() ++ initial.map((_, false))
    var found = initial.contains(target)
    while queue.nonEmpty && !found && visited.size <= 100000 do
      val (current, usedVirtual) = queue.dequeue()
      val ordinary = cfgNextSortedById.getOrElse(current, Nil).filter(allowed.contains).map((_, usedVirtual))
      val virtual =
        if usedVirtual then Nil
        else virtualBackEdges.getOrElse(current, Set.empty).toList.sorted.map((_, true))
      (ordinary ++ virtual).foreach { state =>
        val (next, _) = state
        if !blocked.contains(next) && next == target then found = true
        else if !blocked.contains(next) && !visited.contains(state) then
          visited += state
          queue.enqueue(state)
      }
    found
  }

  // rubysrc2cpg may create a closure-local REF target that has no source-backed
  // LOCAL declaration while the lexical binding exists in the enclosing method.
  // Recover only that unambiguous declaration-backed write for Follower; the
  // shared writes/views used by every other role remain untouched.
  val rubyFollowerRecoveredWritesByDecl: Map[Long, List[WriteInfo]] =
    if !languageUpper.contains("RUBY") then Map.empty
    else
      writeCalls.flatMap { call =>
        val arguments = call.argument.l
        arguments.headOption.collect { case identifier: Identifier => identifier }.toList.flatMap { lhs =>
          val hasModeledTarget = lhs.refsTo.l.exists {
            case local: Local => declById.contains(local.id)
            case parameter: MethodParameterIn => declById.contains(parameter.id)
            case _ => false
          }
          if hasModeledTarget then Nil
          else canonicalRubyClosureDeclaration(lhs).toList.map { declarationId =>
            val method = cachedScopeOf(call)
            val line = optInt(call.lineNumber, optInt(lhs.lineNumber, 0))
            val column = optInt(call.columnNumber, optInt(lhs.columnNumber, 0))
            val rhsCode = arguments.drop(1).map(_.code).mkString(" ")
            val (predictable, nonPredictable) = assignmentPredictability(call.name, rhsCode, lhs.name)
            WriteInfo(
              declarationId, lhs.name, method, line, column, call.code, call.name,
              selfRef = call.name != "<operator>.assignment" || containsName(rhsCode, lhs.name),
              predictable = predictable,
              nonPredictable = nonPredictable,
              insideLoop = false,
              insideControl = insideControl(method, line),
              literalValue = if call.name == "<operator>.assignment" then twoValuedLiteral(rhsCode) else None,
              sourceNames = sourceNamesIn(rhsCode, lhs.name),
              loopKey = None,
              directWrite = true,
              declarationInitializer = false,
              rhsHasMethodCall = rhsHasMethodCall(arguments),
              directSourceDeclId = None,
              eventId = call.id,
              cfgNodeId = Some(call.id),
              definitionNodeId = Some(lhs.id)
            )
          }
        }
      }.groupBy(_.declId).view.mapValues(_.sortBy(write => (write.line, write.column, write.eventId))).toMap

  def followerFlowFor(
    declaration: VarDecl,
    bindingWrites: List[WriteInfo],
    initialization: FixedValueFlow,
    observedMutation: Boolean
  ): FollowerFlow = {
    // Ruby iterator blocks are source-backed lexical loop contexts but rubysrc2cpg
    // exposes them as closure METHODs rather than CONTROL_STRUCTURE nodes.
    val contextualWrites = bindingWrites.map { write =>
      if followerRubyIterationClosureMethods.contains(write.method) then
        write.copy(insideLoop = true, loopKey = Some(s"ruby_iterator:${write.method}"))
      else write
    }
    val provedInitializationIds = initialization.initializationEventIds.filter { eventId =>
      contextualWrites.find(_.eventId == eventId).exists { write =>
        declaration.kind == "member" || write.declarationInitializer || !write.insideLoop
      }
    }
    val memberConstructorInitializationIds =
      if declaration.kind != "member" then Set.empty[Long]
      else
        memberOwnerById.get(declaration.id).toSet.flatMap { owner =>
          contextualWrites.filter { write =>
            isInitializationMethodFullName(write.method, owner) &&
              write.directWrite && write.operator == "<operator>.assignment" && !write.selfRef
          }.map(_.eventId)
        }
    val firstLocalInitialization =
      if declaration.kind != "local" || provedInitializationIds.nonEmpty then Set.empty[Long]
      else
        val reads = fixedReadPositionsByDecl.getOrElse(declaration.id, Nil)
        contextualWrites.sortBy(write => (write.line, write.column, write.eventId)).headOption
          .filter(write =>
            write.directWrite && write.operator == "<operator>.assignment" && !write.selfRef &&
              !write.insideLoop && !reads.exists { case (line, column) =>
                eventBefore(line, column, write.line, write.column)
              }
          )
          .map(write => Set(write.eventId))
          .getOrElse(Set.empty)
    val initializationIds = provedInitializationIds ++ memberConstructorInitializationIds ++ firstLocalInitialization
    val reassignments = contextualWrites
      .filterNot(write => initializationIds.contains(write.eventId))
      .sortBy(write => (write.line, write.column, write.eventId))
      .map(write => FollowerWrite(write,
        parallelComponentByWriteEventId.get(write.eventId)
          .map(_._2.logicalRhsCandidates).getOrElse(classifyFollowerRhs(write))
      ))
    val owner = memberOwnerById.get(declaration.id).map(_._2)
    val ownerUnambiguous = owner.exists { ownerFullName =>
      memberDeclarations.count(candidate =>
        candidate.name == declaration.name && memberOwnerById.get(candidate.id).exists(_._2 == ownerFullName)
      ) == 1
    }

    val reads = followerReadsByDecl.getOrElse(declaration.id, Nil)
    val guardedWrites = guardedFollowerWriteIds(declaration.id, contextualWrites, reads)
    val followerDefinitionIds = contextualWrites.flatMap(write =>
      write.definitionNodeId.toList ++ write.cfgNodeId.toList
    ).toSet
    val candidateSources = reassignments.flatMap(_.rhsCandidates.map(_.masterSource)).distinct
    val atomicTransitions = reassignments.flatMap { reassignment =>
      parallelComponentByWriteEventId.get(reassignment.write.eventId).toList.flatMap { case (group, component) =>
        component.logicalRhsCandidates.flatMap { rhs =>
          group.components.filter(masterComponent =>
            masterComponent.targetDeclarationId != declaration.id &&
              affectsMasterSource(MasterSource(DeclarationRoot(masterComponent.targetDeclarationId), Nil), rhs.masterSource)
          ).map(masterComponent => AtomicFollowerTransition(
            group.groupId, reassignment.write.eventId, masterComponent.targetWriteEventId,
            declaration.id, rhs.masterSource, group.completionCfgNodeId
          ))
        }
      }
    }.distinct
    val relevantMethods = contextualWrites.map(_.method).toSet ++ reads.map(_.method)
    val relevantUpdateCandidates = allMasterUpdateCandidates.filter { update =>
      relevantMethods.contains(update.method) && candidateSources.exists(affectsMasterSource(update.affectedSource, _))
    }
    val masterUpdates = relevantUpdateCandidates.map { update =>
      val directDependencies = parallelComponentByWriteEventId.get(update.nodeId)
        .map(_._2.logicalRhsDeclarationIds)
        .getOrElse(update.dependencyRoot.toSet.flatMap(root => referencedDeclarationIds(root, update.method)))
      val directlyDepends = directDependencies.contains(declaration.id)
      val transitivelyDepends = !directlyDepends && followerDefinitionIds.nonEmpty && update.dependencyRoot.exists { root =>
        root.ast.isIdentifier.l.exists { identifier =>
          val referenced = referencedDeclarationId(identifier)
          val targetIds = sourceDeclarationIds(update.affectedSource)
          referenced.exists(id => id != declaration.id && !targetIds.contains(id)) &&
            reachingDefinitionClosure(identifier, followerDefinitionIds).nonEmpty
        }
      }
      MasterUpdate(
        update.nodeId, update.cfgNodeId, update.method, update.nearestLoopKey,
        update.affectedSource, update.updateKind, directlyDepends, transitivelyDepends
      )
    }.distinctBy(update => (update.nodeId, update.affectedSource, update.updateKind))

    val contexts =
      if declaration.kind == "local" then
        reassignments.flatMap(_.write.loopKey).distinct.sorted.map { loopKey =>
          val method = reassignments.find(_.write.loopKey.contains(loopKey)).map(_.write.method).getOrElse(declaration.method)
          FollowerContext(s"loop:$loopKey", "loop", method, Some(loopKey))
        }
      else if declaration.kind == "member" then
        reassignments.map(_.write.method).distinct.sorted.map { method =>
          FollowerContext(s"method:$method", "method", method, None)
        }
      else Nil

    def contextGraph(context: FollowerContext): Option[(Set[Long], Map[Long, Set[Long]])] =
      context.nearestLoopKey match
        case Some(loopKey) if loopKey.startsWith("ruby_iterator:") =>
          val nodes = cfgNodeIdsByMethod.getOrElse(context.method, Set.empty)
          val entries = methodEntryIds(context.method, nodes)
          val exits = methodExitIds(context.method, nodes)
          Option.when(nodes.nonEmpty && entries.nonEmpty && exits.nonEmpty)(
            nodes -> exits.map(exit => exit -> entries).toMap
          )
        case Some(loopKey) => followerLoopCfgByKey.get(loopKey).map(info =>
          info.nodeIds -> pythonYieldResumeEdgesByLoopKey.getOrElse(loopKey, Map.empty)
        )
        case None =>
          val cfgMethodKeys = reassignments.iterator
            .filter(_.write.method == context.method)
            .flatMap(_.write.cfgNodeId)
            .flatMap(cfgMethodByNodeId.get)
            .toSet
          val cfgMethod = if cfgMethodKeys.size == 1 then cfgMethodKeys.head else context.method
          val nodes = cfgNodeIdsByMethod.getOrElse(cfgMethod, Set.empty)
          Option.when(nodes.nonEmpty)(nodes -> Map.empty[Long, Set[Long]])

    def localCycles(context: FollowerContext, source: MasterSource): List[FollowerCycle] =
      contextGraph(context).toList.flatMap { case (allowed, virtualEdges) =>
        val writes = reassignments.filter(reassignment =>
          reassignment.write.loopKey == context.nearestLoopKey &&
            reassignment.rhsCandidates.exists(_.masterSource == source)
        )
        val updates = masterUpdates.filter(update =>
          update.nearestLoopKey == context.nearestLoopKey && affectsMasterSource(update.affectedSource, source)
        )
        val validUpdates = updates.filterNot(update => update.directlyDependsOnFollower || update.transitivelyDependsOnFollower)
        val contextReads = reads.filter(_.nearestLoopKeys == context.nearestLoopKey.toSet)
        val writeNodeIds = writes.flatMap(_.write.cfgNodeId).toSet
        val updateNodeIds = updates.flatMap(_.cfgNodeId).toSet
        validUpdates.flatMap { update =>
          update.cfgNodeId.toList.flatMap { updateNode =>
            val atomicWrites = writes.filter(reassignment => atomicTransitions.exists(transition =>
              transition.followerWriteEventId == reassignment.write.eventId &&
                transition.masterUpdateEventId == update.nodeId && transition.masterSource == source
            ))
            val orderedWrites = writes.filter { reassignment =>
              reassignment.write.cfgNodeId.exists(writeNode =>
                followerPathExists(Set(writeNode), updateNode, updateNodeIds - updateNode, allowed, virtualEdges)
              )
            }
            val spanWrites = (atomicWrites ++ orderedWrites).distinctBy(_.write.eventId)
            if spanWrites.isEmpty then Nil
            else
              contextReads.flatMap { read =>
                val postUpdate = followerPathExists(
                  Set(updateNode), read.nodeId, writeNodeIds ++ (updateNodeIds - updateNode), allowed, virtualEdges
                )
                val nextWrite = writes.exists { reassignment =>
                  reassignment.write.cfgNodeId.exists { writeNode =>
                    followerPathExists(
                      Set(read.nodeId), writeNode,
                      (writeNodeIds - writeNode) ++ updateNodeIds,
                      allowed, virtualEdges
                    )
                  }
                }
                Option.when(postUpdate && nextWrite)(FollowerCycle(
                  context.key, source, spanWrites.map(_.write.eventId).toSet,
                  update.nodeId, read.nodeId
                ))
              }
          }
        }
      }.distinctBy(cycle => (cycle.contextKey, cycle.masterSource, cycle.masterUpdateId, cycle.postMasterUpdateReadId))

    def persistentMemberCycles(context: FollowerContext, source: MasterSource): List[FollowerCycle] = {
      val writes = reassignments.filter(reassignment =>
        reassignment.write.method == context.method && reassignment.rhsCandidates.exists(_.masterSource == source)
      )
      val allRelevantUpdates = masterUpdates.filter(update =>
        update.method == context.method && affectsMasterSource(update.affectedSource, source)
      )
      val updates = allRelevantUpdates.filterNot(update =>
        update.directlyDependsOnFollower || update.transitivelyDependsOnFollower
      )
      val sameOwnerPersistentReads = owner.toList.flatMap { ownerFullName =>
        reads.filter(read => methodBelongsToOwner(read.method, ownerFullName))
      }.filterNot(read => writes.exists(_.write.definitionNodeId.contains(read.nodeId)))
      profiler.increment("follower_member_candidate_writes", writes.size.toLong)
      profiler.increment("follower_member_candidate_updates", updates.size.toLong)
      profiler.increment("follower_member_persistent_reads", sameOwnerPersistentReads.size.toLong)
      contextGraph(context).toList.flatMap { case (allowed, virtualEdges) =>
        val allWriteNodes = writes.flatMap(_.write.cfgNodeId).toSet
        val allUpdateNodes = allRelevantUpdates.flatMap(_.cfgNodeId).toSet
        writes.flatMap { reassignment =>
          reassignment.write.cfgNodeId.toList.flatMap { writeNode =>
            updates.flatMap { update =>
              update.cfgNodeId.toList.flatMap { updateNode =>
                profiler.increment("follower_member_transition_pairs")
                if updateNode == writeNode then profiler.increment("follower_member_same_event_pairs")
                if !allowed.contains(writeNode) then profiler.increment("follower_member_write_outside_cfg")
                if !allowed.contains(updateNode) then profiler.increment("follower_member_update_outside_cfg")
                val orderedTransition = updateNode != writeNode && followerPathExists(
                  Set(writeNode), updateNode,
                  (allWriteNodes - writeNode) ++ (allUpdateNodes - updateNode - writeNode),
                  allowed, virtualEdges
                )
                if !orderedTransition then Nil
                else {
                  profiler.increment("follower_member_ordered_transitions")
                  val sameMethodReads = sameOwnerPersistentReads.filter(read =>
                    read.method == context.method && followerPathExists(
                      Set(updateNode), read.nodeId, allWriteNodes ++ (allUpdateNodes - updateNode),
                      allowed, virtualEdges
                    )
                  )
                  val externalReads = sameOwnerPersistentReads.filter(_.method != context.method)
                  (sameMethodReads ++ externalReads).map(read => FollowerCycle(
                    context.key, source, Set(reassignment.write.eventId), update.nodeId, read.nodeId
                  ))
                }
              }
            }
          }
        }
      }.distinctBy(cycle => (
        cycle.contextKey, cycle.masterSource, cycle.updateSpanWriteIds,
        cycle.masterUpdateId, cycle.postMasterUpdateReadId
      ))
    }

    val cycles = profiler.timedAccumulating("follower_member_flow") {
      candidateSources.flatMap { source =>
        contexts.flatMap { context =>
          if context.contextKind == "loop" then localCycles(context, source)
          else persistentMemberCycles(context, source)
        }
      }.distinctBy(cycle => (
        cycle.contextKey, cycle.masterSource, cycle.updateSpanWriteIds,
        cycle.masterUpdateId, cycle.postMasterUpdateReadId
      ))
    }

    val contextGraphsComplete = contexts.forall { context =>
      contextGraph(context).nonEmpty || (
        declaration.kind == "member" && cycles.exists(_.contextKey == context.key)
      )
    }
    FollowerFlow(
      initializationIds,
      contextualWrites,
      reassignments,
      reads,
      masterUpdates,
      contexts,
      cycles,
      atomicTransitions,
      guardedWrites,
      owner,
      ownerUnambiguous = declaration.kind != "member" || ownerUnambiguous,
      observedStateMutation = observedMutation,
      analysisComplete = declaration.pos.path != "<unknown>" && declaration.pos.line > 0 &&
        contextGraphsComplete && (!languageUpper.contains("RUBY") || initialization.analysisComplete)
    )
  }

  val sumPreviousValueMutationIds = allWrites
    .filter(w => w.operator == "<operator>.assignmentPlus" && w.directWrite && w.selfRef)
    .map(_.declId)
    .toSet

  val result: FollowerFlows = FollowerFlows(followerReadsByDecl)
}

def extractFollowerFlows(
  context: ExtractionContextIndex,
  bindings: BindingFactIndex,
  lifecycle: ValueLifecycleFlowIndex
): FollowerFlowIndex = new FollowerFlowIndex(context, bindings, lifecycle)
