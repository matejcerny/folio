# folio

Scala 3 cursor-based pagination library.

## Modules

- `core` — main library (`folio-core`), no runtime dependencies (Weaver is test-only); JVM / JS / Native
- `module/effect/cats` — optional Cats adapter (`folio-cats`), derives `FolioEffect` from `ApplicativeError`; JVM / JS / Native
- `module/database/skunk` — Skunk integration (`folio-skunk`), depends on `folio-cats`; JVM / JS / Native
- `it` — PostgreSQL integration tests (JVM + Native), not published
- `example` — usage example (JVM only), not published

Cross-building uses sbt 2 `projectMatrix` (no sbt-typelevel). JVM keeps the short project name; JS/Native rows are `*JS` / `*Native`.

## Key types

- `Position` — pagination strategy, either `Keyset` (id-based, O(1) seek) or `Offset` (offset-based)
- `Cursor` — opaque base64url-encoded string with embedded query fingerprint for stale detection
- `FieldSchema[FIELD]` — typeclass mapping enum cases to string column names; `FieldSchema.fromMapping` derives the reverse `fromName` from the forward `name` mapping at compile time via `Mirror.SumOf`
- `KeysetField[FIELD, T]` — typeclass that designates the unique field within `FIELD` and extracts its value from a row; required to enable keyset pagination
- `CursorCodec` — typeclass for encoding/decoding the opaque cursor string (default: base64url)
- `Query[FIELD]` — incoming query (filters, cursor, limit, ordering)
- `ResolvedQuery[FIELD]` — query handed to `fetchRows` with cursor decoded into a concrete `Position` and a fetch limit applied
- `Page[T]` — paginated result with previous/next cursors; built via `Page.withPagination`
- `FolioError.CursorDecodingError` — decoding failures (invalid base64, bad format, stale cursor, etc.)

## User responsibilities

Users must define:
1. A `FIELD` enum (e.g. `enum MessageField { case Id, EnqueuedAt, LastReadAt }`)
2. `given FieldSchema[FIELD]` — typically built via `FieldSchema.fromMapping { case ... => "col_name" }`, which only requires the forward `FIELD => String` direction
3. _(optional, for keyset)_ `given KeysetField[FIELD, T]` — `KeysetField.uniqueBy(idField, _.id)` designates the unique field and extracts its value from each row. The id type is inferred from the extractor (any type with a `CursorValueCodec` instance — folio ships them for `Int`, `Long`, `String`, `OffsetDateTime`).
4. _(optional, to enable keyset on non-id order fields)_ chain `.withField(field, extract)` calls onto the `KeysetField`, e.g. `KeysetField.uniqueBy(Id, _.id).withField(EnqueuedAt, _.enqueuedAt)`. Each registered field needs a `CursorValueCodec` for its value type.

Keyset pagination is enabled only when `KeysetField[FIELD, T]` is in scope.

## Cursor strategy selection

`Position.fromQuery` picks the strategy automatically:

When `KeysetField[FIELD, ?]` is provided:
- All order fields registered (via `KeysetField.uniqueBy` / `.withField`) → `Keyset` (O(1) seek). The unique field is always registered. The cursor anchor encodes one value per order field, with the unique field appended as a tiebreaker if not already in the ordering.
- Any order field not registered → `Offset` (offset fallback)
- No ordering specified → `Keyset` with default ascending id ordering

When `KeysetField[FIELD, ?]` is not provided:
- Always `Offset` (offset-based pagination only)

## Query construction

`Query` defaults `filters` and `ordering` to empty and requires only `limit`. Prefer
`Query(limit = n.items).orderBy(field.ascending, ...)` over spelling out empty collections.
`ordering` is a `Vector[OrderBy[FIELD]]` (order-sensitive). `orderBy` replaces the ordering.
Duplicate order fields (same field twice, regardless of order) are rejected at
`Page.withPagination` and `Pagination.buildSql` as `FolioError.InvalidQuery`.

## Page assembly

`Page.withPagination[F[_]: FolioEffect, T, FIELD](query, fetchRows)` is the contextual convenience entry point. Adapters
that also render a `ResolvedQuery` use the explicit
`Page.withPagination(query, fetchRows, keyset: Option[KeysetField[FIELD, T]])` overload and pass that same option to the
driver. The caller supplies a `fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]` that fetches `ResolvedQuery.fetchLimit` rows
(one more than the page `limit`); the extra row is dropped and used for hasMore detection. `ResolvedQuery.limit` is the
page size the caller requested, and `Page[T].map` remaps page data while preserving the limit and both cursors. `FolioEffect` deliberately exposes only
`map` and `raiseError`, keeping `folio-core` independent of Cats Effect, ZIO, Kyo, and `Future`. The result is
`F[Page[T]]`; cursor and invalid-query failures are raised as `FolioError` in `F` rather than returned as a nested `Either`. Synchronous
callers use `FolioEffect.Id` (which throws on failure). Cats and Cats Effect callers can depend on `folio-cats` and
`import folio.cats.given`; driver modules hide that bridge. Other effect ecosystems provide the tiny native instance.
Pure decoding APIs continue to return `Either`.

## Code style

- Scala 3 brace-less syntax throughout
- Never use try/catch — use `scala.util.Try`, `Either`, or other Scala constructs instead
- Use `Either.cond(condition, rightValue, leftValue)` instead of `if condition then Right(...) else Left(...)` (and the negated form)
- Use descriptive variable names — avoid short abbreviations (e.g. `fingerprint` not `fp`, `cursorPosition` not `pos`, `fieldSchema` not `fs`)
- Formatted with scalafmt (`sbt --client scalafmtAll`)
- In tests, when asserting multiple `expect.same` values, use `List(expect.same(...), ...).combineAll` — not `and`

## Build

The project uses SBT 2 with a separate server. Prefer `--client`:

```
sbt --client compile                # compile all aggregated modules, all platforms
sbt --client core/compile           # compile core for JVM
sbt --client coreJS/test            # run core tests on JS
sbt --client coreNative/test        # run core tests on Native
sbt --client test                   # unit + integration tests for all aggregated platforms
sbt --client integration/test       # Postgres integration tests (JVM)
sbt --client integrationNative/test # Postgres integration tests (Native)
sbt --client publishLocal           # publish all non-skipped modules locally
sbt --client example/run
sbt --client scalafmtAll
sbt --client clean                  # clear outputs when you need a full re-run
```

Module names: `core`, `coreJS`, `coreNative`, `cats`, `catsJS`, `catsNative`,
`skunk`, `skunkJS`, `skunkNative`, `integration`, `integrationNative`, `example`.

Cross-compilation uses sbt 2 `projectMatrix` with a single shared source tree per module. Integration is JVM +
Native only. Versions and dependencies are defined inline in `build.sbt`. Cross-built deps use `%%` (sbt 2
appends the platform suffix).

Tests use [weaver-cats](https://typelevel.org/weaver-test/).

With sbt 2, `test` is incremental: if nothing relevant changed it may report `Passed: Total 0` / `No tests to run`.
That means nothing was re-executed, not that discovery is broken. Run `sbt --client clean` first when you need
every suite to execute again.
