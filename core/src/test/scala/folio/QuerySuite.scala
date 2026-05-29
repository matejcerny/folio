package folio

import scala.collection.immutable.ListSet

import weaver.SimpleIOSuite

object QuerySuite extends SimpleIOSuite:

  pureTest("Query.empty has no filters"):
    expect(clue(TestFixtures.emptyQueryWithId.filters).isEmpty)

  pureTest("Query.empty has no cursor"):
    expect(clue(TestFixtures.emptyQueryWithId.cursor).isEmpty)

  pureTest("Query.empty uses default limit"):
    expect.same(Limit.Default, TestFixtures.emptyQueryWithId.limit)

  pureTest("Query.empty has no sortBys"):
    expect(clue(TestFixtures.emptyQueryWithId.sortBys).isEmpty)

  pureTest("Query constructor defaults cursor to None when omitted"):
    val query = Query[TestField](Set.empty, ListSet.empty, Limit.Default)
    expect.same(None, query.cursor)
