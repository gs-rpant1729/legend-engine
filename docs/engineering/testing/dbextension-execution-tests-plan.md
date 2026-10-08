# Plan: replace dbExtension SQL-string asserts with execution-based tests

Status: proposed, not yet implemented.

## Goal

Tests in the `legend-engine-xt-relationalStore-<db>-pure` modules mostly assert the generated SQL
string. For every database that has a test connection (a `-PCT` module registering a
`TestConnectionIntegration`), replace those asserts with tests that execute against the real
database and compare results.

## Scope

PCT-capable databases: H2, DuckDB, Postgres, MemSQL, Snowflake, Spanner, SqlServer, Databricks,
Oracle, Trino, ClickHouse, DB2.

SQL-string tests in their `-pure` modules at the time of writing:

| DB | Tests | Main files |
|---|---|---|
| Snowflake | ~80 | `testSnowflakeSqlFunctionsInMapping` (20), `testSnowflakeToSQLString` (18), `executionPlanTestSnowflake` (22, plan strings), post-processor, temp-table, slice, with |
| Databricks | 63 | `testDatabricksDynaFunctions` (49), `testDatabricksToSQLString` (14) |
| MemSQL | ~53 | `testToSQLString` (17), `testSqlFunctionsInMapping` (17), plus some small files |
| Postgres | 41 | `testPostgresToSQLString` (31), plus some small files |
| SqlServer | 15 | `customSqlServerTests` |
| DuckDB | 9 | `testDuckDBSQLGeneration` |
| Oracle, DB2, ClickHouse | 2, 2, 1 | |
| Spanner, Trino, H2 | 0 | H2 has no `-pure` module |

Out of scope:

- `*-sqlDialectTranslation-pure` tests. They are unit tests of the SQL text generator, so string
  comparison is correct there.
- Databases without a PCT module (Athena, BigQuery, Presto, Redshift, Sybase, SybaseIQ, Hive,
  SparkSQL, Aurora). They keep their string tests; converting them is a follow-up.

Some of these tests never touch their database. For example, `testSnowflakeSqlFunctionsInMapping`
runs against `testDataTypeMappingRuntime()`, which is an H2 connection, and then compares the SQL
text.

## The dependency constraint

```
<db>-PCT  ──depends on──▶  <db>-pure
   │                         (dialect code + today's string tests)
   ├─▶ relationalStore-PCT-pure       (getTestConnection native, setupDatabase)
   ├─▶ <db>-execution                 (JDBC driver, StoreExecutor)
   └─▶ <db>TestConnectionIntegration  (src/main/java, ServiceLoader)
```

`-pure` cannot depend on `-PCT`, because that would create a cycle. Without that dependency it has
no test connection, JDBC driver or executor, so a test that runs on the database cannot be **run**
from `-pure`.

Resolution: **define** tests in `-pure` (or `core_relational`) and **run** them from `-PCT`. The
semistructured tests already work this way:

- The tests are `<<paramTest.Test>>` functions in core-pure that take `conn: Connection[1]`.
- `core_relational_<db>_pct/testSemistructured.pure` collects them with
  `collectParameterizedTests(pkg, '<db>', getTestConnection(DatabaseType.X), [], [])`.
- `Test_Relational_<DB>_Semistructured.java` runs them with the `TestServerResource` from
  `TestConnectionIntegrationLoader`.

What makes this work without new dependencies is that
`executeLegendFunction(func, mapping, db, conn, csvs, expectedCsv)` in
`core_relational/relational/tests/planExecutionTestUtility.pure` looks up
`meta::relational::tests::pct::process::setupDatabase` by name at runtime. A `-pure` module can
therefore compile tests that call it with no compile-time dependency on PCT. `setupDatabase` loads
the data into temp tables inside the same plan, so tests stay isolated on shared cloud accounts.

## Triage rules

Apply to each test, in this order:

1. **Shared family** (same test copied across databases with different expected SQL): move to
   `core_relational` as a parameterized test. See [Shared families](#shared-families).
2. **Function-level dialect check** (e.g. the 49 Databricks dyna-function tests,
   convert/date/cast tests):
   - delete it if a PCT test already covers the function on that adapter
   - otherwise write a new `<<PCT.test>>` in the owning `core_functions_*` module and delete the
     original
   - if the new PCT test fails on an adapter, add a manifest exclusion and file an issue
3. **Mapping or dialect behaviour specific to one database**: convert to
   `<<paramTest.Test>> fn(conn: Connection[1])` in that database's `-pure` module, collected by its
   `-PCT` module.
4. **Tests where the SQL text is the subject** (DDL generation, CTE post-processor, temp-table SQL
   statements, `connectionEqualityTest`): keep as string tests.

## Shared families

These families are copied across `-pure` modules with only the expected SQL changed. Most already
have an H2 version in `core_relational`:

| Family | PCT-capable copies | Existing `core_relational` version |
|---|---|---|
| SliceTakeLimitDrop | memsql, postgres, snowflake | `functions/tests/testSliceTakeLimitDrop.pure` |
| Paginated (+WithVariables) | postgres, snowflake, memsql (plan) | `functions/tests/testPaginated.pure` |
| Sort | memsql, postgres | yes |
| WithFunction | memsql, postgres, snowflake | `tests/query/testWithFunction.pure` |
| SqlFunctionsInMapping | memsql, postgres, snowflake | sqlFunction model tests |
| TDSWindowColumn / ProjectWithWindowColumns | memsql, snowflake | yes |
| FilterEqualsWithOptionalParameter | memsql, postgres, snowflake | `executionPlan/tests/executionPlanTest.pure` |
| In with collection input / SliceLimitTakeDrop with variables | memsql, snowflake | partial |

For each family:

1. Rewrite the `core_relational` version as `<<paramTest.Test>> fn(conn: Connection[1])` using
   `executeLegendFunction` and CSV fixtures.
2. Delete the per-database copies.
3. Add a `meta::relational::tests::pct::<db>::shared::testCollection` to every PCT-capable database.
   The family then runs on all of them, including the ones that never had a copy (DuckDB,
   SqlServer, Oracle, ...).

H2 keeps its coverage through the H2 PCT collection. Presto, Sybase and SybaseIQ keep their string
copies, because they have no test connection.

## Execution-plan tests

There are 22 in Snowflake, 5 in MemSQL and 1 in Postgres. Each scenario is converted to executing
its plan where the behaviour can be seen in the results:

| Scenario | Can execution cover it? | How |
|---|---|---|
| Optional params (`equal_null`), `in` with collection / literal list / relational-result input, NOT IN, slice/paginated with variables | Yes | Run with parameter values, including the empty and null cases. Needs a new `executeLegendFunction` variant that takes `Pair<String,Any>[*]` params, because today it passes `[]` to `executePlanAsJSON`. These become shared tests. |
| Graph-fetch temp-table strategy (db/schema, quoted identifiers, column order, parent temp table, cross-store) | Yes | Execute and compare the graph-fetch JSON, building on `executeLegendFunctionWithModelChainJSON`. The column-order test turns into a value check: if the order is wrong, the values come out wrong. |
| Tabular functions (5 tests) | Yes, with an extra setup step | The test needs a UDTF in Snowflake. CSV setup only creates tables, so `executeLegendFunction` needs an optional list of raw setup SQL statements to run before the query. |
| Query tags on/off (4 tests) | Partly | Run a query inside the plan that selects `CURRENT_QUERY_TAG()`, to check "tag present / tag absent" against the real database. The `finallyExecutionNodes` unset cannot be seen from inside the query, so keep a structural assert on the plan (node type and SQL of the finally node), not a full plan string. |
| `templateFunctionsList()` assert | No | Drop it, because it duplicates the same check in core. |

Expected result: only about 2–4 tests still check plan structure, and they assert on nodes rather
than the full `planToString` output.

## Infrastructure

In `core_relational`, `planExecutionTestUtility.pure`:

- `executeLegendFunction` gains a parameter-values variant and an optional raw-setup-SQL variant.
- Add CSV fixtures for the shared models: simple person/firm/product/trade/interaction, and the
  sqlFunction `dataTable`. These replace the H2-only `executeInDb` setup in `<<test.BeforePackage>>`
  functions.

Shared Java runner:

- Add `RelationalTestConnectionSuite.build(pkg, DatabaseType)` in the shared PCT runtime module, so
  each database's runner is a few lines instead of a copy of `Test_Relational_DuckDB_Semistructured`.

Per `-PCT` module:

- add `core_relational_<db>` to its `*.definition.json` dependencies
- add the `<db>-pure` Maven dependency where it is missing (DuckDB PCT is the only one)
- add two collections: `shared` (from `core_relational`) and `dialect` (from `<db>-pure`)

## Rollout

| PR | Content |
|---|---|
| 0 | Infrastructure. Pilot the shared families on H2 and DuckDB, and delete DuckDB's `testDuckDBSQLGeneration` string tests. |
| 1 | Turn on the shared collection for Postgres, SqlServer, Oracle, DB2, ClickHouse, Trino and Spanner. Migrate the Postgres and SqlServer tests that are specific to those databases. |
| 2 | Write the new PCT tests found during the function-level triage (written for all stores). |
| 3 | Snowflake: execution-plan conversions, mappings, and post-processor triage. |
| 4 | Databricks and MemSQL. |
| 5 | Cleanup: delete the `@Ignore`d `Test_Pure_Relational_DbSpecific_*` runners, `dbTestRunner` / `DbTestConfig` / `dynaFunctionTestRunner` / `literalTestRunner`, and any fixtures left unused. Update `docs/pct/`. |

Each PR's description includes a table that maps every deleted test to what replaces it: a PCT test
name, a shared `paramTest`, or "kept as string".

Converted tests run only in the per-database PCT CI groups. There is no plan-only mode for faster PR
feedback.

## Risks

- **Value formatting across databases:** floats, timestamp precision and time zones differ.
  `sortCsv` handles row order but not formatting, so expected CSVs may need normalising per
  database.
- **Identifier case:** Snowflake upper-cases unquoted identifiers. Fixtures should rely on the
  quoting that `setUpDataSQLsV2` already does, not on hand-written DDL.
- **Temp-table support:** `setupDatabase` forces `isTempTable = true`. Check this on Databricks and
  Spanner before PR 1 and PR 4. If they don't support it, they need a per-dialect fallback to
  uniquely named tables.
- **Real bugs will surface**, especially for the tests that currently run on H2. Plan for fixes or
  manifest exclusions in PR 3 and PR 4.

## Open questions

- **Placement of shared `paramTest`s:** should they stay in their current packages, or move to one
  root such as `meta::relational::tests::dialect::*`? One root makes the per-database collection a
  single `collectParameterizedTests` call; that is the current preference.
