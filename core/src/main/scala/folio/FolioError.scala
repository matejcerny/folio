package folio

sealed abstract class FolioError(message: String) extends Exception(message, null, true, false)

object FolioError:

  sealed abstract class CursorDecodingError(message: String) extends FolioError(message)

  object CursorDecodingError:
    case class InvalidBase64(input: String) extends CursorDecodingError(s"Invalid cursor: not valid base64url '$input'")
    case class InvalidFormat(expectedParts: Int, actualParts: Int)
        extends CursorDecodingError(s"Invalid cursor format: expected $expectedParts parts, got $actualParts")
    case object StaleCursor extends CursorDecodingError("Cursor is stale: query parameters changed")
    case class UnknownCursorType(cursorType: String) extends CursorDecodingError(s"Unknown cursor type: $cursorType")
    case class MalformedOffset(offset: String)
        extends CursorDecodingError(s"Invalid cursor: malformed offset '$offset'")
