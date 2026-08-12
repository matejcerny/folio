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
  * A suite extending this supplies datasets and expected page contents only; walks are driven by folio's own cursors,
  * so no cursor string is ever hardcoded.
  *
  * Needs a real PostgreSQL (`docker compose up -d postgres`) to exercise real `bigint`/`text`/`timestamptz` comparison,
  * including the `last_seen IS NULL` boundary.
  */
abstract class RowsSuite extends weaver.IOSuite:

  // === Session resource (Skunk speaks the PG wire protocol — no JDBC) ===

  type Res = Session[IO]

  // Weaver runs test cases concurrently by default and a Skunk `Session` is not concurrency-safe, so serialize them.
  // (`Test/parallelExecution := false` only stops cross-suite parallelism, which is what keeps two suites from
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

  /** Follow `nextCursor` to the end; returns every page in visit order. */
  protected def walkForward(session: Session[IO], query: Query[RowField]): IO[List[Page[Row]]] =
    page(session, query).flatMap: current =>
      current.nextCursor match
        case Some(cursor) => walkForward(session, query.copy(cursor = Some(cursor))).map(current :: _)
        case None         => IO.pure(List(current))

  /** From the final page, follow `previousCursor` to the start; returns each fetched page in visit order (newest to
    * oldest, the final page excluded). Both strategies stop when the first page reports no previous cursor.
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
