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

/** Query handed to a driver after the cursor has been decoded.
  *
  * `ordering` always describes the canonical (forward) ordering — the orders the user requested. Drivers translate
  * `direction` themselves rather than receiving pre-flipped ordering.
  *
  * @param limit
  *   The page size the caller requested — the number of rows [[Page.withPagination]] will ultimately return. Drivers
  *   must fetch [[fetchLimit]] rows (one more than this), not `limit`, so a further page can be detected.
  * @param direction
  *   When `Direction.Backward`, drivers performing a keyset seek must reverse both order and nulls placement (Absent
  *   first) so the reverse seek matches the canonical forward sequence walked in reverse. For offset `position` the
  *   offset is absolute, so direction is a no-op for those drivers. See ADR 0003.
  */
case class ResolvedQuery[FIELD](
    filters: Set[FilterBy[FIELD]],
    ordering: Vector[OrderBy[FIELD]],
    limit: Limit,
    position: Position,
    direction: Direction
):

  /** The number of rows the driver should fetch (page `limit` plus one), used to detect whether a further page exists.
    * The extra row is dropped before the page is assembled.
    */
  def fetchLimit: Limit = limit.fetchLimit
