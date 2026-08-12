# Architecture decision records

Each ADR records one decision, its rationale, and the alternatives rejected. They are append-only:
supersede rather than rewrite. Domain vocabulary lives in `CONTEXT.md`.

| # | Decision | Status |
|---|---|---|
| [0001](0001-absent-sorts-last.md) | `Absent` sorts after present values in **both** directions; drivers emit explicit `NULLS LAST` rather than trusting dialect defaults | Accepted — *SQL placement refined by [0010](0010-nulls-clause-for-absentable-fields-only.md)* |
| [0002](0002-type-driven-absentability.md) | Absentability comes from the row class's `Option[V]` typing, not separate metadata; one overloaded `withField`, structurally non-absentable unique field | Accepted |
| [0003](0003-backward-keyset-driver-contract.md) | `ResolvedQuery.ordering` is always canonical/forward; a backward keyset seek is the driver's job — reverse order **and** `Absent` placement. Direction is a no-op for offset | Accepted |
| [0004](0004-skunk-sql-composition.md) | folio-skunk wraps the user's `SELECT` as an opaque subquery; values bound as parameters, identifiers quoted in the driver (never core) | Accepted |
| [0005](0005-buildsql-escape-hatch.md) | `buildSql` takes `KeysetField` explicitly (wildcard summoning is ambiguous) and returns `Either` instead of malformed SQL | Accepted |
| [0006](0006-filters-not-rendered.md) | ~~folio-skunk ignores `Query.filters`; the inner `SELECT` is the filtering escape hatch~~ | **Superseded by 0009** |
| [0007](0007-offset-total-ordering.md) | Offset rendering appends the unique field as a tiebreaker when a `KeysetField` is available; without one, total ordering is the caller's responsibility | Accepted |
| [0008](0008-minimal-effect-boundary.md) | Core's effect boundary is two operations (`map`, `raiseError`), not Cats `Applicative`; errors raised natively, not `F[Either[...]]` | Accepted |
| [0009](0009-typed-exact-match-filters.md) | Filters are typed, ANDed exact-match predicates rendered as bound parameters; identity is `(field, encoded value)`; one canonical order serves fingerprint and renderer | Accepted |
| [0010](0010-nulls-clause-for-absentable-fields-only.md) | The `NULLS` clause is emitted only for fields registered as absentable; every other field renders a bare `ASC` / `DESC`. The unique field cannot be re-registered, and `buildSql` rejects unregistered keyset cursor fields | Accepted |
