package folio

import scala.annotation.implicitNotFound

@implicitNotFound(
  "Keyset pagination needs a `given RowId[${T}]`. Provide one alongside your `given IdField[FIELD]`."
)
trait RowId[T]:
  def apply(row: T): Long

object RowId:
  def apply[T](extract: T => Long): RowId[T] = (row: T) => extract(row)
