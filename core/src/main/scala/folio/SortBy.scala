package folio

import scala.collection.immutable.ListSet

case class SortBy[FIELD: FieldSchema](order: Order, field: FIELD)

extension [FIELD: FieldSchema](field: FIELD)
  def ascending: SortBy[FIELD] = SortBy(Order.Ascending, field)
  def descending: SortBy[FIELD] = SortBy(Order.Descending, field)
