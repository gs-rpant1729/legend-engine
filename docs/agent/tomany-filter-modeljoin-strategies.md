# Expressing toMany Intent Outside Projection: filter() and ModelJoin Strategies

Analysis of a core platform limitation: there is no clean way to express a filter on a
toMany property — while still asking for row explosion — outside of a `project()`
context, i.e. in a top-level `filter()` or in a ModelJoin condition. This doc records
the verified current behaviour and brainstorms candidate strategies.

## 1. Verified current behaviour

### 1.1 Explosion + merging semantics

`testMergeRules.pure`
(`legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/tests/testMergeRules.pure`)
is the Rosetta stone:

- **ToOne navigations merge freely** between `filter()` and `project()`
  (`testToOneJoinTreeNodesForFilterAndProjectMerge`, testMergeRules.pure:30). Safe because
  a toOne join used for filtering selects the same row the projection reads.
- **`exists()` inside `filter()` deliberately isolates**: it compiles to a
  `select distinct FK from child where pred` subselect joined back with a null check
  (`buildExistsAsJoinWithNullCheck` / `buildExistsPredicate`, pureToSQLQuery.pure:5377-5382),
  and it does **not** merge with the projection's explosion join
  (`testToManyJoinTreeNodesForFilterAndProjectDoNotMerge`, testMergeRules.pure:45-50).
  This is the "conserve row explosion" semantics: all employees of qualifying firms still
  explode.
- Flattening a toMany inside `filter()` (`$f.employees.lastName->toOne() == 'Smith'`) is a
  documented wrong-merge footgun, tagged `test.ToFix`
  (`testToManyJoinTreeNodesForInvalidUsageOfFilterAndProjectDoMergeGivingWrongResults`,
  testMergeRules.pure:55).

### 1.2 Pre-filtering the explosion already exists — but only per navigation site

- **Inline `->filter()` on a toMany navigation inside a project lambda works today**:
  `project(~[name2: x|$x.firm.employees->filter(e|$e.age < 35).firstName])`
  (relation/tests.pure:92, testModelJoinAdvanced.pure:1293,
  testFilterWithQualifiedProperties.pure:285). The predicate is carried as
  `savedFilteringOperation` on the JoinTreeNode and folded into the join. Crucially,
  `joinName()` incorporates the filter conditions (pureToSQLQuery.pure:1167, 1329), so two
  columns with the *same* inline filter merge into one join and *different* filters
  isolate correctly.
- Semantics are **outer**: parents with no surviving children remain with TDSNull
  (the ModelJoin test asserts `Google, TDSNull`).
- **Qualified properties** (`employeesInCity('NYC')`, `employeesByCityOrManager(...)`) are
  the model-side spelling of the same thing and work in both `filter()` and `project()`
  (testQueryStructure.pure, testFilterWithQualifiedProperties.pure, testForcedStructure.pure).
- Filter inside aggregation also works:
  `project(~[a: x|$x.employees->filter(e|$e.age > 30).age->average()])`
  (testRelationFunctionAggregation.pure:84).

### 1.3 ModelJoin mechanics

The condition is a strictly pairwise lambda:
`{employees: Person[1], firm: Firm[1] | <scalar boolean>}`.

`compileModelJoinForBranch` (relationalModelJoins.pure:62):
1. Builds a branch-specific source SWC rooted at a raw table (`buildBranchSourceSWC`).
2. Attaches the target via an `ON 1=1` placeholder join (`buildModelJoinPlaceholder`) so
   `$this` and `$that` share one query tree during condition compilation.
3. Compiles the condition, extracts the real ON clause, and materializes the target as a
   subselect (`materializeTargetAsSubselect`, relationalModelJoins.pure:293) when
   condition compilation created children (nested toOne navigations like
   `$firm.headquarters.city`) or the target mapping has filter/groupBy/distinct.

What works today in conditions: cross-side equality/arithmetic/functions, nested toOne
chains on either side, and **one-sided conjuncts** (`$employees.lastName->in(['Smith',
'Brown'])` in `InConditionMapping`, modelJoinSimpleSetup.pure:447).

What is **not** expressible:
- a toMany navigation inside the condition (would need exists semantics in the ON clause);
- a join through an unmapped bridge table (true many-many) — the only workaround is
  modelling a bridge class plus two ModelJoins and a composed navigation or qualified
  property.

## 2. The problem, distilled

Two distinct gaps hide under "toMany outside projection":

1. **Query side.** `filter()`'s type is `(T[*], T→Boolean) → T[*]` — it can only decide
   *which parents survive*, never *which children explode*. Explosion itself is implicit
   (a side effect of column multiplicity in `project()`), so there is no name for the
   thing you want to pre-filter. Today, "inner-semantics pre-filtered explosion" is:

   ```pure
   Firm.all()
     ->filter(f | $f.employees->exists(pred))          // predicate, statement 1
     ->project(~[empName: x|$x.employees->filter(pred).name, ...])  // predicate, statement 2
   ```

   The predicate is stated twice, and because exists-isolation intentionally defeats
   merging, it *executes* twice (distinct-subselect join + explosion join on the same
   child table). Post-filtering after `project()` avoids the duplication but is not
   guaranteed to push down, which is bad on large tables.

2. **Mapping side.** The ModelJoin pairwise lambda can't reference a collection or an
   unmapped relation, so many-many and "condition over a toMany property" force extra
   model surface (bridge class, extra property navigation, or qualified property).

## 3. Query-side strategies

Ordered by increasing language ambition.

### A. Bless and optimize the existing pattern (no language change)

Recognize the `exists(pred)` + inline `->filter(pred)` pair (same predicate, same
navigation) during generation and fuse them into a single **inner** join instead of
distinct-subselect + explosion join. Purely a `pureToSqlQuery` optimization: when the
exists' isolated subselect and a projection explosion share the association join *and*
the exists predicate equals the navigation's `savedFilteringOperation`, drop the exists
join and flip the explosion join to inner.

- **Pro:** zero new syntax; speeds up queries people already write; contained in the
  merge/isolation machinery.
- **Con:** predicate still stated twice at source level; structural predicate-equality
  detection (`buildUniqueName`-style) is brittle across trivially different spellings.

### B. Guaranteed post-filter pushdown (no language change)

Let users write the honest `->project(...)->filter(<tds row pred>)` and teach generation
to push a TDS-level predicate into the corresponding explosion join when it references
columns of exactly one explosion branch. Pushing WHERE into a LEFT JOIN changes null-row
behaviour, so the safe rewrite is:

- null-rejecting predicate → convert that branch to inner join with the predicate in ON;
- null-tolerant predicate (e.g. `isNull || ...`) → keep left join; only place predicate
  in ON on explicit opt-in.

- **Pro:** the natural relational spelling of the intent; fixes the "bad performance
  post-filter" complaint for existing services without touching models. Legend often
  emits nested subselects with DISTINCT that block DB optimizers, so doing this at
  generation time has real value beyond what the database would do.
- **Con:** heuristic; multi-branch predicates can't push; users can't see whether
  pushdown happened (needs a debug/plan annotation).

### C. A navigation-scoping function: `restrict` (moderate addition, very Pure-like)

Give the intent a first-class object semantics — "these Firm instances, with their
`employees` collection restricted":

```pure
Firm.all()
  ->restrict(~employees, e | $e.name->isNotEmpty())     // Firm[*] with employees narrowed
  ->filter(f | $f.employees->isNotEmpty())              // optional: inner semantics, no duplication
  ->project(~[empName: x|$x.employees.name, firmName: x|$x.legalName])
```

Semantically it's sugar for `map(f | ^$f(employees = $f.employees->filter(pred)))` — the
copy-constructor form gives it a precise in-memory/PCT meaning for free (the M2M side
recently learned to handle intermediate transformed objects, fd14945d98f). Relationally
it's cheap: the router tags the association's property mapping for the rest of the
pipeline, and every downstream join for that navigation gets the compiled predicate
appended to `savedFilteringOperation` — the exact mechanism inline project filters
already use, so merging/isolation comes for free. `exists`/`isEmpty` over the restricted
property compile against the narrowed set, which is what makes single-statement inner
semantics possible.

- **Pro:** states the predicate once; scopes the whole downstream pipeline (multiple
  project columns, groupBy, subsequent filters); composable; PCT-testable because the
  pure semantics are just copy+filter.
- **Con:** new routing state ("scoped navigations" threaded through `State`); needs rules
  for scope collisions (two restricts on the same property → compose with `and`),
  inheritance/union sets, milestoned qualifieds; must decide whether `restrict` survives
  `from()`/service boundaries.

#### C.1 Syntactic sugar alternatives for the copy-constructor semantics

The underlying semantic operation is `map(f | ^$f(employees = $f.employees->filter(pred)))`.
Candidate surface syntaxes, evaluated on Pure-nativeness vs user intuitiveness.

An important constraint: this function operates **purely in the model space**, so `~col`
(`ColSpec`) syntax is out of bounds — that belongs to the Relation API, which is a flat
representation of the data. The model-space idiom, per the core signatures in legend-pure,
is lambdas and Properties:

- `filter<T>(value:T[*], func:Function<{T[1]->Boolean[1]}>[1]):T[*]`
  (platform/pure/grammar/functions/collection/iteration/filter.pure)
- `map<T,V|m>(value:T[m], func:Function<{T[1]->V[1]}>[1]):V[m]` — and notably `map`
  accepts a `Property` directly as the function (`testMapWithPropertyAsVariable`,
  map.pure:66): **`Property` is a `Function`**, so signatures can demand a real
  navigation via typing.
- `copy<T>(object:T[1], id:String[1], keyExpressions:KeyExpression[*]):T[1]` backs
  `^$x(...)`, and the copy spec already supports **deep paths**:
  `^$pierre(firm.employees=[], address.name='Somewhere')` (copy.pure:61).
- `Path` literals (`#/Firm/employees#`, typed `Path<T,V|m>`) are the historical
  model-space property spec (legacy `project()` overloads took `Path[*]`).

Design axes:
- **Implicit vs explicit rebinding** — does `.employees` silently mean something new
  downstream (concise, but action-at-a-distance), or does the restricted collection get a
  new name (transparent, but every use site must change)?
- **Filter-only vs general transform** — the copy form is one instance of
  `^$f(employees = <any V[*]→V[*] chain>)`. Admitting the general form buys
  sort/top-N-per-parent (`->sortBy(...)->limit(3)`) — impossible to express
  pre-projection today, and a frequently wanted explosion shape.
- **Syntax it rhymes with** — navigation lambdas (`sortBy(p|$p.name)`), milestoning args
  on navigation (`$p.classification(%2015-10-16)`), copy constructor, graph-fetch trees.

**Option 1 — navigation lambda + predicate lambda** (the `sortBy(p|$p.name)` idiom):

```pure
function restrict<T,V>(set:T[*], nav:Function<{T[1]->V[*]}>[1],
                       pred:Function<{V[1]->Boolean[1]}>[1]):T[*]

Firm.all()->restrict(f|$f.employees, e|$e.name->isNotEmpty())
Firm.all()->restrict(f|$f.employees.addresses, a|$a.city == 'NYC')   // deep via auto-map chain
```

A plain function declaration — **zero new grammar**. The router requires `nav` to be a
pure property-navigation chain (SFEs applying Properties to the parameter), the same
discipline it already applies when analyzing project lambdas. Reads naturally; fully
lambda-based, i.e. maximally consistent with `filter`/`map`/`sortBy`.

**Option 2 — Property-typed parameter** (tightest typing, weakest ergonomics):

```pure
function restrict<T,V>(set:T[*], prop:Property<T,V|*>[1],
                       pred:Function<{V[1]->Boolean[1]}>[1]):T[*]
```

Type-system-enforced "must be a navigation, not an arbitrary lambda", backed by the
map-takes-Property precedent. But there is no property-literal syntax in user grammar
(`Firm->propertyByName('employees')` is grim), so this works best as the **internal
signature**, with Option 1's lambda form as the surface that compiles down to it.
(Path literals — `restrict(Firm.all(), #/Firm/employees#, pred)` — are the other genuinely
model-space spec, and `Path<T,V|m>` even carries qualified-property parameters; but they
repeat the class name, and are directionally legacy. Noted and passed on.)

**Option 3 — general transform, model space:**

```pure
function with<T,V>(set:T[*], nav:Function<{T[1]->V[*]}>[1],
                   transform:Function<{V[*]->V[*]}>[1]):T[*]

Firm.all()->with(f|$f.employees, es|$es->filter(e|$e.active))
Firm.all()->with(f|$f.employees, es|$es->sortBy(e|$e.salary)->limit(3))   // top-3 per firm
```

The `V[*]→V[*]` transform lambda subsumes restrict and unlocks per-parent
top-N/sort/distinct. Again a plain function — no grammar; SQL-generatability is handled
by constraining the transform body to a lowerable whitelist (filter, sortBy, limit,
distinct).

**Option 4 — anonymous qualifier at the navigation site:**

```pure
Firm.all()->filter(f | $f.employees(e | $e.active)->isNotEmpty())
          ->project(~[n: x | $x.employees(e | $e.active).name])
```

`$x.employees(pred)` ≡ `$x.employees->filter(pred)` — an ad-hoc qualified property.
Killer precedent: milestoned properties already take arguments in exactly this position
(`$p.classification(%2015-10-16)`). Lightest sugar, usable anywhere a navigation appears,
no pipeline operator needed. But per-site (doesn't solve state-it-once by itself),
requires grammar/compile support for lambda args on plain properties, and overloads
property-call syntax against real qualifieds/milestoning args.

**Option 5 — copy constructor spelled directly (no sugar, just router support):**

```pure
Firm.all()->map(f | ^$f(employees = $f.employees->filter(e | $e.active)))
```

Maximal Pure-nativeness, zero new grammar — every sugar above can be defined as this.
The deep-copy spec syntax (`^$f(firm.employees = ...)`, copy.pure:61) means even nested
restriction has existing grammar. But undiscoverable, and the router must pattern-match
a general `map`+`copy` expression rather than a dedicated function, which is fragile.

**Option 6 — graph-fetch-tree scoping (for completeness):**

```pure
Firm.all()->scope(#{Firm { employees->filter(e | $e.active) { addresses->filter(...) } }}#)
```

Natural for deep multi-level scoping and rhymes with graphFetch's per-node worldview, but
GFTs don't currently carry filters, and grafting GFT syntax into the TDS pipeline is a
big conceptual splice. Only worth it if deep nesting is a real requirement.

**Recommended layering:** Option 1 (navigation-lambda `restrict`) as the pipeline-scoping
operator — a plain model-space function, no grammar change, over Option 2's
Property-typed core, defined semantically as Option 5 so PCT semantics are just
copy+filter. Option 4 (anonymous qualifier) as the site-local sugar if grammar appetite
exists — strongest precedent via milestoning args. Option 3 (`with` + transform lambda)
as the designed-for growth path, because top-N-per-parent is the next request after
filtered explosion and falls out of the same copy-constructor semantics.

Naming note: `restrict` collides with nothing in the model-space function namespace
today, whereas `scope`, `narrow`, and `refine` are overloaded elsewhere in Legend; `with`
suits the general form because it echoes the copy-constructor's "with these properties
changed" reading.

### D. An explicit explosion operator: `explode` / lateral (biggest, most honest fix)

Today explosion is an *implicit* consequence of projecting a toMany path. Name it and
every problem in this space becomes ordinary:

```pure
Firm.all()
  ->explode(f | $f.employees->filter(e|$e.name->isNotEmpty()),      // which children, pre-filtered
            {f, e | ~[firmName: $f.legalName, empName: $e.name]})   // row type over (parent, child)
```

with `explode` = inner and `explodeOuter` = left-outer variants. This is exactly a
lateral join — and Legend SQL just gained lateral support (6cc7dca82d6), and the Relation
API already thinks in flatten/ungroup terms for variants. The pair lambda `{f, e | ...}`
also gives users a place to correlate parent and child in one expression, which the
`~[col: x|...]` syntax cannot.

- **Pro:** removes the semantic hole rather than patching it; nests
  (`->explode(...)->explode(...)`) for multi-level explosion with per-level filters; the
  many-many query shape (explode through a bridge) becomes writable in the query even
  when the mapping can't express it.
- **Con:** a real language/protocol addition (grammar, compile, router, TDS/Relation
  duality); needs a coexistence story with implicit explosion (probably: implicit stays,
  `explode` is the escape hatch).

### E. Qualified properties, made cheaper (model-side, works today)

`employeesInCity(city)` already gives named, parameterized, pre-filtered navigations
usable in both `filter()` and `project()` — the gap is ergonomic (a model change per
predicate shape). Keep this as the recommended answer for *stable, business-named*
restrictions ("activeEmployees"), with C/D covering ad-hoc ones. Studio/codegen
affordances may be a better investment than engine work here.

## 4. ModelJoin-side strategies

### F. Allow `exists()` in the condition lambda (contained, high value)

Keep the pairwise contract — `exists` returns a scalar Boolean per pair, so the lambda's
semantics stay clean:

```pure
Person_Firm: ModelJoin
{
  {employees: Person[1], firm: Firm[1] |
    $firm.departments->exists(d | $d.code == $employees.deptCode)}
}
```

Two viable SQL lowerings:
1. `EXISTS (correlated subquery)` directly in the ON clause — legal SQL, dialect-safe
   almost everywhere;
2. semi-join: pre-aggregate the toMany side into a `select distinct` subselect and join
   through it — `materializeTargetAsSubselect` (relationalModelJoins.pure:293) is already
   ~80% of that machinery; condition compilation would classify exists-conjuncts and
   route them into the materialized target's WHERE/ON instead of the flat ON clause.

The exists-vs-join tradeoff mirrors what `shouldBuildExistsPredicate`
(pureToSQLQuery.pure:5392) already arbitrates for query-level exists.

### G. Bridge relation in the mapping: `~via` (kills the bridge-class workaround)

Let the ModelJoin declare an unmapped Relation as join intermediary, adding it as a third
lambda parameter:

```pure
Person_Firm: ModelJoin
{
  ~via meta::...::function::firmPersonBridgeTable():Relation<Any>[1] as $b
  {employees: Person[1], firm: Firm[1] |
    $employees.id == $b.PERSONID && $b.FIRMID == $firm.id}
}
```

No bridge *class*, no extra navigation — the bridge stays at the Relation level, the same
abstraction `~func` Relation class mappings already use, so `buildBranchSourceSWC`'s RFPM
branch is reusable nearly verbatim for the via-relation. Compilation generalizes
`compileModelJoinForBranch` from one placeholder to two: source → via placeholder →
target placeholder, compile the condition against all three SWCs, then classify conjuncts
by which aliases they touch to split the ON clauses of the two real joins (source–bridge
and bridge–target). Multi-hop is a natural extension (`~via a, ~via b`).

Relational-store precedent: `@Join1 > @Join2` chains do exactly this, just
table-anchored — `~via` is the ModelJoin-level equivalent.

Design decisions to pin:
- Is `$b` row-typed (column access by name, checked against the Relation's inferred
  type — the RelationFunction class mapping compiler support helps here)?
- Does the bridge participate in milestoning?
- Union sources multiply branches × via — per-branch compilation extends but branch
  count grows.

### H. One-sided conjuncts as "filtered associations" (works today — document it)

Since the condition lambda already accepts one-sided predicates (`InConditionMapping`), a
*permanently* restricted navigation is expressible now: declare a second association
(`activeEmployees`) whose ModelJoin condition is `join-cond && $employees.active == true`.
This is the mapping-side twin of qualified properties and costs nothing. It doesn't solve
ad-hoc query-time restriction, but it's the right answer for restrictions that are part
of the model's meaning.

## 5. Cross-cutting semantics to pin down (for any new construct)

- **Inner vs outer explosion** — drop parents without surviving children, or keep with
  nulls? Today: inline project filter = outer; exists + inline = inner-with-duplication.
  Any new construct needs an explicit choice (or both variants).
- **Join-tree merging** — the construct must feed `joinName`/`savedFilteringOperation` so
  identical scoped navigations merge and different scopes isolate (machinery exists).
- **Multiple toMany columns under one scope** — scope predicate applies to each branch;
  two branches on the same association with different scopes → distinct joins (already
  handled by filter-aware join naming).
- **Aggregation over scoped navigations** — must compose with groupBy/aggregation
  (inline filter inside aggregation already works, testRelationFunctionAggregation.pure:84).

## 6. Recommended sequencing

1. **Now, no syntax:** H (document the filtered-ModelJoin idiom) + A or B (fuse
   exists+inline-filter, or push post-filters down). These attack the stated perf pain
   for queries people can already write.
2. **ModelJoin next:** F (exists in condition), then G (`~via`) — both contained in
   `relationalModelJoins.pure` + grammar/protocol; both remove the bridge-class tax; G is
   the only clean answer to true many-many.
3. **Language, when appetite exists:** C (`restrict`) to stay in the implicit-explosion
   world with an object-idiomatic scoping primitive (reuses
   `savedFilteringOperation`/joinName merging wholesale); D (`explode`/lateral) to name
   explosion itself.

The deepest fork in the road is **C vs D**: C keeps Pure's "navigations explode
implicitly, projection decides" worldview and adds a scoping knob; D admits explosion is
a join and gives it join-like controls. Given the variant/flatten work is already pulling
the Relation API toward explicit lateral semantics, D is the more future-proof bet — but
C is the one that can land incrementally without changing how anyone writes projections
today.

## 7. Parameterized Relation Function class mappings (query → mapping arguments)

A related expressivity gap: Relation Function class mappings source a class from a
function, but that function must be 0-arg. The metamodel bakes the constraint into the
function *type*:

```pure
// legend-pure: platform_dsl_mapping/grammar/mapping.pure:235
Class meta::pure::mapping::relation::RelationFunctionInstanceSetImplementation extends InstanceSetImplementation
{
  relationFunction: FunctionDefinition<{->Relation<Any>[1]}>[1];
  primaryKey: meta::pure::metamodel::relation::Column<Nil,Any|*>[*];
}
```

Allowing arguments to flow from the query layer into the mapping layer would unlock:
snapshot/as-of selection (`personTable(asOf)`), shard/partition/region selection,
source-system switches, and native table-valued functions (Snowflake TVFs, BigQuery
table decorators) — things `restrict()`/filters can never express because they change
the **FROM clause itself**, not the rows selected from it.

### 7.1 The existing query→mapping channels (precedents in the codebase)

- **Milestoning — the canonical precedent.** `Product.all(%2015-10-16)` is
  `getAll<T>(type:Class<T>[1], milestoningDate:Date[1])` (legend-pure
  platform/pure/grammar/milestoning.pure:56-58). The date is captured into a
  `TemporalMilestoningContext` (milestoning.pure:508 wraps it as
  `DateWrapper(date=^Literal(...))`) and threaded through `State`/`SelectWithCursor`
  into every mapping-compilation call (`processRelationalMappingSpecification`,
  `processRelationFunctionClassMapping` both take `milestoningContext`). Crucially,
  milestoning keeps model purity: the *class* declares the need (temporal stereotype),
  the *query* supplies the value via typed `all()` overloads, the *mapping* consumes it
  implicitly. Any generalization should preserve that division.
- **Plan parameters.** Query-lambda variables already lower to `VarPlaceHolder` /
  `PlanVarPlaceHolder` in the generated SQL (pureToSQLQuery.pure:599-604, 1454) and
  surface as execution-plan parameters. This is the runtime-binding half of the feature,
  already built: a mapping-function argument bound to a query variable can compile to a
  placeholder, keeping plans cacheable with per-execution values.
- **Naming precedent.** `OperationSetImplementation.parameters` already uses the term in
  mapping space; `ExecutionContext` already rides along `from()`-adjacent APIs.

### 7.2 Where should the argument bind? (the central design decision)

Three channels, in increasing dynamism:

**(a) Bound in the mapping — partial application.**

```pure
Person: Relation
{
  ~func personTable('EMEA')   // constant args, mapping-time
}
```

No query-layer involvement; just lets one parameterized function serve many
mappings/sets. Cheap, useful, but adds no query expressivity — table stakes, not the
feature.

**(b) Bound at the query root — generalized `all()` args.**

```pure
Person.all('EMEA')->filter(...)
```

This is the milestoning shape generalized. The problem: milestoning gets away with it
because temporal dates are *model* concepts (stereotypes on the class). A region/snapshot
parameter is a *mapping/store* concern — the query is written against the model, and the
mapping is not even chosen until `from()`. Typing `all()` against parameters that only
some mappings need breaks model/mapping separation (and would require declaring the
parameters on the class to typecheck, polluting the model). Only appropriate when the
parameter genuinely is a model concept — in which case the right move is a new
*stereotype-like* class-level declaration, i.e. "do what milestoning did", which is a
much bigger lift.

**(c) Bound where the mapping enters the query — `from()` / mapping parameters.**
The mapping declares parameters; the point where the query commits to a mapping is the
point where its parameters are satisfied:

```pure
Mapping my::SalesMapping(region: String[1], asOf: Date[1] = %latest)
(
  Person: Relation
  {
    ~func my::personTable($region, $asOf)   // args reference mapping parameters
    firstName: FIRSTNAME, ...
  }
)

// query layer:
Person.all()->filter(...)->from(my::SalesMapping, $runtime, region='EMEA', asOf=%2026-01-01)
```

This respects the separation milestoning established: the query core stays
mapping-agnostic; parameters live and typecheck on the mapping; the supply site is
exactly where the mapping becomes known. **Recommended channel.**

### 7.3 Metamodel design

Two adherent representations, both reusing standard M3 constructs:

**M1 — function pointer + argument list (FunctionExpression-shaped):**

```pure
Class meta::pure::mapping::Mapping ... 
{
  parameters: VariableExpression[*];   // name + genericType + multiplicity, same as function params
  ...
}

Class RelationFunctionInstanceSetImplementation extends InstanceSetImplementation
{
  relationFunction: FunctionDefinition<Any>[1];        // relaxed; constraint: returns Relation<Any>[1]
  relationFunctionArguments: ValueSpecification[*];    // InstanceValue literals or VariableExpressions
                                                       // resolving to Mapping.parameters
  primaryKey: Column<Nil,Any|*>[*];
}
```

This mirrors exactly how `FunctionExpression` pairs `func` with `parametersValues` —
arity/type checking is ordinary function-application checking. `Mapping.parameters` as
`VariableExpression[*]` is the same representation `FunctionDefinition` uses for its
parameters, so defaults, multiplicity and type checks are all standard machinery.

**M2 — expression form (lambda body as the source):**

```pure
relationExpression: LambdaFunction<Any>[1];   // params = mapping params; body returns Relation<Any>[1]
// grammar: ~src {region, asOf | my::personTable($region, $asOf)->filter(r|$r.REGION == $region)}
```

Maximum flexibility (inline post-processing of the source), and precedented — ModelJoin's
`joinCondition` and AggregationAware's `groupByFn`/`mapFn`/`aggregateFn` are all
`LambdaFunction`s in the mapping metamodel. But it dissolves the "source is a named,
reusable, independently-testable function" property that makes `~func` attractive, and
complicates routing (`potentiallyRouteRelationFunctionSet` currently routes a function
reference). **M1 preferred**; M2's flexibility is recoverable by defining a new named
function that wraps the expression.

Grammar note for M1: `~func my::personTable($region, $asOf)` — argument expressions are
either literals or `$param` references; name resolution against the owning Mapping's
parameter list; a 0-arg call stays exactly as today, so the extension is fully
backward-compatible.

### 7.4 Query-side supply and execution plumbing

- **`from()` overload**: `from<T|m>(t:T[m], mapping:Mapping[1], runtime:Runtime[1], params:Pair<String,Any>[*])`
  (or keyword-arg sugar). Compile-time check: every mapping parameter without a default
  is supplied; types/multiplicities match. Mapping includes: an including mapping may
  re-expose or bind the included mapping's parameters — same rules as function
  composition.
- **Unbound parameters become plan parameters.** If a mapping parameter is supplied from
  a query-lambda variable (service parameter), it lowers to a `VarPlaceHolder` — the
  existing machinery (pureToSQLQuery.pure:599) — so one cached plan serves all values.
  Constants fold as `Literal`s.
- **Engine change is localized.** `processRelationFunctionClassMapping`
  (pureToSQLQuery.pure:4727) already processes the function body with a `vars` map; the
  extension is: before processing, bind the function's parameter `VariableExpression`s to
  the resolved argument values (literal or placeholder) in `$vars`. All the RFPM call
  sites in ModelJoin compilation (`buildBranchSourceSWC`, `buildModelJoinPlaceholder`)
  inherit the behaviour by passing the same bindings.
- **Consistency for free.** One mapping-level parameter shared by several RFPM sets
  (Person *and* Firm read the same `$asOf` snapshot) is guaranteed consistent across the
  whole query — something per-`all()` arguments could never guarantee. This also
  sidesteps the hardest part of milestoning (context *propagation* through navigation
  chains): a mapping parameter is constant for the whole execution, so there is nothing
  to propagate.

### 7.5 Correlations with the strategies above

- **Qualified properties / anonymous qualifier (C.1 Option 4)** parameterize a
  *navigation*; this proposal parameterizes a *set source*. Same axis — user-supplied
  values crossing into mapping-owned constructs — at different granularities. A
  parameterized `~func` is to a class mapping what a qualified property is to an
  association.
- **`restrict()` (C) is complementary, not overlapping**: restrict pushes predicates into
  joins/WHERE against a fixed source; mapping parameters change *which relation is read*
  (FROM clause). A TVF source (`sales(region)`) is only reachable with parameters.
- **ModelJoin `~via` (G)**: once RFPM sources can take arguments, a parameterized via-
  relation (`firmPersonBridge($asOf)`) comes for free if `~via` reuses the same
  metamodel shape — argue for designing G's via-clause as a
  `FunctionDefinition + arguments` pair from the start.
- **Milestoning** is both the inspiration and the boundary: model-intrinsic parameters
  (dates on temporal classes) stay on `all()`; mapping-intrinsic parameters (snapshots,
  shards, source systems) bind at `from()`. The two compose: a milestoned class mapped
  via a parameterized relation function receives its dates through
  `milestoningContext` and its source args through mapping parameters, on the same
  `processRelationFunctionClassMapping` call.

### 7.6 Class-mapping-level parameters and property access

Parameters can equally be declared per *class mapping* (set implementation) rather than
per Mapping — metamodel-wise it is the same move one level down:

```pure
Class RelationFunctionInstanceSetImplementation extends InstanceSetImplementation
{
  parameters: VariableExpression[*];                 // set-level, instead of Mapping.parameters
  relationFunction: FunctionDefinition<Any>[1];
  relationFunctionArguments: ValueSpecification[*];  // may reference $parameters
  ...
}
```

But set-level declaration changes the semantics of *reaching* the set: a parameterized
set is no longer a fixed relation but a family of relations indexed by its parameters, so
**every access path must produce argument values**. There are exactly three access paths,
and milestoning — which is precisely a class-level parameterization — had to solve all
three (generated `all(%date)` overloads, generated qualified properties
`classification(%date)`, implicit date propagation):

**(1) Root access — `Person.all()`.** The set's parameters must resolve from the
execution-level bindings (from `from()`, namespaced by set id when ambiguous:
`from($m, $rt, personSet.region='EMEA')`) or from declared defaults; otherwise a compile
error ("unbound set parameter"). Semantically identical to mapping-level parameters —
set-level declaration here is just scoping/organization.

**(2) Property access — the interesting case.** If `Firm.employees` targets a
parameterized Person set, who supplies `$region`? Three candidate answers:

- **(2a) Execution-scoped (same as root):** the navigation reuses the bindings supplied
  at `from()`. Property access in the query is completely unchanged —
  `$f.employees.name` — and the planner resolves the target set's arguments from
  execution scope. This is the behaviour users get "for free" and should be the default.
  With this rule alone, set-level parameters are mapping-level parameters with narrower
  scoping — nothing new semantically.

- **(2b) Correlated supply in the association mapping — the genuinely new capability.**
  Let the property/ModelJoin mapping bind the target set's parameters from *source-side
  values*:

  ```pure
  Person[p](region: String[1]): Relation
  {
    ~func personTable($region)   // e.g. a TVF or region-sharded accessor
    ...
  }

  *Firm_Person: ModelJoin
  {
    ~target employees(region = $firm.regionCode)     // correlated argument
    {employees: Person[1], firm: Firm[1] | $employees.firmId == $firm.id}
  }
  ```

  The query stays `Firm.all()->project(~[e: x|$x.employees.name])` — **property access
  looks exactly like today**; the correlation lives entirely in the mapping. SQL-wise
  this is a **correlated lateral join** (`CROSS/OUTER JOIN LATERAL personTable(firm.REGION)`)
  when the argument reaches a TVF, or an ordinary join condition when the argument only
  feeds filters inside the relation expression. This is expressive power nothing else in
  the doc provides: per-source-row arguments to the target's source relation.
  Precedent for the metamodel shape: ModelJoin's `joinCondition` lambda already
  references source-instance properties; `~target` arguments are the same kind of
  expression (`ValueSpecification[*]` over the source lambda parameter).
  Implementation dependency: `pureToSQLQuery`'s relational metamodel needs a lateral
  join representation (Legend SQL grew lateral support in 6cc7dca82d6; the
  SelectSQLQuery/Join side would need the equivalent).

- **(2c) Query-site supply on the navigation — `$f.employees(region='EMEA')`.**
  This is milestoning's generated-qualified-property pattern generalized. **Reject it**
  for mapping-owned parameters: it leaks mapping concerns into model navigation (the
  same purity violation as `all(args)`, §7.2b). Milestoning earns this syntax only
  because dates are model concepts. If a user wants query-site values on a navigation,
  that is what model-level qualified properties (and C.1 Option 4) are for — the
  qualified property's parameter can then flow into the set argument via (2b).

**(3) Operation/union membership.** Each branch of an operation set brings its own
parameters; bindings resolve per-branch by set id. No new semantics, but the per-branch
compilation (union machinery) must thread per-set bindings rather than one global map.

**Propagation — resist it.** Milestoning's implicit date propagation (target set
inherits the source's business date when omitted) is its most complex, least-understood
behaviour. The equivalent here — "if the target set has a parameter with the same
name/type as the source set's, inherit it silently" — should be avoided: prefer (2a)
execution-scoped resolution plus (2b) *explicit* correlation. Explicit beats implicit
here because unlike milestoning dates, arbitrary parameters have no universal semantics
to justify a global propagation rule.

**When to choose which level:** declare at *mapping level* when the value must be
consistent across sets (one `$asOf` for the whole query — §7.4's consistency argument);
declare at *set level* only when the parameter is meaningfully per-set — and note that
set-level parameters earn their keep almost entirely through (2b) correlated binding,
which mapping-level parameters cannot express (they are constant per execution, while
(2b) varies per source row).

### 7.7 Generalizing to a model-level concept (milestoning-style)

Can this be lifted to the model layer entirely — a general "class dimension parameter"
of which milestoning is the special case? Yes, and the `Class` metamodel is more
hospitable than expected.

**What the Class metamodel already offers:**

- `Class` **already has two kinds of parameters**: `typeParameters: TypeParameter[*]`
  and `multiplicityParameters` (legend-pure m3.pure:292, 320 — `Class GenericPair<T,U>`,
  `Class MultiplicityHolder<|m>`). Class-level parameterization is native to Pure's
  metamodel philosophy; what's missing is the third kind, **value parameters**. Adding
  `Class.contextParameters: VariableExpression[*]` would be symmetric and clean — but it
  is a core M3 change with platform-wide blast radius (every tool that walks classes).
- `Class.originalMilestonedProperties` (m3.pure:233) is precedent that features of this
  class do earn dedicated M3 bookkeeping fields when generation rewrites properties.
- **Milestoning's machinery is stereotype + compiler post-processing**, not metamodel
  surgery: `MilestoningStereotypeEnum`, `MilestoningDatesPropagationFunctions`,
  `MilestoningDates` (legend-pure m3 compiler). The stereotype drives generation of
  `all(date)` validation, the synthetic `businessDate` property, milestoned qualified
  properties on navigations, and date-propagation rules.

**The best reuse: stereotyped Properties.** Milestoning surfaces its parameter *as a
property* — `businessDate` is a generated synthetic property. The generalization inverts
this: the user declares the parameter as an ordinary property carrying a profile
stereotype:

```pure
Class <<ctx.parameterized>> my::Position
{
  <<ctx.parameter>> scenario : String[1];   // not stored data; a query-supplied dimension
  notional : Float[1];
  ...
}
```

This reuses `Class.properties` — **zero M3 change**, just a new Profile (exactly like
`temporal`). Because the parameter *is* a property, everything composes for free:

- `Position.all('STRESS_2026')` — validation generalizes the milestoned-`getAll`
  validator: arity and types come from the stereotyped properties. (Typing is the weak
  spot: the milestoning `getAll` natives are exactly `Date`-typed
  (milestoning.pure:56-58); a general version needs per-arity overloads or a
  compiler-validated `getAll<T>(type, ctx:Any[*])`.)
- `$pos.scenario` is readable in queries, constraints, qualified properties, and
  ModelJoin conditions — it's just a property.
- **Propagation** generalizes `MilestoningDatesPropagationFunctions`: navigating an
  association to another `<<ctx.parameterized>>` class carrying the same dimension
  propagates the value, exactly as business dates flow today. (Contrast §7.6's advice to
  *resist* propagation for mapping-owned parameters: propagation is justified here
  precisely because the dimension is a model concept with declared, shared semantics.)
- **Mapping consumption** connects back to §7.3–7.6: a class-level context property is
  the model-side *supplier* for a parameterized `~func` —
  `~func positionTable($this.scenario)` — closing the loop query → model dimension →
  set-source argument. Engine-side, `TemporalMilestoningContext` threading generalizes
  to a context-bindings map on `State`/`SelectWithCursor`, following the exact seams
  milestoning already cut through `pureToSQLQuery`.

**When model-level is right:** the dimension is a genuine domain concept — business
date (already done), risk scenario, tenant, model version/branch. The test: would two
different mappings of this class *both* need the parameter, and would a user reading
only the model expect it? If the answer is "it depends on the store" (shard, snapshot
table, TVF arg), stay at mapping/set level (§7.3–7.6).

**Honest cost assessment:** milestoning is among the most complex machinery in the
platform — compiler post-processors, propagation edge cases, validator complexity,
engine threading — and it handles *one* parameter type with universal semantics.
Generalizing means rebuilding that machinery generically (once, but generically). The
stereotyped-property route contains the blast radius: no M3 change, validation and
propagation implemented once against the profile, and the engine work is a
generalization of context threading the `pureToSQLQuery` seams already have. A staged
path: land mapping-level parameters (§7.3) first — they need no model changes and cover
store concerns — then lift genuinely domain-shaped parameters to the profile once the
threading exists.

**Resulting three-level architecture** (they compose, not compete):

| Level   | Declared on                  | Supplied at                                 | Varies per              | Use for                                                     |
| ------- | ---------------------------- | ------------------------------------------- | ----------------------- | ----------------------------------------------------------- |
| Model   | `<<ctx.parameter>>` property | `all()` / propagation                       | query (or navigation)   | domain dimensions: scenario, tenant, as-of                  |
| Mapping | `Mapping.parameters`         | `from()`                                    | execution               | store concerns shared across sets: snapshot, source system  |
| Set     | set-impl `parameters`        | `from()` (2a) or correlated `~target` (2b)  | execution or source row | per-set store concerns; lateral TVF args                    |

### 7.8 Semantics of a parameterized class mapping, and a worked use case

**What it means.** A class mapping is an *extent definition*: given a store state, it
denotes the set of instances of the class that the mapping can produce (`Person.all()`
under mapping *m* = ⟦Person⟧ₘ). A parameterized class mapping denotes an **indexed
family of extents** ⟦Person⟧ₘ(p̄) — one instance-set per parameter point. Nothing about
the class changes; what changes is that "the set of all Persons" is no longer a single
thing. Every semantic consequence follows from one question: *at which point of the
family is each part of the query evaluated?*

1. **`Person.all()` alone is no longer denotable.** It must be evaluated *at a point*:
   a constant point for the whole execution (bound at `from()`, or a declared default),
   or — for navigations — a point chosen per source instance (2b correlation). This is
   exactly milestoning's semantics: `Product.all(%2015-10-16)` evaluates the temporal
   family at a date; an unbound date is a compile error.
2. **The parameter is an extent selector, not data.** It need not correspond to any
   stored column (a TVF argument doesn't). In the model-level variant (§7.7) it
   *additionally* surfaces as a readable property whose value is, by construction, the
   point at which the instance's extent was selected — again exactly like the generated
   `businessDate` property.
3. **Instance identity must incorporate the point whenever two points coexist in one
   query.** Under a single global binding, identity is unchanged. Under correlated
   binding, `Position(id=7)` from scenario A and `Position(id=7)` from scenario B are
   *different instances* — the RFPM `primaryKey` must be extended with the parameter
   value, or object dedup, PK-based groupBy, and result caching silently merge slices.
   (Milestoning's analogue: milestoning date columns participate in PK processing.)
4. **Associations into a parameterized set are only well-defined relative to a point
   assignment.** The association mapping's meaning now *includes* the slice choice:
   execution-scoped (§7.6 2a, "everyone at the same point") or correlated
   (§7.6 2b, "each source row picks its point"). Without one of these, `$f.positions`
   has no denotation.
5. **Multi-slice queries expose the level difference.** `from()`-bound parameters give
   one point per execution, so comparing two slices in one query (base vs stress PnL
   side by side) is inexpressible at mapping level — it needs either the model-level
   form, where `all(p)` can be called twice at different points (milestoning allows
   `Product.all(%d1)` and `Product.all(%d2)` in one query today), or two associations
   with different constant `~target` bindings.

**Worked use case: scenario-based position valuation.** A risk store holds positions
revalued under named stress scenarios — either a Snowflake TVF
`scenarioPositions(scenario)` that computes revaluations on the fly, or a wide
pre-computed table keyed by `SCENARIO`. Portfolios are plain reference data.

```pure
// --- Model (scenario-agnostic; Position knows nothing about scenarios) ---
Class risk::Portfolio { name: String[1]; }
Class risk::Position  { positionId: Integer[1]; pnl: Float[1]; }
Association risk::Portfolio_Position
{
  portfolio: risk::Portfolio[1];
  positions: risk::Position[*];
}

// --- Relation function: the extent family ---
function risk::store::scenarioPositions(scenario: String[1]): Relation<Any>[1]
{
  // TVF form:   #>{risk::store::RiskDb.scenarioPositions}#($scenario)
  // Table form (works with no TVF support at all):
  #>{risk::store::RiskDb.POSITIONS}#->filter(r | $r.SCENARIO == $scenario)
       ->select(~[POSITION_ID, PORTFOLIO_ID, PNL])
}

// --- Mapping: Position's extent is indexed by $scenario ---
Mapping risk::RiskMapping(scenario: String[1])          // or set-level, §7.6
(
  risk::Portfolio[port]: Relation
  {
    ~func risk::store::portfolioTable()
    name: NAME, +id: Integer[1]: ID, +stressScenario: String[1]: STRESS_SCENARIO
  }
  risk::Position[pos]: Relation
  {
    ~func risk::store::scenarioPositions($scenario)
    pnl: PNL, +positionId: Integer[1]: POSITION_ID, +portfolioId: Integer[1]: PORTFOLIO_ID
  }
  *risk::Portfolio_Position: ModelJoin
  {
    {positions: risk::Position[1], portfolio: risk::Portfolio[1] |
      $positions.portfolioId == $portfolio.id}
  }
)
```

**Query A — global binding (one point for the whole execution):**

```pure
risk::Portfolio.all()
  ->project(~[name: p|$p.name, stressPnl: p|$p.positions.pnl->sum()])
  ->from(risk::RiskMapping, $runtime, scenario='RATES_UP_100BP')
```

Semantics: the entire query is evaluated in the `RATES_UP_100BP` slice. SQL: the
Position side compiles to `(select ... from POSITIONS where SCENARIO = ?)` (or
`table(scenarioPositions(?))`), the `?` being a `VarPlaceHolder` — one cached plan
serves every scenario. Identity is unaffected (one point).

**Query B — correlated binding (each portfolio picks its point):** each portfolio has
an *assigned* stress scenario (`STRESS_SCENARIO` column). The association supplies the
parameter per source row (§7.6 2b):

```pure
  *risk::Portfolio_Position: ModelJoin
  {
    ~target positions(scenario = $portfolio.stressScenario)
    {positions: risk::Position[1], portfolio: risk::Portfolio[1] |
      $positions.portfolioId == $portfolio.id}
  }
```

The query text is *unchanged* (`$p.positions.pnl->sum()`), but its meaning is now "each
portfolio's positions, valued under that portfolio's own scenario" — a navigation across
slices, compiling to `LEFT JOIN LATERAL scenarioPositions(port.STRESS_SCENARIO)`.
This is the fibered evaluation of point 4, and the case where point 3 bites: two
portfolios with different scenarios can both reach `positionId=7`, and those are
distinct Position instances — the set's effective PK is `(POSITION_ID, scenario)`.

**Query C — the boundary (slice comparison):**

```pure
// Base vs stress PnL side by side — NOT expressible with one from()-bound parameter:
~[base: p|$p.positionsAt('BASE').pnl->sum(), stress: p|$p.positionsAt('RATES_UP_100BP').pnl->sum()]
```

needs either the model-level form (§7.7: scenario as `<<ctx.parameter>>`, `all()`
callable at two points, navigations carrying the point like milestoned qualifieds) or
two associations with different constant `~target` bindings. This is the cleanest
diagnostic for choosing a level: *if users need two points in one query, the parameter
is behaving like a domain dimension — lift it to the model.*

### 7.9 Open questions

- **Multiplicity/type surface of parameters** — restrict to primitives + dates
  initially (what SQL placeholders can carry); collections need `in`-style expansion
  rules like existing plan params.
- **Parameter visibility inside other mapping constructs** — should ModelJoin
  conditions and RFPM `+column` transforms also see `$region`? Natural yes (they are
  owned by the same Mapping), but each construct's compiler must thread the bindings.
- **Studio/service ergonomics** — service endpoints should surface unbound mapping
  parameters as service parameters automatically (plan-parameter lowering makes this
  mechanical).
- **Include/override semantics** — parameter shadowing across `include` chains needs a
  rule (suggest: explicit rebinding only, no implicit capture).
