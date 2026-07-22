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

import scala.compiletime.summonFrom

final class InMemoryTable[FIELD: FieldSchema, T](rows: Vector[T], extract: (FIELD, T) => Option[String]):

  inline def fetch(resolved: ResolvedQuery[FIELD]): Seq[T] =
    val filtered = rows.filter(matches(_, resolved.filters))
    // Backward traversal reverses ordering and Absent placement (ADR 0003), but only for keyset seeks:
    // an offset position already encodes the absolute slot of the previous page, so direction is a no-op.
    val reverseTraversal = resolved.direction == Direction.Backward && resolved.position.isInstanceOf[Position.Keyset]
    val ordered = applyOrdering(filtered, resolved.ordering, reverseTraversal)
    val skipped = applyPosition(ordered, resolved.position, resolved.ordering)
    skipped.take(resolved.fetchLimit.value)

  private def matches(row: T, filters: Set[FilterBy[FIELD]]): Boolean =
    filters.forall:
      case FilterBy.ExactMatch(field, value) => extract(field, row).contains(value)

  private def applyOrdering(rows: Vector[T], ordering: Vector[OrderBy[FIELD]], reverseTraversal: Boolean): Vector[T] =
    if ordering.isEmpty then rows
    else
      val orderings = ordering.toList.map: orderBy =>
        val valueOrdering = (reverseTraversal, orderBy.order) match
          case (false, Order.Ascending)  => InMemoryTable.absentLastAscending
          case (false, Order.Descending) => InMemoryTable.absentLastDescending
          case (true, Order.Ascending)   => InMemoryTable.absentFirstDescending
          case (true, Order.Descending)  => InMemoryTable.absentFirstAscending
        Ordering.by(extract(orderBy.field, _: T))(using valueOrdering)

      rows.sorted(using orderings.reduceLeft(_.orElse(_)))

  private inline def applyPosition(
      ordered: Vector[T],
      position: Position,
      ordering: Vector[OrderBy[FIELD]]
  ): Vector[T] =
    position match
      case Position.Offset(offset) => ordered.drop(offset.toInt)
      case Position.Keyset(Nil)    => ordered
      case Position.Keyset(values) =>
        summonFrom:
          case keysetField: KeysetField[FIELD, T] =>
            val cursorFields = CursorAdvance.cursorFieldsFor(ordering, keysetField.field)
            val anchor = ordered.indexWhere: row =>
              cursorFields
                .zip(values)
                .forall: (field, expected) =>
                  keysetField.fields
                    .get(field)
                    .map(_.encodedFromRow(row) == expected)
                    .getOrElse(keysetField.codec.toKeysetValue(keysetField.rowId(row)) == expected)
            if anchor < 0 then ordered else ordered.drop(anchor + 1)

          case _ => ordered

private object InMemoryTable:

  // ADR 0001: Absent (None) sorts after non-Absent (Some) in canonical forward order, regardless of direction.
  // Only the Some/Some comparison flips between asc and desc.
  val absentLastAscending: Ordering[Option[String]] =
    Ordering.fromLessThan: (lhs, rhs) =>
      (lhs, rhs) match
        case (None, None)       => false
        case (None, Some(_))    => false
        case (Some(_), None)    => true
        case (Some(l), Some(r)) => l < r

  val absentLastDescending: Ordering[Option[String]] =
    Ordering.fromLessThan: (lhs, rhs) =>
      (lhs, rhs) match
        case (None, None)       => false
        case (None, Some(_))    => false
        case (Some(_), None)    => true
        case (Some(l), Some(r)) => l > r

  // ADR 0003: backward traversal reverses both order and Absent placement, so the reverse
  // seek crosses the Some/Absent boundary in the same canonical sequence walked in reverse.
  val absentFirstAscending: Ordering[Option[String]] =
    Ordering.fromLessThan: (lhs, rhs) =>
      (lhs, rhs) match
        case (None, None)       => false
        case (None, Some(_))    => true
        case (Some(_), None)    => false
        case (Some(l), Some(r)) => l < r

  val absentFirstDescending: Ordering[Option[String]] =
    Ordering.fromLessThan: (lhs, rhs) =>
      (lhs, rhs) match
        case (None, None)       => false
        case (None, Some(_))    => true
        case (Some(_), None)    => false
        case (Some(l), Some(r)) => l > r
