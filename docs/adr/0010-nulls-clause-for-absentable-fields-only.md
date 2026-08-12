# ADR 0010 — The `NULLS` clause is emitted only for known-absentable fields

## Status

Accepted — 2026-08-03. Refines ADR 0001.

## Context

ADR 0001 fixed `Absent` to sort after present values in both directions and told
drivers to emit an explicit null placement rather than trust dialect defaults.
`folio-skunk` applied that to **every** field the user named in `Query.ordering`:

| order        | direction | emitted before this ADR |
|--------------|-----------|-------------------------|
| `Ascending`  | forward   | `ASC NULLS LAST`        |
| `Descending` | forward   | `DESC NULLS LAST`       |
| `Ascending`  | backward  | `DESC NULLS FIRST`      |
| `Descending` | backward  | `ASC NULLS FIRST`       |

Only an absentable field needs that clause: it is what makes `ORDER BY` agree with
the keyset predicate's NULL handling (ADRs 0001 and 0003). For a registered
*required* field the clause carries no information and costs index compatibility.

PostgreSQL states the constraint in
[Indexes and `ORDER BY`](https://www.postgresql.org/docs/18/indexes-ordering.html):
a default B-tree index stores entries ascending with nulls last, so a forward scan
satisfies `ORDER BY x ASC NULLS LAST` and a backward scan satisfies
`ORDER BY x DESC NULLS FIRST` — those two and nothing else. The planner compares
the requested placement **exactly**; a `NOT NULL` constraint does not let it treat
`DESC NULLS LAST` as equivalent to `DESC`. So folio's
`ORDER BY "id" DESC NULLS LAST LIMIT 11` turned a bounded backward primary-key
scan into a full scan plus a top-N sort. That is the regression blocking the
pgmq4s migration (`plans/pgmq4s-migration.md`), whose default page is
`msg_id DESC`.

Dropping the clause changes no result folio can decode for a registered required
field: `strictStep` already treats such a field as NULL-free — a bare `>` / `<`
with no NULL branch — and a NULL row value would fail the required row decoder
before pagination saw it. `ORDER BY` then rests on exactly the assumption the
predicate already makes.

The argument does **not** extend to a field folio has no registration for. Its
absentability is unknown, its decoder may well accept NULL, and it is reachable:
ordering by an unregistered field is precisely what makes `Position.fromQueryKeyset`
fall back to offset (ADR 0007), and an offset query may also arrive with no
`KeysetField` at all.

## Decision

**A driver emits the `NULLS` clause exactly when the field is in
`KeysetField.absentableFields`.**

| order        | direction | not known absentable | known absentable   |
|--------------|-----------|----------------------|--------------------|
| `Ascending`  | forward   | `ASC`                | `ASC NULLS LAST`   |
| `Descending` | forward   | `DESC`               | `DESC NULLS LAST`  |
| `Ascending`  | backward  | `DESC`               | `DESC NULLS FIRST` |
| `Descending` | backward  | `ASC`                | `ASC NULLS FIRST`  |

The same set drives the keyset seek predicate's NULL handling, so `ORDER BY` and
the predicate cannot drift apart. "Not known absentable" covers three metadata
cases:

- **Registered required** (`withField(field, _: T => V)`, and the `uniqueBy`
  unique field) — known NULL-free, bare keyword.
- **Unregistered under `Some(keysetField)`** — unknown. This is the
  keyset-to-offset fallback path; the field renders bare and PostgreSQL applies its
  default placement.
- **Every field under `keyset = None`** — unknown for the same reason: folio holds
  no absentability metadata at all.

Two supporting rules make the set trustworthy:

- **The unique field cannot be re-registered.** Both `withField` overloads pass
  through one registration guard that rejects the `uniqueBy` field with
  `IllegalArgumentException`. Without it, the absentable overload could move the
  tiebreaker into `absentableFields` and swap the extractor `CursorAdvance` encodes
  anchors with. The check is runtime because the field value is only known at
  runtime; `uniqueBy`'s refusal of `Option` extractors stays structural (ADR 0002).
- **`Pagination.buildSql` rejects an unregistered keyset cursor field.** On a
  `Position.Keyset` it returns
  `FolioError.InvalidQuery("Position.Keyset has unregistered cursor field(s): …")`,
  listing names in cursor order, before rendering anything (ADR 0005). Normal
  resolution cannot produce such a query, but the public escape hatch can. An
  offset position deliberately skips the check: an unregistered order field is what
  selected that branch.

The rejected alternative — keeping `NULLS LAST` when absentability is unknown — is
recorded below. This is pre-1.0 (`CONTEXT.md` status), so the SQL-shape and
unknown-field ordering change ships without a deprecation path.

## Consequences

- An otherwise compatible default B-tree index is no longer rejected on null
  placement alone. `ORDER BY "id" DESC LIMIT n` uses a backward primary-key scan
  again. This claim is about null placement only — it promises nothing about
  mixed-direction composites, collations, or expression indexes.
- Because the unique field's registration is now enforced, naming the required
  unique field in its canonical direction and letting folio append it render
  **identically**. The `appended` rendering dimension in the driver disappears
  (superseding that implementation detail of ADR 0007), and the pgmq4s trap of
  "naming `msg_id` moves it into the `NULLS`-emitting branch" is gone.
- ADR 0001's canonical core policy is untouched: `Absent` still sorts after
  present values in both directions, and core's in-memory model still implements
  it. What narrows is the *SQL-level* guarantee — an explicit clause is emitted for
  registered absentable fields only.
- A nullable field folio does not know about now uses PostgreSQL's default
  placement, so ordering it `DESC` on the offset branch shows NULLs first instead of
  last. The placement stays deterministic, which is not the same as stable
  pagination: `keyset = None` may lack a total order anyway, concurrent writes move
  absolute offsets, and an outstanding offset cursor can straddle the deployment
  that reorders NULLs. Registering the field (`withField(field, _.value)`) restores
  folio's absent-last ordering and upgrades the query to keyset.
- No cursor wire format, fingerprint, or `Position` shape changes. Keyset cursors
  over registered fields keep their result semantics; offset cursors stay decodable
  but their absolute position may address different rows after deployment.
- `Order.flip` gains a runtime call site: backward traversal is now expressed as
  "flip the order, then place nulls by direction" instead of an eight-case match.

## Alternatives rejected

- **Keep the clause on every named field** (the pre-0010 behavior). Rejected: it
  makes folio's `ORDER BY` unmatchable by any default index for the most common
  page shape, `ORDER BY col DESC LIMIT n`, and the clause it adds is redundant for
  a field the predicate already treats as NULL-free.
- **Keep `NULLS LAST` when absentability is unknown.** Rejected: it preserves the
  index regression for every offset-only user, and turns the rule into "emitted iff
  absentable-or-unknown" rather than using the driver's positive absentability
  metadata directly. Nothing in an offset query depends on the placement — there is
  no seek predicate to agree with.
- **Per-field `nullsPlacement` metadata on `withField`.** Rejected here for the
  same reason ADR 0001 rejected it: no consumer asks for it, and absentability
  already answers the question folio actually needs answered. It stays available
  later as opt-in metadata.
- **Render a `CASE WHEN col IS NULL THEN 1 ELSE 0 END` sort key** to express
  absent-last without a `NULLS` clause. Rejected: an expression key needs a
  matching expression index to be sargable, so it trades one index mismatch for a
  worse one, and it obscures a simple `ORDER BY`.
- **A driver-level dialect switch** (`nullsPlacement = Explicit | DialectDefault`).
  Rejected: it makes SQL shape a deployment setting, doubles the test matrix, and
  the per-field absentability answer is strictly better information than a global
  flag.
- **Subtract the unique field in the Skunk driver only,** leaving `withField` free
  to overwrite it. Rejected: `CursorAdvance` would still encode anchors with the
  overwritten extractor, so cursor encoding and SQL rendering would disagree —
  the failure mode would be silent mis-pagination, not a bad plan.
