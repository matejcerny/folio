# Code Review - folio service

## Findings

### 1. Medium: decoded cursor position is trusted without validating it against the selected strategy

`Cursor.decode` accepts any cursor type as long as the query fingerprint matches. `Page.paginate` then forwards the decoded `Position` directly to the fetcher/advance logic.

This means a malformed or manually constructed cursor can carry a `Position.Keyset` into an offset-only query. In `CursorAdvance.offsetOnly`, that currently becomes a silent no-op:

```scala
case keyset: Position.Keyset => keyset
```

The prior review called this branch unreachable. It is unreachable for cursors produced only through the happy-path API, but it is reachable at the public cursor boundary.

Suggested fix:

- After decoding, validate that the cursor position type matches the strategy selected for the query.
- Return a `CursorDecodingError` for strategy mismatch.
- Consider replacing the silent `offsetOnly` keyset branch with a loud internal error once public validation exists.

Relevant files:

- `core/src/main/scala/folio/Cursor.scala`
- `core/src/main/scala/folio/Page.scala`
- `core/src/main/scala/folio/CursorAdvance.scala`

### 2. Medium: cursor fingerprints are weak and non-canonical

The cursor query fingerprint is:

```scala
MurmurHash3.stringHash(s"$limit;$sort;$filter").toString
```

There are two problems:

- `MurmurHash3.stringHash` is a 32-bit non-cryptographic hash, so intentional tampering/collisions are plausible.
- The serialized query string uses unescaped separators (`;`, `,`, `:`) while filter values are arbitrary strings. Distinct queries can serialize to the same string before hashing.

Base64url encoding hides the cursor payload from casual inspection, but it is not integrity protection.

Suggested fix:

- Serialize the fingerprint input with a canonical structured format, or length-prefix each component.
- Use an HMAC over the canonical payload if cursors should be tamper-resistant.
- At minimum, use a wider stable hash and avoid separator ambiguity.

Relevant files:

- `core/src/main/scala/folio/Cursor.scala`
- `core/src/main/scala/folio/FilterBy.scala`

### 3. Medium: invalid pagination inputs are accepted by public constructors

`Limit.apply` accepts all `Int` values, including `0`, negatives, and `Int.MaxValue`.

Consequences:

- `Limit(0)` always returns empty pages but can still report `hasMore`.
- Negative limits make `take(limit.value)` return empty data and can produce strange cursor arithmetic.
- `Limit(Int.MaxValue).fetchLimit` overflows to a negative value.

Decoded offsets also accept negative `Long` values, so a cursor can carry `Position.Offset(-1)`.

Suggested fix:

- Make `Limit.fromInt` return `Either`/`Option` for public input, or make `Limit.apply` private and expose validated constructors.
- Enforce `limit > 0` and a sane maximum.
- Reject negative decoded offsets and keyset ids at the cursor boundary if ids are expected to be non-negative.

Relevant files:

- `core/src/main/scala/folio/Limit.scala`
- `core/src/main/scala/folio/Cursor.scala`
- `core/src/main/scala/folio/CursorAdvance.scala`

## Coverage Gaps

Add tests that exercise behavior through realistic `fetchRows` implementations, not only pre-shaped row sequences:

- Offset forward/backward against an in-memory ordered collection.
- Keyset forward/backward against an in-memory ordered collection.
- No explicit sort with `IdField` in scope should produce default id ordering in `ResolvedQuery`.
- Tampered cursor type should fail when it does not match the selected query strategy.
- Invalid limits and negative offsets should be rejected.

## Comparison With Prior Notes

The previous document correctly identified some local cleanup items around `CursorAdvance`, especially the surprising `offsetOnly` keyset branch and the `RowId[T]` requirement when `IdField[FIELD]` is in scope.

Several prior notes are stale against the current workspace:

- It says the `IdField`-absent path is missing coverage; current `PagePaginationSuite` has offset-only coverage.
- It mentions commented-out `next`/`previous` blocks that are not present in the current files.

The main missing issues in the prior notes were:

- Backward offset pagination is likely wrong with a real offset fetcher.
- Cursor fingerprints are weak/non-canonical.
- `Limit` and decoded offsets lack validation.

## Recommended Order

1. Add realistic pagination tests that consume `ResolvedQuery`.
2. Add cursor strategy validation.
3. Harden cursor fingerprinting and public input validation.
