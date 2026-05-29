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
      expect.sameR(value, runRead(unsignedVarint(value))(readUnsignedVarint("test")))

  // --- intBytes / readInt ---

  test("intBytes round-trips via readInt"):
    forall(Gen.choose(Int.MinValue, Int.MaxValue)): value =>
      expect.sameR(value, runRead(intBytes(value))(readInt("test")))

  pureTest("readInt rejects zigzag-decoded value above Int.MaxValue with IntOutOfRange"):
    val outOfRange = Int.MaxValue.toLong + 1L
    val bytes = unsignedVarint(zigzagEncode(outOfRange))
    expect.sameL(CursorDecodingError.IntOutOfRange("test", outOfRange), runRead(bytes)(readInt("test")))

  pureTest("readInt rejects zigzag-decoded value below Int.MinValue with IntOutOfRange"):
    val outOfRange = Int.MinValue.toLong - 1L
    val bytes = unsignedVarint(zigzagEncode(outOfRange))
    expect.sameL(CursorDecodingError.IntOutOfRange("test", outOfRange), runRead(bytes)(readInt("test")))

  // --- longBytes / readLong ---

  test("longBytes round-trips via readLong"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      expect.sameR(value, runRead(longBytes(value))(readLong("test")))

  // --- stringBytes / readString ---

  test("stringBytes round-trips via readString"):
    forall(Gen.asciiPrintableStr): value =>
      expect.sameR(value, runRead(stringBytes(value))(readString("test")))

  pureTest("readString rejects length above Int.MaxValue with MalformedStringLength"):
    val tooLarge = Int.MaxValue.toLong + 1L
    expect.sameL(
      CursorDecodingError.MalformedStringLength("test", tooLarge),
      runRead(unsignedVarint(tooLarge) ++ Chain.fromSeq("abc".getBytes("UTF-8").toSeq))(readString("test"))
    )

  pureTest("readString accepts length exactly Int.MaxValue but propagates Truncated when buffer is short"):
    val payload = unsignedVarint(Int.MaxValue.toLong) ++ Chain.fromSeq("abc".getBytes("UTF-8").toSeq)
    expect.sameL(CursorDecodingError.Truncated("test bytes"), runRead(payload)(readString("test")))

  pureTest("readString rejects negative-Long varint length with MalformedStringLength"):
    // 10 bytes whose varint decodes to a negative Long
    val negativeVarint = Chain.fromSeq((Array.fill(9)(0xff.toByte) :+ 0x01.toByte).toSeq)
    runRead(negativeVarint)(readString("test")) match
      case Left(CursorDecodingError.MalformedStringLength("test", length)) if length < 0L => success
      case other => failure(s"expected MalformedStringLength with negative length, got $other")

  pureTest("readBytes rejects Int.MaxValue count from a non-zero index without overflowing"):
    // Buffer of 8 bytes; advance index by 1 then attempt to read Int.MaxValue.
    // Old check: state.index + count = 1 + Int.MaxValue overflows to Int.MinValue, passes <= 8.
    // New check: count <= state.bytes.length - state.index → Int.MaxValue <= 7 → false.
    val state = ReaderState(Array.fill(8)(0x00.toByte), index = 1)
    expect.sameL(CursorDecodingError.Truncated("payload"), readBytes(Int.MaxValue, "payload").runA(state))

  // --- timestampBytes / readTimestamp ---

  test("timestampBytes round-trips via readTimestamp"):
    forall(validTimestamp): timestamp =>
      expect.sameR(timestamp, runRead(timestampBytes(timestamp))(readTimestamp("test")))

  pureTest("readTimestamp accepts boundary nano = 999_999_999 and offset = ±18 * 3600"):
    val maxOffsetSeconds = 18 * 3600
    List(
      OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 999_999_999, ZoneOffset.ofTotalSeconds(maxOffsetSeconds)),
      OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 999_999_999, ZoneOffset.ofTotalSeconds(-maxOffsetSeconds))
    ).map: timestamp =>
      expect.sameR(timestamp, runRead(timestampBytes(timestamp))(readTimestamp("test")))
    .combineAll

  pureTest("readTimestamp rejects nano = 1_000_000_000 with MalformedTimestampField"):
    // epochSecond=0, nano=1_000_000_000 (one above legal max), offsetSeconds=0
    val bytes = longBytes(0L) ++ unsignedVarint(1_000_000_000L) ++ longBytes(0L)
    expect.sameL(
      CursorDecodingError.MalformedTimestampField("test nano", 1_000_000_000L),
      runRead(bytes)(readTimestamp("test"))
    )

  pureTest("readTimestamp rejects offsetSeconds above 18 * 3600 with MalformedTimestampField"):
    val tooLarge = 18L * 3600 + 1
    val bytes = longBytes(0L) ++ unsignedVarint(0L) ++ longBytes(tooLarge)
    expect.sameL(
      CursorDecodingError.MalformedTimestampField("test offset-seconds", tooLarge),
      runRead(bytes)(readTimestamp("test"))
    )

  pureTest("readTimestamp rejects offsetSeconds below -18 * 3600 with MalformedTimestampField"):
    val tooSmall = -18L * 3600 - 1
    val bytes = longBytes(0L) ++ unsignedVarint(0L) ++ longBytes(tooSmall)
    expect.sameL(
      CursorDecodingError.MalformedTimestampField("test offset-seconds", tooSmall),
      runRead(bytes)(readTimestamp("test"))
    )

  pureTest("readTimestamp returns MalformedTimestamp when epoch second exceeds Instant bounds"):
    // epochSecond = Long.MaxValue is far above Instant.MAX (31556889864403199), so OffsetDateTime.ofInstant throws.
    val bytes = longBytes(Long.MaxValue) ++ unsignedVarint(0L) ++ longBytes(0L)
    runRead(bytes)(readTimestamp("test")) match
      case Left(_: CursorDecodingError.MalformedTimestamp) => success
      case other                                           => failure(s"expected MalformedTimestamp, got $other")
