package folio

import scala.annotation.{ implicitNotFound, targetName }

/** Enables keyset pagination by both designating the unique field within `FIELD` and extracting its value from a row of
  * type `T`. Provide one alongside your [[FieldSchema]] to opt into keyset; omit it to fall back to offset-only
  * pagination.
  *
  * The unique-field's value type is captured as a type member [[ID]] and inferred from the row extractor at
  * construction. A [[CursorValueCodec]] for [[ID]] is required so the cursor can serialize the keyset anchor.
  *
  * Use [[withField]] to register additional non-unique sort fields for keyset pagination. Each registered field has a
  * typed extractor and a [[CursorValueCodec]] so its row value can be encoded into the cursor anchor. The
  * `T => Option[V]` overload marks the field as absentable: a missing row value encodes as [[KeysetValue.Absent]] and
  * the decoder accepts the same in that slot.
  */
@implicitNotFound(
  "Keyset pagination needs a `given KeysetField[${FIELD}, ${T}]`. Use `KeysetField.uniqueBy(idField, _.id)` to provide one, or omit it for offset-only pagination."
)
trait KeysetField[FIELD, T]:
  type ID
  def field: FIELD
  def rowId(row: T): ID
  def absentableFields: Set[FIELD]

  @targetName("withRequiredField")
  def withField[V](field: FIELD, extract: T => V)(using CursorValueCodec[V]): KeysetField.Aux[FIELD, T, ID]

  @targetName("withAbsentableField")
  def withField[V](field: FIELD, extract: T => Option[V])(using CursorValueCodec[V]): KeysetField.Aux[FIELD, T, ID]

  private[folio] def codec: CursorValueCodec[ID]
  private[folio] def fields: Map[FIELD, FieldExtractor[T]]

object KeysetField:
  type Aux[FIELD, T, ID0] = KeysetField[FIELD, T] { type ID = ID0 }

  def uniqueBy[FIELD, T, ID0](idField: FIELD, extract: T => ID0)(using
      idCodec: CursorValueCodec[ID0]
  ): Aux[FIELD, T, ID0] =
    make(idField, extract, idCodec, Map(idField -> FieldExtractor.required(extract)(using idCodec)))

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
      def absentableFields: Set[FIELD] =
        registeredFields
          .collect:
            case (registeredField, extractor) if extractor.isAbsentable => registeredField
          .toSet

      @targetName("withRequiredField")
      def withField[V](field: FIELD, extract: T => V)(using fieldCodec: CursorValueCodec[V]): Aux[FIELD, T, ID] =
        make(idField, extractId, idCodec, registeredFields.updated(field, FieldExtractor.required(extract)))

      @targetName("withAbsentableField")
      def withField[V](field: FIELD, extract: T => Option[V])(using
          fieldCodec: CursorValueCodec[V]
      ): Aux[FIELD, T, ID] =
        make(idField, extractId, idCodec, registeredFields.updated(field, FieldExtractor.absentable(extract)))

      private[folio] def codec: CursorValueCodec[ID] = idCodec
      private[folio] def fields: Map[FIELD, FieldExtractor[T]] = registeredFields

private[folio] trait FieldExtractor[T]:
  def encodedFromRow(row: T): KeysetValue
  def isAbsentable: Boolean

private[folio] object FieldExtractor:
  def required[T, V](extractFn: T => V)(using codecForValue: CursorValueCodec[V]): FieldExtractor[T] =
    new FieldExtractor[T]:
      def encodedFromRow(row: T): KeysetValue = codecForValue.toKeysetValue(extractFn(row))
      def isAbsentable: Boolean = false

  def absentable[T, V](extractFn: T => Option[V])(using codecForValue: CursorValueCodec[V]): FieldExtractor[T] =
    new FieldExtractor[T]:
      def encodedFromRow(row: T): KeysetValue =
        extractFn(row).map(codecForValue.toKeysetValue).getOrElse(KeysetValue.Absent)
      def isAbsentable: Boolean = true
