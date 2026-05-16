package folio

import scala.collection.immutable.ListSet

import weaver.SimpleIOSuite

object PositionSuite extends SimpleIOSuite:

  pureTest("IdField present + primary sort is id field returns Id(None)"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))
    val position = Position.fromQuery(query)

    expect.same(position, Position.Keyset(None))

  pureTest("IdField present + primary sort is other field returns Incremental(First)"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.CreatedAt.descending))
    val position = Position.fromQuery(query)

    expect.same(position, Position.Offset.First)

  pureTest("IdField present + no sort returns Id(None)"):
    val position = Position.fromQuery(Query.empty[TestField])

    expect.same(position, Position.Keyset(None))

  pureTest("no IdField always returns Incremental(First)"):
    val position = Position.fromQuery(Query.empty[TestFieldNoId])

    expect.same(position, Position.Offset.First)

  pureTest("no IdField with sort still returns Incremental(First)"):
    val query = Query.empty[TestFieldNoId].copy(sortBys = ListSet(TestFieldNoId.Timestamp.ascending))
    val position = Position.fromQuery(query)

    expect.same(position, Position.Offset.First)
