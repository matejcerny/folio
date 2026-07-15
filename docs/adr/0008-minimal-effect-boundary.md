# ADR 0008 — Core uses a two-operation effect boundary

## Status

Accepted — 2026-07-15.

## Context

`folio-core` is intended to be a sans-IO pagination core with no runtime
dependencies. It nevertheless depended on Cats Core in two places:

- `Page.withPagination` required `Applicative[F]` to put a cursor-decoding
  `Either` in `F` or map the result of `fetchRows` into a `Page`;
- private cursor encoding and decoding used `Chain`, `StateT`, and Cats syntax.

The public pagination path does not perform arbitrary effect composition. For
each call it does exactly one of the following:

1. decode the cursor unsuccessfully and raise the resulting `FolioError` in
   `F`; or
2. call `fetchRows` once and map its rows into a `Page`.

It does not require `flatMap`, arbitrary error handling, concurrency,
cancellation, timing, or resource management. Requiring Cats typeclasses is
therefore a much larger ecosystem commitment than the implementation needs.

## Decision

`folio-core` defines `FolioEffect[F]` with exactly two operations: `map` and
`raiseError`. `Page.withPagination` requires this capability instead of Cats
`Applicative` and returns `F[Page[T]]`.

The companion supplies synchronous `FolioEffect.Id` and standard-library
`Future` instances. Other ecosystems provide the two native operations at
their boundary. Driver modules hide that bridge where possible; for example,
`folio-skunk` derives `FolioEffect[F]` internally from the `Concurrent[F]` that
Skunk already requires.

Effectful Folio APIs raise `FolioError` in the backend's native failure channel
rather than exposing two error channels as `F[Either[FolioError, A]]`. The
errors already extend `Exception`, which makes them native failures for Cats
Effect and `Future`; ZIO and Kyo can retain them in their typed failure
channels. `FolioEffect.Id` and direct-style implementations throw them. Pure
APIs such as `Cursor.decode` have no `F` in which to raise and continue to
return `Either`.

The private cursor implementation uses standard-library collections and a
small private reader abstraction instead of Cats `Chain` and `StateT`. This
reader is an implementation detail, not a public effect abstraction.

`folio-core` is not matrix-built with `kyo-compat`. That technique is valuable
when a substantial shared runtime needs native concurrency, cancellation, and
resource primitives. Folio's core has no such runtime: generating one artifact
and facade per backend would solve a two-operation problem with a considerably
larger build and publication surface.

## Consequences

- The published `folio-core` compile classpath contains no Cats artifact and no
  other effect runtime.
- Cats Effect, ZIO, Kyo, `Future`, direct-style code, and synchronous code can
  all use the same core API without running a foreign effect system.
- Direct users of `Page.withPagination` need a `FolioEffect[F]` instance for
  effect types other than the supplied `Id` and `Future`; the instance is only
  two methods.
- Effectful consumers handle one native failure channel. They can still recover
  specifically from `FolioError` using their ecosystem's ordinary error
  operators.
- Each pagination call performs at most one dispatch through `FolioEffect`.
  This is outside database hot paths and negligible beside the row fetch.
- Effect-system-specific semantics remain in driver/application boundaries.
  `folio-skunk` remains Cats-based because Skunk itself is Cats-based; this
  decision makes the core Cats-free, not its Cats-native integrations.

## Alternatives rejected

- **Use Cats `ApplicativeError` / `MonadError`.** Rejected: it makes every core
  consumer depend on Cats for two operations that have native equivalents in
  every target ecosystem, and asks unlawful/direct-style backends to pretend
  they implement a larger lawful abstraction.
- **Return `F[Either[FolioError, A]]`.** Rejected: it exposes two competing
  failure channels and forces effectful callers to unwrap a domain error that
  every supported backend can represent natively. Pure APIs keep `Either`.
- **Use `kyo-compat` and publish one core artifact per backend.** Rejected: Folio
  has no shared effect runtime worth lowering, while the matrix, backend
  facades, artifact naming, and Scala-version split would all become permanent
  maintenance obligations.
- **Return a pure pagination plan and make callers execute it.** Viable, but it
  would replace the convenient `F[Page[T]]` API with a two-stage workflow. The
  small capability preserves the single-call shape without tying it to an
  ecosystem.
- **Write separate overloads for every effect type.** Rejected: it duplicates
  an otherwise identical algorithm and requires core dependencies on every
  supported ecosystem.
