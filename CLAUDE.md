# folio

Scala 3 cursor-based pagination library.

## Modules

- `core` — main library (`folio-core`), no runtime dependencies (Weaver/Cats are test-only)
- `module/effect/cats` — optional Cats adapter (`folio-cats`), derives `FolioEffect` from `ApplicativeError`
- `module/database/skunk` — Skunk integration (`folio-skunk`), depends on `folio-cats`
- `it` — PostgreSQL integration tests, not published
- `example` — usage example, not published

## Key types

- `Position` — pagination strategy, either `Keyset` (id-based, O(1) seek) or `Offset` (offset-based)
- `Cursor` — opaque base64url-encoded string with embedded query fingerprint for stale detection
- `FieldSchema[FIELD]` — typeclass mapping enum cases to string column names; `FieldSchema.fromMapping` derives the reverse `fromName` from the forward `name` mapping at compile time via `Mirror.SumOf`
- `KeysetField[FIELD, T]` — typeclass that designates the id field within `FIELD` and extracts the id `Long` from a row; required to enable keyset pagination
- `CursorCodec` — typeclass for encoding/decoding the opaque cursor string (default: base64url)
- `Query[FIELD]` — incoming query (filters, cursor, limit, sort)
- `ResolvedQuery[FIELD]` — query handed to `fetchRows` with cursor decoded into a concrete `Position` and a fetch limit applied
- `Page[T]` — paginated result with previous/next cursors; built via `Page.withPagination`
- `FolioError.CursorDecodingError` — decoding failures (invalid base64, bad format, stale cursor, etc.)

## User responsibilities

Users must define:
1. A `FIELD` enum (e.g. `enum MessageField { case Id, EnqueuedAt, LastReadAt }`)
2. `given FieldSchema[FIELD]` — typically built via `FieldSchema.fromMapping { case ... => "col_name" }`, which only requires the forward `FIELD => String` direction
3. _(optional, for keyset)_ `given KeysetField[FIELD, T]` — `KeysetField(idField, _.id)` designates the id field and extracts the id from each row. The id type is inferred from the extractor (any type with a `CursorValueCodec` instance — folio ships them for `Int`, `Long`, `String`, `OffsetDateTime`).
4. _(optional, to enable keyset on non-id sort fields)_ chain `.withField(field, extract)` calls onto the `KeysetField`, e.g. `KeysetField(Id, _.id).withField(EnqueuedAt, _.enqueuedAt)`. Each registered field needs a `CursorValueCodec` for its value type.

Keyset pagination is enabled only when `KeysetField[FIELD, T]` is in scope.

## Cursor strategy selection

`Position.fromQuery` picks the strategy automatically:

When `KeysetField[FIELD, ?]` is provided:
- All sort fields registered (via `KeysetField.apply` / `.withField`) → `Keyset` (O(1) seek). The id field is always registered. The cursor anchor encodes one value per sort field, with the id appended as a tiebreaker if not already in the sort.
- Any sort field not registered → `Offset` (offset fallback)
- No sort specified → `Keyset` with default ascending id sort

When `KeysetField[FIELD, ?]` is not provided:
- Always `Offset` (offset-based pagination only)

## Page assembly

`Page.withPagination[F[_]: FolioEffect, T, FIELD](query, fetchRows)` is the entry point. The caller supplies a
`fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]` that fetches `limit + 1` rows (use `Limit.fetchLimit` from the
`ResolvedQuery`); the extra row is dropped and used for hasMore detection. `FolioEffect` deliberately exposes only
`map` and `raiseError`, keeping `folio-core` independent of Cats Effect, ZIO, Kyo, and `Future`. The result is
`F[Page[T]]`; cursor failures are raised as `FolioError` in `F` rather than returned as a nested `Either`. Synchronous
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

```
sbt --client cleanFull
sbt --client compile
sbt --client test
sbt --client example/run
```

Tests use [weaver-cats](https://typelevel.org/weaver-test/).

With sbt 2, `sbt --client test` can return a cached no-op result when no code or test inputs changed since the latest
test run. In that case the output may say `Passed: Total 0` / `No tests to run`, which means nothing was re-executed,
not that test discovery is broken. Run `sbt --client cleanFull` first when you need to force the suites to execute again.
