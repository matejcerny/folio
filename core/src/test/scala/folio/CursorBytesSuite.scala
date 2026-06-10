package folio

import cats.data.Chain
import cats.syntax.foldable.*
import folio.CursorBytes.*
import folio.FolioError.*
import TestFixtures.*

import java.time.{ Instant, OffsetDateTime, ZoneOffset }

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object CursorBytesSuite extends SimpleIOSuite with Checkers:

  private def runRead[A](bytes: Chain[Byte])(read: Read[A]): Either[CursorDecodingError, A] =
    read.runA(ReaderState(bytes.toList.toArray, 0))

  private given cats.Show[OffsetDateTime] = cats.Show.fromToString

  private val validTimestamp: Gen[OffsetDateTime] =
    for
      epochSecond <- Gen.choose(0L, Int.MaxValue.toLong)
      nano <- Gen.choose(0, 999_999_999)
      offsetHours <- Gen.choose(-18, 18)
    yield OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano.toLong), ZoneOffset.ofHours(offsetHours))

  // --- zigzag ---

  test("zigzagEncode then zigzagDecode is identity"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      expect.same(value, zigzagDecode(zigzagEncode(value)))

  pureTest("zigzagEncode produces correct values for small inputs"):
    List(
      expect.same(0L, zigzagEncode(0L)),
      expect.same(2L, zigzagEncode(1L)),
      expect.same(1L, zigzagEncode(-1L)),
      expect.same(4L, zigzagEncode(2L)),
      expect.same(3L, zigzagEncode(-2L))
    ).combineAll

  // --- intBigEndian ---

  pureTest("intBigEndian encodes zero as four zero bytes"):
    expect.same(List(0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte), intBigEndian(0).toList)

  pureTest("intBigEndian encodes 0x01020304 in big-endian order"):
    expect.same(List(0x01.toByte, 0x02.toByte, 0x03.toByte, 0x04.toByte), intBigEndian(0x01020304).toList)

  pureTest("intBigEndian encodes -1 as four 0xff bytes"):
    expect.same(List(0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte), intBigEndian(-1).toList)

  // --- unsignedVarint ---

  pureTest("unsignedVarint encodes 0 as a single zero byte"):
    expect.same(List(0x00.toByte), unsignedVarint(0L).toList)

  pureTest("unsignedVarint encodes 127 as a single byte without continuation"):
    expect.same(List(0x7f.toByte), unsignedVarint(127L).toList)

  pureTest("unsignedVarint encodes 128 as two bytes with continuation bit"):
    expect.same(List(0x80.toByte, 0x01.toByte), unsignedVarint(128L).toList)

  test("unsignedVarint round-trips via readUnsignedVarint"):
    forall(Gen.choose(0L, Long.MaxValue)): value =>
      expect.sameR(value, runRead(unsignedVarint(value))(readUnsignedVarint))

  // --- intBytes / readInt ---

  test("intBytes round-trips via readInt"):
    forall(Gen.choose(Int.MinValue, Int.MaxValue)): value =>
      expect.sameR(value, runRead(intBytes(value))(readInt))

  pureTest("readInt rejects zigzag-decoded value above Int.MaxValue"):
    val outOfRange = Int.MaxValue.toLong + 1L
    val bytes = unsignedVarint(zigzagEncode(outOfRange))
    expect.sameL(CursorDecodingError.MalformedCursor("integer out of range"), runRead(bytes)(readInt))

  pureTest("readInt rejects zigzag-decoded value below Int.MinValue"):
    val outOfRange = Int.MinValue.toLong - 1L
    val bytes = unsignedVarint(zigzagEncode(outOfRange))
    expect.sameL(CursorDecodingError.MalformedCursor("integer out of range"), runRead(bytes)(readInt))

  // --- longBytes / readLong ---

  test("longBytes round-trips via readLong"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      expect.sameR(value, runRead(longBytes(value))(readLong))

  // --- stringBytes / readString ---

  test("stringBytes round-trips via readString"):
    forall(Gen.asciiPrintableStr): value =>
      expect.sameR(value, runRead(stringBytes(value))(readString))

  pureTest("readString rejects length above Int.MaxValue"):
    val tooLarge = Int.MaxValue.toLong + 1L
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid string length"),
      runRead(unsignedVarint(tooLarge) ++ Chain.fromSeq("abc".getBytes("UTF-8").toSeq))(readString)
    )

  pureTest("readString accepts length exactly Int.MaxValue but reports truncation when buffer is short"):
    val payload = unsignedVarint(Int.MaxValue.toLong) ++ Chain.fromSeq("abc".getBytes("UTF-8").toSeq)
    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), runRead(payload)(readString))

  pureTest("readString rejects negative-Long varint length"):
    // 10 bytes whose varint decodes to a negative Long
    val negativeVarint = Chain.fromSeq((Array.fill(9)(0xff.toByte) :+ 0x01.toByte).toSeq)
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid string length"),
      runRead(negativeVarint)(readString)
    )

  pureTest("readBytes rejects Int.MaxValue count from a non-zero index without overflowing"):
    // Buffer of 8 bytes; advance index by 1 then attempt to read Int.MaxValue.
    // Old check: state.index + count = 1 + Int.MaxValue overflows to Int.MinValue, passes <= 8.
    // New check: count <= state.bytes.length - state.index → Int.MaxValue <= 7 → false.
    val state = ReaderState(Array.fill(8)(0x00.toByte), index = 1)
    expect.sameL(CursorDecodingError.MalformedCursor("truncated"), readBytes(Int.MaxValue).runA(state))

  // --- timestampBytes / readTimestamp ---

  test("timestampBytes round-trips via readTimestamp"):
    forall(validTimestamp): timestamp =>
      expect.sameR(timestamp, runRead(timestampBytes(timestamp))(readTimestamp))

  pureTest("readTimestamp accepts boundary nano = 999_999_999 and offset = ±18 * 3600"):
    val maxOffsetSeconds = 18 * 3600
    List(
      OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 999_999_999, ZoneOffset.ofTotalSeconds(maxOffsetSeconds)),
      OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 999_999_999, ZoneOffset.ofTotalSeconds(-maxOffsetSeconds))
    ).map: timestamp =>
      expect.sameR(timestamp, runRead(timestampBytes(timestamp))(readTimestamp))
    .combineAll

  pureTest("readTimestamp rejects nano = 1_000_000_000"):
    // epochSecond=0, nano=1_000_000_000 (one above legal max), offsetSeconds=0
    val bytes = longBytes(0L) ++ unsignedVarint(1_000_000_000L) ++ longBytes(0L)
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid timestamp field"),
      runRead(bytes)(readTimestamp)
    )

  pureTest("readTimestamp rejects offsetSeconds above 18 * 3600"):
    val tooLarge = 18L * 3600 + 1
    val bytes = longBytes(0L) ++ unsignedVarint(0L) ++ longBytes(tooLarge)
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid timestamp field"),
      runRead(bytes)(readTimestamp)
    )

  pureTest("readTimestamp rejects offsetSeconds below -18 * 3600"):
    val tooSmall = -18L * 3600 - 1
    val bytes = longBytes(0L) ++ unsignedVarint(0L) ++ longBytes(tooSmall)
    expect.sameL(
      CursorDecodingError.MalformedCursor("invalid timestamp field"),
      runRead(bytes)(readTimestamp)
    )

  pureTest("readTimestamp rejects epoch second exceeding Instant bounds"):
    // epochSecond = Long.MaxValue is far above Instant.MAX (31556889864403199), so OffsetDateTime.ofInstant throws.
    val bytes = longBytes(Long.MaxValue) ++ unsignedVarint(0L) ++ longBytes(0L)
    expect.sameL(
      CursorDecodingError.MalformedCursor("malformed timestamp"),
      runRead(bytes)(readTimestamp)
    )
