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

import scala.util.Try

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import TestFixtures.*

object LimitSuite extends SimpleIOSuite with Checkers:

  private val validLimits = Gen.choose(1, 100_000)
  private val negativeInts = Gen.choose(Int.MinValue, -1)
  private val aboveMaxInts = Gen.choose(100_001, Int.MaxValue)

  test("Limit.apply accepts any positive int in range"):
    forall(validLimits): n =>
      expect.sameR(n, Limit(n).map(_.value))

  pureTest("Limit.apply rejects zero"):
    expect.sameL("Limit must be in range (0, 100000], got 0", Limit(0))

  test("Limit.apply rejects any negative int"):
    forall(negativeInts): n =>
      expect.sameL(s"Limit must be in range (0, 100000], got $n", Limit(n))

  test("Limit.apply rejects any int above max"):
    forall(aboveMaxInts): n =>
      expect.sameL(s"Limit must be in range (0, 100000], got $n", Limit(n))

  pureTest("Limit.apply accepts max"):
    expect.sameR(100_000, Limit(100_000).map(_.value))

  pureTest("Limit.unsafe accepts a valid value"):
    expect.same(25, Limit.unsafe(25).value)

  pureTest("Limit.unsafe throws IllegalArgumentException on zero"):
    Try(Limit.unsafe(0)).toEither match
      case Left(error: IllegalArgumentException) =>
        expect(clue(error.getMessage).contains("Limit must be in range (0, 100000], got 0"))
      case other => failure(s"expected IllegalArgumentException, got $other")

  pureTest("Limit.unsafe throws IllegalArgumentException above max"):
    Try(Limit.unsafe(100_001)).toEither match
      case Left(error: IllegalArgumentException) =>
        expect(clue(error.getMessage).contains("Limit must be in range (0, 100000], got 100001"))
      case other => failure(s"expected IllegalArgumentException, got $other")

  pureTest("Limit.Default is 10"):
    expect.same(10, Limit.Default.value)

  pureTest("Int.items literal yields a Limit"):
    expect.same(Limit.unsafe(25), 25.items)

  pureTest("Limit.fetchLimit is one more than the page size"):
    expect.same(26, Limit.unsafe(25).fetchLimit.value)
