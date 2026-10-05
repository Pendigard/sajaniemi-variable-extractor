# Variable-role contracts

The extractor turns Sajaniemi's behavioral roles into conservative static checks over a Joern code property graph (CPG). This guide restates the contracts in Appendix C of the submitted interpretability manuscript as implementation-oriented pseudocode. It describes what this repository checks; a missing role means the available evidence did not satisfy the contract, not that the variable cannot have that behavior at runtime.

## Evidence and execution path

`joern-parse` builds a graph. `src/scala/extract_dynamic_variables.sc` loads the Scala modules; `pipeline/extraction_pipeline.sc` runs extraction context, binding, value-lifecycle, follower, collection, and succession fact passes before `role_predicates.sc` classifies declarations. `annotation_emission.sc` resolves label conflicts and emits source-position hints; `src/sajaniemi_extractor/variable_aware.py` resolves those hints against the original files to make exact spans.

For a declaration `v`, the fact passes distinguish binding writes `W(v)`, initialization events `I(v)`, later writes `U(v)`, reads, state mutations, loop and control contexts, structured right-hand-side expressions, and CPG control/data-flow witnesses. Several roles require an analysis-complete flag. The implementation does not interpret a missing CPG edge or label as proof that an event is impossible. The following pseudocode uses `reject` when required evidence is missing or a disqualifying event is found.

```text
for each source-backed local, parameter, or admitted member declaration v:
    facts[v] = build binding, lifecycle, follower, collection, and succession evidence
    raw_roles[v] = all role predicates proved from facts[v]
    roles[v] = resolve the specified Temporary conflicts in raw_roles[v]
    emit one variable record and one annotation per retained role
```

Most roles may coexist. The checks below are summarized at the decision level; the named fact modules build the source-backed witnesses and handle frontend-specific CPG shapes.

## Contracts

### Fixed value

A fixed value holds one binding per execution path without an observed update or state mutation. It consumes initialization, binding-write, loop, and mutation evidence from `facts/value_lifecycle_facts.sc` and collection evidence from `facts/collection_facts.sc`.

```text
if v is a verified non-text collection: return collection_decision(v) == Fixed
require verified initialization and at most one initialization per path
reject later or external rebinding, self-updates, state mutation,
       or loop initialization/rebinding
accept
```

Parameters count as initialized on entry; local and member evidence has different initialization checks. In `role_predicates.sc`, `isFixedValue` delegates scalar cases to `isScalarFixedValue` and collections to `computeCollectionRoleDecision`.

### Temporary

A temporary holds a locally produced value for a short, bounded use. It consumes direct writes, reaching-definition/read associations, positions, and loop CFG witnesses from `facts/value_lifecycle_facts.sc`.

```text
require a genuine callable-local v, complete read associations, and at least one write
for each write w:
    require a direct, plain, non-self assignment from a supported source
    reject import/definition artifacts
    require 1..5 associated reads; each read is within distance 0..5
require that removing v's writes disconnects loop-entry/back-edge paths to its reads
accept
```

Different-line distance uses source lines; same-line or missing-line cases use an instruction/event distance. Ambiguous loop CFG evidence rejects the role. The predicate is assembled from `allTemporaryAssignmentShape`, `hasReadAfterAllWrite`, `shortLiveRange`, `doesNotCrossLoopBoundaries`, and `hasNoTemporaryUpdate` in `role_predicates.sc`.

### One-way flag

A one-way flag starts at one Boolean value and can move only toward its opposite value. It consumes canonical binding identity, Boolean initialization, and complete transition evidence from `facts/succession_facts.sc`.

```text
require complete Boolean initialization b and at least one later transition
for each transition:
    require an assignment of not b, or a supported monotone expression
    (v OR e from false; v AND e from true)
require at least one transition that can reach the opposite value
accept
```

`e` must have supported Boolean structure; an inert constant alone does not establish a change. `isOneWayFlagFlow` checks structure, not satisfiability of the surrounding guard.

### Stepper

A stepper follows a predictable progression. It consumes an initialization partition, repeated-context updates, structured expressions, guards, and dependencies built in `facts/succession_facts.sc`.

```text
proved = implicit numeric iterators
candidates = declarations with complete repeated progression and only direct,
             self-dependent, supported arithmetic updates and supported guards
repeat:
    add candidate v if every non-self update dependency is a raw Fixed Value
        or already proved Stepper, and every guard dependency is a proved
        Stepper or a Fixed Value with a predictable seed
until no candidate is added
return proved
```

`rawStepperCandidate` and `provedStepperIds` implement the candidate check and fixed point. Ordinary element walkers are excluded. Supported updates use unary plus/minus or binary plus, minus, multiplication, or division; the implementation explicitly rejects the optional `fixed - self` toggle form. Unlike a simple increment detector, a guard depending on an immutable but unpredictably acquired value is insufficient.

### Walker

A walker moves through positions or elements of a complex structure. It consumes iterator and navigation transitions from `facts/binding_facts.sc` and `facts/succession_facts.sc`.

```text
if v is an implicit element iterator and not a numeric iterator: accept
require complete navigation and initialization evidence with later writes
for every later write:
    require previous-position dependence, loop context, and access/control use
    require a resolved member, approved fixed-collection successor index,
            supported navigation method, or direct cursor progression
accept
```

`provedStructuralWalker` and `isWalkerFact` perform these checks. An ordinary array index alone is not a walker witness.

### Most-recent holder

A most-recent holder repeatedly acquires a current value and uses it after acquisition. It consumes acquisitions, correction chains, uses, and owner/loop context from `facts/succession_facts.sc`.

```text
require complete acquisition/correction coverage of later writes
require at least one acquisition; reject holder-controlled acquisition guards
require stable-input corrections and a supported use witness
for a non-member: require loop or proved-iterator context
for a member: require persistent same-owner method/use evidence
reject if Walker, Most Wanted Holder, or Follower is proved
accept
```

`isRawMostRecentHolderFact` and `isMostRecentHolderFact` implement this ordering. Non-member correction witnesses are bounded in the fact pass (depth 64, nodes 10,000, corrections 5, branches 128); member evidence does not prove cross-call execution order.

### Follower

A follower tracks one master variable or stable offset from it across an evolution cycle. It consumes canonical master sources, reassignments, mutations, guard dependencies, and loop/method CFG cycles from `facts/follower_facts.sc`.

```text
require complete, nonempty non-initial reassignment evidence
reject self-dependence, independent mutation, follower-controlled writes,
       or master evolution controlled by the follower
master = the unique valid source shared by every reassignment
require each reassignment to use only master or master plus/minus a fixed offset
require a capture -> master evolution -> follower use -> capture witness
        in every relevant local loop or persistent-member method
accept
```

`isFollower` performs the final check. Fixed indexes and offsets may be literals or raw Fixed Values; member sources must resolve to a consistent owner. Ambiguous masters or cycles fail.

### Most-wanted holder

A most-wanted holder keeps a best candidate, possibly with a companion index or value. It consumes initialization seeds, guarded comparisons, replacement epochs, and coupled writes from `facts/succession_facts.sc`.

```text
primary(v) = complete compatible seed and nonempty selection epochs
             with one selection direction and all writes explained
reject primary if Stepper, Walker, Gatherer, or Follower is proved
if primary(v): accept
if v has later writes uniquely coupled to one primary's guard/context,
   dependencies are Fixed Values/Steppers/Walkers, it is read later,
   and it has no mutation: accept as companion
reject
```

`isPrimaryMostWantedFact`, `primaryMostWantedIds`, and `mostWantedCompanionsById` implement this. Supported selections include holder–candidate comparisons, min/max, and structurally evidenced first-satisfying selection from a null seed.

### Gatherer

A gatherer accumulates data contributions through numeric self-updates. It consumes numeric admissibility, initialization, loop spans, resets, transforms, and finalizations from `facts/succession_facts.sc`.

```text
require an admitted non-collection numeric variable and complete phase coverage
require a parameter, stable numeric seed, or supported first contribution
reject if Stepper or Walker is proved
require at least one loop span with a source-backed data contribution
for each span: require self-updates and at least one actual data contribution
require every later write to be explained as a reset, data update,
        stable transform, or permitted finalization
require stable resets and at most one stable finalization per method outside loops
accept
```

A data contribution comes from indexed/member/dereferenced data or a dependency that is neither a raw Fixed Value nor a proved Stepper. A call counts only through structured argument data. `isNumericGathererFlow` checks the complete phase partition; literal-only counting is not enough.

### Container and organizer

These roles use one exclusive collection decision. They consume verified non-text collection identity, exact-target changes, initialization, and proved permutation spans from `facts/collection_facts.sc`.

```text
if v is not a verified non-text collection or has a synthetic/function binding:
    return Unclassified
consumed = event IDs explained by proved permutation spans
other_changes = post-construction collection events minus consumed
if other_changes is nonempty: return Container
if there is a proved permutation and initialization is verified and complete:
    return Organizer
if initialization and full lifecycle are verified and complete:
    return Fixed Value
return Unclassified
```

A **container** has a post-construction mutation or rebinding beyond proved permutations. An **organizer** only has proved reordering after initialization, such as an exact-target sort/reverse/rotate or a source-backed swap. Alias-only effects and ambiguous manual swaps do not establish these roles. `computeCollectionRoleDecision` gives Container precedence over Organizer and collection Fixed Value.

## Label precedence and implementation boundaries

`annotation_emission.sc` retains multiple proved roles except for two explicit Temporary conflicts: a competing role other than Fixed Value removes Temporary; if only Temporary and Fixed Value coexist, Temporary removes Fixed Value. Member records omit Temporary, Gatherer, and Most-wanted Holder. Raw Fixed Value predicates are still used as dependencies even when a final label is suppressed. Python resolution can merge records that map to the same exact source identity; raw `pre.json` and resolved outputs therefore need not have identical annotation counts.

Appendix C is a compact conceptual specification. The implementation has additional source-backed completeness, identity, owner, and ambiguity checks in the fact passes, plus frontend-specific handling for the five languages. In particular, the optional Sajaniemi `fixed - self` Stepper form is deliberately unsupported, and member role emission is narrower than the local/parameter predicate list. The manuscript itself notes these boundaries; this document names their source locations so changes to the extractor can be checked against the contract.
