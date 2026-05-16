package folio

import scala.collection.immutable.ListSet

import weaver.SimpleIOSuite

object CursorPositionSuite extends SimpleIOSuite:

  pureTest("IdField present + primary sort is id field returns Id(None)"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))
    val position = query.cursorPosition
    expect.same(position, CursorPosition.Id(None))

  pureTest("IdField present + primary sort is other field returns Incremental(First)"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.CreatedAt.descending))
    val position = query.cursorPosition
    expect.same(position, CursorPosition.Incremental(Offset.Incremental.First))

  pureTest("IdField present + no sort returns Id(None)"):
    val position = Query.empty[TestField].cursorPosition
    expect.same(position, CursorPosition.Id(None))

  pureTest("no IdField always returns Incremental(First)"):
    val position = Query.empty[TestFieldNoId].cursorPosition
    expect.same(position, CursorPosition.Incremental(Offset.Incremental.First))

  pureTest("no IdField with sort still returns Incremental(First)"):
    val query = Query
      .empty[TestFieldNoId]
      .copy(
        sortBys = ListSet(TestFieldNoId.Timestamp.ascending)
      )
    val position = query.cursorPosition
    expect.same(position, CursorPosition.Incremental(Offset.Incremental.First))
