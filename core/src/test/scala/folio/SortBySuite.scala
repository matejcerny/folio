package folio

import weaver.SimpleIOSuite

object SortBySuite extends SimpleIOSuite:

  pureTest("ascending extension produces SortBy with Ascending order"):
    val sortBy = TestField.Name.ascending
    expect.same(sortBy.order, Order.Ascending) and
      expect.same(sortBy.field, TestField.Name)

  pureTest("descending extension produces SortBy with Descending order"):
    val sortBy = TestField.CreatedAt.descending
    expect.same(sortBy.order, Order.Descending) and
      expect.same(sortBy.field, TestField.CreatedAt)
