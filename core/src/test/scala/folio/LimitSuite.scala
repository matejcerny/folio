package folio

import weaver.SimpleIOSuite

object LimitSuite extends SimpleIOSuite:

  pureTest("Limit.apply accepts positive within bounds"):
    expect.same(Limit(25).map(_.value), Right(25))

  pureTest("Limit.apply rejects zero"):
    expect.same(Limit(0), Left("Limit must be in range (0, 100000], got 0"))

  pureTest("Limit.apply rejects negatives"):
    expect.same(Limit(-1), Left("Limit must be in range (0, 100000], got -1"))

  pureTest("Limit.apply rejects above max"):
    expect.same(Limit(100_001), Left("Limit must be in range (0, 100000], got 100001"))

  pureTest("Limit.apply accepts max"):
    expect.same(Limit(100_000).map(_.value), Right(100_000))

  pureTest("Limit.unsafe accepts a valid value"):
    expect.same(Limit.unsafe(25).value, 25)

  pureTest("Limit.Default is 10"):
    expect.same(Limit.Default.value, 10)

  pureTest("Int.items literal yields a Limit"):
    expect.same(25.items, Limit.unsafe(25))
