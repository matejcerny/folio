package folio

import java.time.OffsetDateTime
import java.time.ZoneOffset

import scala.compiletime.testing.typeCheckErrors

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object FilterBySuite extends SimpleIOSuite:

  pureTest("ExactMatch keeps field and raw value"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    List(
      expect.same(TestField.Name, filter.field),
      expect.same("alice", filter.value)
    ).combineAll

  pureTest("ExactMatch encodes each supported value type"):
    val timestamp = OffsetDateTime.of(2024, 1, 5, 12, 34, 56, 0, ZoneOffset.UTC)
    List(
      expect.same(FieldValue.StringV("alice"), FilterBy.ExactMatch(TestField.Name, "alice").encodedValue),
      expect.same(FieldValue.IntV(42), FilterBy.ExactMatch(TestField.Id, 42).encodedValue),
      expect.same(FieldValue.LongV(42L), FilterBy.ExactMatch(TestField.Id, 42L).encodedValue),
      expect.same(
        FieldValue.TimestampV(timestamp),
        FilterBy.ExactMatch(TestField.CreatedAt, timestamp).encodedValue
      )
    ).combineAll

  pureTest("same numeric payload encodes differently across types"):
    // Mirrors FieldValueSuite's cross-variant inequality: IntV(1) and LongV(1) are not equal.
    expect(
      FilterBy.ExactMatch(TestField.Id, 1).encodedValue !=
        FilterBy.ExactMatch(TestField.Id, 1L).encodedValue
    )

  pureTest("conjunctive Set keeps distinct values and collapses identical filters"):
    val alice = FilterBy.ExactMatch(TestField.Name, "alice")
    val bob = FilterBy.ExactMatch(TestField.Name, "bob")
    List(
      expect.same(2, Set(alice, bob).size),
      expect.same(1, Set(alice, alice).size)
    ).combineAll

  pureTest("equality keys on the encoded value, not the raw value"):
    // Raw 1 and 1L are `==` under boxed numeric equality; the encoded variants are not, and the
    // encoded variant is what gets rendered and fingerprinted, so both predicates survive the Set.
    val asInt = FilterBy.ExactMatch(TestField.Id, 1)
    val asLong = FilterBy.ExactMatch(TestField.Id, 1L)
    List(
      expect(asInt != asLong),
      expect(asInt.hashCode != asLong.hashCode),
      expect.same(2, Set[FilterBy[TestField]](asInt, asLong).size)
    ).combineAll

  pureTest("equality ignores the value type when the encoding matches"):
    val filter = FilterBy.ExactMatch(TestField.Name, "alice")
    List(
      expect.same(FilterBy.ExactMatch(TestField.Name, "alice"), filter),
      expect(FilterBy.ExactMatch(TestField.Description, "alice") != filter)
    ).combineAll

  pureTest("ExactMatch does not require FieldSchema"):
    // AliasField has no FieldSchema given outside one scoped CursorSuite block.
    val filter = FilterBy.ExactMatch(AliasField.Other, "x")
    List(
      expect.same(AliasField.Other, filter.field),
      expect.same("x", filter.value),
      expect.same(FieldValue.StringV("x"), filter.encodedValue)
    ).combineAll

  pureTest("unsupported value type does not compile"):
    val errors = typeCheckErrors("folio.FilterBy.ExactMatch(folio.TestField.Name, true)")
    List(
      expect(errors.nonEmpty),
      expect(errors.exists(_.message.contains("FieldValueCodec")))
    ).combineAll
