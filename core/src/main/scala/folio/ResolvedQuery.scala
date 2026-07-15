package folio

/** Query handed to a driver after the cursor has been decoded.
  *
  * `ordering` always describes the canonical (forward) ordering — the orders the user requested. Drivers translate
  * `direction` themselves rather than receiving pre-flipped ordering.
  *
  * @param limit
  *   The page size the caller requested — the number of rows [[Page.withPagination]] will ultimately return. Drivers
  *   must fetch [[fetchLimit]] rows (one more than this), not `limit`, so a further page can be detected.
  * @param direction
  *   When `Direction.Backward`, drivers performing a keyset seek must reverse both order and nulls placement (Absent
  *   first) so the reverse seek matches the canonical forward sequence walked in reverse. For offset `position` the
  *   offset is absolute, so direction is a no-op for those drivers. See ADR 0003.
  */
case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    ordering: Vector[OrderBy[FIELD]],
    limit: Limit,
    position: Position,
    direction: Direction
):

  /** The number of rows the driver should fetch (page `limit` plus one), used to detect whether a further page exists.
    * The extra row is dropped before the page is assembled.
    */
  def fetchLimit: Limit = limit.fetchLimit
