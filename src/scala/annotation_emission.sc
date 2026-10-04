def minimalViews(f: VarFacts): MinimalViews = {
  val allWrites = f.writes.sortBy(w => (w.line, w.column, !w.declarationInitializer))
  // Prefer the explicit initializer fact. Python's first assignment often lacks that
  // marker because its LOCAL node is attached to the assignment itself, so retain the
  // extractor's established first-write fallback for those locals.
  val bindingWrites =
    if f.decl.kind == "parameter" then allWrites.filterNot(_.declarationInitializer)
    else if allWrites.exists(_.declarationInitializer) then allWrites.filterNot(_.declarationInitializer)
    else allWrites.drop(1)
  val directReassignments = bindingWrites.filter(_.directWrite)
  val updateWrites = directReassignments.filter(_.selfRef)
  val plainWrites = directReassignments.filterNot(_.selfRef)
  val bindingWriteLines = bindingWrites.map(w => (w.method, w.line)).toSet
  val stateMutationLines = f.resolvedStateMutations.map(m => (m.method, m.line)).toSet
  // Joern can attach a Python loop variable's LOCAL declaration to its first body use.
  // The existing iterator fact already records the real first introduction in the loop header.
  val declarationPosition =
    if f.implicitIterator then f.implicitIteratorPosition.getOrElse(f.decl.pos)
    else f.decl.pos

  val identifierHints = f.occurrences
    .groupBy(o => (o.pos.path, o.pos.line, o.pos.column, o.name))
    .values
    .map { sameToken =>
      val occurrence = sameToken.head
      val isDeclaration =
        occurrence.pos.path == declarationPosition.path &&
        occurrence.pos.line == declarationPosition.line &&
        occurrence.pos.column == declarationPosition.column
      val usage =
        if isDeclaration then
          if f.decl.kind == "parameter" then "parameter" else "declaration"
        else if bindingWriteLines.contains((occurrence.method, occurrence.pos.line)) then "write"
        else if stateMutationLines.contains((occurrence.method, occurrence.pos.line)) then "unknown"
        else "read"
      ViewHint(
        occurrence.pos.path,
        occurrence.pos.line,
        occurrence.pos.column,
        occurrence.name,
        occurrence.pos.code,
        usage
      )
    }
    .toSeq
    .sortBy(h => (h.path, h.line, h.column, h.usage))

  val readHints = identifierHints
    .filter(_.usage == "read")
    .map(h => h.copy(code = h.code, usage = "read"))
    .distinctBy(h => (h.path, h.line))

  val writeHints = plainWrites
    .map(w => ViewHint(f.decl.pos.path, w.line, w.column, f.decl.name, w.code, "write"))
    .distinctBy(h => (h.path, h.line, h.column, h.code))

  val updateHints = updateWrites
    .map(w => ViewHint(f.decl.pos.path, w.line, w.column, f.decl.name, w.code, "update"))
    .distinctBy(h => (h.path, h.line, h.column, h.code))

  val stateMutationHints = f.resolvedStateMutations
    .map(m => ViewHint(
      f.decl.pos.path,
      m.line,
      m.column,
      f.decl.name,
      m.code,
      "state_mutation",
      mutationKind = Some(m.mutationKind),
      parserOperator = Some(m.parserOperator)
    ))
    .distinctBy(h => (h.path, h.line, h.column, h.mutationKind))

  val controlContextHints = f.controlContexts
    .map(c => ViewHint(
      c.path,
      c.line,
      c.column,
      f.decl.name,
      c.code,
      "control",
      contextType = Some(c.contextType),
      subtype = Some(c.subtype),
      relation = Some(c.relation)
    ))
    .distinctBy(h => (h.path, h.line, h.contextType, h.subtype, h.relation))

  // A local declared before a same-line callable defines that callable; it lives
  // in the enclosing scope rather than in the function value it introduces.
  val scopeHints = f.scopeContext.toSeq
    .filterNot(s =>
      f.decl.kind == "local" && f.decl.pos.path == s.path && f.decl.pos.line == s.line &&
        f.decl.pos.column > 0 && s.column > 0 && f.decl.pos.column < s.column
    )
    .map(s => ViewHint(
    s.path,
    s.line,
    s.column,
    s.methodName,
    s.code,
    "scope",
    contextType = Some(s.contextType),
    relation = Some(s.relation),
    hintEndLine = Some(s.endLine),
    hintEndColumn = Some(s.endColumn),
    hintLanguage = Some(s.language),
    hintBoundary = Some(s.boundary)
  ))

  MinimalViews(
    declaration = Seq(ViewHint(
      declarationPosition.path,
      declarationPosition.line,
      declarationPosition.column,
      f.decl.name,
      declarationPosition.code,
      if f.decl.kind == "parameter" then "parameter" else "declaration"
    )),
    identifier = identifierHints,
    reads = readHints,
    writes = writeHints,
    updates = updateHints,
    stateMutations = stateMutationHints,
    controlContext = controlContextHints,
    scope = scopeHints
  )
}

// Resolve only the explicitly specified Temporary conflicts. All other
// multilabel combinations are preserved.
def resolveRoleConflicts(rawRoles: Seq[String]): Seq[String] = {
  val roles = rawRoles.distinct.sorted
  val hasTemporary = roles.contains("temporary")
  val hasCompetingDynamicRole = roles.exists(role => role != "temporary" && role != "fixed_value")
  if hasTemporary && hasCompetingDynamicRole then roles.filterNot(_ == "temporary")
  else if hasTemporary then roles.filterNot(_ == "fixed_value")
  else roles
}

def emitVariableAwareOutput(classification: RoleClassification): VariableAwareOutput = {
  import classification.*

  val localRecords = facts.map { f =>
    val rawRoles = conceptPredicates
      .filter(_._2(f))
      .map((name, _) => cleanConceptName(name))
    val roles = resolveRoleConflicts(rawRoles)
    VariableFactsHint(f.decl, f.collectionFlow.kind != UnknownCollection &&
      f.collectionFlow.kind != TextSequence, roles, minimalViews(f))
  }
  val fieldRecords = fieldFacts.map { f =>
    val rawRoles = Seq(
      if isFixedValue(f) then Some("fixed_value") else None,
      if isOneWayFlag(f) then Some("one_way_flag") else None,
      if isFollower(f) then Some("follower") else None,
      if isStepper(f) then Some("stepper") else None,
      if isWalker(f) then Some("walker") else None,
      if isMostRecentHolder(f) then Some("most_recent_holder") else None,
      if isOrganizer(f) then Some("organizer") else None,
      if isContainer(f) then Some("container") else None
    ).flatten
    val roles = resolveRoleConflicts(rawRoles)
    VariableFactsHint(f.decl, f.collectionFlow.kind != UnknownCollection &&
      f.collectionFlow.kind != TextSequence, roles, minimalViews(f))
  }
  // Keep declaration identities distinct until Python has resolved exact source
  // spans. A line-only Scala key can merge real same-line shadowed declarations.
  val variableFacts = (localRecords ++ fieldRecords)
    .distinctBy(_.subject.id)
    .sortBy(r => (r.subject.pos.path, r.subject.pos.line, r.subject.pos.column, r.subject.name, r.subject.id))
  val roleAnnotations = variableFacts
    .flatMap(record => record.roles.map(role => RoleAnnotationHint(record.subject, role, record.views)))
    .sortBy(r => (r.subject.pos.path, r.subject.pos.line, r.subject.pos.column, r.role, r.subject.id))

  VariableAwareOutput(roleAnnotations, variableFacts)
}
