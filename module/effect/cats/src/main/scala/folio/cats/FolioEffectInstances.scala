package folio.cats

import _root_.cats.ApplicativeError

import folio.{ FolioEffect, FolioError }

/** Derive Folio's minimal effect capability from Cats.
  *
  * Import with `import folio.cats.given` when using `folio-core` directly from a Cats or Cats Effect application.
  * Database integrations such as `folio-skunk` import this bridge internally.
  */
given folioEffectFromApplicativeError[F[_]](using
    effect: ApplicativeError[F, Throwable]
): FolioEffect[F] with
  def map[A, B](value: F[A])(transform: A => B): F[B] = effect.map(value)(transform)
  def raiseError[A](error: FolioError): F[A] = effect.raiseError(error)
