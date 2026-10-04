// Joern compiles these files together using its supported Scala CLI file directive.
//> using file model.sc
//> using file profiling.sc
//> using file export_json.sc
//> using file source_index.sc
//> using file iterator_parsing.sc
//> using file role_predicates.sc
//> using file annotation_emission.sc
//> using file pipeline/extraction_context.sc
//> using file facts/binding_facts.sc
//> using file facts/value_lifecycle_facts.sc
//> using file facts/follower_facts.sc
//> using file facts/collection_facts.sc
//> using file facts/succession_facts.sc
//> using file pipeline/fact_assembly.sc
//> using file pipeline/extraction_pipeline.sc

@main def main(output: String = "joern_dynamic_pre_annotations.json", sourceRoot: String = "code"): Unit = {
  extractDynamicVariables(cpg, output, sourceRoot)
}
