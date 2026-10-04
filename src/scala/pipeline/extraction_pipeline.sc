import io.shiftleft.codepropertygraph.Cpg

/** Batch-only orchestration. Scientific logic lives in the explicit phase modules. */
def extractDynamicVariables(graph: Cpg, output: String, sourceRoot: String): Unit = {
  val context = buildExtractionContext(graph, sourceRoot)
  val bindings = extractBindingFacts(context)
  val lifecycle = extractValueLifecycleFlows(context, bindings)
  val followers = extractFollowerFlows(context, bindings, lifecycle)
  val collections = extractCollectionFlows(context, bindings, lifecycle, followers)
  val succession = extractSuccessionFlows(context, bindings, lifecycle, followers, collections)
  val assembly = assembleVariableFacts(context, bindings, lifecycle, followers, collections, succession)

  import context.*
  import bindings.*
  import lifecycle.*
  import followers.*
  import collections.*
  import succession.*
  import assembly.*
  val classification = profiler.timed("role_classification") { classifyRoles(
    RolePredicateContext(
      extractedFacts.variables,
      extractedFacts.members,
      extractedFacts.loopRanges,
      extractedFacts.controlRanges,
      declById,
      (method, line) => loopAt(method, line),
      (method, line) => controlAt(method, line),
      (name, value) => profiler.set(name, value)
    )
  ) }
  val variableAwareOutput = profiler.timed("annotation_emission") { emitVariableAwareOutput(classification) }
  profiler.set("role_annotations", variableAwareOutput.roleAnnotations.size.toLong)
  profiler.set("variable_facts", variableAwareOutput.variableFacts.size.toLong)
  profiler.timed("json_export") { exportVariableAwareJson(output, variableAwareOutput) }
  profiler.finish()
  println(
    s"Wrote ${variableAwareOutput.roleAnnotations.size} role annotations and " +
    s"${variableAwareOutput.variableFacts.size} variable facts to $output"
  )
}
