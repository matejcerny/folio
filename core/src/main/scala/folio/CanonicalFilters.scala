package folio

import folio.CursorBytes.*

import scala.math.Ordering.Implicits.seqOrdering

/** The single canonical view of a query's filter set, shared by cursor fingerprinting and by driver modules that render
  * predicates.
  *
  * `Query.filters` is a `Set`, so iteration order is an implementation detail of the set. Both consumers need a total
  * order over the same filters for the same reason: a fingerprint that depended on iteration order would declare
  * cursors stale at random, and SQL whose predicate order drifted would defeat statement caching and make SQL-shape
  * assertions untestable. Ordering here once keeps both in step.
  *
  * The order is field name, then predicate tag, then the unsigned lexicographic comparison of the encoded value bytes.
  * Encoded bytes rather than raw values, because the encoded value is what a filter is identified by (see
  * [[FilterBy.ExactMatch]]) and it is comparable across value types without inventing a cross-type value ordering.
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

  /** The filter contribution to the stale-cursor fingerprint: an empty string when there are no filters, otherwise the
    * hex rendering of the canonical filter bytes.
    *
    * Every entry is self-delimiting — a length-delimited field name, a predicate tag, then the tagged field value — so
    * no field name or string value can imitate an entry boundary. Hex keeps the result free of the delimiters the
    * surrounding fingerprint string uses, and an empty filter set contributes nothing, which leaves unfiltered
    * fingerprints byte-identical to a folio without filters.
    *
    * `TimestampV` keeps the offset the caller supplied instead of normalising to UTC. That matches
    * `OffsetDateTime.equals` and therefore filter identity: the same instant written at a different offset is a
    * different filter, renders a different bind, and invalidates outstanding cursors.
    */
  def fingerprintPart[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): String =
    hex(sorted(filters).flatMap(entryBytes))

  private def entryBytes[FIELD: FieldSchema](filter: FilterBy[FIELD]): EncodedBytes =
    stringBytes(filter.field.name) ++ byte(predicateTag(filter)) ++ fieldValueBytes(filter.encodedValue)

  private def hex(bytes: EncodedBytes): String =
    bytes.map(byteValue => f"${byteValue & 0xff}%02x").mkString
