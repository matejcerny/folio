package folio

/** Query handed to a driver after the cursor has been decoded.
  *
  * `ordering` always describes the canonical (forward) ordering the user requested; drivers apply `direction`
  * themselves.
  *
  * @param limit
  *   The page size the caller requested. Drivers must fetch [[fetchLimit]] rows, not `limit`, to detect a further page.
  * @param direction
  *   On `Direction.Backward` a keyset seek must reverse both order and nulls placement (Absent first) so it matches the
  *   forward sequence walked in reverse. Offsets are absolute, so direction is a no-op there. See ADR 0003.
  */
case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    ordering: Vector[OrderBy[FIELD]],
    limit: Limit,
    position: Position,
    direction: Direction
):

  /** Rows the driver should fetch (`limit` plus one) to detect a further page. The extra row is dropped. */
  def fetchLimit: Limit = limit.fetchLimit
