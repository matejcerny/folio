package folio

import scala.annotation.tailrec

case class OrderBy[FIELD: FieldSchema](order: Order, field: FIELD)

object OrderBy:

  /** Reject orderings that mention the same field more than once (identical or contradictory orders). Reports the first
    * duplicate in ordering-priority order (left to right).
    */
  private[folio] def validateFields[FIELD: FieldSchema](
      ordering: Vector[OrderBy[FIELD]]
  ): Either[FolioError.InvalidQuery, Unit] =

    @tailrec
    def loop(index: Int, seen: Set[FIELD]): Either[FolioError.InvalidQuery, Unit] =
      if index >= ordering.length then Right(())
      else
        val field = ordering(index).field
        if seen.contains(field) then Left(FolioError.InvalidQuery(s"duplicate order field: ${field.name}"))
        else loop(index + 1, seen + field)

    loop(0, Set.empty)

extension [FIELD: FieldSchema](field: FIELD)
  def ascending: OrderBy[FIELD] = OrderBy(Order.Ascending, field)
  def descending: OrderBy[FIELD] = OrderBy(Order.Descending, field)
