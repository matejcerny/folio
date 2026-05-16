package folio

import weaver.SimpleIOSuite

object DecodedCursorSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Any] = KeysetField(TestField.Id, _ => 0L)

  pureTest("query.toCursor defaults to Forward direction"):
    val query = TestFixtures.emptyQueryWithId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) => expect.same(decoded.direction, Direction.Forward)
      case Left(error)    => failure(s"decode failed: $error")

  pureTest("query.toCursor uses CursorPosition.fromQuery for position"):
    val query = TestFixtures.emptyQueryWithId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) => expect.same(decoded.position, Position.fromQuery(query))
      case Left(error)    => failure(s"decode failed: $error")

  pureTest("query.toCursor without IdField returns Forward + Incremental(First)"):
    val query = TestFixtures.emptyQueryNoId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) =>
        expect.same(
          decoded,
          DecodedCursor(Direction.Forward, Position.Offset.First)
        )
      case Left(error) => failure(s"decode failed: $error")

  pureTest("toCursor / toDecodedCursor roundtrip via extension methods"):
    val query = TestFixtures.emptyQueryWithId
    val decoded = DecodedCursor(Direction.Backward, Position.Keyset(Some(7L)))
    val cursor = decoded.encode(query)
    val roundtrip = cursor.decode(query)

    expect.same(roundtrip, Right(decoded))
