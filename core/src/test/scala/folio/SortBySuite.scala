package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object SortBySuite extends SimpleIOSuite:

  pureTest("ascending extension produces SortBy with Ascending order"):
    val sortBy = TestField.Name.ascending
    List(
      expect.same(Order.Ascending, sortBy.order),
      expect.same(TestField.Name, sortBy.field)
    ).combineAll

  pureTest("descending extension produces SortBy with Descending order"):
    val sortBy = TestField.CreatedAt.descending
    List(
      expect.same(Order.Descending, sortBy.order),
      expect.same(TestField.CreatedAt, sortBy.field)
    ).combineAll
