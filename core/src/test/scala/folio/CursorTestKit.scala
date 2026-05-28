package folio

import java.io.ByteArrayOutputStream
import java.util.Base64

object CursorTestKit:

  /** Returns the 4 hash bytes from any encoded cursor for the given query (positions 1..5 of the binary frame). */
  def hashBytes[FIELD: FieldSchema](query: Query[FIELD])(using CursorCodec): Array[Byte] =
    val baseline = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), query)
    val raw = Base64.getUrlDecoder.decode(baseline.value)
    raw.slice(1, 5)

  def buildCursor(flags: Byte, hash: Array[Byte], payload: Array[Byte]): Cursor =
    val output = ByteArrayOutputStream()
    output.write(flags.toInt)
    output.write(hash)
    output.write(payload)
    Cursor(Base64.getUrlEncoder.withoutPadding.encodeToString(output.toByteArray))

  /** Parses space-separated hex pairs into a byte array. e.g. "02 03 ff" -> Array(2, 3, -1). */
  def hex(text: String): Array[Byte] =
    text.split("\\s+").filter(_.nonEmpty).map(pair => Integer.parseInt(pair, 16).toByte)

  def concat(parts: Array[Byte]*): Array[Byte] =
    val output = ByteArrayOutputStream()
    parts.foreach(output.write)
    output.toByteArray
