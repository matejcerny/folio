# ADR 0001 — Absent values sort last, regardless of direction

## Status

Accepted — 2026-05-30.

## Context

folio is adding first-class support for sort fields whose row values can
be missing (see [[Absent]] in `CONTEXT.md`). Once a sort field can carry
`Absent`, the ordering between an `Absent` slot and a non-`Absent` slot
must be defined somewhere — and consistently across folio core's
cursor-advance logic *and* every driver module that emits a backend
query. If those disagree, pagination skips or duplicates rows.

Three policy shapes were considered:

1. **Fixed: `Absent` sorts last regardless of direction.** Asc:
   `[non-absent asc] then [absent in tiebreaker order]`. Desc:
   `[non-absent desc] then [absent in tiebreaker order]`.
2. **Direction-driven (Postgres defaults):** `Order.Ascending` →
   nulls-last, `Order.Descending` → nulls-first.
3. **Per-field configurable** at registration time, e.g.
   `.withField(LastReadAt, _.lastReadAt, AbsentOrder.NullsLast)`.

## Decision

Adopt option 1: **`Absent` always sorts after non-`Absent`**, regardless
of `Order` direction.

Driver modules emit explicit ordering clauses (`NULLS LAST` / equivalent
`CASE WHEN`) rather than relying on dialect defaults — so a Postgres
driver emits `ORDER BY x DESC NULLS LAST` even though Postgres' default
for `DESC` would be nulls-first.

## Consequences

- Folio core has a single canonical position for `Absent` in the
  cursor-advance algorithm. Drivers cannot disagree with core without
  observably-broken pagination.
- The behavior matches the most common product spec ("show the rows
  that have a value, then the missing ones at the end") without forcing
  the user to think about `Order` direction.
- Postgres users sorting `DESC` get explicit `NULLS LAST`, which differs
  from the dialect default (nulls-first for `DESC`). Acceptable: the
  dialect default is rarely what an HTTP-pagination user wants anyway,
  and the SQL is one extra clause.
- A future requirement for per-field control (option 3) remains
  reversible: it can be introduced as opt-in metadata on `withField`,
  defaulting to nulls-last, without breaking existing call sites.
- A future Mongo or KV driver has no analogous "dialect default" to
  imitate; the canonical core policy gives them one decisive instruction.

## Alternatives rejected

- **Direction-driven (Postgres defaults).** Rejected: requires every
  driver to carry the same dialect-flavored rule, and the rule is
  surprising in non-SQL backends. Familiarity for Postgres users is
  not worth the cross-driver cost.
- **Per-field configurable.** Rejected for now: pays for flexibility
  no current consumer asks for. Reversible — can be added later as
  opt-in metadata.
