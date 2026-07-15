package folio

case class Query[FIELD](
    filters: Set[FilterBy[FIELD]] = Set.empty[FilterBy[FIELD]],
    ordering: Vector[OrderBy[FIELD]] = Vector.empty[OrderBy[FIELD]],
    limit: Limit,
    cursor: Option[Cursor] = None
):

  /** Replace the ordering with the given fields in priority order. Does not append or validate; validation runs at
    * execution boundaries.
    */
  def orderBy(first: OrderBy[FIELD], rest: OrderBy[FIELD]*): Query[FIELD] =
    copy(ordering = first +: rest.toVector)

object Query:
  def empty[FIELD]: Query[FIELD] = Query[FIELD](limit = Limit.Default)
