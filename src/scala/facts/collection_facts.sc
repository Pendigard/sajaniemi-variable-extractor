import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class CollectionFlowIndex(
  val context: ExtractionContextIndex,
  val bindings: BindingFactIndex,
  val lifecycle: ValueLifecycleFlowIndex,
  val followers: FollowerFlowIndex
) {
  import context.*
  import bindings.*
  import lifecycle.*
  import followers.*
  // Collection-typed declarations and collection literals establish the "Is a Collection" rule.
  def codeLooksCollection(code: String): Boolean = {
    val lower = code.toLowerCase
    lower.matches("(?s).*\\b(?:arraylist|collection|deque|dict|hashmap|hashset|iterable|list|map|queue|set|stack|string|vector)\\b.*") ||
    lower.matches("(?s).*\\bnew\\s+array\\s*\\(.*") ||
    lower.matches("(?s).*\\bstd::(?:array|deque|list|map|queue|set|string|unordered_map|unordered_set|vector)\\b.*") ||
    lower.matches("(?s).*=[\\s]*\\[.*") ||
    lower.matches("(?s).*\\[\\s*\\].*") ||
    lower.matches("(?s).*\\{\\s*\\}.*") ||
    lower.matches("(?s).*(['\"])\\s*\\1.*")
  }
  def nameLooksCollection(name: String): Boolean =
    name.toLowerCase.matches("(?s).*(?:arr|array|buffer|collection|dict|files|items|keys|list|options|queue|results|rows|stack|values).*")
  def hasSequentialCollectionEvidence(f: VarFacts): Boolean = {
    val seed = (f.decl.pos.code :: f.writes.headOption.map(_.code).toList).mkString("\n").toLowerCase
    seed.matches("(?s).*\\b(?:array|arraylist|deque|list|queue|stack|vector)\\b.*") ||
    seed.matches("(?s).*\\bstd::(?:array|deque|list|queue|vector)\\b.*") ||
    seed.matches("(?s).*=[\\s]*\\[.*") ||
    f.decl.name.toLowerCase.matches("(?s).*(?:arr|array|buffer|items|list|queue|results|rows|stack|values).*")
  }
  def hasCollectionSeed(d: VarDecl, writes: List[WriteInfo]): Boolean =
    codeLooksCollection(d.pos.code) ||
    writes.headOption.exists(w => codeLooksCollection(w.code))
  def hasIndependentCollectionEvidence(f: VarFacts): Boolean =
    codeLooksCollection(f.decl.pos.code) ||
    f.writes.headOption.exists(w => codeLooksCollection(w.code)) ||
    nameLooksCollection(f.decl.name)
  def collectionOperationIds: Set[Long] =
    lengthMutationIds ++ inPlaceMutationIds

  def collectionKindFromText(text: String): Option[CollectionKind] = {
    val lower = text.toLowerCase
    if lower.matches("(?s).*\\b(?:std::)?(?:basic_)?string\\b.*") ||
        lower.matches("(?s).*\\bqstring\\b.*") ||
        lower.matches("(?s).*:\\s*str\\b.*") || lower.matches("(?s).*\\bstr\\s*\\(.*") ||
        lower.matches("(?s).*\\bchar\\s*[*\\[].*") ||
        lower.matches("(?s).*=[\\s]*(?:r|u|b|f|rb|br)?(?:\"[^\"]*\"|'[^']*').*")
    then Some(TextSequence)
    else if lower.matches("(?s).*\\b(?:dict|hashmap|map|unordered_map|objectmap)\\b.*") ||
        lower.matches("(?s).*\\bstd::(?:map|unordered_map)\\b.*")
    then Some(AssociativeCollection)
    else if lower.matches("(?s).*\\b(?:hashset|set|unordered_set)\\b.*") ||
        lower.matches("(?s).*\\bstd::(?:set|unordered_set)\\b.*")
    then Some(SetCollection)
    else if lower.matches("(?s).*\\b(?:array|arraylist|collection|deque|iterable|list|queue|stack|tuple|vector)\\b.*") ||
        lower.matches("(?s).*\\bstd::(?:array|deque|list|queue|stack|vector)\\b.*") ||
        lower.matches("(?s).*\\bnew\\s+array\\s*\\(.*") ||
        lower.matches("(?s).*=[\\s]*(?:new\\s+array\\s*\\([^)]*\\)|\\[[^]]*\\]).*")
    then Some(SequentialCollection)
    else None
  }

  def collectionKindFor(d: VarDecl, writes: List[WriteInfo], events: List[CollectionEvent]): CollectionKind = {
    val structuralText = List(
      declarationTypeById.getOrElse(d.id, ""),
      d.pos.code,
      writes.headOption.map(_.code).getOrElse("")
    ).mkString("\n")
    val initializerReadsElement = writes.sortBy(write => (write.line, write.column, write.eventId)).headOption
      .flatMap(write => collectionCallById(write.eventId))
      .exists { call =>
        call.argument.l.drop(1) match
          case List(expression) => collectionElementPath(expression, cachedScopeOf(call)).exists(_.accesses.nonEmpty)
          case _ => false
      }
    val quotedName = java.util.regex.Pattern.quote(d.name)
    val sourceDeclaresArray = List(d.pos.code, writes.headOption.map(_.code).getOrElse(""))
      .exists(_.matches(s"(?s).*\\b$quotedName\\s*\\[[^]]*\\].*"))
    val textualKind = collectionKindFromText(structuralText)
    if initializerReadsElement && events.isEmpty then UnknownCollection
    else textualKind.filter(_ == TextSequence)
        .orElse(if sourceDeclaresArray then Some(SequentialCollection) else None)
        .orElse(textualKind)
        .orElse(events.iterator.flatMap(event => collectionKindFromText(event.parserOperator)).toSeq.headOption)
        .orElse(if events.nonEmpty then Some(SequentialCollection) else None)
        .getOrElse(UnknownCollection)
  }

  def knownCollectionInitializationText(d: VarDecl, writes: List[WriteInfo]): Boolean = {
    val declaredType = declarationTypeById.getOrElse(d.id, "")
    val source = List(d.pos.code, writes.headOption.map(_.code).getOrElse("")).mkString("\n")
    val lower = source.toLowerCase
    val explicitNonTextType = collectionKindFromText(declaredType).exists(kind =>
      kind != TextSequence && kind != UnknownCollection
    )
    val literalOrConstructor =
      lower.matches("(?s).*=[\\s]*\\[[^]]*\\].*") ||
      lower.matches("(?s).*=[\\s]*\\{[^}]*\\}.*") ||
      lower.matches("(?s).*=[\\s]*(?:new\\s+)?(?:array|arraylist|collection|deque|dict|hashmap|hashset|list|map|queue|set|stack|tuple|vector)\\b.*") ||
      lower.matches("(?s).*\\b(?:std::)?(?:array|deque|list|map|queue|set|stack|unordered_map|unordered_set|vector)\\s*[<{].*") ||
      source.matches(s"(?s).*\\b${java.util.regex.Pattern.quote(d.name)}\\s*\\[[^]]*\\]\\s*=.*")
    explicitNonTextType || literalOrConstructor
  }

  def eventPositionBefore(left: CollectionEvent, right: CollectionEvent): Boolean =
    left.method == right.method &&
      (left.line < right.line || (left.line == right.line &&
        (left.column < right.column || (left.column == right.column && left.eventId < right.eventId))))

  def sourcePositionBefore(
    leftLine: Int, leftColumn: Int, leftId: Long,
    rightLine: Int, rightColumn: Int, rightId: Long
  ): Boolean =
    leftLine < rightLine || (leftLine == rightLine &&
      (leftColumn < rightColumn || (leftColumn == rightColumn && leftId < rightId)))

  def comparableElementPath(path: CollectionElementPath): Boolean =
    path.accesses.nonEmpty && path.accesses.forall {
      case CollectionIndex(code, declarationId) =>
        code.nonEmpty && (declarationId.nonEmpty || code.matches("[-+]?(?:0[xX][0-9A-Fa-f]+|[0-9]+|['\"][^'\"]+['\"])"))
      case CollectionMember(memberDeclarationId, ownerFullName) =>
        memberDeclarationId.nonEmpty && ownerFullName.exists(_.nonEmpty)
    }

  def isExactElementNoOp(event: CollectionEvent): Boolean = {
    val targets = event.targetElementPaths.filter(comparableElementPath).distinct
    val sources = event.sourceElementPaths.filter(comparableElementPath).distinct
    event.effect == ReplaceElement && targets.size == 1 && sources == targets
  }

  def directCollectionTargetFor(d: VarDecl): CollectionTarget =
    memberOwnerById.get(d.id)
      .map(owner => MemberCollection(d.id, owner._2): CollectionTarget)
      .getOrElse(DirectCollection(d.id))

  def annotationBindingWrites(d: VarDecl, writes: List[WriteInfo]): List[WriteInfo] =
    if d.kind == "parameter" then writes.filterNot(_.declarationInitializer)
    else if writes.exists(_.declarationInitializer) then writes.filterNot(_.declarationInitializer)
    else writes.drop(1)

  def bindingCollectionEvents(d: VarDecl, writes: List[WriteInfo]): List[CollectionEvent] =
    annotationBindingWrites(d, writes).map { write =>
      CollectionEvent(
        write.eventId,
        directCollectionTargetFor(d),
        if write.selfRef || write.operator != "<operator>.assignment" then UpdateCollectionBinding
        else RebindCollection,
        initialization = false,
        Nil,
        Nil,
        sourceBacked = d.pos.path != "<unknown>" && write.line > 0,
        write.method,
        write.line,
        write.column,
        write.code,
        write.operator
      )
    }

  def unknownResolvedMutationEvents(d: VarDecl, known: List[CollectionEvent]): List[CollectionEvent] = {
    val knownPositions = known.map(event => (event.method, event.line, event.column)).toSet
    stateMutationsByDecl.getOrElse(d.id, Nil)
      .filterNot(mutation => knownPositions.contains((mutation.method, mutation.line, mutation.column)))
      .flatMap { mutation =>
        collectionCallsAt(d.pos.path, mutation.method, mutation.line, mutation.column).find(call =>
          call.receiver.l.nonEmpty && call.argument.l.filter(_.argumentIndex == 0).size == 1 &&
          call.argument.l.find(_.argumentIndex == 0)
            .flatMap(receiver => collectionTarget(receiver, mutation.method))
            .exists(_.declarationId == d.id))
          .map(call => CollectionEvent(
            call.id,
            directCollectionTargetFor(d),
            UnknownCollectionMutation,
            initialization = false,
            Nil,
            Nil,
            sourceBacked = nodePath(call) != "<unknown>" && mutation.line > 0,
            mutation.method,
            mutation.line,
            mutation.column,
            mutation.code,
            mutation.parserOperator,
            sourceOperationName = Some(mutation.mutationKind)
          ))
      }
  }

  def knownPermutationSpans(events: List[CollectionEvent]): List[PermutationSpan] =
    events.filter(_.effect == RearrangeElements).flatMap { event =>
      val sourcePaths = event.sourceElementPaths.filter(comparableElementPath).distinct
      val targetPaths = event.targetElementPaths.filter(comparableElementPath).distinct
      val kind =
        if sourcePaths.size == 2 && targetPaths.size == 2 && targetPaths == sourcePaths.reverse then
          if event.sourceOperationName.isEmpty && event.parserOperator.toLowerCase.contains("swap")
          then SwapPrimitivePermutation
          else TupleSwapPermutation
        else KnownMethodPermutation
      val structurallyValid = kind match
        case TupleSwapPermutation | SwapPrimitivePermutation =>
          sourcePaths.size == 2 && targetPaths.size == 2 && sourcePaths.map(_.target).distinct.size == 1 &&
            sourcePaths.forall(_.target == event.target) && targetPaths.forall(_.target == event.target)
        case KnownMethodPermutation => true
        case _ => false
      if event.sourceBacked && structurallyValid then
        Some(PermutationSpan(event.target, Set(event.eventId), kind, sourceBacked = true))
      else None
    }

  def argumentsDefinitionId(call: Call): Long =
    call.argument.l.headOption.collect { case identifier: Identifier => identifier.id }.getOrElse(call.id)

  val manualSwapLoadCandidates = profiler.timed("collection_manual_swap_load_index") {
    assignmentCalls.flatMap { call =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      val localTarget = arguments.headOption.collect { case identifier: Identifier => identifier }
        .flatMap(identifier => declarationId(identifier).filter(id => declById.get(id).exists(_.kind == "local")))
      val method = cachedScopeOf(call)
      val loadedPath = arguments.drop(1) match
        case List(expression) => collectionElementPath(expression, method).filter(comparableElementPath)
        case _ => None
      for
        temporaryDeclId <- localTarget.toList
        path <- loadedPath.toList if path.accesses.nonEmpty
      yield ManualSwapLoadCandidate(
        path.target.declarationId,
        temporaryDeclId,
        call.id,
        method,
        optInt(call.lineNumber, 0),
        optInt(call.columnNumber, 0),
        path
      )
    }
  }
  val manualSwapLoadsByCollectionMethod = manualSwapLoadCandidates
    .groupBy(candidate => (candidate.collectionDeclId, candidate.method))
  profiler.set("manual_swap_load_candidates", manualSwapLoadCandidates.size.toLong)
  profiler.set("manual_swap_cfg_queries", 0L)

  def tuplePermutationSpans(d: VarDecl, events: List[CollectionEvent]): List[PermutationSpan] =
    events.filter(event => event.effect == ReplaceElement && event.sourceBacked)
      .groupBy(event => (event.target, event.method, event.line))
      .values.flatMap { sameInstruction =>
        val writes = sameInstruction.sortBy(event => (event.column, event.eventId))
        val targets = writes.flatMap(_.targetElementPaths.filter(comparableElementPath)).distinct
        val sources = writes.flatMap(_.sourceElementPaths.filter(comparableElementPath)).distinct
        val directExactPair = writes.size == 2 && targets.size == 2 && sources.size == 2 &&
          targets.toSet == sources.toSet && targets.forall(_.target == writes.head.target) &&
          targets.head != targets.last
        val syntheticExactPair = if writes.size != 2 || targets.size != 2 || sources.size != 2 then false
        else {
          val temporaryIds = sources.map(_.target.declarationId).distinct
          temporaryIds match
            case List(tempId) if tempId != d.id =>
              profiler.increment("collection_position_lookups")
              val load = callsByMethodLine.getOrElse((writes.head.method, writes.head.line), Nil).find { call =>
                call.name == "<operator>.assignment" &&
                  call.argument.l.headOption.collect { case identifier: Identifier => declarationId(identifier) }
                    .flatten.contains(tempId)
              }
              load.exists { loadCall =>
                val loadedPaths = loadCall.argument.l.drop(1).flatMap(_.ast.isCall.l)
                  .flatMap(expression => collectionElementPath(expression, cachedScopeOf(loadCall)))
                  .filter(path => comparableElementPath(path) && path.target.declarationId == d.id)
                  .distinct
                val mappedSources = writes.flatMap { event =>
                  event.sourceElementPaths.filter(_.target.declarationId == tempId).flatMap { path =>
                    path.accesses.headOption.collect {
                      case CollectionIndex(code, _) if code.matches("[01]") => loadedPaths.lift(code.toInt)
                    }.flatten
                  }.headOption
                }
                val reaching = writes.forall { event =>
                  collectionCallById(event.eventId).exists { call =>
                    call.argument.l.drop(1).flatMap(_.ast.isIdentifier.l)
                      .filter(identifier => declarationId(identifier).contains(tempId))
                      .exists(identifier => reachingDefinitionClosure(identifier,
                        Set(argumentsDefinitionId(loadCall))).nonEmpty)
                  }
                }
                loadedPaths.size == 2 && mappedSources.size == 2 && mappedSources.toSet == targets.toSet &&
                  writes.zip(mappedSources).forall { case (event, source) =>
                    event.targetElementPaths.filter(comparableElementPath).distinct == List(source match
                      case path if path == loadedPaths.head => loadedPaths.last
                      case _ => loadedPaths.head
                    )
                  } && reaching
              }
            case _ => false
        }
        if directExactPair || syntheticExactPair then
          Some(PermutationSpan(writes.head.target, writes.map(_.eventId).toSet,
            TupleSwapPermutation, sourceBacked = true))
        else None
      }.toList

  def manualSwapPermutationSpans(
    d: VarDecl,
    events: List[CollectionEvent],
    loadCandidates: List[ManualSwapLoadCandidate]
  ): List[PermutationSpan] = {
    val elementWrites = events.filter(event =>
      Set(ReplaceElement, TransformElement).contains(event.effect) && event.target.declarationId == d.id
    ).sortBy(event => (event.method, event.line, event.column, event.eventId))
    loadCandidates.flatMap { loadCandidate =>
      val tempId = loadCandidate.temporaryDeclId
      val loadCall = collectionCallById(loadCandidate.callId)
      val originalPath = loadCandidate.sourcePath.copy(target = directCollectionTargetFor(d))
      val loadMethod = loadCandidate.method
      val loadLine = loadCandidate.line
      val candidates = elementWrites.filter(event => event.method == loadMethod && event.line >= loadLine)
      candidates.sliding(2).flatMap {
        case List(first, second) =>
          profiler.increment("manual_swap_candidate_pairs")
          val firstTarget = first.targetElementPaths.filter(comparableElementPath).distinct
          val firstSource = first.sourceElementPaths.filter(comparableElementPath).distinct
          val secondTarget = second.targetElementPaths.filter(comparableElementPath).distinct
          val secondCall = collectionCallById(second.eventId)
          val secondUsesTemp = loadCall.nonEmpty && secondCall.exists { call =>
            call.argument.l.drop(1) match
              case List(identifier: Identifier) =>
                profiler.increment("manual_swap_reaching_def_queries")
                declarationId(identifier).contains(tempId) &&
                  reachingDefinitionClosure(identifier, Set(argumentsDefinitionId(loadCall.get))).nonEmpty
              case _ => false
          }
          val noTempRewrite = assignmentWritesByDecl.getOrElse(tempId, Nil)
            .count(write => write.method == loadMethod && write.line >= loadLine && write.line <= second.line) == 1
          val strictlyOrdered =
            sourcePositionBefore(loadLine, loadCandidate.column, loadCandidate.callId,
              first.line, first.column, first.eventId) &&
            sourcePositionBefore(first.line, first.column, first.eventId,
              second.line, second.column, second.eventId)
          val sameImmediateBranch = {
            val branchRoots = List(loadCandidate.callId, first.eventId, second.eventId)
              .map(id => exclusiveBranchByEventId.get(id).map(_.branchId))
            branchRoots.distinct.size == 1
          }
          val incompatibleBetween = events.exists { event =>
            event.method == loadMethod && !Set(first.eventId, second.eventId).contains(event.eventId) &&
              sourcePositionBefore(loadLine, loadCandidate.column, loadCandidate.callId,
                event.line, event.column, event.eventId) &&
              sourcePositionBefore(event.line, event.column, event.eventId,
                second.line, second.column, second.eventId)
          }
          if firstTarget == List(originalPath) && firstSource.size == 1 && firstSource.head != originalPath &&
              secondTarget == firstSource && secondUsesTemp && noTempRewrite && strictlyOrdered &&
              sameImmediateBranch && !incompatibleBetween &&
              controlAt(loadMethod, loadLine) == controlAt(first.method, first.line) &&
              controlAt(first.method, first.line) == controlAt(second.method, second.line)
          then {
            profiler.increment("manual_swap_proved")
            Some(PermutationSpan(first.target, Set(first.eventId, second.eventId),
              DataFlowSwapPermutation, sourceBacked = first.sourceBacked && second.sourceBacked))
          }
          else None
        case _ => None
      }
    }.distinctBy(span => (span.target, span.eventIds, span.kind))
  }

  def collectionFlowFor(d: VarDecl, writes: List[WriteInfo], fixedFlow: FixedValueFlow): CollectionFlow = {
    val sameSourceBindings = sourceBindingsByPathName.getOrElse((d.pos.path, d.name), Nil)
    val structurallyRelatedIds =
      if fixedFlow.observedStateMutation && canonicalSourceBinding(sameSourceBindings).nonEmpty then
        sameSourceBindings.map(_.id).toSet + d.id
      else Set(d.id)
    val directTarget = directCollectionTargetFor(d)
    val semanticEvents = structurallyRelatedIds.toList.sorted.flatMap(id => collectionEventsByDecl.getOrElse(id, Nil))
      .map(event => if event.target == directTarget then event else event.copy(target = directTarget))
      .distinctBy(event => (event.eventId, event.effect, event.method, event.line, event.column))
    val kind = collectionKindFor(d, writes, semanticEvents)
    if kind == UnknownCollection && semanticEvents.isEmpty then {
      profiler.increment("collection_prefiltered")
      return CollectionFlow.Empty
    }
    if kind == TextSequence then {
      profiler.increment("collection_prefiltered")
      return CollectionFlow(
        analysisComplete = false,
        kind = TextSequence,
        verifiedInitialization = false,
        initialization = CollectionInitializationSpan(Set.empty, analysisComplete = false),
        postInitializationEvents = Nil,
        permutationSpans = Nil
      )
    }
    profiler.increment("collection_candidates")
    val knownInitialization = knownCollectionInitializationText(d, writes)
    val verifiedInitialization = d.kind == "parameter" ||
      (knownInitialization && (d.kind != "member" || fixedFlow.verifiedInitialization))
    val initializationComplete = d.kind == "parameter" ||
      (verifiedInitialization && fixedFlow.analysisComplete && !fixedFlow.observedExternalRebinding)
    val initializationIds =
      if verifiedInitialization then fixedFlow.initializationEventIds else Set.empty[Long]
    val allEvents = (semanticEvents ++ bindingCollectionEvents(d, writes) ++
      unknownResolvedMutationEvents(d, semanticEvents))
      .distinctBy(event => (event.eventId, event.target, event.effect))
      .filterNot(event => initializationIds.contains(event.eventId) || isExactElementNoOp(event))
      .sortBy(event => (event.method, event.line, event.column, event.eventId))
    val indexedElementWrites = structurallyRelatedIds.toList.sorted
      .flatMap(id => elementWritesByCollectionDecl.getOrElse(id, Nil))
    val candidateMethods = indexedElementWrites.iterator.map(_.method).toSet
    val indexedLoads = structurallyRelatedIds.toList.sorted.flatMap { id =>
      candidateMethods.toList.sorted.flatMap(method =>
        manualSwapLoadsByCollectionMethod.getOrElse((id, method), Nil)
      )
    }
    val analyzeManualSwap = kind != UnknownCollection && kind != TextSequence &&
      indexedElementWrites.size >= 2 && indexedLoads.nonEmpty
    val manualSpans =
      if analyzeManualSwap then {
        profiler.increment("manual_swap_candidate_collections")
        manualSwapPermutationSpans(d, allEvents, indexedLoads)
      } else {
        profiler.increment("collection_prefiltered")
        Nil
      }
    val permutationSpans = (knownPermutationSpans(allEvents) ++ tuplePermutationSpans(d, allEvents) ++ manualSpans)
      .filter(_.sourceBacked)
      .distinctBy(span => (span.target, span.eventIds, span.kind))
    CollectionFlow(
      analysisComplete = d.pos.path != "<unknown>" && d.pos.line > 0 &&
        kind != UnknownCollection && allEvents.forall(_.sourceBacked) &&
        (!fixedFlow.observedStateMutation || semanticEvents.nonEmpty),
      kind = kind,
      verifiedInitialization = verifiedInitialization,
      initialization = CollectionInitializationSpan(initializationIds, initializationComplete),
      postInitializationEvents = allEvents,
      permutationSpans = permutationSpans
    )
  }


  val result: CollectionFlows = CollectionFlows(collectionEventsByDecl)
}

def extractCollectionFlows(
  context: ExtractionContextIndex,
  bindings: BindingFactIndex,
  lifecycle: ValueLifecycleFlowIndex,
  followers: FollowerFlowIndex
): CollectionFlowIndex = new CollectionFlowIndex(context, bindings, lifecycle, followers)
