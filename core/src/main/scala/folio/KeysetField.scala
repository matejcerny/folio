package folio

import scala.annotation.{ implicitNotFound, targetName }

/** Enables keyset pagination by both designating the unique field within `FIELD` and extracting its value from a row of
  * type `T`. Provide one alongside your [[FieldSchema]] to opt into keyset; omit it to fall back to offset-only
  * pagination.
  *
  * The unique field's value type is the type member [[ID]], inferred from the row extractor; it needs a
  * [[FieldValueCodec]] so the cursor can serialize the anchor.
  *
  * Use [[withField]] to register additional non-unique order fields, each with a typed extractor and a
  * [[FieldValueCodec]]. The `T => Option[V]` overload marks the field absentable: a missing value encodes as
  * [[AnchorValue.Absent]] and the decoder accepts the same in that slot.
  *
  * Neither [[withField]] overload may re-register the unique field, so one extractor serves the tiebreaker and it can
  * never become absentable. An attempt throws `IllegalArgumentException` — the field value is only known at runtime, so
  * unlike the `Option`-typed `uniqueBy` this cannot be rejected at compile time (ADR 0002).
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
  def withField[V](field: FIELD, extract: T => V)(using FieldValueCodec[V]): KeysetField.Aux[FIELD, T, ID]

  @targetName("withAbsentableField")
  def withField[V](field: FIELD, extract: T => Option[V])(using FieldValueCodec[V]): KeysetField.Aux[FIELD, T, ID]

  private[folio] def codec: FieldValueCodec[ID]
  private[folio] def fields: Map[FIELD, FieldExtractor[T]]

object KeysetField:
  type Aux[FIELD, T, ID0] = KeysetField[FIELD, T] { type ID = ID0 }

  private val uniqueFieldReregistration = "The unique field cannot be re-registered with withField"

  def uniqueBy[FIELD, T, ID0](idField: FIELD, extract: T => ID0)(using
      idCodec: FieldValueCodec[ID0]
  ): Aux[FIELD, T, ID0] =
    make(idField, extract, idCodec, Map(idField -> FieldExtractor.required(extract)(using idCodec)))

  private def make[FIELD, T, ID0](
      idField: FIELD,
      extractId: T => ID0,
      idCodec: FieldValueCodec[ID0],
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
      def withField[V](field: FIELD, extract: T => V)(using fieldCodec: FieldValueCodec[V]): Aux[FIELD, T, ID] =
        register(field, FieldExtractor.required(extract))

      @targetName("withAbsentableField")
      def withField[V](field: FIELD, extract: T => Option[V])(using
          fieldCodec: FieldValueCodec[V]
      ): Aux[FIELD, T, ID] =
        register(field, FieldExtractor.absentable(extract))

      /** The one registration path both overloads take, so neither can overwrite the unique field's extractor —
        * `CursorAdvance` would encode anchors with the replacement while the driver kept treating it as the tiebreaker.
        */
      private def register(field: FIELD, extractor: FieldExtractor[T]): Aux[FIELD, T, ID] =
        if field == idField then throw IllegalArgumentException(uniqueFieldReregistration)
        else make(idField, extractId, idCodec, registeredFields.updated(field, extractor))

      private[folio] def codec: FieldValueCodec[ID] = idCodec
      private[folio] def fields: Map[FIELD, FieldExtractor[T]] = registeredFields

private[folio] trait FieldExtractor[T]:
  def encodedFromRow(row: T): AnchorValue
  def isAbsentable: Boolean

  /** Whether a decoded anchor slot carries a value this field's codec can consume. [[AnchorValue.Absent]] always passes
    * (absentability is validated separately); an unrecognised [[FieldValue]] variant is rejected, so a type-forged
    * cursor fails as a `CursorDecodingError` instead of reaching the driver as a mismatched bind.
    */
  def acceptsVariant(value: AnchorValue): Boolean

private[folio] object FieldExtractor:
  def required[T, V](extractFn: T => V)(using codecForValue: FieldValueCodec[V]): FieldExtractor[T] =
    new FieldExtractor[T]:
      def encodedFromRow(row: T): AnchorValue = codecForValue.toFieldValue(extractFn(row)).present
      def isAbsentable: Boolean = false
      def acceptsVariant(value: AnchorValue): Boolean = accepts(codecForValue, value)

  def absentable[T, V](extractFn: T => Option[V])(using codecForValue: FieldValueCodec[V]): FieldExtractor[T] =
    new FieldExtractor[T]:
      def encodedFromRow(row: T): AnchorValue =
        extractFn(row).map(codecForValue.toFieldValue(_).present).getOrElse(AnchorValue.Absent)
      def isAbsentable: Boolean = true
      def acceptsVariant(value: AnchorValue): Boolean = accepts(codecForValue, value)

  private def accepts[V](codec: FieldValueCodec[V], value: AnchorValue): Boolean =
    value match
      case AnchorValue.Absent                 => true
      case AnchorValue.Present(concreteValue) => codec.fromFieldValue(concreteValue).isDefined
