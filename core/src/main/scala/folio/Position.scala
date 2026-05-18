package folio

import scala.compiletime.summonFrom

sealed trait Position(val asString: String)

object Position:
  /** Keyset pagination: tracks the id of the last seen row. */
  case class Keyset(lastId: Option[Long]) extends Position("keyset")
  object Keyset:
    val First = Keyset(lastId = None)

  /** Offset-based pagination: tracks an absolute non-negative row offset. */
  case class Offset private[folio] (offset: Long) extends Position("offset"):
    def previous(limit: Limit): Offset = Offset.unsafe(math.max(Offset.First.offset, offset - limit.value))
    def next(limit: Limit): Offset = Offset.unsafe(offset + limit.value)

  object Offset:
    val First: Offset = unsafe(0L)

    private def condition(offset: Long): Boolean = offset >= 0
    private def errorMessage(offset: Long): String = s"Offset must be non-negative, got $offset"

    def apply(offset: Long): Either[String, Offset] =
      Either.cond(condition(offset), new Offset(offset), errorMessage(offset))

    def unsafe(offset: Long): Offset =
      require(condition(offset), errorMessage(offset))
      new Offset(offset)

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
