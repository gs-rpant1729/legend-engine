# DuckDB: Redundant Subselect Elimination and Unused Column Pruning

**Verified against DuckDB `main` at commit `613c530` — 2026-11-12.** Source reading only; plans
were not confirmed with `EXPLAIN`. File links point at `main` and may drift.

Pure-to-SQL generation emits deeply nested subselects. This note records how DuckDB's optimizer
treats them, and which shapes still cost something.

---

## TL;DR

- **Yes to both.** Subselect elimination is not a dedicated pass — it falls out of how DuckDB
  plans `FROM` subqueries. Column pruning is a real pass (`UNUSED_COLUMNS`) with a long list of
  deliberate exemptions.
- A pass-through subselect costs nothing at runtime. A subselect that computes expressions keeps
  only the expressions that are referenced, and pruning reaches down into the scan.
- Shapes that still carry cost: `UNION` (not `UNION ALL`), `INTERSECT`/`EXCEPT`, unreferenced
  `GROUP BY` keys and `DISTINCT`, `MATERIALIZED` CTEs, and anything feeding DML or `COPY TO`.

---

## 1. Redundant subselect elimination

### A `FROM` subquery adds no operator

[`bind_subqueryref.cpp`](https://github.com/duckdb/duckdb/blob/main/src/planner/binder/tableref/bind_subqueryref.cpp)
binds the inner query and returns its plan unchanged. The outer query binds directly to the
output columns of the subquery's root operator, normally a `LogicalProjection`. There is no
query-block boundary in the logical plan, so

```sql
SELECT a FROM (SELECT a, b FROM (SELECT * FROM t))
```

is simply a chain of projections over one table scan.

### How the stacked projections disappear

| Step | Where | Effect |
|---|---|---|
| Trim | `RemoveUnusedColumns`, projection case | Drops every expression no parent references and renumbers the survivors |
| Pull up | `PROJECTION_PULLUP` ([`projection_pullup.cpp`](https://github.com/duckdb/duckdb/blob/main/src/optimizer/projection_pullup.cpp)) | Moves projections above joins; re-runs because binding rewrites can make parent projections redundant |
| Place | `PROJECTION_PLACEMENT` | Cost-based repositioning and merging of projections |
| Omit | [`plan_projection.cpp`](https://github.com/duckdb/duckdb/blob/main/src/execution/physical_plan/plan_projection.cpp) | A projection that passes its child's columns through in order with identical types (`#0, #1, #2…`) produces no physical operator |

### Related passes

- **`REDUNDANT_ORDER`** ([`remove_redundant_order.cpp`](https://github.com/duckdb/duckdb/blob/main/src/optimizer/remove_redundant_order.cpp))
  drops an `ORDER BY` inside a subquery when nothing above reads the order.
  - Order is treated as unread below joins, hash aggregates and hash-based set operations.
  - Order is kept for `LIMIT`, window functions, sampling, CTEs, DML sinks, `DISTINCT ON`,
    `UNION ALL` (concatenates its inputs in order) and an ungrouped aggregate whose function
    reads rows in order. The final query result order is always kept.
- **`CTE_INLINING`** ([`cte_inlining.cpp`](https://github.com/duckdb/duckdb/blob/main/src/optimizer/cte_inlining.cpp)):
  - An unreferenced CTE is removed, unless it has side effects.
  - A CTE referenced once is inlined.
  - A CTE referenced several times is inlined unless that is expensive: more than 2 base tables
    and (base tables × references) > 10.
  - Never inlined if marked `MATERIALIZED`, if it has side effects, or if a reference would end
    up beneath another DELIM_JOIN.
- **Correlated subqueries** in `WHERE`/`SELECT` are decorrelated into joins; `DELIMINATOR` then
  removes duplicate-eliminated joins where it can.

---

## 2. Unused column pruning (`UNUSED_COLUMNS`)

Implemented in
[`remove_unused_columns.cpp`](https://github.com/duckdb/duckdb/blob/main/src/optimizer/remove_unused_columns.cpp)
(~1,870 lines). It is a top-down visitor:

- The root starts with `everything_referenced = true`, since the query's output columns are needed.
- At each operator it collects the column bindings its parent references, removes outputs nobody
  references (`ClearUnusedExpressions`), renumbers the survivors, and recurses with the reduced set.

### When it runs

From [`optimizer.cpp`](https://github.com/duckdb/duckdb/blob/main/src/optimizer/optimizer.cpp):

- Once, after join ordering, join elimination and the unnest rewriter.
- Again, if statistics propagation removed filters or join conditions it proved redundant, or if
  Top-N used late materialization — both can leave scan columns that nothing reads.
- `COLUMN_LIFETIME` runs afterwards and adds projection maps so each operator drops columns as
  soon as nothing downstream needs them (smaller hash-table payloads, for example).

### Per-operator rules

| Operator | Rule |
|---|---|
| Projection | Unreferenced expressions are dropped. If none remain (e.g. `EXISTS (SELECT * …)`), it projects the constant `42` so the row count is preserved |
| Table scan | Column IDs are reduced to what parents or pushed-down filters need. Filter-only columns are read but not output. If nothing is needed (`SELECT count(*)`), only the row id is read. For `struct_extract` and VARIANT fields, the extraction is pushed into the scan when the scan supports it |
| Aggregate | Unused aggregate expressions are dropped. If no aggregates and no groups remain, `COUNT(*)` is inserted so the cardinality stays correct |
| Window | Unused window expressions are dropped. If none remain, the window operator is removed |
| Inner equi-join `X = Y` | References to the build-side column `Y` are rewritten to the probe-side `X`, so `Y` need not be kept in the hash table. Duplicate join conditions left behind are removed |
| `UNION ALL` | Pruned by position across every branch; a projection is pushed under each branch. `COUNT(*)` over a union keeps column 0 |
| Materialized CTE | The body is pruned to the union of columns used by all readers (see exemptions) |
| Recursive CTE | Pruned via `TryPruneRecursiveCTE`; on failure, treated as everything referenced |

### Deliberate exemptions

1. **`UNION` (without `ALL`), `INTERSECT`, `EXCEPT` are not pruned.** Every column participates
   in duplicate elimination, so removing one changes the result. Children are still visited as
   roots.
2. **Grouping keys are never removed, even if unreferenced.**
   `SELECT x FROM (SELECT a, b, sum(c) x FROM t GROUP BY a, b)` still groups by `a, b`, because
   removing a key changes the row count. An open `FIXME` notes that unused keys are still scanned
   and output although they are only needed for grouping.
3. **Volatile window expressions are kept even if unused**, so they are still evaluated. Equal
   volatile join conditions are never deduplicated ("two evaluations"). No equivalent guard was
   found for projections: an unused `random()` column in a plain subselect appears to be dropped.
4. **Floating-point equi-joins are excluded from the build-to-probe rewrite**, because
   `+0.0 = -0.0` compares equal while the values differ.
5. **Only inner joins get that rewrite.** In outer, semi, anti and mark joins the two sides are
   not interchangeable.
6. **`INSERT`, `UPDATE`, `DELETE`, `MERGE` and `RETURNING` keep their full input**, since the
   child schema must stay fixed. A TODO notes that pushing projections down from `RETURNING` is
   not implemented.
7. **`COPY TO` and `PIVOT` keep everything.**
8. **CTE pruning is conservative.**
   - The body is pruned only if every reader of the CTE was seen during the walk; otherwise a
     missed reference could produce wrong results.
   - Skipped when the CTE's output exposes its readers' bindings, because operators above could
     reference them directly.
   - A reader that needs all columns (e.g. a DML reader) disables pruning for that CTE.
9. **Table functions with a child operator** (table-in-out functions) keep all of the child's
   columns.
10. **Scan filters that cannot be re-pushed** after a nested-field extraction make the scan
    output every column in scan order — the safe fallback.
11. **Secure views** go through `VisitSecureView`, which carries replacement expressions so the
    view's output expressions stay consistent.

---

## Implications for Pure-to-SQL on DuckDB

- Nested subselects generated by the relational router are effectively free on DuckDB: nesting
  adds no plan operators, pass-through layers have no runtime operator, and unused columns are
  pruned down to the scan.
- Worth checking in generated SQL, because they block or limit pruning:
  - `UNION` where `UNION ALL` would be correct.
  - `GROUP BY` keys or `DISTINCT` columns that the outer query does not use.
  - CTEs explicitly marked `MATERIALIZED`.

---

## Re-verification

```sql
EXPLAIN SELECT ...;                                   -- which projections and columns survive
SET disabled_optimizers = 'unused_columns';           -- compare against the plan without pruning
SET disabled_optimizers = 'projection_pullup,redundant_order';
```

To re-read the source:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/duckdb/duckdb.git
cd duckdb
git sparse-checkout set src/optimizer src/planner src/execution/physical_plan
```

Start from `src/optimizer/optimizer.cpp` for pass ordering, then
`src/optimizer/remove_unused_columns.cpp` (`VisitOperator`) for the per-operator rules.
