package folio

import scala.collection.immutable.ListSet

case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    limit: Limit,
    sortBys: ListSet[SortBy[FIELD]],
    position: Position
)
