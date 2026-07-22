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

import scala.compiletime.testing.typeCheckErrors

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object KeysetFieldSuite extends SimpleIOSuite:

  pureTest("uniqueBy registers the unique field as required (non-absentable)"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    List(
      expect.same(TestField.Id, keysetField.field),
      expect.same(Set(TestField.Id), keysetField.fields.keySet),
      expect.same(Set.empty[TestField], keysetField.absentableFields)
    ).combineAll

  pureTest("withField (T => V) registers a non-absentable field"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
    List(
      expect.same(Set(TestField.Id, TestField.Name), keysetField.fields.keySet),
      expect.same(Set.empty[TestField], keysetField.absentableFields)
    ).combineAll

  pureTest("withField (T => Option[V]) registers an absentable field"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, _.lastSeen)
    List(
      expect.same(Set(TestField.Id, TestField.LastSeen), keysetField.fields.keySet),
      expect.same(Set(TestField.LastSeen), keysetField.absentableFields)
    ).combineAll

  pureTest("absentableFields reports only the absentable fields"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
      .withField(TestField.CreatedAt, _.createdAt)
      .withField(TestField.LastSeen, _.lastSeen)
    expect.same(Set[TestField](TestField.LastSeen), keysetField.absentableFields)

  pureTest("absentable extractor encodes None as KeysetValue.Absent and Some as the inner codec value"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, _.lastSeen)
    val extractor = keysetField.fields(TestField.LastSeen)
    val rowWithValue = Row(0L, "alice", "2024-01-01", "2024-01-01", Some("2024-02-03"))
    val rowAbsent = Row(0L, "alice", "2024-01-01", "2024-01-01", None)
    List(
      expect.same(KeysetValue.StringV("2024-02-03"), extractor.encodedFromRow(rowWithValue)),
      expect.same(KeysetValue.Absent, extractor.encodedFromRow(rowAbsent)),
      expect(extractor.isAbsentable)
    ).combineAll

  pureTest("required extractor encodes the value via the inner codec and is not absentable"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
    val extractor = keysetField.fields(TestField.Name)
    val row = Row(0L, "alice", "2024-01-01", "2024-01-01", None)
    List(
      expect.same(KeysetValue.StringV("alice"), extractor.encodedFromRow(row)),
      expect(!extractor.isAbsentable)
    ).combineAll

  pureTest("uniqueBy does not accept Option-typed extractor (compile error)"):
    val errors = typeCheckErrors(
      "KeysetField.uniqueBy(folio.TestField.Id, (row: folio.Row) => row.lastSeen)"
    )
    expect(errors.nonEmpty)

  pureTest("CursorValueCodec[Option[Long]] is not derived (compile error)"):
    val errors = typeCheckErrors("summon[folio.CursorValueCodec[Option[Long]]]")
    expect(errors.nonEmpty)
