package folio

import weaver.SimpleIOSuite

object CursorCodecSuite extends SimpleIOSuite:

  // Use the real base64 codec, not the no-op test codec
  private val realCodec: CursorCodec = CursorCodec.given_CursorCodec

  // To test the real codec roundtrip, we go through Cursor.encode/decode
  // with the real codec as a local given, exercising actual base64 encoding.

  pureTest("roundtrip encode/decode for simple query via real base64 codec"):
    given CursorCodec = realCodec
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(None))
    val query = Query.empty[TestField]
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.same(roundtrip, Right(decoded))

  pureTest("roundtrip encode/decode for query with special characters in filter"):
    given CursorCodec = realCodec
    val query = Query
      .empty[TestField]
      .copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "hello+world/foo=bar")))
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(42L))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.same(roundtrip, Right(decoded))

  pureTest("encoded cursor contains only base64url characters without padding"):
    given CursorCodec = realCodec
    val cursor = Cursor.encode(
      DecodedCursor(Direction.Forward, Position.Keyset(Some(99L))),
      Query.empty[TestField]
    )
    val base64UrlPattern = "^[A-Za-z0-9_-]+$".r

    expect(clue(base64UrlPattern.findFirstIn(cursor.value)).isDefined) and
      expect(!clue(cursor.value).contains("="))

  pureTest("real codec decode returns InvalidBase64 for invalid input"):
    // Encode with the no-op codec (package-level given) to create a Cursor
    // containing raw text, which is not valid base64
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(None))
    val query = Query.empty[TestField]
    val noopCursor = Cursor.encode(decoded, query) // raw text via no-op codec

    // Now decode that raw-text cursor with the real base64 codec
    val result = realCodec.decode(noopCursor)

    expect(clue(result).isLeft) and
      expect(clue(result).left.exists(_.isInstanceOf[FolioError.CursorDecodingError.InvalidBase64]))

  pureTest("roundtrip preserves cursor position with LastId"):
    given CursorCodec = realCodec
    val decoded = DecodedCursor(Direction.Backward, Position.Keyset(Some(Long.MaxValue)))
    val query = TestFixtures.fullyPopulatedQuery
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.same(roundtrip, Right(decoded))
