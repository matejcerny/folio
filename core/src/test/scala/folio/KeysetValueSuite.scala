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

import weaver.SimpleIOSuite

object KeysetValueSuite extends SimpleIOSuite:

  pureTest("Absent is a singleton case object value"):
    val value: KeysetValue = KeysetValue.Absent
    expect.same(KeysetValue.Absent, value)

  pureTest("Absent pattern-matches as Absent and is distinct from atomic cases"):
    val value: KeysetValue = KeysetValue.Absent
    val matched = value match
      case KeysetValue.Absent        => "absent"
      case _: KeysetValue.IntV       => "int"
      case _: KeysetValue.LongV      => "long"
      case _: KeysetValue.StringV    => "string"
      case _: KeysetValue.TimestampV => "timestamp"
    expect.same("absent", matched)

  pureTest("Absent is not equal to any atomic KeysetValue"):
    List(
      expect(KeysetValue.Absent != KeysetValue.IntV(0)),
      expect(KeysetValue.Absent != KeysetValue.LongV(0L)),
      expect(KeysetValue.Absent != KeysetValue.StringV("")),
      expect(KeysetValue.Absent != KeysetValue.StringV("absent"))
    ).reduce(_ and _)
