package folio

sealed trait Offset:
  def value: Long

object Offset:
  /** Keyset pagination: tracks the id of the last seen row. */
  case class LastId(value: Long) extends Offset

  /** Offset-based pagination: tracks an absolute row offset. */
  case class Incremental(value: Long) extends Offset:
    def next(limit: Limit): Incremental = Incremental(value + limit.value)

  object Incremental:
    val First: Incremental = Incremental(0L)
