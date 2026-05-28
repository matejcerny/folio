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
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(0L)), Right(0L)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(42L)), Right(42L)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(-1L)), Right(-1L)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(Long.MaxValue)), Right(Long.MaxValue)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(Long.MinValue)), Right(Long.MinValue))
    ).combineAll

  pureTest("Long: maps onto LongV"):
    expect.same(CursorValueCodec[Long].toKeysetValue(42L), KeysetValue.LongV(42L))

  pureTest("Long: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Long].fromKeysetValue(KeysetValue.StringV("abc"))
    expect.same(
      result,
      Left(CursorDecodingError.MalformedKeysetValue("expected LongV, got StringV"))
    )

  pureTest("Int: round-trips arbitrary values"):
    val codec = CursorValueCodec[Int]
    List(
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(0)), Right(0)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(42)), Right(42)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(-1)), Right(-1)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(Int.MaxValue)), Right(Int.MaxValue)),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue(Int.MinValue)), Right(Int.MinValue))
    ).combineAll

  pureTest("Int: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[Int].fromKeysetValue(KeysetValue.LongV(42L))
    expect.same(
      result,
      Left(CursorDecodingError.MalformedKeysetValue("expected IntV, got LongV"))
    )

  pureTest("String: round-trips arbitrary values including former separators"):
    val codec = CursorValueCodec[String]
    List(
      expect.same(codec.fromKeysetValue(codec.toKeysetValue("")), Right("")),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue("hello")), Right("hello")),
      expect.same(codec.fromKeysetValue(codec.toKeysetValue("foo::bar;baz")), Right("foo::bar;baz"))
    ).combineAll

  pureTest("String: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[String].fromKeysetValue(KeysetValue.IntV(7))
    expect.same(
      result,
      Left(CursorDecodingError.MalformedKeysetValue("expected StringV, got IntV"))
    )

  pureTest("OffsetDateTime: round-trips an ISO-8601 timestamp"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    expect.same(codec.fromKeysetValue(codec.toKeysetValue(timestamp)), Right(timestamp))

  pureTest("OffsetDateTime: round-trips with non-UTC offset and nanos"):
    val codec = CursorValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 123_456_789, ZoneOffset.ofHours(-5))
    expect.same(codec.fromKeysetValue(codec.toKeysetValue(timestamp)), Right(timestamp))

  pureTest("OffsetDateTime: fromKeysetValue returns MalformedKeysetValue for variant mismatch"):
    val result = CursorValueCodec[OffsetDateTime].fromKeysetValue(KeysetValue.StringV("not-a-date"))
    expect.same(
      result,
      Left(CursorDecodingError.MalformedKeysetValue("expected TimestampV, got StringV"))
    )
