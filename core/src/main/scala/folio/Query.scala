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

case class Query[FIELD](
    filters: Set[FilterBy[FIELD]] = Set.empty[FilterBy[FIELD]],
    ordering: Vector[OrderBy[FIELD]] = Vector.empty[OrderBy[FIELD]],
    limit: Limit,
    cursor: Option[Cursor] = None
):

  /** Replace the ordering with the given fields in priority order. Does not append or validate; validation runs at
    * execution boundaries.
    */
  def orderBy(first: OrderBy[FIELD], rest: OrderBy[FIELD]*): Query[FIELD] =
    copy(ordering = first +: rest.toVector)

object Query:
  def empty[FIELD]: Query[FIELD] = Query[FIELD](limit = Limit.Default)
