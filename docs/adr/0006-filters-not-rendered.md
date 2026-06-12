# ADR 0006 — folio-skunk does not render `Query.filters` into SQL

## Status

Accepted — 2026-06-12.

## Context

A `Query[FIELD]` carries `filters` alongside its cursor, limit, and sort. A SQL
driver could translate those filters into `WHERE` predicates. The Skunk driver,
however, already wraps an opaque user-supplied `SELECT` (see ADR 0004), and that
`SELECT` is the natural place for the caller to express filtering — with full
access to joins, functions, and dialect features folio does not model.

Filters still matter to core: they are part of the cursor fingerprint used for
stale-cursor detection, so a query whose filters changed invalidates an old
cursor.

## Decision

folio-skunk does **not** translate `Query.filters` into SQL. `resolved.filters`
is ignored by `buildSql`; the opaque inner `SELECT` is the caller's filtering
escape hatch. Filters continue to feed the cursor fingerprint in core.

## Consequences

- The caller filters by writing their `SELECT`; folio adds only positioning,
  ordering, and the limit on top of it.
- Stale-cursor detection still reacts to filter changes, because the fingerprint
  is computed in core from the full `Query`, independent of what the driver
  renders.
- **Forward-compat trap:** the ADR 0004 contract that sort/keyset columns must
  be projected by the inner `SELECT` will *tighten* once filter rendering lands.
  A `SELECT` that is valid today could begin to error when folio starts emitting
  filter predicates against columns the projection does not expose. This is
  acceptable while folio is pre-1.0 (no wire-format or API compatibility
  guarantee yet, per `CONTEXT.md` status); it is recorded here so the change is
  not a surprise.

## Alternatives rejected

- **Render `filters` into `WHERE` clauses now.** Rejected: duplicates
  expressiveness the opaque `SELECT` already provides, and would require folio
  to model a filter-to-SQL translation (operators, types, dialects) far beyond
  what positioning needs. Deferred until there is a concrete consumer that
  cannot express its filtering in the `SELECT`.
