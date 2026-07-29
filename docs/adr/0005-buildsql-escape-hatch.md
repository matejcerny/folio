# ADR 0005 — `buildSql` takes `KeysetField` explicitly and returns `Either`

## Status

Accepted — 2026-06-12.

## Context

`Pagination.buildSql` is the low-level, effect-free SQL composition core,
exposed as an escape hatch for users who want their own error mapping,
observability hooks, or multi-statement transactions rather than the effectful
`withPagination` wiring.

Two API-shape questions arise:

1. **How does `buildSql` obtain the `KeysetField` metadata?** The keyset
   predicate needs the id-field designation that only `KeysetField[FIELD, T]`
   carries. The obvious move is to `summon` it implicitly.

2. **What does `buildSql` return when the inputs are inconsistent?** Two
   contract violations are possible:
   - a `Position.Keyset` resolved query paired with no `KeysetField` — there is
     no id metadata to render the tiebreaker;
   - a non-empty `Position.Keyset` anchor whose value count does not match the
     cursor-field count (order fields plus the appended id tiebreaker) — the
     predicate builder `zip`s fields against values, so a mismatch silently
     drops or ignores rungs.

An earlier design had `buildSql` return a bare `AppliedFragment` and take two
implicit-style arguments.

## Decision

`buildSql` takes the `KeysetField` **explicitly** as
`keyset: Option[KeysetField[FIELD, ?]]` rather than summoning it, and returns
**`Either[FolioError, AppliedFragment]`**.

Explicit `KeysetField`: when several row models share one `FIELD` enum, a
wildcard `summon[KeysetField[FIELD, ?]]` is ambiguous — there can be more than
one instance in scope. Passing it explicitly removes the ambiguity and matches
the explicit `Page.withPagination` overload, so adapters can resolve one option
and pass it to both page resolution and SQL rendering. Pass `Some(keysetField)`
to render the keyset predicate; pass `None` only for an offset-positioned query.

`Either` return: both contract violations above return a typed
`Left(FolioError.InvalidQuery(...))` rather than emitting truncated or malformed
SQL. Failing loudly at composition time beats shipping SQL that silently drops
the id tiebreaker (or an empty `ORDER BY`) to the database. The effectful
`withPagination` entry point raises the same error unchanged through its native
error channel.

## Consequences

- The escape hatch stays usable in codebases where one `FIELD` enum backs
  several row models.
- `None` + `Position.Keyset`, and any anchor-arity mismatch, surface as a
  typed `FolioError.InvalidQuery` the caller can map to their own error type —
  never as wrong SQL.
- An empty anchor (`Position.Keyset(Nil)`, the first-page request) is valid and
  renders without a `WHERE` clause.
- This deviates from the earlier bare-`AppliedFragment`, two-argument shape;
  the `withPagination` wiring adopts this signature.

## Alternatives rejected

- **Summon `KeysetField` implicitly.** Rejected: ambiguous when multiple row
  models share a `FIELD` enum. The explicit argument is unambiguous and
  consistent with `Page.withPagination`.
- **Return a bare `AppliedFragment`** and treat inconsistent inputs as
  best-effort. Rejected: a `None`/`Keyset` mix or an arity mismatch would
  emit truncated SQL that fails — or worse, silently mis-paginates — at the
  database rather than at composition time.
