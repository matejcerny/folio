# ADR 0004 — Skunk driver composes SQL by opaque subquery wrapping

## Status

Accepted — 2026-06-12.

## Context

The Skunk driver must turn a `ResolvedQuery[FIELD]` plus the caller's own
`SELECT` into a single parameterized statement that applies the keyset (or
offset) position, the direction-aware `ORDER BY`, and the fetch limit.

Two things have to be decided: how folio's clauses combine with the user's
`SELECT`, and how the bound values (keyset anchor values, offset, limit) reach
Postgres.

The user's `SELECT` is arbitrary — joins, CTEs, function calls, parameters of
its own. folio cannot assume any particular shape, and must not reorder or
reinterpret the user's filtering.

Identifier handling is a related concern: the keyset predicate and `ORDER BY`
reference sort/keyset columns by name, and those names come from
`FieldSchema`. A column name could be a reserved word, mixed case, or contain a
double quote.

## Decision

Compose by **opaque subquery wrapping**. The user's `SELECT` is treated as a
black box and wrapped:

```
SELECT * FROM ( <select> ) AS usersql
 WHERE <keyset predicate>
 ORDER BY <sortBys, direction-aware>
 LIMIT <limit>
```

Keyset anchor values, the offset, and the limit are bound as Skunk parameters
and combined via `AppliedFragment.|+|` — **never** baked into the SQL text as
literals. The template text stays byte-identical across cursor advances; only
the bound arguments change, so Postgres' prepared-statement cache stays warm.

Column identifiers are emitted as `usersql."<name>"`, with the identifier
double-quoted and any embedded double quote doubled. This quoting is total
(not regex- or allowlist-dependent), so it is injection-safe by construction
and transparent for ordinary snake_case names while remaining correct for
reserved words and mixed case.

Identifier quoting lives in the **driver, not core**. `FieldSchema` names are
also fed into the cursor fingerprint and will feed future non-SQL backends, so
a SQL-identifier quoting rule must not leak into core.

## Consequences

- The user keeps full control of their `SELECT`; folio never parses or rewrites
  it. Any valid query the caller can write, folio can paginate, as long as the
  sort/keyset columns are projected by the inner `SELECT` (see ADR 0006).
- The prepared-statement template is stable across pages, so the statement
  cache is reused rather than thrashed.
- Parameters from a parameterized user `SELECT` precede folio's, and Skunk
  renumbers placeholders across the composed fragment automatically.
- Reserved words, mixed case, and embedded-quote column names work without a
  special-case list.
- Core stays backend-agnostic: a future Mongo or KV driver supplies its own
  identifier rules without unwinding a SQL-specific one baked into core.

## Alternatives rejected

- **Parse / rewrite the user's SQL** to splice clauses into it directly.
  Rejected: requires a SQL parser, breaks on dialect features folio does not
  model, and turns every user query into something folio has to understand.
  The subquery wrap needs to understand nothing about the inner `SELECT`.
- **Literal-bake values into the SQL text.** Rejected: every cursor advance
  produces different SQL text, defeating the prepared-statement cache, and
  reintroduces an injection surface the parameter binding otherwise removes.
- **Validate / quote identifiers in `FieldSchema.fromMapping` (in core).**
  Rejected: SQL-identifier quoting is a driver concern. The same `FieldSchema`
  name feeds the cursor fingerprint and non-SQL backends; baking a Postgres
  quoting rule into core would leak a driver detail into shared types.
