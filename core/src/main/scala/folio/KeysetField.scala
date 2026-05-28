package folio

import scala.annotation.implicitNotFound

/** Enables keyset pagination by both designating the id field within `FIELD` and extracting the id from a row of type
  * `T`. Provide one alongside your [[FieldSchema]] to opt into keyset; omit it to fall back to offset-only pagination.
  *
  * The id type is captured as a type member [[ID]] and inferred from the row extractor at construction. A
  * [[CursorValueCodec]] for [[ID]] is required so the cursor can serialize the keyset anchor.
  */
@implicitNotFound(
  "Keyset pagination needs a `given KeysetField[${FIELD}, ${T}]`. Use `KeysetField(idField, _.id)` to provide one, or omit it for offset-only pagination."
)
trait KeysetField[FIELD, T]:
  type ID
  def field: FIELD
  def rowId(row: T): ID
  private[folio] def codec: CursorValueCodec[ID]

object KeysetField:
  type Aux[FIELD, T, ID0] = KeysetField[FIELD, T] { type ID = ID0 }

  def apply[FIELD, T, ID0](idField: FIELD, extract: T => ID0)(using
      idCodec: CursorValueCodec[ID0]
  ): Aux[FIELD, T, ID0] =
    new KeysetField[FIELD, T]:
      type ID = ID0
      def field: FIELD = idField
      def rowId(row: T): ID = extract(row)
      private[folio] def codec: CursorValueCodec[ID] = idCodec
