# Migrating pgmq4s inspector pagination to folio

Date: 2026-07-17

## Outcome

pgmq4s can replace its custom inspector pagination with folio without adding a
new pagination algorithm or a new cursor value type. All five PGMQ order fields
fit folio's current keyset model, and `folio-skunk` already generates the SQL
needed by the Skunk backend.

Both libraries are effect-agnostic. pgmq4s-core is written against its own
`PgmqEffect`; folio is written against `FolioEffect`. The integration bridges
the two with a single Cats-free adapter given (see **Effect model** below), so
`PgmqInspector.apply[F: PgmqEffect]` can call `Page.withPagination[F, ...]`
without pulling Cats into pgmq4s-core.

The recommended integration keeps pgmq4s's existing core/backend split:

1. `pgmq4s-core` uses `folio.Page.withPagination` to decode cursors, resolve a
   `ResolvedQuery`, fetch `limit + 1`, construct cursors, and assemble a page.
2. `pgmq4s-skunk` uses `folio.skunk.Pagination.buildSql` to turn that
   `ResolvedQuery` and its existing base `SELECT` into Skunk SQL.

This replaces both custom layers while retaining the backend SPI, its test seam,
and a clean route for future non-Skunk inspector backends. Calling
`folio.skunk.Pagination.withPagination` directly from
`SkunkPgmqInspector.scala` is possible, but would move pagination ownership out
of `PgmqInspector` and make the existing backend abstraction largely pointless.

The only hard folio blocker for pgmq4s's current platform matrix is
cross-platform publication: pgmq4s cross-publishes `core` and `skunk` for JVM,
Scala.js, and Scala Native, while folio is JVM-only and has no Scala.js/Native
or sbt-typelevel plumbing yet. See **P0** below for the real size of that lift.

## Effect model

pgmq4s-core gains a direct dependency on `folio-core` and defines one adapter
given. It is Cats-free and works identically for `Future` and for any Cats
`MonadThrow` (both already have a `PgmqEffect` instance):

```scala
given [F[_]](using effect: PgmqEffect[F]): FolioEffect[F] with
  def map[A, B](fa: F[A])(f: A => B): F[B] = effect.map(fa)(f)
  def raiseError[A](error: FolioError): F[A] = effect.raiseError(error) // FolioError <: Throwable
```

`FolioError` extends `Exception`, so it satisfies `PgmqEffect.raiseError`'s
`Throwable` requirement directly. This one given lets
`PgmqInspector.apply[F: PgmqEffect]` call `Page.withPagination[F, ...]`.

The Skunk backend stays on Cats Effect `Temporal` (it does real Skunk I/O) and
uses the pure `folio.skunk.Pagination.buildSql`. It never summons `FolioEffect`,
so there is no ambiguity with the `folio-cats` bridge that is transitively
visible through `folio-skunk`.

## Where the custom implementation actually lives

`SkunkPgmqInspector.scala` itself is only a constructor. It creates a
`SkunkPgmqInspectorBackend` and passes it to the core `PgmqInspector`
implementation. The pagination implementation is spread across:

| Responsibility | Current pgmq4s source | Folio replacement |
| --- | --- | --- |
| Page orchestration and backward traversal | `PgmqInspector.scala`, `CursorPage.scala` | `Page.withPagination` |
| Cursor wire format and parsing | `Cursor.scala`, `MessageCursor.scala`, parts of `MessageSortField.scala` | `Cursor`, `CursorCodec`, `CursorValueCodec` |
| Limit validation and `limit + 1` | `PageSize.scala` | `Limit`, `ResolvedQuery.limit` |
| Order model | `Sort.scala` | `OrderBy`, `Order` |
| Keyset SQL and null handling | `SkunkPgmqInspectorBackend.scala` | `folio.skunk.Pagination.buildSql` |
| Result page | `CursorPage.scala` | `Page` |

`countMessages` and `countArchive` do not involve pagination and should remain
unchanged.

## Domain fit

The row decoded by Skunk is `RawMessage`, so the keyset metadata should be
defined for `KeysetField[MessageSortField, RawMessage]`, not for
`InspectedMessage`. The completed `Page[RawMessage]` can then be mapped to
`Page[InspectedMessage]` without changing its cursors.

| `MessageSortField` | SQL projection | `RawMessage` extractor | Shape | Existing folio support |
| --- | --- | --- | --- | --- |
| `Id` | `msg_id` | `_.msgId` | unique, required `Long` | `CursorValueCodec[Long]`, Skunk `int8` |
| `EnqueuedAt` | `enqueued_at` | `_.enqueuedAt` | required `OffsetDateTime` | timestamp codec, Skunk `timestamptz` |
| `VisibleAt` | `vt` | `_.vt` | required `OffsetDateTime` | timestamp codec, Skunk `timestamptz` |
| `ReadCount` | `read_ct` | `_.readCt` | required `Int` | `CursorValueCodec[Int]`, Skunk `int4` |
| `LastReadAt` | `last_read_at` | `_.lastReadAt` | absentable `Option[OffsetDateTime]` | `KeysetValue.Absent`, Skunk null predicates |

All fields can therefore be registered on one keyset definition:

```scala
given messageKeyset: KeysetField[MessageSortField, RawMessage] =
  KeysetField
    .uniqueBy(MessageSortField.Id, (message: RawMessage) => message.msgId)
    .withField(MessageSortField.EnqueuedAt, _.enqueuedAt)
    .withField(MessageSortField.VisibleAt, _.vt)
    .withField(MessageSortField.ReadCount, _.readCt)
    .withField(MessageSortField.LastReadAt, _.lastReadAt)
```

Because every currently exposed order field is registered, all inspector queries
remain keyset queries. Folio's offset fallback is reachable only if a future
`MessageSortField` is added without a corresponding `.withField` registration.
That would be a silent performance regression, so pgmq4s should have a test that
every enum value resolves to `Position.Keyset`. A strict "keyset required" mode
in folio would make this enforceable in production as well.

## Existing `last_read_at` boundary bug

The migration also removes a correctness bug in the current custom Skunk SQL.
`last_read_at` is nullable, but the hand-written predicates do not describe the
same order as PostgreSQL's default null placement:

- With `ASC`, PostgreSQL orders present timestamps before nulls. A present
  `ByTimestamp` cursor uses a row comparison, whose comparison against a null
  timestamp is unknown, so it cannot advance into the null block. Once anchored
  on a null, the current predicate includes `last_read_at IS NOT NULL`, which
  reselects the entire present block that sorts *before* the anchor.
- With `DESC`, PostgreSQL orders nulls before present timestamps. A null cursor's
  current predicate only selects another null with a lower ID, so it can never
  advance from the null block into the present block that sorts after it.

The same clauses are used after the core layer flips order direction for a
backward request, so backward traversal is exposed to the same disagreement.
Depending on where a page boundary lands, rows can be skipped, reselected, or
made unreachable. The current integration suite does not cover a mixed
present/null data set; its backend unit tests assert the SQL fragments but not
their result ordering.

Folio avoids this mismatch by owning absent placement explicitly and expanding
the lexicographic predicate for both present and absent anchors. The pgmq4s
migration should add a regression walk in both directions and both orders,
with enough rows to put a page boundary on each side of the present/absent
transition.

## Required work in folio

### P0: cross-build and publish `folio-core` and `folio-skunk`

This is the sole mechanical blocker if pgmq4s keeps its current JVM/JS/Native
matrix, and it is a larger lift than "flip a flag". folio's `build.sbt`
currently declares plain JVM `project`s only — there is no crossProject, no
Scala.js/Native, and no sbt-typelevel plugins in the build at all. The full lift
is:

- Add the Scala.js and Scala Native sbt plugins (and, to match pgmq4s's release
  tooling, the sbt-typelevel plugins) to `project/plugins.sbt`.
- Add the `scala-java-time` dependency so `OffsetDateTime` (the only temporal
  cursor value type folio uses) resolves on JS and Native.
- Convert every module — `core`, `folio-cats`, `folio-skunk` — from `project`
  to `crossProject(JVMPlatform, JSPlatform, NativePlatform)` with
  `CrossType.Pure`; the current source layout is already suitable for
  `CrossType.Pure`.
- Change cross-built dependencies from `%%` to `%%%`, including Cats, Skunk,
  and cross-platform test libraries.
- Point the JVM-only `example` and database integration test (`it`) projects at
  `core.jvm` and `skunk.jvm`, unless those projects are deliberately
  cross-built too.
- Add JVM, JS, and Native compile/test jobs and publish all platform artifacts.

Skunk 1.0.0 itself cross-publishes for JVM/JS/Native, so `folio-skunk` can
follow pgmq4s's platform matrix once folio's build grows the plumbing above.

The existing pgmq4s cursor implementation already uses `java.util.Base64` on
all three platforms, so folio's default `CursorCodec` does not introduce a new
platform concept. Both projects also use Skunk 1.0.0 and compatible Cats
versions.

### No algorithm or codec work is required

In particular, folio already has:

- mixed-direction, multi-field lexicographic keysets;
- a unique `Long` tiebreaker;
- `Int`, `Long`, and `OffsetDateTime` cursor values;
- type-driven absentability for `last_read_at`;
- correct forward and backward traversal across the present/absent boundary;
- parameterized Skunk fragments and safely quoted projected column names;
- opaque subquery wrapping, which works with pgmq4s's existing base `SELECT`;
- typed malformed/stale/incompatible cursor errors.

## Required work in pgmq4s

### 1. Add dependencies

- Add a direct `folio-core` dependency to the cross-built `core` project.
- Add `folio-skunk` to the cross-built `skunk` project. It will also bring
  `folio-core` transitively, but `pgmq4s-core` should retain its own direct
  dependency because its public inspector API uses folio types.
- Pin one folio version in `build.sbt`, alongside the existing Skunk version.

### 2. Replace public pagination types

Since compatibility may be broken, the cleanest API is to expose folio's types
instead of maintaining aliases with the same responsibilities:

- `PageSize` -> `folio.Limit`;
- `Sort[MessageSortField]` and `SortDirection` ->
  `OrderBy[MessageSortField]` and `Order`;
- pgmq4s `Cursor` -> `folio.Cursor`;
- `CursorPage[InspectedMessage]` -> `folio.Page[InspectedMessage]`.

`MessageSortField` remains a pgmq4s domain enum. Its companion should provide a
`FieldSchema` mapping to the real projected column names. The old cursor parsing
and value encoding methods on the enum can then be removed.

Do not expose an unrestricted `folio.Query[MessageSortField]` yet. Folio's
Skunk driver deliberately does not render `Query.filters`, while the inspector
does not let callers supply the inner `SELECT`. Accepting non-empty filters
would claim functionality that is silently ignored. The inspector can accept a
`Limit`, one `OrderBy` (or an explicitly supported collection of orderings), and an
optional `Cursor`, then construct a `Query` with `filters = Set.empty`.

The existing default order is ID descending. Folio's empty-ordering default is ID
ascending, so pgmq4s must continue to materialize
`MessageSortField.Id.descending` rather than pass an empty ordering set.

### 3. Define folio metadata once

- Add `given FieldSchema[MessageSortField]`, preferably in the
  `MessageSortField` companion so user-facing `.ascending`/`.descending` syntax
  works without an extra import.
- Add the `KeysetField[MessageSortField, RawMessage]` shown above in one
  package-private object.
- Bind that definition to a stable name and pass the exact same
  `Some(messageKeyset)` explicitly to both `Page.withPagination` and
  `Pagination.buildSql`.

Using one named option keeps core cursor construction and Skunk SQL rendering
on the same metadata.

### 4. Move core orchestration to folio

Change `PgmqInspectorBackend.browseMessages` from the custom
`(limit, ordering, MessageCursor)` contract to:

```scala
def browseMessages(
    table: String,
    query: ResolvedQuery[MessageSortField]
): F[Seq[RawMessage]]
```

`PgmqInspectorImpl.browse` should:

1. build `Query(limit = limit, cursor = cursor).orderBy(orderBy)`;
2. call
   `Page.withPagination(query, backend.browseMessages(table, _), Some(messageKeyset))`;
3. map `RawMessage` values to `InspectedMessage` while retaining the page
   metadata.

The factory's `PgmqEffect` constraint is sufficient: the `PgmqEffect →
FolioEffect` adapter given (see **Effect model**) supplies what
`Page.withPagination` requires, and every `Future` and Cats `MonadThrow` caller
already has a `PgmqEffect` instance.

Cursor failures are raised in `F`, matching folio's own `Page.withPagination`
contract. The return type is:

```scala
F[Page[InspectedMessage]]
```

`Page.withPagination` raises `FolioError.CursorDecodingError` (a subtype of
`FolioError <: Exception`) through the effect, so a malformed, stale, or
incompatible cursor short-circuits before any fetch and surfaces in the caller's
error channel. Database/session failures are likewise raised in `F`.

If a typed cursor error is wanted at some boundary — cursors commonly come from
untrusted HTTP input and map naturally to a client error — a thin pure decode
helper built on `Cursor.decode` (which returns `Either`) can wrap the raised
form without changing this API.

### 5. Replace Skunk SQL construction

`SkunkPgmqInspectorBackend` should keep its current base projection:

```sql
SELECT msg_id, read_ct, enqueued_at, last_read_at, vt,
       message::text, headers::text
FROM <queue-or-archive-table>
```

Pass that as an `AppliedFragment` to
`Pagination.buildSql(resolved, select, Some(messageKeyset))`. Folio will wrap
it, append the keyset predicate, total `ORDER BY`, and the already-expanded
fetch limit. Stream with `resolved.limit.value`; do not add one again.

The dynamic table fragment and `QueueName` trust boundary remain pgmq4s's
responsibility. Folio treats the supplied `SELECT` as opaque and only quotes
the projected field references it adds itself.

There is an adjacent pre-existing identifier issue worth fixing in the same
backend edit. `QueueName` validation now rejects a blocklist (`$ ; ' --`,
uppercase, length > 48), but it still accepts hyphenated values such as
`my-queue`, and `QueueName.trusted` bypasses validation entirely. The inspector
builds `pgmq.q_<name>` / `pgmq.a_<name>` and splices it into SQL as an unquoted
raw fragment (`#$table`). An accepted hyphenated name is not a valid unquoted
relation reference, and a `trusted` name can be anything. The new base `SELECT`
and `countMessages` should render the schema and relation as separately
double-quoted identifiers, escaping embedded quotes, or accept a structured
trusted table target instead of a raw fully qualified string. Folio's opaque
subquery wrapper cannot repair an unsafe inner `SELECT`.

### 6. Delete superseded implementation

After callers and tests migrate, remove:

- `domain/pagination/Cursor.scala`;
- `domain/pagination/CursorPage.scala`;
- `domain/pagination/MessageCursor.scala`;
- `domain/pagination/PageSize.scala`;
- `domain/pagination/Sort.scala`;
- `SkunkPgmqInspectorBackend.cursorWhereClause` and `browseQuery`.

If source migration ergonomics matter, deprecated aliases or extension methods
can be kept for one release, but none of their pagination logic should remain.

### 7. Update tests and release metadata

- Rewrite core inspector tests to capture `ResolvedQuery`, including
  `limit + 1`, position, canonical ordering, and direction.
- Change the old "stale cursor is ignored" test to assert that a
  `StaleCursor`/`IncompatibleCursor` is raised in `F` and that the backend was
  not called.
- Replace backend `cursorWhereClause` tests with thin adapter tests for the
  PGMQ field mapping and base projection. Folio's own suite should remain the
  source of truth for predicate combinatorics.
- Keep end-to-end pgmq4s integration coverage for forward and backward
  traversal. Add duplicate primary order values and a `last_read_at` data set
  that crosses the present/absent boundary; those are the cases most likely to
  reveal disagreement between cursor metadata and SQL ordering.
- Add a test that every `MessageSortField.values` entry resolves to keyset.
- Treat removal of public pagination types and the cursor wire format as an
  intentional compatibility break in MiMa/release settings and release notes.

## Intentional behavior and compatibility changes

| Area | Current pgmq4s | With folio | Decision/action |
| --- | --- | --- | --- |
| Existing cursor strings | Text fields encoded into a custom base64 format | Folio binary payload and fingerprint | All outstanding cursors become invalid; document the cutover |
| Malformed or mismatched cursor | Silently treated as no cursor/first page | Typed decoding error; no fetch | Adopt folio behavior |
| Changed limit or ordering | Often accepted, even when semantically stale | Fingerprint rejects cursor | Adopt folio behavior |
| Data-set identity | Queue/archive not encoded | Not encoded (unchanged) | Caller responsibility: each inspector call targets a fixed table, so a cursor never crosses tables in correct use |
| Result model | `items: List`, `prevCursor`, no limit | `data: Seq`, `previousCursor`, includes limit; returned as `F[Page]` with cursor errors raised in `F` | Expose `Page` or add only naming aliases |
| Limit | Any positive `Int` | `1..100000` | Adopt bound or change folio deliberately |
| Empty ordering | pgmq4s API defaults to ID descending | Folio keyset default is ID ascending | Always pass explicit ID descending |
| `last_read_at DESC` nulls | Postgres default puts nulls first | Folio canonical order puts absent values last | Intentional visible ordering change |
| Non-ID tie order | ID follows the primary direction | Appended unique field defaults to ascending | Accept, or explicitly include ID with the old direction |
| Present/absent boundary | Nullable tuple/special-case predicates disagree with PostgreSQL ordering and can skip or repeat rows | Predicate and ordering share one explicit absent-placement rule | Correctness fix; add regression coverage |
| SQL shape | Direct select, tuple comparisons | Opaque subquery plus expanded lexicographic predicate | Verify plans/index use with representative `EXPLAIN` |

The tiebreaker choice deserves an explicit product decision. For a request such
as `EnqueuedAt.descending`, pgmq4s currently orders ties by `msg_id DESC`; folio
orders them by its appended default `msg_id ASC`. Both are total and valid. To
preserve the old order, construct the folio query with both
`EnqueuedAt.descending` and `Id.descending`. If pgmq4s continues to expose only
one order argument, accepting folio's ascending unique tiebreaker is the simpler
new contract.

## Folio developer-experience improvements exposed by this exercise

These are not all blockers, but they would make folio easier and safer to embed
in another library.

1. **Cross-platform modules (P0).** A backend library cannot adopt folio in a
   shared cross-project until matching artifacts exist. folio's build has no
   Scala.js/Native or sbt-typelevel plugins today, so this means adding those
   plugins plus `scala-java-time`, converting every module to
   `crossProject(...)` `CrossType.Pure`, moving `%%` to `%%%`, and adding JS/
   Native CI and publishing — not a one-line change.
2. ~~**`Page.map` (P1)**~~  DONE `Page[T].map(f: T => U): Page[U]` maps page data
   while preserving the limit and both cursors, so a database library can decode
   an internal row and expose another model without reconstructing the case class.
3. ~~**Surface the fetch limit unambiguously (P1)**~~ DONE `ResolvedQuery.limit`
   now holds the page size the caller requested, and a public
   `ResolvedQuery.fetchLimit` (page limit + 1, backed by public `Limit.fetchLimit`)
   is what drivers fetch. The old footgun — a field named `limit` that secretly
   held `limit + 1` — is gone; drivers read `resolved.fetchLimit.value`.
4. ~~**Typed Skunk composition errors (P1)**~~ DONE `buildSql` returns
   `Either[FolioError, AppliedFragment]`, and the high-level API raises a `Left`
   unchanged through `Concurrent[F].raiseError`. Composition failures therefore
   retain their typed `FolioError.InvalidQuery` structure.
5. ~~**Strict strategy selection**~~ (future improvement)When a `KeysetField`
   given is present but not every order field is registered via `.withField`,
   folio silently falls back to offset — a hidden performance cliff for a caller
   who believed they had opted into keyset. Deferred deliberately: providing the
   keyset givens is the caller's configuration, and this is cleanly additive
   later (a defaulted `Strategy`/`KeysetRequired` mode or a `FolioConfig`, plus a
   pure `resolvedStrategy` inspection API so callers can test that every field
   resolves to keyset). Revisit only if a real caller needs enforcement.
6. ~~**Explicit keyset configuration (P2)**~~ DONE `Page.withPagination` retains
   its inline contextual-discovery overload and now has a non-inline overload
   accepting `Option[KeysetField[FIELD, T]]`. `folio-skunk` resolves the option
   once and passes that same value to both page resolution and `buildSql`;
   external adapters can do the same without independently summoning metadata.
7. ~~**More ergonomic query construction (P2)**~~ DONE `Query.ordering` is
   `Vector[OrderBy[FIELD]]` (order-sensitive, at most one order per field at the
   boundaries). Defaults cover empty filters/orderings; `Query.orderBy` replaces the
   ordering. `Page.withPagination` and `Pagination.buildSql` reject duplicate
   order fields as `FolioError.InvalidQuery`.
8. ~~**Session-pool convenience (P2)**~~ DONE `Pagination.withPagination` now has
   a `Resource[F, Session[F]]` overload alongside the primitive `Session[F]` one.
   It acquires a session for the call and releases it afterwards, fitting a pooled
   checkout; the `Session[F]` overload stays the primitive for callers that already
   hold a session and own its lifecycle.
9. ~~**Align docs with the current constructor (small cleanup).**~~ DONE
    `CLAUDE.md`, `Position` scaladoc, and the quick example now use
    `KeysetField.uniqueBy` (matching the implementation and domain context).

The raw queue-table identifier issue above is a pgmq4s concern rather than a
folio API issue, but it should travel with the migration because the draft
touches both affected Skunk queries.

## Suggested implementation order

1. Cross-build and publish folio artifacts.
2. Add `Page.map`.
3. Add folio dependencies and metadata to pgmq4s.
4. Change the backend SPI to `ResolvedQuery` and switch Skunk to `buildSql`.
5. Change the public inspector API and core implementation to folio types.
6. Rewrite unit/integration tests, including null-boundary and duplicate-key
   cases.
7. Remove custom pagination files and release with an explicit cursor/API
   compatibility note.

The accompanying short code sketch is in
[`pgmq4s-implementation-draft.md`](pgmq4s-implementation-draft.md).
