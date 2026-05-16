package folio

import folio.FolioError.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

trait CursorCodec:
  def encode(raw: String): String
  def decode(cursor: Cursor): Either[CursorDecodingError, String]

object CursorCodec:
  given CursorCodec with
    def encode(raw: String): String =
      Base64.getUrlEncoder.withoutPadding.encodeToString(raw.getBytes(StandardCharsets.UTF_8))

    def decode(cursor: Cursor): Either[CursorDecodingError, String] =
      Try(String(Base64.getUrlDecoder.decode(cursor.value), StandardCharsets.UTF_8)).toEither.left
        .map(_ => CursorDecodingError.InvalidBase64(cursor.value))
