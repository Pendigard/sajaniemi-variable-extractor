//> using file ../src/scala/iterator_parsing.sc

@main def main(): Unit = {
  import IteratorParsing.*

  assert(numericIteratorNames("RUBYSRC", "for numeric_index in 1..10") == Set("numeric_index"))
  assert(numericIteratorNames("RUBYSRC", "for numeric_index in 1...limit") == Set("numeric_index"))
  assert(numericIteratorNames("RUBYSRC", "(1..limit).each do |numeric_index|") == Set("numeric_index"))
  assert(iteratorNames("RUBYSRC", "collection.each { |element| puts element }") == Set("element"))
  assert(iteratorNames("RUBYSRC", "collection.each do |element|") == Set("element"))
  assert(iteratorNames("RUBYSRC", "for element in collection") == Set.empty)

  assert(numericIteratorNames("PYTHONSRC", "for numeric_index in range(10):") == Set("numeric_index"))
  assert(iteratorNames("PYTHONSRC", "for numeric_index in range(10):") == Set.empty)
  assert(iteratorNames("PYTHONSRC", "for element in collection:") == Set("element"))

  List("auto u", "auto &u", "auto& u", "auto & u", "auto&& u", "auto &&u",
    "const auto &u", "const auto& u", "ns::Value &u", "auto* u", "Object* u").foreach { binding =>
    assert(iteratorNames("NEWC", s"for ($binding : values) use(u);") == Set("u"), binding)
  }
  assert(iteratorNames("NEWC", "for (auto u = ns::value; u < 10; ++u)").isEmpty)
  assert(iteratorNames("NEWC", "for (auto &u : ns::values)") == Set("u"))
  assert(iteratorNames("NEWC", "for (auto [u, v] : values)").isEmpty)

  val spaced = " " * 1000 + "let delayed = 1;"
  val minified = "function f(){for(const item of values){use(item)}}" * 1000
  val nonRubyRange = "for value in 1..10"
  val javascriptHeaders = List(
    spaced, minified, "// for index in 1..10", "/* 1...limit */",
    "for (const key in object) {}", "for (const value of values) {}", nonRubyRange
  )
  javascriptHeaders.foreach { header =>
    assert(numericIteratorNames("JSSRC", header).isEmpty)
    assert(rubyNumericIterator(header).isEmpty || header == nonRubyRange)
  }
  assert(iteratorNames("JSSRC", "for (const key in object) {}") == Set("key"))
  assert(iteratorNames("JSSRC", "for (const value of values) {}") == Set("value"))
  assert(numericIteratorNames("NEWC", nonRubyRange).isEmpty)

  val started = System.nanoTime()
  (0 until 10000).foreach(_ => numericIteratorNames("JSSRC", spaced))
  val elapsedSeconds = (System.nanoTime() - started).toDouble / 1e9
  assert(elapsedSeconds < 20.0, s"pathological JavaScript parser case took $elapsedSeconds seconds")
  println(s"ITERATOR_PARSER_OK elapsed_seconds=$elapsedSeconds")
}
