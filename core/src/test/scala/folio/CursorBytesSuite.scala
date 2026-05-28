package folio

import cats.data.Chain
import cats.syntax.foldable.*
import folio.CursorBytes.*
import folio.FolioError.*

import java.time.{ OffsetDateTime, ZoneOffset }

import weaver.SimpleIOSuite

object CursorBytesSuite extends SimpleIOSuite:

  private def runRead[A](bytes: Chain[Byte])(read: Read[A]): Either[CursorDecodingError, A] =
    read.runA(ReaderState(bytes.toList.toArray, 0))

  // --- zigzag ---

  pureTest("zigzagEncode then zigzagDecode is identity"):
    val values = List(0L, 1L, -1L, 2L, -2L, Long.MaxValue, Long.MinValue)
    values.map(value => expect.same(zigzagDecode(zigzagEncode(value)), value)).combineAll

  pureTest("zigzagEncode produces correct values for small inputs"):
    List(
      expect.same(zigzagEncode(0L), 0L),
      expect.same(zigzagEncode(1L), 2L),
      expect.same(zigzagEncode(-1L), 1L),
      expect.same(zigzagEncode(2L), 4L),
      expect.same(zigzagEncode(-2L), 3L)
    ).combineAll

  // --- intBigEndian ---

  pureTest("intBigEndian encodes zero as four zero bytes"):
    expect.same(intBigEndian(0).toList, List(0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte))

  pureTest("intBigEndian encodes 0x01020304 in big-endian order"):
    expect.same(intBigEndian(0x01020304).toList, List(0x01.toByte, 0x02.toByte, 0x03.toByte, 0x04.toByte))

  pureTest("intBigEndian encodes -1 as four 0xff bytes"):
    expect.same(
      intBigEndian(-1).toList,
      List(0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)
    )

  // --- unsignedVarint ---

  pureTest("unsignedVarint encodes 0 as a single zero byte"):
    expect.same(unsignedVarint(0L).toList, List(0x00.toByte))

  pureTest("unsignedVarint encodes 127 as a single byte without continuation"):
    expect.same(unsignedVarint(127L).toList, List(0x7f.toByte))

  pureTest("unsignedVarint encodes 128 as two bytes with continuation bit"):
    expect.same(unsignedVarint(128L).toList, List(0x80.toByte, 0x01.toByte))

  pureTest("unsignedVarint round-trips via readUnsignedVarint"):
    val values = List(0L, 1L, 127L, 128L, 255L, 16383L, 16384L, Int.MaxValue.toLong, Long.MaxValue >> 1)
    values
      .map: value =>
        expect.same(runRead(unsignedVarint(value))(readUnsignedVarint("test")), Right(value))
      .combineAll

  // --- intBytes / readInt ---

  pureTest("intBytes round-trips via readInt"):
    List(0, 1, -1, 42, -42, Int.MaxValue, Int.MinValue)
      .map: value =>
        expect.same(runRead(intBytes(value))(readInt("test")), Right(value))
      .combineAll

  // --- longBytes / readLong ---

  pureTest("longBytes round-trips via readLong"):
    List(0L, 1L, -1L, 42L, -42L, Long.MaxValue, Long.MinValue)
      .map: value =>
        expect.same(runRead(longBytes(value))(readLong("test")), Right(value))
      .combineAll

  // --- stringBytes / readString ---

  pureTest("stringBytes round-trips via readString"):
    List("", "hello", "café", "a::b", "a;b;c")
      .map: value =>
        expect.same(runRead(stringBytes(value))(readString("test")), Right(value))
      .combineAll

  // --- timestampBytes / readTimestamp ---

  pureTest("timestampBytes round-trips via readTimestamp"):
    List(
      OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC),
      OffsetDateTime.of(2024, 6, 1, 0, 0, 0, 500_000_000, ZoneOffset.ofHours(5)),
      OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-8))
    ).map: timestamp =>
      expect.same(runRead(timestampBytes(timestamp))(readTimestamp("test")), Right(timestamp))
    .combineAll
