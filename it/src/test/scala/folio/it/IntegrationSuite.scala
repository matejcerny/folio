package folio.it

import cats.effect.{ IO, Ref, Resource }
import cats.syntax.foldable.*
import skunk.Session

import folio.*
import folio.skunk.Pagination

/** Deterministic Postgres integration tests for unfiltered pagination (folio-skunk Phase 3).
  *
  * Each test owns its dataset: it truncates `rows`, inserts a fixed, known set, then walks folio's pagination forward
  * to the end and backward to the start, asserting each page's exact row contents against hand-written expected
  * sequences. The schema, row model, and walk helpers live in [[Rows]] and [[RowsSuite]]; [[FilteredIntegrationSuite]]
  * covers the same ground with filters applied.
  *
  * The headline case is [[B]]: backward traversal across the `last_seen IS NULL` boundary. If it passes, folio's keyset
  * SQL algorithm is confirmed against real Postgres ordering.
  */
object IntegrationSuite extends RowsSuite:

  // Fixed 6-row dataset: distinct ids, distinct descriptions (total order for the offset case), last_seen mixed
  // Some/None, payload distinct and folio-invisible. group_id is constant because these cases never filter — the
  // filtered suite is where it carries repeated values.
  private val row1 = Row(1, "alice", at(1), "d1", Some(at(10)), 100, "p1")
  private val row2 = Row(2, "bob", at(2), "d2", Some(at(20)), 100, "p2")
  private val row3 = Row(3, "carol", at(3), "d3", None, 100, "p3")
  private val row4 = Row(4, "dave", at(4), "d4", Some(at(30)), 100, "p4")
  private val row5 = Row(5, "erin", at(5), "d5", None, 100, "p5")
  private val row6 = Row(6, "frank", at(6), "d6", None, 100, "p6")
  private val dataset: List[Row] = List(row1, row2, row3, row4, row5, row6)

  // === Cases ===

  // A — keyset by Id ASC, limit 2. Canonical [1,2,3,4,5,6]; pages [1,2] [3,4] [5,6]. Backward reconstructs them in
  // reverse page order (excluding the final page): [3,4] then [1,2].
  test("A: keyset by Id ASC, limit 2 — forward to end, backward to start"): session =>
    val query = Query(limit = 2.items).orderBy(RowField.Id.ascending)
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
    val query = Query(limit = 2.items).orderBy(RowField.LastSeen.ascending, RowField.Id.ascending)
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
    val query = Query(limit = 2.items).orderBy(RowField.LastSeen.descending, RowField.Id.ascending)
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
    val query = Query(limit = 2.items).orderBy(RowField.Description.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2), List(row3, row4), List(row5, row6)),
      expectedBackwardData = List(List(row3, row4), List(row1, row2))
    )

  // E — single page (limit >= 6). One page in full canonical order, no previous/next cursor, empty backward walk.
  test("E: single page (limit 10) — one page, no previous/next cursor"): session =>
    val query = Query(limit = 10.items).orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(row1, row2, row3, row4, row5, row6)),
      expectedBackwardData = Nil
    )

  // Resource[Session] overload — acquiring a session per call yields the same page as the primitive Session overload.
  // The shared session is wrapped in Resource.pure (no-op finalizer), so it stays open for the concurrently-run cases.
  test("Resource[Session] overload matches the Session overload"): session =>
    val query = Query(limit = 2.items).orderBy(RowField.Id.ascending)
    val sessionResource: Resource[IO, Session[IO]] = Resource.pure(session)
    for
      _ <- reset(session, dataset)
      viaSession <- Pagination.withPagination[IO, Row, RowField](query, session, rowCodec)(select)
      viaResource <- Pagination.withPagination[IO, Row, RowField](query, sessionResource, rowCodec)(select)
    yield List(
      expect.same(List(row1, row2), viaResource.data.toList),
      expect.same(viaSession.data.toList, viaResource.data.toList),
      expect.same(viaSession.nextCursor, viaResource.nextCursor),
      expect.same(viaSession.previousCursor, viaResource.previousCursor)
    ).combineAll

  // The Resource[Session] overload must acquire the session for the call and release it afterwards. Ref counters
  // recorded by a Resource.make wrapper around the shared session prove the finalizer runs exactly once, which
  // Resource.pure (no-op finalizer) above cannot show.
  test("Resource[Session] overload acquires and releases the session exactly once"): session =>
    val query = Query(limit = 2.items).orderBy(RowField.Id.ascending)
    for
      _ <- reset(session, dataset)
      acquired <- Ref[IO].of(0)
      released <- Ref[IO].of(0)
      sessionResource = Resource.make(acquired.update(_ + 1).as(session))(_ => released.update(_ + 1))
      resultPage <- Pagination.withPagination[IO, Row, RowField](query, sessionResource, rowCodec)(select)
      acquiredCount <- acquired.get
      releasedCount <- released.get
    yield List(
      expect.same(List(row1, row2), resultPage.data.toList),
      expect.same(1, acquiredCount),
      expect.same(1, releasedCount)
    ).combineAll

  // === Effect-path error handling (no rows fetched; the session is untouched because both paths short-circuit) ===

  // A cursor that cannot be decoded fails inside Page.withPagination before fetchRows runs, so the failure surfaces
  // through folio-skunk's FolioEffect.raiseError bridge as a FolioError in IO.
  test("malformed cursor is raised as a FolioError, not run as SQL"): session =>
    val query = Query(limit = 2.items, cursor = Some(Cursor("!! not base64 !!"))).orderBy(RowField.Id.ascending)
    page(session, query).attempt.map:
      case Left(_: FolioError.CursorDecodingError) => success
      case other                                   => failure(s"expected a CursorDecodingError, got $other")

  // ADR 0005: buildSql must never emit truncated SQL. withPagination always pairs a Keyset position with Some(keyset),
  // so this guard is unreachable through the public API; call fetchFromSession directly to confirm the Left is raised.
  test("fetchFromSession raises when buildSql rejects a Keyset position without keyset metadata"): session =>
    val resolvedQuery = ResolvedQuery[RowField](
      Set.empty,
      Vector(RowField.Id.ascending),
      10.items,
      Position.Keyset(List(FieldValue.LongV(1).present)),
      Direction.Forward
    )
    Pagination
      .fetchFromSession[IO, Row, RowField](resolvedQuery, session, rowCodec, select, keysetField = None)
      .attempt
      .map:
        case Left(_: FolioError.InvalidQuery) => success
        case Left(error)                      => failure(s"expected InvalidKeysetQuery, got $error")
        case Right(rows)                      => failure(s"expected buildSql failure to raise, got $rows")
