import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class ValueLifecycleFlowIndex(
  val context: ExtractionContextIndex,
  val bindings: BindingFactIndex
) {
  import context.*
  import bindings.*
  def isTemporaryImportOrDefinitionAssignment(code: String): Boolean = {
    val normalized = code.trim
    normalized.matches("(?s).*\\bfrom\\s+.+\\bimport\\b.*") ||
    normalized.matches("(?s).*\\bimport\\b.*") ||
    normalized.matches("(?s).*\\brequire\\s*\\(.*") ||
    normalized.matches("(?s).*\\bdef\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*") ||
    normalized.matches("(?s).*\\bfunction\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=\\s*function\\b.*")
  }
  def isTemporarySyntaxCandidate(declaration: VarDecl): Boolean = {
    val writes = writesByDecl.getOrElse(declaration.id, Nil)
    declaration.kind == "local" && writes.nonEmpty && writes.forall { write =>
      write.directWrite &&
      write.operator == "<operator>.assignment" &&
      !write.selfRef &&
      !isTemporaryImportOrDefinitionAssignment(write.code)
    }
  }
  val sourceScopeContextByDecl = profiler.timed("source_callable_scope_index") {
    declarations.iterator.map(declaration => declaration.id -> sourceScopeContextFor(declaration)).toMap
  }
  // Temporary is an executable-local role. Several frontends also emit LOCALs
  // for module/class declaration bodies; a scope labelled "function" is not
  // sufficient evidence that such a LOCAL belongs to a real callable.
  def isExecutableSourceCallableLocal(declaration: VarDecl): Boolean = {
    val scope = sourceScopeContextByDecl.getOrElse(declaration.id, None)
    val callableBoundary = scope.exists(context => Set(
      "block", "python_def", "python_def_adapter", "python_lambda",
      "ruby_def", "ruby_do", "ruby_brace", "cpp_lambda_block",
      "javascript_block", "javascript_arrow_expression"
    ).contains(context.boundary))
    val canonicalMemberConflict = memberByPathName
      .getOrElse((declaration.pos.path, declaration.name), Nil)
      .exists(member => member.pos.line == declaration.pos.line)
    declaration.kind == "local" && callableBoundary && !canonicalMemberConflict
  }
  val temporaryAdmissibleIds = profiler.timed("temporary_admissibility") {
    declarations.iterator.filter(isExecutableSourceCallableLocal).map(_.id).toSet
  }
  val temporaryCandidateIds = declarations.iterator
    .filter(declaration => temporaryAdmissibleIds.contains(declaration.id) && isTemporarySyntaxCandidate(declaration))
    .map(_.id).toSet
  profiler.set("temporary_admissible", temporaryAdmissibleIds.size.toLong)
  profiler.set("temporary_admissibility_rejected", (declarations.size - temporaryAdmissibleIds.size).toLong)
  profiler.set("temporary_candidates", temporaryCandidateIds.size.toLong)
  profiler.set("temporary_prefiltered", (declarations.size - temporaryCandidateIds.size).toLong)

  // Identify only structurally explicit, exhaustive branch alternatives. This is
  // deliberately narrower than generic `insideControl`: two independent ifs do
  // not share a control id, and two writes in one branch share a branch id.
  case class ExclusiveBranch(controlId: Long, branchId: Long, exhaustive: Boolean)
  def astAncestors(node: AstNode): List[AstNode] = {
    val result = scala.collection.mutable.ListBuffer[AstNode]()
    var current = scala.util.Try(node.astParent).toOption
    val seen = scala.collection.mutable.Set[Long]()
    while current.nonEmpty && !seen.contains(current.get.id) do
      val parent = current.get
      result += parent
      seen += parent.id
      current = scala.util.Try(parent.astParent).toOption
    result.toList
  }
  def exclusiveBranch(call: Call): Option[ExclusiveBranch] = {
    val ancestors = astAncestors(call)
    val nearestControl = ancestors.collectFirst { case cs: ControlStructure => cs }
    val normalizedControl = nearestControl.flatMap { cs =>
      if cs.controlStructureType.toUpperCase == "ELSE" then
        ancestors.dropWhile(_.id != cs.id).drop(1).collectFirst {
          case parent: ControlStructure if parent.controlStructureType.toUpperCase == "IF" => parent
        }
      else Some(cs)
    }
    normalizedControl.filter(_.controlStructureType.toUpperCase == "IF").flatMap { control =>
      val chain = call.asInstanceOf[AstNode] :: ancestors
      val branchRoot = chain.takeWhile(_.id != control.id).lastOption
      val children = control.astChildren.l
      val branchChildren = children.drop(1)
      val exhaustive = branchChildren.size >= 2 && (
        branchChildren.exists {
          case cs: ControlStructure => cs.controlStructureType.toUpperCase == "ELSE"
          case _ => false
        } || branchChildren.count(_.isInstanceOf[Block]) >= 2 ||
          (languageUpper.contains("JSSRC") && branchChildren.size == 2)
      )
      branchRoot.filter(root => branchChildren.exists(_.id == root.id))
        .map(root => ExclusiveBranch(control.id, root.id, exhaustive))
    }
  }
  val modeledWriteCallIds = allWrites.iterator.map(_.eventId).filter(_ > 0).toSet
  val exclusiveBranchByEventId = writeCalls.filter(call => modeledWriteCallIds.contains(call.id)).flatMap(call =>
    exclusiveBranch(call).map(info => call.id -> info)
  ).toMap

  def normalizedConditionalControl(control: ControlStructure): Option[ControlStructure] =
    if isLoopType(control.controlStructureType) then None
    else if control.controlStructureType.toUpperCase == "ELSE" then
      astAncestors(control).collectFirst {
        case parent: ControlStructure if parent.controlStructureType.toUpperCase == "IF" => parent
      }
    else Option.when(isControlType(control.controlStructureType))(control)

  val guardAnalysisCache = scala.collection.mutable.Map.empty[Long, GuardInfo]
  def guardInfoFor(control: ControlStructure): GuardInfo =
    guardAnalysisCache.getOrElseUpdate(control.id, {
      val method = cachedScopeOf(control)
      val conditionExpressions = (control.condition.l ++ control.astChildren.headOption.toList)
        .collect { case expression: Expression => expression }
        .distinctBy(_.id)
      val analyses = conditionExpressions.map { expression =>
        val normalized = normalizedRhsExpression(expression, -1L, method)
        RhsAnalysis(
          normalized,
          rhsDependencies(normalized),
          sourceBacked = optInt(control.lineNumber, 0) > 0 && control.code.nonEmpty,
          complete = isCompleteRhs(normalized)
        )
      }
      GuardInfo(
        control.id,
        analyses,
        analyses.iterator.flatMap(_.declarationDependencies).toSet,
        sourceBacked = analyses.nonEmpty && analyses.forall(_.sourceBacked),
        complete = analyses.nonEmpty && analyses.forall(_.complete)
      )
    })

  def controllingConditionalGuards(call: Call): List[GuardInfo] = {
    val method = cachedScopeOf(call)
    val line = optInt(call.lineNumber, 0)
    val astControls = astAncestors(call).collect { case control: ControlStructure => control }
    val dependencyControls = call.controlledBy.l.flatMap {
      case control: ControlStructure => List(control)
      case node: AstNode => astAncestors(node).collect { case control: ControlStructure => control }.take(1)
      case _ => Nil
    }
    val rangeControls = followerGuardControlsByMethod.getOrElse(method, Nil).collect {
      case (_, start, end, control) if lineInside(line, start, end) && line != start => control
    }
    (astControls ++ dependencyControls ++ rangeControls)
      .flatMap(normalizedConditionalControl)
      .distinctBy(_.id)
      .sortBy(control => (optInt(control.lineNumber, 0), control.id))
      .map(guardInfoFor)
  }

  val guardsByWriteEventId = profiler.timed("guard_index") {
    writeCalls.iterator.map(call => call.id -> controllingConditionalGuards(call)).filter(_._2.nonEmpty).toMap
  }
  val directGuardByWriteEventId = profiler.timed("mwh_direct_guard_index") {
    writeCalls.iterator.flatMap { call =>
      astAncestors(call).collectFirst {
        case control: ControlStructure if normalizedConditionalControl(control).nonEmpty =>
          call.id -> guardInfoFor(normalizedConditionalControl(control).get)
      }
    }.toMap
  }
  profiler.set("guard_controls", guardAnalysisCache.size.toLong)
  profiler.set("guarded_writes", guardsByWriteEventId.size.toLong)

  // Temporary uses source-resolved definitions plus the real CFG. These facts stay
  // internal and never enter the annotation schema.
  case class LoopCfgInfo(
    key: String,
    method: String,
    start: Int,
    end: Int,
    nodeIds: Set[Long],
    entryIds: Set[Long]
  )
  val temporaryCandidateMethods = declarations
    .filter(declaration => temporaryCandidateIds.contains(declaration.id))
    .map(_.method).toSet
  val knownMethodFullNames = allMethodNodes.map(_.fullName).toSet
  val unresolvedTemporaryCandidateMethods = temporaryCandidateMethods -- knownMethodFullNames
  // Keep the complete historical CFG projection: limiting nodes to METHOD ASTs
  // drops frontend-owned nodes for some JavaScript callables. Adjacency entries
  // still carry method ownership so individual queries never cross a known
  // callable boundary.
  val cfgMethods = allMethodNodes
  profiler.set("cfg_methods", cfgMethods.size.toLong)
  profiler.set("cfg_method_fallbacks", unresolvedTemporaryCandidateMethods.size.toLong)
  val cfgNodeMethodPairs = profiler.timed("cfg_index") { cfgMethods.flatMap { method =>
    (method.ast.l ++ List(method.methodReturn))
      .collect { case node: CfgNode => node.id -> (node, method.fullName) }
  }.distinctBy(_._1) }
  val cfgNodes: List[CfgNode] = cfgNodeMethodPairs.map(_._2._1)
  val cfgMethodByNodeId = cfgNodeMethodPairs.map { case (id, (_, method)) => id -> method }.toMap
  val cfgNextSortedById = cfgNodes.map(node => node.id -> node.cfgNext.l.map(_.id).distinct.sorted).toMap
  val cfgPrevSortedById = cfgNodes.map(node => node.id -> node.cfgPrev.l.map(_.id).distinct.sorted).toMap
  val cfgNodeIdsByMethod = cfgNodeMethodPairs.groupMap(_._2._2)(_._1).view.mapValues(_.toSet).toMap
  val instructionCfgNodeIds = cfgNodes.collect {
    case node: Call => node.id
    case node: ControlStructure => node.id
    case node: Return => node.id
  }.toSet
  val allLoopCfgInfos = allControlStructureNodes
    .filter(cs => isLoopType(cs.controlStructureType))
    .flatMap { cs =>
      cs.lineNumber.map(_.toInt).map { start =>
        val method = cachedScopeOf(cs)
        val end = controlStructureEnd(cs, start)
        val key = s"$method:$start:$end"
        val nodes = cs.ast.l.collect { case node: CfgNode => node }.distinctBy(_.id)
        val ids = nodes.map(_.id).toSet
        val entries = nodes.filter(node => node.cfgPrev.l.exists(prev => !ids.contains(prev.id))).map(_.id).toSet
        LoopCfgInfo(key, method, start, end, ids, entries)
      }
    }
  val loopCfgInfos = allLoopCfgInfos
    .filter(info => temporaryCandidateMethods.contains(info.method) || unresolvedTemporaryCandidateMethods.nonEmpty)
  val loopCfgByKey = loopCfgInfos.map(info => info.key -> info).toMap
  val loopCfgInfosByMethod = loopCfgInfos.groupBy(_.method)
  val followerLoopCfgByKey = allLoopCfgInfos.map(info => info.key -> info).toMap
  val followerLoopCfgInfosByMethod = allLoopCfgInfos.groupBy(_.method)

  def shortestCfgDistance(
    starts: Set[Long],
    target: Long,
    blocked: Set[Long],
    allowed: Option[Set[Long]] = None
  ): Option[Int] = {
    profiler.increment("cfg_reachability_queries")
    val queryMethods = (starts + target).flatMap(cfgMethodByNodeId.get)
    val localMethod = if queryMethods.size == 1 then queryMethods.headOption else None
    if localMethod.nonEmpty then profiler.increment("cfg_method_local_queries")
    else profiler.increment("cfg_method_fallback_queries")
    val permittedStarts = starts.filterNot(blocked).filter(id => allowed.forall(_.contains(id)))
    if permittedStarts.contains(target) then Some(0)
    else {
      val queue = scala.collection.mutable.Queue[(Long, Int)]()
      permittedStarts.toSeq.sorted.foreach(id => queue.enqueue((id, 0)))
      val visited = scala.collection.mutable.Set[Long]() ++ permittedStarts
      var result: Option[Int] = None
      while queue.nonEmpty && result.isEmpty && visited.size <= 100000 do
        val (current, distance) = queue.dequeue()
        cfgNextSortedById.getOrElse(current, Nil).foreach { next =>
          val sameMethod = localMethod.forall(method => cfgMethodByNodeId.get(next).contains(method))
          val permitted = sameMethod && !blocked.contains(next) && allowed.forall(_.contains(next))
          if permitted && next == target then result = Some(distance + 1)
          else if permitted && !visited.contains(next) then
            visited += next
            queue.enqueue((next, distance + 1))
        }
      profiler.increment("cfg_reachability_visited", visited.size.toLong)
      if result.nonEmpty then result
      else if queue.nonEmpty then Some(Int.MaxValue)
      else None
    }
  }

  // The traversal stops at the first modeled definition. Its result therefore
  // depends on the modeled domain; caching a domain-independent transitive
  // closure would cross that conservative boundary and change Temporary or
  // Follower. Keep the domains lazy and cache only identical scientific queries.
  val reachingDefinitionClosureByNode =
    scala.collection.mutable.Map.empty[Long, List[(Set[Long], Set[Long])]]

  def reachingDefinitionClosure(node: StoredNode, modeledDefinitionIds: Set[Long]): Set[Long] = {
    reachingDefinitionClosureByNode.getOrElse(node.id, Nil).find(_._1 == modeledDefinitionIds) match
      case Some((_, cached)) =>
        profiler.increment("reaching_def_cache_hits")
        cached
      case None =>
        profiler.increment("reaching_def_cache_misses")
        profiler.increment("reaching_def_closures")
        val queue = scala.collection.mutable.Queue[StoredNode]()
        node.in("REACHING_DEF").cast[StoredNode].l.sortBy(_.id).foreach(queue.enqueue(_))
        val visited = scala.collection.mutable.Set[Long]()
        val reachedDefinitions = scala.collection.mutable.Set[Long]()
        while queue.nonEmpty && visited.size <= 100000 do
          val current = queue.dequeue()
          if !visited.contains(current.id) then
            visited += current.id
            if modeledDefinitionIds.contains(current.id) then reachedDefinitions += current.id
            else current.in("REACHING_DEF").cast[StoredNode].l.sortBy(_.id).foreach(queue.enqueue(_))
        profiler.increment("reaching_def_visited", visited.size.toLong)
        val result = if queue.nonEmpty then Set.empty[Long] else reachedDefinitions.toSet
        reachingDefinitionClosureByNode.update(node.id,
          (modeledDefinitionIds -> result) :: reachingDefinitionClosureByNode.getOrElse(node.id, Nil))
        result
  }

  // Count instruction-like CFG nodes rather than raw CPG edges. This is the
  // deterministic fallback when source line distance is unavailable or both
  // events share a line.
  def shortestCfgInstructionDistance(
    starts: Set[Long],
    target: Long,
    blocked: Set[Long]
  ): Option[Int] = {
    profiler.increment("cfg_instruction_distance_queries")
    val queryMethods = (starts + target).flatMap(cfgMethodByNodeId.get)
    val localMethod = if queryMethods.size == 1 then queryMethods.headOption else None
    if localMethod.nonEmpty then profiler.increment("cfg_method_local_queries")
    else profiler.increment("cfg_method_fallback_queries")
    val ordering = Ordering.by[(Int, Long), (Int, Long)] { case (distance, id) => (-distance, -id) }
    val queue = scala.collection.mutable.PriorityQueue.empty[(Int, Long)](ordering)
    val distances = scala.collection.mutable.Map[Long, Int]()
    starts.filterNot(blocked).toSeq.sorted.foreach { start =>
      distances(start) = 0
      queue.enqueue((0, start))
    }
    var result: Option[Int] = None
    while queue.nonEmpty && result.isEmpty && distances.size <= 100000 do
      val (distance, current) = queue.dequeue()
      if distances.get(current).contains(distance) then
        cfgNextSortedById.getOrElse(current, Nil).foreach { next =>
          val sameMethod = localMethod.forall(method => cfgMethodByNodeId.get(next).contains(method))
          if sameMethod && !blocked.contains(next) then
            val step = if next == target || instructionCfgNodeIds.contains(next) then 1 else 0
            val candidate = distance + step
            if next == target then result = Some(candidate)
            else if candidate < distances.getOrElse(next, Int.MaxValue) then
              distances(next) = candidate
              queue.enqueue((candidate, next))
        }
    profiler.increment("cfg_instruction_distance_visited", distances.size.toLong)
    result
  }

  val bindingDefinitionNodeIds = allWrites.flatMap(w => w.definitionNodeId.toList ++ w.cfgNodeId.toList).toSet
  val normalizedReadIdentifiers = allIdentifierNodes
    .filterNot(id => bindingDefinitionNodeIds.contains(id.id))
    .flatMap { id =>
      val declarationIds = id.refsTo.l.collect {
        case local: Local if declById.contains(local.id) || canonicalMemberIdentityByPseudoLocalId.contains(local.id) =>
          canonicalDeclarationId(local.id)
        case parameter: MethodParameterIn if declById.contains(parameter.id) => parameter.id
      }.distinct
      declarationIds.map(declarationId => (id, declarationId))
    }
  val fixedReadPositionsByDecl = normalizedReadIdentifiers
    .groupMap(_._2) { case (id, _) => (optInt(id.lineNumber, 0), optInt(id.columnNumber, 0)) }
  val temporaryReadIdentifiers = normalizedReadIdentifiers
    .filter { case (_, declarationId) => temporaryCandidateIds.contains(declarationId) }
  profiler.set("temporary_read_identifiers", temporaryReadIdentifiers.size.toLong)
  profiler.set("reaching_def_closures_avoided", (normalizedReadIdentifiers.size - temporaryReadIdentifiers.size).toLong)
  val readEventsByDecl = profiler.timed("reaching_def_reads") { temporaryReadIdentifiers
    .groupBy(_._1.id)
    .valuesIterator
    .flatMap { sameIdentifier =>
      val id = sameIdentifier.head._1
      val declarationIds = sameIdentifier.map(_._2).distinct
      val reachingSources = reachingDefinitionClosure(id, bindingDefinitionNodeIds)
      declarationIds.map { declarationId =>
        val method = cachedScopeOf(id)
        val line = optInt(id.lineNumber, 0)
        val containingLoopKeys = loopCfgInfosByMethod.getOrElse(method, Nil)
          .filter(info => info.nodeIds.contains(id.id))
          .map(_.key)
          .toSet
        // Some frontends chain REACHING_DEF through earlier reads instead of
        // linking every read directly to the binding definition. Normalize the
        // transitive closure so all reads reached by the same write are retained.
        ReadEvent(
          declarationId,
          id.id,
          method,
          line,
          optInt(id.columnNumber, 0),
          containingLoopKeys,
          reachingSources
        )
      }
    }
    .groupBy(_.declId)
    .view.mapValues(_.distinctBy(_.nodeId).sortBy(read => (read.line, read.column, read.nodeId))).toMap }

  def temporaryFlowFor(declaration: VarDecl, writes: List[WriteInfo]): TemporaryFlow = {
    if !temporaryCandidateIds.contains(declaration.id) then
      return TemporaryFlow(Nil, Map.empty, analysisComplete = false, loopSafe = false)
    profiler.increment("temporary_flow_built")
    val reads = readEventsByDecl.getOrElse(declaration.id, Nil)
    val allDefinitionIds = writes.flatMap(w => w.definitionNodeId.toList ++ w.cfgNodeId.toList).toSet
    val orderedVariableEventIds = (
      writes.map(write => (write.line, write.column, write.eventId)) ++
      reads.map(read => (read.line, read.column, read.nodeId))
    ).sortBy { case (line, column, id) => (if line > 0 then line else Int.MaxValue, column, id) }
      .map(_._3)
    val eventOrdinalById = orderedVariableEventIds.zipWithIndex.toMap
    val associations = writes.map { write =>
      val sources = write.definitionNodeId.toSet ++ write.cfgNodeId.toSet
      val blocked = allDefinitionIds -- sources
      val linked = reads
        .filter(read => read.reachingDefinitionNodeIds.intersect(sources).nonEmpty)
        .flatMap { read =>
          shortestCfgInstructionDistance(sources, read.nodeId, blocked).map { instructionDistance =>
            val ordinalDistance = for
              writeOrdinal <- eventOrdinalById.get(write.eventId)
              readOrdinal <- eventOrdinalById.get(read.nodeId)
              if write.line > 0 && read.line == write.line && readOrdinal >= writeOrdinal
            yield readOrdinal - writeOrdinal
            DefinitionRead(read, ordinalDistance.getOrElse(instructionDistance))
          }
        }
        .sortBy(link => (link.read.line, link.read.column, link.read.nodeId))
      write.eventId -> linked
    }.toMap
    val linkedReadIds = associations.values.flatten.map(_.read.nodeId).toSet
    val nodeBackedWrites = writes.nonEmpty && writes.forall(write => write.cfgNodeId.nonEmpty && write.definitionNodeId.nonEmpty)
    val associationComplete = nodeBackedWrites && reads.nonEmpty && reads.forall(read => linkedReadIds.contains(read.nodeId))
    val loopSafe = reads.forall { read =>
      val lineLoopKeys = loopRangesByMethod.getOrElse(read.method, Nil)
        .filter { case (_, start, end, _, _) => lineInside(read.line, start, end) }
        .map(_._4)
        .toSet
      val membershipReliable = lineLoopKeys.subsetOf(read.loopKeys)
      membershipReliable && read.loopKeys.forall { loopKey =>
        loopCfgByKey.get(loopKey).exists { loop =>
          val loopWriteNodes = writes.flatMap(write =>
            write.definitionNodeId.toList ++ write.cfgNodeId.toList
          ).filter(loop.nodeIds.contains).toSet
          loop.entryIds.nonEmpty && loopWriteNodes.nonEmpty &&
            shortestCfgDistance(loop.entryIds, read.nodeId, loopWriteNodes, Some(loop.nodeIds)).isEmpty
        }
      }
    }
    TemporaryFlow(reads, associations, associationComplete, loopSafe)
  }

  def eventBefore(line: Int, column: Int, otherLine: Int, otherColumn: Int): Boolean =
    line > 0 && otherLine > 0 && (line < otherLine || (line == otherLine && column < otherColumn))

  val rubyIterationClosureMethods =
    if languageUpper.contains("RUBY") then
      allMethodNodes.filter(_.name.startsWith("<lambda>"))
        .filter { method =>
          method.lineNumber.exists(line => sourceLine(nodePath(method), line.toInt).exists(_.matches(
            "(?s).*\\.(?:each|each_index|map|collect|times|upto|downto)\\b.*"
          )))
        }.map(_.fullName).toSet
    else Set.empty[String]
  val followerRubyIterationClosureMethods =
    if languageUpper.contains("RUBY") then
      rubyIterationClosureMethods ++ allMethodNodes.filter(_.name.startsWith("<lambda>"))
        .filter(method => firstLine(method.code).matches(
          "(?s).*\\.(?:each|each_index|map|collect|times|upto|downto)\\b.*"
        )).map(_.fullName)
    else Set.empty[String]
  val javascriptIterationClosureMethods =
    if languageUpper.contains("JSSRC") || languageUpper.contains("JAVASCRIPT") then
      allMethodNodes.filter(method => method.name.startsWith("<lambda>") || method.name.startsWith("<anonymous>"))
        .filter(method => method.lineNumber.exists(line => sourceLine(nodePath(method), line.toInt).exists(_.matches(
          "(?s).*\\.(?:forEach|map|flatMap|filter|reduce|some|every)\\s*\\(.*"
        )))).map(_.fullName).toSet
    else Set.empty[String]
  val iteratorClosureMethods = followerRubyIterationClosureMethods ++ javascriptIterationClosureMethods
  val iteratorContextByMethod = profiler.timed("iterator_contexts") {
    iteratorClosureMethods.toList.sorted.map(method => method -> s"iterator:$method").toMap
  }
  profiler.set("iterator_context_count", iteratorContextByMethod.size.toLong)

  val pythonGlobalsDeclaredByMethod: Map[String, Set[String]] =
    if languageUpper.contains("PYTHON") then
      sourceMethodRanges.map { case (path, start, end, fullName, _, _) =>
        val names = sourceLines(path).toList.flatten
          .slice(math.max(0, start - 1), math.min(sourceLines(path).map(_.size).getOrElse(0), end))
          .flatMap { line =>
            "^\\s*global\\s+(.+)$".r.findFirstMatchIn(line).toList
              .flatMap(_.group(1).split(",").map(_.trim).filter(_.matches("[A-Za-z_$][A-Za-z0-9_$]*")))
          }.toSet
        fullName -> names
      }.filter(_._2.nonEmpty).toMap
    else Map.empty
  def directCpgMethod(node: AstNode): String = node match
    case call: Call => scala.util.Try(call.method.fullName).toOption.filter(_.nonEmpty).getOrElse(cachedScopeOf(call))
    case _ => cachedScopeOf(node)
  val pythonExternalGlobalRebindings: Set[(String, String)] =
    if languageUpper.contains("PYTHON") then
      writeCalls.flatMap { call =>
        val method = directCpgMethod(call)
        call.argument.l.headOption.toList.map(_.code.trim)
          .filter(name => pythonGlobalsDeclaredByMethod.getOrElse(method, Set.empty).contains(name))
          .map(name => (nodePath(call), name))
      }.toSet
    else Set.empty
  val pythonDirectGlobalStateMutations: Set[(String, String)] =
    if languageUpper.contains("PYTHON") then
      (lengthMutationCalls ++ inPlaceMutationCalls).flatMap { call =>
        val method = directCpgMethod(call)
        "^\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.".r.findFirstMatchIn(call.code).toList
          .map(_.group(1))
          .filterNot(name => declarationsByMethodName.getOrElse((method, name), Nil).exists(_.kind == "local"))
          .map(name => (nodePath(call), name))
      }.toSet
    else Set.empty
  val sourceBindingsByPathName = declarations.filter(_.kind == "local")
    .groupBy(declaration => (declaration.pos.path, declaration.name))
  val wrapperMethodNames = Set("<global>", "<module>", "<main>", ":program")
  def isWrapperDeclaration(candidate: VarDecl): Boolean =
    methodByFullName.get(candidate.method).exists(method => wrapperMethodNames.contains(method.name)) ||
      candidate.method.endsWith(":<global>") || candidate.method.endsWith(":<module>") ||
      candidate.method.endsWith(":<main>") || candidate.method.endsWith("::program") ||
      (languageUpper.contains("PYTHON") && !sourceMethodRangesByPath.getOrElse(candidate.pos.path, Nil).exists {
        case (_, start, end, _, name, _) =>
          lineInside(candidate.pos.line, start, end) && !isOwnershipSyntheticMethodName(name)
      })
  def canonicalSourceBinding(candidates: List[VarDecl]): Option[VarDecl] = {
    val canonicalGlobal = candidates.filter(isWrapperDeclaration)
      .sortBy(candidate => (candidate.pos.line, candidate.pos.column, candidate.id)).headOption
    val canonicalRubyClosureOwner =
      if languageUpper.contains("RUBY") then
        candidates.filter(outer => candidates.exists(inner =>
          inner.id != outer.id && inner.method.startsWith(outer.method + ".<lambda>")
        )).sortBy(candidate => (candidate.method.length, candidate.pos.line, candidate.id)).headOption
      else None
    canonicalGlobal.orElse(canonicalRubyClosureOwner)
  }

  // A local's first separate assignment is accepted only when it is source-backed
  // and no normalized read precedes it. Multiple initializers are accepted solely
  // when they are the distinct arms of one exhaustive CPG IF node.
  def fixedValueFlowFor(
    declaration: VarDecl,
    writes: List[WriteInfo],
    mutations: List[StateMutation]
  ): FixedValueFlow = {
    val sameSourceBindings = sourceBindingsByPathName.getOrElse(
      (declaration.pos.path, declaration.name), Nil
    )
    val canonicalGlobal = sameSourceBindings.filter(isWrapperDeclaration)
      .sortBy(candidate => (candidate.pos.line, candidate.pos.column, candidate.id)).headOption
    val canonicalBinding = canonicalSourceBinding(sameSourceBindings)
    val isNonCanonicalAlias = canonicalBinding.nonEmpty && !canonicalBinding.exists(_.id == declaration.id)
    val analyzedWrites = canonicalBinding.filter(_.id == declaration.id)
      .map(_ => sameSourceBindings.flatMap(candidate => writesByDecl.getOrElse(candidate.id, Nil)).distinctBy(_.eventId))
      .getOrElse(writes)
    val analyzedMutations = canonicalBinding.filter(_.id == declaration.id)
      .map(_ => sameSourceBindings.flatMap(candidate => stateMutationsByDecl.getOrElse(candidate.id, Nil)))
      .getOrElse(mutations)
    val isCanonicalPythonGlobal = languageUpper.contains("PYTHON") &&
      canonicalGlobal.exists(_.id == declaration.id)
    val globalKey = (declaration.pos.path, declaration.name)
    val externalGlobalRebinding = isCanonicalPythonGlobal && pythonExternalGlobalRebindings.contains(globalKey)
    val directGlobalStateMutation = isCanonicalPythonGlobal && pythonDirectGlobalStateMutations.contains(globalKey)
    val hasRubyLoopWrite = analyzedWrites.exists(write => rubyIterationClosureMethods.contains(write.method))
    if isNonCanonicalAlias then
      FixedValueFlow(analyzedWrites, Set.empty, false, false, false, externalGlobalRebinding,
        analyzedMutations.nonEmpty || fixedRubyShiftMutationIds.contains(declaration.id),
        analyzedWrites.exists(_.insideLoop) || hasRubyLoopWrite)
    else if declaration.kind == "parameter" then
      FixedValueFlow(analyzedWrites, Set.empty, true, true, true, externalGlobalRebinding,
        analyzedMutations.nonEmpty || fixedRubyShiftMutationIds.contains(declaration.id),
        analyzedWrites.exists(_.insideLoop) || hasRubyLoopWrite)
    else
      val ordered = analyzedWrites.sortBy(w => (w.line, w.column, w.eventId))
      val assignmentShape = ordered.filter(w =>
        w.directWrite && w.operator == "<operator>.assignment" && !w.selfRef
      )
      val declarationInitializers = assignmentShape.filter(_.declarationInitializer)
      val reads = fixedReadPositionsByDecl.getOrElse(declaration.id, Nil)
      def noReadBefore(write: WriteInfo): Boolean =
        !reads.exists { case (line, column) => eventBefore(line, column, write.line, write.column) }

      val (initializationIds, verified, exclusive) =
        if declarationInitializers.size == 1 then
          (declarationInitializers.map(_.eventId).toSet, true, true)
        else if declarationInitializers.size > 1 then
          (Set.empty[Long], false, false)
        else if assignmentShape.size == 1 && noReadBefore(assignmentShape.head) then
          (Set(assignmentShape.head.eventId), true, true)
        else if assignmentShape.size >= 2 && assignmentShape.forall(noReadBefore) then
          val branches = assignmentShape.flatMap(w => exclusiveBranchByEventId.get(w.eventId))
          val oneControl = branches.map(_.controlId).distinct.size == 1
          val distinctArms = branches.map(_.branchId).distinct.size == assignmentShape.size
          val exhaustive = branches.nonEmpty && branches.forall(_.exhaustive)
          if branches.size == assignmentShape.size && oneControl && distinctArms && exhaustive then
            (assignmentShape.map(_.eventId).toSet, true, true)
          else (Set.empty[Long], false, false)
        else (Set.empty[Long], false, false)

      FixedValueFlow(
        ordered,
        initializationIds,
        verifiedInitialization = verified,
        atMostOneInitializationPerPath = exclusive,
        analysisComplete = declaration.pos.line > 0 && declaration.pos.path != "<unknown>",
        observedExternalRebinding = externalGlobalRebinding,
        observedStateMutation = analyzedMutations.nonEmpty || directGlobalStateMutation || fixedRubyShiftMutationIds.contains(declaration.id),
        observedLoopEvent = analyzedWrites.exists(_.insideLoop) || hasRubyLoopWrite
      )
  }

  val fixedMemberWritesByDecl = (fixedMemberBindingWrites ++ fixedMemberDeclarationInitializerWrites ++
    canonicalMemberLocalWrites)
    .distinctBy(write => (write.declId, write.eventId, write.line, write.column, write.code))
    .map(attachNormalizedRhs)
    .groupBy(_.declId)
    .view.mapValues(_.sortBy(write => (write.line, write.column, write.eventId))).toMap

  def fixedMemberFlowFor(declaration: VarDecl): FixedValueFlow = {
    val writes = fixedMemberWritesByDecl.getOrElse(declaration.id, Nil)
    memberOwnerById.get(declaration.id) match
      case None => FixedValueFlow(writes, Set.empty, false, false, false, false,
        fixedMutatedMemberIds.contains(declaration.id),
        writes.exists(_.insideLoop) || fixedLoopMutatedMemberIds.contains(declaration.id))
      case Some(owner) =>
        val declarationInitializers = writes.filter(_.declarationInitializer)
        val constructors = methodsByOwnerFullName.getOrElse(owner._2, Nil)
          .filter(method => isConstructor(method, owner))
          .filterNot(method => method.code.matches(s"(?s).*:\\s*(?:this|${java.util.regex.Pattern.quote(owner._1)})\\s*\\(.*"))
        val constructorNames = constructors.map(_.fullName).toSet
        val constructorWrites = writes.filter(write => constructorNames.contains(write.method))
        val validConstructorWrites = constructorWrites.forall(write =>
          write.directWrite && write.operator == "<operator>.assignment" && !write.selfRef &&
          !write.insideControl && !write.insideLoop
        )
        val eachConstructorInitializesOnce = constructors.nonEmpty && constructors.forall { constructor =>
          constructorWrites.count(_.method == constructor.fullName) == 1
        }
        val initializationIds =
          if declarationInitializers.size == 1 then declarationInitializers.map(_.eventId).toSet
          else if declarationInitializers.isEmpty && validConstructorWrites && eachConstructorInitializesOnce then
            constructorWrites.map(_.eventId).toSet
          else Set.empty[Long]
        val verified = initializationIds.nonEmpty
        FixedValueFlow(
          writes,
          initializationIds,
          verifiedInitialization = verified,
          atMostOneInitializationPerPath = verified,
          analysisComplete = true,
          observedExternalRebinding = false,
          observedStateMutation = fixedMutatedMemberIds.contains(declaration.id),
          observedLoopEvent = writes.exists(_.insideLoop) || fixedLoopMutatedMemberIds.contains(declaration.id)
        )
  }

  val localFixedValueFlowByDecl = declarations.map { declaration =>
    val writes = writesByDecl.getOrElse(declaration.id, Nil)
    val mutations = stateMutationsByDecl.getOrElse(declaration.id, Nil)
    declaration.id -> fixedValueFlowFor(declaration, writes, mutations)
  }.toMap
  val mwhLoopContextsByWriteEventId: Map[Long, List[String]] = profiler.timed("mwh_loop_context_index") {
    writeCalls.iterator.flatMap { call =>
      val loops = (astAncestors(call).collect { case control: ControlStructure if isLoopType(control.controlStructureType) => control } ++
        call.controlledBy.l.collect { case control: ControlStructure if isLoopType(control.controlStructureType) => control })
        .distinctBy(_.id).flatMap { control =>
          control.lineNumber.map(_.toInt).map { start =>
            val end = controlStructureEnd(control, start)
            (s"${cachedScopeOf(control)}:$start:$end", start, end)
          }
        }.sortBy { case (_, start, end) => (end - start, -start) }.map(_._1)
      Option.when(loops.nonEmpty)(call.id -> loops)
    }.toMap
  }
  val memberFixedValueFlowByDecl = memberDeclarations.map { declaration =>
    declaration.id -> fixedMemberFlowFor(declaration)
  }.toMap


  val result: ValueLifecycleFlows = ValueLifecycleFlows(
    fixedValueByDeclaration = localFixedValueFlowByDecl,
    fixedValueByMember = memberFixedValueFlowByDecl,
    temporaryAdmissibleIds = temporaryAdmissibleIds
  )
}

def extractValueLifecycleFlows(
  context: ExtractionContextIndex,
  bindings: BindingFactIndex
): ValueLifecycleFlowIndex = new ValueLifecycleFlowIndex(context, bindings)
