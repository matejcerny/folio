package folio

import weaver.SimpleIOSuite

object FilterBySuite extends SimpleIOSuite:

  pureTest("ExactMatch stores field correctly"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    expect.same(TestField.Name, filter.field)

  pureTest("ExactMatch stores value correctly"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    expect.same("alice", filter.value)
