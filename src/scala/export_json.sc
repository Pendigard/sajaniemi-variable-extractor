import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

def jsonEscape(s: String): String =
  s.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c if c.isControl => f"\\u${c.toInt}%04x"
    case c => c.toString
  }

def subjectToJson(subject: VarDecl): String =
  val fileScoped = subject.method == subject.pos.path || subject.method.isEmpty
  val scopeType =
    if fileScoped then "file"
    else if subject.kind == "member" then "class"
    else "function"
  val scopeName =
    if fileScoped then subject.pos.path
    else subject.method.split("[.:]").filter(_.nonEmpty).lastOption.getOrElse(subject.method)
  "{" +
    s""""id":"${jsonEscape(subject.pos.path)}::${jsonEscape(subject.method)}::${jsonEscape(subject.kind)}::${jsonEscape(subject.name)}:${subject.pos.line}:${subject.pos.column}",""" +
    s""""joern_id":${subject.id},""" +
    s""""name":"${jsonEscape(subject.name)}",""" +
    s""""kind":"${jsonEscape(subject.kind)}",""" +
    s""""path":"${jsonEscape(subject.pos.path)}",""" +
    s""""scope":{"type":"$scopeType","name":"${jsonEscape(scopeName)}","qualified_name":"${jsonEscape(subject.method)}"},""" +
    s""""_declaration_line":${subject.pos.line},""" +
    s""""_declaration_column":${subject.pos.column}""" +
  "}"

def viewHintToJson(hint: ViewHint): String =
  val metadata = Seq(
    hint.mutationKind.map(value => s""""mutation_kind":"${jsonEscape(value)}""""),
    hint.parserOperator.map(value => s""""parser_operator":"${jsonEscape(value)}""""),
    hint.contextType.map(value => s""""type":"${jsonEscape(value)}""""),
    hint.subtype.map(value => s""""subtype":"${jsonEscape(value)}""""),
    hint.relation.map(value => s""""relation":"${jsonEscape(value)}"""")
  ).flatten
  val hintMetadata = Seq(
    hint.hintEndLine.map(value => s"\"end_line\":$value"),
    hint.hintEndColumn.map(value => s"\"end_column\":$value"),
    hint.hintLanguage.map(value => s"\"language\":\"${jsonEscape(value)}\""),
    hint.hintBoundary.map(value => s"\"boundary\":\"${jsonEscape(value)}\"")
  ).flatten
  "{" +
    s""""usage":"${jsonEscape(hint.usage)}",""" +
    (if metadata.nonEmpty then metadata.mkString("", ",", ",") else "") +
    s"\"_hint\":{\"path\":\"${jsonEscape(hint.path)}\",\"line\":${hint.line},\"column\":${hint.column},\"name\":\"${jsonEscape(hint.name)}\",\"code\":\"${jsonEscape(hint.code)}\"" +
    (if hintMetadata.nonEmpty then hintMetadata.mkString(",", ",", "") else "") +
    "}" +
  "}"

def hintSequenceToJson(hints: Seq[ViewHint]): String =
  hints.map(viewHintToJson).mkString("[", ",", "]")

def viewsToJson(views: MinimalViews): String =
  "{" +
    s""""declaration":${hintSequenceToJson(views.declaration)},""" +
    s""""identifier":${hintSequenceToJson(views.identifier)},""" +
    s""""reads":${hintSequenceToJson(views.reads)},""" +
    s""""writes":${hintSequenceToJson(views.writes)},""" +
    s""""updates":${hintSequenceToJson(views.updates)},""" +
    s""""state_mutations":${hintSequenceToJson(views.stateMutations)},""" +
    s""""control_context":${hintSequenceToJson(views.controlContext)},""" +
    s""""scope":${hintSequenceToJson(views.scope)}""" +
  "}"

def roleAnnotationToJson(record: RoleAnnotationHint): String =
  "{" +
    """"schema_version":1,""" +
    s""""concept":{"family":"sajaniemi_role","name":"${jsonEscape(record.role)}"},""" +
    s""""subject":${subjectToJson(record.subject)},""" +
    s""""views":${viewsToJson(record.views)}""" +
  "}"

def variableFactsToJson(record: VariableFactsHint): String =
  "{" +
    """"schema_version":1,""" +
    s""""subject":${subjectToJson(record.subject)},""" +
    s""""is_collection":${record.isCollection},""" +
    s""""roles":${record.roles.map(role => s"\"${jsonEscape(role)}\"").mkString("[", ",", "]")},""" +
    s""""views":${viewsToJson(record.views)}""" +
  "}"

def exportVariableAwareJson(path: String, output: VariableAwareOutput): Unit = {
  val roles = output.roleAnnotations.map(roleAnnotationToJson).mkString("[\n    ", ",\n    ", "\n  ]")
  val facts = output.variableFacts.map(variableFactsToJson).mkString("[\n    ", ",\n    ", "\n  ]")
  val json = s"""{
  "schema_version": 1,
  "role_annotations": $roles,
  "variable_facts": $facts
}
"""
  Files.write(Paths.get(path), json.getBytes(StandardCharsets.UTF_8))
}
