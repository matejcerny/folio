/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
