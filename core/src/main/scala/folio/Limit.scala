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

opaque type Limit = Int

object Limit:
  inline val MaxValue = 100_000

  val Default: Limit = unsafe(10)

  private def condition(n: Int): Boolean = n > 0 && n <= MaxValue
  private def errorMessage(n: Int): String = s"Limit must be in range (0, $MaxValue], got $n"

  def apply(n: Int): Either[String, Limit] =
    Either.cond(condition(n), n, errorMessage(n))

  def unsafe(n: Int): Limit =
    require(condition(n), errorMessage(n))
    n

  extension (limit: Limit)
    def value: Int = limit

    /** The number of rows a driver should fetch: one more than the page size, so [[Page.withPagination]] can detect
      * whether a further page exists. Drivers read this (via [[ResolvedQuery.fetchLimit]]) rather than adding one
      * themselves.
      */
    def fetchLimit: Limit = limit + 1

    private[folio] def hasMore(items: Seq[?]): Boolean = items.lengthCompare(limit) > 0

extension (n: Int)
  /** Construct a [[Limit]] from an integer literal with a compile-time range check.
    *
    * Example: `10.items`
    */
  inline def items: Limit =
    inline if n > 0 && n <= Limit.MaxValue then Limit.unsafe(n)
    else scala.compiletime.error("Limit must be in range (0, 100_000]")
