package folio

import scala.collection.immutable.ListSet

import cats.Id
import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object PagePaginationSuite extends SimpleIOSuite:

  private val limit = Limit(2)

  private val keysetQuery: Query[TestField] = TestFixtures.queryWithIdSort.copy(limit = Some(limit))
  private val offsetQuery: Query[TestField] =
    TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.CreatedAt.descending), limit = Some(limit))
  private val offsetOnlyQuery: Query[TestFieldNoId] =
    TestFixtures.emptyQueryNoId.copy(sortBys = ListSet(TestFieldNoId.Timestamp.descending), limit = Some(limit))

  private case class Row(id: Long)

  private given RowId[Row] = RowId(_.id)

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

  // ---------- keyset ----------

  pureTest("keyset: initial forward with hasMore=true emits next pointing at last displayed id"):
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
    val page = pageOrFail(rowsPlusOne, keysetQuery)
    val next = page.nextCursor.map(decodedOf(_, keysetQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, Seq(Row(1), Row(2))),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(2L)))))
    ).combineAll

  pureTest("keyset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = Seq(Row(1), Row(2))
    val page = pageOrFail(rowsPlusOne, keysetQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None),
      expect.same(page.data, Seq(Row(1), Row(2)))
    ).combineAll

  pureTest("keyset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Keyset(Some(0L)))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
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
    val rowsPlusOne = Seq(Row(1), Row(2))
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
    val rowsPlusOne = Seq(Row(7), Row(6), Row(5))
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.data, Seq(Row(6), Row(7))),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Keyset(Some(7L))))),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Keyset(Some(6L)))))
    ).combineAll

  pureTest("keyset: backward with hasMore=false emits next only"):
    val current = DecodedCursor(Direction.Backward, Position.Keyset(Some(10L)))
    val query = queryWithCurrent(keysetQuery, current)
    val rowsPlusOne = Seq(Row(6), Row(5))
    val page = pageOrFail(rowsPlusOne, query)
    val next = page.nextCursor.map(decodedOf(_, query))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, Seq(Row(5), Row(6))),
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
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(2L))))
    ).combineAll

  pureTest("offset: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = Seq(Row(1))
    val page = pageOrFail(rowsPlusOne, offsetQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None)
    ).combineAll

  pureTest("offset: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
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
    val rowsPlusOne = Seq(Row(1))
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.nextCursor, None),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(0L))))
    ).combineAll

  pureTest("offset: backward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Backward, Position.Offset(30L))
    val query = queryWithCurrent(offsetQuery, current)
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
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
    val rowsPlusOne = Seq(Row(1))
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
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    val next = page.nextCursor.map(decodedOf(_, offsetOnlyQuery))
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.data, Seq(Row(1), Row(2))),
      expect.same(next, Some(DecodedCursor(Direction.Forward, Position.Offset(2L))))
    ).combineAll

  pureTest("offset-only: initial forward with hasMore=false emits no cursors"):
    val rowsPlusOne = Seq(Row(1))
    val page = pageOrFail(rowsPlusOne, offsetOnlyQuery)
    List(
      expect.same(page.previousCursor, None),
      expect.same(page.nextCursor, None),
      expect.same(page.data, Seq(Row(1)))
    ).combineAll

  pureTest("offset-only: mid-page forward with hasMore=true emits both"):
    val current = DecodedCursor(Direction.Forward, Position.Offset(30L))
    val query = queryWithCurrent(offsetOnlyQuery, current)
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
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
    val rowsPlusOne = Seq(Row(1), Row(2), Row(3))
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
    val rowsPlusOne = Seq(Row(1))
    val page = pageOrFail(rowsPlusOne, query)
    val previous = page.previousCursor.map(decodedOf(_, query))
    List(
      expect.same(page.nextCursor, None),
      expect.same(previous, Some(DecodedCursor(Direction.Backward, Position.Offset(0L))))
    ).combineAll
