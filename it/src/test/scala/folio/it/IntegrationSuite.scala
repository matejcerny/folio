package folio.it

import java.time.{ OffsetDateTime, ZoneOffset }
import java.time.temporal.ChronoUnit

import scala.collection.immutable.ListSet

import cats.effect.{ IO, Resource }
import cats.syntax.foldable.*
import skunk.{ AppliedFragment, Codec, Command, Session, Void }
import skunk.codec.all.{ int8, text, timestamptz }
import skunk.implicits.*

import folio.*
import folio.skunk.Pagination

/** Deterministic Postgres integration tests (folio-skunk Phase 3).
  *
  * Each test owns its dataset: it truncates `rows`, inserts a fixed, known set, then walks folio's pagination forward
  * to the end and backward to the start, asserting each page's exact row contents against hand-written expected
  * sequences. These run against a real PostgreSQL (`docker compose up -d postgres`) so that real `bigint`/`timestamptz`
  * sorting — including the `last_seen IS NULL` boundary — is exercised end to end.
  *
  * The headline case is [[B]]: backward traversal across the `last_seen IS NULL` boundary. If it passes, folio's keyset
  * SQL algorithm is confirmed against real Postgres sorting.
  */
object IntegrationSuite extends weaver.IOSuite:

  // === Fixtures: mirror the `rows` table (it/sql/init.sql) ===

  final case class Row(
      id: Long,
      name: String,
      createdAt: OffsetDateTime,
      description: String,
      lastSeen: Option[OffsetDateTime],
      payload: String // decoded into the row, invisible to folio
  )

  enum RowField derives FieldSchema.SnakeCase:
    case Id, Name, CreatedAt, Description, LastSeen // deliberately NO Payload case
  // SnakeCase => id, name, created_at, description, last_seen — matches those columns.

  // LastSeen is registered via the `T => Option[V]` overload, marking it absentable. Description is deliberately not
  // registered, so sorting by it forces the offset branch (case D).
  given KeysetField[RowField, Row] =
    KeysetField
      .uniqueBy(RowField.Id, (row: Row) => row.id)
      .withField(RowField.Name, _.name)
      .withField(RowField.CreatedAt, _.createdAt)
      .withField(RowField.LastSeen, _.lastSeen)

  // Whole-second UTC timestamps so `timestamptz` round-trips losslessly and decoded rows equal the inserted literals.
  private val base: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
  private def at(seconds: Long): OffsetDateTime = base.plusSeconds(seconds).truncatedTo(ChronoUnit.SECONDS)

  // Fixed 6-row dataset: distinct ids, distinct descriptions (total order for the offset case), last_seen mixed
  // Some/None, payload distinct and folio-invisible.
  private val row1 = Row(1, "alice", at(1), "d1", Some(at(10)), "p1")
  private val row2 = Row(2, "bob", at(2), "d2", Some(at(20)), "p2")
  private val row3 = Row(3, "carol", at(3), "d3", None, "p3")
  private val row4 = Row(4, "dave", at(4), "d4", Some(at(30)), "p4")
  private val row5 = Row(5, "erin", at(5), "d5", None, "p5")
  private val row6 = Row(6, "frank", at(6), "d6", None, "p6")
  private val dataset: List[Row] = List(row1, row2, row3, row4, row5, row6)

  // === SELECT + codec ===

  // Projection order (id, name, created_at, description, last_seen, payload) matches Row's field order. The first five
  // match their FieldSchema names (the column-name contract); `payload` is an extra projected column folio does
  // not know about. One Codec[Row] serves both the SELECT decoder and the INSERT encoder.
  private val rowCodec: Codec[Row] =
    (int8 *: text *: timestamptz *: text *: timestamptz.opt *: text).to[Row]

  private val select: AppliedFragment =
    sql"SELECT id, name, created_at, description, last_seen, payload FROM rows".apply(Void)

  private val insert: Command[Row] =
    sql"INSERT INTO rows (id, name, created_at, description, last_seen, payload) VALUES ($rowCodec)".command

  // === Session resource (Skunk speaks the PG wire protocol — no JDBC) ===

  type Res = Session[IO]

  // weaver runs a suite's test cases concurrently by default (MutableFSuite.maxParallelism = 10000); a single Skunk
  // `Session` is not safe for concurrent use, so serialize the cases onto it. (`Test/parallelExecution := false` only
  // controls sbt-level cross-suite parallelism, not weaver's intra-suite concurrency.)
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

  private def reset(session: Session[IO], rows: List[Row]): IO[Unit] =
    session.execute(sql"TRUNCATE TABLE rows".command).void *>
      session.prepare(insert).flatMap(prepared => rows.traverse_(prepared.execute))

  // === Walk helpers (drive by folio's own cursors — no hardcoded cursor strings) ===

  private def page(session: Session[IO], query: Query[RowField]): IO[Page[Row]] =
    Pagination.withPagination[IO, Row, RowField](query, session, rowCodec)(select)

  /** Follow `nextCursor` to the end; returns every page in visit order. Terminates when a page has no next cursor (a
    * finite dataset always reaches a short final fetch).
    */
  private def walkForward(session: Session[IO], query: Query[RowField]): IO[List[Page[Row]]] =
    page(session, query).flatMap: current =>
      current.nextCursor match
        case Some(cursor) => walkForward(session, query.copy(cursor = Some(cursor))).map(current :: _)
        case None         => IO.pure(List(current))

  /** From the final page, follow `previousCursor` to the start; returns each fetched page in visit order (newest to
    * oldest, the final page itself excluded).
    *
    * Termination: both strategies now stop naturally when the first page reports no previous cursor. Keyset's reverse
    * seek past the start returns fewer than `limit + 1` rows; offset's previous availability is positional (offset >
    * 0), so at offset 0 the first page emits no previous cursor rather than clamping back to itself and self-looping.
    */
  private def walkBackward(
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
  private def checkWalk(
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

  // === Cases ===

  // A — keyset by Id ASC, limit 2. Canonical [1,2,3,4,5,6]; pages [1,2] [3,4] [5,6]. Backward reconstructs them in
  // reverse page order (excluding the final page): [3,4] then [1,2].
  test("A: keyset by Id ASC, limit 2 — forward to end, backward to start"): session =>
    val query = Query(Set.empty[FilterBy[RowField]], ListSet(RowField.Id.ascending), 2.items)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2), List(row3, row4), List(row5, row6)),
      expectedBackwardData = List(List(row3, row4), List(row1, row2))
    )

  // B — keyset by LastSeen ASC, Id ASC, limit 2 (THE GATE). ADR 0001: Absent sorts last forward, so
  // present-by-last_seen 1,2,4 then NULLs-by-id 3,5,6 => canonical [1,2,4,3,5,6]; pages [1,2] [4,3] [5,6]. ADR 0003:
  // backward walks the boundary in reverse => [4,3] then [1,2].
  test("B: keyset by LastSeen ASC, Id ASC, limit 2 — backward across the last_seen IS NULL boundary"): session =>
    val query =
      Query(Set.empty[FilterBy[RowField]], ListSet(RowField.LastSeen.ascending, RowField.Id.ascending), 2.items)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2), List(row4, row3), List(row5, row6)),
      expectedBackwardData = List(List(row4, row3), List(row1, row2))
    )

  // C — keyset by LastSeen DESC, Id ASC, limit 2. DESC Forward => NULLS LAST: present-desc 4,2,1 then NULLs-by-id
  // 3,5,6 => canonical [4,2,1,3,5,6]; pages [4,2] [1,3] [5,6]. Backward => [1,3] then [4,2].
  test("C: keyset by LastSeen DESC, Id ASC, limit 2 — DESC forward NULLS LAST, backward reverse"): session =>
    val query =
      Query(Set.empty[FilterBy[RowField]], ListSet(RowField.LastSeen.descending, RowField.Id.ascending), 2.items)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row4, row2), List(row1, row3), List(row5, row6)),
      expectedBackwardData = List(List(row1, row3), List(row4, row2))
    )

  // D — offset by Description ASC, limit 2. Description is unregistered => offset branch. Distinct descriptions give a
  // total order => canonical [1,2,3,4,5,6]; pages [1,2] [3,4] [5,6]. Backward => [3,4] then [1,2].
  test("D: offset by Description ASC, limit 2 — unregistered field forces the offset branch"): session =>
    val query = Query(Set.empty[FilterBy[RowField]], ListSet(RowField.Description.ascending), 2.items)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2), List(row3, row4), List(row5, row6)),
      expectedBackwardData = List(List(row3, row4), List(row1, row2))
    )

  // E — single page (limit >= 6). One page in full canonical order, no previous/next cursor, empty backward walk.
  test("E: single page (limit 10) — one page, no previous/next cursor"): session =>
    val query = Query(Set.empty[FilterBy[RowField]], ListSet(RowField.Id.ascending), 10.items)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2, row3, row4, row5, row6)),
      expectedBackwardData = Nil
    )

  // === Effect-path error handling (no rows fetched; the session is untouched because both paths short-circuit) ===

  // A cursor that cannot be decoded fails inside Page.withPagination before fetchRows runs, so the failure surfaces
  // through folio-skunk's FolioEffect.raiseError bridge as a FolioError in IO.
  test("malformed cursor is raised as a FolioError, not run as SQL"): session =>
    val query = Query(
      Set.empty[FilterBy[RowField]],
      ListSet(RowField.Id.ascending),
      2.items,
      cursor = Some(Cursor("!! not base64 !!"))
    )
    page(session, query).attempt.map:
      case Left(_: FolioError.CursorDecodingError) => success
      case other                                   => failure(s"expected a CursorDecodingError, got $other")

  // ADR 0005: buildSql must never emit truncated SQL. withPagination always pairs a Keyset position with Some(keyset),
  // so this guard is unreachable through the public API; call fetchFromSession directly to confirm the Left is raised.
  test("fetchFromSession raises when buildSql rejects a Keyset position without keyset metadata"): session =>
    val resolvedQuery = ResolvedQuery[RowField](
      Set.empty,
      ListSet(RowField.Id.ascending),
      10.items,
      Position.Keyset(List(KeysetValue.LongV(1))),
      Direction.Forward
    )
    Pagination
      .fetchFromSession[IO, Row, RowField](resolvedQuery, session, rowCodec, select, keysetField = None)
      .attempt
      .map:
        case Left(error) => expect(error.getMessage.contains("folio-skunk buildSql"))
        case Right(rows) => failure(s"expected buildSql failure to raise, got $rows")
