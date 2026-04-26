package folio

import weaver.SimpleIOSuite

object QuerySuite extends SimpleIOSuite:

  pureTest("Query.empty has no filters"):
    expect(clue(TestFixtures.emptyQueryWithId.filters).isEmpty)

  pureTest("Query.empty has no cursor"):
    expect(clue(TestFixtures.emptyQueryWithId.cursor).isEmpty)

  pureTest("Query.empty has no limit"):
    expect(clue(TestFixtures.emptyQueryWithId.limit).isEmpty)

  pureTest("Query.empty has no sortBys"):
    expect(clue(TestFixtures.emptyQueryWithId.sortBys).isEmpty)

  pureTest("cursorPosition extension delegates to CursorPosition.fromQuery"):
    val query = TestFixtures.queryWithIdSort
    expect.same(query.cursorPosition, CursorPosition.Id(None))
