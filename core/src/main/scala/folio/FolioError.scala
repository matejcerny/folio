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
