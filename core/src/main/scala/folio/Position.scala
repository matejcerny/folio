package folio

import scala.compiletime.summonFrom

sealed trait Position(val asString: String)

object Position:

  /** Keyset pagination: tracks the keyset anchor as a list of typed values, one per cursor field. */
  case class Keyset(values: List[KeysetValue]) extends Position("keyset")
  object Keyset:
    val First: Keyset = Keyset(Nil)

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
    *     - All sort fields registered (via `KeysetField.apply` / `.withField`) -> [[Keyset]] (O(1) seek)
    *     - Any sort field not registered -> [[Offset]] (offset fallback)
    *     - No sort specified -> [[Keyset]] with default ascending id sort (materialized into [[ResolvedQuery.sortBys]]
    *       by [[Page.withPagination]])
    *   - When [[KeysetField]] is not available:
    *     - Always [[Offset]] (offset)
    */
  inline def fromQuery[FIELD: FieldSchema](query: Query[FIELD]): Position =
    summonFrom:
      case keysetField: KeysetField[FIELD, ?] => fromQueryKeyset(query, keysetField)
      case _                                  => Offset.First

  private[folio] def fromQueryKeyset[FIELD](
      query: Query[FIELD],
      keysetField: KeysetField[FIELD, ?]
  ): Position =
    val sortFields = query.sortBys.toList.map(_.field)
    if sortFields.isEmpty then Keyset.First
    else if sortFields.forall(keysetField.fields.contains) then Keyset.First
    else Offset.First
