import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class ExtractionContextIndex(graph: Cpg, sourceRoot: String) {
  val cpg = graph
  val language = cpg.metaData.language.headOption.getOrElse("")
  val profiler = ExtractionProfiler.fromEnvironment(language)
  List(
    "collection_candidates", "collection_prefiltered", "collection_events",
    "collection_element_write_events", "manual_swap_candidate_collections",
    "manual_swap_load_candidates", "manual_swap_candidate_pairs", "manual_swap_proved",
    "manual_swap_cfg_queries", "manual_swap_reaching_def_queries",
    "collection_path_cache_hits", "collection_path_cache_misses",
    "collection_call_id_lookups", "collection_position_lookups",
    "mrh_candidate_declarations", "mrh_acquisitions", "mrh_corrections",
    "mrh_semantic_reads", "mrh_cfg_searches", "mrh_cfg_visited_volume",
    "mrh_max_writes_per_candidate", "mrh_max_reads_per_candidate",
    "mrh_max_acquisitions_per_candidate", "mrh_methods_with_candidates",
    "mrh_rejected_before_cfg", "mrh_rejected_after_cfg",
    "mrh_cfg_cache_entries", "mrh_cfg_cache_hits", "mrh_cfg_cache_misses",
    "numeric_iterator_candidate_lines", "numeric_iterator_language_filtered_calls",
    "numeric_iterator_cache_hits", "numeric_iterator_candidate_controls",
    "numeric_iterator_fallback_declarations", "numeric_iterator_max_header_length",
    "numeric_iterator_cache_entries", "numeric_iterator_resolved"
  ).foreach(counter => profiler.set(counter, 0L))
  val graphFilenames = cpg.file.name.l.map(_.toLowerCase)
  val sourceIndexLanguage =
    if language.toUpperCase.contains("NEWC") && graphFilenames.exists(
      _.matches(".*\\.(?:cc|cpp|cxx|c\\+\\+|hh|hpp|hxx)$")
    ) then s"${language}_CPP"
    else if language.toUpperCase.contains("NEWC") then s"${language}_C"
    else language
  val sourceIndex = profiler.timed("source_index") {
    SourceIndex(sourceRoot, sourceIndexLanguage)
  }
  import sourceIndex.*
  // Explicitly expose SourceIndex through the phase contract. Imports are
  // lexical in Scala and therefore are not re-exported to downstream modules.
  def sourceLines(path: String): Option[List[String]] = sourceIndex.sourceLines(path)
  def sourceLine(path: String, line: Int): Option[String] = sourceIndex.sourceLine(path, line)
  def firstLine(code: String): String = sourceIndex.firstLine(code)
  def sourceIndentBlockEnd(path: String, start: Int): Option[Int] =
    sourceIndex.sourceIndentBlockEnd(path, start)
  // Materialize frequently reused traversals once. These node handles are shared
  // by all downstream indexes; no graph content is copied.
  val allMethodNodes = profiler.timed("cpg_materialization_methods") { cpg.method.l }
  val allCallNodes = profiler.timed("cpg_materialization_calls") { cpg.call.l }
  val callsByName = allCallNodes.groupBy(_.name)
  val callsByLowerName = allCallNodes.groupBy(_.name.toLowerCase)
  val callById = allCallNodes.iterator.map(call => call.id -> call).toMap
  val allIdentifierNodes = profiler.timed("cpg_materialization_identifiers") { cpg.identifier.l }
  val allFieldIdentifierNodes = cpg.fieldIdentifier.l
  val allControlStructureNodes = profiler.timed("cpg_materialization_controls") { cpg.controlStructure.l }
  val extractionContext = ExtractionContext(
    graph = cpg,
    sourceRoot = sourceRoot,
    language = language,
    sourceIndex = sourceIndex,
    profiler = profiler,
    materialized = MaterializedCpg(
      methods = allMethodNodes,
      calls = allCallNodes,
      identifiers = allIdentifierNodes,
      fieldIdentifiers = allFieldIdentifierNodes,
      controls = allControlStructureNodes
    )
  )
  profiler.set("cpg_methods", allMethodNodes.size.toLong)
  profiler.set("cpg_calls", allCallNodes.size.toLong)
  profiler.set("cpg_identifiers", allIdentifierNodes.size.toLong)
  profiler.set("cpg_controls", allControlStructureNodes.size.toLong)
  val languageUpper = language.toUpperCase
  val blockDelimitedScopes = languageUpper.contains("NEWC") || languageUpper.contains("JSSRC")
  val definitionDelimitedScopes = languageUpper.contains("PYTHON") || languageUpper.contains("RUBY")
  def isOwnershipSyntheticMethodName(name: String): Boolean =
    Set("<module>", "<global>", "<clinit>", "<main>", ":program", "<body>", "<fakeNew>", "<metaClassCallHandler>")
      .contains(name) || name.startsWith("<lambda>") || name.contains("<metaClassAdapter>")
  def isFileOrWrapperMethodName(name: String): Boolean =
    Set("<module>", "<global>", "<clinit>", "<main>", ":program", "<body>", "<fakeNew>", "<metaClassCallHandler>")
      .contains(name) || name.contains("<metaClassAdapter>")
  def scopeContextType(method: Method): String =
    method.astParent match
      case _: TypeDecl => "method"
      case _ if method.parameter.name.l.exists(name => name == "self" || name == "this") => "method"
      case _ => "function"
  def sourceDefinitionBoundary(method: Method, line: Int): Option[String] =
    sourceLine(nodePath(method), line).exists { source =>
      val trimmed = source.trim
      if languageUpper.contains("PYTHON") then
        trimmed.matches("^(?:async\\s+)?def\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*(?:\\(|:).*" )
      else if languageUpper.contains("RUBY") then
        trimmed.matches("^def\\s+(?:self\\.)?(?:[A-Za-z_$][A-Za-z0-9_$]*[!?=]?|\\[\\]=?|<=>|==|===|<=|>=|<<|>>|[-+*/%&|^~`])(?:\\s|\\(|$).*" )
      else false
    } match
      case true if languageUpper.contains("PYTHON") => Some("python_def")
      case true if languageUpper.contains("RUBY") => Some("ruby_def")
      case _ => None
  def anonymousBoundary(method: Method, blockBoundary: Option[(Int, Int)]): Option[(String, Int, Int)] = {
    val code = method.code.trim
    val endLine = optInt(method.lineNumberEnd, optInt(method.lineNumber, 0))
    val endColumn = optInt(method.columnNumberEnd, optInt(method.columnNumber, 0))
    if languageUpper.contains("NEWC") && method.name.startsWith("<lambda>") && code.startsWith("[") then
      blockBoundary.map((line, column) => ("cpp_lambda_block", line, column))
    else if languageUpper.contains("JSSRC") && !isFileOrWrapperMethodName(method.name) then
      blockBoundary.map((line, column) => ("javascript_block", line, column))
        .orElse(if code.contains("=>") then Some(("javascript_arrow_expression", endLine, endColumn)) else None)
    else if languageUpper.contains("PYTHON") && method.name.startsWith("<lambda>") then
      Some(("python_lambda", endLine, endColumn))
    else if languageUpper.contains("RUBY") && method.name.startsWith("<lambda>") then
      if code.matches("(?s).*\\bdo(?:\\s*\\|[^|]*\\|)?.*") then Some(("ruby_do", endLine, endColumn))
      else if code.matches("(?s).*\\{\\s*\\|[^|]*\\|.*") then Some(("ruby_brace", endLine, endColumn))
      else None
    else None
  }
  def sourceCodeAnchor(method: Method, fallbackLine: Int, fallbackColumn: Int): (Int, Int) = {
    val prefix = firstLine(method.code).trim
    if prefix.isEmpty || prefix == "<empty>" then (fallbackLine, fallbackColumn)
    else
      sourceLines(nodePath(method)).flatMap { lines =>
        val codeLineCount = math.max(1, method.code.linesIterator.size)
        val firstCandidate = math.max(1, fallbackLine - codeLineCount)
        (firstCandidate to fallbackLine).reverse.flatMap { candidateLine =>
          val source = lines.lift(candidateLine - 1).getOrElse("")
          val column = source.indexOf(prefix)
          if column >= 0 then Some((candidateLine, column)) else None
        }.headOption
      }.getOrElse((fallbackLine, fallbackColumn))
  }
  // A scope candidate must be backed either by a direct CPG body block (C/C++/JS)
  // or by an actual `def` line (Python/Ruby). File/module and frontend wrapper methods
  // deliberately remain without a scope view.
  val directScopeContexts = profiler.timed("source_scope_contexts") { allMethodNodes.flatMap { method =>
    val line = optInt(method.lineNumber, 0)
    val column = optInt(method.columnNumber, 0)
    val path = nodePath(method)
    val directBlock = method.astChildren.isBlock.headOption
    val blockBoundary = directBlock.flatMap { block =>
      for
        endLine <- block.lineNumber.map(_.toInt)
        endColumn <- block.columnNumber.map(_.toInt)
        if block.code.trim.startsWith("{")
      yield (endLine, endColumn)
    }
    val sourceBacked = line > 0 && path != "<unknown>" && !method.isExternal
    val anonymous = if sourceBacked then anonymousBoundary(method, blockBoundary) else None
    val definitionBoundary =
      if sourceBacked && definitionDelimitedScopes && !isFileOrWrapperMethodName(method.name) && !method.name.startsWith("<lambda>")
      then sourceDefinitionBoundary(method, line)
      else None
    val prototype =
      sourceBacked && languageUpper.contains("NEWC") && !isFileOrWrapperMethodName(method.name) &&
        !method.name.startsWith("<lambda>") && blockBoundary.isEmpty && method.code.trim.endsWith(";")
    if anonymous.nonEmpty then
      val (boundary, endLine, endColumn) = anonymous.get
      val sourceName = if method.name.startsWith("<lambda>") then "" else method.name
      val (contextLine, contextColumn) =
        if boundary == "ruby_do" || boundary == "ruby_brace" then sourceCodeAnchor(method, line, column)
        else (line, column)
      Some((path, method.fullName) -> ScopeContext(
        path, method.fullName, sourceName, contextLine, contextColumn, endLine, endColumn,
        method.code, language, boundary, scopeContextType(method), "direct_scope"
      ))
    else if sourceBacked && blockDelimitedScopes && blockBoundary.nonEmpty && !isFileOrWrapperMethodName(method.name) then
      val (endLine, endColumn) = blockBoundary.get
      Some((path, method.fullName) -> ScopeContext(
        path, method.fullName, method.name, line, column, endLine, endColumn,
        method.code, language, "block", scopeContextType(method), "direct_scope"
      ))
    else if prototype then
      Some((path, method.fullName) -> ScopeContext(
        path, method.fullName, method.name, line, column,
        optInt(method.lineNumberEnd, line), optInt(method.columnNumberEnd, column),
        method.code, language, "prototype", scopeContextType(method), "direct_scope"
      ))
    else if definitionBoundary.nonEmpty then
      Some((path, method.fullName) -> ScopeContext(
        path, method.fullName, method.name, line, column, line, column,
        method.code, language, definitionBoundary.get,
        scopeContextType(method), "direct_scope"
      ))
    else None
  }}
  val directScopeContextsByMethod = directScopeContexts.toMap
  // Python may attach declarations to frontend adapter methods. Map only variants
  // that have exactly one real source-backed method on the same source line.
  val pythonCanonicalScopeAliases =
    if languageUpper.contains("PYTHON") then
      val canonicalByPathLine = directScopeContexts
        .filter(_._2.boundary == "python_def")
        .groupBy { case ((path, _), context) => (path, context.line) }
      allMethodNodes.flatMap { method =>
        val wrapper = method.name.contains("<metaClassAdapter>") ||
          Set("<fakeNew>", "<metaClassCallHandler>").contains(method.name)
        if !wrapper then None
        else
          val key = (nodePath(method), optInt(method.lineNumber, 0))
          val cleanedName = method.name.stripSuffix("<metaClassAdapter>")
          val candidates = canonicalByPathLine.getOrElse(key, Nil).filter { case (_, context) =>
            !method.name.contains("<metaClassAdapter>") || context.methodName == cleanedName
          }
          if candidates.size == 1 then
            Some((key._1, method.fullName) -> candidates.head._2.copy(boundary = "python_def_adapter"))
          else None
      }.toMap
    else Map.empty[(String, String), ScopeContext]
  val scopeContextsByMethod = directScopeContextsByMethod ++ pythonCanonicalScopeAliases
  val sourceMethodRanges = profiler.timed("source_method_ranges") { allMethodNodes.flatMap { method =>
    method.lineNumber.map(_.toInt).filter(_ > 0).filter(_ => !method.isExternal).flatMap { start =>
      val path = nodePath(method)
      // Preserve the historical ownership identity for frontend file scopes
      // (<global>, :program, <main>). Python <module> was already excluded.
      // These ranges may own declarations but never receive an emitted scope view.
      if path == "<unknown>" || method.name == "<module>" then None
      else
        val astLines = method.ast.l.flatMap(_.lineNumber.map(_.toInt)).filter(_ >= start)
        val end = if astLines.nonEmpty then astLines.max else start
        val syntheticPenalty = if isOwnershipSyntheticMethodName(method.name) && !method.name.startsWith("<lambda>") then 1 else 0
        Some((path, start, end, method.fullName, method.name, syntheticPenalty))
    }
  }}
  val sourceMethodRangesByPath = sourceMethodRanges.groupBy(_._1)
  val scopeByNodeId = scala.collection.mutable.Map.empty[Long, String]
  // Some frontends attach loop locals or nested nodes to a file scope. Resolve the
  // narrowest containing method. Anonymous and synthetic ranges remain ownership
  // barriers even though they deliberately have no emitted scope context.
  def scopeOf(n: AstNode): String = {
    val path = nodePath(n)
    val line = optInt(n.lineNumber, 0)
    sourceMethodRanges
      .filter { case (candidatePath, start, end, _, _, _) =>
        candidatePath == path && lineInside(line, start, end)
      }
      .sortBy { case (_, start, end, _, _, syntheticPenalty) => (end - start, syntheticPenalty) }
      .headOption
      .map(_._4)
      .getOrElse(path)
  }
  def cachedScopeOf(n: AstNode): String =
    scopeByNodeId.getOrElseUpdate(n.id, {
      val path = nodePath(n)
      val line = optInt(n.lineNumber, 0)
      sourceMethodRangesByPath.getOrElse(path, Nil)
        .filter { case (_, start, end, _, _, _) => lineInside(line, start, end) }
        .sortBy { case (_, start, end, _, _, syntheticPenalty) => (end - start, syntheticPenalty) }
        .headOption
        .map(_._4)
        .getOrElse(path)
    })
  val (callsBySourcePosition, callsByMethodLine) = profiler.timed("collection_call_indexes") {
    val byPosition = allCallNodes.groupBy { call =>
      (nodePath(call), cachedScopeOf(call), optInt(call.lineNumber, 0), optInt(call.columnNumber, 0))
    }
    val byMethodLine = allCallNodes.groupBy { call =>
      (cachedScopeOf(call), optInt(call.lineNumber, 0))
    }
    (byPosition, byMethodLine)
  }
  def collectionCallById(id: Long): Option[Call] = {
    profiler.increment("collection_call_id_lookups")
    callById.get(id)
  }
  def collectionCallsAt(path: String, method: String, line: Int, column: Int): List[Call] = {
    profiler.increment("collection_position_lookups")
    callsBySourcePosition.getOrElse((path, method, line, column), Nil)
  }
  // Scope views have their own position-aware ownership. This deliberately does
  // not alter scopeOf(), which remains the scientific ownership used by writes,
  // controls, and role predicates.
  val sourceScopeRanges = profiler.timed("source_scope_ranges") { allMethodNodes.flatMap { method =>
    val path = nodePath(method)
    scopeContextsByMethod.get((path, method.fullName)).flatMap { context =>
      method.lineNumber.map(_.toInt).filter(_ > 0).map { methodStartLine =>
        val startLine = math.min(methodStartLine, context.line)
        val startColumn = if startLine == context.line then context.column else optInt(method.columnNumber, 0)
        val endLine = optInt(method.lineNumberEnd, methodStartLine)
        val endColumn = optInt(method.columnNumberEnd, Int.MaxValue)
        (path, startLine, startColumn, endLine, endColumn, context)
      }
    }
  }}
  val sourceScopeRangesByPath = sourceScopeRanges.groupBy(_._1)
  def sourceScopeContextFor(declaration: VarDecl): Option[ScopeContext] = {
    sourceScopeRangesByPath.getOrElse(declaration.pos.path, Nil)
      .filter { case (_, startLine, startColumn, endLine, endColumn, context) =>
        val line = declaration.pos.line
        val column = declaration.pos.column
        val withinLines = lineInside(line, startLine, endLine)
        val afterStart = line > startLine || startColumn <= 0 || column <= 0 || column >= startColumn
        val beforeEnd = line < endLine || endColumn <= 0 || column <= 0 || column <= endColumn
        val definesSameLineCallable = declaration.kind == "local" && line == context.line &&
          column > 0 && context.column > 0 && column < context.column
        withinLines && afterStart && beforeEnd && !definesSameLineCallable
      }
      .sortBy { case (_, startLine, startColumn, endLine, endColumn, _) =>
        val lineSpan = endLine - startLine
        val columnSpan = if lineSpan == 0 then math.max(0, endColumn - startColumn) else Int.MaxValue
        (lineSpan, columnSpan, -startLine, -startColumn)
      }
      .headOption
      .map(_._6)
  }
  // Prefer Joern's AST block children for loop/control ranges; they are more reliable than parsing source text.
  def astBlockEnd(cs: ControlStructure, start: Int): Option[Int] = {
    val lines = cs.astChildren.isBlock.l
      .flatMap(_.ast.l)
      .flatMap(_.lineNumber.map(_.toInt))
      .filter(_ >= start)
    if lines.nonEmpty then Some(lines.max) else None
  }
  // Combine CPG code, AST blocks, and the source fallback into one inclusive range end.
  def controlStructureEnd(cs: ControlStructure, start: Int): Int = {
    val codeEnd = start + cs.code.count(_ == '\n')
    List(
      Some(codeEnd),
      astBlockEnd(cs, start),
      sourceIndentBlockEnd(nodePath(cs), start)
    ).flatten.max
  }

  // Require the declaration token to exist at Joern's source position.
  def hasSourceToken(n: AstNode, name: String): Boolean = {
    val line = optInt(n.lineNumber, 0)
    val quoted = java.util.regex.Pattern.quote(name)
    sourceLine(nodePath(n), line).exists(
      _.matches(s"(?s).*(?<![A-Za-z0-9_$$])$quoted(?![A-Za-z0-9_$$]).*")
    )
  }

  val isPython = language.toUpperCase.contains("PYTHON")
  case class SourceBindingNames(bindings: Set[String], memberBindings: Set[String], callables: Set[String])
  val sourceBindingNamesByPath = scala.collection.mutable.Map.empty[String, SourceBindingNames]
  def sourceBindingNames(path: String): SourceBindingNames =
    sourceBindingNamesByPath.getOrElseUpdate(path, {
      val identifierPattern = raw"[A-Za-z_$$][A-Za-z0-9_$$]*".r
      val assignmentOperator = "(?:\\*\\*=|//=|>>=|<<=|[+\\-*/%@&|^]=|:=|=(?!=))"
      val bindings = scala.collection.mutable.Set.empty[String]
      val memberBindings = scala.collection.mutable.Set.empty[String]
      val callables = scala.collection.mutable.Set.empty[String]
      sourceLines(path).getOrElse(Nil).foreach { text =>
        identifierPattern.findAllIn(text).toSet.foreach { name =>
          val quoted = java.util.regex.Pattern.quote(name)
          val simpleAssignment = text.matches(
            s"(?s)^\\s*$quoted\\s*(?::\\s*[^=]+)?\\s*$assignmentOperator.*"
          )
          val loopBinding = text.matches(
            s"(?s).*\\b(?:async\\s+)?for\\s+(?:\\(\\s*)?$quoted\\s+in\\b.*"
          )
          val aliasBinding = text.matches(
            s"(?s).*\\b(?:with\\b.*|except\\b.*)\\bas\\s+$quoted(?:\\s*[:,]|\\s*$$).*"
          )
          val memberAssignment = text.matches(
            s"(?s).*\\.\\s*$quoted\\s*$assignmentOperator.*"
          )
          if simpleAssignment || loopBinding || aliasBinding then bindings += name
          if memberAssignment then memberBindings += name
          val callable =
            text.matches(s"(?is)^\\s*(?:async\\s+)?def\\s+$quoted\\s*\\(.*") ||
            text.matches(s"(?is)^\\s*(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?function\\s+$quoted\\s*\\(.*") ||
            text.matches(s"(?is)^\\s*def\\s+$quoted(?:\\s|\\().*") ||
            text.matches(s"(?is)^\\s*class\\s+$quoted(?:\\s|\\(|:).*")
          if callable then callables += name.toLowerCase
        }
      }
      SourceBindingNames(bindings.toSet, memberBindings.toSet, callables.toSet)
    })

  // Python frontends may synthesize LOCAL/MEMBER nodes for every callee. Require a
  // source-level binding form instead of accepting a name merely because `name(...)`
  // occurs in the file. This deliberately models simple, inspectable introductions.
  def hasPythonBinding(path: String, name: String, allowMemberTarget: Boolean): Boolean = {
    val names = sourceBindingNames(path)
    names.bindings.contains(name) || (allowMemberTarget && names.memberBindings.contains(name))
  }

  // Ruby lowers `for` targets to declaration-backed `<iterator>` locals whose
  // LOCAL node lacks a source location. Recover the introduction from the first
  // source-backed IDENTIFIER that REFers to that exact LOCAL; lexical names alone
  // never create a variable identity.
  val recoveredRubyIteratorIntroductionByLocalId: Map[Long, Identifier] =
    if languageUpper.contains("RUBY") then allIdentifierNodes.flatMap { identifier =>
      identifier.refsTo.l.collect {
        case local: Local
            if local.lineNumber.isEmpty && identifier.lineNumber.exists(_ > 0) &&
              sourceLine(nodePath(identifier), identifier.lineNumber.get.toInt)
                .exists(line => containsName(line, identifier.name)) =>
          local.id -> identifier
      }
    }.groupBy(_._1).flatMap { case (localId, candidates) =>
      candidates.map(_._2).sortBy(identifier => (
        optInt(identifier.lineNumber, Int.MaxValue), optInt(identifier.columnNumber, Int.MaxValue), identifier.id
      )).headOption.map(localId -> _)
    }
    else Map.empty

  // Keep only source-backed locals; some frontends emit LOCAL nodes for functions and synthetic bindings.
  def isSourceVariableLocal(n: Local): Boolean = {
    val validIdentifier = n.name.matches("[A-Za-z_$][A-Za-z0-9_$]*")
    val path = nodePath(n)
    val declaredCallableOrType = sourceBindingNames(path).callables.contains(n.name.toLowerCase)
    val hasSourceIntroduction =
      !isPython || hasPythonBinding(path, n.name, allowMemberTarget = false)
    val directSourceToken = hasSourceToken(n, n.name)
    val recoveredRubyIterator = recoveredRubyIteratorIntroductionByLocalId.contains(n.id)
    validIdentifier && !declaredCallableOrType && (directSourceToken || recoveredRubyIterator) &&
      (hasSourceIntroduction || recoveredRubyIterator)
  }
  // Start from CPG declarations instead of raw identifiers to avoid call-site identifiers.
  val sourceLocalNodes = profiler.timed("declarations") { cpg.local.l.filter(isSourceVariableLocal) }
  def isAnnotableSourceParameter(p: MethodParameterIn): Boolean = {
    val name = Option(p.name).getOrElse("").trim
    val ordinaryName = name.matches("[A-Za-z_$][A-Za-z0-9_$]*") &&
      !name.matches(raw"(?i)(?:<.*>|arg(?:ument)?_?\d+|param(?:eter)?_?\d+|anonymous.*)")
    val position = posOf(p, name)
    ordinaryName && !Set("this", "self").contains(name) &&
      position.path != "<unknown>" && position.line > 0 && hasSourceToken(p, name)
  }
  val sourceParameterNodes = cpg.parameter.l.filter(isAnnotableSourceParameter)
  val locals = sourceLocalNodes.map { n =>
    recoveredRubyIteratorIntroductionByLocalId.get(n.id) match
      case Some(identifier) =>
        VarDecl(n.id, n.name, "local", cachedScopeOf(identifier), posOf(identifier, n.name))
      case None => VarDecl(n.id, n.name, "local", cachedScopeOf(n), posOf(n, n.name))
  }
  val params = sourceParameterNodes.map { n =>
    VarDecl(n.id, n.name, "parameter", cachedScopeOf(n), posOf(n, n.name))
  }
  val candidateDeclarations = (locals ++ params).filter(d => d.pos.path != "<unknown>" && d.pos.line > 0)
  // MEMBER and FIELD_IDENTIFIER are consumed once, so retaining their full lists
  // only increases peak memory without avoiding a traversal.
  val sourceMemberNodes = profiler.timed("member_declarations") { cpg.member.l.filter { n =>
    !isPython || (
      hasSourceToken(n, n.name) &&
      hasPythonBinding(nodePath(n), n.name, allowMemberTarget = true)
    )
  }}
  val memberDeclarations = sourceMemberNodes.map { n =>
    VarDecl(n.id, n.name, "member", cachedScopeOf(n), posOf(n, n.name))
  }.filter(d => d.pos.path != "<unknown>" && d.pos.line > 0)
  val memberNodeById = sourceMemberNodes.map(n => n.id -> n).toMap
  val declarationTypeById = (
    sourceLocalNodes.map(node => node.id -> node.typeFullName) ++
    sourceParameterNodes.map(node => node.id -> node.typeFullName) ++
    sourceMemberNodes.map(node => node.id -> node.typeFullName)
  ).toMap
  val memberOwnerById = sourceMemberNodes.flatMap { member =>
    scala.util.Try(member.astParent).toOption.collect {
      case owner: TypeDecl => member.id -> (owner.name, owner.fullName)
    }
  }.toMap
  val memberByPathName = memberDeclarations.groupBy(d => (d.pos.path, d.name))

  case class CanonicalMemberIdentity(canonicalMemberId: Long, pseudoLocalId: Long)
  val earlyMethodOwnerByFullName = allMethodNodes.flatMap { method =>
    scala.util.Try(method.astParent).toOption.collect {
      case owner: TypeDecl => method.fullName -> owner.fullName
    }
  }.toMap
  val canonicalMemberIdentityByPseudoLocalId: Map[Long, CanonicalMemberIdentity] =
    candidateDeclarations.iterator.filter(_.kind == "local").flatMap { local =>
      val method = allMethodNodes.find(_.fullName == local.method)
      val syntheticOwner = method.filter(m => Set("<body>", "<clinit>").contains(m.name))
        .flatMap(m => earlyMethodOwnerByFullName.get(m.fullName))
      val candidates = memberByPathName.getOrElse((local.pos.path, local.name), Nil).filter { member =>
        member.pos.line == local.pos.line && member.pos.column == local.pos.column &&
          syntheticOwner.exists(owner => memberOwnerById.get(member.id).exists(_._2 == owner))
      }
      candidates match
        case member :: Nil => Some(local.id -> CanonicalMemberIdentity(member.id, local.id))
        case _ => None
    }.toMap
  def canonicalDeclarationId(id: Long): Long =
    canonicalMemberIdentityByPseudoLocalId.get(id).map(_.canonicalMemberId).getOrElse(id)
  val declarations = candidateDeclarations.filterNot(d => canonicalMemberIdentityByPseudoLocalId.contains(d.id))
  val declById = declarations.map(d => d.id -> d).toMap
  val declarationsByMethodName = declarations.groupBy(declaration => (declaration.method, declaration.name))
  val variableNames = declarations.map(_.name).toSet
  val standardVariableNames = variableNames.filter(_.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
  val nonStandardVariableNames = variableNames -- standardVariableNames
  def sourceNamesIn(code: String, excludedName: String): Set[String] = {
    val candidates = raw"[A-Za-z_$$][A-Za-z0-9_$$]*".r.findAllIn(code).flatMap { token =>
      val starts = token.indices.filter(index => index == 0 || token.charAt(index - 1) == '$')
      val ends = (1 to token.length).filter(index => index == token.length || token.charAt(index) == '$')
      starts.flatMap(start => ends.filter(_ > start).map(end => token.substring(start, end)))
    }.toSet
    val standardMatches = candidates.intersect(standardVariableNames).filter(name => containsName(code, name))
    val nonStandardMatches = nonStandardVariableNames.filter(name => containsName(code, name))
    (standardMatches ++ nonStandardMatches) - excludedName
  }
  def assignmentPredictability(operator: String, rhsCode: String, name: String): (Boolean, Boolean) =
    if operator != "<operator>.assignment" then (true, false)
    else
      val predictable = predictableCode(rhsCode, variableNames - name)
      (predictable, !predictable)

  // Merge declaration tokens with all identifier references resolved back to those declarations.
  val declarationOccurrences = declarations.map(d => Occurrence(d.id, d.name, d.method, d.pos, isDeclaration = true))
  val identifierOccurrences = profiler.timed("occurrences") { allIdentifierNodes.flatMap { id =>
    id.refsTo.l.collect {
      case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
        Occurrence(canonicalDeclarationId(l.id), id.name, cachedScopeOf(id), posOf(id, id.name), isDeclaration = false)
      case p: MethodParameterIn if declById.contains(p.id) =>
        Occurrence(p.id, id.name, cachedScopeOf(id), posOf(id, id.name), isDeclaration = false)
    }
  }.filter(o => o.pos.path != "<unknown>" && o.pos.line > 0) }
  val occurrencesByDecl = (declarationOccurrences ++ identifierOccurrences)
    .groupBy(o => o.declId)
    .view.mapValues(_.distinctBy(o => (o.pos.path, o.pos.line, o.pos.column, o.name, o.isDeclaration)).sortBy(o => (o.pos.line, o.pos.column)))
    .toMap
  val memberDeclarationOccurrences = memberDeclarations.map(d => Occurrence(d.id, d.name, d.method, d.pos, isDeclaration = true))
  val fieldOccurrences = profiler.timed("field_occurrences") { allFieldIdentifierNodes.flatMap { id =>
    memberByPathName.getOrElse((nodePath(id), id.code), Nil).flatMap { member =>
      val pos = posOf(id, member.name)
      val duplicateDeclarationToken = pos.line == member.pos.line && pos.column < member.pos.column
      if duplicateDeclarationToken then None
      else Some(Occurrence(member.id, member.name, cachedScopeOf(id), pos, isDeclaration = false))
    }
  }.filter(o => o.pos.path != "<unknown>" && o.pos.line > 0) }
  val canonicalMemberIdentifierOccurrences = identifierOccurrences.filter(o => memberNodeById.contains(o.declId))
  val fieldOccurrencesByDecl = (memberDeclarationOccurrences ++ fieldOccurrences ++ canonicalMemberIdentifierOccurrences)
    .groupBy(o => o.declId)
    .view.mapValues(_.distinctBy(o => (o.pos.path, o.pos.line, o.pos.column, o.name, o.isDeclaration)).sortBy(o => (o.pos.line, o.pos.column)))
    .toMap

  // Loop ranges drive most dynamic concepts; block children recover bodies when Joern code is header-only.
  val loopRanges = allControlStructureNodes
    .filter(cs => isLoopType(cs.controlStructureType))
    .flatMap { cs =>
      cs.lineNumber.map(_.toInt).map { start =>
        val end = controlStructureEnd(cs, start)
        (cachedScopeOf(cs), start, end, s"${cachedScopeOf(cs)}:$start:$end", cs.code)
      }
    }
  val loopRangesByMethod = loopRanges.groupBy(_._1)
  // Control ranges identify assignments gated by a condition, e.g. most-wanted candidates.
  val controlRanges = allControlStructureNodes
    .filter(cs => isControlType(cs.controlStructureType))
    .flatMap { cs =>
      cs.lineNumber.map(_.toInt).map { start =>
        val end = controlStructureEnd(cs, start)
        val header = sourceLine(nodePath(cs), start).getOrElse(firstLine(cs.code))
        (cachedScopeOf(cs), start, end, header)
      }
    }
  val controlRangesByMethod = controlRanges.groupBy(_._1)
  val followerGuardControlsByMethod = allControlStructureNodes
    .filter(cs => isControlType(cs.controlStructureType))
    .flatMap(cs => cs.lineNumber.map(_.toInt).map { start =>
      (cachedScopeOf(cs), start, controlStructureEnd(cs, start), cs)
    }).groupBy(_._1)
  // Rich control headers are annotation facts only; role predicates keep using the
  // established loopRanges/controlRanges tuples above.
  val controlContextRanges = allControlStructureNodes
    .filter(cs => isLoopType(cs.controlStructureType) || isControlType(cs.controlStructureType))
    .flatMap { cs =>
      cs.lineNumber.map(_.toInt).map { start =>
        val header = sourceLine(nodePath(cs), start).getOrElse(firstLine(cs.code))
        val trimmed = header.trim.toLowerCase
        val subtype =
          if trimmed.startsWith("for ") || trimmed.startsWith("async for ") then "for"
          else if trimmed.startsWith("while ") then "while"
          else if trimmed.startsWith("if ") then "if"
          else if trimmed.startsWith("elif ") then "elif"
          else if trimmed.startsWith("switch ") then "switch"
          else if trimmed.startsWith("try") then "try"
          else if trimmed.startsWith("except") || trimmed.startsWith("catch") then "catch"
          else cs.controlStructureType.toLowerCase
        val contextType =
          if isLoopType(cs.controlStructureType) then "loop"
          else if Set("if", "elif").contains(subtype) then "conditional"
          else if subtype == "try" || subtype == "catch" then "try"
          else if subtype == "switch" then "switch"
          else "other"
        (
          cachedScopeOf(cs),
          nodePath(cs),
          start,
          controlStructureEnd(cs, start),
          header,
          contextType,
          subtype
        )
      }
    }
  val controlContextRangesByMethod = controlContextRanges.groupBy(_._1)
  // Use original loop headers when available so Python `for x in xs` is visible.
  val loopConditions = allControlStructureNodes
    .filter(cs => isLoopType(cs.controlStructureType))
    .map { cs =>
      val line = optInt(cs.lineNumber, -1)
      val header = sourceLine(nodePath(cs), line).getOrElse(firstLine(cs.code))
      (cachedScopeOf(cs), header)
    }
  val loopConditionsByMethod = loopConditions.groupMap(_._1)(_._2)

  // Return the loop enclosing a write so writes can be tied to a specific traversal.
  def loopAt(method: String, line: Int): Option[(String, Int, Int, String)] =
    loopRangesByMethod.getOrElse(method, Nil)
      .filter { case (_, s, e, _, _) => lineInside(line, s, e) }
      .sortBy { case (_, s, e, _, _) => e - s }
      .headOption
      .map { case (_, s, e, key, code) => (key, s, e, code) }
  // Detect whether a write is conditionally executed.
  def insideControl(method: String, line: Int): Boolean =
    controlRangesByMethod.getOrElse(method, Nil)
      .exists { case (_, s, e, _) => lineInside(line, s, e) }
  // Return the nearest condition header guarding a write.
  def controlAt(method: String, line: Int): Option[String] =
    controlRangesByMethod.getOrElse(method, Nil)
      .filter { case (_, s, e, _) => lineInside(line, s, e) }
      .sortBy { case (_, s, e, _) => e - s }
      .headOption
      .map(_._4)

}

def buildExtractionContext(graph: Cpg, sourceRoot: String): ExtractionContextIndex =
  new ExtractionContextIndex(graph, sourceRoot)
