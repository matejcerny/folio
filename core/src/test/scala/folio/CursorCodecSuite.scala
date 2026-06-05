package folio

import folio.KeysetSyntax.keysetOf
import weaver.SimpleIOSuite
import TestFixtures.*

object CursorCodecSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Row] = KeysetField.uniqueBy(TestField.Id, _.id)

  private val realCodec: CursorCodec = summon[CursorCodec]

  pureTest("roundtrip encode/decode for simple query via real base64 codec"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Nil))
    val query = Query.empty[TestField]
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip encode/decode for query with special characters in filter"):
    val query = Query
      .empty[TestField]
      .copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "hello+world/foo=bar")))
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(42L))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("encoded cursor contains only base64url characters without padding"):
    val cursor = Cursor.encode(
      DecodedCursor(Direction.Forward, keysetOf(99L)),
      Query.empty[TestField]
    )
    val base64UrlPattern = "^[A-Za-z0-9_-]+$".r

    expect(clue(base64UrlPattern.findFirstIn(cursor.value)).isDefined) and
      expect(!clue(cursor.value).contains("="))

  pureTest("real codec decode returns InvalidBase64 for invalid input"):
    val invalidCursor = Cursor("not valid base64 because of spaces")

    val result = realCodec.decode(invalidCursor)

    expect(clue(result).isLeft) and
      expect(clue(result).left.exists(_.isInstanceOf[FolioError.CursorDecodingError.InvalidBase64]))

  pureTest("roundtrip preserves cursor position with Long.MaxValue"):
    val decoded = DecodedCursor(Direction.Backward, keysetOf(Long.MaxValue))
    val query = Query.empty[TestField]
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("encode produces a compact frame for typical single-Long keyset"):
    val cursor = Cursor.encode(
      DecodedCursor(Direction.Forward, keysetOf(2L)),
      Query.empty[TestField]
    )
    val raw = java.util.Base64.getUrlDecoder.decode(cursor.value)

    // flags(1) + hash(4) + count-varint(1) + tag(1) + Long-zigzag-varint(1 for value=2) = 8 bytes
    expect.same(8, raw.length)
