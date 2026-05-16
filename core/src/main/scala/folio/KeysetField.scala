package folio

import scala.annotation.implicitNotFound

/** Enables keyset pagination by both designating the id field within `FIELD` and extracting the id `Long` from a row of
  * type `T`. Provide one alongside your [[FieldSchema]] to opt into keyset; omit it to fall back to offset-only
  * pagination.
  */
@implicitNotFound(
  "Keyset pagination needs a `given KeysetField[${FIELD}, ${T}]`. Use `KeysetField(idField, _.id)` to provide one, or omit it for offset-only pagination."
)
trait KeysetField[FIELD, T]:
  def field: FIELD
  def rowId(row: T): Long

object KeysetField:
  def apply[FIELD, T](idField: FIELD, extract: T => Long): KeysetField[FIELD, T] =
    new KeysetField[FIELD, T]:
      def field: FIELD = idField
      def rowId(row: T): Long = extract(row)
