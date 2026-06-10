package folio

import java.util.Base64

import scala.collection.immutable.ListSet

import cats.syntax.foldable.*
import folio.FolioError.*
import TestFixtures.*
import folio.KeysetSyntax.keysetOf
import weaver.SimpleIOSuite

object CursorSuite extends SimpleIOSuite:

  private val baseQuery = TestFixtures.emptyQueryWithId

  private given KeysetField[TestField, Row] = KeysetField.uniqueBy(TestField.Id, _.id)

  private def decodeBase64Url(value: String): Array[Byte] =
    Base64.getUrlDecoder.decode(value)

  pureTest("roundtrip for forward Keyset(Nil)"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Nil))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for forward Keyset(LongV(42))"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf(42L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for forward Offset(100)"):
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(100L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for backward Keyset(LongV(42))"):
    val decoded = DecodedCursor(Direction.Backward, keysetOf(42L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for backward Offset(100)"):
    val decoded = DecodedCursor(Direction.Backward, Position.Offset.unsafe(100L))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("encoding for keyset with multiple String values"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf("foo", "bar"))
    val cursor = Cursor.encode(decoded, baseQuery)

    // flags=0x02 (Forward, Keyset) | hash=H | count=2 | tag=03 len=3 "foo" | tag=03 len=3 "bar"
    val expected = CursorTestKit.concat(
      CursorTestKit.hex("02"),
      CursorTestKit.hashBytes(baseQuery),
      CursorTestKit.hex("02"),
      CursorTestKit.hex("03 03"),
      "foo".getBytes("UTF-8"),
      CursorTestKit.hex("03 03"),
      "bar".getBytes("UTF-8")
    )
    expect.same(expected.toSeq, decodeBase64Url(cursor.value).toSeq)

  pureTest("decoding rejects keyset with arity above maxKeysetArity"):
    val decoded =
      DecodedCursor(Direction.Forward, Position.Keyset(List.tabulate(17)(i => KeysetValue.IntV(i))))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("keyset arity exceeds limit"), roundtrip)

  pureTest("roundtrip for keyset with three values (string, timestamp, long)"):
    val timestamp = java.time.OffsetDateTime.parse("2024-01-15T10:30:00Z")
    val query =
      baseQuery.copy(sortBys = ListSet(TestField.Name.ascending, TestField.CreatedAt.ascending, TestField.Id.ascending))
    val decoded = DecodedCursor(
      Direction.Forward,
      Position.Keyset(
        List(
          KeysetValue.StringV("alpha"),
          KeysetValue.TimestampV(timestamp),
          KeysetValue.LongV(99L)
        )
      )
    )
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for keyset with IntV value"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.IntV(42))))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for keyset value containing the legacy length separator"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf("a::b"))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for keyset value containing the legacy part separator"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf("a;b;c"))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for keyset with single empty-string value"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf(""))
    val cursor = Cursor.encode(decoded, baseQuery)
    val roundtrip = Cursor.decode(cursor, baseQuery)

    // flags=0x02 | hash=H | count=1 | tag=03 len=0
    val expected = CursorTestKit.concat(
      CursorTestKit.hex("02"),
      CursorTestKit.hashBytes(baseQuery),
      CursorTestKit.hex("01 03 00")
    )
    List(
      expect.same(expected.toSeq, decodeBase64Url(cursor.value).toSeq),
      expect.sameR(decoded, roundtrip)
    ).combineAll

  pureTest("first-page keyset cursor encodes to 6 bytes"):
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Nil))
    val cursor = Cursor.encode(decoded, baseQuery)

    expect.same(6, decodeBase64Url(cursor.value).length)

  pureTest("deterministic encoding - same input produces same output"):
    val decoded = DecodedCursor(Direction.Forward, keysetOf(7L))
    val cursor1 = Cursor.encode(decoded, baseQuery)
    val cursor2 = Cursor.encode(decoded, baseQuery)

    expect.same(cursor1.value, cursor2.value)

  pureTest("different positions produce different cursors"):
    val cursorKeyset = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), baseQuery)
    val cursorOffset = Cursor.encode(DecodedCursor(Direction.Forward, Position.Offset.First), baseQuery)

    expect(clue(cursorKeyset.value) != clue(cursorOffset.value))

  pureTest("different directions produce different cursors"):
    val position = keysetOf(5L)
    val forwardCursor = Cursor.encode(DecodedCursor(Direction.Forward, position), baseQuery)
    val backwardCursor = Cursor.encode(DecodedCursor(Direction.Backward, position), baseQuery)

    expect(clue(forwardCursor.value) != clue(backwardCursor.value))

  pureTest("direction does not affect the query fingerprint"):
    val position = keysetOf(5L)
    def fingerprint(direction: Direction): Seq[Byte] =
      decodeBase64Url(Cursor.encode(DecodedCursor(direction, position), baseQuery).value).slice(1, 5).toSeq
    expect.same(fingerprint(Direction.Backward), fingerprint(Direction.Forward))

  pureTest("stale cursor - limit changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), baseQuery)
    val modifiedQuery = baseQuery.copy(limit = 50.items)
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.sameL(CursorDecodingError.StaleCursor, decoded)

  pureTest("stale cursor - sort changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), baseQuery)
    val modifiedQuery = baseQuery.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.sameL(CursorDecodingError.StaleCursor, decoded)

  pureTest("stale cursor - filter changed"):
    val cursor = Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), baseQuery)
    val modifiedQuery = baseQuery.copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "bob")))
    val decoded = Cursor.decode(cursor, modifiedQuery)

    expect.sameL(CursorDecodingError.StaleCursor, decoded)

  pureTest("decode rejects empty input as truncated"):
    val cursor = Cursor(Base64.getUrlEncoder.withoutPadding.encodeToString(Array.emptyByteArray))
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects missing fingerprint bytes as truncated"):
    // only flags byte present
    val cursor = Cursor(Base64.getUrlEncoder.withoutPadding.encodeToString(Array(0x02.toByte)))
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects missing offset varint as truncated"):
    val cursor = CursorTestKit.buildCursor(
      flags = 0x00.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = Array.emptyByteArray
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects missing keyset count varint as truncated"):
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = Array.emptyByteArray
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects keyset count promising more values than present as truncated"):
    // count=1 but no value bytes follow
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("01")
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects StringV length exceeding remaining bytes as truncated"):
    // count=1 tag=03 len=9 but only 3 bytes follow
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.concat(CursorTestKit.hex("01 03 09"), "abc".getBytes("UTF-8"))
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), decoded)

  pureTest("decode rejects reserved flag bits"):
    val flags = 0x40.toByte
    val cursor = CursorTestKit.buildCursor(
      flags = flags,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = Array.emptyByteArray
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("reserved flag bits set"), decoded)

  pureTest("decode rejects an over-long varint"):
    // 11 bytes of 0xff (continuation) — exceeds 10-byte varint limit
    val varint = Array.fill(11)(0xff.toByte)
    val cursor = CursorTestKit.buildCursor(
      flags = 0x00.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = varint
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("malformed varint"), decoded)

  pureTest("decode rejects an unrecognised keyset tag byte"):
    // count=1 tag=0x09
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("01 09")
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("unknown keyset value type"), decoded)

  pureTest("decode rejects extra bytes following a complete frame"):
    // valid offset payload (varint 0) plus one extra byte
    val cursor = CursorTestKit.buildCursor(
      flags = 0x00.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("00 ff")
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("trailing data after parse"), decoded)

  pureTest("decode rejects offset varint decoding to a negative Long"):
    // 10 bytes whose varint decodes to -1L when interpreted as a signed Long
    val payload = Array.fill(9)(0xff.toByte) :+ 0x01.toByte
    val cursor = CursorTestKit.buildCursor(
      flags = 0x00.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = payload
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("negative offset"), decoded)

  pureTest("decode rejects an out-of-range zone offset"):
    // count=1 tag=04 (TimestampV) epochSecond=0 nano=0 offsetSeconds=100000 (above ±18h ZoneOffset limit)
    val payload = CursorTestKit.hex("01 04 00 00 C0 9A 0C")
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = payload
    )
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid timestamp field"),
      Cursor.decode(cursor, baseQuery)
    )

  pureTest("decode rejects forged keyset cursor with huge varint count without allocating"):
    // varint = 0xff 0xff 0xff 0xff 0x07 -> ~2^31, far above MaxKeysetArity
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("ff ff ff ff 07")
    )
    expect.sameL(
      CursorDecodingError.MalformedCursor("keyset arity exceeds limit"),
      Cursor.decode(cursor, baseQuery)
    )

  pureTest("decode rejects forged keyset cursor with arity above max"):
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("11")
    )
    val decoded = Cursor.decode(cursor, baseQuery)

    expect.sameL(CursorDecodingError.MalformedCursor("keyset arity exceeds limit"), decoded)

  pureTest("decode rejects forged keyset cursor whose varint count overflows Long signedness"):
    // 10 bytes whose varint decodes to a negative Long when interpreted as signed
    val payload = Array.fill(9)(0xff.toByte) :+ 0x01.toByte
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = payload
    )
    expect.sameL(
      CursorDecodingError.MalformedCursor("keyset arity exceeds limit"),
      Cursor.decode(cursor, baseQuery)
    )

  pureTest("decode rejects forged IntV with zigzag-decoded value above Int.MaxValue"):
    // count=1 tag=01 (IntV) value varint = 0x80 0x80 0x80 0x80 0x10 (zigzagEncode of Int.MaxValue + 1L)
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("01 01 80 80 80 80 10")
    )
    expect.sameL(CursorDecodingError.MalformedCursor("integer out of range"), Cursor.decode(cursor, baseQuery))

  pureTest("decode rejects forged StringV with length above Int.MaxValue"):
    // count=1 tag=03 (StringV) length varint = 0x80 0x80 0x80 0x80 0x08 (decodes to 2^31, > Int.MaxValue)
    val cursor = CursorTestKit.buildCursor(
      flags = 0x02.toByte,
      hash = CursorTestKit.hashBytes(baseQuery),
      payload = CursorTestKit.hex("01 03 80 80 80 80 08")
    )
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid string length"),
      Cursor.decode(cursor, baseQuery)
    )

  pureTest("roundtrip with fully populated query"):
    val query = TestFixtures.fullyPopulatedQuery
    val decoded = DecodedCursor(Direction.Forward, Position.Offset.unsafe(40L))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("roundtrip for keyset containing Absent for an absentable field"):
    given KeysetField[TestField, Row] =
      KeysetField
        .uniqueBy(TestField.Id, (row: Row) => row.id)
        .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.LastSeen.ascending))
    val decoded =
      DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5L))))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("decode rejects Absent value in non-absentable field"):
    given KeysetField[TestField, Row] =
      KeysetField
        .uniqueBy(TestField.Id, (row: Row) => row.id)
        .withField(TestField.Name, (row: Row) => row.name)
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Name.ascending))
    // Forge a cursor with Absent in the Name slot (Name is registered required, not absentable).
    val decoded =
      DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(7L))))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameL(
      CursorDecodingError.IncompatibleCursor("anchor has Absent value in non-absentable field 'name'"),
      roundtrip
    )

  pureTest("decode rejects keyset cursor with too few values"):
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded =
      DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.LongV(7L))))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameL(CursorDecodingError.IncompatibleCursor("keyset arity does not match query"), roundtrip)

  pureTest("decode rejects keyset cursor with too many values"):
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded =
      DecodedCursor(
        Direction.Forward,
        Position.Keyset(List(KeysetValue.StringV("alice"), KeysetValue.LongV(7L), KeysetValue.LongV(99L)))
      )
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameL(CursorDecodingError.IncompatibleCursor("keyset arity does not match query"), roundtrip)

  pureTest("decode accepts empty keyset cursor (first-page) regardless of expected arity"):
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded = DecodedCursor(Direction.Forward, Position.Keyset(Nil))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("decode surfaces arity mismatch before Absent-in-required when both would fire"):
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.Name.ascending))
    val decoded =
      DecodedCursor(
        Direction.Forward,
        Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(7L), KeysetValue.LongV(99L)))
      )
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameL(CursorDecodingError.IncompatibleCursor("keyset arity does not match query"), roundtrip)

  pureTest("decode succeeds when two FIELD cases share the same column name as the id field"):
    given FieldSchema[AliasField] = FieldSchema.fromMapping:
      case AliasField.Id      => "id"
      case AliasField.IdAlias => "id"
      case AliasField.Other   => "other"

    given KeysetField[AliasField, Row] = KeysetField.uniqueBy(AliasField.Id, (row: Row) => row.id)

    val query = Query.empty[AliasField].copy(sortBys = ListSet(AliasField.IdAlias.ascending))
    val decoded =
      DecodedCursor(Direction.Forward, Position.Keyset(List(KeysetValue.LongV(7L), KeysetValue.LongV(42L))))
    val cursor = Cursor.encode(decoded, query)
    val roundtrip = Cursor.decode(cursor, query)

    expect.sameR(decoded, roundtrip)

  pureTest("toggling a field from required to absentable produces a different fingerprint (StaleCursor)"):
    val query = TestFixtures.emptyQueryWithId.copy(sortBys = ListSet(TestField.LastSeen.ascending))
    val cursor =
      locally:
        given KeysetField[TestField, Row] =
          KeysetField
            .uniqueBy(TestField.Id, (row: Row) => row.id)
            .withField(TestField.LastSeen, (row: Row) => row.lastSeen.getOrElse(""))
        Cursor.encode(DecodedCursor(Direction.Forward, Position.Keyset(Nil)), query)
    val roundtrip =
      locally:
        given KeysetField[TestField, Row] =
          KeysetField
            .uniqueBy(TestField.Id, (row: Row) => row.id)
            .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
        Cursor.decode(cursor, query)

    expect.sameL(CursorDecodingError.StaleCursor, roundtrip)
