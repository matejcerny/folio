package folio

import scala.compiletime.summonFrom

sealed trait CursorPosition

object CursorPosition:
  case class Id(lastId: Option[Offset.LastId]) extends CursorPosition
  case class Incremental(offset: Offset.Incremental) extends CursorPosition

  /** Picks the pagination strategy from a [[Query]]:
    *   - When [[IdField]] is available:
    *     - Primary sort field == id field -> [[Id]] (keyset, O(1) seek)
    *     - Other primary sort field -> [[Incremental]] (offset)
    *     - No sort specified -> [[Id]] with default ascending id sort
    *   - When [[IdField]] is not available:
    *     - Always [[Incremental]] (offset)
    */
  inline def fromQuery[FIELD: FieldSchema](query: Query[FIELD]): CursorPosition =
    summonFrom:
      case idField: IdField[FIELD] => fromQueryKeyset(query, idField)
      case _                       => Incremental(Offset.Incremental.First)

  private def fromQueryKeyset[FIELD](query: Query[FIELD], idField: IdField[FIELD]): CursorPosition =
    query.sortBys.headOption
      .map:
        case primary if primary.field == idField.idField => Id(lastId = None)
        case _                                           => Incremental(Offset.Incremental.First)
      .getOrElse(Id(lastId = None))
