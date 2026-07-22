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

import weaver.SimpleIOSuite

object CursorAdvanceSuite extends SimpleIOSuite:

  private val limit = 10.items

  // --- offsetOnly defensive Keyset passthrough ---

  pureTest("offsetOnly.next passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(KeysetValue.LongV(7L)))

    expect.same(keyset, advance.next(keyset, Vector.empty, Seq.empty, limit))

  pureTest("offsetOnly.previous passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(KeysetValue.LongV(7L)))

    expect.same(keyset, advance.previous(keyset, Vector.empty, Seq.empty, limit))

  // --- keysetAware None-extractor fallback ---
  // When an order field has no registered extractor, encodeRow falls back to the id codec/extractor.
  // In normal flow Position.fromQuery would pick Offset for this configuration, but the trait
  // defensively handles a forged Keyset position.

  pureTest("keysetAware.next falls back to id when order field has no registered extractor"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(99L, "alice", "2024-01-01", "2024-01-01", None)
    val ordering = Vector(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.LongV(99L), KeysetValue.LongV(99L))),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.previous falls back to id when order field has no registered extractor"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(7L, "bob", "2024-01-02", "2024-01-02", None)
    val ordering = Vector(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.LongV(7L), KeysetValue.LongV(7L))),
      advance.previous(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  // --- absentable-field extractor produces KeysetValue.Absent on None ---

  pureTest("keysetAware.next emits Absent when the boundary row's absentable field is None"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(8L, "alice", "2024-01-09", "2024-01-09", None)
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(8L))),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.previous emits Absent when the boundary row's absentable field is None"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(5L, "alice", "2024-01-06", "2024-01-06", None)
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5L))),
      advance.previous(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.next emits the inner codec value when the absentable field has Some"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(2L, "alice", "2024-01-01", "2024-01-01", Some("2024-02-03"))
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.StringV("2024-02-03"), KeysetValue.LongV(2L))),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )
