package folio

import scala.concurrent.{ ExecutionContext, Future }

/** The minimal effect capability needed by [[Page.withPagination]].
  *
  * Folio performs one fetch and transforms its result, or raises a [[FolioError]]. No composition, concurrency, or
  * resource management is required, which lets `folio-core` work with Cats Effect, ZIO, Kyo, `Future`, and synchronous
  * code without depending on any of them. An operational capability, not a lawful effect typeclass.
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
