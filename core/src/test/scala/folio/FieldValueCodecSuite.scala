package folio

import java.time.OffsetDateTime
import java.time.ZoneOffset

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object FieldValueCodecSuite extends SimpleIOSuite with Checkers:

  test("Long: round-trips arbitrary values"):
    forall(Gen.choose(Long.MinValue, Long.MaxValue)): value =>
      val codec = FieldValueCodec[Long]
      expect.same(Some(value), codec.fromFieldValue(codec.toFieldValue(value)))

  pureTest("Long: maps onto LongV"):
    expect.same(FieldValue.LongV(42L), FieldValueCodec[Long].toFieldValue(42L))

  pureTest("Long: fromFieldValue rejects variant mismatch"):
    expect.same(None, FieldValueCodec[Long].fromFieldValue(FieldValue.StringV("abc")))

  test("Int: round-trips arbitrary values"):
    forall(Gen.choose(Int.MinValue, Int.MaxValue)): value =>
      val codec = FieldValueCodec[Int]
      expect.same(Some(value), codec.fromFieldValue(codec.toFieldValue(value)))

  pureTest("Int: fromFieldValue rejects variant mismatch"):
    expect.same(None, FieldValueCodec[Int].fromFieldValue(FieldValue.LongV(42L)))

  test("String: round-trips arbitrary values including former separators"):
    forall(Gen.asciiPrintableStr): value =>
      val codec = FieldValueCodec[String]
      expect.same(Some(value), codec.fromFieldValue(codec.toFieldValue(value)))

  pureTest("String: fromFieldValue rejects variant mismatch"):
    expect.same(None, FieldValueCodec[String].fromFieldValue(FieldValue.IntV(7)))

  pureTest("OffsetDateTime: round-trips an ISO-8601 timestamp"):
    val codec = FieldValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    expect.same(Some(timestamp), codec.fromFieldValue(codec.toFieldValue(timestamp)))

  pureTest("OffsetDateTime: round-trips with non-UTC offset and nanos"):
    val codec = FieldValueCodec[OffsetDateTime]
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 123_456_789, ZoneOffset.ofHours(-5))
    expect.same(Some(timestamp), codec.fromFieldValue(codec.toFieldValue(timestamp)))

  pureTest("OffsetDateTime: fromFieldValue rejects variant mismatch"):
    expect.same(None, FieldValueCodec[OffsetDateTime].fromFieldValue(FieldValue.StringV("not-a-date")))
