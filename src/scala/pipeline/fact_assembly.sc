import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class FactAssemblyIndex(
  val context: ExtractionContextIndex,
  val bindings: BindingFactIndex,
  val lifecycle: ValueLifecycleFlowIndex,
  val followers: FollowerFlowIndex,
  val collections: CollectionFlowIndex,
  val succession: SuccessionFlowIndex
) {
  import context.*
  import bindings.*
  import lifecycle.*
  import followers.*
  import collections.*
  import succession.*
  // Build all reusable variable facts once, then run concept predicates over them.
  val facts = declarations.flatMap { d => profiler.timedAccumulating("facts_build") {
    occurrencesByDecl.get(d.id).map { occs =>
      val writes = writesByDecl.getOrElse(d.id, Nil)
      val mutations = stateMutationsByDecl.getOrElse(d.id, Nil)
      val fixedFlow = localFixedValueFlowByDecl(d.id)
      val bindingPartition = profiler.timedAccumulating("binding_partition") {
        bindingPartitionFor(d, writes, fixedFlow)
      }
      val stepperFlow = profiler.timedAccumulating("stepper_flow") { stepperFlowFor(d, bindingPartition, fixedFlow) }
      val walkerFlow = profiler.timedAccumulating("walker_flow") { walkerFlowFor(d, bindingPartition) }
      val gathererAdmissibility = gathererAdmissibilityFor(d, bindingPartition, walkerFlow)
      val (oneWayFlagPartition, oneWayFlagOwnerComplete) = oneWayFlagPartitionFor(d, bindingPartition)
      val oneWayFlagFlow = profiler.timedAccumulating("one_way_flag_flow") {
        oneWayFlagFlowFor(d, oneWayFlagPartition, oneWayFlagOwnerComplete,
          observedMutation = mutations.nonEmpty)
      }
      val gathererFlow = if gathererAdmissibility.admitted then profiler.timedAccumulating("gatherer_flow") {
        gathererFlowFor(d, bindingPartition)
      } else emptyGathererFlow
      val followerWrites = (fixedFlow.bindingWrites ++ rubyFollowerRecoveredWritesByDecl.getOrElse(d.id, Nil))
        .distinctBy(_.eventId)
      val implicitPosition = annotationImplicitIteratorDeclById.get(d.id)
      val firstWriteLine = writes.headOption.map(_.line).getOrElse(d.pos.line)
      val initializedBeforeLoop = loopRangesByMethod.getOrElse(d.method, Nil).exists { case (_, s, e, _, code) =>
        firstWriteLine > 0 && firstWriteLine < s && (containsName(code, d.name) || writes.exists(w => w.insideLoop && w.loopKey.exists(_.contains(s":$s:$e"))))
      }
      VarFacts(
        decl = d,
        occurrences = occs,
        writes = writes,
        stateMutations = mutations,
        resolvedStateMutations = resolvedStateMutationsByDecl.getOrElse(d.id, Nil),
        controlContexts = profiler.timed("control_contexts") { controlContextsFor(d, occs, writes, mutations, implicitPosition) },
        scopeContext = sourceScopeContextByDecl.getOrElse(d.id, None),
        fixedValueFlow = fixedFlow,
        temporaryFlow = profiler.timed("temporary_flow") { temporaryFlowFor(d, writes) },
        oneWayFlagFlow = oneWayFlagFlow,
        gathererAdmissibility = gathererAdmissibility,
        gathererFlow = gathererFlow,
        followerFlow = profiler.timedAccumulating("follower_flow") {
          followerFlowFor(d, followerWrites, fixedFlow, mutations.nonEmpty)
        },
        collectionFlow = profiler.timed("collection_flow") { collectionFlowFor(d, writes, fixedFlow) },
        stepperFlow = stepperFlow,
        walkerFlow = walkerFlow,
        mostWantedFlow = profiler.timedAccumulating("most_wanted_flow") {
          mostWantedFlowFor(d, bindingPartition, writes, mutations.nonEmpty)
        },
        mostRecentFlow = profiler.timedAccumulating("most_recent_flow") {
          mostRecentFlowFor(d, bindingPartition, ownerUnambiguous = false,
            hasIndependentStateMutation = mutations.nonEmpty,
            strongImplicitWalker = walkerFlow.implicitElementWalker && !walkerFlow.implicitNumericIterator)
        },
        usedInLoopCondition = loopConditionsByMethod.getOrElse(d.method, Nil).exists(code => containsName(code, d.name)),
        implicitIterator = annotationImplicitIteratorIds.contains(d.id),
        implicitIteratorPosition = implicitPosition,
        initializedBeforeLoop = initializedBeforeLoop,
        readCount = occs.count(!_.isDeclaration),
        isCollection = hasCollectionSeed(d, writes) || collectionOperationIds.contains(d.id),
        hasCollectionLengthMutation = lengthMutationIds.contains(d.id),
        hasSumPreviousValueMutation = sumPreviousValueMutationIds.contains(d.id),
        hasInPlaceMutation = inPlaceMutationIds.contains(d.id),
        hasIndexedValueMutation = indexedValueMutationIds.contains(d.id)
      )
    }
  } }
  val fieldFacts = memberDeclarations.flatMap { d => profiler.timedAccumulating("facts_build") {
    fieldOccurrencesByDecl.get(d.id).map { occs =>
      val followerWrites = fixedMemberWritesByDecl.getOrElse(d.id, Nil)
      val fixedFlow = memberFixedValueFlowByDecl(d.id)
      val writes = fixedMemberWritesByDecl.getOrElse(d.id, fieldWritesByDecl.getOrElse(d.id, Nil))
      val bindingPartition = profiler.timedAccumulating("binding_partition") {
        bindingPartitionFor(d, writes, fixedFlow)
      }
      val stepperFlow = profiler.timedAccumulating("stepper_flow") { stepperFlowFor(d, bindingPartition, fixedFlow) }
      val walkerFlow = profiler.timedAccumulating("walker_flow") { walkerFlowFor(d, bindingPartition) }
      val gathererAdmissibility = gathererAdmissibilityFor(d, bindingPartition, walkerFlow)
      val memberMutations = (resolvedStateMutationsByDecl.getOrElse(d.id, Nil) ++
        stateMutationsByDecl.getOrElse(d.id, Nil))
        .distinctBy(mutation => (mutation.method, mutation.line, mutation.column, mutation.mutationKind))
        .sortBy(mutation => (mutation.line, mutation.column))
      val (oneWayFlagPartition, oneWayFlagOwnerComplete) = oneWayFlagPartitionFor(d, bindingPartition)
      val oneWayFlagFlow = profiler.timedAccumulating("one_way_flag_flow") {
        oneWayFlagFlowFor(d, oneWayFlagPartition, oneWayFlagOwnerComplete, memberMutations.nonEmpty)
      }
      val gathererFlow = if gathererAdmissibility.admitted then profiler.timedAccumulating("gatherer_flow") {
        gathererFlowFor(d, bindingPartition)
      } else emptyGathererFlow
      VarFacts(
        decl = d,
        occurrences = occs,
        writes = writes,
        stateMutations = Nil,
        resolvedStateMutations = memberMutations,
        controlContexts = Nil,
        scopeContext = profiler.timed("scope_resolution") { sourceScopeContextFor(d) },
        fixedValueFlow = fixedFlow,
        temporaryFlow = TemporaryFlow(
          Nil,
          Map.empty,
          analysisComplete = false,
          loopSafe = false
        ),
        oneWayFlagFlow = oneWayFlagFlow,
        gathererAdmissibility = gathererAdmissibility,
        gathererFlow = gathererFlow,
        followerFlow = profiler.timedAccumulating("follower_flow") {
          followerFlowFor(d, followerWrites, fixedFlow, fixedMutatedMemberIds.contains(d.id))
        },
        collectionFlow = profiler.timed("collection_flow") {
          collectionFlowFor(d, writes, fixedFlow)
        },
        stepperFlow = stepperFlow,
        walkerFlow = walkerFlow,
        mostWantedFlow = profiler.timedAccumulating("most_wanted_flow") {
          mostWantedFlowFor(d, bindingPartition, writes, memberMutations.nonEmpty)
        },
        mostRecentFlow = profiler.timedAccumulating("most_recent_flow") {
          mostRecentFlowFor(d, bindingPartition, ownerUnambiguous = memberOwnerById.contains(d.id),
            hasIndependentStateMutation = memberMutations.nonEmpty,
            strongImplicitWalker = walkerFlow.implicitElementWalker && !walkerFlow.implicitNumericIterator)
        },
        usedInLoopCondition = false,
        implicitIterator = false,
        implicitIteratorPosition = None,
        initializedBeforeLoop = false,
        readCount = occs.count(!_.isDeclaration),
        isCollection = false,
        hasCollectionLengthMutation = false,
        hasSumPreviousValueMutation = false,
        hasInPlaceMutation = false,
        hasIndexedValueMutation = false
      )
    }
  } }

  val bindingFacts = BindingFacts(
    declarations = declarations,
    memberDeclarations = memberDeclarations,
    occurrencesByDeclaration = occurrencesByDecl,
    memberOccurrencesByDeclaration = fieldOccurrencesByDecl,
    writesByDeclaration = writesByDecl,
    memberWritesByDeclaration = fixedMemberWritesByDecl,
    stateMutationsByDeclaration = stateMutationsByDecl
  )
  val lifecycleFlows = ValueLifecycleFlows(
    fixedValueByDeclaration = localFixedValueFlowByDecl,
    fixedValueByMember = memberFixedValueFlowByDecl,
    temporaryAdmissibleIds = temporaryAdmissibleIds
  )
  val collectionFlows = CollectionFlows(collectionEventsByDecl)
  val followerFlows = FollowerFlows(followerReadsByDecl)
  val successionFlows = SuccessionFlows(annotationImplicitIteratorIds)
  val extractedFacts = ExtractedFacts(facts, fieldFacts, loopRanges, controlRanges)

  if sys.env.get("SAJANIEMI_DEBUG_FOLLOWER").contains("1") then
    (facts ++ fieldFacts).filter(_.followerFlow.reassignments.nonEmpty).foreach { fact =>
      val flow = fact.followerFlow
      System.err.println(
        s"SAJANIEMI_FOLLOWER_DEBUG subject=${fact.decl.id}:${fact.decl.name} kind=${fact.decl.kind} " +
          s"owner=${flow.ownerFullName} writes=${flow.reassignments.map(_.write.eventId)} " +
          s"sources=${flow.reassignments.map(_.rhsCandidates)} reads=${flow.reads.map(_.nodeId)} " +
          s"updates=${flow.masterUpdates} atomic=${flow.atomicTransitions} contexts=${flow.contexts} cycles=${flow.cycles} " +
          s"guards=${flow.guardedWriteIds} complete=${flow.analysisComplete}"
      )
    }

  if sys.env.get("SAJANIEMI_DEBUG_MWH").contains("1") then
    (facts ++ fieldFacts).filter(_.mostWantedFlow.replacements.nonEmpty).foreach { fact =>
      val flow = fact.mostWantedFlow
      System.err.println(
        s"SAJANIEMI_MWH_DEBUG subject=${fact.decl.id}:${fact.decl.name} kind=${fact.decl.kind} " +
          s"writes=${fact.writes.map(write => (write.eventId, write.line, write.loopKey, write.rhs))} " +
          s"epochs=${flow.epochs} replacements=${flow.replacements.map(replacement => (replacement.write.eventId, replacement.direction, replacement.contextKey))} " +
          s"explained=${flow.explainedEventIds} incompatible=${flow.incompatibleWriteIds} complete=${flow.analysisComplete}"
      )
    }

  if sys.env.get("SAJANIEMI_DEBUG_COLLECTION").contains("1") then
    (facts ++ fieldFacts).filter(fact => fact.collectionFlow.postInitializationEvents.nonEmpty).foreach { fact =>
      val flow = fact.collectionFlow
      System.err.println(
        s"SAJANIEMI_COLLECTION_DEBUG subject=${fact.decl.id}:${fact.decl.name} kind=${fact.decl.kind} " +
          s"collectionKind=${flow.kind} initialized=${flow.verifiedInitialization} " +
          s"complete=${flow.analysisComplete} initializationIds=${flow.initialization.eventIds} " +
          s"events=${flow.postInitializationEvents.map(event => (event.eventId, event.effect, event.code, event.sourceElementPaths, event.targetElementPaths))} " +
          s"permutations=${flow.permutationSpans.map(span => (span.kind, span.eventIds))}"
      )
    }

  if sys.env.get("SAJANIEMI_DEBUG_DYNAMIC").contains("1") then
    val debugNames = sys.env.get("SAJANIEMI_DEBUG_DYNAMIC_NAMES").toList
      .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)).toSet
    (facts ++ fieldFacts).filter(fact =>
      (debugNames.isEmpty || debugNames.contains(fact.decl.name)) && (
        fact.stepperFlow.updates.nonEmpty || fact.walkerFlow.transitions.nonEmpty ||
          fact.mostRecentFlow.partition.postInitializationWrites.nonEmpty ||
          fact.walkerFlow.implicitNumericIterator || fact.walkerFlow.implicitElementWalker ||
          fact.decl.name == "numeric_index"
      )
    ).foreach { fact =>
      System.err.println(
        s"SAJANIEMI_DYNAMIC_DEBUG subject=${fact.decl.id}:${fact.decl.name}:${fact.decl.kind} " +
          s"partition=${fact.stepperFlow.partition} stepper=${fact.stepperFlow.updates} " +
          s"walker=${fact.walkerFlow} mostRecent=${fact.mostRecentFlow}"
      )
    }

  profiler.set("declarations", declarations.size.toLong)
  profiler.set("members", memberDeclarations.size.toLong)
  profiler.set("occurrences", occurrencesByDecl.valuesIterator.map(_.size.toLong).sum)
  profiler.set("reads", normalizedReadIdentifiers.size.toLong)
  profiler.set("writes", allWrites.size.toLong)
  profiler.set("state_mutations", stateMutationsByDecl.valuesIterator.map(_.size.toLong).sum)
  profiler.set("cfg_nodes", cfgNodes.size.toLong)
  profiler.set("loops", loopCfgInfos.size.toLong)
  profiler.set("rhs_analyzed", rhsAnalysisCache.valuesIterator.count(_.nonEmpty).toLong)
  profiler.set("mrh_methods_with_candidates", mrhCandidateMethods.size.toLong)

  val result: ExtractedFacts = extractedFacts
}

def assembleVariableFacts(
  context: ExtractionContextIndex,
  bindings: BindingFactIndex,
  lifecycle: ValueLifecycleFlowIndex,
  followers: FollowerFlowIndex,
  collections: CollectionFlowIndex,
  succession: SuccessionFlowIndex
): FactAssemblyIndex =
  new FactAssemblyIndex(context, bindings, lifecycle, followers, collections, succession)
