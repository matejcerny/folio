package folio

import scala.collection.immutable.ListSet

import folio.FolioError.*
import weaver.SimpleIOSuite

object CursorSuite extends SimpleIOSuite:

  private val baseQuery = TestFixtures.emptyQueryWithId

  private def fingerprint(query: Query[TestField]): String =
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(None))
    val cursor = Cursor.encode(decoded, query)

    cursor.value.split(";").last

  pureTest("roundtrip for forward Id(None)"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(None))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.same(roundtrip, Right(decoded))

  pureTest("roundtrip for forward Id(Some(LastId(42)))"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Some(42L)))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.same(roundtrip, Right(decoded))

  pureTest("roundtrip for forward Incremental(100)"):
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(100L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.same(roundtrip, Right(decoded))

  pureTest("roundtrip for backward Id(Some(LastId(42)))"):
    val decoded = DecodedCursor(Direction.Backward, Position.Keyset(Some(42L)))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.same(roundtrip, Right(decoded))

  pureTest("roundtrip for backward Incremental(100)"):
    val decoded = DecodedCursor(Direction.Backward, Position.Offset.unsafe(100L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.same(roundtrip, Right(decoded))

  pureTest("deterministic encoding - same input produces same output"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Some(7L)))
    val cursor1 = Cursor.encode(decoded, baseQuery)
    val cursor2 = Cursor.encode(decoded, baseQuery)

    expect.same(cursor1.value, cursor2.value)

  pureTest("different positions produce different cursors"):
    val cursorId = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(None)), baseQuery)
    val cursorIncremental = Cursor.encode(
      DecodedCursor(Direction.Forward, Position.Offset.First),
      baseQuery
    )

    expect(clue(cursorId.value) != clue(cursorIncremental.value))

  pureTest("different directions produce different cursors"):
    val position = Position.Keyset(Some(5L))
    val forwardCursor = Cursor.encode(DecodedCursor(Direction.Forward, position), baseQuery)
    val backwardCursor = Cursor.encode(DecodedCursor(Direction.Backward, position), baseQuery)

    expect(clue(forwardCursor.value) != clue(backwardCursor.value))

  pureTest("direction does not affect the query fingerprint"):
    val position = Position.Keyset(Some(5L))
    val forwardFingerprint =
      Cursor.encode(DecodedCursor(Direction.Forward, position), baseQuery).value.split(";").last
    val backwardFingerprint =
      Cursor.encode(DecodedCursor(Direction.Backward, position), baseQuery).value.split(";").last

    expect.same(forwardFingerprint, backwardFingerprint)

  pureTest("stale cursor - limit changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(None)), baseQuery)
    val modifiedQuery = baseQuery.copy(limit = 50.items)
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("stale cursor - sort changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(None)), baseQuery)
    val modifiedQuery = baseQuery.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("stale cursor - filter changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(None)), baseQuery)
    val modifiedQuery = baseQuery.copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "bob")))
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.same(decoded, Left(CursorDecodingError.StaleCursor))

  pureTest("decode returns InvalidFormat for wrong number of parts"):
    val decoded = Cursor.decode(Cursor("F;k;0"), baseQuery)

    expect.same(decoded, Left(CursorDecodingError.InvalidFormat(4, 3)))

  pureTest("decode returns UnknownCursorType for invalid type"):
    val hash = fingerprint(baseQuery)
    val decoded = Cursor.decode(Cursor(s"F;x;0;$hash"), baseQuery)

    expect.same(decoded, Left(CursorDecodingError.UnknownCursorType("x")))

  pureTest("decode returns UnknownDirection for invalid direction"):
    val hash = fingerprint(baseQuery)
    val decoded = Cursor.decode(Cursor(s"X;k;0;$hash"), baseQuery)

    expect.same(decoded, Left(CursorDecodingError.UnknownDirection("X")))

  pureTest("decode returns MalformedOffset for non-numeric offset"):
    val hash = fingerprint(baseQuery)
    val decoded = Cursor.decode(Cursor(s"F;k;abc;$hash"), baseQuery)

    expect.same(decoded, Left(CursorDecodingError.MalformedOffset("abc")))

  pureTest("roundtrip with fully populated query"):
    val query = TestFixtures.fullyPopulatedQuery
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(40L))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.same(roundtrip, Right(decoded))
