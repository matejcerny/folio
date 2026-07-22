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

/** Base error raised through [[FolioEffect]] by effectful Folio APIs and returned as `Left` by pure validation APIs. */
sealed abstract class FolioError(message: String) extends Exception(message, null, true, false)

object FolioError:

  sealed abstract class CursorDecodingError(message: String) extends FolioError(message)

  object CursorDecodingError:
    case class MalformedCursor(reason: String) extends CursorDecodingError(s"Invalid cursor: $reason")

    case object StaleCursor extends CursorDecodingError("Cursor is stale: query parameters changed")

    case class IncompatibleCursor(reason: String) extends CursorDecodingError(s"Cursor does not match query: $reason")

  case class InvalidQuery(reason: String) extends FolioError(s"Invalid query: $reason")
