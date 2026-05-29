# folio — Domain context

This document captures the ubiquitous language used throughout folio. It is
not API reference (see scaladoc for that) and not architecture (see ADRs for
that). It records the *words* folio uses and what they mean.

folio's intended scope spans three layers:

- **HTTP modules** (e.g. tapir): parse sort/filter from query params.
- **Core**: cursor encoding, position resolution, sort/filter modeling —
  driver-agnostic.
- **Driver modules** (Skunk, Doobie, future Mongo, …): translate a
  `ResolvedQuery` into a backend-native query.

Terms below live in the **core** unless otherwise noted.

> **Status:** folio is in active pre-1.0 development. Public API, cursor
> wire format, and stale-cursor detection scope can change without a
> deprecation cycle. Design choices in this document and the ADRs do not
> account for compat costs.

---

## Glossary

### Absent

The cursor-anchor slot for a row whose sort-column value was missing.

Driver modules translate `Absent` into their backend's idiom:

- SQL drivers: `IS NULL`.
- (Future) document-store drivers: e.g. `$exists: false`.

`Absent` is folio's neutral name for the concept. It is deliberately *not*
called "Null" — that word belongs only to the SQL driver layer.

A sort field is **absentable** when its row values may be missing. The
source of truth is the row class itself: if `T.field` is typed as
`Option[V]`, the field is absentable; otherwise it is not. `KeysetField`
mirrors that type — there is no separate `withAbsentableField` method —
because the row class is the source of truth for the schema and the
registration should not invent extra metadata.

The unique field (see [[Unique field]]) is never absentable.

### Unique field

The first field passed to `KeysetField.uniqueBy(...)`. It plays the role of
*tiebreaker* in the keyset algorithm: when all other sort fields produce
equal values, the unique field decides order. Its values must therefore be
distinct across the entire result set — that is the contract the
`uniqueBy` name advertises.

Often the row's `id` column, but folio does not require it to be called
"id"; any field with distinct values qualifies.

The unique field cannot be absentable: a missing tiebreaker value would
let two cursor anchors collide and pagination order would become
ambiguous. This is enforced structurally — `KeysetField.uniqueBy` accepts
only atomic codecs (no `Option` derivation), so an `Option`-typed unique
field will not compile.

### Absent ordering

`Absent` always sorts **after** non-`Absent` values, regardless of
`Order.Ascending` / `Order.Descending`. See `docs/adr/0001-absent-sorts-last.md`
for the reasoning.

---

## Conventions referenced by ADRs

- `KeysetField` registration uses `uniqueBy` for the tiebreaker and
  `withField` (overloaded for `T => V` / `T => Option[V]`) for the
  remaining fields. See `docs/adr/0002-type-driven-absentability.md`.
- The stale-cursor fingerprint includes per-field absentability so that
  flipping a field from required to absentable (or back) invalidates
  outstanding cursors uniformly.
- The cursor decoder rejects `Absent` slots that land on a non-absentable
  field with a dedicated `CursorDecodingError` variant
  (`AbsentInRequiredField`, name TBD at implementation time).

