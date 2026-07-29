# ADR 0009 — Typed exact-match filters are rendered as parameterized predicates

## Status

Accepted — 2026-07-30. Supersedes ADR 0006.

## Context

ADR 0006 decided that `folio-skunk` would not translate `Query.filters` into SQL:
the opaque inner `SELECT` (ADR 0004) was the caller's filtering escape hatch, and
filters existed in core only to feed the stale-cursor fingerprint. That left
`Query.filters` a half-modeled concept — changing a filter invalidated outstanding
cursors, yet folio never applied the predicate, so a page could legitimately
contain rows the filter excluded.

The layering in `CONTEXT.md` is what forces the issue: an HTTP module parses
*order and filter* from query params, hands them to core, and a driver module
turns them into a backend query. Under ADR 0006 the order half of that sentence
worked and the filter half did not. The caller's only remedy was to build the
filter into their own `SELECT` text, which means splicing request-derived values
into SQL — reintroducing exactly the injection surface ADR 0004 removed for
identifiers.

Rendering filters raises three questions the old model could avoid:

- **What is a filter value?** A `String`-only model would need `::text` casts on
  the column, which defeats index usage and gives the wrong comparison semantics
  for timestamps.
- **When are two filters the same filter?** `Query.filters` is a `Set`, so the set
  decides which duplicates survive, and whatever survives determines both the
  fingerprint and the bound SQL type.
- **In what order do filters render?** A `Set`'s iteration order is an
  implementation detail, but both the fingerprint and the prepared-statement
  template must be stable for the same filter set.

## Decision

Filters are a first-class, typed, rendered part of a query.

**Value model.** `FilterBy.ExactMatch[FIELD, V](field, value)(using
FieldValueCodec[V])` carries the caller's value and exposes its encoding as a
`FieldValue` (`IntV`, `LongV`, `StringV`, `TimestampV`). `FieldValue` is folio's
neutral currency for user values, shared with keyset anchor slots — anchors wrap
it in `AnchorValue` to add their `Absent` case, which filters do not have.
Built-in `FieldValueCodec` instances cover `Int`, `Long`, `String`, and
`OffsetDateTime`.

**Scope.** V1 models non-null exact equality only, and every filter in
`Query.filters` is ANDed. No disjunction, no ranges, no `IS NULL`, no other
operator, and no query DSL. The opaque inner `SELECT` remains the escape hatch
for everything folio does not model.

**Identity.** A filter is identified by `(field, encoded value)`, not by the raw
value: `ExactMatch` overrides `equals`/`hashCode` accordingly. `ExactMatch(Id, 1)`
and `ExactMatch(Id, 1L)` therefore remain two predicates even though `1 == 1L`
under boxed numeric equality. Case-class equality would keep whichever the set saw
first, making both the fingerprint and the bound column type depend on insertion
order.

**Canonical order.** `private[folio] CanonicalFilters` owns one total order —
field name, predicate tag, then unsigned lexicographic comparison of the encoded
value bytes — and both consumers use it: `CanonicalFilters.fingerprintPart` for
cursor hashing and driver modules for rendering. Comparison runs over encoded
bytes because that is what identifies a filter and it needs no cross-type value
ordering. `TimestampV` keeps the offset the caller supplied; folio does not
normalise to UTC, so filter identity agrees with `OffsetDateTime.equals`.

**Fingerprint.** Each entry encodes as a length-delimited field name, a predicate
tag, then the tagged field value, hex-rendered so it cannot collide with the
delimiters of the surrounding fingerprint string. An empty filter set contributes
the empty string, keeping unfiltered fingerprints byte-identical to a folio
without filtering.

**Rendering (Skunk).** Each filter renders as `usersql."<quoted field>" = $n`,
bound through the fixed codec for its `FieldValue` variant (`int4`, `int8`,
`text`, `timestamptz`) — the same mapping the keyset anchor binder uses. Values are
never interpolated and columns are never cast. One outer `WHERE` combines the
canonical filters with the optional keyset seek on both the keyset and the offset
branch; the seek is a disjunction, so it is parenthesized whenever filters are
ANDed onto it. Bound parameters appear in the order inner `SELECT`, filters,
keyset or offset, fetch limit. A query with no filters emits byte-identical SQL to
before this decision.

**Caller responsibility.** The `FIELD` enum carries no value type, so pairing a
field with a comparable value type — and projecting every filter column from the
inner `SELECT` — is the caller's job. Mismatches surface at the driver/database
boundary.

## Consequences

- Request-derived filters reach SQL as bound parameters. An HTTP module can map
  query params to `FilterBy` values without any caller building SQL text.
- **ADR 0004's projection contract tightens**, exactly as ADR 0006 predicted: the
  inner `SELECT` must now project filter columns as well as order/keyset columns,
  under their `FieldSchema` names. A `SELECT` that paginated fine while filters
  were ignored can now fail with an unknown-column error.
- Cursors minted for *filtered* queries before this change decode as
  `StaleCursor`, because the filter part of the fingerprint changed. Unfiltered
  cursors are unaffected. Acceptable pre-1.0 (see `CONTEXT.md` status).
- A field/value type mismatch is a PostgreSQL error at execution time, not a
  compile error. Making it a compile error would require per-field value types in
  `FIELD`, which the enum-based schema deliberately does not carry.
- Because `Set` is invariant, a filter set built outside an expected-type position
  needs an explicit `Set[FilterBy[FIELD]](...)` ascription.
- Adding a second predicate later is mechanical but not free: the renderer matches
  on the `FilterBy` case (so a new case is an exhaustivity error rather than a
  silent `=`), and the new case needs its own predicate tag, which changes
  fingerprints only for queries that use it.
- Filters need no `KeysetField` registration — registration is about *ordering*.
  Filtering on an unregistered field keeps keyset pagination; ordering by one is
  what falls back to offset.

## Alternatives rejected

- **Keep ADR 0006 (filter only inside the user's `SELECT`).** Rejected: it forces
  callers to splice request values into SQL text, and it leaves `Query.filters`
  affecting staleness while affecting nothing else — a model that can only mislead.
- **Untyped `String` filter values with `::text` casts.** Rejected: casting the
  column defeats index usage and compares timestamps and numbers lexically.
- **A filter DSL with operators, ranges, and disjunction.** Rejected for V1:
  positioning needs none of it, and each operator adds a predicate tag, a
  fingerprint change, and a renderer branch. Exact equality is the case that
  cannot be pushed into the inner `SELECT` when values come from a request.
- **Case-class equality for `ExactMatch`.** Rejected: `1` and `1L` compare equal
  boxed, so the surviving member of the `Set` — and with it the fingerprint and the
  bound type — would depend on insertion order.
- **An ordered collection (`Vector`/`List`) for `Query.filters`.** Rejected: a
  conjunction has no meaningful order, and an ordered type would invite callers to
  read meaning into it. Canonicalising at the two points that need determinism
  keeps the model honest.
- **Normalising `TimestampV` to UTC or epoch millis.** Rejected: it would split
  filter identity from `OffsetDateTime.equals`, so two filters that are `!=` in
  Scala would collapse in the `Set`. Determinism, not instant-equivalence, is what
  the canonical order owes its consumers.
- **Rendering filters into the inner `SELECT`.** Rejected: folio would have to
  parse and rewrite the user's SQL, which ADR 0004 exists to avoid.
