# pgmq4s folio integration: short implementation draft

This is a shape-of-the-change draft, not a patch, written against the current
API of both libraries:

- **Cross-publishing folio for JVM/JS/Native is the one P0 blocker.** folio is
  JVM-only today; see the migration doc's P0 for the plumbing that lift needs.
- **`Page[T].map`** remaps page data (`RawMessage` -> `InspectedMessage`) while
  preserving the limit and both cursors.
- **Cursor errors are raised in `F`.** `Page.withPagination` raises
  `FolioError.CursorDecodingError` through the effect, and pgmq4s propagates it.
- Both libraries are effect-agnostic: pgmq4s-core is on `PgmqEffect`, folio on
  `FolioEffect`, bridged by the adapter given in pgmq4s-core (shown below).

## Dependencies

```scala
val FolioV = "<released-folio-version>"

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  // existing settings ...
  .settings(
    libraryDependencies += "io.github.matejcerny" %%% "folio-core" % FolioV
  )

lazy val skunk = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  // existing settings ...
  .settings(
    libraryDependencies += "io.github.matejcerny" %%% "folio-skunk" % FolioV
  )
```

## Field schema and row keyset

Keep `MessageSortField`, but replace its custom cursor methods with a folio
schema:

```scala
package pgmq4s.domain.pagination

import folio.FieldSchema

enum MessageSortField:
  case Id, EnqueuedAt, VisibleAt, ReadCount, LastReadAt

object MessageSortField:
  given FieldSchema[MessageSortField] = FieldSchema.fromMapping:
    case Id         => "msg_id"
    case EnqueuedAt => "enqueued_at"
    case VisibleAt  => "vt"
    case ReadCount  => "read_ct"
    case LastReadAt => "last_read_at"
```

Define the row metadata once and share the named given between core and Skunk:

```scala
package pgmq4s

import folio.KeysetField
import pgmq4s.domain.RawMessage
import pgmq4s.domain.pagination.MessageSortField

private[pgmq4s] object InspectorPagination:
  given messageKeyset: KeysetField[MessageSortField, RawMessage] =
    KeysetField
      .uniqueBy(MessageSortField.Id, (message: RawMessage) => message.msgId)
      .withField(MessageSortField.EnqueuedAt, _.enqueuedAt)
      .withField(MessageSortField.VisibleAt, _.vt)
      .withField(MessageSortField.ReadCount, _.readCt)
      .withField(MessageSortField.LastReadAt, _.lastReadAt)
```

## Effect adapter

pgmq4s-core defines one Cats-free adapter given, bridging its `PgmqEffect` to
folio's `FolioEffect`. It works for `Future` and any Cats `MonadThrow` alike,
and is what lets `PgmqInspector.apply[F: PgmqEffect]` call `Page.withPagination`:

```scala
package pgmq4s

import folio.{ FolioEffect, FolioError }

given [F[_]](using effect: PgmqEffect[F]): FolioEffect[F] with
  def map[A, B](fa: F[A])(f: A => B): F[B] = effect.map(fa)(f)
  def raiseError[A](error: FolioError): F[A] = effect.raiseError(error) // FolioError <: Throwable
```

## Public algebra and core implementation

This version keeps pgmq4s's existing single-order feature but uses folio types
and raises cursor failures in `F`:

```scala
package pgmq4s

import folio.*
import pgmq4s.domain.*
import pgmq4s.domain.pagination.*

trait PgmqInspector[F[_]]:
  def browseMessages(
      queue: QueueName,
      limit: Limit,
      orderBy: OrderBy[MessageSortField] = MessageSortField.Id.descending,
      cursor: Option[Cursor] = None
  ): F[Page[InspectedMessage]]

  def browseArchive(
      queue: QueueName,
      limit: Limit,
      orderBy: OrderBy[MessageSortField] = MessageSortField.Id.descending,
      cursor: Option[Cursor] = None
  ): F[Page[InspectedMessage]]

  def countMessages(queue: QueueName): F[Long]
  def countArchive(queue: QueueName): F[Long]

object PgmqInspector:
  def apply[F[_]: PgmqEffect](backend: PgmqInspectorBackend[F]): PgmqInspector[F] =
    PgmqInspectorImpl(backend)

  private class PgmqInspectorImpl[F[_]: PgmqEffect](backend: PgmqInspectorBackend[F])
      extends PgmqInspector[F]:

    import InspectorPagination.given

    def browseMessages(
        queue: QueueName,
        limit: Limit,
        orderBy: OrderBy[MessageSortField],
        cursor: Option[Cursor]
    ): F[Page[InspectedMessage]] =
      browse(queue.tableName, limit, orderBy, cursor)

    def browseArchive(
        queue: QueueName,
        limit: Limit,
        orderBy: OrderBy[MessageSortField],
        cursor: Option[Cursor]
    ): F[Page[InspectedMessage]] =
      browse(queue.archiveName, limit, orderBy, cursor)

    def countMessages(queue: QueueName): F[Long] =
      backend.countMessages(queue.tableName)

    def countArchive(queue: QueueName): F[Long] =
      backend.countMessages(queue.archiveName)

    private def browse(
        table: String,
        limit: Limit,
        orderBy: OrderBy[MessageSortField],
        cursor: Option[Cursor]
    ): F[Page[InspectedMessage]] =
      val query = Query(limit = limit, cursor = cursor).orderBy(orderBy)

      // `Page.withPagination` raises `FolioError.CursorDecodingError` through F.
      // The `PgmqEffect => FolioEffect` adapter given (above) supplies the
      // `FolioEffect[F]` it needs.
      val page: F[Page[RawMessage]] =
        Page.withPagination[F, RawMessage, MessageSortField](
          query,
          backend.browseMessages(table, _),
          Some(InspectorPagination.messageKeyset)
        )

      // `Page[T].map` remaps the data while preserving the limit and both cursors.
      PgmqEffect[F].map(page)(_.map(InspectedMessage.fromRaw))
```

The backend SPI becomes:

```scala
private[pgmq4s] trait PgmqInspectorBackend[F[_]]:
  def browseMessages(
      table: String,
      query: ResolvedQuery[MessageSortField]
  ): F[Seq[RawMessage]]

  def countMessages(table: String): F[Long]
```

## Skunk backend

The hand-written cursor predicate and order construction disappear:

```scala
package pgmq4s.skunk

import _root_.skunk as sk
import cats.effect.{ Resource, Temporal }
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import folio.*
import folio.skunk.Pagination
import pgmq4s.*
import pgmq4s.domain.*
import pgmq4s.domain.pagination.MessageSortField
import sk.*
import sk.codec.all.*
import sk.implicits.*

class SkunkPgmqInspectorBackend[F[_]: Temporal](pool: Resource[F, Session[F]])
    extends PgmqInspectorBackend[F]:

  import SkunkCodecs.rawMessageDecoder

  def browseMessages(
      table: String,
      query: ResolvedQuery[MessageSortField]
  ): F[Seq[RawMessage]] =
    // This helper must quote the schema and relation separately and double any
    // embedded quotes; do not pass the current raw `table` string through.
    val quotedTable = InspectorTableSql.quoteQualified(table)
    val select =
      sql"""SELECT msg_id, read_ct, enqueued_at, last_read_at, vt,
                    message::text, headers::text
             FROM #$quotedTable""".apply(Void)

    Pagination.buildSql(query, select, Some(InspectorPagination.messageKeyset)) match
      case Left(error) =>
        Temporal[F].raiseError(error)
      case Right(applied) =>
        pool.use:
          _.prepare(applied.fragment.query(rawMessageDecoder))
            .flatMap:
              _.stream(applied.argument, chunkSize = query.fetchLimit.value)
                .compile
                .toList
                .widen[Seq[RawMessage]]

  // countMessages keeps its behavior but uses the same quoted table fragment.
```

The count operation's table reference must use the same quoting helper. A
better follow-up is to change the SPI from `table: String` to a small structured
live/archive table target, so an unquoted arbitrary relation cannot reach a
backend at all.

The stream/chunk size uses `query.fetchLimit.value` (the page limit plus one),
the same fetch size folio's `buildSql` renders as `LIMIT`; do **not** add 1 again.
`ResolvedQuery.fetchLimit` (and `Limit.fetchLimit`) are public. Once `buildSql`
returns a typed `FolioError`, raise it unchanged as above or map it at the
application boundary when pgmq4s needs its own error vocabulary.

`SkunkPgmqInspector.apply` itself needs no structural change; its `Temporal`
constraint supplies a `PgmqEffect[F]` (via the `MonadThrow → PgmqEffect` bridge
in `pgmq4s-cats`), which is what `PgmqInspector.apply` now requires:

```scala
def apply[F[_]: Temporal](pool: Resource[F, Session[F]]): PgmqInspector[F] =
  PgmqInspector(SkunkPgmqInspectorBackend[F](pool))
```

At a call site, `browseMessages` returns `F[Page[InspectedMessage]]` directly.
A malformed, stale, or incompatible cursor is raised as a `FolioError` in `F`
and flows through the caller's own error channel (e.g. `handleErrorWith` /
`recover` / `attempt`). Navigation reuses the same arguments with the cursor
returned by the page:

```scala
inspector.browseMessages(queue, 25.items).map: page =>
  val nextRequest = page.nextCursor.map: cursor =>
    inspector.browseMessages(queue, 25.items, cursor = Some(cursor))
  render(page.data, page.previousCursor, nextRequest)
// Cursor failures surface as a raised FolioError.CursorDecodingError in F;
// handle them where the effect's errors are handled.
```

See [`pgmq4s-migration.md`](pgmq4s-migration.md) for behavior changes, test
coverage, and the reasons for the two-layer integration.
