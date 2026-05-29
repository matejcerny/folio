package folio

import scala.collection.immutable.ListSet

import cats.Id
import cats.data.Writer
import cats.syntax.foldable.*
import folio.FolioError.CursorDecodingError
import TestFixtures.*
import folio.KeysetSyntax.keysetOf
import weaver.SimpleIOSuite

object PagePaginationSuite extends SimpleIOSuite:

  private val limit = 2.items

  private val keysetQuery: Query[TestField] = TestFixtures.queryWithIdSort.copy(limit = limit)
  // Description is intentionally not registered as a keyset field, so this query falls back to offset.
  private val offsetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Description.descending), limit = limit)
  private val offsetOnlyQuery: Query[TestFieldNoId] =
    TestFixtures.emptyQueryNoId.copy(sortBys = ListSet(TestFieldNoId.Timestamp.descending), limit = limit)

  private val rowTable: InMemoryTable[TestField, Row] = InMemoryTable(TestFixtures.rows, TestFixtures.rowExtract)
  private val eventTable: InMemoryTable[TestFieldNoId, EventRow] =
    InMemoryTable(TestFixtures.events, TestFixtures.eventExtract)

  private def syntheticRow(id: Long): Row = Row(id, name = "", createdAt = "", description = "", lastSeen = None)
  private def syntheticRows(ids: Long*): Seq[Row] = ids.map(syntheticRow)

  private inline def decodedOf[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD]): DecodedCursor =
    Cursor.decode(cursor, query) match
      case Right(decoded) => decoded
      case Left(error)    => sys.error(s"decode failed: $error")

  private inline def queryWithCurrent[FIELD: FieldSchema](base: Query[FIELD], current: DecodedCursor): Query[FIELD] =
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
      expect.same(None, page.previousCursor),
      expect.same(syntheticRows(1, 2), page.data),
      expect.same(Some(DecodedCursor(Direction.Forward, keysetOf(2L))), next)
    ).combineAll

  pureTest("keyset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1, 2)
    val page = pageOrFail(rowsPlusOne, keysetQuery)
    List(
      expect.same(None, page.previousCursor),
      expect.same(None, page.nextCursor),
      expect.same(syntheticRows(1, 2), page.data)
    ).combineAll

  pureTest("keyset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, keysetOf(0L))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(Some(DecodedCursor(Direction.Forward, keysetOf(2L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, keysetOf(1L))), previous)
    ).combineAll

  pureTest("keyset: mid-page forward with hasMore=false emits previous only"):
    val current = DecodedCursor(Direction.Forward, keysetOf(0L))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(None, page.nextCursor),
      expect.same(Some(DecodedCursor(Direction.Backward, keysetOf(1L))), previous)
    ).combineAll

  pureTest("keyset: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, keysetOf(10L))
    val query = queryWithCurrent(keysetQuery, current)
    // backward fetch returns rows descending; take(limit) keeps the two closest to the cursor,
    // and reversing produces the displayed ascending order.
    val rowsPlusOne = syntheticRows(7, 6, 5)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(syntheticRows(6, 7), page.data),
      expect.same(Some(DecodedCursor(Direction.Forward, keysetOf(7L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, keysetOf(6L))), previous)
    ).combineAll

  pureTest("keyset: backward with hasMore=false emits next only"):
    val current = DecodedCursor(Direction.Backward, keysetOf(10L))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = syntheticRows(6, 5)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    List(
      expect.same(None, page.previousCursor),
      expect.same(syntheticRows(5, 6), page.data),
      expect.same(Some(DecodedCursor(Direction.Forward, keysetOf(6L))), next)
    ).combineAll

  pureTest("keyset: empty rows emits no cursors"):
    val current = DecodedCursor(Direction.Forward, keysetOf(0L))
    val page = pageOrFail(Seq.empty[Row], queryWithCurrent(keysetQuery, current))
    List(
      expect.same(None, page.previousCursor),
      expect.same(None, page.nextCursor)
    ).combineAll

  // ---------- offset ----------

  pureTest("offset: initial forward with hasMore=true emits next only (advances by limit)"):
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetQuery))
    List(
      expect.same(None, page.previousCursor),
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(2L))), next)
    ).combineAll

  pureTest("offset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    List(
      expect.same(None, page.previousCursor),
      expect.same(None, page.nextCursor)
    ).combineAll

  pureTest("offset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(32L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(28L))), previous)
    ).combineAll

  pureTest("offset: mid-page forward with hasMore=false emits previous only, clamped at zero"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(1L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(None, page.nextCursor),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.First)), previous)
    ).combineAll

  pureTest("offset: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Offset.unsafe(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(32L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(28L))), previous)
    ).combineAll

  pureTest("offset: backward with hasMore=false emits next only"):
    val current = DecodedCursor(Direction.Backward, Position.Offset.unsafe(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    List(
      expect.same(None, page.previousCursor),
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(32L))), next)
    ).combineAll

  // ---------- offset-only (no IdField) ----------
  // FIELD without IdField -> Page.withPagination selects CursorAdvance.offsetOnly,
  // which has no rowId dependency. Position.fromQuery always resolves to Offset.First here.

  pureTest("offset-only: initial forward with hasMore=true emits next pointing at limit"):
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetOnlyQuery))
    List(
      expect.same(None, page.previousCursor),
      expect.same(syntheticRows(1, 2), page.data),
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(2L))), next)
    ).combineAll

  pureTest("offset-only: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    List(
      expect.same(None, page.previousCursor),
      expect.same(None, page.nextCursor),
      expect.same(syntheticRows(1), page.data)
    ).combineAll

  pureTest("offset-only: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(30L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(32L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(28L))), previous)
    ).combineAll

  pureTest("offset-only: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Offset.unsafe(30L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1, 2, 3)
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(32L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(28L))), previous)
    ).combineAll

  pureTest("offset-only: previous offset clamped at zero"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(1L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = syntheticRows(1)
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(None, page.nextCursor),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(0L))), previous)
    ).combineAll

  // ---------- realistic fetcher ----------

  // Sort by Description (unregistered) keeps offset semantics; description == createdAt for all rows
  // so the realistic data assertions land in the same order as a CreatedAt asc sort.
  private val realisticOffsetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Description.ascending), limit = limit)

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
      expect.same(Seq(2L, 4L), page1.data.map(_.id)),
      expect.same(Seq(1L, 6L), page2.data.map(_.id)),
      expect.same(Seq(0L, 5L), page3.data.map(_.id)),
      expect.same(Seq(7L, 3L), page4.data.map(_.id)),
      expect.same(Seq(8L, 9L), page5.data.map(_.id)),
      expect.same(None, page5.nextCursor)
    ).combineAll

  pureTest("realistic offset: backward returns previous slice in original sort order"):
    val current = DecodedCursor(Direction.Backward, Position.Offset.unsafe(4L))
    val query = queryWithCurrent(realisticOffsetQuery, current)
    val (captured, page) = pageCapturing(rowTable.fetch, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(List(ListSet(TestField.Description.ascending)), captured.map(_.sortBys)),
      expect.same(List(Position.Offset.unsafe(4L)), captured.map(_.position)),
      expect.same(Seq(0L, 5L), page.data.map(_.id)),
      expect.same(Some(DecodedCursor(Direction.Forward, Position.Offset.unsafe(6L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, Position.Offset.unsafe(2L))), previous)
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
      expect.same(Seq(1L, 6L), page2.data.map(_.id)),
      expect.same(page2, page4)
    ).combineAll

  pureTest("realistic keyset: backward returns the slice preceding the anchor in original order"):
    val current = DecodedCursor(Direction.Backward, keysetOf(7L))
    val query = queryWithCurrent(keysetQuery, current)
    val (captured, page) = pageCapturing(rowTable.fetch, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(List(ListSet(TestField.Id.ascending)), captured.map(_.sortBys)),
      expect.same(List(Direction.Backward), captured.map(_.direction)),
      expect.same(Seq(5L, 6L), page.data.map(_.id)),
      expect.same(Some(DecodedCursor(Direction.Forward, keysetOf(6L))), next),
      expect.same(Some(DecodedCursor(Direction.Backward, keysetOf(5L))), previous)
    ).combineAll

  // ---------- realistic non-id sorts and filters ----------

  pureTest("realistic offset: sort by Name asc with Id asc tiebreak paginates alphabetically"):
    val nameSortQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.Name.ascending, TestField.Id.ascending),
      limit = limit
    )
    val page1 = pageWith(rowTable.fetch, nameSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, nameSortQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, nameSortQuery.copy(cursor = Some(cursor3)))
    // alice ids 0,2,5,8 then bob ids 1,4,7 then charlie ids 3,6,9.
    List(
      expect.same(Seq(("alice", 0L), ("alice", 2L)), page1.data.map(row => (row.name, row.id))),
      expect.same(Seq(("alice", 5L), ("alice", 8L)), page2.data.map(row => (row.name, row.id))),
      expect.same(Seq(("bob", 1L), ("bob", 4L)), page3.data.map(row => (row.name, row.id)))
    ).combineAll

  pureTest("realistic offset: sort by CreatedAt desc returns rows newest to oldest"):
    val createdDescQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.CreatedAt.descending),
      limit = limit
    )
    val page1 = pageWith(rowTable.fetch, createdDescQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, createdDescQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, createdDescQuery.copy(cursor = Some(cursor3)))
    // CreatedAt desc: 9 (01-10), 8 (01-09), 3 (01-08), 7 (01-07), 5 (01-06), 0 (01-05), ...
    List(
      expect.same(Seq(9L, 8L), page1.data.map(_.id)),
      expect.same(Seq(3L, 7L), page2.data.map(_.id)),
      expect.same(Seq(5L, 0L), page3.data.map(_.id))
    ).combineAll

  pureTest("realistic offset: filter Name=alice with sort CreatedAt asc round-trips through alice rows"):
    val aliceQuery: Query[TestField] = Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      sortBys = ListSet(TestField.CreatedAt.ascending),
      limit = limit,
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
      expect.same(Seq(2L, 0L), page1.data.map(_.id)),
      expect.same(Seq(5L, 8L), page2.data.map(_.id)),
      expect.same(None, page2.nextCursor),
      expect.same(Seq(2L, 0L), pageBack.data.map(_.id)),
      expect.same(page2, page2Again)
    ).combineAll

  pureTest("realistic offset: multi-key sort (Name asc, CreatedAt desc) backward cursor returns prior slice"):
    val multiKeyQuery: Query[TestField] = TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.Name.ascending, TestField.CreatedAt.descending),
      limit = limit
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
      expect.same(Seq(8L, 5L), page1.data.map(_.id)),
      expect.same(Seq(0L, 2L), page2.data.map(_.id)),
      expect.same(Seq(7L, 1L), page3.data.map(_.id)),
      expect.same(page2, page2Again)
    ).combineAll

  pureTest("realistic offset-only: filter Source=api with sort Timestamp desc paginates without KeysetField"):
    val eventQuery: Query[TestFieldNoId] = Query(
      filters = Set(FilterBy.ExactMatch(TestFieldNoId.Source, "api")),
      sortBys = ListSet(TestFieldNoId.Timestamp.descending),
      limit = limit,
      cursor = None
    )
    val (captured, page1) = pageCapturing(eventTable.fetch, eventQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(eventTable.fetch, eventQuery.copy(cursor = Some(cursor2)))
    // api timestamps desc: 01-07, 01-05, 01-03, 01-01.
    List(
      expect.same(List(Position.Offset.First), captured.map(_.position)),
      expect.same(Seq("2024-01-07T00:00:00", "2024-01-05T00:00:00"), page1.data.map(_.timestamp)),
      expect.same(Seq("2024-01-03T00:00:00", "2024-01-01T00:00:00"), page2.data.map(_.timestamp)),
      expect.same(None, page2.nextCursor)
    ).combineAll

  pureTest("realistic keyset: filter Name=alice with default Id asc anchors cursor within filtered set"):
    val keysetFilteredQuery: Query[TestField] = Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      sortBys = ListSet.empty,
      limit = limit,
      cursor = None
    )
    val (captured1, page1) = pageCapturing(rowTable.fetch, keysetFilteredQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val followUpQuery = keysetFilteredQuery.copy(cursor = Some(cursor2))
    val (captured2, page2) = pageCapturing(rowTable.fetch, followUpQuery)
    // alice rows by id asc: 0, 2, 5, 8. Cursor anchors at id=2 within filtered candidates.
    List(
      expect.same(List(ListSet(TestField.Id.ascending)), captured1.map(_.sortBys)),
      expect.same(List(Position.Keyset(Nil)), captured1.map(_.position)),
      expect.same(Seq(0L, 2L), page1.data.map(_.id)),
      expect.same(List(keysetOf(2L)), captured2.map(_.position)),
      expect.same(Seq(5L, 8L), page2.data.map(_.id)),
      expect.same(None, page2.nextCursor)
    ).combineAll

  // ---------- no-sort keyset default sort ----------

  pureTest("no-sort keyset: forward initial fetch resolves to default ascending id sort"):
    val query = TestFixtures.emptyQueryWithId.copy(limit = limit)
    val (captured, _) = pageCapturing(rowTable.fetch, query)
    List(
      expect.same(List(ListSet(TestField.Id.ascending)), captured.map(_.sortBys)),
      expect.same(List(Position.Keyset(Nil)), captured.map(_.position))
    ).combineAll

  pureTest("no-sort keyset: backward fetch keeps the canonical ascending id sort and signals Backward direction"):
    val current = DecodedCursor(Direction.Backward, keysetOf(7L))
    val query = queryWithCurrent(TestFixtures.emptyQueryWithId.copy(limit = limit), current)
    val (captured, _) = pageCapturing(rowTable.fetch, query)
    List(
      expect.same(List(ListSet(TestField.Id.ascending)), captured.map(_.sortBys)),
      expect.same(List(Direction.Backward), captured.map(_.direction))
    ).combineAll

  pureTest("no-sort offset-only: empty sortBys still passed through (no IdField in scope)"):
    val query = TestFixtures.emptyQueryNoId.copy(limit = limit)
    val emptyFetch: ResolvedQuery[TestFieldNoId] => Seq[EventRow] = _ => Seq.empty
    val (captured, _) = pageCapturing(emptyFetch, query)
    List(
      expect.same(List(ListSet.empty[SortBy[TestFieldNoId]]), captured.map(_.sortBys)),
      expect.same(List(Position.Offset.unsafe(0L)), captured.map(_.position))
    ).combineAll

  // ---------- strategy mismatch ----------

  pureTest("mismatch: keyset cursor against offset query (KeysetField in scope, non-id sort) is rejected"):
    val current = DecodedCursor(Direction.Forward, keysetOf(5L))
    val query = queryWithCurrent(offsetQuery, current)
    val result = Page.withPagination[Id, Row, TestField](query, _ => sys.error("fetch not expected"))
    expect.sameL(CursorDecodingError.StrategyMismatch(Position.Offset.First, keysetOf(5L)), result)

  pureTest("mismatch: keyset cursor against offset-only query (no KeysetField) is rejected"):
    val current = DecodedCursor(Direction.Forward, keysetOf(5L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val result = Page.withPagination[Id, EventRow, TestFieldNoId](query, _ => sys.error("fetch not expected"))
    expect.sameL(CursorDecodingError.StrategyMismatch(Position.Offset.First, keysetOf(5L)), result)

  pureTest("mismatch: offset cursor against keyset query (id sort) is rejected"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(10L))
    val query = queryWithCurrent(keysetQuery, current)
    val result = Page.withPagination[Id, Row, TestField](query, _ => sys.error("fetch not expected"))
    expect.sameL(CursorDecodingError.StrategyMismatch(Position.Keyset.First, Position.Offset.unsafe(10L)), result)

  pureTest("mismatch: offset cursor against no-sort keyset default is rejected"):
    val current = DecodedCursor(Direction.Forward, Position.Offset.unsafe(10L))
    val query = queryWithCurrent(TestFixtures.emptyQueryWithId.copy(limit = limit), current)
    val result = Page.withPagination[Id, Row, TestField](query, _ => sys.error("fetch not expected"))
    expect.sameL(CursorDecodingError.StrategyMismatch(Position.Keyset.First, Position.Offset.unsafe(10L)), result)

  // ---------- non-id keyset (multi-field cursor) ----------

  private val createdAtKeysetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.CreatedAt.ascending), limit = limit)

  pureTest("non-id keyset: CreatedAt asc round-trips forward through dataset with id tiebreaker"):
    val page1 = pageWith(rowTable.fetch, createdAtKeysetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(cursor3)))
    // CreatedAt asc: 2 (01-01), 4 (01-02), 1 (01-03), 6 (01-04), 0 (01-05), 5 (01-06), 7 (01-07), 3 (01-08), ...
    List(
      expect.same(Seq(2L, 4L), page1.data.map(_.id)),
      expect.same(Seq(1L, 6L), page2.data.map(_.id)),
      expect.same(Seq(0L, 5L), page3.data.map(_.id))
    ).combineAll

  pureTest("non-id keyset: CreatedAt asc round-trips backward to the previous slice"):
    val page1 = pageWith(rowTable.fetch, createdAtKeysetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(cursor2)))
    val backCursor = page2.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val pageBack = pageWith(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(backCursor)))
    val forwardCursor = pageBack.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2Again = pageWith(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(forwardCursor)))
    List(
      expect.same(Seq(1L, 6L), page2.data.map(_.id)),
      expect.same(Seq(2L, 4L), pageBack.data.map(_.id)),
      expect.same(page2, page2Again)
    ).combineAll

  pureTest("non-id keyset: CreatedAt asc cursor decodes to 2-element keyset (StringV, LongV)"):
    val page1 = pageWith(rowTable.fetch, createdAtKeysetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val decoded = decodedOf(cursor2, createdAtKeysetQuery)
    // page1 ends with row id=4 createdAt=2024-01-02; cursor encodes (createdAt, id) for the last displayed row
    expect.same(
      DecodedCursor(
        Direction.Forward,
        Position.Keyset(List(KeysetValue.StringV("2024-01-02"), KeysetValue.LongV(4L)))
      ),
      decoded
    )

  pureTest("non-id keyset: CreatedAt asc cursor reaches fetcher as 2-element keyset position"):
    val page1 = pageWith(rowTable.fetch, createdAtKeysetQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val (captured, _) = pageCapturing(rowTable.fetch, createdAtKeysetQuery.copy(cursor = Some(cursor2)))
    expect.same(
      List(Position.Keyset(List(KeysetValue.StringV("2024-01-02"), KeysetValue.LongV(4L)))),
      captured.map(_.position)
    )

  pureTest("non-id keyset fallback: sort by unregistered field falls back to offset"):
    // Description is unregistered, so Position.fromQuery resolves to Offset.First.
    val unregisteredSortQuery: Query[TestField] =
      TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Description.descending), limit = limit)
    val (captured, _) = pageCapturing(rowTable.fetch, unregisteredSortQuery)
    List(
      expect.same(List(Position.Offset.First), captured.map(_.position)),
      expect.same(List(ListSet(TestField.Description.descending)), captured.map(_.sortBys))
    ).combineAll

  // ---------- absentable-field keyset ----------
  // LastSeen is registered as absentable; rows 0..4 have Some lastSeen, rows 5..9 have None.
  // Forward order with sort [LastSeen.asc, Id.asc] (Absent-last per ADR 0001):
  //   ids 0, 1, 2, 3, 4 (Some asc), then ids 5, 6, 7, 8, 9 (None tiebroken by Id.asc).

  private val absentSortQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(
      sortBys = ListSet(TestField.LastSeen.ascending, TestField.Id.ascending),
      limit = limit
    )

  pureTest("absent keyset: forward walk crosses Some/Absent boundary in canonical order"):
    val page1 = pageWith(rowTable.fetch, absentSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor3)))
    val cursor4 = page3.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page4 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor4)))
    val cursor5 = page4.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page5 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor5)))
    List(
      expect.same(Seq(0L, 1L), page1.data.map(_.id)),
      expect.same(Seq(2L, 3L), page2.data.map(_.id)),
      // page3 straddles the Some/Absent boundary: id 4 (Some) then id 5 (None).
      expect.same(Seq(4L, 5L), page3.data.map(_.id)),
      expect.same(Seq(6L, 7L), page4.data.map(_.id)),
      expect.same(Seq(8L, 9L), page5.data.map(_.id)),
      expect.same(None, page5.nextCursor)
    ).combineAll

  pureTest("absent keyset: page3's nextCursor anchors Absent in the LastSeen slot"):
    val page1 = pageWith(rowTable.fetch, absentSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor3)))
    val cursor4 = page3.nextCursor.getOrElse(sys.error("expected next cursor"))
    val decoded = decodedOf(cursor4, absentSortQuery)
    // page3 ends with id=5 (None lastSeen); cursor encodes (Absent, id=5).
    expect.same(
      DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5L)))),
      decoded
    )

  pureTest("absent keyset: backward inside the Absent block round-trips to the prior page"):
    // page4 = [(None,6), (None,7)] and page5 = [(None,8), (None,9)] both lie inside the Absent block.
    val page4Cursor = pageWith(rowTable.fetch, absentSortQuery).nextCursor
      .flatMap(c => pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(c))).nextCursor)
      .flatMap(c => pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(c))).nextCursor)
      .getOrElse(sys.error("expected next cursor"))
    val page4 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(page4Cursor)))
    val cursor5 = page4.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page5 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor5)))
    val backCursor = page5.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val page4Again = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(backCursor)))
    List(
      expect.same(Seq(6L, 7L), page4.data.map(_.id)),
      expect.same(Seq(8L, 9L), page5.data.map(_.id)),
      expect.same(page4, page4Again)
    ).combineAll

  pureTest("absent keyset: forward then backward round-trips inside the Some block"):
    val page1 = pageWith(rowTable.fetch, absentSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor2)))
    val backCursor = page2.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val pageBack = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(backCursor)))
    val forwardCursor = pageBack.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2Again = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(forwardCursor)))
    List(
      expect.same(Seq(0L, 1L), pageBack.data.map(_.id)),
      expect.same(page2, page2Again)
    ).combineAll

  pureTest("absent keyset: backward across the Some/Absent boundary returns the canonical preceding slice"):
    // Forward sequence: [0,1] [2,3] [4,5] [6,7] [8,9]. page4=[6,7] sits entirely in the Absent block;
    // its previousCursor anchors at (Absent, 6), so the backward seek must cross into the Some block
    // and return [4, 5] — the canonical slice immediately preceding page4.
    val page1 = pageWith(rowTable.fetch, absentSortQuery)
    val cursor2 = page1.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page2 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor2)))
    val cursor3 = page2.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page3 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor3)))
    val cursor4 = page3.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page4 = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(cursor4)))
    val backCursor = page4.previousCursor.getOrElse(sys.error("expected previous cursor"))
    val pageBack = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(backCursor)))
    val forwardCursor = pageBack.nextCursor.getOrElse(sys.error("expected next cursor"))
    val page4Again = pageWith(rowTable.fetch, absentSortQuery.copy(cursor = Some(forwardCursor)))
    List(
      expect.same(Seq(6L, 7L), page4.data.map(_.id)),
      expect.same(Seq(4L, 5L), pageBack.data.map(_.id)),
      expect.same(page4, page4Again)
    ).combineAll
