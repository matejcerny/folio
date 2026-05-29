package folio

import scala.collection.immutable.ListSet

/** Query handed to a driver after the cursor has been decoded.
  *
  * `sortBys` always describes the canonical (forward) ordering — the orders the user requested. Drivers translate
  * `direction` themselves rather than receiving pre-flipped sortBys.
  *
  * @param direction
  *   When `Direction.Backward`, drivers performing a keyset seek must reverse both sort order and nulls placement
  *   (Absent first) so the reverse seek matches the canonical forward sequence walked in reverse. For offset `position`
  *   the offset is absolute, so direction is a no-op for those drivers. See ADR 0003.
  */
case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    sortBys: ListSet[SortBy[FIELD]],
    limit: Limit,
    position: Position,
    direction: Direction
)
