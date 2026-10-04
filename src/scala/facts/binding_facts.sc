import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply

final class BindingFactIndex(val context: ExtractionContextIndex) {
  import context.*
  // Joern models assignments, compound assignments, and increments as calls.
  val writeOperators = Set(
    "<operator>.assignment", "<operator>.assignmentPlus", "<operator>.assignmentMinus",
    "<operator>.assignmentMultiplication", "<operator>.assignmentDivision",
    "<operator>.preIncrement", "<operator>.postIncrement", "<operator>.preDecrement", "<operator>.postDecrement"
  )
  // Preserve the original CPG call order because equal-position frontend writes
  // use stable first-write fallbacks in existing role predicates.
  val writeCalls = allCallNodes.filter(call => writeOperators.contains(call.name))
  val assignmentCalls = callsByName.getOrElse("<operator>.assignment", Nil)
  val assignmentCallsByMethod = assignmentCalls.groupBy(cachedScopeOf)
  // Operators describe expression plumbing; only named calls represent returned function/method values.
  def rhsHasMethodCall(args: List[Expression]): Boolean =
    args.drop(1).exists(_.ast.isCall.l.exists { c =>
      !c.name.startsWith("<operator>.") && !Set("<init>", "<alloc>").contains(c.name)
    })
  // A follower RHS must be exactly one resolved local/parameter, not an expression containing one.
  def directSourceDeclId(args: List[Expression]): Option[Long] =
    args.drop(1) match
      case List(id: Identifier) =>
        id.refsTo.l.collectFirst {
          case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
            canonicalDeclarationId(l.id)
          case p: MethodParameterIn if declById.contains(p.id) => p.id
        }
      case _ => None
  // Declaration initializers are only recovered when the source line has declaration syntax before the name.
  def hasDeclarationSyntaxBeforeName(path: String, line: Int, name: String): Boolean =
    sourceLine(path, line).exists { text =>
      val quoted = java.util.regex.Pattern.quote(name)
      val pattern = (s"(?<![A-Za-z0-9_$$])$quoted(?![A-Za-z0-9_$$])\\s*=\\s*(?!=)").r
      pattern.findFirstMatchIn(text).exists { m =>
        val prefix = text.substring(0, m.start).trim
        val rejectedControlPrefix = prefix.matches("(?is).*\\b(?:if|while|for|switch|return|else)\\s*\\(?\\s*$")
        prefix.nonEmpty && !rejectedControlPrefix
      }
    }
  // Normalize all assignment-like calls into WriteInfo records for later predicates.
  val assignmentWrites = profiler.timed("binding_writes") { writeCalls.flatMap { call =>
    val args = call.argument.l
    val lhs = args.headOption
    val directWrite = lhs.exists {
      case _: Identifier => true
      case _ => false
    }
    // Only a bare identifier LHS writes the variable itself; field/index writes mutate contained state.
    val lhsIdentifiers = lhs.toList.collect { case id: Identifier => id }
    lhsIdentifiers.flatMap { lhs =>
      lhs.refsTo.l.collect {
        case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
          val rhsCode = args.drop(1).map(_.code).mkString(" ")
          val (predictable, nonPredictable) = assignmentPredictability(call.name, rhsCode, lhs.name)
          val code = call.code
          val method = cachedScopeOf(call)
          val line = optInt(call.lineNumber, optInt(lhs.lineNumber, 0))
          val column = optInt(call.columnNumber, optInt(lhs.columnNumber, 0))
          val loop = loopAt(method, line)
          val targetId = canonicalDeclarationId(l.id)
          val declarationInitializer = declById.get(l.id).exists(d =>
            d.kind == "local" && d.pos.line == line && hasDeclarationSyntaxBeforeName(d.pos.path, line, d.name)
          ) || canonicalMemberIdentityByPseudoLocalId.get(l.id).exists(identity =>
            memberDeclarations.find(_.id == identity.canonicalMemberId).exists(_.pos.line == line)
          )
          WriteInfo(targetId, lhs.name, method, line, column, code, call.name,
            selfRef = call.name != "<operator>.assignment" || containsName(rhsCode, lhs.name),
            predictable = predictable,
            nonPredictable = nonPredictable,
            insideLoop = loop.nonEmpty,
            insideControl = insideControl(method, line),
            literalValue = if call.name == "<operator>.assignment" then twoValuedLiteral(rhsCode) else None,
            sourceNames = sourceNamesIn(rhsCode, lhs.name),
            loopKey = loop.map(_._1),
            directWrite = directWrite,
            declarationInitializer = declarationInitializer,
            rhsHasMethodCall = rhsHasMethodCall(args),
            directSourceDeclId = directSourceDeclId(args),
            eventId = call.id,
            cfgNodeId = Some(call.id),
            definitionNodeId = Some(lhs.id))
        case p: MethodParameterIn if declById.contains(p.id) =>
          val rhsCode = args.drop(1).map(_.code).mkString(" ")
          val (predictable, nonPredictable) = assignmentPredictability(call.name, rhsCode, lhs.name)
          val code = call.code
          val method = cachedScopeOf(call)
          val line = optInt(call.lineNumber, optInt(lhs.lineNumber, 0))
          val column = optInt(call.columnNumber, optInt(lhs.columnNumber, 0))
          val loop = loopAt(method, line)
          WriteInfo(p.id, lhs.name, method, line, column, code, call.name,
            selfRef = call.name != "<operator>.assignment" || containsName(rhsCode, lhs.name),
            predictable = predictable,
            nonPredictable = nonPredictable,
            insideLoop = loop.nonEmpty,
            insideControl = insideControl(method, line),
            literalValue = if call.name == "<operator>.assignment" then twoValuedLiteral(rhsCode) else None,
            sourceNames = sourceNamesIn(rhsCode, lhs.name),
            loopKey = loop.map(_._1),
            directWrite = directWrite,
            declarationInitializer = false,
            rhsHasMethodCall = rhsHasMethodCall(args),
            directSourceDeclId = directSourceDeclId(args),
            eventId = call.id,
            cfgNodeId = Some(call.id),
            definitionNodeId = Some(lhs.id))
      }
    }
  }}
  val assignmentWritesByDecl = assignmentWrites.groupBy(_.declId)

  // Field writes are handled only for one-way flags so member state does not affect other concepts.
  val fieldAssignmentWrites = profiler.timed("field_binding_writes") { writeCalls.flatMap { call =>
    val args = call.argument.l
    val lhs = args.headOption
    val rhsCode = args.drop(1).map(_.code).mkString(" ")
    val code = call.code
    val method = cachedScopeOf(call)
    val line = optInt(call.lineNumber, 0)
    val column = optInt(call.columnNumber, 0)
    val loop = loopAt(method, line)
    lhs.toList.flatMap(_.ast.isFieldIdentifier.l).flatMap { field =>
      memberByPathName.getOrElse((nodePath(field), field.code), Nil).map { member =>
        val (predictable, nonPredictable) = assignmentPredictability(call.name, rhsCode, member.name)
        val declarationInitializer = member.pos.line == line
        WriteInfo(member.id, member.name, method, line, column, code, call.name,
          selfRef = call.name != "<operator>.assignment" || containsName(rhsCode, member.name),
          predictable = predictable,
          nonPredictable = nonPredictable,
          insideLoop = loop.nonEmpty,
          insideControl = insideControl(method, line),
          literalValue = if call.name == "<operator>.assignment" then twoValuedLiteral(rhsCode) else None,
          sourceNames = sourceNamesIn(rhsCode, member.name),
          loopKey = loop.map(_._1),
          directWrite = true,
          declarationInitializer = declarationInitializer,
          rhsHasMethodCall = rhsHasMethodCall(args),
          directSourceDeclId = directSourceDeclId(args),
          eventId = call.id,
          cfgNodeId = Some(call.id),
          definitionNodeId = Some(field.id))
      }
    }
  }}

  val methodByFullName = allMethodNodes.map(method => method.fullName -> method).toMap
  val methodOwnerByFullName = allMethodNodes.flatMap { method =>
    scala.util.Try(method.astParent).toOption.collect {
      case owner: TypeDecl => method.fullName -> owner.fullName
    }
  }.toMap
  val methodsByOwnerFullName = allMethodNodes.flatMap(method =>
    methodOwnerByFullName.get(method.fullName).map(owner => owner -> method)
  ).groupMap(_._1)(_._2)
  def methodBelongsToOwner(method: String, ownerFullName: String): Boolean =
    methodOwnerByFullName.get(method).contains(ownerFullName) ||
      method.startsWith(ownerFullName + ".") || method.startsWith(ownerFullName + "::")
  def receiverTypeNames(lhs: Expression): Set[String] =
    lhs.ast.isIdentifier.l.flatMap(_.refsTo.l).flatMap {
      case local: Local => Some(local.typeFullName)
      case parameter: MethodParameterIn => Some(parameter.typeFullName)
      case _ => None
    }.toSet
  def ownerMatchesType(owner: (String, String), typeName: String): Boolean = {
    val normalized = typeName.replaceAll("[^A-Za-z0-9_$.:]", "")
    normalized == owner._1 || normalized.endsWith(s".${owner._1}") ||
      normalized.endsWith(s"::${owner._1}") || normalized.contains(owner._2)
  }
  // Resolve a field access to one declaration only. Owner metadata wins; receiver
  // type is a fallback for C-style pointer access outside a class method.
  def resolvedMemberFor(field: FieldIdentifier, lhs: Expression, method: String): Option[VarDecl] = {
    val candidates = memberByPathName.getOrElse((nodePath(field), field.code), Nil)
    if candidates.size == 1 then candidates.headOption
    else
      val methodOwner = methodOwnerByFullName.get(method)
      val receiverTypes = receiverTypeNames(lhs)
      val compatible = candidates.filter { candidate =>
        memberOwnerById.get(candidate.id).exists { owner =>
          methodOwner.contains(owner._2) || methodBelongsToOwner(method, owner._2) ||
            receiverTypes.exists(ownerMatchesType(owner, _))
        }
      }
      if compatible.size == 1 then compatible.headOption else None
  }
  // Canonical cross-frontend initialization-method predicate shared by Fixed
  // Value, binding partitioning, and persistent-member roles.
  def isInitializationMethod(method: Method, owner: (String, String)): Boolean = {
    val name = method.name.toLowerCase
    !method.isExternal && !method.name.contains("<metaClassAdapter>") &&
      methodBelongsToOwner(method.fullName, owner._2) && (
        method.name == owner._1 ||
        Set("<init>", "constructor", "__init__", "__new__", "initialize").contains(name)
      )
  }
  def isConstructor(method: Method, owner: (String, String)): Boolean =
    isInitializationMethod(method, owner)
  def isInitializationMethodFullName(methodFullName: String, owner: (String, String)): Boolean =
    methodByFullName.get(methodFullName).exists(isInitializationMethod(_, owner)) || {
      val normalized = methodFullName.toLowerCase
      methodBelongsToOwner(methodFullName, owner._2) &&
        Set(".__init__", ".__new__", ".initialize", ".constructor", ".<init>", "::__init__",
          "::__new__", "::initialize", "::constructor", "::<init>")
          .exists(normalized.contains)
    }
  def isDeclarationBody(method: Method): Boolean =
    Set("<body>", "<clinit>").contains(method.name)

  // Fixed Value uses stricter member ownership than the historical one-way flag
  // path. This does not alter existing member views or one-way classification.
  val fixedMemberBindingWrites = profiler.timed("fixed_member_binding_writes") { writeCalls.flatMap { call =>
    val args = call.argument.l
    val lhs = args.headOption
    // Unlike line-range ownership, the direct CPG METHOD distinguishes overloaded
    // constructors that share one source line.
    val method = scala.util.Try(call.method.fullName).toOption.filter(_.nonEmpty).getOrElse(cachedScopeOf(call))
    val line = optInt(call.lineNumber, 0)
    val column = optInt(call.columnNumber, 0)
    val rhsCode = args.drop(1).map(_.code).mkString(" ")
    val loop = loopAt(method, line)
    lhs.toList.filterNot(_.code.contains("[")).flatMap { target =>
      target.ast.isFieldIdentifier.l.flatMap { field =>
        resolvedMemberFor(field, target, method).map { member =>
          val declarationBodyInitializer = methodByFullName.get(method).exists(isDeclarationBody) && member.pos.line == line
          val (predictable, nonPredictable) = assignmentPredictability(call.name, rhsCode, member.name)
          WriteInfo(member.id, member.name, method, line, column, call.code, call.name,
            selfRef = call.name != "<operator>.assignment" || containsName(rhsCode, member.name),
            predictable = predictable,
            nonPredictable = nonPredictable,
            insideLoop = loop.nonEmpty,
            insideControl = insideControl(method, line),
            literalValue = if call.name == "<operator>.assignment" then twoValuedLiteral(rhsCode) else None,
            sourceNames = sourceNamesIn(rhsCode, member.name),
            loopKey = loop.map(_._1),
            directWrite = true,
            declarationInitializer = declarationBodyInitializer,
            rhsHasMethodCall = rhsHasMethodCall(args),
            directSourceDeclId = directSourceDeclId(args),
            eventId = call.id,
            cfgNodeId = Some(call.id),
            definitionNodeId = Some(field.id))
        }
      }
    }
  }}

  val fixedMemberDeclarationInitializerWrites = memberDeclarations.flatMap { member =>
    memberNodeById.get(member.id).flatMap { node =>
      val quoted = java.util.regex.Pattern.quote(member.name)
      val pattern = (s"(?s)^\\s*$quoted\\s*=\\s*(.+)$$").r
      node.code match
        case pattern(rhs) =>
          val predictable = predictableCode(rhs, variableNames - member.name)
          Some(WriteInfo(member.id, member.name, member.method, member.pos.line, member.pos.column,
            sourceLine(member.pos.path, member.pos.line).map(_.trim).getOrElse(node.code),
            "<operator>.assignment", selfRef = containsName(rhs, member.name),
            predictable = predictable,
            nonPredictable = !predictable,
            insideLoop = false, insideControl = false, literalValue = twoValuedLiteral(rhs),
            sourceNames = sourceNamesIn(rhs, member.name),
            loopKey = None, directWrite = true, declarationInitializer = true,
            rhsHasMethodCall = false, directSourceDeclId = None, eventId = -member.id,
            cfgNodeId = None, definitionNodeId = None))
        case _ => None
    }
  }

  // Some frontends keep local declaration initializers on the LOCAL node only.
  def localInitializerRhs(d: VarDecl): Option[String] =
    sourceLine(d.pos.path, d.pos.line).flatMap { line =>
      val name = java.util.regex.Pattern.quote(d.name)
      val pattern = (s"(?<![A-Za-z0-9_$$])$name(?![A-Za-z0-9_$$])\\s*=\\s*(?!=)([^,;]+)").r
      pattern.findFirstMatchIn(line).filter(_ => hasDeclarationSyntaxBeforeName(d.pos.path, d.pos.line, d.name)).map(_.group(1).trim)
    }

  // Recover declaration-time writes such as `bool done = false` when Joern omits an assignment call.
  val localInitializerWrites = declarations.filter(_.kind == "local").flatMap { d =>
    localInitializerRhs(d).map { rhsCode =>
      val method = d.method
      val line = d.pos.line
      val loop = loopAt(method, line)
      val code = sourceLine(d.pos.path, d.pos.line).map(_.trim).getOrElse(d.pos.code)
      val predictable = predictableCode(rhsCode, variableNames - d.name)
      WriteInfo(d.id, d.name, method, line, d.pos.column, code, "<operator>.assignment",
        selfRef = containsName(rhsCode, d.name),
        predictable = predictable,
        nonPredictable = !predictable,
        insideLoop = loop.nonEmpty,
        insideControl = insideControl(method, line),
        literalValue = twoValuedLiteral(rhsCode),
        sourceNames = sourceNamesIn(rhsCode, d.name),
        loopKey = loop.map(_._1),
        directWrite = true,
        declarationInitializer = true,
        rhsHasMethodCall = false,
        directSourceDeclId = None,
        eventId = -d.id,
        cfgNodeId = None,
        definitionNodeId = None)
    }
  }

  // Length-changing calls make a collection a container rather than just an organizer.
  val collectionLengthMutationNames =
    if language.contains("JS") || language.contains("JAVA_SCRIPT") then
      Set("pop", "push", "shift", "splice", "unshift")
    else if language.contains("PYTHON") then
      Set("append", "clear", "extend", "insert", "pop", "remove")
    else
      Set(
        "add", "addall", "append", "clear", "delete", "dequeue", "enqueue", "erase", "extend",
        "insert", "offer", "poll", "pop", "push", "push_back", "put", "putifabsent",
        "remove", "removeall", "removeat", "setdefault", "shift", "splice", "unshift"
      )
  val inPlaceMutationNames = Set("sort", "reverse", "shuffle", "rotate", "partition")
  // Match receiver-style mutations even when language frontends encode them differently.
  def looksLikeReceiverMutation(c: Call, names: Set[String]): Boolean = {
    val n = c.name.toLowerCase
    val code = c.code.toLowerCase
    names.exists(name =>
      n == name || code.matches(s"(?s).*([.>]|::)\\s*${java.util.regex.Pattern.quote(name)}\\s*\\(.*")
    )
  }
  val (lengthMutationCalls, inPlaceMutationCalls) = profiler.timed("mutation_call_index") {
    (
      allCallNodes.filter(c => looksLikeReceiverMutation(c, collectionLengthMutationNames)),
      allCallNodes.filter(c => looksLikeReceiverMutation(c, inPlaceMutationNames))
    )
  }
  // Return the declaration ids of variables used as the call receiver.
  def receiverVarIds(call: Call): Set[Long] = {
    val receiver = call.argument.l.headOption
    val receiverCode = receiver.map(_.code.trim).getOrElse("")
    val directReceiver = receiverCode.matches("[A-Za-z_$][A-Za-z0-9_$]*")
    val beginReceiver = receiverCode.matches("[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*begin\\s*\\(\\s*\\)")
    if !directReceiver && !beginReceiver then Set.empty
    else receiver
      .flatMap(_.ast.isIdentifier.l.sortBy(id => optInt(id.columnNumber, Int.MaxValue)).headOption)
      .toList
      .flatMap(_.refsTo.l.collect {
        case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
          canonicalDeclarationId(l.id)
        case p: MethodParameterIn if declById.contains(p.id) => p.id
      })
      .toSet
  }
  val lengthMutationIds = lengthMutationCalls.flatMap(receiverVarIds).toSet
  // Static calls such as `std::sort(v.begin(), v.end())` mutate the first collection argument.
  val inPlaceMutationIds = inPlaceMutationCalls.flatMap(receiverVarIds).toSet
  // Indexed writes mutate an existing slot/key, e.g. `items[i] = value`.
  val indexedValueMutationIds = callsByName.getOrElse("<operator>.assignment", Nil).flatMap { call =>
    call.argument.l.headOption.toList
      .filter(lhs => lhs.code.matches("(?s).*\\[[^\\]]+\\].*"))
      .flatMap(_.ast.isIdentifier.l.sortBy(id => optInt(id.columnNumber, Int.MaxValue)).headOption)
      .flatMap(_.refsTo.l.collect {
        case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
          canonicalDeclarationId(l.id)
        case p: MethodParameterIn if declById.contains(p.id) => p.id
      })
  }.toSet

  // Record contained-state writes separately so binding-oriented concept predicates remain unchanged.
  val assignmentStateMutations = profiler.timed("assignment_state_mutations") { writeCalls.flatMap { call =>
    call.argument.l.headOption.toList
      .filter(!_.isInstanceOf[Identifier])
      .flatMap(_.ast.isIdentifier.l.sortBy(id => optInt(id.columnNumber, Int.MaxValue)).headOption)
      .flatMap(_.refsTo.l.collect {
        case l: Local if declById.contains(l.id) || canonicalMemberIdentityByPseudoLocalId.contains(l.id) =>
          canonicalDeclarationId(l.id)
        case p: MethodParameterIn if declById.contains(p.id) => p.id
      })
      .map(id => StateMutation(
        id,
        cachedScopeOf(call),
        optInt(call.lineNumber, 0),
        optInt(call.columnNumber, 0),
        call.code,
        if call.argument.l.headOption.exists(_.code.matches("(?s).*\\[[^\\]]+\\].*")) then "indexed_write" else "other",
        call.name
      ))
  }}
  val receiverStateMutations = profiler.timed("receiver_state_mutations") { (lengthMutationCalls ++ inPlaceMutationCalls).flatMap { call =>
    val normalizedName = (collectionLengthMutationNames ++ inPlaceMutationNames)
      .find(name => looksLikeReceiverMutation(call, Set(name)))
      .getOrElse("in_place_mutation")
    receiverVarIds(call).map(id => StateMutation(
      id,
      cachedScopeOf(call),
      optInt(call.lineNumber, 0),
      optInt(call.columnNumber, 0),
      call.code,
      normalizedName,
      call.name
    ))
  }}
  // Ruby models `items << value` as an operator instead of a receiver call.
  // This remains a Fixed Value decision fact so existing views and collection
  // role predicates are not broadened by this refinement.
  val fixedRubyShiftMutationIds =
    if languageUpper.contains("RUBY") then
      callsByName.getOrElse("<<", Nil).flatMap(receiverVarIds).toSet
    else Set.empty[Long]
  val stateMutationsByDecl = profiler.timed("state_mutations") { (assignmentStateMutations ++ receiverStateMutations)
    .distinctBy(m => (m.declId, m.method, m.line, m.column, m.mutationKind))
    .groupBy(_.declId)
    .view.mapValues(_.sortBy(m => (m.line, m.column))).toMap
  }

  // Collection roles use an exact target resolver instead of the historical
  // first-argument/name heuristics above. The historical mutation summary remains
  // available to the other scientific predicates so this refinement cannot alter
  // Fixed Value, Temporary, or Follower classifications.
  val declarationIdByIdentifierId = scala.collection.mutable.Map.empty[Long, Option[Long]]
  val collectionElementPathCache = scala.collection.mutable.Map.empty[(Long, String), Option[CollectionElementPath]]

  def declarationId(identifier: Identifier): Option[Long] =
    declarationIdByIdentifierId.getOrElseUpdate(identifier.id,
      identifier.refsTo.l.collectFirst {
        // Synthetic locals are retained internally so tuple-assignment lowering
        // can be reassembled by dataflow. They never become emitted subjects.
        case local: Local => local.id
        case parameter: MethodParameterIn if declById.contains(parameter.id) => parameter.id
      }
    )

  val rhsAnalysisCache = scala.collection.mutable.Map.empty[(Long, Long), Option[RhsAnalysis]]
  val arithmeticBinaryOperators = Map(
    "<operator>.addition" -> "+",
    "<operator>.subtraction" -> "-",
    "<operator>.multiplication" -> "*",
    "<operator>.division" -> "/"
  )
  val guardBinaryOperators = Map(
    "<operator>.lessThan" -> "<", "<operator>.lessEqualsThan" -> "<=",
    "<operator>.greaterThan" -> ">", "<operator>.greaterEqualsThan" -> ">=",
    "<operator>.equals" -> "==", "<operator>.identityEquals" -> "==", "<operator>.is" -> "==",
    "<operator>.notEquals" -> "!=", "<operator>.identityNotEquals" -> "!=",
    "<operator>.logicalAnd" -> "&&", "<operator>.logicalOr" -> "||"
  )
  val normalizedBinaryOperators = arithmeticBinaryOperators ++ guardBinaryOperators
  val transparentRhsOperators = Set(
    "<operator>.cast", "<operator>.parenthesis", "<operator>.addressOf"
  )
  val mostWantedStandardConstants = Set(
    "infinity", "int_max", "long_max", "llong_max", "dbl_max", "flt_max",
    "int_min", "long_min", "llong_min"
  )

  def rhsDependencies(expression: RhsExpr): Set[Long] = expression match
    case RhsDeclarationRef(id) => Set(id)
    case RhsSelfRef(id) => Set(id)
    case RhsUnary(_, operand) => rhsDependencies(operand)
    case RhsBinary(_, left, right) => rhsDependencies(left) ++ rhsDependencies(right)
    case RhsCall(_, arguments) => arguments.iterator.flatMap(rhsDependencies).toSet
    case RhsMemberAccess(receiver, memberId, _, _) => rhsDependencies(receiver) ++ memberId
    case RhsIndexAccess(receiver, index) => rhsDependencies(receiver) ++ rhsDependencies(index)
    case RhsDereference(operand) => rhsDependencies(operand)
    case _ => Set.empty

  def normalizedRhsExpression(expression: Expression, targetDeclId: Long, method: String): RhsExpr = expression match
    case literal: Literal => RhsLiteral(literal.code.trim)
    case identifier: Identifier => declarationId(identifier) match
      case Some(id) if id == targetDeclId => RhsSelfRef(id)
      case Some(id) => RhsDeclarationRef(id)
      case None if mostWantedStandardConstants.contains(identifier.code.trim.toLowerCase) =>
        RhsStandardConstant(identifier.code.trim)
      case None if identifier.code.trim.matches("[A-Za-z_$][A-Za-z0-9_$]*") =>
        RhsExternalReference(identifier.code.trim)
      case None => RhsUnknown("unresolved_identifier")
    case call: Call if normalizedBinaryOperators.contains(call.name) =>
      call.argument.l.filter(_.argumentIndex >= 0).sortBy(_.argumentIndex) match
        case left :: right :: Nil => RhsBinary(
          normalizedBinaryOperators(call.name),
          normalizedRhsExpression(left, targetDeclId, method),
          normalizedRhsExpression(right, targetDeclId, method)
        )
        case _ => RhsUnknown("binary_arity")
    case call: Call if Set("<operator>.plus", "<operator>.minus", "<operator>.logicalNot").contains(call.name) =>
      call.argument.l.sortBy(_.argumentIndex).headOption
        .map(operand => RhsUnary(
          if call.name.endsWith("minus") then "-"
          else if call.name.endsWith("logicalNot") then "!" else "+",
          normalizedRhsExpression(operand, targetDeclId, method)))
        .getOrElse(RhsUnknown("unary_arity"))
    case call: Call if call.name == "<operator>.indirection" =>
      call.argument.l.sortBy(_.argumentIndex).headOption
        .map(operand => RhsDereference(normalizedRhsExpression(operand, targetDeclId, method)))
        .getOrElse(RhsUnknown("dereference_arity"))
    case call: Call if Set("<operator>.indexAccess", "<operator>.indirectIndexAccess").contains(call.name) =>
      call.argument.l.sortBy(_.argumentIndex) match
        case receiver :: index :: Nil => RhsIndexAccess(
          normalizedRhsExpression(receiver, targetDeclId, method),
          normalizedRhsExpression(index, targetDeclId, method)
        )
        case _ => RhsUnknown("index_arity")
    case call: Call if call.name == "<operator>.fieldAccess" &&
        Set("math.inf", "math.infinity").contains(call.code.replace(" ", "").toLowerCase) =>
      RhsStandardConstant(call.code.trim)
    case call: Call if Set("<operator>.fieldAccess", "<operator>.indirectFieldAccess").contains(call.name) =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      val receiver = arguments.headOption
        .map(normalizedRhsExpression(_, targetDeclId, method))
        .getOrElse(RhsUnknown("member_receiver"))
      val field = call.ast.isFieldIdentifier.l.headOption
      val resolved = field.flatMap(resolvedMemberFor(_, call, method))
      resolved match
        case Some(member) if member.id == targetDeclId => RhsSelfRef(targetDeclId)
        case _ => RhsMemberAccess(receiver, resolved.map(_.id), field.map(_.code).getOrElse(""),
          indirect = call.name == "<operator>.indirectFieldAccess")
    case call: Call if transparentRhsOperators.contains(call.name) =>
      call.argument.l.sortBy(_.argumentIndex).lastOption
        .map(normalizedRhsExpression(_, targetDeclId, method))
        .getOrElse(RhsUnknown("transparent_arity"))
    case call: Call if call.name.startsWith("<operator>.") =>
      RhsUnknown(call.name)
    case call: Call =>
      RhsCall(call.name, call.argument.l.filter(_.argumentIndex > 0).sortBy(_.argumentIndex)
        .map(normalizedRhsExpression(_, targetDeclId, method)))
    case _ => RhsUnknown(expression.getClass.getSimpleName)

  def isCompleteRhs(expression: RhsExpr): Boolean = expression match
    case _: RhsUnknown => false
    case RhsUnary(_, operand) => isCompleteRhs(operand)
    case RhsBinary(_, left, right) => isCompleteRhs(left) && isCompleteRhs(right)
    case RhsCall(_, arguments) => arguments.forall(isCompleteRhs)
    case RhsMemberAccess(receiver, _, _, _) => isCompleteRhs(receiver)
    case RhsIndexAccess(receiver, index) => isCompleteRhs(receiver) && isCompleteRhs(index)
    case RhsDereference(operand) => isCompleteRhs(operand)
    case _ => true

  def normalizedRhsFor(write: WriteInfo): Option[RhsAnalysis] = {
    val key = (write.eventId, write.declId)
    rhsAnalysisCache.get(key) match
      case Some(result) =>
        profiler.increment("rhs_normalization_cache_hits")
        result
      case None =>
        profiler.increment("rhs_normalization_cache_misses")
        val result = callById.get(write.eventId).flatMap { call =>
          val arguments = call.argument.l.sortBy(_.argumentIndex)
          val expression = call.name match
            case "<operator>.preIncrement" | "<operator>.postIncrement" =>
              Some(RhsBinary("+", RhsSelfRef(write.declId), RhsLiteral("1")))
            case "<operator>.preDecrement" | "<operator>.postDecrement" =>
              Some(RhsBinary("-", RhsSelfRef(write.declId), RhsLiteral("1")))
            case "<operator>.assignmentPlus" | "<operator>.assignmentMinus" |
                 "<operator>.assignmentMultiplication" | "<operator>.assignmentDivision" =>
              arguments.drop(1).headOption.map { rhsNode =>
                val operator = Map(
                  "<operator>.assignmentPlus" -> "+", "<operator>.assignmentMinus" -> "-",
                  "<operator>.assignmentMultiplication" -> "*", "<operator>.assignmentDivision" -> "/"
                )(call.name)
                RhsBinary(operator, RhsSelfRef(write.declId),
                  normalizedRhsExpression(rhsNode, write.declId, write.method))
              }
            case "<operator>.assignment" => arguments.drop(1) match
              case rhsNode :: Nil => Some(normalizedRhsExpression(rhsNode, write.declId, write.method))
              case _ => None
            case _ => None
          expression.map { rhs =>
            RhsAnalysis(rhs, rhsDependencies(rhs), sourceBacked = write.line > 0 && write.code.nonEmpty,
              complete = isCompleteRhs(rhs))
          }
        }
        rhsAnalysisCache.update(key, result)
        result
  }

  def collectionIndex(expression: Expression): CollectionIndex = {
    val identifiers = expression.ast.isIdentifier.l.flatMap(id => declarationId(id)).distinct
    CollectionIndex(expression.code.trim, if identifiers.size == 1 then identifiers.headOption else None)
  }

  def uncachedCollectionElementPath(expression: Expression, method: String): Option[CollectionElementPath] = expression match
    case identifier: Identifier => declarationId(identifier).map(id =>
      CollectionElementPath(DirectCollection(id), Nil)
    )
    case call: Call if call.name == "<operator>.indirection" =>
      call.argument.l.sortBy(_.argumentIndex).headOption.flatMap {
        case identifier: Identifier => declarationId(identifier).map(id =>
          CollectionElementPath(DereferencedCollection(id), Nil)
        )
        case _ => None
      }
    case call: Call if Set("<operator>.indexAccess", "<operator>.indirectIndexAccess").contains(call.name) =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      arguments.headOption.flatMap(collectionElementPath(_, method)).map { base =>
        val index = arguments.drop(1).headOption.map(collectionIndex).getOrElse(CollectionIndex("", None))
        base.copy(accesses = base.accesses :+ index)
      }
    case call: Call if Set("<operator>.fieldAccess", "<operator>.indirectFieldAccess").contains(call.name) =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      val base = arguments.headOption.flatMap(collectionElementPath(_, method))
      val field = call.ast.isFieldIdentifier.l.headOption
      val resolved = field.flatMap(resolvedMemberFor(_, call, method))
      base match
        // An element field write keeps the collection root and records the field
        // below the indexed element; it is never a rearrangement proof.
        case Some(path) if path.accesses.exists(_.isInstanceOf[CollectionIndex]) =>
          Some(path.copy(accesses = path.accesses :+ CollectionMember(
            resolved.map(_.id), resolved.flatMap(member => memberOwnerById.get(member.id).map(_._2))
          )))
        case _ => resolved.flatMap { member =>
          memberOwnerById.get(member.id).map { case (_, ownerFullName) =>
            CollectionElementPath(MemberCollection(member.id, ownerFullName), Nil)
          }
        }
    case _ => None

  def collectionElementPath(expression: Expression, method: String): Option[CollectionElementPath] = {
    val key = (expression.id, method)
    collectionElementPathCache.get(key) match
      case Some(result) =>
        profiler.increment("collection_path_cache_hits")
        result
      case None =>
        profiler.increment("collection_path_cache_misses")
        val result = uncachedCollectionElementPath(expression, method)
        collectionElementPathCache.update(key, result)
        result
  }

  def collectionTarget(expression: Expression, method: String): Option[CollectionTarget] =
    collectionElementPath(expression, method).filter(_.accesses.isEmpty).map(_.target)

  val addCollectionNames = Set(
    "add", "addall", "append", "enqueue", "extend", "insert", "offer", "push", "push_back",
    "put", "putifabsent", "setdefault", "unshift", "<<"
  )
  val removeCollectionNames = Set(
    "delete", "dequeue", "erase", "poll", "pop", "pop_back", "pop_front", "remove", "removeall", "removeat", "shift"
  )
  val clearCollectionNames = Set("clear")
  val rearrangeCollectionNames = Set("reverse", "reverse!", "rotate", "shuffle", "shuffle!", "sort", "sort!")
  val receiverCollectionNames = addCollectionNames ++ removeCollectionNames ++
    clearCollectionNames ++ rearrangeCollectionNames

  def collectionEffectForName(name: String): Option[CollectionEffect] = {
    val normalized = name.toLowerCase
    if addCollectionNames.contains(normalized) then Some(AddElement)
    else if removeCollectionNames.contains(normalized) then Some(RemoveElement)
    else if clearCollectionNames.contains(normalized) then Some(ClearElements)
    else if rearrangeCollectionNames.contains(normalized) then Some(RearrangeElements)
    else None
  }

  def eventFor(
    call: Call,
    target: CollectionTarget,
    effect: CollectionEffect,
    sourcePaths: List[CollectionElementPath] = Nil,
    targetPaths: List[CollectionElementPath] = Nil,
    sourceOperationName: Option[String] = None
  ): CollectionEvent = CollectionEvent(
    call.id,
    target,
    effect,
    initialization = false,
    sourcePaths,
    targetPaths,
    sourceBacked = nodePath(call) != "<unknown>" && optInt(call.lineNumber, 0) > 0,
    cachedScopeOf(call),
    optInt(call.lineNumber, 0),
    optInt(call.columnNumber, 0),
    call.code,
    if call.methodFullName.nonEmpty then call.methodFullName else call.name,
    sourceOperationName
  )

  // Receiver calls have a frontend receiver expression and a distinct argument
  // index zero. Free calls such as remove(fileName) start at argument index one
  // and therefore cannot become collection mutations.
  val receiverCollectionEvents = profiler.timed("collection_receiver_events") {
    receiverCollectionNames.toList.sorted.flatMap(name => callsByLowerName.getOrElse(name, Nil)).flatMap { call =>
      val receiverArguments = call.argument.l.filter(_.argumentIndex == 0)
      if call.receiver.l.isEmpty || receiverArguments.size != 1 then Nil
      else receiverArguments.flatMap { receiver =>
        collectionTarget(receiver, cachedScopeOf(call)).flatMap { target =>
          collectionEffectForName(call.name).map(effect => eventFor(call, target, effect,
            sourceOperationName = Some(call.name)))
        }
      }
    }
  }

  // rubysrc represents a zero-argument receiver invocation such as `items.pop`
  // or `items.sort!` as a field-access operator without a CALL receiver edge.
  // Restrict recovery to Ruby, an exact mutator field name, and a declaration-
  // backed base expression; ordinary free calls and nested enclosing calls do
  // not satisfy this shape.
  val rubyZeroArgumentCollectionEvents = profiler.timed("collection_ruby_zero_argument_events") {
    if !languageUpper.contains("RUBY") then Nil
    else callsByName.getOrElse("<operator>.fieldAccess", Nil).flatMap { call =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      val operation = arguments.collectFirst { case field: FieldIdentifier => field.code.toLowerCase }
      for
        name <- operation.toList if name != "<<"
        effect <- collectionEffectForName(name).toList
        base <- arguments.headOption.toList
        target <- collectionTarget(base, cachedScopeOf(call)).toList
      yield eventFor(call, target, effect, sourceOperationName = Some(name))
    }
  }

  def rangeEndpoint(expression: Expression, method: String): Option[(CollectionTarget, String)] = expression match
    case call: Call if Set("begin", "cbegin", "end", "cend", "rbegin", "rend").contains(call.name.toLowerCase) =>
      call.argument.l.find(_.argumentIndex == 0).flatMap(collectionTarget(_, method)).map((_, call.name.toLowerCase))
    case _ => None

  val staticRearrangementEvents = profiler.timed("collection_static_rearrangements") {
    List("reverse", "sort").flatMap(name => callsByLowerName.getOrElse(name, Nil)).flatMap { call =>
      val method = cachedScopeOf(call)
      val endpoints = call.argument.l.filter(_.argumentIndex > 0).flatMap(rangeEndpoint(_, method))
      val targets = endpoints.map(_._1).distinct
      val endpointNames = endpoints.map(_._2).toSet
      if targets.size == 1 && endpoints.size >= 2 &&
          endpointNames.exists(name => name.endsWith("begin")) && endpointNames.exists(name => name.endsWith("end"))
      then Some(eventFor(call, targets.head, RearrangeElements))
      else None
    }
  }

  val cQsortEvents = profiler.timed("collection_c_qsort") {
    callsByLowerName.getOrElse("qsort", Nil).flatMap { call =>
      val targets = call.argument.l.filter(_.argumentIndex > 0).sortBy(_.argumentIndex)
        .headOption.flatMap(collectionTarget(_, cachedScopeOf(call))).toList
      if targets.size == 1 && call.argument.l.count(_.argumentIndex > 0) >= 4
      then Some(eventFor(call, targets.head, RearrangeElements))
      else None
    }
  }

  val staticSwapEvents = profiler.timed("collection_static_swaps") {
    callsByLowerName.getOrElse("swap", Nil).flatMap { call =>
      val method = cachedScopeOf(call)
      val arguments = call.argument.l.filter(_.argumentIndex > 0).sortBy(_.argumentIndex)
      val paths = arguments.flatMap(collectionElementPath(_, method))
      if paths.size == 2 && paths.map(_.target).distinct.size == 1 &&
          paths.forall(_.accesses.nonEmpty) && paths.head.accesses != paths.last.accesses
      then Some(eventFor(call, paths.head.target, RearrangeElements, paths, paths.reverse))
      else if arguments.size == 3 then
        arguments.headOption.flatMap(collectionTarget(_, method)).flatMap { target =>
          val indexes = arguments.drop(1).map(collectionIndex)
          val elementPaths = indexes.map(index => CollectionElementPath(target, List(index)))
          if elementPaths.size == 2 && elementPaths.head.accesses != elementPaths.last.accesses then
            Some(eventFor(call, target, RearrangeElements, elementPaths, elementPaths.reverse))
          else None
        }
      else None
    }
  }

  val staticKnownPermutationEvents = profiler.timed("collection_static_permutations") {
    callsByLowerName.getOrElse("shuffle", Nil).flatMap { call =>
      val arguments = call.argument.l.filter(_.argumentIndex > 0).sortBy(_.argumentIndex)
      if arguments.size == 1 then
        collectionTarget(arguments.head, cachedScopeOf(call))
          .map(target => eventFor(call, target, RearrangeElements))
      else None
    }
  }

  val assignmentCollectionEvents = profiler.timed("collection_assignment_events") {
    writeCalls.flatMap { call =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      arguments.headOption.filterNot(_.isInstanceOf[Identifier]).flatMap { lhs =>
        collectionElementPath(lhs, cachedScopeOf(call)).filter(_.accesses.nonEmpty).map { targetPath =>
          val rhs = arguments.drop(1)
          val sourcePaths = rhs.flatMap(expression =>
            expression.ast.isCall.l.flatMap(candidate => collectionElementPath(candidate, cachedScopeOf(call))) ++
              expression.ast.isIdentifier.l.flatMap(identifier => collectionElementPath(identifier, cachedScopeOf(call)))
          ).distinct
          val writesElementMember = targetPath.accesses.lastOption.exists(_.isInstanceOf[CollectionMember])
          val transformsElement = rhs.exists(_.ast.isCall.l.exists(candidate => !candidate.name.startsWith("<operator>.")))
          val effect = if writesElementMember || transformsElement then TransformElement else ReplaceElement
          eventFor(call, targetPath.target, effect, sourcePaths, List(targetPath))
        }
      }
    }
  }

  // Multiple-assignment frontends may expose the two element writes under one
  // assignment node. Preserve the complete pair as one event so a later
  // PermutationSpan can consume it atomically.
  val tupleSwapCollectionEvents = profiler.timed("collection_tuple_swaps") {
    assignmentCalls.flatMap { call =>
      val arguments = call.argument.l.sortBy(_.argumentIndex)
      val targetPaths = arguments.headOption.toList.flatMap(_.ast.isCall.l)
        .flatMap(candidate => collectionElementPath(candidate, cachedScopeOf(call)))
        .filter(_.accesses.nonEmpty).distinct
      val sourcePaths = arguments.drop(1).flatMap(_.ast.isCall.l)
        .flatMap(candidate => collectionElementPath(candidate, cachedScopeOf(call)))
        .filter(_.accesses.nonEmpty).distinct
      val oneTarget = targetPaths.map(_.target).distinct
      if targetPaths.size == 2 && sourcePaths.size == 2 && oneTarget.size == 1 &&
          sourcePaths.forall(_.target == oneTarget.head) &&
          targetPaths.head.accesses != targetPaths.last.accesses &&
          targetPaths == sourcePaths.reverse
      then Some(eventFor(call, oneTarget.head, RearrangeElements, sourcePaths, targetPaths))
      else None
    }
  }

  val collectionEvents = (
    receiverCollectionEvents ++ rubyZeroArgumentCollectionEvents ++ staticRearrangementEvents ++
      cQsortEvents ++ staticSwapEvents ++ staticKnownPermutationEvents ++
      tupleSwapCollectionEvents ++ assignmentCollectionEvents
  ).distinctBy(event => (event.eventId, event.target, event.effect))
    .sortBy(event => (event.method, event.line, event.column, event.eventId))
  val collectionEventsByDecl = collectionEvents.groupBy(_.target.declarationId)
  val collectionEventsById = collectionEvents.groupBy(_.eventId)
  val elementWritesByCollectionDecl = collectionEvents
    .filter(event => Set(ReplaceElement, TransformElement).contains(event.effect))
    .groupBy(_.target.declarationId)
  profiler.set("collection_events", collectionEvents.size.toLong)
  profiler.set("collection_element_write_events",
    elementWritesByCollectionDecl.valuesIterator.map(_.size.toLong).sum)
  def mutationKindForEffect(effect: CollectionEffect): String = effect match
    case AddElement => "other"
    case RemoveElement => "other"
    case ClearElements => "clear"
    case RearrangeElements => "rearrange"
    case ReplaceElement => "indexed_write"
    case TransformElement => "element_transform"
    case RebindCollection | UpdateCollectionBinding | UnknownCollectionMutation => "other"

  val resolvedStateMutationsByDecl = profiler.timed("resolved_state_mutations") {
    val semanticMutations = collectionEvents.map(event => StateMutation(
      event.target.declarationId,
      event.method,
      event.line,
      event.column,
      event.code,
      event.sourceOperationName.filter(name => receiverCollectionNames.contains(name.toLowerCase))
        .getOrElse(mutationKindForEffect(event.effect)),
      event.parserOperator
    ))
    // The public view is a source-backed mutation inventory, not a first-argument
    // guess. Assignment events include unknown element/member mutations; receiver
    // events are emitted only after exact target resolution above.
    semanticMutations
      .distinctBy(mutation => (mutation.declId, mutation.method, mutation.line, mutation.column, mutation.mutationKind))
      .groupBy(_.declId)
      .view.mapValues(_.sortBy(mutation => (mutation.line, mutation.column, mutation.mutationKind))).toMap
  }

  val fixedMemberMutationEvents = (
    writeCalls.filter(call => call.argument.l.headOption.exists(_.code.contains("["))) ++
    lengthMutationCalls ++ inPlaceMutationCalls
  ).flatMap { call =>
    val method = cachedScopeOf(call)
    val line = optInt(call.lineNumber, 0)
    val target = call.argument.l.headOption.getOrElse(call)
    call.ast.isFieldIdentifier.l.flatMap(field =>
      resolvedMemberFor(field, target, method).map(member => (member.id, loopAt(method, line).nonEmpty))
    )
  }.distinct
  val fixedMutatedMemberIds = fixedMemberMutationEvents.map(_._1).toSet
  val fixedLoopMutatedMemberIds = fixedMemberMutationEvents.collect { case (id, true) => id }.toSet


  // Split tuple/list headers without breaking nested calls such as enumerate(zip(a, b)).
  def splitTopLevelComma(text: String): List[String] = {
    val parts = scala.collection.mutable.ListBuffer[String]()
    val current = new StringBuilder
    var depth = 0
    text.foreach {
      case ',' if depth == 0 =>
        parts += current.toString.trim
        current.clear()
      case ch @ ('(' | '[' | '{') =>
        depth += 1
        current.append(ch)
      case ch @ (')' | ']' | '}') =>
        depth = math.max(0, depth - 1)
        current.append(ch)
      case ch =>
        current.append(ch)
    }
    parts += current.toString.trim
    parts.toList.filter(_.nonEmpty)
  }
  import IteratorParsing.*
  // Suppress implicit walker detection for Python loops that are really range steppers.
  def excludesPythonStepperHeader(header: String): Boolean = {
    isPythonLanguage(language) && numericIteratorNames(language, header).nonEmpty
  }
  // Parse iterator variable names directly from language-specific loop headers.
  def iteratorNamesFromHeader(header: String): Set[String] = iteratorNames(language, header)
  // Prefer the original source header, falling back to Joern's code string.
  def implicitIteratorNames(cs: ControlStructure): Set[String] = {
    val line = optInt(cs.lineNumber, -1)
    val fromSource = sourceLine(nodePath(cs), line).map(iteratorNamesFromHeader).getOrElse(Set.empty)
    if fromSource.nonEmpty then fromSource else iteratorNamesFromHeader(firstLine(cs.code))
  }
  // Joern may lower Python `for` to synthetic `while`; recover the loop target from that shape.
  def syntheticPythonForIteratorIds(cs: ControlStructure): List[Identifier] = {
    val line = optInt(cs.lineNumber, -1)
    val sameLineIds = cs.ast.isIdentifier.l.filter(id => optInt(id.lineNumber, -2) == line)
    val hasSyntheticTmp = sameLineIds.exists(id => id.name.matches("tmp\\d+"))
    if cs.controlStructureType.toUpperCase.contains("WHILE") && hasSyntheticTmp then
      sameLineIds
        .filterNot(id => id.name.matches("tmp\\d+"))
        .sortBy(id => optInt(id.columnNumber, Int.MaxValue))
        .take(1)
    else Nil
  }

  // Return identifier nodes that should be considered declaration positions for walkers.
  def implicitIteratorHeaderIds(cs: ControlStructure): List[Identifier] = {
    val sourceHeader = sourceLine(nodePath(cs), optInt(cs.lineNumber, -1))
    if sourceHeader.exists(excludesPythonStepperHeader) then return Nil
    val names = implicitIteratorNames(cs)
    val line = optInt(cs.lineNumber, -1)
    val headerIds =
      if names.nonEmpty || cs.controlStructureType.toUpperCase.contains("FOR") || firstLine(cs.code).matches("(?s).*\\bfor\\b.*") then
        cs.ast.isIdentifier.l.filter(id => names.contains(id.name) && optInt(id.lineNumber, -2) == line)
      else Nil
    val fallbackIds = if names.isEmpty then syntheticPythonForIteratorIds(cs) else Nil
    val candidates = (headerIds ++ fallbackIds).distinctBy(id => (id.name, id.lineNumber, id.columnNumber))
    if !isNewCLanguage(language) then candidates
    else candidates.groupBy(_.name).values.toList.flatMap { identifiers =>
      val refs = identifiers.flatMap(_.refsTo.l.collect { case local: Local => local })
        .distinctBy(_.id).filter(local => optInt(local.lineNumber, -2) == line)
      if refs.size == 1 then identifiers.filter(_.refsTo.l.exists(_.id == refs.head.id)) else Nil
    }
  }

  // Resolve loop-header iterator ids back to variable declarations, with a short-line fallback.
  val declarationsByPathName = declarations.groupBy(declaration => (declaration.pos.path, declaration.name))
  val controlImplicitIteratorDeclById = profiler.timed("implicit_iterator_detection") { allControlStructureNodes
    .flatMap(implicitIteratorHeaderIds)
    .flatMap { id =>
      val direct = id.refsTo.l.collect {
        case l: Local if declById.contains(l.id) => l.id -> posOf(id, id.name)
        case p: MethodParameterIn if declById.contains(p.id) => p.id -> posOf(id, id.name)
      }
      val fallback =
        if direct.nonEmpty || isNewCLanguage(language) then Nil
        else
          val idPath = nodePath(id)
          val idLine = optInt(id.lineNumber, -1)
          declarationsByPathName.getOrElse((idPath, id.name), Nil)
            .filter(d => d.pos.line >= idLine && d.pos.line <= idLine + 5)
            .map(d => d.id -> posOf(id, id.name))
            .take(1)
      direct ++ fallback
    }
    .groupBy(_._1)
    .view.mapValues(_.map(_._2).sortBy(p => (p.path, p.line, p.column)).head)
    .toMap }
  val sourceBackedImplicitIteratorDeclById = profiler.timed("implicit_iterator_detection") { declarations.flatMap { declaration =>
    sourceLine(declaration.pos.path, declaration.pos.line).filter(line =>
      !isNewCLanguage(language) && iteratorNamesFromHeader(line).contains(declaration.name)
    ).map(_ => declaration.id -> declaration.pos)
  }.toMap }
  val implicitIteratorDeclById = controlImplicitIteratorDeclById ++ sourceBackedImplicitIteratorDeclById
  val implicitIteratorIds = implicitIteratorDeclById.keySet

  // Keep presentation hints independent from the corrected role binding.
  def annotationIteratorNamesFromHeader(header: String): Set[String] = annotationIteratorNames(language, header)
  def annotationImplicitIteratorNames(cs: ControlStructure): Set[String] = {
    val line = optInt(cs.lineNumber, -1)
    val fromSource = sourceLine(nodePath(cs), line).map(annotationIteratorNamesFromHeader).getOrElse(Set.empty)
    if fromSource.nonEmpty then fromSource else annotationIteratorNamesFromHeader(firstLine(cs.code))
  }
  // Joern may lower Python `for` to synthetic `while`; recover the loop target from that shape.
  def annotationSyntheticPythonForIteratorIds(cs: ControlStructure): List[Identifier] = {
    val line = optInt(cs.lineNumber, -1)
    val sameLineIds = cs.ast.isIdentifier.l.filter(id => optInt(id.lineNumber, -2) == line)
    val hasSyntheticTmp = sameLineIds.exists(id => id.name.matches("tmp\\d+"))
    if cs.controlStructureType.toUpperCase.contains("WHILE") && hasSyntheticTmp then
      sameLineIds
        .filterNot(id => id.name.matches("tmp\\d+"))
        .sortBy(id => optInt(id.columnNumber, Int.MaxValue))
        .take(1)
    else Nil
  }

  // Return identifier nodes that should be considered declaration positions for walkers.
  def annotationImplicitIteratorHeaderIds(cs: ControlStructure): List[Identifier] = {
    val sourceHeader = sourceLine(nodePath(cs), optInt(cs.lineNumber, -1))
    if sourceHeader.exists(excludesPythonStepperHeader) then return Nil
    val names = annotationImplicitIteratorNames(cs)
    val line = optInt(cs.lineNumber, -1)
    val headerIds =
      if names.nonEmpty || cs.controlStructureType.toUpperCase.contains("FOR") || firstLine(cs.code).matches("(?s).*\\bfor\\b.*") then
        cs.ast.isIdentifier.l.filter(id => names.contains(id.name) && optInt(id.lineNumber, -2) == line)
      else Nil
    val fallbackIds = if names.isEmpty then annotationSyntheticPythonForIteratorIds(cs) else Nil
    (headerIds ++ fallbackIds).distinctBy(id => (id.name, id.lineNumber, id.columnNumber))
  }

  // Resolve loop-header iterator ids back to variable declarations, with a short-line fallback.
  val annotationControlImplicitIteratorDeclById = profiler.timed("implicit_iterator_detection") { allControlStructureNodes
    .flatMap(annotationImplicitIteratorHeaderIds)
    .flatMap { id =>
      val direct = id.refsTo.l.collect {
        case l: Local if declById.contains(l.id) => l.id -> posOf(id, id.name)
        case p: MethodParameterIn if declById.contains(p.id) => p.id -> posOf(id, id.name)
      }
      val fallback =
        if direct.nonEmpty then Nil
        else
          val idPath = nodePath(id)
          val idLine = optInt(id.lineNumber, -1)
          declarationsByPathName.getOrElse((idPath, id.name), Nil)
            .filter(d => d.pos.line >= idLine && d.pos.line <= idLine + 5)
            .map(d => d.id -> posOf(id, id.name))
            .take(1)
      direct ++ fallback
    }
    .groupBy(_._1)
    .view.mapValues(_.map(_._2).sortBy(p => (p.path, p.line, p.column)).head)
    .toMap }
  val annotationSourceBackedImplicitIteratorDeclById = profiler.timed("implicit_iterator_detection") { declarations.flatMap { declaration =>
    sourceLine(declaration.pos.path, declaration.pos.line).filter(line =>
      annotationIteratorNamesFromHeader(line).contains(declaration.name)
    ).map(_ => declaration.id -> declaration.pos)
  }.toMap }
  val annotationImplicitIteratorDeclById = annotationControlImplicitIteratorDeclById ++ annotationSourceBackedImplicitIteratorDeclById
  val annotationImplicitIteratorIds = annotationImplicitIteratorDeclById.keySet

  val numericHeaderCache = scala.collection.mutable.Map.empty[(String, Int), Set[String]]
  def numericNamesAt(path: String, line: Int, fallback: => String = ""): Set[String] = {
    val key = (path, line)
    numericHeaderCache.get(key) match
      case Some(names) =>
        profiler.increment("numeric_iterator_cache_hits")
        names
      case None =>
        val header = sourceLine(path, line).getOrElse(fallback).trim
        profiler.increment("numeric_iterator_candidate_lines")
        profiler.maximum("numeric_iterator_max_header_length", header.length.toLong)
        val names = numericIteratorNames(language, header)
        numericHeaderCache.update(key, names)
        names
  }
  val numericLanguageSupported = isRubyLanguage(language) || isPythonLanguage(language)
  val numericCandidateControls =
    if numericLanguageSupported then allControlStructureNodes.filter { control =>
      val kind = control.controlStructureType.toUpperCase
      kind.contains("FOR") || (isPythonLanguage(language) && kind.contains("WHILE")) ||
        (isRubyLanguage(language) && firstLine(control.code).contains(".."))
    }
    else {
      profiler.increment("numeric_iterator_language_filtered_calls", allControlStructureNodes.size.toLong)
      Nil
    }
  profiler.set("numeric_iterator_candidate_controls", numericCandidateControls.size.toLong)
  profiler.set(s"numeric_iterator_candidate_controls_${languageUpper.toLowerCase}", numericCandidateControls.size.toLong)

  val implicitNumericIteratorDeclIds = profiler.timed("numeric_iterator_detection") {
    val fromControls = numericCandidateControls.flatMap { control =>
      val line = optInt(control.lineNumber, -1)
      val path = nodePath(control)
      numericNamesAt(path, line, firstLine(control.code)).flatMap { name =>
        val direct = control.ast.isIdentifier.l.filter(_.name == name)
          .flatMap(declarationId).filter(declById.contains)
        if direct.nonEmpty then direct
        else declarationsByPathName.getOrElse((path, name), Nil)
          .filter(declaration => declaration.pos.line >= line && declaration.pos.line <= line + 5)
          .sortBy(declaration => (declaration.pos.line, declaration.pos.column, declaration.id))
          .take(1).map(_.id)
      }
    }.toSet
    val unresolved =
      if numericLanguageSupported then declarations.filterNot(declaration => fromControls.contains(declaration.id))
      else Nil
    profiler.set("numeric_iterator_fallback_declarations", unresolved.size.toLong)
    val fromFallback = unresolved.iterator.flatMap { declaration =>
      (0 to 3).iterator.flatMap { offset =>
        val line = declaration.pos.line - offset
        numericNamesAt(declaration.pos.path, line).iterator
          .filter(_ == declaration.name).map(_ => declaration.id)
      }.take(1)
    }.toSet
    val resolved = fromControls ++ fromFallback
    profiler.set("numeric_iterator_cache_entries", numericHeaderCache.size.toLong)
    profiler.set("numeric_iterator_resolved", resolved.size.toLong)
    profiler.set(s"numeric_iterator_resolved_${languageUpper.toLowerCase}", resolved.size.toLong)
    resolved
  }

  val modeledInitializerKeys = assignmentWrites
    .filter(w => declById.get(w.declId).exists(d => d.kind == "local" && d.pos.line == w.line))
    .map(w => (w.declId, w.line))
    .toSet
  val recoveredLocalInitializerWrites = localInitializerWrites.filterNot(w => modeledInitializerKeys.contains((w.declId, w.line)))
  def attachNormalizedRhs(write: WriteInfo): WriteInfo =
    write.copy(rhs = normalizedRhsFor(write))
  val allWrites = (assignmentWrites ++ recoveredLocalInitializerWrites)
    .distinctBy(w => (w.declId, w.line, w.column, w.operator, w.code, w.literalValue))
    .map(attachNormalizedRhs)
  val normalizedFieldAssignmentWrites = fieldAssignmentWrites.map(attachNormalizedRhs)
  val writesByDecl = allWrites.groupBy(_.declId).view.mapValues(_.sortBy(w => (w.line, w.column, !w.declarationInitializer))).toMap
  val canonicalMemberLocalWrites = allWrites.filter(write => memberNodeById.contains(write.declId))
  val fieldWritesByDecl = (normalizedFieldAssignmentWrites ++ canonicalMemberLocalWrites).groupBy(_.declId)
    .view.mapValues(_.sortBy(w => (w.line, w.column, !w.declarationInitializer))).toMap

  val result: BindingFacts = BindingFacts(
    declarations = declarations,
    memberDeclarations = memberDeclarations,
    occurrencesByDeclaration = occurrencesByDecl,
    memberOccurrencesByDeclaration = fieldOccurrencesByDecl,
    writesByDeclaration = writesByDecl,
    memberWritesByDeclaration = fieldWritesByDecl,
    stateMutationsByDeclaration = stateMutationsByDecl
  )
}

def extractBindingFacts(context: ExtractionContextIndex): BindingFactIndex =
  new BindingFactIndex(context)
