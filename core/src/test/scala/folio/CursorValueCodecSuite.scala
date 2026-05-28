package folio

import java.time.OffsetDateTime
import java.time.ZoneOffset

import cats.syntax.foldable.*
import folio.FolioError.CursorDecodingError
import weaver.SimpleIOSuite

object CursorValueCodecSuite extends SimpleIOSuite:

  pureTest("Long: round-trips arbitrary values"):
    val codec = CursorValueCodec[Long]
    List(
      expect.same(Right(0L), codec.fromKeysetValue(codec.toKeysetValue(0L))),
      expect.same(Right(42L), codec.fromKeysetValue(codec.toKeysetValue(42L))),
      expect.same(Right(-1L), codec.fromKeysetValue(codec.toKeysetValue(-1L))),
      expect.same(Right(Long.MaxValue), codec.fromKeysetValue(codec.toKeysetValue(Long.MaxValue))),
      expect.same(Right(Long.MinValue), codec.fromKeysetValue(codec.toKeysetValue(Long.MinValue)))
    ).combineAll

  pureTest("Long: maps onto LongV"):
    expect.same(KeysetValue.LongV(42L), CursorValueCodec[Long].toKeysetValue(42L))

  pureTest("Long: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Long].fromKeysetValue(KeysetValue.StringV("abc"))
    expect.same(Left(CursorDecodingError.MalformedKeysetValue("expected LongV, got StringV")), result)

  pureTest("Int: round-trips arbitrary values"):
    val codec = CursorValueCodec[Int]
    List(
      expect.same(Right(0), codec.fromKeysetValue(codec.toKeysetValue(0))),
      expect.same(Right(42), codec.fromKeysetValue(codec.toKeysetValue(42))),
      expect.same(Right(-1), codec.fromKeysetValue(codec.toKeysetValue(-1))),
      expect.same(Right(Int.MaxValue), codec.fromKeysetValue(codec.toKeysetValue(Int.MaxValue))),
      expect.same(Right(Int.MinValue), codec.fromKeysetValue(codec.toKeysetValue(Int.MinValue)))
    ).combineAll

  pureTest("Int: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Int].fromKeysetValue(KeysetValue.LongV(42L))
    expect.same(Left(CursorDecodingError.MalformedKeysetValue("expected IntV, got LongV")), result)

  pureTest("String: round-trips arbitrary values including former separators"):
    val codec = CursorValueCodec[String]
    List(
      expect.same(Right(""), codec.fromKeysetValue(codec.toKeysetValue(""))),
      expect.same(Right("hello"), codec.fromKeysetValue(codec.toKeysetValue("hello"))),
      expect.same(Right("foo::bar;baz"), codec.fromKeysetValue(codec.toKeysetValue("foo::bar;baz")))
    ).combineAll

  pureTest("String: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[String].fromKeysetValue(KeysetValue.IntV(7))
    expect.same(Left(CursorDecodingError.MalformedKeysetValue("expected StringV, got IntV")), result)

  pureTest("OffsetDateTime: round-trips an ISO-8601 timestamp"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    expect.same(Right(timestamp), codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: round-trips with non-UTC offset and nanos"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 123_456_789, ZoneOffset.ofHours(-5))
    expect.same(Right(timestamp), codec.fromKeysetValue(codec.toKeysetValue(timestamp)))

  pureTest("OffsetDateTime: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[OffsetDateTime].fromKeysetValue(KeysetValue.StringV("not-a-date"))
    expect.same(Left(CursorDecodingError.MalformedKeysetValue("expected TimestampV, got StringV")), result)
