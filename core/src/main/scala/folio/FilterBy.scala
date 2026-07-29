package folio

/** A predicate applied before pagination. Every filter is ANDed with the others in `Query.filters`.
  *
  * The sealed trait carries only what neutral code needs: the field it constrains and its value in folio's own currency
  * ([[FieldValue]]). The value type is existential at this level, so drivers bind by matching on the [[FieldValue]]
  * variant.
  */
sealed trait FilterBy[FIELD]:
  def field: FIELD
  def encodedValue: FieldValue

object FilterBy:

  /** Exact equality: `field = value`. `V` is any type with a [[FieldValueCodec]] — folio ships instances for `Int`,
    * `Long`, `String`, and `OffsetDateTime`. Pairing a field with the wrong value type is the caller's responsibility;
    * it surfaces at the driver/database boundary.
    *
    * Equality and hashing are over `(field, encodedValue)` rather than over the raw value, so a filter is identified by
    * the predicate it renders. `ExactMatch(Id, 1)` and `ExactMatch(Id, 1L)` therefore stay two predicates inside a
    * `Query.filters` set: their raw values are `==` under boxed numeric equality, yet they encode to different
    * [[FieldValue]] variants and bind different column types. The case-class equality would collapse them into
    * whichever one the set happened to keep first, which would make the cursor fingerprint depend on set insertion
    * order.
    */
  case class ExactMatch[FIELD, V](field: FIELD, value: V)(using codec: FieldValueCodec[V]) extends FilterBy[FIELD]:
    override val encodedValue: FieldValue = codec.toFieldValue(value)

    override def equals(other: Any): Boolean = other match
      case that: ExactMatch[?, ?] => field == that.field && encodedValue == that.encodedValue
      case _                      => false

    override def hashCode: Int = (field, encodedValue).hashCode
