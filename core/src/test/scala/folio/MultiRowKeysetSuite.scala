package folio

import folio.FolioEffect.Id
import weaver.SimpleIOSuite

/** Regression: when two row models share the same `FIELD` enum and each provides a `KeysetField[FIELD, T]` for its own
  * row type, [[Page.withPagination]] must dispatch on the concrete `T` at the call site without re-summoning
  * `KeysetField[FIELD, ?]`, which would be ambiguous against the wildcard.
  */
object MultiRowKeysetSuite extends SimpleIOSuite:

  case class AuditRow(id: Long, actor: String)

  given KeysetField[TestField, AuditRow] = KeysetField.uniqueBy(TestField.Id, (row: AuditRow) => row.id)

  private val baseQuery: Query[TestField] = TestFixtures.queryWithIdOrdering.copy(limit = 2.items)

  pureTest("withPagination dispatches on concrete T when two KeysetField givens share the same FIELD"):
    val rowPage: Page[Row] =
      Page.withPagination[Id, Row, TestField](
        baseQuery,
        _ => Vector(Row(1, "", "", "", None), Row(2, "", "", "", None), Row(3, "", "", "", None))
      )
    val auditPage: Page[AuditRow] =
      Page.withPagination[Id, AuditRow, TestField](
        baseQuery,
        _ => Vector(AuditRow(10, "alice"), AuditRow(11, "bob"), AuditRow(12, "carol"))
      )

    expect.all(
      rowPage.data.map(_.id) == Seq(1L, 2L),
      rowPage.nextCursor.isDefined,
      rowPage.previousCursor.isEmpty,
      auditPage.data.map(_.id) == Seq(10L, 11L),
      auditPage.nextCursor.isDefined,
      auditPage.previousCursor.isEmpty
    )
