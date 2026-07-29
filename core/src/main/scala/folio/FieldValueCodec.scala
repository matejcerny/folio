package folio

import java.time.OffsetDateTime

import scala.annotation.implicitNotFound

/** Maps a user-facing value type onto one of folio's [[FieldValue]] variants and back. `fromFieldValue` returns `None`
  * when the variant does not match this codec, leaving the caller to decide what a mismatch means in its context.
  */
@implicitNotFound(
  "folio needs a `given FieldValueCodec[${V}]` for values of this type. Folio ships instances for Int, Long, String, and OffsetDateTime."
)
trait FieldValueCodec[V]:
  def toFieldValue(value: V): FieldValue
  def fromFieldValue(fieldValue: FieldValue): Option[V]

object FieldValueCodec:

  def apply[V](using codec: FieldValueCodec[V]): FieldValueCodec[V] = codec

  given FieldValueCodec[Long] with
    def toFieldValue(value: Long): FieldValue = FieldValue.LongV(value)
    def fromFieldValue(fieldValue: FieldValue): Option[Long] =
      fieldValue match
        case FieldValue.LongV(value) => Some(value)
        case _                       => None

  given FieldValueCodec[Int] with
    def toFieldValue(value: Int): FieldValue = FieldValue.IntV(value)
    def fromFieldValue(fieldValue: FieldValue): Option[Int] =
      fieldValue match
        case FieldValue.IntV(value) => Some(value)
        case _                      => None

  given FieldValueCodec[String] with
    def toFieldValue(value: String): FieldValue = FieldValue.StringV(value)
    def fromFieldValue(fieldValue: FieldValue): Option[String] =
      fieldValue match
        case FieldValue.StringV(value) => Some(value)
        case _                         => None

  given FieldValueCodec[OffsetDateTime] with
    def toFieldValue(value: OffsetDateTime): FieldValue = FieldValue.TimestampV(value)
    def fromFieldValue(fieldValue: FieldValue): Option[OffsetDateTime] =
      fieldValue match
        case FieldValue.TimestampV(value) => Some(value)
        case _                            => None
