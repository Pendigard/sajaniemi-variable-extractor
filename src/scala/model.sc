import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Call, ControlStructure, FieldIdentifier, Identifier, Method}

// Compact location attached to a variable token before Python resolves exact spans.
case class Position(path: String, line: Int, column: Int, lineEnd: Int, columnEnd: Int, code: String)

// Variable declarations are limited to locals and parameters; identifiers such as function names are excluded.
case class VarDecl(id: Long, name: String, kind: String, method: String, pos: Position)

// Every concrete token tied back to a declaration, including the declaration token itself.
case class Occurrence(declId: Long, name: String, method: String, pos: Position, isDeclaration: Boolean)

// Compact, declaration-backed RHS representation shared by the dynamic roles.
// It is deliberately incomplete: an unknown node makes the corresponding proof
// conservative instead of falling back to expression text.
sealed trait RhsExpr
case class RhsLiteral(code: String) extends RhsExpr
case class RhsStandardConstant(code: String) extends RhsExpr
case class RhsDeclarationRef(declarationId: Long) extends RhsExpr
case class RhsSelfRef(declarationId: Long) extends RhsExpr
case class RhsExternalReference(name: String) extends RhsExpr
case class RhsUnary(operator: String, operand: RhsExpr) extends RhsExpr
case class RhsBinary(operator: String, left: RhsExpr, right: RhsExpr) extends RhsExpr
case class RhsCall(name: String, arguments: List[RhsExpr]) extends RhsExpr
case class RhsMemberAccess(
  receiver: RhsExpr,
  memberDeclarationId: Option[Long],
  memberName: String,
  indirect: Boolean
) extends RhsExpr
case class RhsIndexAccess(receiver: RhsExpr, index: RhsExpr) extends RhsExpr
case class RhsDereference(operand: RhsExpr) extends RhsExpr
case class RhsUnknown(nodeKind: String) extends RhsExpr

case class RhsAnalysis(
  expression: RhsExpr,
  declarationDependencies: Set[Long],
  sourceBacked: Boolean,
  complete: Boolean
)

// Normalized write event used by the concept predicates.
case class WriteInfo(declId: Long, name: String, method: String, line: Int, column: Int, code: String, operator: String,
                     selfRef: Boolean, predictable: Boolean, nonPredictable: Boolean,
                     insideLoop: Boolean, insideControl: Boolean, literalValue: Option[String],
                     sourceNames: Set[String], loopKey: Option[String], directWrite: Boolean,
                     declarationInitializer: Boolean, rhsHasMethodCall: Boolean,
                     directSourceDeclId: Option[Long], eventId: Long,
                     cfgNodeId: Option[Long], definitionNodeId: Option[Long],
                     rhs: Option[RhsAnalysis] = None)

case class BindingPartition(
  initializationEventIds: Set[Long],
  postInitializationWrites: List[WriteInfo],
  analysisComplete: Boolean
)

case class GuardInfo(
  controlId: Long,
  expressions: List[RhsAnalysis],
  declarationDependencies: Set[Long],
  sourceBacked: Boolean,
  complete: Boolean
)

case class StepperUpdate(
  write: WriteInfo,
  rhs: RhsAnalysis,
  guards: List[GuardInfo],
  dependencyIds: Set[Long]
)
case class StepperProgression(
  contextKey: String,
  updateEventIds: List[Long],
  repeatedContext: Boolean,
  implicitIteration: Boolean,
  correctionLike: Boolean,
  analysisComplete: Boolean
)
// Internal event context; never changes WriteInfo or emitted control views.
case class StepperEventContext(loopKey: Option[String], analysisComplete: Boolean, enclosingLoopKeys: Set[String] = Set.empty)
case class PersistentStepperContext(ownerIdentity: String, methods: Set[String])
case class StepperFlow(
  partition: BindingPartition,
  updates: List[StepperUpdate],
  progressions: List[StepperProgression],
  analysisComplete: Boolean,
  persistentContext: Option[PersistentStepperContext] = None
)

sealed trait NavigationSource
case class ImplicitElementSource(collectionDeclarationId: Option[Long]) extends NavigationSource
case class MemberNavigationSource(memberDeclarationId: Option[Long]) extends NavigationSource
case class IndexedNavigationSource(collectionDeclarationId: Long) extends NavigationSource
case class IteratorProgressionSource(
  iteratorDeclarationId: Long,
  collectionDeclarationId: Option[Long],
  direction: String
) extends NavigationSource
case class NavigationMethodSource(receiverDeclarationId: Long, methodIdentity: String) extends NavigationSource

case class WalkerTransition(
  write: WriteInfo,
  source: NavigationSource,
  loopKey: String,
  usesPreviousPosition: Boolean,
  usedForAccessOrControl: Boolean
)
case class WalkerFlow(
  implicitElementWalker: Boolean,
  implicitNumericIterator: Boolean,
  transitions: List[WalkerTransition],
  analysisComplete: Boolean
)

sealed trait AcquisitionKind
case object CallAcquisition extends AcquisitionKind
case object CollectionAcquisition extends AcquisitionKind
case object CurrentElementAcquisition extends AcquisitionKind
case object ParameterToMemberAcquisition extends AcquisitionKind
case object ExternalCallToMemberAcquisition extends AcquisitionKind
case object CollectionElementToMemberAcquisition extends AcquisitionKind
case object CurrentWalkerElementToMemberAcquisition extends AcquisitionKind

case class AcquisitionEvent(
  write: WriteInfo,
  kind: AcquisitionKind,
  contextKey: String,
  projectionRootIds: Set[Long],
  guards: List[GuardInfo]
)
case class CorrectionSpan(
  acquisitionEventId: Long,
  correctionEventIds: List[Long],
  firstSemanticReadId: Option[Long] = None,
  contextKey: Option[String] = None,
  analysisComplete: Boolean = true
)
case class MostRecentTraversalSummary(
  acquisitionEventId: Long,
  nearestCorrectionEventIds: List[Long],
  reachableSemanticRead: Boolean,
  blockingDefinitionEventIds: Set[Long],
  ambiguous: Boolean,
  visitedNodeCount: Int
)
case class MostRecentFlow(
  partition: BindingPartition,
  acquisitions: List[AcquisitionEvent],
  corrections: List[CorrectionSpan],
  explainedEventIds: Set[Long],
  allAcquisitionsUsed: Boolean,
  persistentMember: Boolean,
  analysisComplete: Boolean
)

// Complete best-so-far selection lifecycle used only by the Most-wanted holder
// predicate. These summaries are deliberately internal and are never exported.
sealed trait MostWantedSeedKind
case object LiteralSentinel extends MostWantedSeedKind
case object NullSentinel extends MostWantedSeedKind
case object ExtremeSentinel extends MostWantedSeedKind
case object FixedSentinel extends MostWantedSeedKind
case object StableExternalSentinel extends MostWantedSeedKind
case object FirstCandidateSeed extends MostWantedSeedKind
case object ImplicitParameterSeed extends MostWantedSeedKind

case class MostWantedSeed(
  write: Option[WriteInfo],
  kind: MostWantedSeedKind,
  value: Option[RhsAnalysis],
  contextKey: Option[String],
  directionHint: Option[SelectionDirection],
  sourceBacked: Boolean
)

sealed trait SelectionDirection
case object SelectMinimum extends SelectionDirection
case object SelectMaximum extends SelectionDirection
case object SelectByPredicate extends SelectionDirection
case object SelectByTerminalPredicate extends SelectionDirection

sealed trait MostWantedReplacementKind
case object GuardedCandidateReplacement extends MostWantedReplacementKind
case object MinMaxSelection extends MostWantedReplacementKind
case object FirstSatisfyingCandidateReplacement extends MostWantedReplacementKind

case class MostWantedFallback(
  write: WriteInfo,
  guard: SelectionGuardAnalysis,
  sourceBacked: Boolean
)

case class ComparatorSummary(
  methodFullName: String,
  leftParameterId: Long,
  rightParameterId: Long,
  direction: SelectionDirection,
  analysisComplete: Boolean
)

sealed trait CandidateRelationKind
case object DirectCandidateValue extends CandidateRelationKind
case object EquivalentCandidateExpression extends CandidateRelationKind
case object CandidateSelectedByProjection extends CandidateRelationKind
case object SymmetricScoreCandidate extends CandidateRelationKind

case class CandidateFingerprint(
  operatorOrResolvedCall: String,
  declarationDependencies: List[Long],
  normalizedChildren: List[CandidateFingerprint],
  literalValue: Option[String],
  projectionPath: List[String],
  complete: Boolean,
  pure: Boolean
)

case class CandidateRelation(
  assignedCandidate: RhsAnalysis,
  comparedCandidate: RhsAnalysis,
  holderProjection: RhsAnalysis,
  kind: CandidateRelationKind
)

case class SelectionScore(
  expression: CandidateFingerprint,
  selectedRootId: Long,
  varyingRootId: Long,
  stableDependencies: Set[Long],
  complete: Boolean,
  pure: Boolean
)

case class ScoreComparison(
  candidateScore: SelectionScore,
  holderScore: SelectionScore,
  direction: SelectionDirection,
  analysisComplete: Boolean
)

case class SelectionGuardAnalysis(
  relation: Option[CandidateRelation],
  direction: Option[SelectionDirection],
  bootstrapCondition: Option[RhsAnalysis],
  eligibilityConditions: List[RhsAnalysis],
  incompatibleConditions: List[RhsAnalysis],
  analysisComplete: Boolean
)

case class MostWantedReplacement(
  write: WriteInfo,
  candidate: RhsAnalysis,
  kind: MostWantedReplacementKind,
  direction: SelectionDirection,
  guards: List[GuardInfo],
  contextKey: String,
  sourceBacked: Boolean
)

case class MostWantedSelectionEpoch(
  seed: MostWantedSeed,
  selectionContextKey: String,
  replacements: List[MostWantedReplacement],
  explainedEventIds: Set[Long],
  analysisComplete: Boolean
)

case class MostWantedCompanion(
  declarationId: Long,
  primaryHolderId: Long,
  replacementEventIds: List[Long],
  primaryReplacementEventIds: List[Long],
  candidateDependencyIds: Set[Long],
  contextKey: String,
  analysisComplete: Boolean
)

case class MostWantedFlow(
  epochs: List[MostWantedSelectionEpoch],
  replacements: List[MostWantedReplacement],
  explainedEventIds: Set[Long],
  incompatibleWriteIds: Set[Long],
  analysisComplete: Boolean,
  writeGuardControlIds: Map[Long, Set[Long]] = Map.empty
)

// Source read tied to its concrete identifier/CFG node. This remains internal.
case class ReadEvent(
  declId: Long,
  nodeId: Long,
  method: String,
  line: Int,
  column: Int,
  loopKeys: Set[String],
  reachingDefinitionNodeIds: Set[Long]
)

// One may-reaching definition/read relation plus its shortest redefinition-free CFG distance.
case class DefinitionRead(read: ReadEvent, instructionDistance: Int)

// Conservative flow summary used only by the Temporary predicate.
case class TemporaryFlow(
  reads: List[ReadEvent],
  associatedReadsByWrite: Map[Long, List[DefinitionRead]],
  analysisComplete: Boolean,
  loopSafe: Boolean
)

// Canonical boolean lifecycle used only by the One-way flag predicate.
sealed trait BooleanTransition { def eventId: Long }
case class AssignTerminal(value: Boolean, eventId: Long) extends BooleanTransition
case class MonotoneOr(expression: RhsAnalysis, eventId: Long) extends BooleanTransition
case class MonotoneAnd(expression: RhsAnalysis, eventId: Long) extends BooleanTransition
case class OneWayFlagFlow(
  initializationValue: Option[Boolean],
  initializationEventIds: Set[Long],
  transitions: List[BooleanTransition],
  identityDeclarationIds: Set[Long],
  ownerIdentityComplete: Boolean,
  analysisComplete: Boolean
)

// Source-backed accumulation phases used only by the Gatherer predicate.
case class InitializationSeed(
  write: Option[WriteInfo],
  seed: Option[RhsAnalysis],
  implicitParameter: Boolean,
  dynamicFirstContribution: Boolean
)
case class GathererReset(write: WriteInfo, seed: RhsAnalysis)
case class DataAccumulationUpdate(write: WriteInfo, contribution: RhsAnalysis)
case class StableAccumulatorTransform(write: WriteInfo, operand: RhsAnalysis)
case class GathererFinalization(write: WriteInfo, operand: RhsAnalysis)
case class AccumulationSpan(
  contextKey: String,
  resetEventId: Option[Long],
  dataUpdateEventIds: List[Long],
  transformEventIds: List[Long]
)
case class GathererFlow(
  initialization: Option[InitializationSeed],
  resets: List[GathererReset],
  dataUpdates: List[DataAccumulationUpdate],
  stableTransforms: List[StableAccumulatorTransform],
  finalizations: List[GathererFinalization],
  spans: List[AccumulationSpan],
  incompatibleWriteIds: Set[Long],
  analysisComplete: Boolean
)

// Cheap, declaration-backed target qualification performed before GathererFlow.
case class GathererAdmissibility(
  admitted: Boolean,
  implicitTraversalElement: Boolean,
  pointerOrAddress: Boolean,
  usedForAddressing: Boolean,
  textualGrowth: Boolean
)

// Conservative initialization proof used only by the Fixed Value predicate.
// Event ids identify exactly which binding writes form the initialization phase.
case class FixedValueFlow(
  bindingWrites: List[WriteInfo],
  initializationEventIds: Set[Long],
  verifiedInitialization: Boolean,
  atMostOneInitializationPerPath: Boolean,
  analysisComplete: Boolean,
  observedExternalRebinding: Boolean,
  observedStateMutation: Boolean,
  observedLoopEvent: Boolean
)

// Canonical, declaration-backed sources accepted by the Follower predicate.
// These summaries are internal only and are never serialized.
sealed trait MasterRoot
case class DeclarationRoot(declarationId: Long) extends MasterRoot
case class ImplicitOwnerRoot(ownerFullName: String) extends MasterRoot

sealed trait AllowedIndex
case class LiteralIndex(code: String) extends AllowedIndex
case class FixedVariableIndex(declarationId: Long) extends AllowedIndex

sealed trait MasterAccess
case class MemberAccess(
  memberDeclarationId: Long,
  ownerFullName: String
) extends MasterAccess
case class IndexAccess(index: AllowedIndex) extends MasterAccess

case class MasterSource(root: MasterRoot, path: List[MasterAccess])

sealed trait FixedOffset
case class LiteralOffset(code: String) extends FixedOffset
case class VariableOffset(declarationId: Long) extends FixedOffset

case class FollowerRhs(masterSource: MasterSource, fixedOffset: Option[FixedOffset])
case class ParallelAssignmentComponent(
  position: Int,
  targetDeclarationId: Long,
  targetWriteEventId: Long,
  logicalRhsCandidates: List[FollowerRhs],
  logicalRhsDeclarationIds: Set[Long]
)
case class ParallelAssignmentGroup(
  groupId: Long,
  method: String,
  path: String,
  line: Int,
  statementCode: String,
  temporaryDeclarationId: Long,
  loadEventId: Long,
  completionCfgNodeId: Long,
  components: List[ParallelAssignmentComponent],
  sourceBacked: Boolean,
  simultaneous: Boolean
)
case class AtomicFollowerTransition(
  groupId: Long,
  followerWriteEventId: Long,
  masterUpdateEventId: Long,
  followerDeclarationId: Long,
  masterSource: MasterSource,
  completionCfgNodeId: Long
)
case class FollowerWrite(write: WriteInfo, rhsCandidates: List[FollowerRhs])
case class FollowerRead(
  nodeId: Long,
  method: String,
  nearestLoopKeys: Set[String],
  line: Int,
  column: Int
)
case class MasterUpdate(
  nodeId: Long,
  cfgNodeId: Option[Long],
  method: String,
  nearestLoopKey: Option[String],
  affectedSource: MasterSource,
  updateKind: String,
  directlyDependsOnFollower: Boolean,
  transitivelyDependsOnFollower: Boolean
)
case class FollowerContext(
  key: String,
  contextKind: String,
  method: String,
  nearestLoopKey: Option[String]
)
case class FollowerCycle(
  contextKey: String,
  masterSource: MasterSource,
  updateSpanWriteIds: Set[Long],
  masterUpdateId: Long,
  postMasterUpdateReadId: Long
)
case class FollowerFlow(
  initializationEventIds: Set[Long],
  bindingWrites: List[WriteInfo],
  reassignments: List[FollowerWrite],
  reads: List[FollowerRead],
  masterUpdates: List[MasterUpdate],
  contexts: List[FollowerContext],
  cycles: List[FollowerCycle],
  atomicTransitions: List[AtomicFollowerTransition],
  guardedWriteIds: Set[Long],
  ownerFullName: Option[String],
  ownerUnambiguous: Boolean,
  observedStateMutation: Boolean,
  analysisComplete: Boolean
)

// Internal collection summaries. They are role-detection details and are never
// serialized as annotation facts or evidence.
sealed trait CollectionTarget { def declarationId: Long }
case class DirectCollection(declarationId: Long) extends CollectionTarget
case class MemberCollection(declarationId: Long, ownerFullName: String) extends CollectionTarget
case class DereferencedCollection(declarationId: Long) extends CollectionTarget

sealed trait CollectionKind
case object SequentialCollection extends CollectionKind
case object AssociativeCollection extends CollectionKind
case object SetCollection extends CollectionKind
case object TextSequence extends CollectionKind
case object UnknownCollection extends CollectionKind

sealed trait CollectionEffect
case object AddElement extends CollectionEffect
case object RemoveElement extends CollectionEffect
case object ClearElements extends CollectionEffect
case object RearrangeElements extends CollectionEffect
case object ReplaceElement extends CollectionEffect
case object TransformElement extends CollectionEffect
case object RebindCollection extends CollectionEffect
case object UpdateCollectionBinding extends CollectionEffect
case object UnknownCollectionMutation extends CollectionEffect

sealed trait CollectionElementAccess
case class CollectionIndex(code: String, declarationId: Option[Long]) extends CollectionElementAccess
case class CollectionMember(memberDeclarationId: Option[Long], ownerFullName: Option[String]) extends CollectionElementAccess

case class CollectionElementPath(target: CollectionTarget, accesses: List[CollectionElementAccess])

case class CollectionEvent(
  eventId: Long,
  target: CollectionTarget,
  effect: CollectionEffect,
  initialization: Boolean,
  sourceElementPaths: List[CollectionElementPath],
  targetElementPaths: List[CollectionElementPath],
  sourceBacked: Boolean,
  method: String,
  line: Int,
  column: Int,
  code: String,
  parserOperator: String,
  sourceOperationName: Option[String] = None
)

case class CollectionInitializationSpan(eventIds: Set[Long], analysisComplete: Boolean)

sealed trait PermutationKind
case object TupleSwapPermutation extends PermutationKind
case object DataFlowSwapPermutation extends PermutationKind
case object SwapPrimitivePermutation extends PermutationKind
case object KnownMethodPermutation extends PermutationKind

case class PermutationSpan(
  target: CollectionTarget,
  eventIds: Set[Long],
  kind: PermutationKind,
  sourceBacked: Boolean
)

// Pre-indexed once per extraction. Manual-swap analysis consumes only candidates
// already tied to one collection declaration and callable.
case class ManualSwapLoadCandidate(
  collectionDeclId: Long,
  temporaryDeclId: Long,
  callId: Long,
  method: String,
  line: Int,
  column: Int,
  sourcePath: CollectionElementPath
)

sealed trait CollectionRoleDecision
case object CollectionContainer extends CollectionRoleDecision
case object CollectionOrganizer extends CollectionRoleDecision
case object CollectionFixedValue extends CollectionRoleDecision
case object CollectionUnclassified extends CollectionRoleDecision

case class CollectionFlow(
  analysisComplete: Boolean,
  kind: CollectionKind,
  verifiedInitialization: Boolean,
  initialization: CollectionInitializationSpan,
  postInitializationEvents: List[CollectionEvent],
  permutationSpans: List[PermutationSpan]
)

object CollectionFlow {
  val Empty: CollectionFlow = CollectionFlow(
    analysisComplete = false,
    kind = UnknownCollection,
    verifiedInitialization = false,
    initialization = CollectionInitializationSpan(Set.empty, analysisComplete = false),
    postInitializationEvents = Nil,
    permutationSpans = Nil
  )
}

// A field, indexed value, or mutating receiver call changes state held by a variable without rebinding it.
case class StateMutation(
  declId: Long,
  method: String,
  line: Int,
  column: Int,
  code: String,
  mutationKind: String,
  parserOperator: String
)

case class ControlContext(
  path: String,
  method: String,
  line: Int,
  column: Int,
  code: String,
  contextType: String,
  subtype: String,
  relation: String
)

case class ScopeContext(
  path: String,
  method: String,
  methodName: String,
  line: Int,
  column: Int,
  endLine: Int,
  endColumn: Int,
  code: String,
  language: String,
  boundary: String,
  contextType: String,
  relation: String
)

// Per-variable summary so concept rules can be evaluated without re-querying the CPG.
case class VarFacts(decl: VarDecl, occurrences: List[Occurrence], writes: List[WriteInfo], stateMutations: List[StateMutation],
                    resolvedStateMutations: List[StateMutation],
                    controlContexts: List[ControlContext],
                    scopeContext: Option[ScopeContext],
                    fixedValueFlow: FixedValueFlow,
                    temporaryFlow: TemporaryFlow,
                    oneWayFlagFlow: OneWayFlagFlow,
                    gathererAdmissibility: GathererAdmissibility,
                    gathererFlow: GathererFlow,
                    followerFlow: FollowerFlow,
                    collectionFlow: CollectionFlow,
                    stepperFlow: StepperFlow,
                    walkerFlow: WalkerFlow,
                    mostWantedFlow: MostWantedFlow,
                    mostRecentFlow: MostRecentFlow,
                    usedInLoopCondition: Boolean,
                    implicitIterator: Boolean, implicitIteratorPosition: Option[Position],
                    initializedBeforeLoop: Boolean,
                    readCount: Int, isCollection: Boolean,
                    hasCollectionLengthMutation: Boolean,
                    hasSumPreviousValueMutation: Boolean,
                    hasInPlaceMutation: Boolean,
                    hasIndexedValueMutation: Boolean)

/** Expensive CPG traversals shared by every scientific phase.
  *
  * The lists retain Joern's materialized traversal order.  They are deliberately
  * stored once and passed by reference so moving a consumer between modules
  * cannot introduce another global traversal or alter stable write ordering.
  */
case class MaterializedCpg(
  methods: List[Method],
  calls: List[Call],
  identifiers: List[Identifier],
  fieldIdentifiers: List[FieldIdentifier],
  controls: List[ControlStructure]
)

/** Shared extraction inputs and non-role infrastructure. */
case class ExtractionContext(
  graph: Cpg,
  sourceRoot: String,
  language: String,
  sourceIndex: SourceIndex,
  profiler: ExtractionProfiler,
  materialized: MaterializedCpg
)

/** Named phase contracts.  During the progressive migration these bundles are
  * populated at the existing evaluation points, then become the return values
  * of the corresponding modules.  No bundle is part of the public JSON schema.
  */
case class BindingFacts(
  declarations: List[VarDecl],
  memberDeclarations: List[VarDecl],
  occurrencesByDeclaration: Map[Long, List[Occurrence]],
  memberOccurrencesByDeclaration: Map[Long, List[Occurrence]],
  writesByDeclaration: Map[Long, List[WriteInfo]],
  memberWritesByDeclaration: Map[Long, List[WriteInfo]],
  stateMutationsByDeclaration: Map[Long, List[StateMutation]]
)

case class ValueLifecycleFlows(
  fixedValueByDeclaration: Map[Long, FixedValueFlow],
  fixedValueByMember: Map[Long, FixedValueFlow],
  temporaryAdmissibleIds: Set[Long]
)

case class CollectionFlows(eventsByDeclaration: Map[Long, List[CollectionEvent]])
case class FollowerFlows(readsByDeclaration: Map[Long, List[FollowerRead]])
case class SuccessionFlows(implicitIteratorIds: Set[Long])

case class ExtractedFacts(
  variables: List[VarFacts],
  members: List[VarFacts],
  loopRanges: List[(String, Int, Int, String, String)],
  controlRanges: List[(String, Int, Int, String)]
)

// Source hint emitted by Scala; Python resolves it to an exact token or statement span.
case class ViewHint(
  path: String,
  line: Int,
  column: Int,
  name: String,
  code: String,
  usage: String,
  mutationKind: Option[String] = None,
  parserOperator: Option[String] = None,
  contextType: Option[String] = None,
  subtype: Option[String] = None,
  relation: Option[String] = None,
  hintEndLine: Option[Int] = None,
  hintEndColumn: Option[Int] = None,
  hintLanguage: Option[String] = None,
  hintBoundary: Option[String] = None
)

case class MinimalViews(
  declaration: Seq[ViewHint],
  identifier: Seq[ViewHint],
  reads: Seq[ViewHint],
  writes: Seq[ViewHint],
  updates: Seq[ViewHint],
  stateMutations: Seq[ViewHint],
  controlContext: Seq[ViewHint],
  scope: Seq[ViewHint]
)

case class RoleAnnotationHint(subject: VarDecl, role: String, views: MinimalViews)
case class VariableFactsHint(subject: VarDecl, isCollection: Boolean, roles: Seq[String], views: MinimalViews)
case class VariableAwareOutput(
  roleAnnotations: Seq[RoleAnnotationHint],
  variableFacts: Seq[VariableFactsHint]
)
