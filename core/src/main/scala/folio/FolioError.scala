package folio

sealed abstract class FolioError(message: String) extends Exception(message, null, true, false)

object FolioError:

  sealed abstract class CursorDecodingError(message: String) extends FolioError(message)

  object CursorDecodingError:
    case class InvalidBase64(input: String) extends CursorDecodingError(s"Invalid cursor: not valid base64url '$input'")
    case object StaleCursor extends CursorDecodingError("Cursor is stale: query parameters changed")
    case class Truncated(stage: String)
        extends CursorDecodingError(s"Invalid cursor: input ran out while parsing $stage")
    case class MalformedFlags(byte: Byte)
        extends CursorDecodingError(f"Invalid cursor: reserved flag bits set in byte 0x$byte%02x")
    case object MalformedVarint extends CursorDecodingError("Invalid cursor: malformed varint")
    case class UnknownKeysetTag(tag: Byte)
        extends CursorDecodingError(f"Invalid cursor: unknown keyset value tag 0x$tag%02x")
    case class TrailingBytes(count: Int)
        extends CursorDecodingError(s"Invalid cursor: $count trailing byte(s) after parse")
    case class MalformedKeysetValue(description: String)
        extends CursorDecodingError(s"Invalid cursor: malformed keyset value ($description)")
    case class MalformedTimestamp(description: String)
        extends CursorDecodingError(s"Invalid cursor: malformed timestamp ($description)")
    case class MalformedOffset(value: Long)
        extends CursorDecodingError(s"Invalid cursor: offset must be non-negative, got $value")
    case class StrategyMismatch(expected: Position, actual: Position)
        extends CursorDecodingError(
          s"Cursor strategy mismatch: query expects ${expected.asString}, cursor carries ${actual.asString}"
        )
