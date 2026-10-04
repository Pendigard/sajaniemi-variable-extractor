import io.shiftleft.codepropertygraph.generated.nodes.AstNode
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.LazyLocation.apply
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// Joern fields are often optional; this keeps default handling explicit at call sites.
def optInt(v: Option[Int], fallback: Int): Int = v.getOrElse(fallback)

// Use the filename as the analysis scope because these snippets are file-level graphs.
def nodePath(n: AstNode): String =
  try n.location.filename
  catch case _: Throwable => "<unknown>"

// Scope currently equals path; keeping a helper makes later method-level scoping easy.
def nodeScope(n: AstNode): String = nodePath(n)

// Build a best-effort token position from Joern line/column metadata.
def posOf(n: AstNode, name: String): Position = {
  val line = optInt(n.lineNumber, 0)
  val column = math.max(1, optInt(n.columnNumber, 1))
  val lineEnd = line
  val columnEnd = column + math.max(1, name.length)
  Position(nodePath(n), line, column, lineEnd, columnEnd, n.code)
}

// Convert taxonomy labels to stable annotation ids.
def cleanConceptName(name: String): String =
  name.toLowerCase.replace("-", " ").replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

// Future-mutated references are occurrences that appear before a later write.
def writeAfterOccurrence(a: Occurrence, w: WriteInfo): Boolean =
  a.method == w.method && w.line > 0 && a.pos.line > 0 &&
    (w.line > a.pos.line || (w.line == a.pos.line && w.column > a.pos.column))

def mutationAfterOccurrence(a: Occurrence, m: StateMutation): Boolean =
  a.method == m.method && m.line > 0 && a.pos.line > 0 &&
    (m.line > a.pos.line || (m.line == a.pos.line && m.column > a.pos.column))

// Match identifiers as whole tokens so names like `i` do not match `index`.
def containsName(code: String, name: String): Boolean =
  raw"(?<![A-Za-z0-9_])${java.util.regex.Pattern.quote(name)}(?![A-Za-z0-9_])".r.findFirstIn(code).nonEmpty

// Normalize direct two-valued literal assignments used by one-way flags.
def twoValuedLiteral(code: String): Option[String] =
  code.trim match
    case s if s.matches("(?i)^(true|false)$") => Some(s.toLowerCase)
    case "0" => Some("0")
    case "1" => Some("1")
    case _ => None

// Treat literal-only expressions as predictable; calls or variable dependencies are not.
def predictableCode(code: String, variableNames: Set[String]): Boolean = {
  val withoutStrings = code.replaceAll("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'", "\"\"")
  val hasCall = raw"[A-Za-z_][A-Za-z0-9_]*\s*\(".r.findFirstIn(withoutStrings).nonEmpty
  val identifiers = raw"[A-Za-z_][A-Za-z0-9_]*".r.findAllIn(withoutStrings).toSet
  val languageWords = Set("true", "false", "null", "none", "nil", "True", "False", "None", "return", "new")
  val dependsOnVariable = identifiers.exists(id => variableNames.contains(id) && !languageWords.contains(id))
  !hasCall && !dependsOnVariable
}

// Normalize Joern loop type names across languages.
def isLoopType(t: String): Boolean = {
  val u = t.toUpperCase
  u.contains("FOR") || u.contains("WHILE") || u.contains("DO")
}

// Control blocks are used to distinguish unconditional holders from selected candidates.
def isControlType(t: String): Boolean = {
  val u = t.toUpperCase
  u.contains("IF") || u.contains("SWITCH") || u.contains("TRY") || u.contains("CATCH") || u.contains("EXCEPT")
}

// Inclusive source-line range check used by loop/control membership tests.
def lineInside(line: Int, start: Int, end: Int): Boolean =
  line > 0 && start > 0 && line >= start && line <= end

final class SourceIndex(sourceRoot: String, language: String) {
  // Map Joern's language label to the folder that contains the original source files.
  private val languageDir =
    if language.contains("PYTHON") then "Python"
    else if language.contains("NEWC_CPP") then "C++"
    else if language.contains("NEWC_C") then "C"
    else if language.contains("JS") || language.contains("JAVA_SCRIPT") then "JavaScript"
    else if language.contains("JAVA") then "Java"
    else if language.contains("RUBY") then "Ruby"
    else ""
  private val rootPath = Paths.get(sourceRoot)
  private val languageBase = if languageDir.nonEmpty then rootPath.resolve(languageDir) else rootPath
  // Accept both corpus layouts: a root containing language directories
  // (`root/Python/...`) and a root pointing directly at the source files.
  private val sourceBase = if Files.exists(languageBase) then languageBase else rootPath

  // Joern paths are often basename-like, so we index sources by basename for line recovery.
  private val sourceByBasename =
    if Files.exists(sourceBase) then
      Files.walk(sourceBase).iterator.asScala
        .filter(p => Files.isRegularFile(p))
        .toList
        .groupBy(_.getFileName.toString)
        .view.mapValues(_.sortBy(_.toString).head)
        .toMap
    else Map.empty[String, java.nio.file.Path]

  private val sourceFileCache = scala.collection.mutable.Map[String, Option[List[String]]]()
  private val sourceLineCache = scala.collection.mutable.Map[(String, Int), Option[String]]()

  // Cache full source files because many range heuristics need repeated line lookups.
  def sourceLines(path: String): Option[List[String]] =
    sourceFileCache.getOrElseUpdate(path, {
      sourceByBasename.get(Paths.get(path).getFileName.toString).flatMap { p =>
        try Some(Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toList)
        catch case _: Throwable => None
      }
    })

  // Read one source line when Joern's synthetic code omits the original header.
  def sourceLine(path: String, line: Int): Option[String] =
    sourceLineCache.getOrElseUpdate((path, line), {
      sourceLines(path).flatMap(lines => if line > 0 && line <= lines.size then Some(lines(line - 1)) else None)
    })

  // Joern sometimes stores a whole subtree as code; headers only need the first line.
  def firstLine(code: String): String = code.linesIterator.take(1).mkString

  // Count indentation for a source fallback when a frontend omits block children.
  private def indentOf(line: String): Int =
    line.takeWhile(ch => ch == ' ' || ch == '\t').map(ch => if ch == '\t' then 4 else 1).sum

  // Recover indentation-based block ends only when the CPG has no usable block child.
  def sourceIndentBlockEnd(path: String, start: Int): Option[Int] =
    sourceLines(path).flatMap { lines =>
      if start <= 0 || start > lines.size then None
      else
        val header = lines(start - 1)
        if header.trim.endsWith(":") then
          val baseIndent = indentOf(header)
          var end = start
          var idx = start
          var done = false
          while idx < lines.size && !done do
            val text = lines(idx)
            if text.trim.nonEmpty && indentOf(text) <= baseIndent then done = true
            else
              if text.trim.nonEmpty then end = idx + 1
              idx += 1
          Some(math.max(start, end))
        else None
    }
}
