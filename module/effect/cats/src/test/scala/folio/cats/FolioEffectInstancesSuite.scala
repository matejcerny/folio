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

package folio.cats

import cats.effect.IO
import weaver.SimpleIOSuite

import folio.*

object FolioEffectInstancesSuite extends SimpleIOSuite:

  private enum EventField derives FieldSchema.SnakeCase:
    case Timestamp

  private case class Event(timestamp: String)

  test("derives FolioEffect for Cats Effect IO"):
    import folio.cats.given

    val effect = FolioEffect[IO]
    val error = FolioError.CursorDecodingError.StaleCursor

    for
      mapped <- effect.map(IO.pure(41))(_ + 1)
      raised <- effect.raiseError[Int](error).attempt
    yield expect.all(
      mapped == 42,
      raised == Left(error)
    )

  test("supports direct folio-core pagination in IO"):
    import folio.cats.given

    val rows = Seq(Event("2026-07-15"), Event("2026-07-16"))

    Page
      .withPagination[IO, Event, EventField](Query.empty[EventField], _ => IO.pure(rows))
      .map(page => expect.same(rows, page.data))
