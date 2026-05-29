package folio

import java.time.OffsetDateTime
import java.time.ZoneOffset

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

import folio.FolioError.CursorDecodingError
import TestFixtures.*

object CursorValueCodecSuite extends SimpleIOSuite with Checkers:

  test("Long: round-trips arbitrary values"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      val codec = CursorValueCodec[Long]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("Long: maps onto LongV"):
    expect.same(KeysetValue.LongV(42L), CursorValueCodec[Long].toKeysetValue(42L))

  pureTest("Long: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Long].fromKeysetValue(KeysetValue.StringV("abc"))
    expect.sameL(CursorDecodingError.MalformedKeysetValue("expected LongV, got StringV"), result)

  test("Int: round-trips arbitrary values"):
    forall(Gen.choose(Int.MinValue, Int.MaxValue)): value =>
      val codec = CursorValueCodec[Int]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("Int: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Int].fromKeysetValue(KeysetValue.LongV(42L))
    expect.sameL(CursorDecodingError.MalformedKeysetValue("expected IntV, got LongV"), result)

  test("String: round-trips arbitrary values including former separators"):
    forall(Gen.asciiPrintableStr): value =>
      val codec = CursorValueCodec[String]
      expect.sameR(value, codec.fromKeysetValue(codec.toKeysetValue(value)))

  pureTest("String: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[String].fromKeysetValue(KeysetValue.IntV(7))
    expect.sameL(CursorDecodingError.MalformedKeysetValue("expected StringV, got IntV"), result)

  pureTest("OffsetDateTime: round-trips an ISO-8601 timestamp"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    expect.sameR(timestamp, codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: round-trips with non-UTC offset and nanos"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 123_456_789, ZoneOffset.ofHours(-5))
    expect.sameR(timestamp, codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[OffsetDateTime].fromKeysetValue(KeysetValue.StringV("not-a-date"))
    expect.sameL(CursorDecodingError.MalformedKeysetValue("expected TimestampV, got StringV"), result)
