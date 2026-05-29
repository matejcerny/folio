# ADR 0003 — Backward keyset traversal: drivers reverse both sort order and Absent placement

## Status

Accepted — 2026-05-30.

## Context

ADR 0001 fixes Absent values to sort **last** in both directions. Drivers
emit explicit `NULLS LAST` (or equivalent) regardless of `Order` direction.

Backward keyset traversal needs to walk the canonical forward sequence in
reverse. An earlier implementation tried to do this entirely in core by
flipping each `SortBy.order` (`Asc ↔ Desc`) before handing the query to the
driver. Under ADR 0001 this is wrong: flipping orders alone reverses the
non-Absent block but leaves the Absent block at the **same end**, so a
backward seek across the Some/Absent boundary skips rows.

Concretely, with rows whose `lastSeen` is `Some` for ids 0..4 and `None` for
ids 5..9, sort `[lastSeen.asc, id.asc]`, page size 2:

- Forward pages: `[0,1] [2,3] [4,5] [6,7] [8,9]`.
- Backward from `[6,7]` (anchor `(Absent, 6)`):
  - Flip-orders-only produces `[lastSeen.desc, id.desc]` with Absent still
    last. The reverse-sorted block is `[Some 4, …, Some 0, None 9, …, None
    5]`; the seek "after `(Absent, 6)`" hits `[None 5]` and stops, missing
    id 4.
  - Correct slice is `[4, 5]` — the canonical row immediately preceding
    page4.

The sibling project `pgmq4s` uses the same flip-direction trick and gets
away with it because its Skunk backend relies on Postgres' direction-driven
null defaults (`ASC`→`NULLS LAST`, `DESC`→`NULLS FIRST`). Flipping the sort
direction implicitly flips null placement too. ADR 0001 explicitly rejected
that policy for folio, so flip-only cannot work here.

## Decision

`ResolvedQuery` carries a `direction: Direction` field. `sortBys` always
describes the **canonical (forward) ordering** — exactly the orders the
user requested. Drivers performing a **keyset seek** translate
`Direction.Backward` themselves by **reversing both sort order and nulls
placement (Absent first)**.

For an **offset** `position` the cursor advance has already pre-computed
the absolute offset of the page the user wants, so the offset is walked
forward against the canonical sort regardless of `direction`. Direction is
therefore a no-op for offset drivers.

So a Postgres driver receiving sort `[x.ASC, y.DESC]` with
`Direction.Backward` emits:

```
ORDER BY x DESC NULLS FIRST, y ASC NULLS FIRST
```

and the `Direction.Forward` form of the same query emits:

```
ORDER BY x ASC NULLS LAST, y DESC NULLS LAST
```

A Mongo or KV driver receives the same unambiguous instruction.

## Consequences

- The user-facing `SortBy` describes the canonical (forward) ordering and
  never needs to encode "this is the reverse traversal". Reverse traversal
  is an internal driver concern, signaled by `direction`.
- Drivers gain a single decisive rule for backward traversal — "reverse
  order and reverse Absent placement" — independent of dialect-specific
  null defaults. The rule is stable across SQL, document, and KV backends.
- Backward seeks that cross the Some/Absent boundary return the canonical
  slice. Round-trip `next → previous → next` lands on the same page.
- The internal helper `ListSet[SortBy[FIELD]].flipOrder` becomes dead and
  is removed.

## Alternatives rejected

- **Flip `SortBy.order` in core** (the previous implementation). Rejected:
  silently broken under ADR 0001. Works only when the driver's null
  defaults are direction-driven, which folio explicitly does not require.
- **Encode null placement on `SortBy` itself** (e.g. add an
  `AbsentPlacement` field per sort key, flipped on backward traversal).
  Rejected: couples the public sort type to an internal cursor-traversal
  mechanism. Users would have to reason about an extra dimension that has
  no bearing on the canonical query they want to express. The signal
  belongs on `ResolvedQuery`, not on `SortBy`.
- **Adopt the `pgmq4s` flip-only model** (revert ADR 0001's explicit
  `NULLS LAST` policy, lean on direction-driven defaults). Rejected: ADR
  0001 was decided on its own merits (cross-driver consistency, sane Mongo
  / KV semantics); reversing it just to simplify backward traversal is the
  wrong trade.
