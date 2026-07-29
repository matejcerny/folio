package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object DecodedCursorSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Any] = KeysetField.uniqueBy(TestField.Id, _ => 0L)

  pureTest("query.toCursor defaults to Forward direction"):
    val query = TestFixtures.emptyQueryWithId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) => expect.same(Direction.Forward, decoded.direction)
      case Left(error)    => failure(s"decode failed: $error")

  pureTest("query.toCursor uses CursorPosition.fromQuery for position"):
    val query = TestFixtures.emptyQueryWithId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) => expect.same(Position.fromQuery(query), decoded.position)
      case Left(error)    => failure(s"decode failed: $error")

  pureTest("query.toCursor without IdField returns Forward + Incremental(First)"):
    val query = TestFixtures.emptyQueryNoId
    val cursor = query.toCursor()

    Cursor.decode(cursor, query) match
      case Right(decoded) =>
        expect.same(DecodedCursor(Direction.Forward, Position.Offset.First), decoded)
      case Left(error) => failure(s"decode failed: $error")

  pureTest("toCursor / toDecodedCursor roundtrip via extension methods"):
    val query = TestFixtures.emptyQueryWithId
    val decoded = DecodedCursor(Direction.Backward, Position.Keyset(List(FieldValue.LongV(7L).present)))
    val cursor = decoded.encode(query)
    val roundtrip = cursor.decode(query)

    expect.same(Right(decoded), roundtrip)

  pureTest("isFirst: true for a forward first-page anchor, keyset or offset"):
    List(
      expect(DecodedCursor(Direction.Forward, Position.Keyset.First).isFirst),
      expect(DecodedCursor(Direction.Forward, Position.Offset.First).isFirst)
    ).combineAll

  pureTest("isFirst: false for backward direction or a non-first anchor"):
    List(
      expect(!DecodedCursor(Direction.Backward, Position.Keyset.First).isFirst),
      expect(!DecodedCursor(Direction.Backward, Position.Offset.First).isFirst),
      expect(!DecodedCursor(Direction.Forward, Position.Offset.unsafe(5)).isFirst),
      expect(!DecodedCursor(Direction.Forward, Position.Keyset(List(FieldValue.LongV(1).present))).isFirst)
    ).combineAll
