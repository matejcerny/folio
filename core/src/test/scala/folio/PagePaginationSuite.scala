package folio

import scala.collection.immutable.ListSet

import cats.Id
import cats.data.Writer
import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object PagePaginationSuite extends SimpleIOSuite:

  private val limit = Limit(2)

  private val keysetQuery: Query[TestField] = TestFixtures.queryWithIdSort.copy(limit = Some(limit))
  private val offsetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.CreatedAt.descending), limit = Some(limit))
  private val offsetOnlyQuery: Query[TestFieldNoId] =
    TestFixtures.emptyQueryNoId.copy(sortBys = ListSet(TestFieldNoId.Timestamp.descending), limit = Some(limit))

  private val rowTable: InMemoryTable[TestField, Row] = InMemoryTable(TestFixtures.rows, TestFixtures.rowExtract)
  private val eventTable: InMemoryTable[TestFieldNoId, EventRow] =
    InMemoryTable(TestFixtures.events, TestFixtures.eventExtract)

  private def syntheticRow(id: Long): Row = Row(id, name = "", createdAt = "")
  private def syntheticRows(ids: Long*): Seq[Row] = ids.map(syntheticRow)

  private def decodedOf[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD]): DecodedCursor =
    Cursor.decode(cursor, query) match
      case Right(decoded) => decoded
      case Left(error)    => sys.error(s"decode failed: $error")

  private def queryWithCurrent[FIELD: FieldSchema](base: Query[FIELD], current: DecodedCursor): Query[FIELD] =
    base.copy(cursor = Some(Cursor.encode(current, base)))

  private inline def pageOrFail[FIELD: FieldSchema](rowsPlusOne: Seq[Row], query: Query[FIELD]): Page[Row] =
    Page.withPagination[Id, Row, FIELD](query, _ => rowsPlusOne) match
      case Right(page) => page
      case Left(error) => sys.error(s"unexpected: $error")

  private inline def pageWith[FIELD: FieldSchema, T](
      fetch: ResolvedQuery[FIELD] => Seq[T],
      query: Query[FIELD]
  ): Page[T] =
    Page.withPagination[Id, T, FIELD](query, fetch) match
      case Right(page) => page
      case Left(error) => sys.error(s"unexpected: $error")

  private inline def pageCapturing[FIELD: FieldSchema, T](
      fetch: ResolvedQuery[FIELD] => Seq[T],
      query: Query[FIELD]
  ): (List[ResolvedQuery[FIELD]], Page[T]) =
    type Captured[A] = Writer[List[ResolvedQuery[FIELD]], A]
    val capturingFetch: ResolvedQuery[FIELD] => Captured[Seq[T]] = resolved => Writer(List(resolved), fetch(resolved))
    val (captured, result) = Page.withPagination[Captured, T, FIELD](query, capturingFetch).run
    val page = result match
      case Right(page) => page
      case Left(error) => sys.error(s"unexpected: $error")
    (captured, page)

  // ---------- keyset ----------

  pureTest("keyset: initial forward with hasMore=true emits next pointing at last displayed id"):
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, keysetQuery)
    val next = page.nextCursor.map(decodedOf(_, keysetQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, syntheticRows(1, 2)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(2L)))))
    ).combineAll

  pureTest("keyset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1, 2)
    val page = pageOrFail(rowsPlusOne, keysetQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None),
      expect.same(page.data, syntheticRows(1, 2))
    ).combineAll

  pureTest("keyset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Keyset(Some(0L)))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(2L))))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Keyset(Some(1L)))))
    ).combineAll

  pureTest("keyset: mid-page forward with hasMore=false emits previous only"):
    val current = DecodedCursor(Direction.Forward, Position.Keyset(Some(0L)))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.nextCursor, None),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Keyset(Some(1L)))))
    ).combineAll

  pureTest("keyset: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Keyset(Some(10L)))
    val query = queryWithCurrent(keysetQuery, current)
    // backward fetch returns rows descending; take(limit) keeps the two closest to the cursor,
    // and reversing produces the displayed ascending order.
    val rowsPlusOne = syntheticRows(7, 6, 5)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.data, syntheticRows(6, 7)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(7L))))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Keyset(Some(6L)))))
    ).combineAll

  pureTest("keyset: backward with hasMore=false emits next only"):
    val current = DecodedCursor(Direction.Backward, Position.Keyset(Some(10L)))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(6, 5)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, syntheticRows(5, 6)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(6L)))))
    ).combineAll

  pureTest("keyset: empty rows emits no cursors"):
    val current = DecodedCursor(Direction.Forward, Position.Keyset(Some(0L)))
    val page = pageOrFail(Seq.empty[Row], queryWithCurrent(keysetQuery, current))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None)
    ).combineAll

  // ---------- offset ----------

  pureTest("offset: initial forward with hasMore=true emits next only (advances by limit)"):
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(2L))))
    ).combineAll

  pureTest("offset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None)
    ).combineAll

  pureTest("offset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(32L)))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(28L))))
    ).combineAll

  pureTest("offset: mid-page forward with hasMore=false emits previous only, clamped at zero"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(1L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.nextCursor, None),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset.First)))
    ).combineAll

  pureTest("offset: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Offset(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(32L)))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(28L))))
    ).combineAll

  pureTest("offset: backward with hasMore=false emits next only"):
    val current = DecodedCursor(Direction.Backward, Position.Offset(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    List(
      expect.same(page.previousCursor, None),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(32L))))
    ).combineAll

  // ---------- offset-only (no IdField) ----------
  // FIELD without IdField -> Page.withPagination selects CursorAdvance.offsetOnly,
  // which has no rowId dependency. Position.fromQuery always resolves to Offset.First here.

  pureTest("offset-only: initial forward with hasMore=true emits next pointing at limit"):
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetOnlyQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, syntheticRows(1, 2)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(2L))))
    ).combineAll

  pureTest("offset-only: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None),
      expect.same(page.data, syntheticRows(1))
    ).combineAll

  pureTest("offset-only: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(30L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(32L)))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(28L))))
    ).combineAll

  pureTest("offset-only: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Offset(30L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(32L)))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(28L))))
    ).combineAll

  pureTest("offset-only: previous offset clamped at zero"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(1L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.nextCursor, None),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(0L))))
    ).combineAll

  // ---------- realistic fetcher ----------

  private val realisticOffsetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.CreatedAt.ascending), limit = Some(limit))

  pureTest("realistic offset: forward through full dataset returns contiguous slices"):
    val page1 = pageWith(rowTable.fetch, realisticOffsetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(cursor3)))
    val cursor4 = page3.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page4 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(cursor4)))
    val cursor5 = page4.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page5 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(cursor5)))
    // CreatedAt asc order over the dataset: 2 (01-01), 4 (01-02), 1 (01-03), 6 (01-04),
    // 0 (01-05), 5 (01-06), 7 (01-07), 3 (01-08), 8 (01-09), 9 (01-10).
    List(
      expect.same(page1.data.map(_.id), Seq(2L, 4L)),
      expect.same(page2.data.map(_.id), Seq(1L, 6L)),
      expect.same(page3.data.map(_.id), Seq(0L, 5L)),
      expect.same(page4.data.map(_.id), Seq(7L, 3L)),
      expect.same(page5.data.map(_.id), Seq(8L, 9L)),
      expect.same(page5.nextCursor, None)
    ).combineAll

  pureTest("realistic offset: backward returns previous slice in original sort order"):
    val current = DecodedCursor(Direction.Backward, Position.Offset(4L))
    val query = queryWithCurrent(realisticOffsetQuery, current)
    val (captured, page) = pageCapturing(rowTable.fetch, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(captured.map(_.sortBys), List(ListSet(TestField.CreatedAt.ascending))),
      expect.same(captured.map(_.position), List(Position.Offset(4L))),
      expect.same(page.data.map(_.id), Seq(0L, 5L)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(6L)))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(2L))))
    ).combineAll

  pureTest("realistic offset: round-trip forward/previous/next returns to starting page"):
    val page1 = pageWith(rowTable.fetch, realisticOffsetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(cursor2)))
    val backCursor = page2.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val page3 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(backCursor)))
    val forwardCursor = page3.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page4 = pageWith(rowTable.fetch, realisticOffsetQuery.copy(cursor = Some(forwardCursor)))
    List(
      expect.same(page2.data.map(_.id), Seq(1L, 6L)),
      expect.same(page4, page2)
    ).combineAll

  pureTest("realistic keyset: backward returns the slice preceding the anchor in original order"):
    val current = DecodedCursor(Direction.Backward, Position.Keyset(Some(7L)))
    val query = queryWithCurrent(keysetQuery, current)
    val (captured, page) = pageCapturing(rowTable.fetch, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(captured.map(_.sortBys), List(ListSet(TestField.Id.descending))),
      expect.same(page.data.map(_.id), Seq(5L, 6L)),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(6L))))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Keyset(Some(5L)))))
    ).combineAll

  // ---------- realistic non-id sorts and filters ----------

  pureTest("realistic offset: sort by Name asc with Id asc tiebreak paginates alphabetically"):
    val nameSortQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.Name.ascending, TestField.Id.ascending),
      limit = Some(limit)
    )
    val page1 = pageWith(rowTable.fetch, nameSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, nameSortQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, nameSortQuery.copy(cursor = Some(cursor3)))
    // alice ids 0,2,5,8 then bob ids 1,4,7 then charlie ids 3,6,9.
    List(
      expect.same(page1.data.map(row => (row.name, row.id)), Seq(("alice", 0L), ("alice", 2L))),
      expect.same(page2.data.map(row => (row.name, row.id)), Seq(("alice", 5L), ("alice", 8L))),
      expect.same(page3.data.map(row => (row.name, row.id)), Seq(("bob", 1L), ("bob", 4L)))
    ).combineAll

  pureTest("realistic offset: sort by CreatedAt desc returns rows newest to oldest"):
    val createdDescQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.CreatedAt.descending),
      limit = Some(limit)
    )
    val page1 = pageWith(rowTable.fetch, createdDescQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, createdDescQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, createdDescQuery.copy(cursor = Some(cursor3)))
    // CreatedAt desc: 9 (01-10), 8 (01-09), 3 (01-08), 7 (01-07), 5 (01-06), 0 (01-05), ...
    List(
      expect.same(page1.data.map(_.id), Seq(9L, 8L)),
      expect.same(page2.data.map(_.id), Seq(3L, 7L)),
      expect.same(page3.data.map(_.id), Seq(5L, 0L))
    ).combineAll

  pureTest("realistic offset: filter Name=alice with sort CreatedAt asc round-trips through alice rows"):
    val aliceQuery: Query[TestField] = Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      sortBys = ListSet(TestField.CreatedAt.ascending),
      limit = Some(limit),
      cursor = None
    )
    val page1 = pageWith(rowTable.fetch, aliceQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, aliceQuery.copy(cursor = Some(cursor2)))
    val backCursor = page2.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val pageBack = pageWith(rowTable.fetch, aliceQuery.copy(cursor = Some(backCursor)))
    val forwardCursor = pageBack.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2Again = pageWith(rowTable.fetch, aliceQuery.copy(cursor = Some(forwardCursor)))
    // alice rows by createdAt asc: 2 (01-01), 0 (01-05), 5 (01-06), 8 (01-09).
    List(
      expect.same(page1.data.map(_.id), Seq(2L, 0L)),
      expect.same(page2.data.map(_.id), Seq(5L, 8L)),
      expect.same(page2.nextCursor, None),
      expect.same(pageBack.data.map(_.id), Seq(2L, 0L)),
      expect.same(page2Again, page2)
    ).combineAll

  pureTest("realistic offset: multi-key sort (Name asc, CreatedAt desc) backward cursor returns prior slice"):
    val multiKeyQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.Name.ascending, TestField.CreatedAt.descending),
      limit = Some(limit)
    )
    val page1 = pageWith(rowTable.fetch, multiKeyQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, multiKeyQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, multiKeyQuery.copy(cursor = Some(cursor3)))
    val backCursor = page3.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val page2Again = pageWith(rowTable.fetch, multiKeyQuery.copy(cursor = Some(backCursor)))
    // Order: alice 8 (01-09), 5 (01-06), 0 (01-05), 2 (01-01), bob 7 (01-07), 1 (01-03), 4 (01-02), charlie ...
    List(
      expect.same(page1.data.map(_.id), Seq(8L, 5L)),
      expect.same(page2.data.map(_.id), Seq(0L, 2L)),
      expect.same(page3.data.map(_.id), Seq(7L, 1L)),
      expect.same(page2Again, page2)
    ).combineAll

  pureTest("realistic offset-only: filter Source=api with sort Timestamp desc paginates without KeysetField"):
    val eventQuery: Query[TestFieldNoId] = Query(
      filters = Set(FilterBy.ExactMatch(TestFieldNoId.Source, "api")),
      sortBys = ListSet(TestFieldNoId.Timestamp.descending),
      limit = Some(limit),
      cursor = None
    )
    val (captured, page1) = pageCapturing(eventTable.fetch, eventQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(eventTable.fetch, eventQuery.copy(cursor = Some(cursor2)))
    // api timestamps desc: 01-07, 01-05, 01-03, 01-01.
    List(
      expect.same(captured.map(_.position), List(Position.Offset.First)),
      expect.same(page1.data.map(_.timestamp), Seq("2024-01-07T00:00:00", "2024-01-05T00:00:00")),
      expect.same(page2.data.map(_.timestamp), Seq("2024-01-03T00:00:00", "2024-01-01T00:00:00")),
      expect.same(page2.nextCursor, None)
    ).combineAll

  pureTest("realistic keyset: filter Name=alice with default Id asc anchors cursor within filtered set"):
    val keysetFilteredQuery: Query[TestField] = Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      sortBys = ListSet.empty,
      limit = Some(limit),
      cursor = None
    )
    val (captured1, page1) = pageCapturing(rowTable.fetch, keysetFilteredQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val followUpQuery = keysetFilteredQuery.copy(cursor = Some(cursor2))
    val (captured2, page2) = pageCapturing(rowTable.fetch, followUpQuery)
    // alice rows by id asc: 0, 2, 5, 8. Cursor anchors at id=2 within filtered candidates.
    List(
      expect.same(captured1.map(_.sortBys), List(ListSet(TestField.Id.ascending))),
      expect.same(captured1.map(_.position), List(Position.Keyset(None))),
      expect.same(page1.data.map(_.id), Seq(0L, 2L)),
      expect.same(captured2.map(_.position), List(Position.Keyset(Some(2L)))),
      expect.same(page2.data.map(_.id), Seq(5L, 8L)),
      expect.same(page2.nextCursor, None)
    ).combineAll

  // ---------- no-sort keyset default sort ----------

  pureTest("no-sort keyset: forward initial fetch resolves to default ascending id sort"):
    val query = TestFixtures.emptyQueryWithId.copy(limit = Some(limit))
    val (captured, _) = pageCapturing(rowTable.fetch, query)
    List(
      expect.same(captured.map(_.sortBys), List(ListSet(TestField.Id.ascending))),
      expect.same(captured.map(_.position), List(Position.Keyset(None)))
    ).combineAll

  pureTest("no-sort keyset: backward fetch flips the default to descending id sort"):
    val current = DecodedCursor(Direction.Backward, Position.Keyset(Some(7L)))
    val query = queryWithCurrent(TestFixtures.emptyQueryWithId.copy(limit = Some(limit)), current)
    val (captured, _) = pageCapturing(rowTable.fetch, query)
    expect.same(captured.map(_.sortBys), List(ListSet(TestField.Id.descending)))

  pureTest("no-sort offset-only: empty sortBys still passed through (no IdField in scope)"):
    val query = TestFixtures.emptyQueryNoId.copy(limit = Some(limit))
    val emptyFetch: ResolvedQuery[TestFieldNoId] => Seq[EventRow] = _ => Seq.empty
    val (captured, _) = pageCapturing(emptyFetch, query)
    List(
      expect.same(captured.map(_.sortBys), List(ListSet.empty[SortBy[TestFieldNoId]])),
      expect.same(captured.map(_.position), List(Position.Offset(0L)))
    ).combineAll
