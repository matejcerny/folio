package folio

import scala.collection.immutable.ListSet

case class Query[FIELD](
    filters: Set[FilterBy[FIELD]],
    cursor: Option[Cursor],
    limit: Option[Limit],
    sortBys: ListSet[SortBy[FIELD]]
)

object Query:
  def empty[FIELD]: Query[FIELD] = Query(Set.empty, None, None, ListSet.empty)
