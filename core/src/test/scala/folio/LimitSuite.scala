package folio

import weaver.SimpleIOSuite

object LimitSuite extends SimpleIOSuite:

  pureTest("Limit.apply accepts positive within bounds"):
    expect.same(Right(25), Limit(25).map(_.value))

  pureTest("Limit.apply rejects zero"):
    expect.same(Limit(0), Left("Limit must be in range (0, 100000], got 0"))

  pureTest("Limit.apply rejects negatives"):
    expect.same(Limit(-1), Left("Limit must be in range (0, 100000], got -1"))

  pureTest("Limit.apply rejects above max"):
    expect.same(Limit(100_001), Left("Limit must be in range (0, 100000], got 100001"))

  pureTest("Limit.apply accepts max"):
    expect.same(Right(100_000), Limit(100_000).map(_.value))

  pureTest("Limit.unsafe accepts a valid value"):
    expect.same(25, Limit.unsafe(25).value)

  pureTest("Limit.Default is 10"):
    expect.same(10, Limit.Default.value)

  pureTest("Int.items literal yields a Limit"):
    expect.same(Limit.unsafe(25), 25.items)
