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

import scala.concurrent.{ ExecutionContext, Future }

/** The minimal effect capability needed by [[Page.withPagination]].
  *
  * Folio performs one fetch and transforms its result, or raises a [[FolioError]] without fetching. It does not require
  * arbitrary effect composition, concurrency, cancellation, or resource management from the caller's effect system.
  * Keeping this boundary deliberately small lets `folio-core` work with Cats Effect, ZIO, Kyo, `Future`, direct-style
  * effects, and synchronous code without depending on any of them. It is an operational capability, not a claim that
  * `F` is referentially transparent or supplies a lawful full effect typeclass.
  */
trait FolioEffect[F[_]]:
  def map[A, B](effect: F[A])(transform: A => B): F[B]
  def raiseError[A](error: FolioError): F[A]

object FolioEffect:

  /** Summon the effect capability for `F`. */
  def apply[F[_]](using effect: FolioEffect[F]): FolioEffect[F] = effect

  /** Identity effect for synchronous pagination; raising an error throws it. */
  type Id[A] = A

  given FolioEffect[Id] with
    def map[A, B](effect: A)(transform: A => B): B = transform(effect)
    def raiseError[A](error: FolioError): A = throw error

  /** Standard-library `Future` support. */
  given future(using executionContext: ExecutionContext): FolioEffect[Future] with
    def map[A, B](effect: Future[A])(transform: A => B): Future[B] = effect.map(transform)
    def raiseError[A](error: FolioError): Future[A] = Future.failed(error)
