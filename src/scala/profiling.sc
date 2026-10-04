import scala.collection.mutable

/** Lightweight opt-in profiler for the batch extractor.
  *
  * Heap values are samples of JVM managed memory (`totalMemory - freeMemory`),
  * not process RSS and not a guaranteed peak. External runners may complement
  * them with OS-specific maximum-RSS measurements.
  */
final class ExtractionProfiler private (val enabled: Boolean, val language: String) {
  private val startedAt = System.nanoTime()
  private val durationsNs = mutable.LinkedHashMap.empty[String, Long]
  private val counters = mutable.LinkedHashMap.empty[String, Long]
  private val runtime = Runtime.getRuntime
  private var peakSampledHeapBytes = usedHeapBytes

  private def usedHeapBytes: Long = runtime.totalMemory() - runtime.freeMemory()

  private def sampleHeap(): Unit =
    if enabled then peakSampledHeapBytes = math.max(peakSampledHeapBytes, usedHeapBytes)

  def timed[T](name: String)(body: => T): T =
    if !enabled then body
    else
      val start = System.nanoTime()
      try body
      finally
        val elapsed = System.nanoTime() - start
        durationsNs.update(name, durationsNs.getOrElse(name, 0L) + elapsed)
        sampleHeap()

  /** Accumulate a hot-path duration without sampling heap for every element.
    * Heap is still sampled at the surrounding coarse `timed` phase and finish.
    */
  def timedAccumulating[T](name: String)(body: => T): T =
    if !enabled then body
    else
      val start = System.nanoTime()
      try body
      finally
        val elapsed = System.nanoTime() - start
        durationsNs.update(name, durationsNs.getOrElse(name, 0L) + elapsed)

  def increment(name: String, amount: Long = 1L): Unit =
    if enabled then counters.update(name, counters.getOrElse(name, 0L) + amount)

  def set(name: String, value: Long): Unit =
    if enabled then counters.update(name, value)

  def maximum(name: String, value: Long): Unit =
    if enabled then counters.update(name, math.max(counters.getOrElse(name, 0L), value))

  private def escape(value: String): String =
    value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    }

  def finish(): Unit =
    if enabled then
      sampleHeap()
      val totalNs = System.nanoTime() - startedAt
      val durations = durationsNs.toSeq.sortBy(_._1)
        .map((name, value) => s"\"${escape(name)}\":$value").mkString("{", ",", "}")
      val countJson = counters.toSeq.sortBy(_._1)
        .map((name, value) => s"\"${escape(name)}\":$value").mkString("{", ",", "}")
      System.err.println(
        "SAJANIEMI_SCALA_PROFILE=" +
        s"{\"schema_version\":1,\"language\":\"${escape(language)}\",\"total_ns\":$totalNs," +
        s"\"used_heap_bytes\":${usedHeapBytes},\"peak_sampled_heap_bytes\":$peakSampledHeapBytes," +
        s"\"durations_ns\":$durations,\"counters\":$countJson}"
      )
}

object ExtractionProfiler {
  def fromEnvironment(language: String): ExtractionProfiler =
    val enabled = Option(System.getenv("SAJANIEMI_PROFILE")).contains("1")
    new ExtractionProfiler(enabled, language)
}
