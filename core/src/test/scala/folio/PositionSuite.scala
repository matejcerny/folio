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

import scala.util.Try

import weaver.SimpleIOSuite
import TestFixtures.*

object PositionSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Any] = KeysetField.uniqueBy(TestField.Id, _ => 0L)

  // Variant where only CreatedAt has an extractor registered (no Name extractor),
  // used to exercise non-id keyset selection and unregistered-secondary fallback.
  private object WithCreatedAt:
    given KeysetField[TestField, Any] =
      KeysetField.uniqueBy(TestField.Id, (_: Any) => 0L).withField(TestField.CreatedAt, _ => "")

  pureTest("IdField present + primary order is id field returns Keyset(Nil)"):
    val query = Query.empty[TestField].copy(ordering = Vector(TestField.Id.ascending))
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset(Nil), position)

  pureTest("IdField present + primary order has no extractor returns Offset.First"):
    val query = Query.empty[TestField].copy(ordering = Vector(TestField.CreatedAt.descending))
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("IdField present + no ordering returns Keyset(Nil)"):
    val position = Position.fromQuery(Query.empty[TestField])

    expect.same(Position.Keyset(Nil), position)

  pureTest("no IdField always returns Offset.First"):
    val position = Position.fromQuery(Query.empty[TestFieldNoId])

    expect.same(Position.Offset.First, position)

  pureTest("no IdField with ordering still returns Offset.First"):
    val query = Query.empty[TestFieldNoId].copy(ordering = Vector(TestFieldNoId.Timestamp.ascending))
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("non-id keyset: primary order with extractor registered returns Keyset.First"):
    import WithCreatedAt.given
    val query = Query.empty[TestField].copy(ordering = Vector(TestField.CreatedAt.descending))
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset.First, position)

  pureTest("non-id keyset: secondary order without extractor falls back to Offset.First"):
    import WithCreatedAt.given
    val query = Query
      .empty[TestField]
      .copy(
        ordering = Vector(TestField.CreatedAt.ascending, TestField.Name.ascending)
      )
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("non-id keyset: all order fields registered (CreatedAt + Id) returns Keyset.First"):
    import WithCreatedAt.given
    val query = Query
      .empty[TestField]
      .copy(
        ordering = Vector(TestField.CreatedAt.ascending, TestField.Id.ascending)
      )
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset.First, position)

  pureTest("Offset.apply rejects negative offsets"):
    expect.sameL("Offset must be non-negative, got -1", Position.Offset(-1L))

  pureTest("Offset.apply accepts zero"):
    expect.sameR(Position.Offset.First, Position.Offset(0L))

  pureTest("Offset.unsafe throws IllegalArgumentException on negative input"):
    Try(Position.Offset.unsafe(-5L)).toEither match
      case Left(error: IllegalArgumentException) =>
        expect(clue(error.getMessage).contains("Offset must be non-negative, got -5"))
      case other => failure(s"expected IllegalArgumentException, got $other")
