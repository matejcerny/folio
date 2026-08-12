package folio

import folio.CursorBytes.*

import scala.math.Ordering.Implicits.seqOrdering

/** The single canonical view of a query's filter set, shared by cursor fingerprinting and by driver modules that render
  * predicates.
  *
  * `Query.filters` is a `Set`, and both consumers need a stable order: a fingerprint following iteration order would
  * declare cursors stale at random, and drifting predicate order would defeat statement caching.
  *
  * The order is field name, then predicate tag, then the unsigned lexicographic comparison of the encoded value bytes.
  * Encoded bytes because that is what identifies a filter ([[FilterBy.ExactMatch]]) and they compare across value types
  * without a cross-type value ordering.
  */
private[folio] object CanonicalFilters:

  /** Predicate tags. Distinct from the [[FieldValue]] tag space: this one names the operator, not the value. */
  private val tagExactMatch: Byte = 0x01

  private def predicateTag(filter: FilterBy[?]): Byte = filter match
    case _: FilterBy.ExactMatch[?, ?] => tagExactMatch

  /** Each byte widened to its unsigned value, so the tuple ordering compares value bytes unsigned rather than as signed
    * `Byte`s.
    */
  private def sortKey[FIELD: FieldSchema](filter: FilterBy[FIELD]): (String, Byte, Vector[Int]) =
    (filter.field.name, predicateTag(filter), fieldValueBytes(filter.encodedValue).map(_ & 0xff))

  /** The filters of a query in canonical order. */
  def sorted[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): Vector[FilterBy[FIELD]] =
    filters.toVector.sortBy(sortKey)

  /** The filter contribution to the stale-cursor fingerprint: empty when there are no filters, otherwise hex-rendered
    * canonical filter bytes.
    *
    * Every entry is self-delimiting — length-delimited field name, predicate tag, tagged field value — so no name or
    * string value can imitate a boundary. Hex avoids the delimiters the surrounding fingerprint uses.
    *
    * `TimestampV` keeps the caller's offset rather than normalising to UTC, matching `OffsetDateTime.equals`: the same
    * instant at a different offset is a different filter and invalidates outstanding cursors.
    */
  def fingerprintPart[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): String =
    hex(sorted(filters).flatMap(entryBytes))

  private def entryBytes[FIELD: FieldSchema](filter: FilterBy[FIELD]): EncodedBytes =
    stringBytes(filter.field.name) ++ byte(predicateTag(filter)) ++ fieldValueBytes(filter.encodedValue)

  private def hex(bytes: EncodedBytes): String =
    bytes.map(byteValue => f"${byteValue & 0xff}%02x").mkString
