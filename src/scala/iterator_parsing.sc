import java.util.regex.Pattern

/** Small, source-backed loop-header parsers.
  *
  * Every entry point is language-gated. The Ruby range recognizer is deliberately
  * anchored and accepts only inspectable scalar endpoints, so its cost is linear
  * in the header length and ambiguous source yields no iterator.
  */
object IteratorParsing {
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*"
  private val JsIdentifier = "[A-Za-z_$][A-Za-z0-9_$]*"
  private val RubyEndpoint = s"(?:[-+]?\\d+|$Identifier)"

  private val RubyForRange = Pattern.compile(
    s"^for\\s+($Identifier)\\s+in\\s+($RubyEndpoint)\\s*(\\.\\.\\.?)\\s*($RubyEndpoint)\\s*(?:do)?\\s*$$"
  )
  private val RubyBlockRange = Pattern.compile(
    s"^\\(?\\s*($RubyEndpoint)\\s*(\\.\\.\\.?)\\s*($RubyEndpoint)\\s*\\)?\\s*\\.\\s*each\\s*(?:do\\s*|\\{\\s*)\\|\\s*($Identifier)\\s*\\|"
  )
  private val RubyCollectionBlock = Pattern.compile(
    s"^[^#;|]*\\.\\s*(?:each|map|select|reject|collect|inject|each_with_index)\\s*(?:do\\s*|\\{\\s*)\\|\\s*([^|]+)\\|"
  )
  private val PythonFor = Pattern.compile("^\\s*for\\s+(.+?)\\s+in\\s+(.+?)(?:\\s*:\\s*)?$")
  private val JsFor = Pattern.compile(
    s"^\\s*for\\s*\\(\\s*(?:var|let|const)?\\s*($JsIdentifier)\\s+(?:in|of)\\s+"
  )
  private val CppType = Pattern.compile(s"$Identifier(?:::$Identifier)*(?:\\s+$Identifier(?:::$Identifier)*)*")

  // Scan only the balanced header. Single colons are separators; :: is part
  // of a qualified name. Classic for headers and ambiguous declarators reject.
  def cppRangeIterator(header: String): Option[String] = {
    val text = header.trim
    if !text.startsWith("for") then return None
    var cursor = 3
    while cursor < text.length && text(cursor).isWhitespace do cursor += 1
    if cursor >= text.length || text(cursor) != '(' then return None
    val start = cursor + 1
    cursor = start
    var depth = 1
    var separator = -1
    while cursor < text.length && depth > 0 do {
      val ch = text(cursor)
      if ch == ';' || ch == '"' || ch == '\'' then return None
      if ch == '(' then depth += 1
      else if ch == ')' then depth -= 1
      else if ch == ':' && depth == 1 && text(cursor - 1) != ':' &&
          (cursor + 1 >= text.length || text(cursor + 1) != ':') then {
        if separator >= 0 then return None
        separator = cursor
      }
      cursor += 1
    }
    if depth != 0 || separator < 0 || text.substring(separator + 1, cursor - 1).trim.isEmpty then return None
    val binding = text.substring(start, separator).trim
    var nameStart = binding.length
    while nameStart > 0 && (binding(nameStart - 1).isLetterOrDigit || binding(nameStart - 1) == '_') do
      nameStart -= 1
    if nameStart == 0 || nameStart == binding.length then return None
    val name = binding.substring(nameStart)
    if !name.matches(Identifier) then return None
    val prefix = binding.substring(0, nameStart).trim
    val base = prefix.stripSuffix("&&").stripSuffix("&").replace("*", " ").trim
    if !CppType.matcher(base).matches() then None else Some(name)

  }

  // Frozen source-view hint recognizer. Classification never consumes this
  // legacy heuristic; changing its hints would migrate existing subject spans.
  private val LegacyCppViewBinding = Pattern.compile(
    s"^\\s*for\\s*\\(\\s*(?:[^;():]+?\\s+)?($JsIdentifier)\\s*:\\s*"
  )
  def annotationIteratorNames(language: String, header: String): Set[String] =
    if !isNewCLanguage(language) then iteratorNames(language, header)
    else {
      val matcher = LegacyCppViewBinding.matcher(header.trim)
      if matcher.find() then Set(matcher.group(1)) else Set.empty
    }

  def isRubyLanguage(language: String): Boolean = language.toUpperCase.contains("RUBY")
  def isPythonLanguage(language: String): Boolean = language.toUpperCase.contains("PYTHON")
  def isJavaScriptLanguage(language: String): Boolean = {
    val upper = language.toUpperCase
    upper.contains("JSSRC") || upper.contains("JAVASCRIPT") || upper.contains("JAVA_SCRIPT")
  }
  def isNewCLanguage(language: String): Boolean = language.toUpperCase.contains("NEWC")

  private def uncommentedHeader(header: String): Option[String] = {
    val normalized = header.trim
    if normalized.isEmpty || normalized.startsWith("#") || normalized.startsWith("//") ||
        normalized.startsWith("/*") || normalized.startsWith("*") then None
    else Some(normalized)
  }

  /** Return the proved Ruby numeric iterator, never merely a lexical range hit. */
  def rubyNumericIterator(header: String): Option[String] = uncommentedHeader(header).flatMap { normalized =>
    if !normalized.contains("..") then None
    else {
      val forMatcher = RubyForRange.matcher(normalized)
      if forMatcher.matches() then Some(forMatcher.group(1))
      else {
        val blockMatcher = RubyBlockRange.matcher(normalized)
        if blockMatcher.find() then Some(blockMatcher.group(4)) else None
      }
    }
  }

  def numericIteratorNames(language: String, header: String): Set[String] = {
    val normalized = header.trim
    if isRubyLanguage(language) then rubyNumericIterator(normalized).toSet
    else if isPythonLanguage(language) then {
      val matcher = PythonFor.matcher(normalized)
      if matcher.matches() && matcher.group(2).trim.matches("(?:x?range)\\s*\\(.*") then
        simplePythonNames(matcher.group(1)).toSet
      else Set.empty
    } else Set.empty
  }

  def simplePythonNames(text: String): List[String] =
    text.replace("(", "").replace(")", "").split(",").iterator
      .map(_.trim).filter(_.matches(Identifier)).toList

  def pythonWalkerNames(targetText: String, sourceText: String): List[String] = {
    val targets = simplePythonNames(targetText)
    val source = sourceText.trim.stripSuffix(":").trim
    def range(expression: String): Boolean = expression.matches("(?:x?range)\\s*\\(.*")
    def inner(call: String): Option[String] = {
      val prefix = call + "("
      if source.startsWith(prefix) && source.endsWith(")") then
        Some(source.substring(prefix.length, source.length - 1).trim)
      else None
    }
    if targets.isEmpty || range(source) then Nil
    else inner("enumerate") match {
      case Some(value) if range(value) => Nil
      case Some(_) if targets.size >= 2 => targets.drop(1)
      case Some(_) => targets
      case None => targets
    }
  }

  def iteratorNames(language: String, header: String): Set[String] = uncommentedHeader(header).toSet.flatMap { normalized =>
    if isRubyLanguage(language) then {
      if rubyNumericIterator(normalized).nonEmpty then Set.empty
      else {
        val matcher = RubyCollectionBlock.matcher(normalized)
        if matcher.find() then matcher.group(1).split(",").iterator.map(_.trim)
          .filter(_.matches(Identifier)).toSet
        else Set.empty
      }
    } else if isPythonLanguage(language) then {
      val matcher = PythonFor.matcher(normalized)
      if matcher.matches() then pythonWalkerNames(matcher.group(1), matcher.group(2)).toSet else Set.empty
    } else if isJavaScriptLanguage(language) then {
      val matcher = JsFor.matcher(normalized)
      if matcher.find() then Set(matcher.group(1)) else Set.empty
    } else if isNewCLanguage(language) then {
      cppRangeIterator(normalized).toSet
    } else Set.empty
  }
}
