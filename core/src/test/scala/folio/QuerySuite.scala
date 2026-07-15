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
