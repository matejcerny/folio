package folio

import scala.collection.immutable.ListSet
import scala.compiletime.summonFrom

final class InMemoryTable[FIELD: FieldSchema, T](rows: Vector[T], extract: (FIELD, T) => Option[String]):

  inline def fetch(resolved: ResolvedQuery[FIELD]): Seq[T] =
    val filtered = rows.filter(matches(_, resolved.filters))
    // Backward traversal reverses sort and Absent placement (ADR 0003), but only for keyset seeks:
    // an offset position already encodes the absolute slot of the previous page, so direction is a no-op.
    val reverseTraversal = resolved.direction == Direction.Backward && resolved.position.isInstanceOf[Position.Keyset]
    val sorted = applySort(filtered, resolved.sortBys, reverseTraversal)
    val skipped = applyPosition(sorted, resolved.position, resolved.sortBys)
    skipped.take(resolved.limit.value)

  private def matches(row: T, filters: Set[FilterBy[FIELD]]): Boolean =
    filters.forall:
      case FilterBy.ExactMatch(field, value) => extract(field, row).contains(value)

  private def applySort(rows: Vector[T], sortBys: ListSet[SortBy[FIELD]], reverseTraversal: Boolean): Vector[T] =
    if sortBys.isEmpty then rows
    else
      val orderings = sortBys.toList.map: sortBy =>
        val ordering = (reverseTraversal, sortBy.order) match
          case (false, Order.Ascending)  => InMemoryTable.absentLastAscending
          case (false, Order.Descending) => InMemoryTable.absentLastDescending
          case (true, Order.Ascending)   => InMemoryTable.absentFirstDescending
          case (true, Order.Descending)  => InMemoryTable.absentFirstAscending
        Ordering.by(extract(sortBy.field, _: T))(using ordering)

      rows.sorted(using orderings.reduceLeft(_.orElse(_)))

  private inline def applyPosition(
      sorted: Vector[T],
      position: Position,
      sortBys: ListSet[SortBy[FIELD]]
  ): Vector[T] =
    position match
      case Position.Offset(offset) => sorted.drop(offset.toInt)
      case Position.Keyset(Nil)    => sorted
      case Position.Keyset(values) =>
        summonFrom:
          case keysetField: KeysetField[FIELD, T] =>
            val cursorFields = CursorAdvance.cursorFieldsFor(sortBys, keysetField.field)
            val anchor = sorted.indexWhere: row =>
              cursorFields
                .zip(values)
                .forall: (field, expected) =>
                  keysetField.fields
                    .get(field)
                    .map(_.encodedFromRow(row) == expected)
                    .getOrElse(keysetField.codec.toKeysetValue(keysetField.rowId(row)) == expected)
            if anchor < 0 then sorted else sorted.drop(anchor + 1)

          case _ => sorted

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

  // ADR 0003: backward traversal reverses both sort order and Absent placement, so the reverse
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
