# folio

Scala 3 cursor-based pagination library. Domain language: `CONTEXT.md`. Decisions: `docs/adr/README.md`.

## Modules

- `core` — `folio-core`, no runtime deps (Weaver is test-only)
- `module/effect/cats` — `folio-cats`, derives `FolioEffect` from `ApplicativeError`
- `module/database/skunk` — `folio-skunk`, depends on `folio-cats`
- `it` — PostgreSQL integration tests (JVM + Native), not published
- `example` — usage example (JVM only), not published

All but `it`/`example` cross-build JVM / JS / Native via sbt 2 `projectMatrix` (single shared source
tree, no sbt-typelevel). JVM keeps the short name; other rows are `*JS` / `*Native`.

## Key types

- `Query[FIELD]` — incoming query (filters, cursor, limit, ordering)
- `ResolvedQuery[FIELD]` — handed to `fetchRows`: cursor decoded into a `Position`, `fetchLimit` applied
- `Position` — `Keyset` (O(1) seek) or `Offset`
- `Cursor` — opaque base64url string embedding a query fingerprint for stale detection
- `FieldSchema[FIELD]` — enum case → column name; `fromMapping` derives the reverse via `Mirror.SumOf`
- `KeysetField[FIELD, T]` — registers the unique field + extractors; presence enables keyset
- `CursorCodec` — cursor string encoding (default base64url)
- `Page[T]` — result with previous/next cursors; built via `Page.withPagination`
- `FolioError` — `CursorDecodingError`, `InvalidQuery`

## User responsibilities

1. A `FIELD` enum, e.g. `enum MessageField { case Id, EnqueuedAt, LastReadAt }`
2. `given FieldSchema[FIELD]` — usually `FieldSchema.fromMapping { case ... => "col_name" }`
3. _(optional, for keyset)_ `given KeysetField[FIELD, T]` — `KeysetField.uniqueBy(Id, _.id)`; value type
   inferred from the extractor (needs a `FieldValueCodec`: `Int`, `Long`, `String`, `OffsetDateTime`)
4. _(optional)_ chain `.withField(field, extract)` to allow keyset ordering on more fields

## Cursor strategy selection

`Position.fromQuery`:

- No `KeysetField` in scope → always `Offset`
- `KeysetField` in scope, all order fields registered → `Keyset`; anchor holds one value per order
  field, unique field appended as tiebreaker if absent from the ordering
- Any order field unregistered → `Offset` fallback
- No ordering → `Keyset` with default ascending unique-field ordering

## Query construction

`Query(limit = n.items).orderBy(field.ascending, ...)` — `filters` and `ordering` default to empty.
`ordering` is an order-sensitive `Vector[OrderBy[FIELD]]`; `orderBy` replaces it. Duplicate order
fields are rejected as `FolioError.InvalidQuery` at `Page.withPagination` / `Pagination.buildSql`.

`filters` is a conjunctive `Set[FilterBy[FIELD]]`; `FilterBy.ExactMatch(field, value)` is typed and
needs a `FieldValueCodec`. Identity is `(field, encoded value)`, so `ExactMatch(Id, 1)` and
`ExactMatch(Id, 1L)` remain distinct and the fingerprint ignores set insertion order. `Set` is
invariant, so ascribe `Set[FilterBy[FIELD]](...)` outside an expected-type position. Filtering needs
no `KeysetField` registration (registration is about *ordering*); core never filters rows, it passes
`filters` into `ResolvedQuery`. `folio-skunk` renders them as parameterized `=` predicates in the
outer `WHERE` before positioning on both branches, so the inner `SELECT` must project every filter
column under its `FieldSchema` name (ADR 0009).

## Page assembly

`Page.withPagination[F[_]: FolioEffect, T, FIELD](query, fetchRows)` is the contextual entry point;
adapters that also render a `ResolvedQuery` use the explicit overload taking
`keyset: Option[KeysetField[FIELD, T]]` and pass the same option to the driver. `fetchRows` fetches
`ResolvedQuery.fetchLimit` rows (limit + 1); the extra row drives hasMore and is dropped.
`Page[T].map` preserves limit and both cursors. `FolioEffect` exposes only `map` and `raiseError`,
keeping core free of Cats Effect / ZIO / Kyo / `Future` (ADR 0008); failures are raised in `F`, not
returned as a nested `Either`. Sync callers use `FolioEffect.Id` (throws). Cats users
`import folio.cats.given`. Pure decoding APIs still return `Either`.

## Code style

- Scala 3 brace-less syntax
- Never try/catch — use `Try`, `Either`, etc.
- `Either.cond(condition, right, left)` over `if/then/else`
- Descriptive names, no abbreviations (`fingerprint` not `fp`, `fieldSchema` not `fs`)
- Run `format-file` / `sbt --client scalafmtAll`
- In tests, combine assertions with `List(expect.same(...), ...).combineAll` — not `and`

## Build

SBT 2 with a separate server; prefer `--client`:

```
sbt --client compile                # all modules, all platforms
sbt --client core/compile           # core, JVM
sbt --client coreJS/test            # core tests on JS (also coreNative/test)
sbt --client test                   # all aggregated tests
sbt --client integration/test       # Postgres ITs (also integrationNative/test)
sbt --client publishLocal
sbt --client example/run
sbt --client scalafmtAll
sbt --client clean                  # force a full re-run
```

Modules: `core`, `cats`, `skunk` (each `+JS`/`+Native`), `integration`, `integrationNative`,
`example`. Versions and deps are inline in `build.sbt`; cross-built deps use `%%`.
Tests use [weaver-cats](https://typelevel.org/weaver-test/). `test` is incremental — `Passed: Total 0`
means nothing was re-executed, not that discovery broke; `clean` first if you need every suite.
