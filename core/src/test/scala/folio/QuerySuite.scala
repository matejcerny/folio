package folio

import weaver.SimpleIOSuite

object QuerySuite extends SimpleIOSuite:

  pureTest("Query.empty has no filters"):
    expect(clue(TestFixtures.emptyQueryWithId.filters).isEmpty)

  pureTest("Query.empty has no cursor"):
    expect(clue(TestFixtures.emptyQueryWithId.cursor).isEmpty)

  pureTest("Query.empty uses default limit"):
    expect.same(TestFixtures.emptyQueryWithId.limit, Limit.Default)

  pureTest("Query.empty has no sortBys"):
    expect(clue(TestFixtures.emptyQueryWithId.sortBys).isEmpty)
