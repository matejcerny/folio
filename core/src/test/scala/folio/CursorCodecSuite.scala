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

import folio.KeysetSyntax.keysetOf
import weaver.SimpleIOSuite
import TestFixtures.*

object CursorCodecSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Row] = KeysetField.uniqueBy(TestField.Id, _.id)

  private val realCodec: CursorCodec = summon[CursorCodec]

  pureTest("roundtrip encode/decode for simple query via real base64 codec"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Nil))
    val query = Query.empty[TestField]
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip encode/decode for query with special characters in filter"):
    val query = Query
      .empty[TestField]
      .copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "hello+world/foo=bar")))
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(42L))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("encoded cursor contains only base64url characters without padding"):
    val cursor = Cursor.encode(
      DecodedCursor(Direction.Forward, keysetOf(99L)),
      Query.empty[TestField]
    )
    val base64UrlPattern = "^[A-Za-z0-9_-]+$".r

    expect(clue(base64UrlPattern.findFirstIn(cursor.value)).isDefined) and
      expect(!clue(cursor.value).contains("="))

  pureTest("real codec decode rejects invalid base64 input"):
    val invalidCursor = Cursor("not valid base64 because of spaces")

    val result = realCodec.decode(invalidCursor)

    expect.sameL(FolioError.CursorDecodingError.MalformedCursor("not valid base64"), result)

  pureTest("roundtrip preserves cursor position with Long.MaxValue"):
    val decoded = DecodedCursor(Direction.Backward, keysetOf(Long.MaxValue))
    val query = Query.empty[TestField]
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("encode produces a compact frame for typical single-Long keyset"):
    val cursor = Cursor.encode(
      DecodedCursor(Direction.Forward, keysetOf(2L)),
      Query.empty[TestField]
    )
    val raw = java.util.Base64.getUrlDecoder.decode(cursor.value)

    // flags(1) + hash(4) + count-varint(1) + tag(1) + Long-zigzag-varint(1 for value=2) = 8 bytes
    expect.same(8, raw.length)
