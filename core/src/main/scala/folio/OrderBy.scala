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

import scala.annotation.tailrec

case class OrderBy[FIELD: FieldSchema](order: Order, field: FIELD)

object OrderBy:

  /** Reject orderings that mention the same field more than once (identical or contradictory orders). Reports the first
    * duplicate in ordering-priority order (left to right).
    */
  private[folio] def validateFields[FIELD: FieldSchema](
      ordering: Vector[OrderBy[FIELD]]
  ): Either[FolioError.InvalidQuery, Unit] =

    @tailrec
    def loop(index: Int, seen: Set[FIELD]): Either[FolioError.InvalidQuery, Unit] =
      if index >= ordering.length then Right(())
      else
        val field = ordering(index).field
        if seen.contains(field) then Left(FolioError.InvalidQuery(s"duplicate order field: ${field.name}"))
        else loop(index + 1, seen + field)

    loop(0, Set.empty)

extension [FIELD: FieldSchema](field: FIELD)
  def ascending: OrderBy[FIELD] = OrderBy(Order.Ascending, field)
  def descending: OrderBy[FIELD] = OrderBy(Order.Descending, field)
