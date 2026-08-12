package folio

import scala.compiletime.summonFrom

final class InMemoryTable[FIELD: FieldSchema, T](rows: Vector[T], extract: (FIELD, T) => Option[String]):

  inline def fetch(resolved: ResolvedQuery[FIELD]): Seq[T] =
    val filtered = rows.filter(matches(_, resolved.filters))
    // Backward reverses ordering and Absent placement (ADR 0003), but only for keyset seeks: an offset position already
    // encodes the absolute slot, so direction is a no-op there.
    val reverseTraversal = resolved.direction == Direction.Backward && resolved.position.isInstanceOf[Position.Keyset]
    val ordered = applyOrdering(filtered, resolved.ordering, reverseTraversal)
    val skipped = applyPosition(ordered, resolved.position, resolved.ordering)
    skipped.take(resolved.fetchLimit.value)

  private def matches(row: T, filters: Set[FilterBy[FIELD]]): Boolean =
    // Every column here is text, so only a StringV filter can match — as against a real text column.
    filters.forall: filter =>
      filter.encodedValue match
        case FieldValue.StringV(value) => extract(filter.field, row).contains(value)
        case _                         => false

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
                    .getOrElse(keysetField.codec.toFieldValue(keysetField.rowId(row)).present == expected)
            if anchor < 0 then ordered else ordered.drop(anchor + 1)

          case _ => ordered

private object InMemoryTable:

  // ADR 0001: Absent sorts after Some in canonical forward order regardless of direction; only Some/Some flips.
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

  // ADR 0003: backward reverses both order and Absent placement, so the seek crosses the Some/Absent boundary in the
  // canonical sequence walked in reverse.
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
