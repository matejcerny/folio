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

import cats.syntax.foldable.*
import weaver.SimpleIOSuite
import TestFixtures.*

object FieldSchemaSuite extends SimpleIOSuite:

  pureTest("name extension returns correct column name"):
    List(
      expect.same("id", TestField.Id.name),
      expect.same("name", TestField.Name.name),
      expect.same("created_at", TestField.CreatedAt.name)
    ).combineAll

  pureTest("fromName roundtrip for all fields"):
    val schema = summon[FieldSchema[TestField]]

    List(
      expect.sameR(TestField.Id, schema.fromName("id")),
      expect.sameR(TestField.Name, schema.fromName("name")),
      expect.sameR(TestField.CreatedAt, schema.fromName("created_at"))
    ).combineAll

  pureTest("fromName returns Left for unknown name"):
    val schema = summon[FieldSchema[TestField]]

    expect(clue(schema.fromName("unknown")).isLeft)
