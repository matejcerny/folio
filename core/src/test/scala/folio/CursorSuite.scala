package folio

import scala.collection.immutable.ListSet

import folio.FolioError.*
import weaver.SimpleIOSuite

object CursorSuite extends SimpleIOSuite:

  private val baseQuery = TestFixtures.emptyQueryWithId

  private def fingerprint(query: Query[TestField]): String =
    val cursor = Cursor.encode(CursorPosition.Id(None), query)
    cursor.value.split(";").last

  pureTest("roundtrip for Id(None)"):
    val position = CursorPosition.Id(None)
    val cursor = Cursor.encode(position, baseQuery)
    val decoded = Cursor.decode(cursor, baseQuery)
    expect.same(decoded, Right(position))

  pureTest("roundtrip for Id(Some(LastId(42)))"):
    val position = CursorPosition.Id(Some(Offset.LastId(42)))
    val cursor = Cursor.encode(position, baseQuery)
    val decoded = Cursor.decode(cursor, baseQuery)
    expect.same(decoded, Right(position))

  pureTest("roundtrip for Incremental(100)"):
    val position = CursorPosition.Incremental(Offset.Incremental(100))
    val cursor = Cursor.encode(position, baseQuery)
    val decoded = Cursor.decode(cursor, baseQuery)
    expect.same(decoded, Right(position))

  pureTest("deterministic encoding - same input produces same output"):
    val position = CursorPosition.Id(Some(Offset.LastId(7)))
    val cursor1 = Cursor.encode(position, baseQuery)
    val cursor2 = Cursor.encode(position, baseQuery)
    expect.same(cursor1.value, cursor2.value)

  pureTest("different positions produce different cursors"):
    val cursorId = Cursor.encode(CursorPosition.Id(None), baseQuery)
    val cursorIncremental = Cursor.encode(CursorPosition.Incremental(Offset.Incremental(0)), baseQuery)
    expect(clue(cursorId.value) != clue(cursorIncremental.value))

  pureTest("stale cursor - limit changed"):
    val cursor = Cursor.encode(CursorPosition.Id(None), baseQuery)
    val modifiedQuery = baseQuery.copy(limit = Some(Limit(50)))
    val decoded = Cursor.decode(cursor, modifiedQuery)
    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("stale cursor - sort changed"):
    val cursor = Cursor.encode(CursorPosition.Id(None), baseQuery)
    val modifiedQuery = baseQuery.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded = Cursor.decode(cursor, modifiedQuery)
    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("stale cursor - filter changed"):
    val cursor = Cursor.encode(CursorPosition.Id(None), baseQuery)
    val modifiedQuery = baseQuery.copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "bob")))
    val decoded = Cursor.decode(cursor, modifiedQuery)
    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("decode returns InvalidFormat for wrong number of parts"):
    val decoded = Cursor.decode(Cursor("x;0"), baseQuery)
    expect.same(decoded, Left(CursorDecodingError.InvalidFormat(3, 2)))

  pureTest("decode returns UnknownCursorType for invalid type"):
    val hash = fingerprint(baseQuery)
    val decoded = Cursor.decode(Cursor(s"x;0;$hash"), baseQuery)
    expect.same(decoded, Left(CursorDecodingError.UnknownCursorType("x")))

  pureTest("decode returns MalformedOffset for non-numeric offset"):
    val hash = fingerprint(baseQuery)
    val decoded = Cursor.decode(Cursor(s"d;abc;$hash"), baseQuery)
    expect.same(decoded, Left(CursorDecodingError.MalformedOffset("abc")))

  pureTest("roundtrip with fully populated query"):
    val query = TestFixtures.fullyPopulatedQuery
    val position = CursorPosition.Incremental(Offset.Incremental(40))
    val cursor = Cursor.encode(position, query)
    val decoded = Cursor.decode(cursor, query)
    expect.same(decoded, Right(position))
