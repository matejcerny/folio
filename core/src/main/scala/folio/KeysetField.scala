package folio

import scala.annotation.implicitNotFound

/** Enables keyset pagination by both designating the id field within `FIELD` and extracting the id from a row of type
  * `T`. Provide one alongside your [[FieldSchema]] to opt into keyset; omit it to fall back to offset-only pagination.
  *
  * The id type is captured as a type member [[ID]] and inferred from the row extractor at construction. A
  * [[CursorValueCodec]] for [[ID]] is required so the cursor can serialize the keyset anchor.
  *
  * Use [[withField]] to register additional non-id sort fields for keyset pagination. Each registered field has a typed
  * extractor and a [[CursorValueCodec]] so its row value can be encoded into the cursor anchor.
  */
@implicitNotFound(
  "Keyset pagination needs a `given KeysetField[${FIELD}, ${T}]`. Use `KeysetField(idField, _.id)` to provide one, or omit it for offset-only pagination."
)
trait KeysetField[FIELD, T]:
  type ID
  def field: FIELD
  def rowId(row: T): ID
  private[folio] def codec: CursorValueCodec[ID]
  private[folio] def fields: Map[FIELD, FieldExtractor[T]]
  def withField[V](field: FIELD, extract: T => V)(using CursorValueCodec[V]): KeysetField.Aux[FIELD, T, ID]

object KeysetField:
  type Aux[FIELD, T, ID0] = KeysetField[FIELD, T] { type ID = ID0 }

  def apply[FIELD, T, ID0](idField: FIELD, extract: T => ID0)(using
      idCodec: CursorValueCodec[ID0]
  ): Aux[FIELD, T, ID0] =
    val idExtractor = FieldExtractor.of(extract)(using idCodec)
    make(idField, extract, idCodec, Map(idField -> idExtractor))

  private def make[FIELD, T, ID0](
      idField: FIELD,
      extractId: T => ID0,
      idCodec: CursorValueCodec[ID0],
      registeredFields: Map[FIELD, FieldExtractor[T]]
  ): Aux[FIELD, T, ID0] =
    new KeysetField[FIELD, T]:
      type ID = ID0
      def field: FIELD = idField
      def rowId(row: T): ID = extractId(row)
      private[folio] def codec: CursorValueCodec[ID] = idCodec
      private[folio] def fields: Map[FIELD, FieldExtractor[T]] = registeredFields
      def withField[V](field: FIELD, extract: T => V)(using
          fieldCodec: CursorValueCodec[V]
      ): Aux[FIELD, T, ID] =
        make(idField, extractId, idCodec, registeredFields.updated(field, FieldExtractor.of(extract)))

private[folio] trait FieldExtractor[T]:
  type V
  def extract(row: T): V
  def codec: CursorValueCodec[V]
  final def encodedFromRow(row: T): KeysetValue = codec.toKeysetValue(extract(row))

private[folio] object FieldExtractor:
  def of[T, V0](extractFn: T => V0)(using codecForValue: CursorValueCodec[V0]): FieldExtractor[T] =
    new FieldExtractor[T]:
      type V = V0
      def extract(row: T): V = extractFn(row)
      def codec: CursorValueCodec[V] = codecForValue
