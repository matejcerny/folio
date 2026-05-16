package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object SortBySuite extends SimpleIOSuite:

  pureTest("ascending extension produces SortBy with Ascending order"):
    val sortBy = TestField.Name.ascending
    List(
      expect.same(sortBy.order, Order.Ascending),
      expect.same(sortBy.field, TestField.Name)
    ).combineAll

  pureTest("descending extension produces SortBy with Descending order"):
    val sortBy = TestField.CreatedAt.descending
    List(
      expect.same(sortBy.order, Order.Descending),
      expect.same(sortBy.field, TestField.CreatedAt)
    ).combineAll
