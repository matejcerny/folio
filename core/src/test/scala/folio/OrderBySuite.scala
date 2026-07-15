package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object OrderBySuite extends SimpleIOSuite:

  pureTest("ascending extension produces OrderBy with Ascending order"):
    val orderBy = TestField.Name.ascending
    List(
      expect.same(Order.Ascending, orderBy.order),
      expect.same(TestField.Name, orderBy.field)
    ).combineAll

  pureTest("descending extension produces OrderBy with Descending order"):
    val orderBy = TestField.CreatedAt.descending
    List(
      expect.same(Order.Descending, orderBy.order),
      expect.same(TestField.CreatedAt, orderBy.field)
    ).combineAll

  pureTest("validateFields accepts empty ordering"):
    expect.same(Right(()), OrderBy.validateFields(Vector.empty[OrderBy[TestField]]))

  pureTest("validateFields accepts distinct fields in any orders"):
    val ordering = Vector(
      TestField.Name.descending,
      TestField.CreatedAt.ascending,
      TestField.Id.descending
    )
    expect.same(Right(()), OrderBy.validateFields(ordering))

  pureTest("validateFields rejects identical duplicate of the same field"):
    val ordering = Vector(TestField.Id.ascending, TestField.Id.ascending)
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: id")),
      OrderBy.validateFields(ordering)
    )

  pureTest("validateFields rejects contradictory orders on the same field"):
    val ordering = Vector(TestField.Name.ascending, TestField.Name.descending)
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: name")),
      OrderBy.validateFields(ordering)
    )

  pureTest("validateFields reports the first duplicate in ordering-priority order"):
    // Name is seen again before Id is, so Name must be reported even though Id is also duplicated later.
    val ordering = Vector(
      TestField.Name.ascending,
      TestField.Id.ascending,
      TestField.Name.descending,
      TestField.Id.descending
    )
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: name")),
      OrderBy.validateFields(ordering)
    )

  pureTest("validateFields reports a later field when it is the first to repeat"):
    val ordering = Vector(
      TestField.Name.ascending,
      TestField.Id.ascending,
      TestField.CreatedAt.descending,
      TestField.Id.descending
    )
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: id")),
      OrderBy.validateFields(ordering)
    )
