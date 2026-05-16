package folio

import weaver.SimpleIOSuite

object FilterBySuite extends SimpleIOSuite:

  pureTest("ExactMatch stores field correctly"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    expect.same(filter.field, TestField.Name)

  pureTest("ExactMatch stores value correctly"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    expect.same(filter.value, "alice")
