package folio

sealed abstract class FolioError(message: String) extends Exception(message, null, true, false)

object FolioError:

  sealed abstract class CursorDecodingError(message: String) extends FolioError(message)

  object CursorDecodingError:
    case class MalformedCursor(reason: String) extends CursorDecodingError(s"Invalid cursor: $reason")

    case object StaleCursor extends CursorDecodingError("Cursor is stale: query parameters changed")

    case class IncompatibleCursor(reason: String) extends CursorDecodingError(s"Cursor does not match query: $reason")
