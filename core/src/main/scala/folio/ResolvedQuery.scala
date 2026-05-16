package folio

import scala.collection.immutable.ListSet

case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    sortBys: ListSet[SortBy[FIELD]],
    limit: Limit,
    position: Position
)
