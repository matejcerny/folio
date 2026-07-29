# folio — Domain context

The ubiquitous language of folio: the *words* and what they mean. Not API reference (scaladoc) and
not architecture (`docs/adr/README.md`).

Scope spans three layers; terms below belong to **core** unless noted:

- **HTTP modules** (e.g. tapir): parse order/filter from query params
- **Core**: cursor encoding, position resolution, order/filter modeling — driver-agnostic
- **Driver modules** (Skunk, Doobie, future Mongo, …): translate a `ResolvedQuery` into a
  backend-native query

> **Status:** pre-1.0. Public API, cursor wire format, and stale-cursor detection scope can change
> without deprecation. Design choices here do not account for compat costs.

---

## Glossary

### FieldValue

A concrete value folio carries across its own boundaries — `IntV`, `LongV`, `StringV`, `TimestampV`.
Shared currency for keyset anchor slots and filter values. It has no missing-value case; a
`FieldValueCodec[V]` maps a user type onto a variant and back, with `fromFieldValue: Option[V]` so
each caller decides what a variant mismatch means.

### AnchorValue

One slot of a keyset cursor anchor: `Present(FieldValue)` or `Absent`. Kept off `FieldValue` so that
contexts where a value can never be missing — filters — don't inherit a meaningless variant.

### AnchorValue.Absent

The anchor slot for a row whose order-column value was missing. Drivers translate it into their
backend idiom (SQL: `IS NULL`; document stores: `$exists: false`). The name is deliberately not
"Null" — that word belongs to the SQL driver layer.

An order field is **absentable** when its row values may be missing. The row class is the source of
truth: `Option[V]` means absentable. `KeysetField` mirrors that type (no separate
`withAbsentableField`) rather than inventing extra metadata — see ADR 0002.

### Unique field

The first field passed to `KeysetField.uniqueBy(...)`; the *tiebreaker* when all other order fields
compare equal. Its values must be distinct across the whole result set — that is the contract
`uniqueBy` advertises. Often `id`, but any distinct field qualifies.

It can never be absentable: a missing tiebreaker would let two anchors collide. Enforced
structurally — `uniqueBy` accepts only atomic codecs, so an `Option`-typed unique field won't compile.

### Absent ordering

`Absent` always sorts **after** non-`Absent` values, regardless of `Order.Ascending` /
`Order.Descending` (ADR 0001).

### Filter

A predicate applied **before** pagination, so positioning happens inside the filtered set.
`Query.filters` is a `Set[FilterBy[FIELD]]`, all members ANDed; the only operator today is exact
equality (`FilterBy.ExactMatch`) — no disjunction.

Values are typed: `ExactMatch(field, value)` needs a `FieldValueCodec[V]` and carries the resulting
`FieldValue` as `encodedValue`. Supported types are those folio ships codecs for — `Int`, `Long`,
`String`, `OffsetDateTime` — bound through native driver codecs (folio-skunk: `int4`, `int8`, `text`,
`timestamptz`), never as cast-to-text. Pairing a field with an incomparable value type surfaces at
the driver/database boundary, not in core.

Filtering needs no `KeysetField` registration — registration is about *ordering*. A filter on an
unregistered field keeps keyset; *ordering* by an unregistered field falls back to offset.

**Who applies a filter.** Core never touches rows; it carries `filters` into the `ResolvedQuery`
handed to `fetchRows`. folio-skunk renders one conjunction of parameterized equality predicates in
the outer `WHERE`, before positioning, on both branches (ADR 0009); the inner `SELECT` must project
every filter column under its `FieldSchema` name. A hand-written `fetchRows` applies them itself.

**Identity is `(field, encoded value)`** — not the raw value. Two filters collapse exactly when they
would render the same predicate, keeping `ExactMatch(Id, 1)` (`IntV`) distinct from
`ExactMatch(Id, 1L)` (`LongV`). Filters participate in the stale-cursor fingerprint: changing a
filter's field, value, or value type invalidates outstanding cursors. Unfiltered fingerprints were
unaffected by filter rendering; cursors minted for *filtered* queries beforehand decode as
`StaleCursor`.

`Set` is invariant, so a filter set built outside an expected-type position needs an explicit
`Set[FilterBy[FIELD]](...)` ascription.

### Canonical filter order

The one total order over a query's filters, shared by cursor fingerprinting and predicate rendering:
**field name, then predicate tag, then unsigned lexicographic comparison of the encoded value
bytes**. `Query.filters` iteration order is an implementation detail — a fingerprint following it
would declare cursors stale at random, and drifting predicate order would defeat statement caching
and make SQL-shape assertions untestable.

Comparison runs over *encoded* bytes: they identify the filter and compare across value types
without inventing a cross-type value ordering. String and timestamp payloads are length-delimited,
so the order differs from the natural order of the underlying values. Determinism is the contract;
the particular sequence is not.

`TimestampV` keeps the caller's offset — no normalisation to UTC or epoch. That matches
`OffsetDateTime.equals` and therefore filter identity: the same instant at a different offset is a
different filter, binds a different value, and invalidates outstanding cursors.

The fingerprint encodes each filter as a length-delimited field name, a predicate tag, then the
tagged field value, so no name or string value can imitate an entry boundary. An empty filter set
contributes nothing.

---

## Conventions referenced by ADRs

- `KeysetField` registration: `uniqueBy` for the tiebreaker, `withField` (overloaded `T => V` /
  `T => Option[V]`) for the rest (ADR 0002)
- The stale-cursor fingerprint includes per-field absentability, so flipping a field between
  required and absentable invalidates outstanding cursors
- The decoder rejects `Absent` slots landing on a non-absentable field with a dedicated
  `CursorDecodingError` variant (`AbsentInRequiredField`)
