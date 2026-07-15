# ADR 0002 — Absentability is type-driven via `Option[V]`

## Status

Accepted — 2026-05-30.

## Context

folio is adding support for order fields whose row values can be missing
(see [[Absent]] in `CONTEXT.md` and ADR 0001). The user must somehow
declare "this field's row values are absentable" so that:

- the cursor encoder can emit `Absent` in that field's slot,
- the cursor decoder can accept `Absent` in that field's slot,
- the stale-cursor fingerprint can include the per-field absentability
  flag,
- the driver can emit `NULLS LAST` and the right `WHERE` branches.

The declaration shape considered:

1. **Type-driven via `Option[V]`** — the row class is the source of
   truth: if `T.field: Option[V]`, the field is absentable; otherwise
   not. Single `withField` method, overloaded for `T => V` and
   `T => Option[V]`. The unique field's constructor accepts only the
   non-`Option` overload, so the unique field is structurally
   non-absentable.
2. **Explicit `withAbsentableField`** — separate method, takes
   `T => Option[V]` and the inner codec. Reads explicitly at the call
   site that this field is absentable.
3. **Schema-side `FieldShape[FIELD]` typeclass** — declare absentability
   on a separate typeclass. Future-proofs for richer per-field metadata
   (filter typing, collation).

## Decision

Adopt option 1: **the row class's type is the source of truth.**

`KeysetField` exposes:

```scala
object KeysetField:
  def uniqueBy[FIELD, T, ID](idField: FIELD, extract: T => ID)(using
      CursorValueCodec[ID]
  ): Aux[FIELD, T, ID]

trait KeysetField[FIELD, T]:
  def withField[V](field: FIELD, extract: T => V)(using
      CursorValueCodec[V]
  ): Aux[FIELD, T, ID]

  def withField[V](field: FIELD, extract: T => Option[V])(using
      CursorValueCodec[V]
  ): Aux[FIELD, T, ID]
```

`CursorValueCodec` keeps shipping only atomic instances (`Int`, `Long`,
`String`, `OffsetDateTime`); there is **no** ambient
`CursorValueCodec[Option[V]]` derivation. The two `withField` overloads
each handle their case; the unique-field constructor (`uniqueBy`) has no
`Option` overload, so the tiebreaker structurally cannot be absentable.

## Consequences

- **One source of truth.** Absentability lives in the row class. There
  is no separate metadata declaration that can drift out of sync with
  the `Option[V]` typing.
- **Smaller user-facing API.** Users see one method name, `withField`,
  for both required and absentable fields. Method overload resolution
  picks the right path from the extractor's type.
- **Unique field protection is structural, not runtime.** No
  `NotGiven`-style guards, no documentation-only constraints. An
  `Option`-typed unique field simply does not compile.
- **No public `CursorValueCodec[Option[V]]` derivation** — the optional
  handling is internal to the second `withField` overload, so it cannot
  be summoned in unrelated places by accident.
- **Driver introspection is minimal.** `def absentableFields: Set[FIELD]`
  on `KeysetField` is enough for today's needs; richer per-field
  metadata can be added when filter typing arrives (see [[project-folio-pre-1-0]]).

## Alternatives rejected

- **Explicit `withAbsentableField`** (option 2). Rejected: the row
  class's `Option[V]` already carries the same information. Forcing the
  user to also pick a different method name duplicates the disclosure
  for negligible gain.
- **`FieldShape[FIELD]` typeclass now** (option 3). Rejected for now:
  duplicates absentability info between the row class and the typeclass,
  and bets on a `FieldShape` design we have not grilled. Pre-1.0 the
  bridge can be crossed when richer filter typing forces it; the
  current `Set[FIELD]` API converts cleanly to a derived helper at that
  point.
