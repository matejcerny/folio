package folio

import scala.collection.immutable.ListSet

case class Query[FIELD](
    filters: Set[FilterBy[FIELD]],
    sortBys: ListSet[SortBy[FIELD]],
    limit: Limit,
    cursor: Option[Cursor] = None
)

object Query:
  def empty[FIELD]: Query[FIELD] = Query(Set.empty, ListSet.empty, Limit.Default, None)
