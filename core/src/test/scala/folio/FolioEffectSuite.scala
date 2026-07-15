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
