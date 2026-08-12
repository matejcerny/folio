package folio

/** A predicate applied before pagination. Every filter is ANDed with the others in `Query.filters`.
  *
  * Carries only the field it constrains and its value as a [[FieldValue]]. The value type is existential here, so
  * drivers bind by matching on the variant.
  */
sealed trait FilterBy[FIELD]:
  def field: FIELD
  def encodedValue: FieldValue

object FilterBy:

  /** Exact equality: `field = value`. `V` is any type with a [[FieldValueCodec]] — folio ships instances for `Int`,
    * `Long`, `String`, and `OffsetDateTime`. Pairing a field with the wrong value type is the caller's responsibility;
    * it surfaces at the driver/database boundary.
    *
    * Equality and hashing are over `(field, encodedValue)`, so a filter is identified by the predicate it renders.
    * `ExactMatch(Id, 1)` and `ExactMatch(Id, 1L)` stay two predicates in a `Query.filters` set: their raw values are
    * `==` under boxed numeric equality, but they bind different column types. Default case-class equality would
    * collapse them into whichever the set kept first, making the fingerprint depend on insertion order.
    */
  case class ExactMatch[FIELD, V](field: FIELD, value: V)(using codec: FieldValueCodec[V]) extends FilterBy[FIELD]:
    override val encodedValue: FieldValue = codec.toFieldValue(value)

    override def equals(other: Any): Boolean = other match
      case that: ExactMatch[?, ?] => field == that.field && encodedValue == that.encodedValue
      case _                      => false

    override def hashCode: Int = (field, encodedValue).hashCode
