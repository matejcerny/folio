package folio

import java.time.OffsetDateTime

import scala.annotation.implicitNotFound

import folio.FolioError.CursorDecodingError

@implicitNotFound(
  "Keyset pagination needs a `given CursorValueCodec[${V}]` for the id type. Folio ships instances for Int, Long, String, and OffsetDateTime."
)
trait CursorValueCodec[V]:
  def toKeysetValue(value: V): KeysetValue
  def fromKeysetValue(keysetValue: KeysetValue): Either[CursorDecodingError, V]

object CursorValueCodec:

  def apply[V](using codec: CursorValueCodec[V]): CursorValueCodec[V] = codec

  private val typeMismatch: CursorDecodingError =
    CursorDecodingError.MalformedCursor("keyset value type mismatch")

  given CursorValueCodec[Long] with
    def toKeysetValue(value: Long): KeysetValue = KeysetValue.LongV(value)
    def fromKeysetValue(keysetValue: KeysetValue): Either[CursorDecodingError, Long] =
      keysetValue match
        case KeysetValue.LongV(value) => Right(value)
        case _                        => Left(typeMismatch)

  given CursorValueCodec[Int] with
    def toKeysetValue(value: Int): KeysetValue = KeysetValue.IntV(value)
    def fromKeysetValue(keysetValue: KeysetValue): Either[CursorDecodingError, Int] =
      keysetValue match
        case KeysetValue.IntV(value) => Right(value)
        case _                       => Left(typeMismatch)

  given CursorValueCodec[String] with
    def toKeysetValue(value: String): KeysetValue = KeysetValue.StringV(value)
    def fromKeysetValue(keysetValue: KeysetValue): Either[CursorDecodingError, String] =
      keysetValue match
        case KeysetValue.StringV(value) => Right(value)
        case _                          => Left(typeMismatch)

  given CursorValueCodec[OffsetDateTime] with
    def toKeysetValue(value: OffsetDateTime): KeysetValue = KeysetValue.TimestampV(value)
    def fromKeysetValue(keysetValue: KeysetValue): Either[CursorDecodingError, OffsetDateTime] =
      keysetValue match
        case KeysetValue.TimestampV(value) => Right(value)
        case _                             => Left(typeMismatch)
