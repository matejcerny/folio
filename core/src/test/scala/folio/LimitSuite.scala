package folio

import weaver.SimpleIOSuite

object LimitSuite extends SimpleIOSuite:

  pureTest("Limit stores value"):
    val limit = Limit(25)

    expect.same(limit.value, 25)

  pureTest("Limit.Default is 10"):
    expect.same(Limit.Default.value, 10)
