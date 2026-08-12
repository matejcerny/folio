# ADR 0007 — Offset pagination appends the id tiebreaker for a total order

## Status

Accepted — 2026-07-14. The appended-tiebreaker decision stands; the `appended`
flag described under *Decision* is gone, replaced by the absentability-driven
renderer of
[ADR 0010](0010-nulls-clause-for-absentable-fields-only.md) — which renders the
appended unique field identically.

## Context

Postgres `OFFSET` paging is only stable over a **total** order. If the
`ORDER BY` leaves rows tied, the database is free to return tied rows in a
different relative order on each execution, so paging with a fixed offset can
**skip or duplicate** rows across pages.

The Skunk driver's `renderOffset` originally built `ORDER BY` from the user's
order fields alone — or emitted **no** `ORDER BY` when there were none — leaving
every non-total order exposed to this hazard.

The keyset branch already avoids it: `renderKeyset` uses
`CursorAdvance.cursorFieldsFor(ordering, idField)` to append the unique id field
as a tiebreaker, so the keyset `ORDER BY` is always total.

Offset-with-a-`KeysetField` is a real, reachable path.
`Position.fromQueryKeyset` falls back to `Offset` whenever any order field is not
registered for keyset, and in that case the unique id field is still available —
it was simply unused for ordering. `buildSql` already receives the
`KeysetField`; the offset branch just dropped it.

folio cannot detect whether an arbitrary user column is unique, so it has no way
to know whether a `None` (no `KeysetField`) offset query already has a total
order. It can only append a tiebreaker when it has a **designated** unique
field — the id carried by a `KeysetField`.

## Decision

`renderOffset` reuses the same cursor-field logic as the keyset branch:

- `Some(keysetField)` → order by
  `CursorAdvance.cursorFieldsFor(resolved.ordering, keysetField.field)`, i.e. the
  order fields with the unique id appended as a tiebreaker when it is not already
  an order field. This yields a total order and stable offset paging.
- `None` → order by the order fields alone (unchanged). folio has no designated
  unique field to append, so the **caller owns total ordering**: they must
  ensure the `select` plus its order fields already yield a total order, or
  `OFFSET` paging may skip/duplicate rows.

The appended id is a column reference, not a bound value, so no encoder types
change. It is emitted with `orderBy`'s `appended` flag as plain `ASC` (forward)
with no `NULLS` clause — identical to the keyset forward orientation. Offset is
absolute, so direction stays `Direction.Forward`.

The empty → no-`ORDER BY` guard is retained; it now only triggers on `None` with
no order fields.

## Consequences

- Offset queries that carry a `KeysetField` (the keyset-to-offset fallback path)
  gain a total order automatically — the stability bug is closed for them.
- Offset queries with no `KeysetField` are unchanged in behaviour, but the
  caller-owns-total-ordering caveat is now documented (in the `buildSql`
  "Offset ordering" scaladoc and here) rather than silently unsafe.
- The two branches share `CursorAdvance.cursorFieldsFor`, so their ordering
  policy cannot drift apart.

## Alternatives rejected

- **Append a tiebreaker even without a `KeysetField`.** Rejected: folio has no
  designated unique field to append, and it cannot detect whether an arbitrary
  user order column is unique. There is nothing correct to append.
- **Reject `None` + offset queries that lack a total order.** Rejected:
  uniqueness of arbitrary user columns is not detectable from `FieldSchema`, so
  the condition cannot be enforced. Documenting the caller responsibility is the
  only honest option.
- **Leave the offset branch as-is and document the hazard only.** Rejected: the
  id tiebreaker is already available on the `KeysetField` path and costs nothing
  to append, so silently skipping/duplicating rows there is a fixable bug, not an
  inherent limitation.
