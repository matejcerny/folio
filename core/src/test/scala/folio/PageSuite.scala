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
import weaver.FunSuite

object PageSuite extends FunSuite:

  private val previous = Cursor("previous")
  private val next = Cursor("next")

  test("Page.map maps the data and preserves the limit and both cursors"):
    val page = Page(25.items, Some(previous), Some(next), Seq(1, 2, 3))
    val mapped = page.map(_.toString)
    List(
      expect.same(Seq("1", "2", "3"), mapped.data),
      expect.same(25.items, mapped.limit),
      expect.same(Some(previous), mapped.previousCursor),
      expect.same(Some(next), mapped.nextCursor)
    ).combineAll

  test("Page.map on an empty page keeps it empty and cursor-less"):
    val mapped = Page.empty[Int](10.items).map(_.toString)
    List(
      expect.same(Seq.empty[String], mapped.data),
      expect.same(None, mapped.previousCursor),
      expect.same(None, mapped.nextCursor)
    ).combineAll

  test("ResolvedQuery.fetchLimit is the page limit plus one"):
    val resolved =
      ResolvedQuery[Int](Set.empty, Vector.empty, 25.items, Position.Keyset(Nil), Direction.Forward)
    List(
      expect.same(25, resolved.limit.value),
      expect.same(26, resolved.fetchLimit.value)
    ).combineAll
