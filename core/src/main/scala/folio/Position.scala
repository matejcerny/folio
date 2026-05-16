package folio

import scala.compiletime.summonFrom

sealed trait Position

object Position:
  /** Keyset pagination: tracks the id of the last seen row. */
  case class Keyset(lastId: Option[Long]) extends Position
  object Keyset:
    val First = Keyset(lastId = None)

  /** Offset-based pagination: tracks an absolute row offset. */
  case class Offset(offset: Long) extends Position
  object Offset:
    val First = Offset(0L)

  /** Picks the pagination strategy from a [[Query]]:
    *   - When [[IdField]] is available:
    *     - Primary sort field == id field -> [[Keyset]] (keyset, O(1) seek)
    *     - Other primary sort field -> [[Offset]] (offset)
    *     - No sort specified -> [[Keyset]] with default ascending id sort
    *   - When [[IdField]] is not available:
    *     - Always [[Offset]] (offset)
    */
  inline def fromQuery[FIELD: FieldSchema](query: Query[FIELD]): Position =
    summonFrom:
      case idField: IdField[FIELD] => fromQueryKeyset(query, idField)
      case _                       => Offset.First

  private def fromQueryKeyset[FIELD](query: Query[FIELD], idField: IdField[FIELD]): Position =
    query.sortBys.headOption
      .map:
        case primary if primary.field == idField.idField => Keyset.First
        case _                                           => Offset.First
      .getOrElse(Keyset.First)
