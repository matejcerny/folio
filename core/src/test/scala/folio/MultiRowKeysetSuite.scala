/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
