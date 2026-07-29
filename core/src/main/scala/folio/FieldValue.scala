package folio

import java.time.OffsetDateTime

/** A concrete field value folio knows how to carry across its own boundaries. Every variant is a present value; the
  * missing-value case belongs to [[AnchorValue.Absent]], which wraps this type rather than extending it.
  */
enum FieldValue:
  case IntV(value: Int)
  case LongV(value: Long)
  case StringV(value: String)
  case TimestampV(value: OffsetDateTime)
