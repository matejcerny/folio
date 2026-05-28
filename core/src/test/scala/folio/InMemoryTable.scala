package folio

import scala.collection.immutable.ListSet
import scala.compiletime.summonFrom

final class InMemoryTable[FIELD: FieldSchema, T](rows: Vector[T], extract: (FIELD, T) => String):

  inline def fetch(resolved: ResolvedQuery[FIELD]): Seq[T] =
    val filtered = rows.filter(matches(_, resolved.filters))
    val sorted = applySort(filtered, resolved.sortBys)
    val skipped = applyPosition(sorted, resolved.position)
    skipped.take(resolved.limit.value)

  private def matches(row: T, filters: Set[FilterBy[FIELD]]): Boolean =
    filters.forall:
      case FilterBy.ExactMatch(field, value) => extract(field, row) == value

  private def applySort(rows: Vector[T], sortBys: ListSet[SortBy[FIELD]]): Vector[T] =
    if sortBys.isEmpty then rows
    else
      val orderings = sortBys.toList.map: sortBy =>
        val base: Ordering[T] = Ordering.by(extract(sortBy.field, _))
        sortBy.order match
          case Order.Ascending  => base
          case Order.Descending => base.reverse
      val combined = orderings.reduceLeft(_.orElse(_))
      rows.sorted(using combined)

  private inline def applyPosition(sorted: Vector[T], position: Position): Vector[T] =
    position match
      case Position.Offset(offset)         => sorted.drop(offset.toInt)
      case Position.Keyset(Nil)            => sorted
      case Position.Keyset(encodedId :: _) =>
        summonFrom:
          case keysetField: KeysetField[FIELD, T] =>
            val anchor = sorted.indexWhere: row =>
              keysetField.codec.toKeysetValue(keysetField.rowId(row)) == encodedId
            if anchor < 0 then sorted else sorted.drop(anchor + 1)
          case _ => sorted
