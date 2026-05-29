package folio

import java.time.OffsetDateTime

enum KeysetValue:
  case IntV(value: Int)
  case LongV(value: Long)
  case StringV(value: String)
  case TimestampV(value: OffsetDateTime)
  case Absent
