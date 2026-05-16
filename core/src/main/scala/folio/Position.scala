package folio

import scala.compiletime.summonFrom

sealed trait Position

object Position:
  /** Keyset pagination: tracks the id of the last seen row. */
  case class Keyset(lastId: Option[Long]) extends Position
  object Keyset:
    val First = Keyset(lastId = None)

  /** Offset-based pagination: tracks an absolute row offset. */
  case class Offset(offset: Long) extends Position:
    def previous(limit: Limit): Offset = Offset(math.max(Offset.First.offset, offset - limit.value))
    def next(limit: Limit): Offset = Offset(offset + limit.value)

  object Offset:
    val First = Offset(0L)

  /** Picks the pagination strategy from a [[Query]]:
    *   - When [[KeysetField]] is available:
    *     - Primary sort field == id field -> [[Keyset]] (keyset, O(1) seek)
    *     - Other primary sort field -> [[Offset]] (offset)
    *     - No sort specified -> [[Keyset]] with default ascending id sort (materialized into [[ResolvedQuery.sortBys]]
    *       by [[Page.withPagination]])
    *   - When [[KeysetField]] is not available:
    *     - Always [[Offset]] (offset)
    */
  inline def fromQuery[FIELD: FieldSchema](query: Query[FIELD]): Position =
    summonFrom:
      case keysetField: KeysetField[FIELD, ?] => fromQueryKeyset(query, keysetField.field)
      case _                                  => Offset.First

  private def fromQueryKeyset[FIELD](query: Query[FIELD], idField: FIELD): Position =
    query.sortBys.headOption
      .map:
        case primary if primary.field == idField => Keyset.First
        case _                                   => Offset.First
      .getOrElse(Keyset.First)
