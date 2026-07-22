/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
    val decoded = DecodedCursor(Direction.Backward, Position.Keyset(List(KeysetValue.LongV(7L))))
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
      expect(!DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.LongV(1)))).isFirst)
    ).combineAll
