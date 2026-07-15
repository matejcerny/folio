package folio

import folio.FolioError.CursorDecodingError

import java.util.Base64
import scala.util.Try

trait CursorCodec:
  def encode(bytes: Array[Byte]): String
  def decode(cursor: Cursor): Either[CursorDecodingError, Array[Byte]]

object CursorCodec:
  given CursorCodec with
    def encode(bytes: Array[Byte]): String =
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

    def decode(cursor: Cursor): Either[CursorDecodingError, Array[Byte]] =
      Try(Base64.getUrlDecoder.decode(cursor.value)).toEither.left
        .map(_ => CursorDecodingError.MalformedCursor("not valid base64"))
