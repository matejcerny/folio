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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

import weaver.SimpleIOSuite

object FolioEffectSuite extends SimpleIOSuite:

  pureTest("Id maps synchronously and raises FolioError by throwing"):
    val effect = FolioEffect[FolioEffect.Id]
    val error = FolioError.CursorDecodingError.StaleCursor
    expect.all(
      effect.map(41)(_ + 1) == 42,
      Try(effect.raiseError[Int](error)) == Failure(error)
    )

  pureTest("Future maps with the supplied execution context and raises FolioError as a failed Future"):
    given ExecutionContext = ExecutionContext.parasitic
    val effect = FolioEffect[scala.concurrent.Future]
    val error = FolioError.CursorDecodingError.StaleCursor
    expect.all(
      effect.map(Future.successful(41))(_ + 1).value == Some(Success(42)),
      effect.raiseError[Int](error).value == Some(Failure(error))
    )
