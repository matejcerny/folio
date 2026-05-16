package folio

import weaver.SimpleIOSuite

object LimitSuite extends SimpleIOSuite:

  pureTest("Limit stores value"):
    expect.same(Limit(25).value, 25)

  pureTest("Limit.Default is 10"):
    expect.same(Limit.Default.value, 10)
