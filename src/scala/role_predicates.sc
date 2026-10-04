case class RolePredicateContext(
  facts: Seq[VarFacts],
  fieldFacts: Seq[VarFacts],
  loopRanges: Seq[(String, Int, Int, String, String)],
  controlRanges: Seq[(String, Int, Int, String)],
  declById: Map[Long, VarDecl],
  loopAt: (String, Int) => Option[(String, Int, Int, String)],
  controlAt: (String, Int) => Option[String],
  recordMetric: (String, Long) => Unit
)

case class RoleClassification(
  facts: Seq[VarFacts],
  fieldFacts: Seq[VarFacts],
  conceptPredicates: List[(String, VarFacts => Boolean)],
  isFixedValue: VarFacts => Boolean,
  isOneWayFlag: VarFacts => Boolean,
  isFollower: VarFacts => Boolean,
  isStepper: VarFacts => Boolean,
  isWalker: VarFacts => Boolean,
  isMostRecentHolder: VarFacts => Boolean,
  collectionRoleDecision: VarFacts => CollectionRoleDecision,
  isOrganizer: VarFacts => Boolean,
  isContainer: VarFacts => Boolean
)

def classifyRoles(context: RolePredicateContext): RoleClassification = {
  import context.*

  // A parameter counts as already initialized; locals need at least one write.
  def hasSingleInitialization(f: VarFacts): Boolean =
    f.decl.kind == "parameter" || f.writes.nonEmpty
  // All dynamic roles share the source-backed initialization partition built once
  // during fact extraction. No predicate guesses with writes.drop(1).
  def subsequentWrites(f: VarFacts): List[WriteInfo] =
    f.stepperFlow.partition.postInitializationWrites
  // In the fixedValueFlow a verified initialization takes into account explicit initialization (local and member) and implicit initialization (parameters).
  def hasVerifiedInitialization(f: VarFacts): Boolean =
    f.fixedValueFlow.analysisComplete && f.fixedValueFlow.verifiedInitialization
  def hasAtMostOneInitializationPerExecutionPath(f: VarFacts): Boolean =
    f.fixedValueFlow.analysisComplete && f.fixedValueFlow.atMostOneInitializationPerPath
  def hasNoRebindingAfterInitialization(f: VarFacts): Boolean =
    !f.fixedValueFlow.observedExternalRebinding &&
      f.fixedValueFlow.bindingWrites.forall(write => f.fixedValueFlow.initializationEventIds.contains(write.eventId))
  def hasNoUpdate(f: VarFacts): Boolean =
    f.fixedValueFlow.bindingWrites.forall(write => !write.selfRef && write.operator == "<operator>.assignment")
  def hasNoStateMutation(f: VarFacts): Boolean =
    f.stateMutations.isEmpty && !f.fixedValueFlow.observedStateMutation
  def hasNoLoopInitializationOrRebinding(f: VarFacts): Boolean =
    f.fixedValueFlow.bindingWrites.forall(!_.insideLoop) && !f.fixedValueFlow.observedLoopEvent
  def isScalarFixedValue(f: VarFacts): Boolean =
    hasVerifiedInitialization(f) &&
    hasAtMostOneInitializationPerExecutionPath(f) &&
    hasNoRebindingAfterInitialization(f) &&
    hasNoUpdate(f) &&
    hasNoStateMutation(f) &&
    hasNoLoopInitializationOrRebinding(f)

  // Gatherers and steppers
  // A non-predictable update inside a loop signals accumulation or selection behavior.
  def hasNonPredictableUpdate(f: VarFacts): Boolean =
    subsequentWrites(f).exists(w => w.insideLoop && w.nonPredictable)
  // String concatenation uses + but is not the numeric/math gatherer intended here.
  def hasStringLiteral(code: String): Boolean =
    code.matches("(?s).*\"([^\"\\\\]|\\\\.)*\".*") || code.matches("(?s).*'([^'\\\\]|\\\\.)*'.*")
  // Gatherers are direct arithmetic accumulations with an unpredictable contribution.
  def isArithmeticGathererUpdate(name: String, w: WriteInfo): Boolean = {
    val n = java.util.regex.Pattern.quote(name)
    val ident = "(?<![A-Za-z0-9_$])" + n + "(?![A-Za-z0-9_$])"
    val arithOp = "(?:\\+|-(?!>)|\\*|/)"
    val code = w.code.trim
    val hasUnpredictableTerm = w.sourceNames.nonEmpty || w.code.matches("(?s).*\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(.*")
    if !w.insideLoop || !w.directWrite || !w.selfRef || !hasUnpredictableTerm || hasStringLiteral(code) then false
    else
      w.operator match
        case "<operator>.assignmentPlus" | "<operator>.assignmentMinus" |
             "<operator>.assignmentMultiplication" | "<operator>.assignmentDivision" =>
          true
        case "<operator>.assignment" =>
          code.matches(s"(?s)^\\s*$ident\\s*=\\s*$ident\\s*$arithOp.*") ||
            code.matches(s"(?s)^\\s*$ident\\s*=\\s*.*$arithOp\\s*$ident\\s*$$")
        case _ => false
  }
  // At least one loop update must gather non-literal data; literal-only steppers stay out.
  def hasArithmeticGathererUpdate(f: VarFacts): Boolean =
    subsequentWrites(f).exists(w => isArithmeticGathererUpdate(f.decl.name, w))
  // Numeric gatherers should not start from string seeds or string-typed declarations.
  def hasNumericGathererSeed(f: VarFacts): Boolean = {
    val declCode = f.decl.pos.code.toLowerCase
    val stringTyped = declCode.matches("(?s).*\\b(?:std::)?string\\b.*")
    !stringTyped && (f.decl.kind == "parameter" || firstWrite(f).forall(w => !hasStringLiteral(w.code)))
  }
  // Holder concepts require a later unpredictable assignment, not just the initialization.
  def hasNonPredictableNonInitializationAssignment(f: VarFacts): Boolean =
    subsequentWrites(f).exists(w => w.directWrite && w.nonPredictable)
  // Ensure a holder is initialized before the same loop that later writes it.
  def isInitializedBeforeWritingLoop(f: VarFacts): Boolean = {
    val firstLine = firstWrite(f).map(_.line).getOrElse(f.decl.pos.line)
    loopRanges.exists { case (m, s, _, key, _) =>
      m == f.decl.method &&
      firstLine > 0 &&
      firstLine < s &&
      subsequentWrites(f).exists(w => w.loopKey.contains(key))
    }
  }
  // Stepper proof is resolved below as one deterministic declaration-level fixed point.
  def isStepperFact(f: VarFacts): Boolean = provedStepperIds.contains(f.decl.id)
  // An overwrite replaces the whole variable; declaration initializers and update operators do not count.
  def isLoopOverwrite(w: WriteInfo): Boolean = {
    val occursOnLocalDeclaration = declById.get(w.declId).exists(d => d.kind == "local" && d.pos.line == w.line)
    val occursInLoopBody = w.loopKey.exists { key =>
      loopRanges.exists { case (_, start, _, candidateKey, _) => candidateKey == key && w.line > start }
    }
    occursInLoopBody && w.directWrite && !w.declarationInitializer &&
      !occursOnLocalDeclaration && w.operator == "<operator>.assignment" && !w.selfRef
  }
  def loopOverwrites(f: VarFacts): List[WriteInfo] = f.writes.filter(isLoopOverwrite)
  def hasOneOverwriteInsideLoop(f: VarFacts): Boolean = loopOverwrites(f).size == 1

  // Tie walker values to the exact loop where their iterator token occurs.
  def isWalkerInLoop(f: VarFacts, loopKey: String): Boolean =
    f.implicitIterator && f.implicitIteratorPosition.exists { p =>
      loopAt(f.decl.method, p.line).exists(_._1 == loopKey)
    }

  lazy val allFactsById = (facts ++ fieldFacts).map(fact => fact.decl.id -> fact).toMap
  lazy val factsByMethodName = facts.groupBy(fact => (fact.decl.method, fact.decl.name))

  def rawFixedValueDeclaration(declarationId: Long): Boolean =
    allFactsById.get(declarationId).exists(isFixedValue)

  // A raw Fixed Value is sufficient as an arithmetic step contribution, but a
  // guard also needs a predictable seed. A value acquired from a call,
  // collection, dereference, or unresolved member stays immutable yet does not
  // make the decision to apply the step predictable. Parameters are accepted as
  // the caller-provided fixed guard inputs required by the contract.
  def predictableFixedGuardDeclaration(declarationId: Long): Boolean =
    allFactsById.get(declarationId).exists { fact =>
      rawFixedValueDeclaration(declarationId) && (
        fact.decl.kind == "parameter" || fact.fixedValueFlow.bindingWrites
          .filter(write => fact.fixedValueFlow.initializationEventIds.contains(write.eventId))
          .forall(_.rhs.exists(rhs => predictableStepperExpression(rhs.expression, fact.decl.id)))
      )
    }

  def allowedFollowerIndex(index: AllowedIndex): Boolean = index match
    case _: LiteralIndex => true
    case FixedVariableIndex(declarationId) => rawFixedValueDeclaration(declarationId)

  def allowedFollowerOffset(offset: FixedOffset): Boolean = offset match
    case _: LiteralOffset => true
    case VariableOffset(declarationId) => rawFixedValueDeclaration(declarationId)

  def masterDeclarationIds(source: MasterSource): Set[Long] = {
    val rootIds = source.root match
      case DeclarationRoot(declarationId) => Set(declarationId)
      case _: ImplicitOwnerRoot => Set.empty[Long]
    source.path.foldLeft(rootIds) {
      case (ids, MemberAccess(memberDeclarationId, _)) => ids + memberDeclarationId
      case (ids, IndexAccess(FixedVariableIndex(declarationId))) => ids + declarationId
      case (ids, _: IndexAccess) => ids
    }
  }

  def memberOwner(declarationId: Long): Option[String] =
    allFactsById.get(declarationId).flatMap(_.followerFlow.ownerFullName)

  def allowedMasterSource(follower: VarFacts, source: MasterSource): Boolean =
    !masterDeclarationIds(source).contains(follower.decl.id) && {
      val rootValid = source.root match
        case DeclarationRoot(declarationId) => allFactsById.contains(declarationId)
        case ImplicitOwnerRoot(ownerFullName) => ownerFullName.nonEmpty
      val pathValid = source.path.forall {
        case MemberAccess(memberDeclarationId, ownerFullName) =>
          memberOwner(memberDeclarationId).contains(ownerFullName)
        case IndexAccess(index) => allowedFollowerIndex(index)
      }
      val implicitOwnerConsistent = source.root match
        case ImplicitOwnerRoot(ownerFullName) => source.path.headOption.exists {
          case MemberAccess(_, memberOwnerFullName) => memberOwnerFullName == ownerFullName
          case _ => false
        }
        case _ => true
      rootValid && pathValid && implicitOwnerConsistent
    }

  def validFollowerRhs(follower: VarFacts, rhs: FollowerRhs): Boolean =
    allowedMasterSource(follower, rhs.masterSource) && rhs.fixedOffset.forall(allowedFollowerOffset)

  def validFollowerSources(write: FollowerWrite, follower: VarFacts): Set[MasterSource] =
    write.rhsCandidates.filter(validFollowerRhs(follower, _)).map(_.masterSource).toSet

  def hasFollowerReassignment(f: VarFacts): Boolean =
    f.followerFlow.analysisComplete && f.followerFlow.reassignments.nonEmpty

  def hasSelfDependentReassignment(f: VarFacts): Boolean =
    f.followerFlow.reassignments.exists { reassignment =>
      reassignment.write.selfRef || reassignment.rhsCandidates.exists(rhs =>
        masterDeclarationIds(rhs.masterSource).contains(f.decl.id) ||
          rhs.fixedOffset.exists {
            case VariableOffset(declarationId) => declarationId == f.decl.id
            case _ => false
          }
      )
    }

  def hasIndependentStateMutation(f: VarFacts): Boolean =
    f.stateMutations.nonEmpty || f.followerFlow.observedStateMutation

  def hasFollowerControlledReassignment(f: VarFacts): Boolean =
    f.followerFlow.guardedWriteIds.nonEmpty

  def commonMasterSource(f: VarFacts): Option[MasterSource] = {
    val sourceSets = f.followerFlow.reassignments.map(validFollowerSources(_, f))
    if sourceSets.isEmpty || sourceSets.exists(_.isEmpty) then None
    else
      val common = sourceSets.reduce(_ intersect _)
      if common.size == 1 && sourceSets.forall(_.size == 1) then common.headOption else None
  }

  def hasSingleConsistentMasterSource(f: VarFacts, source: MasterSource): Boolean =
    commonMasterSource(f).contains(source)

  def allFollowerWritesDependOnlyOnMaster(f: VarFacts, source: MasterSource): Boolean =
    f.followerFlow.reassignments.nonEmpty && f.followerFlow.reassignments.forall { reassignment =>
      validFollowerSources(reassignment, f) == Set(source)
    }

  def followerAccessPrefix(prefix: List[MasterAccess], path: List[MasterAccess]): Boolean =
    prefix.size <= path.size && prefix.zip(path).forall(_ == _)

  def canonicalFollowerMasterSource(source: MasterSource): MasterSource = source.root match
    case DeclarationRoot(declarationId) => memberOwner(declarationId) match
      case Some(ownerFullName) => MasterSource(
        ImplicitOwnerRoot(ownerFullName),
        MemberAccess(declarationId, ownerFullName) :: source.path
      )
      case None => source
    case _ => source

  def affectsFollowerMasterSource(updated: MasterSource, selected: MasterSource): Boolean = {
    val canonicalUpdated = canonicalFollowerMasterSource(updated)
    val canonicalSelected = canonicalFollowerMasterSource(selected)
    canonicalUpdated.root == canonicalSelected.root && (
      followerAccessPrefix(canonicalUpdated.path, canonicalSelected.path) ||
        followerAccessPrefix(canonicalSelected.path, canonicalUpdated.path)
    )
  }

  def masterUpdateDependsOnFollower(f: VarFacts, source: MasterSource): Boolean =
    f.followerFlow.masterUpdates.exists { update =>
      val relevantContext =
        if f.decl.kind == "local" then update.nearestLoopKey.exists(loopKey =>
          f.followerFlow.reassignments.exists(_.write.loopKey.contains(loopKey))
        )
        else f.followerFlow.reassignments.exists(_.write.method == update.method)
      relevantContext && affectsFollowerMasterSource(update.affectedSource, source) &&
        (update.directlyDependsOnFollower || update.transitivelyDependsOnFollower)
    }

  def hasLocalFollowerCycles(f: VarFacts, source: MasterSource): Boolean = {
    val writes = f.followerFlow.reassignments.map(_.write)
    val expectedContexts = writes.flatMap(_.loopKey).map(loopKey => s"loop:$loopKey").toSet
    f.decl.kind == "local" && writes.nonEmpty && writes.forall(_.loopKey.nonEmpty) &&
      f.followerFlow.contexts.filter(_.contextKind == "loop").map(_.key).toSet == expectedContexts &&
      expectedContexts.forall(contextKey =>
        f.followerFlow.cycles.exists(cycle => cycle.contextKey == contextKey && cycle.masterSource == source)
      )
  }

  def hasPersistentMemberFollowerCycles(f: VarFacts, source: MasterSource): Boolean = {
    val writes = f.followerFlow.reassignments.map(_.write)
    val expectedContexts = writes.map(write => s"method:${write.method}").toSet
    val rootOwner = source.root match
      case DeclarationRoot(declarationId) => memberOwner(declarationId)
      case ImplicitOwnerRoot(ownerFullName) => Some(ownerFullName)
    val persistentSourceOwner = source.path.collectFirst {
      case MemberAccess(_, ownerFullName) => ownerFullName
    }.orElse(rootOwner)
    f.decl.kind == "member" && f.followerFlow.ownerUnambiguous && writes.nonEmpty &&
      f.followerFlow.ownerFullName.nonEmpty &&
      persistentSourceOwner == f.followerFlow.ownerFullName &&
      writes.forall(_.method.nonEmpty) &&
      f.followerFlow.contexts.filter(_.contextKind == "method").map(_.key).toSet == expectedContexts &&
      expectedContexts.forall { contextKey =>
        val expectedWriteIds = writes.filter(write => s"method:${write.method}" == contextKey).map(_.eventId).toSet
        val cycles = f.followerFlow.cycles.filter(cycle =>
          cycle.contextKey == contextKey && cycle.masterSource == source
        )
        cycles.nonEmpty && cycles.iterator.flatMap(_.updateSpanWriteIds).toSet == expectedWriteIds
      }
  }

  def hasValidFollowerCycleInEveryContext(f: VarFacts, source: MasterSource): Boolean =
    hasLocalFollowerCycles(f, source) || hasPersistentMemberFollowerCycles(f, source)

  def isFollower(f: VarFacts): Boolean =
    hasFollowerReassignment(f) &&
      !hasSelfDependentReassignment(f) &&
      !hasIndependentStateMutation(f) &&
      !hasFollowerControlledReassignment(f) &&
      commonMasterSource(f).exists { source =>
        hasSingleConsistentMasterSource(f, source) &&
          allFollowerWritesDependOnlyOnMaster(f, source) &&
          !masterUpdateDependsOnFollower(f, source) &&
          hasValidFollowerCycleInEveryContext(f, source)
      }

  def structuredIndexDeclarationIds(expression: RhsExpr): Set[Long] = expression match
    case RhsIndexAccess(receiver, index) =>
      structuredIndexDeclarationIds(receiver) ++ rhsDeclarationIds(index)
    case RhsUnary(_, operand) => structuredIndexDeclarationIds(operand)
    case RhsBinary(_, left, right) => structuredIndexDeclarationIds(left) ++ structuredIndexDeclarationIds(right)
    case RhsMemberAccess(receiver, _, _, _) => structuredIndexDeclarationIds(receiver)
    case RhsDereference(operand) => structuredIndexDeclarationIds(operand)
    case RhsCall(_, arguments) => arguments.iterator.flatMap(structuredIndexDeclarationIds).toSet
    case _ => Set.empty

  def rhsDeclarationIds(expression: RhsExpr): Set[Long] = expression match
    case RhsDeclarationRef(id) => Set(id)
    case RhsSelfRef(id) => Set(id)
    case RhsUnary(_, operand) => rhsDeclarationIds(operand)
    case RhsBinary(_, left, right) => rhsDeclarationIds(left) ++ rhsDeclarationIds(right)
    case RhsMemberAccess(receiver, memberId, _, _) => rhsDeclarationIds(receiver) ++ memberId
    case RhsIndexAccess(receiver, index) => rhsDeclarationIds(receiver) ++ rhsDeclarationIds(index)
    case RhsDereference(operand) => rhsDeclarationIds(operand)
    case RhsCall(_, arguments) => arguments.iterator.flatMap(rhsDeclarationIds).toSet
    case _ => Set.empty

  lazy val stepperLoopKeysByDecl = facts.iterator.filter(isStepperFact).map { fact =>
    fact.decl.id -> fact.writes.flatMap(_.loopKey).toSet
  }.toMap

  // `items[i]` is loop data when the structured index resolves to a proved
  // Stepper declaration in that exact loop. No lexical scan or all-facts product.
  def indexesCollectionWithLoopStepper(w: WriteInfo, loopKey: String): Boolean =
    w.rhs.exists(rhs => structuredIndexDeclarationIds(rhs.expression).exists(indexId =>
      stepperLoopKeysByDecl.getOrElse(indexId, Set.empty).contains(loopKey)
    ))

  // Follow earlier direct copies in the same loop iteration to support aliases of traversed elements.
  def overwriteUsesLoopData(w: WriteInfo, visited: Set[Long] = Set.empty): Boolean =
    w.loopKey.exists { loopKey =>
      indexesCollectionWithLoopStepper(w, loopKey) ||
      w.sourceNames.exists { sourceName =>
        factsByMethodName.getOrElse((w.method, sourceName), Nil).exists { source =>
          source.decl.method == w.method &&
          source.decl.name == sourceName &&
          source.decl.id != w.declId &&
          !visited.contains(source.decl.id) &&
          (
            isWalkerInLoop(source, loopKey) ||
            source.writes.exists { sourceWrite =>
              isLoopOverwrite(sourceWrite) &&
              sourceWrite.loopKey.contains(loopKey) &&
              sourceWrite.line <= w.line &&
              overwriteUsesLoopData(sourceWrite, visited + w.declId + source.decl.id)
            }
          )
        }
      }
    }

  def hasOverwriteWithLoopDataCollection(f: VarFacts): Boolean =
    loopOverwrites(f).exists(overwriteUsesLoopData(_))
  def hasOverwriteWithMethodCall(f: VarFacts): Boolean =
    loopOverwrites(f).exists(_.rhsHasMethodCall)
  // A conditional candidate from the traversed collection is a selected best-so-far value.
  def hasControlAssignmentWithLoopDataCollection(f: VarFacts): Boolean =
    subsequentWrites(f).exists(w => w.insideControl && isLoopOverwrite(w) && overwriteUsesLoopData(w))
  // Require holder and candidate to appear in the same ordered comparison, not just the same if.
  def conditionOrdersNames(condition: String, holder: String, candidate: String): Boolean =
    condition
      .split("(?i)\\b(?:and|or)\\b|&&|\\|\\|")
      .exists { part =>
        containsName(part, holder) &&
        containsName(part, candidate) &&
        part.matches("(?s).*(?:<=|>=|<|>).*")
      }
  // Best-so-far code often assigns a candidate that was compared with the holder in the guard.
  def hasSelectedCandidateAssignment(f: VarFacts): Boolean =
    subsequentWrites(f).exists { w =>
      w.insideLoop &&
      w.insideControl &&
      w.directWrite &&
      !w.selfRef &&
      w.nonPredictable &&
      controlAt(w.method, w.line).exists { condition =>
        w.sourceNames.exists(src => conditionOrdersNames(condition, f.decl.name, src))
      }
    }
  // C/C++ code often expresses best-so-far updates as max/min-style self updates.
  def hasUpdateWithOptimumFunction(f: VarFacts): Boolean =
    subsequentWrites(f).exists { w =>
      w.insideLoop &&
      w.directWrite &&
      w.selfRef &&
      w.code.matches("(?is).*\\b(?:max|min|closest|best|minimum|maximum)\\s*\\(.*")
    }
  // One-way flags overwrite the variable directly rather than computing from the old value.
  def hasOverwriteOnlyWrites(f: VarFacts): Boolean =
    f.writes.nonEmpty && f.writes.forall(w => w.directWrite && !w.selfRef && w.operator == "<operator>.assignment")
  // Boolean-like declarations use bool/boolean types or only 0/1/true/false writes.
  def isBooleanLike(f: VarFacts): Boolean = {
    val declCode = f.decl.pos.code.toLowerCase
    val declaredBoolean = declCode.matches("(?s).*\\b(?:bool|boolean)\\b.*")
    val values = f.writes.flatMap(_.literalValue).toSet
    val hasBooleanLiteral = values.exists(v => v == "true" || v == "false")
    val numericFlagValues = values.nonEmpty && values.subsetOf(Set("0", "1"))
    val usedAsCondition = controlRanges.exists { case (m, _, _, header) => m == f.decl.method && containsName(header, f.decl.name) }
    declaredBoolean || hasBooleanLiteral || (numericFlagValues && usedAsCondition)
  }
  // One-way flags need one known initial literal value.
  def hasLiteralInitialization(f: VarFacts): Boolean =
    Set("local", "member").contains(f.decl.kind) && firstWrite(f).exists(w => w.directWrite && (w.declarationInitializer || !w.insideControl) && w.literalValue.nonEmpty)
  def oppositeLiteral(value: String): String =
    value match
      case "true" => "false"
      case "false" => "true"
      case "1" => "0"
      case "0" => "1"
      case other => other
  // After initialization, every write must assign the opposite literal and never revert.
  def subsequentWritesAreOppositeLiteralInitialization(f: VarFacts): Boolean =
    firstWrite(f).flatMap(_.literalValue).exists { init =>
      val opposite = oppositeLiteral(init)
      val sw = subsequentWrites(f)
      sw.nonEmpty && sw.forall(w => w.directWrite && w.literalValue.contains(opposite))
    }
  // Block-position predicates translate taxonomy rules into reusable checks.
  def notWrittenInsideControl(f: VarFacts): Boolean = f.writes.forall(!_.insideControl)
  def writtenInsideControl(f: VarFacts): Boolean = f.writes.exists(_.insideControl)
  def writtenInsideLoop(f: VarFacts): Boolean = f.writes.exists(_.insideLoop)
  // Generic source unpredictability rule for concepts that only need assignment entropy.
  def nonPredictableAssignmentSource(f: VarFacts): Boolean =
    f.writes.exists(_.nonPredictable)
  // First write is used as the initialization boundary for local variables.
  def firstWrite(f: VarFacts): Option[WriteInfo] = f.writes.sortBy(_.line).headOption
  // Imports and function definitions can look like assignments but are not temporary variables.
  def isImportOrDefinitionAssignment(code: String): Boolean = {
    val normalized = code.trim
    normalized.matches("(?s).*\\bfrom\\s+.+\\bimport\\b.*") ||
    normalized.matches("(?s).*\\bimport\\b.*") ||
    normalized.matches("(?s).*\\brequire\\s*\\(.*") ||
    normalized.matches("(?s).*\\bdef\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*") ||
    normalized.matches("(?s).*\\bfunction\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=\\s*function\\b.*")
  }
  // An update carries the previous binding value and is never a Temporary write.
  def hasNoTemporaryUpdate(f: VarFacts): Boolean =
    f.writes.nonEmpty && f.writes.forall(write => !write.selfRef)
  // Every binding write must be a direct, non-predictable overwrite rather than
  // an import/definition artifact. One invalid write rejects the whole variable.
  def allTemporaryAssignmentShape(f: VarFacts): Boolean =
    f.decl.kind == "local" &&
    f.writes.nonEmpty &&
    f.writes.forall { write =>
      write.directWrite &&
      write.operator == "<operator>.assignment" &&
      write.nonPredictable &&
      !isImportOrDefinitionAssignment(write.code)
    }
  // REACHING_DEF associations are complete only when every write reaches a read
  // and every normalized read is tied back to at least one modeled write.
  def hasReadAfterAllWrite(f: VarFacts): Boolean =
    f.temporaryFlow.analysisComplete &&
    f.writes.forall(write => f.temporaryFlow.associatedReadsByWrite.getOrElse(write.eventId, Nil).nonEmpty)
  // Reliable different-line positions use line distance. Same-line or missing-line
  // events fall back to a redefinition-free variable-event/instruction ordinal.
  def shortLiveRange(f: VarFacts): Boolean =
    f.temporaryFlow.analysisComplete && f.writes.forall { write =>
      val reads = f.temporaryFlow.associatedReadsByWrite.getOrElse(write.eventId, Nil)
      reads.size >= 1 && reads.size <= 5 && reads.forall { linked =>
        val readLine = linked.read.line
        val distance =
          if write.line > 0 && readLine > write.line then readLine - write.line
          else if write.line > 0 && readLine > 0 && readLine < write.line then Int.MaxValue
          else linked.instructionDistance
        distance >= 0 && distance <= 5
      }
    }
  // For every containing loop, removing this variable's writes must disconnect
  // loop entry/back-edge flow from each read. Ambiguous loop CFG rejects the role.
  def doesNotCrossLoopBoundaries(f: VarFacts): Boolean =
    f.temporaryFlow.analysisComplete && f.temporaryFlow.loopSafe

  // Joern-generated temporaries are implementation artifacts, not source-level collection concepts.
  def isSyntheticTemporaryName(name: String): Boolean =
    name.matches("_?tmp_?\\d+")
  def isFunctionLikeDeclaration(f: VarFacts): Boolean =
    f.decl.pos.code.matches("(?s).*\\bfunction\\b.*") ||
    f.writes.headOption.exists(w => isImportOrDefinitionAssignment(w.code))

  def isVerifiedNonTextCollection(f: VarFacts): Boolean =
    f.collectionFlow.kind != UnknownCollection && f.collectionFlow.kind != TextSequence
  def computeCollectionRoleDecision(f: VarFacts): CollectionRoleDecision = {
    if !isVerifiedNonTextCollection(f) || isSyntheticTemporaryName(f.decl.name) || isFunctionLikeDeclaration(f) then
      CollectionUnclassified
    else {
      val consumedEventIds = f.collectionFlow.permutationSpans.iterator.flatMap(_.eventIds).toSet
      val unconsumed = f.collectionFlow.postInitializationEvents.filterNot(event =>
        consumedEventIds.contains(event.eventId)
      )
      if unconsumed.nonEmpty then CollectionContainer
      else if f.collectionFlow.permutationSpans.nonEmpty && f.collectionFlow.verifiedInitialization &&
          f.collectionFlow.initialization.analysisComplete
      then CollectionOrganizer
      else if f.collectionFlow.verifiedInitialization && f.collectionFlow.initialization.analysisComplete &&
          f.collectionFlow.analysisComplete
      then CollectionFixedValue
      else CollectionUnclassified
    }
  }
  lazy val collectionRoleDecisionByDecl = (facts ++ fieldFacts).iterator
    .map(fact => fact.decl.id -> computeCollectionRoleDecision(fact)).toMap
  def collectionRoleDecision(f: VarFacts): CollectionRoleDecision =
    collectionRoleDecisionByDecl.getOrElse(f.decl.id, CollectionUnclassified)
  def isFixedValue(f: VarFacts): Boolean =
    if isVerifiedNonTextCollection(f) then collectionRoleDecision(f) == CollectionFixedValue
    else isScalarFixedValue(f)
  def isOrganizer(f: VarFacts): Boolean = collectionRoleDecision(f) == CollectionOrganizer
  def isContainer(f: VarFacts): Boolean = collectionRoleDecision(f) == CollectionContainer

  def predictableStepperExpression(expression: RhsExpr, targetId: Long): Boolean = expression match
    case _: RhsLiteral | RhsSelfRef(`targetId`) | _: RhsDeclarationRef => true
    case RhsUnary(operator, operand) =>
      Set("+", "-").contains(operator) && predictableStepperExpression(operand, targetId)
    case RhsBinary(operator, left, right) =>
      Set("+", "-", "*", "/").contains(operator) &&
        predictableStepperExpression(left, targetId) && predictableStepperExpression(right, targetId) &&
        // Deliberately reject the optional Sajaniemi toggle form `fixed - self`.
        !(operator == "-" && right == RhsSelfRef(targetId) && left != RhsSelfRef(targetId))
    case _ => false

  def predictableStepperGuardExpression(expression: RhsExpr): Boolean = expression match
    case _: RhsLiteral | _: RhsDeclarationRef | _: RhsSelfRef => true
    case RhsUnary(operator, operand) =>
      Set("!", "+", "-").contains(operator) && predictableStepperGuardExpression(operand)
    case RhsBinary(operator, left, right) =>
      Set("+", "-", "*", "/", "<", "<=", ">", ">=", "==", "!=", "&&", "||").contains(operator) &&
        predictableStepperGuardExpression(left) && predictableStepperGuardExpression(right)
    case RhsMemberAccess(receiver, memberId, _, _) =>
      memberId.nonEmpty && predictableStepperGuardExpression(receiver)
    case _ => false

  def rawStepperCandidate(f: VarFacts): Boolean =
    f.stepperFlow.analysisComplete && f.stepperFlow.updates.nonEmpty &&
      !isImplicitElementWalker(f) && !provedStructuralWalker(f) &&
      f.stepperFlow.progressions.exists(progression => progression.analysisComplete &&
        (progression.repeatedContext || progression.implicitIteration) && !progression.correctionLike) &&
      f.stepperFlow.updates.size == f.stepperFlow.partition.postInitializationWrites.size &&
      f.stepperFlow.updates.forall { update =>
        update.write.directWrite && update.rhs.complete && update.rhs.sourceBacked &&
          update.rhs.declarationDependencies.contains(f.decl.id) &&
          predictableStepperExpression(update.rhs.expression, f.decl.id) &&
          update.guards.forall(guard => guard.complete && guard.sourceBacked &&
            guard.expressions.nonEmpty && guard.expressions.forall(expression =>
              predictableStepperGuardExpression(expression.expression)
            ))
      }

  lazy val rawStepperCandidatesById: Map[Long, VarFacts] = allFactsById
    .filter((_, fact) => rawStepperCandidate(fact))

  lazy val provedStepperIds: Set[Long] = {
    recordMetric("stepper_candidates", rawStepperCandidatesById.size.toLong)
    recordMetric("stepper_prefiltered", (allFactsById.size - rawStepperCandidatesById.size).toLong)
    val guardedCandidates = rawStepperCandidatesById.valuesIterator.flatMap(_.stepperFlow.updates)
      .count(_.guards.nonEmpty)
    recordMetric("stepper_guard_candidates", guardedCandidates.toLong)
    recordMetric("stepper_guard_dependency_count", rawStepperCandidatesById.valuesIterator
      .flatMap(_.stepperFlow.updates).flatMap(_.guards).map(_.declarationDependencies.size.toLong).sum)
    val proved = scala.collection.mutable.Set[Long]()
    proved ++= allFactsById.valuesIterator.filter(_.walkerFlow.implicitNumericIterator).map(_.decl.id)
    var pending = rawStepperCandidatesById.keySet -- proved
    var changed = true
    var iterations = 0
    while changed && pending.nonEmpty && iterations <= rawStepperCandidatesById.size do
      iterations += 1
      val newlyProved = pending.toList.sorted.filter { declarationId =>
        val fact = rawStepperCandidatesById(declarationId)
        val rhsDependencies = fact.stepperFlow.updates.iterator
          .flatMap(update => update.rhs.declarationDependencies - declarationId).toSet
        val guardDependencies = fact.stepperFlow.updates.iterator
          .flatMap(_.guards).flatMap(_.declarationDependencies).filter(_ != declarationId).toSet
        rhsDependencies.forall(id => rawFixedValueDeclaration(id) || proved.contains(id)) &&
          guardDependencies.forall(id => predictableFixedGuardDeclaration(id) || proved.contains(id))
      }
      changed = newlyProved.nonEmpty
      proved ++= newlyProved
      pending --= newlyProved
    recordMetric("stepper_fixed_point_iterations", iterations.toLong)
    recordMetric("stepper_proved", proved.size.toLong)
    recordMetric("stepper_persistent_proved", proved.count(id =>
      allFactsById.get(id).exists(_.stepperFlow.persistentContext.nonEmpty)).toLong)
    recordMetric("stepper_guard_rejections", rawStepperCandidatesById.valuesIterator.count(fact =>
      fact.stepperFlow.updates.exists(_.guards.nonEmpty) && !proved.contains(fact.decl.id)
    ).toLong)
    proved.toSet
  }

  def validNavigationSource(source: NavigationSource): Boolean = source match
    case MemberNavigationSource(memberId) => memberId.nonEmpty
    case IndexedNavigationSource(collectionId) => rawFixedValueDeclaration(collectionId)
    case _: ImplicitElementSource => true
    case IteratorProgressionSource(_, collectionId, _) => collectionId.forall(rawFixedValueDeclaration)
    case NavigationMethodSource(_, methodIdentity) => methodIdentity.nonEmpty

  def provedStructuralWalker(f: VarFacts): Boolean = {
    val transitions = f.walkerFlow.transitions
    val transitionIds = transitions.map(_.write.eventId).toSet
    f.walkerFlow.analysisComplete && f.stepperFlow.partition.analysisComplete && transitions.nonEmpty &&
      f.stepperFlow.partition.postInitializationWrites.nonEmpty &&
      f.stepperFlow.partition.postInitializationWrites.forall(write => transitionIds.contains(write.eventId)) &&
      transitions.forall(transition => transition.usesPreviousPosition &&
        transition.loopKey.nonEmpty && transition.write.rhs.exists(rhs => rhs.complete && rhs.sourceBacked) &&
        transition.usedForAccessOrControl && validNavigationSource(transition.source))
  }

  def isImplicitElementWalker(f: VarFacts): Boolean =
    f.walkerFlow.implicitElementWalker && !f.walkerFlow.implicitNumericIterator

  def isWalkerFact(f: VarFacts): Boolean =
    isImplicitElementWalker(f) || provedStructuralWalker(f)

  def correctionUsesOnlyStableInputs(f: VarFacts, eventId: Long): Boolean =
    f.mostRecentFlow.partition.postInitializationWrites.find(_.eventId == eventId).flatMap(_.rhs).exists { rhs =>
      (rhs.declarationDependencies - f.decl.id).forall(rawFixedValueDeclaration)
    }

  def hasHolderControlledAcquisition(f: VarFacts): Boolean =
    f.mostRecentFlow.acquisitions.exists { acquisition =>
      acquisition.guards.exists(_.declarationDependencies.contains(f.decl.id)) &&
        Set(CollectionAcquisition, CurrentElementAcquisition).contains(acquisition.kind)
    }

  // An initializer may execute in an outer loop while the best-so-far
  // replacement is selected in an inner unbraced loop. Some C/C++ frontends
  // truncate that inner loop range at its header, so the write retains only the
  // outer loop key. Accept this fallback only with the complete structured MWH
  // proof: source-backed initialization before an intervening loop header,
  // collection/current-element replacement, and a guard that reads the holder.
  def initializedBeforeNestedSelection(f: VarFacts): Boolean = {
    val initializationWrites = f.writes.filter(write =>
      f.mostRecentFlow.partition.initializationEventIds.contains(write.eventId) && write.line > 0
    )
    f.mostRecentFlow.acquisitions.exists { acquisition =>
      val write = acquisition.write
      val holderGuard = acquisition.guards.exists(_.declarationDependencies.contains(f.decl.id))
      holderGuard && write.insideLoop && write.line > 0 &&
        Set(CollectionAcquisition, CurrentElementAcquisition).contains(acquisition.kind) &&
        initializationWrites.exists { initialization =>
          initialization.method == write.method && initialization.line < write.line &&
            loopRanges.exists { case (method, start, _, _, _) =>
              method == write.method && initialization.line < start && start < write.line
            }
        }
    }
  }

  def validMostWantedSeed(seed: MostWantedSeed): Boolean =
    seed.sourceBacked && (seed.kind match
      case ImplicitParameterSeed => seed.write.isEmpty
      case FixedSentinel => seed.value.exists(value => value.expression match
        case RhsDeclarationRef(declarationId) => rawFixedValueDeclaration(declarationId)
        case _ => false)
      case StableExternalSentinel => seed.value.exists(value => value.expression match
        case RhsExternalReference(name) => name.nonEmpty && value.complete && value.sourceBacked
        case _: RhsDeclarationRef => value.complete && value.sourceBacked
        case _ => false)
      case LiteralSentinel | NullSentinel | ExtremeSentinel | FirstCandidateSeed =>
        seed.value.exists(value => value.complete && value.sourceBacked &&
          !value.expression.isInstanceOf[RhsUnknown])
    )

  def isPrimaryMostWantedFact(f: VarFacts): Boolean = {
    val flow = f.mostWantedFlow
    val allWriteIds = f.writes.map(_.eventId).toSet
    val epochDirections = flow.epochs.map(epoch => epoch.replacements.map(_.direction).distinct)
    flow.analysisComplete && flow.epochs.nonEmpty && flow.epochs.forall(epoch =>
      validMostWantedSeed(epoch.seed) && epoch.replacements.nonEmpty && epoch.analysisComplete
    ) && flow.replacements.nonEmpty && flow.incompatibleWriteIds.isEmpty &&
      flow.explainedEventIds == allWriteIds && epochDirections.forall(_.size == 1) &&
      f.stateMutations.isEmpty && f.resolvedStateMutations.isEmpty &&
      !isStepperFact(f) && !isWalkerFact(f) && !isNumericGathererFlow(f) && !isFollower(f)
  }

  lazy val primaryMostWantedIds: Set[Long] = allFactsById.valuesIterator
    .filter(isPrimaryMostWantedFact).map(_.decl.id).toSet

  lazy val mostWantedCompanionsById: Map[Long, MostWantedCompanion] = {
    val primaryWritesByGuard = primaryMostWantedIds.toList.sorted.flatMap { primaryId =>
      val primary = allFactsById(primaryId)
      primary.mostWantedFlow.replacements.flatMap { replacement =>
        primary.mostWantedFlow.writeGuardControlIds.getOrElse(replacement.write.eventId, Set.empty)
          .map(guardId => guardId -> (primaryId, replacement))
      }
    }.groupMap(_._1)(_._2)
    allFactsById.valuesIterator.filterNot(fact => primaryMostWantedIds.contains(fact.decl.id)).flatMap { fact =>
      val postWrites = fact.stepperFlow.partition.postInitializationWrites
      val coupled = postWrites.flatMap { write =>
        val guards = fact.mostWantedFlow.writeGuardControlIds.getOrElse(write.eventId, Set.empty)
        Option.when(guards.size == 1)(guards.head).flatMap { guardId =>
          primaryWritesByGuard.getOrElse(guardId, Nil) match
            case List((primaryId, replacement)) => Some((write, primaryId, replacement))
            case _ => None
        }
      }
      val primaryIds = coupled.map(_._2).distinct
      val dependencyIds = postWrites.iterator.flatMap(_.rhs.toList)
        .flatMap(_.declarationDependencies).filter(_ != fact.decl.id).toSet
      val dependenciesAdmissible = dependencyIds.nonEmpty && dependencyIds.forall(id =>
        allFactsById.get(id).exists(dependency =>
          isFixedValue(dependency) || isStepperFact(dependency) || isWalkerFact(dependency)))
      val lastWriteLine = postWrites.map(_.line).maxOption.getOrElse(Int.MaxValue)
      val readAfterSelection = fact.occurrences.exists(occurrence =>
        !occurrence.isDeclaration && occurrence.pos.line > lastWriteLine)
      val contexts = coupled.map(_._3.contextKey).distinct
      Option.when(postWrites.nonEmpty && coupled.size == postWrites.size && primaryIds.size == 1 &&
        contexts.size == 1 && dependenciesAdmissible && readAfterSelection &&
        fact.stateMutations.isEmpty && fact.resolvedStateMutations.isEmpty)(
        fact.decl.id -> MostWantedCompanion(
          fact.decl.id, primaryIds.head, postWrites.map(_.eventId),
          coupled.map(_._3.write.eventId), dependencyIds, contexts.head, analysisComplete = true)
      )
    }.toMap
  }

  def isMostWantedFact(f: VarFacts): Boolean =
    isPrimaryMostWantedFact(f) || mostWantedCompanionsById.contains(f.decl.id)

  def acquisitionGuardDependsOnHolder(f: VarFacts, acquisition: AcquisitionEvent): Boolean =
    acquisition.guards.exists(_.declarationDependencies.contains(f.decl.id))

  def validAcquisitionContext(acquisition: AcquisitionEvent): Boolean =
    if acquisition.contextKey.startsWith("loop:") then true
    else if acquisition.contextKey.startsWith("iterator:") then
      acquisition.projectionRootIds.exists(rootId => allFactsById.get(rootId).exists(isWalkerFact))
    else false

  def isRawMostRecentHolderFact(f: VarFacts): Boolean = {
    val flow = f.mostRecentFlow
    val allCorrectionsStable = flow.corrections.forall(span =>
      span.correctionEventIds.forall(correctionUsesOnlyStableInputs(f, _))
    )
    val contextsValid =
      if f.decl.kind == "member" then flow.persistentMember
      else flow.acquisitions.nonEmpty && flow.acquisitions.forall(validAcquisitionContext)
    val holderControlled = flow.acquisitions.exists(acquisitionGuardDependsOnHolder(f, _))
    flow.analysisComplete && flow.acquisitions.nonEmpty && contextsValid &&
      flow.explainedEventIds == flow.partition.postInitializationWrites.map(_.eventId).toSet &&
      flow.allAcquisitionsUsed && allCorrectionsStable &&
      !holderControlled && !isWalkerFact(f) && !isMostWantedFact(f)
  }

  def isMostRecentHolderFact(f: VarFacts): Boolean =
    isRawMostRecentHolderFact(f) && !isFollower(f)

  def monotoneOperand(expression: RhsAnalysis, targetIds: Set[Long], operator: String): Option[RhsExpr] =
    expression.expression match
      case RhsBinary(`operator`, RhsSelfRef(id), other) if targetIds.contains(id) => Some(other)
      case RhsBinary(`operator`, other, RhsSelfRef(id)) if targetIds.contains(id) => Some(other)
      case _ => None

  def constantBoolean(expression: RhsExpr): Option[Boolean] = expression match
    case RhsLiteral(code) => code.trim.toLowerCase match
      case "true" | "1" => Some(true)
      case "false" | "0" => Some(false)
      case _ => None
    case RhsUnary("!", operand) => constantBoolean(operand).map(value => !value)
    case RhsBinary("||", left, right) =>
      constantBoolean(left).flatMap(leftValue =>
        constantBoolean(right).map(rightValue => leftValue || rightValue)
      )
    case RhsBinary("&&", left, right) =>
      constantBoolean(left).flatMap(leftValue =>
        constantBoolean(right).map(rightValue => leftValue && rightValue)
      )
    case _ => None

  def structurallyBooleanOperand(expression: RhsExpr): Boolean = expression match
    case RhsLiteral(code) => Set("true", "false").contains(code.trim.toLowerCase)
    case _: RhsDeclarationRef => true
    case RhsUnary("!", operand) => structurallyBooleanOperand(operand)
    case RhsBinary(operator, left, right) if Set("&&", "||").contains(operator) =>
      structurallyBooleanOperand(left) && structurallyBooleanOperand(right)
    case RhsBinary(operator, _, _) if Set("==", "!=", "<", "<=", ">", ">=").contains(operator) => true
    case _ => false

  def isOneWayFlagFlow(f: VarFacts): Boolean = {
    val flow = f.oneWayFlagFlow
    flow.analysisComplete && flow.ownerIdentityComplete && flow.transitions.nonEmpty &&
      flow.initializationValue.exists { initial =>
        val compatible = flow.transitions.forall {
          case AssignTerminal(value, _) => value == !initial
          case MonotoneOr(expression, _) => !initial &&
            monotoneOperand(expression, flow.identityDeclarationIds, "||")
              .exists(structurallyBooleanOperand)
          case MonotoneAnd(expression, _) => initial &&
            monotoneOperand(expression, flow.identityDeclarationIds, "&&")
              .exists(structurallyBooleanOperand)
        }
        val canReachTerminal = flow.transitions.exists {
          case AssignTerminal(value, _) => value == !initial
          case MonotoneOr(expression, _) =>
            !initial && monotoneOperand(expression, flow.identityDeclarationIds, "||")
              .exists(operand => structurallyBooleanOperand(operand) && constantBoolean(operand) != Some(false))
          case MonotoneAnd(expression, _) =>
            initial && monotoneOperand(expression, flow.identityDeclarationIds, "&&")
              .exists(operand => structurallyBooleanOperand(operand) && constantBoolean(operand) != Some(true))
        }
        compatible && canReachTerminal
      }
  }

  def numericLiteral(expression: RhsExpr): Boolean = expression match
    case RhsLiteral(code) =>
      val normalized = code.trim.toLowerCase
      normalized.matches("[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)") &&
        !Set("true", "false").contains(normalized)
    case _ => false

  def stableGathererInput(analysis: RhsAnalysis): Boolean =
    analysis.complete && analysis.sourceBacked && {
      def stable(expression: RhsExpr): Boolean = expression match
        case literal: RhsLiteral => numericLiteral(literal)
        case RhsDeclarationRef(declarationId) =>
          rawFixedValueDeclaration(declarationId) || provedStepperIds.contains(declarationId)
        case RhsUnary("+" | "-", operand) => stable(operand)
        case RhsBinary("+" | "-" | "*" | "/", left, right) => stable(left) && stable(right)
        case _ => false
      stable(analysis.expression)
    }

  def stableGathererSeed(reset: GathererReset): Boolean =
    reset.seed.complete && reset.seed.sourceBacked && (reset.seed.expression match
      case RhsLiteral(code) =>
        val normalized = code.trim.toLowerCase
        normalized.matches("[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)") &&
          !Set("true", "false").contains(normalized)
      case RhsDeclarationRef(declarationId) => rawFixedValueDeclaration(declarationId)
      case _ => false
    )

  def hasDataContribution(expression: RhsExpr): Boolean = expression match
    case _: RhsIndexAccess | _: RhsMemberAccess | _: RhsDereference => true
    // A dynamic call is not data merely by being dynamic. Only structured data
    // dependencies in its arguments may establish a contribution.
    case RhsCall(_, arguments) => arguments.exists(hasDataContribution)
    case RhsUnary(_, operand) => hasDataContribution(operand)
    case RhsBinary(_, left, right) => hasDataContribution(left) || hasDataContribution(right)
    case _ => false

  def isGathererDataContribution(contribution: RhsAnalysis): Boolean = {
    val dependencies = contribution.declarationDependencies
    contribution.complete && contribution.sourceBacked &&
      (hasDataContribution(contribution.expression) || dependencies.exists(declarationId =>
        !rawFixedValueDeclaration(declarationId) && !provedStepperIds.contains(declarationId)
      ))
  }

  def validGathererInitialization(initialization: InitializationSeed): Boolean =
    initialization.implicitParameter || initialization.seed.exists { seed =>
      stableGathererInput(seed) || (initialization.dynamicFirstContribution &&
        (isGathererDataContribution(seed) || seed.expression.isInstanceOf[RhsCall]))
    }

  def isNumericGathererFlow(f: VarFacts): Boolean = {
    val flow = f.gathererFlow
    val postWriteIds = f.stepperFlow.partition.postInitializationWrites.map(_.eventId).toSet
    val explainedPostWriteIds = flow.resets.map(_.write.eventId).toSet ++
      flow.dataUpdates.map(_.write.eventId) ++ flow.stableTransforms.map(_.write.eventId) ++
      flow.finalizations.map(_.write.eventId)
    val structuralDataById = flow.dataUpdates.map(update => update.write.eventId -> update.contribution).toMap
    val structuralTransformsById = flow.stableTransforms.map(transform =>
      transform.write.eventId -> transform.operand).toMap
    val effectiveDataIds = structuralDataById.collect {
      case (eventId, contribution) if isGathererDataContribution(contribution) => eventId
    }.toSet ++ structuralTransformsById.collect {
      case (eventId, operand) if isGathererDataContribution(operand) => eventId
    }
    val effectiveStableTransformIds = structuralTransformsById.collect {
      case (eventId, operand) if stableGathererInput(operand) => eventId
    }.toSet
    val loopUpdateIds = structuralDataById.keySet ++ structuralTransformsById.keySet
    val classifiedLoopUpdateIds = effectiveDataIds ++ effectiveStableTransformIds
    val spannedUpdateIds = flow.spans.iterator.flatMap(span =>
      span.dataUpdateEventIds ++ span.transformEventIds).toSet
    val updateContexts = (flow.dataUpdates.map(_.write) ++ flow.stableTransforms.map(_.write))
      .flatMap(_.loopKey).toSet
    val spanContexts = flow.spans.map(_.contextKey).toSet
    val spansContainData = flow.spans.forall(span =>
      (span.dataUpdateEventIds ++ span.transformEventIds).exists(effectiveDataIds.contains)
    )
    val finalizationsValid = flow.finalizations.groupBy(_.write.method).values.forall(_.size == 1) &&
      flow.finalizations.forall(finalization => finalization.write.loopKey.isEmpty &&
        finalization.write.directWrite && finalization.write.selfRef && stableGathererInput(finalization.operand))
    f.gathererAdmissibility.admitted && flow.analysisComplete && !f.isCollection && hasNumericGathererSeed(f) &&
      !isStepperFact(f) && !isWalkerFact(f) && flow.initialization.exists(validGathererInitialization) &&
      effectiveDataIds.nonEmpty && flow.incompatibleWriteIds.isEmpty &&
      postWriteIds.subsetOf(explainedPostWriteIds) &&
      flow.resets.forall(stableGathererSeed) &&
      classifiedLoopUpdateIds == loopUpdateIds && spannedUpdateIds == loopUpdateIds &&
      updateContexts == spanContexts && flow.spans.nonEmpty && spansContainData && finalizationsValid
  }

  val walkerCandidateCount = allFactsById.valuesIterator.count(f =>
    f.walkerFlow.implicitElementWalker || f.walkerFlow.implicitNumericIterator || f.walkerFlow.transitions.nonEmpty
  )
  val mostRecentCandidateCount = allFactsById.valuesIterator.count(_.mostRecentFlow.acquisitions.nonEmpty)
  recordMetric("walker_candidates", walkerCandidateCount.toLong)
  recordMetric("walker_prefiltered", (allFactsById.size - walkerCandidateCount).toLong)
  recordMetric("most_recent_candidates", mostRecentCandidateCount.toLong)
  recordMetric("most_recent_prefiltered", (allFactsById.size - mostRecentCandidateCount).toLong)
  recordMetric("walker_projection_acquisitions", allFactsById.valuesIterator.flatMap(_.mostRecentFlow.acquisitions)
    .count(acquisition => acquisition.contextKey.startsWith("iterator:") &&
      acquisition.projectionRootIds.exists(rootId => allFactsById.get(rootId).exists(isWalkerFact))).toLong)
  recordMetric("persistent_member_candidates", fieldFacts.count(_.mostRecentFlow.acquisitions.nonEmpty).toLong)
  recordMetric("follower_member_candidates", fieldFacts.count(_.followerFlow.reassignments.nonEmpty).toLong)
  recordMetric("follower_member_cycles", fieldFacts.iterator.flatMap(_.followerFlow.cycles).size.toLong)
  recordMetric("follower_member_proved", fieldFacts.count(isFollower).toLong)
  recordMetric("mrh_guard_rejections", allFactsById.valuesIterator.count(fact =>
    fact.mostRecentFlow.acquisitions.exists(acquisitionGuardDependsOnHolder(fact, _))
  ).toLong)
  val oneWayCandidates = allFactsById.valuesIterator.count(_.oneWayFlagFlow.initializationValue.nonEmpty)
  val gathererCandidateFacts = allFactsById.valuesIterator.filter(fact =>
    fact.gathererFlow.dataUpdates.nonEmpty || fact.gathererFlow.stableTransforms.nonEmpty).toList
  val gathererCandidates = gathererCandidateFacts.size
  recordMetric("one_way_flag_candidates", oneWayCandidates.toLong)
  recordMetric("one_way_flag_prefiltered", (allFactsById.size - oneWayCandidates).toLong)
  recordMetric("one_way_flag_owner_complete", allFactsById.valuesIterator
    .count(_.oneWayFlagFlow.ownerIdentityComplete).toLong)
  recordMetric("one_way_flag_with_transitions", allFactsById.valuesIterator
    .count(_.oneWayFlagFlow.transitions.nonEmpty).toLong)
  recordMetric("one_way_flag_analysis_complete", allFactsById.valuesIterator
    .count(_.oneWayFlagFlow.analysisComplete).toLong)
  recordMetric("one_way_flag_proved", allFactsById.valuesIterator.count(isOneWayFlagFlow).toLong)
  recordMetric("gatherer_candidates", gathererCandidates.toLong)
  recordMetric("gatherer_prefiltered", (allFactsById.size - gathererCandidates).toLong)
  recordMetric("gatherer_implicit_initializations", gathererCandidateFacts.count(
    _.gathererFlow.initialization.exists(_.implicitParameter)).toLong)
  recordMetric("gatherer_dynamic_initializations", gathererCandidateFacts.count(
    _.gathererFlow.initialization.exists(_.dynamicFirstContribution)).toLong)
  recordMetric("gatherer_stable_resets", gathererCandidateFacts.iterator.flatMap(_.gathererFlow.resets)
    .count(stableGathererSeed).toLong)
  recordMetric("gatherer_data_contributions", gathererCandidateFacts.iterator.flatMap { fact =>
    fact.gathererFlow.dataUpdates.map(_.contribution) ++ fact.gathererFlow.stableTransforms.map(_.operand)
  }.count(isGathererDataContribution).toLong)
  recordMetric("gatherer_stable_transforms", gathererCandidateFacts.iterator
    .flatMap(_.gathererFlow.stableTransforms).count(transform => stableGathererInput(transform.operand)).toLong)
  recordMetric("gatherer_finalizations", gathererCandidateFacts.iterator.flatMap(_.gathererFlow.finalizations)
    .count(finalization => stableGathererInput(finalization.operand)).toLong)
  recordMetric("gatherer_incompatible_writes", gathererCandidateFacts.iterator
    .map(_.gathererFlow.incompatibleWriteIds.size.toLong).sum)
  recordMetric("gatherer_collection_text_rejections", gathererCandidateFacts.count(fact =>
    fact.isCollection || !hasNumericGathererSeed(fact)).toLong)
  recordMetric("gatherer_stepper_walker_rejections", gathererCandidateFacts.count(fact =>
    isStepperFact(fact) || isWalkerFact(fact)).toLong)
  recordMetric("gatherer_spans", allFactsById.valuesIterator.flatMap(_.gathererFlow.spans).size.toLong)
  recordMetric("gatherer_proved", allFactsById.valuesIterator.count(isNumericGathererFlow).toLong)
  recordMetric("mrh_follower_raw_collisions", allFactsById.valuesIterator
    .count(fact => isFollower(fact) && isRawMostRecentHolderFact(fact)).toLong)
  recordMetric("mwh_companions_proved", mostWantedCompanionsById.size.toLong)

  // Taxonomy concepts expressed as predicates over normalized variable facts.
  val conceptPredicates: List[(String, VarFacts => Boolean)] = List(
    "Fixed value" -> (f => isFixedValue(f)),
    "Stepper" -> (f => isStepperFact(f)),
    "Gatherer" -> (f => isNumericGathererFlow(f)),
    "Walker" -> (f => isWalkerFact(f)),
    "Follower" -> (f => isFollower(f)),
    "Most-recent holder" -> (f => isMostRecentHolderFact(f)),
    "Most-wanted holder" -> (f => isMostWantedFact(f)),
    "One-way flag" -> (f => isOneWayFlagFlow(f)),
    "Temporary" -> (f =>
      allTemporaryAssignmentShape(f) &&
      hasReadAfterAllWrite(f) &&
      shortLiveRange(f) &&
      doesNotCrossLoopBoundaries(f) &&
      hasNoTemporaryUpdate(f)
    ),
    "Organizer" -> (f => isOrganizer(f)),
    "Container" -> (f => isContainer(f))
  )

  def isOneWayFlag(f: VarFacts): Boolean =
    isOneWayFlagFlow(f)

  RoleClassification(facts, fieldFacts, conceptPredicates, isFixedValue, isOneWayFlag, isFollower,
    isStepperFact, isWalkerFact, isMostRecentHolderFact,
    collectionRoleDecision, isOrganizer, isContainer)
}
