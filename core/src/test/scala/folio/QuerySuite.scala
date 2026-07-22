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

object QuerySuite extends SimpleIOSuite:

  pureTest("Query.empty has no filters"):
    expect(clue(TestFixtures.emptyQueryWithId.filters).isEmpty)

  pureTest("Query.empty has no cursor"):
    expect(clue(TestFixtures.emptyQueryWithId.cursor).isEmpty)

  pureTest("Query.empty uses default limit"):
    expect.same(Limit.Default, TestFixtures.emptyQueryWithId.limit)

  pureTest("Query.empty has no ordering"):
    expect(clue(TestFixtures.emptyQueryWithId.ordering).isEmpty)

  pureTest("Query.empty equals Query(limit = Limit.Default)"):
    expect.same(Query[TestField](limit = Limit.Default), Query.empty[TestField])

  pureTest("Query constructor defaults filters, ordering, and cursor when only limit is given"):
    val query = Query[TestField](limit = Limit.Default)
    List(
      expect.same(Set.empty[FilterBy[TestField]], query.filters),
      expect.same(Vector.empty[OrderBy[TestField]], query.ordering),
      expect.same(None, query.cursor)
    ).combineAll

  pureTest("orderBy sets ordering in the given priority order"):
    val query = Query[TestField](limit = 10.items).orderBy(TestField.Name.ascending, TestField.Id.descending)
    expect.same(Vector(TestField.Name.ascending, TestField.Id.descending), query.ordering)

  pureTest("a second orderBy replaces the previous ordering"):
    val query = Query[TestField](limit = 10.items)
      .orderBy(TestField.Name.ascending)
      .orderBy(TestField.CreatedAt.descending, TestField.Id.ascending)
    expect.same(Vector(TestField.CreatedAt.descending, TestField.Id.ascending), query.ordering)
