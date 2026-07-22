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
import java.time.ZoneOffset

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

import folio.FolioError.CursorDecodingError
import TestFixtures.*

object CursorValueCodecSuite extends SimpleIOSuite with Checkers:

  test("Long: round-trips arbitrary values"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      val codec = CursorValueCodec[Long]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("Long: maps onto LongV"):
    expect.same(KeysetValue.LongV(42L), CursorValueCodec[Long].toKeysetValue(42L))

  pureTest("Long: fromKeysetValue rejects variant mismatch"):
    val result = CursorValueCodec[Long].fromKeysetValue(KeysetValue.StringV("abc"))
    expect.sameL(CursorDecodingError.MalformedCursor("keyset value type mismatch"), result)

  test("Int: round-trips arbitrary values"):
    forall(Gen.choose(Int.MinValue, Int.MaxValue)): value =>
      val codec = CursorValueCodec[Int]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("Int: fromKeysetValue rejects variant mismatch"):
    val result = CursorValueCodec[Int].fromKeysetValue(KeysetValue.LongV(42L))
    expect.sameL(CursorDecodingError.MalformedCursor("keyset value type mismatch"), result)

  test("String: round-trips arbitrary values including former separators"):
    forall(Gen.asciiPrintableStr): value =>
      val codec = CursorValueCodec[String]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("String: fromKeysetValue rejects variant mismatch"):
    val result = CursorValueCodec[String].fromKeysetValue(KeysetValue.IntV(7))
    expect.sameL(CursorDecodingError.MalformedCursor("keyset value type mismatch"), result)

  pureTest("OffsetDateTime: round-trips an ISO-8601 timestamp"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    expect.sameR(timestamp, codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: round-trips with non-UTC offset and nanos"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 123_456_789, ZoneOffset.ofHours(-5))
    expect.sameR(timestamp, codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: fromKeysetValue rejects variant mismatch"):
    val result = CursorValueCodec[OffsetDateTime].fromKeysetValue(KeysetValue.StringV("not-a-date"))
    expect.sameL(CursorDecodingError.MalformedCursor("keyset value type mismatch"), result)
