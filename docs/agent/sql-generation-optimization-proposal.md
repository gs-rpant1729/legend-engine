# Generating Optimized SQL: Analysis and Proposal

Analysis of the current Pure→SQL generation strategy (`pureToSQLQuery.pure`, dialect
string-gen via `snowflakeExtension.pure`) and a layered proposal for producing highly
optimized SQL — covering generation-time fixes in `manageIsolation`/`mergeSQLQueryData`,
a rule-based SQL-AST optimizer post-processor, user/model-provided hints, and the
option of an external platform optimizer.

All file references are to
`legend-engine-xts-relationalStore/.../core_relational/relational/` unless noted.

---

## 1. How generation works today (the parts that matter for optimization)

The pipeline (see `docs/engineering/architecture/router-and-pure-to-sql.md`) is:
routed Pure expression → `toSQLQuery` builds a `SelectSQLQuery` AST → post-processor
chain → per-dialect `sqlQueryToString` rendering. Three structural mechanisms decide
almost all of the SQL's *shape*; the dialect layer only decides its *spelling*.

### 1.1 Thread-per-column compilation + `mergeSQLQueryData`

`project()` compiles **each projection column as an independent query thread**, each
producing its own `SelectSQLQuery` with its own join tree. `mergeSQLQueryData`
(pureToSQLQuery.pure:8581) then folds them pairwise into one query via `merge()`
(pureToSQLQuery.pure:8459). There are ~20 call sites (binary ops, if/then/else,
groupBy, qualifiers, calendar aggregation, PK+property merges in
`relationalMappingExecution.pure`), so its quality dominates output quality.

The critical detail: **`merge()` matches join-tree children by join *name* only** —

```pure
let commonNode = $a.node->children()->cast(@JoinTreeNode)
                     ->filter(jtn|eq($jtn.join.name, $childNode.join.name));   // :8466
```

Names are the merge key by design: `joinName()` (pureToSQLQuery.pure:7503) embeds the
filter's `buildUniqueName` into the name, so identical inline filters merge and
different filters isolate. But the same mechanism means *any* name divergence defeats
merging even when the joins are semantically identical (see §2.1).

Before merging, `mergeSQLQueryData` re-runs isolation on every input if any two
threads have different `__iso`-node signatures (`hasIsolations`, :8586) — a coarse,
all-or-nothing trigger.

### 1.2 `manageIsolation` + `isolateSubJoins`

Isolation exists to preserve Pure semantics when a filter rides on a shared join tree:
a filter compiled for one projection thread must not cancel rows of another thread
(left-join semantics of implicit toMany explosion), and inner joins must not leak
row-multiplication into filters. The decision (pureToSQLQuery.pure:7390-7443):

```pure
filterShouldIsolate = !savedFilteringOperation->isEmpty();
innerShouldIsolate  = data has children && data->containsInnerJoin();   // GLOBAL test
finalIsolationDecision = state.shouldIsolate && (innerShouldIsolate || filterShouldIsolate);
```

`containsInnerJoin` (:7459) is **tree-global**: one inner join anywhere in the tree
forces the isolation path for a filter that may only touch an unrelated toOne chain.

`isolateSubJoins` (:7545) picks one of three strategies (`IsolationStrategy`, :7496),
with documented trade-offs in the code:

| Strategy | What it emits | Cost |
|---|---|---|
| `MoveFilterOnTop` | filter → outer WHERE | cheapest; only safe when no toMany interplay |
| `MoveFilterInOnClause` | filter ANDed into the join ON, LEFT OUTER | no subquery, but "doesn't prevent records explosion" (code comment :7614) |
| `BuildCorrelatedSubQuery` | node's subtree wrapped in a nested `SelectSQLQuery`, join renamed `…csq` | "expensive during execution, reduces the scope of columns" (:7604) |

Escalation is pessimistic: `MoveFilterOnTop` degrades to `BuildCorrelatedSubQuery`
whenever the root contains *any* inner join (:7592). When no suitable node is found on
a projection thread, a **full self-join of the driver relation** is added
(`addSelfJoinOnNode`, alias prefix `gen_`, :7522/4153) purely to create an isolation
boundary — an extra scan of a potentially large table that exists only for the
generator's bookkeeping.

### 1.3 Existing optimization surface

- **Post-processor chain** (`postprocessor/defaultPostProcessor/defaultPostProcessor.pure:58`):
  CTE reference collection → `pushFiltersDownToJoins` → `removeUnionOrJoins` →
  re-aliasing → SQL comments. Connection-level `sqlQueryPostProcessors` and
  `RelationalExecutionContext.postProcessors` allow injection of more.
- **`pushFiltersDownToJoins`** (`pushFiltersDownToJoin.pure`): propagates WHERE
  predicates across join-equality columns into join ON clauses and *into subselects*
  (WHERE or HAVING as appropriate), with careful bail-outs (window columns, LIMIT,
  RIGHT/FULL joins, unknown elements). Notably it does **not** descend into `Union`
  branches (`tryPushFiltersIntoSubQuery` matches only `SelectSQLQuery`/`ViewSelectSQLQuery`).
- **`removeUnionOrJoins`** (`removeUnionOrJoinsPostProcessor.pure`): the template for
  everything this proposal recommends — an opt-in structural rewrite, **gated by
  dialect (Snowflake) and by `GenerationFeaturesConfig` enable/disable lists on the
  connection**. This is an existing, protocol-supported hint channel.
- **`processInOperation`**: large IN-lists → temp table joins (dialect thresholds,
  e.g. Snowflake `collectionThresholdLimit = 16348`).
- **Dialect layer** (`snowflakeExtension.pure`): pure string rendering — literal
  processors, dyna-function dispatch, window/lateral/asof rendering. It contains **no
  structural rewriting and no hook designed for optimization**; the only "optimizer
  aware" knobs today are per-dialect processors chosen at `DbExtension` construction.

### 1.4 Canonicalization primitives that already exist

`buildUniqueName(…)` is already used as a semantic fingerprint — for filter dedup
(`removeDuplicatesBy(f|$f->buildUniqueName(true, $extensions))`, :8533/8656) and
inside `joinName()`. `findOneNode`/`replaceTreeNode`/`reprocessAliases`/`transformNonCached`
form a working AST-rewriting toolkit. Any optimizer framework can build on these
rather than inventing new machinery.

---

## 2. Where suboptimal SQL comes from — failure-mode catalog

Each item names the mechanism, so the proposal's rules can be traced back to a cause.

**F1 — Merge failure by name divergence → duplicate join trees.**
`merge()` keys on `join.name`. Names diverge for semantically identical joins when:
(a) isolation renamed one side (`__iso_…`, `…csq` suffixes, `moveFiltersOnTop` renames
joins *specifically so they are not shared*, :1170); (b) filters that are equivalent
but not `buildUniqueName`-identical (conjunct order, literal spelling) were embedded
into the name; (c) `applyJoinInTreeDeep` generated a fresh name to avoid a sibling
collision (:8711). Result: the same table joined N times through the same join
condition, N scans, and — because each copy carries its own filter — potential
row duplication that then needs `distinct` to paper over.

**F2 — Isolation is triggered too widely.** The `containsInnerJoin` test is global
(§1.2), and `mergeSQLQueryData`'s `hasIsolations` re-isolates *every* thread if any
two differ. Both inflate the number of correlated subqueries/self-joins beyond what
the touched paths require.

**F3 — Isolation adds joins that exist only for bookkeeping.** `gen_` self-joins on
the root (§1.2) and `csq` correlated subqueries wrap subtrees in nested selects whose
column lists are recomputed defensively (`addExtraJoinColumns`, :8672). Nested
selects with DISTINCT/groupBy block database optimizers from unnesting — noted
explicitly in `docs/agent/tomany-filter-modeljoin-strategies.md` §3.B ("Legend often
emits nested subselects with DISTINCT that block DB optimizers").

**F4 — `exists` + inline-filter duplication.** `filter(f|$f.emps->exists(pred))` +
`project(…->filter(pred)…)` executes the child-table predicate twice (distinct
subselect join + explosion join). Documented as strategy A in the toMany doc.

**F5 — No cardinality awareness.** A join that is provably to-one (FK→PK: join
equalities cover the target's declared `primaryKey`) can never duplicate or cancel
rows differently across threads — isolation for it is pure waste. Nothing in
`manageIsolation` or `merge` consults `Table.primaryKey` or join-cardinality.

**F6 — No column pruning.** Correlated subqueries and materialized subselects carry
every defensively-added column; unions project all branch columns. Wide intermediate
projections cost real money on columnar warehouses (Snowflake).

**F7 — No join elimination.** LEFT OUTER joins whose target contributes no referenced
column (common after merging PK-only threads with property threads) survive to the
final SQL. With declared/derivable uniqueness, they are removable.

**F8 — Filters never reach union branches.** `tryPushFiltersIntoSubQuery` skips
`Union` — a filter over a union-mapped class scans every branch fully before
filtering.

**F9 — No subquery unnesting after the fact.** Isolation decisions are made locally,
early, per-thread. By the time the full query is assembled, many `csq` subselects are
trivially flattenable (no groupBy/distinct/window inside, filter now provably local)
— but nothing revisits them.

**F10 — Dialect strengths unused.** Snowflake has `QUALIFY` (and `qualifyOperation`
already exists on `SelectSQLQuery`), semi-join-friendly `EXISTS`, lateral joins
(recently added), and excellent decorrelation for `EXISTS` but poor handling of
join-to-distinct-subselect patterns. Strategy selection in `isolateSubJoins` is
dialect-blind; the exists-vs-join arbitration (`shouldBuildExistsPredicate`, :5392)
likewise.

---

## 3. Design constraints (why "just rewrite the generator" is wrong)

1. **Semantics first.** `manageIsolation`/`mergeSQLQueryData` encode hard-won Pure
   semantics (row-explosion conservation, per-thread filter scoping, milestoning
   interplay at `applyJoinInTreeDeep:8733`). Changes must be provably
   row-set-preserving, and the arbiter is PCT + the merge-rule tests
   (`tests/testMergeRules.pure`).
2. **Golden-SQL test estate.** Thousands of tests assert exact SQL strings. Any
   default-on structural change is a mass test migration. Therefore: every rewrite
   ships **feature-gated** (the `removeUnionOrJoins` pattern — dialect allowlist +
   `GenerationFeaturesConfig` enable/disable) and graduates to default-on per dialect
   only after PCT parity.
3. **Plan caching and placeholders.** Generated SQL carries FreeMarker
   `VarPlaceHolder`s and temp-table references; any optimizer must treat them as
   opaque and must be deterministic (same AST in → same SQL out) to keep plan caches
   valid.
4. **Two viable altitudes.** Fixes can land (a) *inside generation* (merge/isolation
   produce better trees in the first place) or (b) *after generation* (post-processor
   rewrites the assembled AST). (a) is precise but touches the scariest code; (b) is
   contained, independently testable, and already has a framework. The proposal uses
   both deliberately: generation-time only for what post-processing cannot recover
   (e.g. avoiding a `gen_` self-join is easier than proving it removable later).

---

## 4. Proposal

Four tracks, independently shippable. A/B are the core; C feeds both with
information; D is an evaluation/oracle strategy rather than a hot-path dependency.

### Track A — Generation-time fixes in `merge` / `manageIsolation`

**A1. Semantic join signatures for merging (fixes F1).**
Introduce `joinSignature(jtn): String` = canonical form of
`(target relation identity, joinType, canonicalized join operation, canonicalized
savedFilter set)`, where canonicalization = `buildUniqueName` extended with: sorted
conjuncts under `and`/`or`, alias-normalized `TableAliasColumn`s (positional, not
name-based), normalized literal rendering. `merge()` keeps the name-equality fast
path but falls back to signature equality before declaring "no common node". The
`__iso_`/`csq` prefixes stay *out* of the signature (they encode provenance, not
semantics) — but a signature match between an isolated and non-isolated node merges
only when both sides' filter sets match, preserving the intent of filter-encoded
names. This single change removes the largest class of duplicate joins.

**A2. Path-local isolation trigger (fixes F2).**
Replace the global `containsInnerJoin()` with: *does the path from root to any node
referenced by the filter (via `extractTableAliasColumns().alias`) contain an inner
join, or does the filter's node have toMany children?* The thread-walking machinery
(`buildThreads`, `findBestNodeToIsolate`) already computes exactly these paths.
Similarly, in `mergeSQLQueryData`, replace the binary `hasIsolations` re-isolation
with per-thread comparison against the accumulated tree (isolate only threads whose
iso-set actually conflicts).

**A3. Cardinality-aware isolation skip (fixes F5, biggest single win).**
Add `isToOneJoin(join, targetRelation): Boolean` — true when the join operation is a
conjunction of equalities whose target-side columns cover `targetRelation.primaryKey`
(Tables and Views both declare it). Thread it into:
- `manageIsolation`: a filter whose referenced aliases are reachable through
  exclusively to-one joins needs **no isolation at all** — `MoveFilterOnTop` is
  always safe (it cannot cancel sibling-thread rows because it cannot lose rows the
  projection would have kept, and cannot multiply).
- `containsInnerJoin` replacement in A2: an inner to-one join is not a
  multiplication risk.
- `merge()`: two to-one joins with equal signatures merge even when their inline
  filters differ (filters can be lifted to WHERE conjuncts) — today they isolate.
Where PKs are undeclared, C2's hints supply the same fact.

**A4. Fuse `exists(pred)` + inline `->filter(pred)` (fixes F4).**
As specced in the toMany doc §3.A: when an exists-isolation subselect and a
projection explosion share the association join and the predicates'
`buildUniqueName`s match (after A1 canonicalization, which directly de-brittles
this), drop the exists join and flip the explosion join to INNER.

**A5. Dialect-informed strategy selection (fixes F10 at the source).**
`isolateSubJoins` currently picks strategies dialect-blind. Give `DbExtension` a
small capability record (proposal: `optimizationCapabilities` — e.g.
`supportsQualify`, `prefersExistsOverDistinctJoin`, `supportsLateral`,
`decorrelatesWell`) threaded via the `DbConfig`/connection that plan generation
already has. Concretely for Snowflake: prefer `EXISTS` semi-join lowering over
`BuildCorrelatedSubQuery`'s join-to-distinct-subselect; lower "isolate a filter on a
window column" to `QUALIFY` instead of a wrapping subselect. Note generation today is
sometimes dialect-agnostic until stringification; the capability record must default
to conservative ANSI behaviour when no connection is known.

### Track B — A rule-based SQL-AST optimizer (post-processor framework)

The assembled `SelectSQLQuery` is a complete relational algebra tree, and the
post-processor chain is the right, existing seam. Generalize it from a hard-coded
list into an optimizer:

```pure
Class meta::relational::postProcessor::optimizer::OptimizerRule
{
  name          : String[1];                       // doubles as the GenerationFeaturesConfig key
  isApplicable  : Function<{SelectSQLQuery[1], OptimizerContext[1] -> Boolean[1]}>[1];
  apply         : Function<{SelectSQLQuery[1], OptimizerContext[1] -> SelectSQLQuery[1]}>[1];
}

Class meta::relational::postProcessor::optimizer::OptimizerContext
{
  dbType        : DatabaseType[0..1];
  capabilities  : OptimizationCapabilities[0..1];  // from DbExtension (A5)
  config        : GenerationFeaturesConfig[0..1];  // user enable/disable
  statistics    : RelationStatistics[*];           // from C2 hints, optional
  fired         : String[*];                       // audit trail of applied rules
}
```

Runner: apply enabled rules **to a fixpoint with an iteration cap** (e.g. 5 passes —
rules enable each other: dedup exposes prunable columns, pruning exposes removable
joins), record `fired` into the debug output and (optionally) as a SQL comment beside
the existing `executionTraceID`. Rules are contributed via the relational `Extension`
so dialect modules and users can register their own; ordering by declared phase
(normalize → prune → restructure → dialect).

Rule catalog, in recommended build order (each maps to failure modes):

| # | Rule | Fixes | Sketch |
|---|---|---|---|
| R1 | **Predicate normalize/simplify** | enabler | canonical conjunct order; drop `1=1`/duplicate conjuncts (reuse `buildUniqueName` dedup); constant-fold comparisons over `Literal`s; `OR`-of-equals → `IN`; detect contradictions → prune branch (feeds R7) |
| R2 | **Duplicate join dedup** | F1 | within one tree, unify sibling/descendant `JoinTreeNode`s with equal A1-signatures; catches what pairwise `merge()` missed due to fold order or post-isolation renames |
| R3 | **Column pruning** | F6 | top-down required-column sets through subselects, unions (per-branch), CTEs; drop unreferenced columns from nested selects; never touch `SELECT` shape of the outermost query |
| R4 | **Join elimination** | F7, F3 | remove LEFT OUTER joins contributing no referenced columns when target is unique on the join key (PK-derived or C2 hint); specifically target `gen_` self-joins (provably PK-preserving by construction) and `csq` wrappers with empty effective column contribution |
| R5 | **Subquery unnesting** | F9, F3 | inline a child `SelectSQLQuery` into its parent when it has no groupBy/distinct/window/limit/qualify and its filter references only its own tree — the post-hoc inverse of over-eager isolation; strictly the mirror of `pushFiltersDownToJoins`' bail-out list |
| R6 | **Union filter pushdown** | F8 | extend `tryPushFiltersIntoSubQuery` to map filters through `Union.queries` column alignment; with R1's contradiction detection this also prunes statically-dead union branches (set-id filters) |
| R7 | **Distinct elimination** | F3 | drop `distinct` when the projected set covers a key of the (post-R4) tree |
| R8 | **Semi-join / EXISTS rewrite** | F10 | join-to-distinct-subselect ↔ `EXISTS` correlated predicate, direction chosen by `capabilities`; Snowflake: prefer EXISTS |
| R9 | **QUALIFY / window rewrites** | F10 | filter-on-window-column subselect → `QUALIFY` (Snowflake/DuckDB/Databricks); top-N-per-group join patterns → `ROW_NUMBER()` + QUALIFY |
| R10 | **Pre-aggregation pushdown** | perf | push `sum/count/min/max` below a to-one join when grouping keys functionally determine the join key (needs C2 stats/uniqueness; highest risk, last) |

Every rule ships with: PCT run in both states (rule on/off must both pass — PCT is a
row-set oracle, insulated from SQL-string changes), targeted golden tests for the
rewrite itself, and an H2/DuckDB differential harness (execute optimized vs
unoptimized SQL over the standard test schemas, compare row sets) for randomized
regression hunting.

### Track C — User- and model-provided hints

Three channels, two of which already exist:

**C1. Connection / execution-context flags (exists — extend).**
`GenerationFeaturesConfig.enabled/disabled` is the rule on/off switch (each
`OptimizerRule.name` is a feature key — exactly how `REMOVE_UNION_OR_JOINS` works
today). Add a query-level equivalent on `RelationalExecutionContext` for per-service
overrides (services are where perf regressions are actually triaged).

**C2. Model-level physical metadata (new, feeds A3/R4/R7/R10).**
The store DSL already declares `primaryKey`. Add optional, non-semantic physical
hints in the `###Relational` grammar (parser+composer+round-trip test per repo
convention):

```
Table SALES (…)
  meta::stats(rowCount = 2000000000)          // magnitude only, drives R10/strategy choice
Join FIRM_PERSON(…) meta::cardinality(toOne)   // asserts FK→PK where PK undeclared
Column ACCOUNT_ID meta::unique                 // uniqueness w/o PK declaration
```

Cardinality assertions are *trusted* facts with the same status as `primaryKey`
(wrong hints → wrong results is already true of wrong PKs today — document it).
`rowCount` is advisory only. These flow into `OptimizerContext.statistics` and into
A3's `isToOneJoin`.

**C3. Dialect hint emission (string-gen layer).**
Add one `DbExtension` hook — `hintsProcessor(hints: SqlHint[*], sgc): String` — and a
`hints: SqlHint[*]` property on `SelectSQLQuery`, populated from execution-context
hints or optimizer rules. Snowflake has almost no inline hints (this hook is mostly
a no-op there), but MemSQL/SQLServer/Oracle/Sybase IQ users routinely need
`/*+ … */` / `OPTION(…)` injection and currently resort to string post-processors.
Cheap, contained, and keeps hint text out of core generation.

### Track D — Platform / external optimizer (Calcite-class)

Evaluated honestly: translating `SelectSQLQuery` → Apache Calcite `RelNode` (or
Substrait), running HepPlanner rule sets (it has mature decorrelation, pruning, join
elimination), and rendering back via RelToSql would buy the R1–R7 catalog "for free".
Costs: a Java-side round-trip in the middle of a Pure pipeline (post-processing runs
in Pure today; it would have to move to a `PlanGeneratorExtension` operating on the
serialized query), fidelity risk for Legend-specific AST nodes (FreeMarker
placeholders, temp tables, semi-structured/variant ops, `ViewSelectSQLQuery`,
milestoning comments), dialect-rendering mismatches with the carefully-tuned
`DbExtension`s, and loss of the debug/provenance story.

**Recommendation: not in the hot path.** Use Calcite as an **offline equivalence
oracle and benchmark**: a test-only module that round-trips generated SQL through
Calcite to (a) flag semantic diffs between optimized/unoptimized outputs beyond what
row-set testing catches, and (b) measure how much headroom remains after Track B (if
Calcite finds a big rewrite the rule set misses, that's the next rule to write). The
databases themselves (especially Snowflake) already are the "platform optimizer" —
Track B's job is precisely to stop emitting the patterns (nested DISTINCT
subselects, correlated joins, dead columns) that defeat them, then let them do CBO.

---

## 5. Rollout and verification strategy

1. **Gate everything** behind `GenerationFeaturesConfig` keys; default-off except
   where a dialect owner opts in (Snowflake first — the `removeUnionOrJoins`
   precedent, and warehouse cost is where the ROI is).
2. **PCT as the semantic gate**: every rule/change runs the full default-profile PCT
   (H2, DuckDB, Java binding) with the feature forced on via a
   `testRuntimeWith<Feature>Enabled` wrapper (pattern exists,
   removeUnionOrJoinsPostProcessor.pure:60).
3. **Differential row-set harness** (new, small): execute optimized vs unoptimized
   SQL for the existing relational test-suite queries on H2/DuckDB; assert row-set
   equality. This catches what golden-string tests can't (they'd just be updated) and
   what PCT doesn't cover (mapping-heavy shapes).
4. **Observability**: emit fired-rule names into the debug print and a structured
   plan annotation; add cheap SQL-shape metrics (join count, max nesting depth,
   subselect count, column counts) computed on the final AST and logged via
   `LogInfo`/`LoggingEventType` — this is how you prove the optimizer pays rent in
   production and how regressions get bisected to a rule.
5. **Graduation**: per dialect, flip a rule default-on once PCT + differential
   harness + golden-test migration are done; keep the disable key forever (escape
   hatch — the `disabled` list already supports this).

## 6. Sequencing (dependency-ordered)

| Phase | Items | Rationale |
|---|---|---|
| 1 | B framework + R1 + R2; A1 signatures (shared canonicalizer) | R2/A1 share one canonicalization utility; framework unlocks everything else; duplicate-join dedup is the most visible pain (F1) |
| 2 | A3 + C2 cardinality (`isToOneJoin`) ; R3, R4 | to-one awareness collapses the isolation problem for the FK→PK majority of joins; pruning+elimination compound |
| 3 | A2 path-local triggers; R5 unnesting; A4 exists-fusion | shrink isolation at the source, then clean up the remainder post-hoc |
| 4 | A5 capabilities + R8/R9 (Snowflake QUALIFY/EXISTS); C3 hint hook | dialect-specific wins, gated per dialect |
| 5 | R6, R7, R10; D oracle harness | broader coverage; measure remaining headroom |

The single highest-leverage investment is the **canonical signature utility**
(A1/R1/R2) — it de-brittles merging, filter dedup, exists-fusion, and every
signature-based rule, and it is testable in complete isolation.

---

## Appendix: key code touchpoints

| Concern | Location |
|---|---|
| Merge by join name | `merge()` pureToSQLQuery.pure:8459 (:8466 name match) |
| Thread merge + forced isolation | `mergeSQLQueryData` :8581 (`hasIsolations` :8586) |
| Isolation decision | `manageIsolation` :7390/:7429; `containsInnerJoin` :7459 |
| Strategy choice + escalation | `isolateSubJoins` :7545 (:7576 choice, :7592 escalation) |
| Strategies | `moveFiltersInOnClause` :1118, `moveFiltersOnTop` :1157, `buildCorrelatedSubQuery` :1189 |
| Self-join bookkeeping | `addSelfJoin` :4153, `addSelfJoinOnNode` :7522, `addedSelfJoin` (`gen_`) :7454 |
| Filter-encoded join names | `joinName` :7503, `collectJoinNames` :7490 |
| Iso-branch merging | `possiblyMergeIsolatedBranches` :8359, `possiblyMergeUnions` :8373 |
| Canonicalization primitive | `buildUniqueName` (used :8533, :8656, :7506) |
| Post-processor chain | `defaultPostProcessor.pure:58` (`sqlQueryDefaultPostProcessors`) |
| Filter pushdown | `pushFiltersDownToJoin.pure:27` (no Union descent :77-84) |
| Feature-gated rewrite template | `removeUnionOrJoinsPostProcessor.pure:31` + `GenerationFeaturesConfig` (`runtime/relationalRuntimeExtension.pure:15`) |
| Dialect hooks | `DbExtension` (`sqlQueryToString/dbExtension.pure`); Snowflake: `snowflakeExtension.pure:36` |
| Exists lowering | `buildExistsPredicate` / `shouldBuildExistsPredicate` :5377/:5392 |
| Merge-rule semantics tests | `pureToSQLQuery/tests/testMergeRules.pure` |
