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

import java.io.ByteArrayOutputStream
import java.util.Base64

object CursorTestKit:

  /** Returns the 4 hash bytes from any encoded cursor for the given query (positions 1..5 of the binary frame). */
  inline def hashBytes[FIELD: FieldSchema](query: Query[FIELD])(using CursorCodec): Array[Byte] =
    val baseline = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), query)
    val raw = Base64.getUrlDecoder.decode(baseline.value)
    raw.slice(1, 5)

  def buildCursor(flags: Byte, hash: Array[Byte], payload: Array[Byte]): Cursor =
    val output = ByteArrayOutputStream()
    output.write(flags.toInt)
    output.write(hash)
    output.write(payload)
    Cursor(Base64.getUrlEncoder.withoutPadding.encodeToString(output.toByteArray))

  /** Parses space-separated hex pairs into a byte array. e.g. "02 03 ff" -> Array(2, 3, -1). */
  def hex(text: String): Array[Byte] =
    text.split("\\s+").filter(_.nonEmpty).map(pair => Integer.parseInt(pair, 16).toByte)

  def concat(parts: Array[Byte]*): Array[Byte] =
    val output = ByteArrayOutputStream()
    parts.foreach(output.write)
    output.toByteArray
