package folio

import weaver.SimpleIOSuite

object OffsetSuite extends SimpleIOSuite:

  pureTest("LastId stores value"):
    val lastId = Offset.LastId(42)
    expect.same(lastId.value, 42L)

  pureTest("Incremental stores value"):
    val offset = Offset.Incremental(100)
    expect.same(offset.value, 100L)

  pureTest("Incremental.First is zero"):
    expect.same(Offset.Incremental.First.value, 0L)

  pureTest("Incremental.next advances by limit"):
    val offset = Offset.Incremental(10)
    val next = offset.next(Limit(25))
    expect.same(next.value, 35L)

  pureTest("Incremental.next from First advances by limit"):
    val next = Offset.Incremental.First.next(Limit(10))
    expect.same(next.value, 10L)

  pureTest("Incremental.next with default limit"):
    val next = Offset.Incremental.First.next(Limit.Default)
    expect.same(next.value, 10L)
