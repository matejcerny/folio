package folio.it

import cats.effect.{ IO, Resource }
import cats.syntax.foldable.*
import skunk.Session
import skunk.implicits.*

import folio.*
import folio.skunk.Pagination

/** Postgres harness shared by the integration suites: the session, the per-test dataset reset, and the cursor-driven
  * walk helpers.
  *
  * A suite extending this supplies datasets and expected page contents only. Walks are driven by folio's own cursors —
  * no cursor string is ever hardcoded — so a suite states what each page must contain and nothing about how the cursor
  * got there.
  *
  * These run against a real PostgreSQL (`docker compose up -d postgres`) so that real `bigint`/`text`/`timestamptz`
  * comparison and ordering — including the `last_seen IS NULL` boundary — is exercised end to end.
  */
abstract class RowsSuite extends weaver.IOSuite:

  // === Session resource (Skunk speaks the PG wire protocol — no JDBC) ===

  type Res = Session[IO]

  // weaver runs a suite's test cases concurrently by default (MutableFSuite.maxParallelism = 10000); a single Skunk
  // `Session` is not safe for concurrent use, so serialize the cases onto it. (`Test/parallelExecution := false` only
  // controls sbt-level cross-suite parallelism, not weaver's intra-suite concurrency — it is what keeps two suites from
  // truncating the shared `rows` table underneath each other.)
  override def maxParallelism: Int = 1

  override def sharedResource: Resource[IO, Session[IO]] =
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    Session
      .Builder[IO]
      .withHost("localhost")
      .withPort(5432)
      .withUserAndPassword("folio", "folio")
      .withDatabase("folio")
      .single

  // === Per-test DB lifecycle ===

  protected def reset(session: Session[IO], rows: List[Row]): IO[Unit] =
    session.execute(sql"TRUNCATE TABLE rows".command).void *>
      session.prepare(insert).flatMap(prepared => rows.traverse_(prepared.execute))

  // === Walk helpers (drive by folio's own cursors — no hardcoded cursor strings) ===

  protected def page(session: Session[IO], query: Query[RowField]): IO[Page[Row]] =
    Pagination.withPagination[IO, Row, RowField](query, session, rowCodec)(select)

  /** Follow `nextCursor` to the end; returns every page in visit order. Terminates when a page has no next cursor (a
    * finite dataset always reaches a short final fetch).
    */
  protected def walkForward(session: Session[IO], query: Query[RowField]): IO[List[Page[Row]]] =
    page(session, query).flatMap: current =>
      current.nextCursor match
        case Some(cursor) => walkForward(session, query.copy(cursor = Some(cursor))).map(current :: _)
        case None         => IO.pure(List(current))

  /** From the final page, follow `previousCursor` to the start; returns each fetched page in visit order (newest to
    * oldest, the final page itself excluded).
    *
    * Termination: both strategies stop naturally when the first page reports no previous cursor. Keyset's reverse seek
    * past the start returns fewer than `limit + 1` rows; offset's previous availability is positional (offset > 0), so
    * at offset 0 the first page emits no previous cursor rather than clamping back to itself and self-looping.
    */
  protected def walkBackward(
      session: Session[IO],
      lastPage: Page[Row],
      query: Query[RowField]
  ): IO[List[Page[Row]]] =
    lastPage.previousCursor match
      case Some(cursor) =>
        page(session, query.copy(cursor = Some(cursor)))
          .flatMap(previousPage => walkBackward(session, previousPage, query).map(previousPage :: _))
      case None => IO.pure(Nil)

  /** Run the full forward + backward walk and assert the page data and the end-cursor presence/absence. */
  protected def checkWalk(
      session: Session[IO],
      rows: List[Row],
      query: Query[RowField],
      expectedForwardData: List[List[Row]],
      expectedBackwardData: List[List[Row]]
  ): IO[weaver.Expectations] =
    for
      _ <- reset(session, rows)
      forwardPages <- walkForward(session, query)
      backwardPages <- walkBackward(session, forwardPages.last, query)
    yield List(
      expect.same(expectedForwardData, forwardPages.map(_.data.toList)),
      expect.same(None, forwardPages.head.previousCursor), // first page: no previous
      expect.same(None, forwardPages.last.nextCursor), // last page: no next
      expect.same(expectedBackwardData, backwardPages.map(_.data.toList))
    ).combineAll
