package folio

import java.time.{ OffsetDateTime, ZoneOffset }

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object CanonicalFiltersSuite extends SimpleIOSuite:

  /** Names chosen so an encoding without explicit boundaries could be imitated by a crafted string value. */
  private enum DelimiterField:
    case A, C, AC

  private given FieldSchema[DelimiterField] = FieldSchema.fromMapping:
    case DelimiterField.A  => "a"
    case DelimiterField.C  => "c"
    case DelimiterField.AC => "ac"

  private val utc = OffsetDateTime.of(2024, 1, 5, 12, 0, 0, 0, ZoneOffset.UTC)

  pureTest("sorted orders by field name"):
    val name = FilterBy.ExactMatch(TestField.Name, "alice")
    val createdAt = FilterBy.ExactMatch(TestField.CreatedAt, "2024-01-05")
    val id = FilterBy.ExactMatch(TestField.Id, 1L)
    expect.same(
      Vector(createdAt, id, name),
      CanonicalFilters.sorted(Set[FilterBy[TestField]](name, id, createdAt))
    )

  pureTest("sorted does not depend on Set insertion order"):
    val name = FilterBy.ExactMatch(TestField.Name, "alice")
    val createdAt = FilterBy.ExactMatch(TestField.CreatedAt, "2024-01-05")
    val id = FilterBy.ExactMatch(TestField.Id, 1L)
    expect.same(
      CanonicalFilters.sorted(Set[FilterBy[TestField]](name, id, createdAt)),
      CanonicalFilters.sorted(Set[FilterBy[TestField]](createdAt, name, id))
    )

  pureTest("sorted breaks same-field ties on the encoded value"):
    val ann = FilterBy.ExactMatch(TestField.Name, "ann")
    val bob = FilterBy.ExactMatch(TestField.Name, "bob")
    expect.same(Vector(ann, bob), CanonicalFilters.sorted(Set[FilterBy[TestField]](bob, ann)))

  pureTest("sorted compares the encoded bytes, so a shorter string value sorts first"):
    // The string encoding is length-prefixed, so the length leads the comparison rather than the text.
    // Any total order will do; this pins which one folio picked.
    val bob = FilterBy.ExactMatch(TestField.Name, "bob")
    val alice = FilterBy.ExactMatch(TestField.Name, "alice")
    expect.same(Vector(bob, alice), CanonicalFilters.sorted(Set[FilterBy[TestField]](alice, bob)))

  pureTest("sorted compares encoded value bytes unsigned"):
    // Equal-length UTF-8 payloads: '~' is 0x7e, 'é' is 0xc3 0xa9. Signed byte comparison would read 0xc3 as
    // -61 and sort the accented value first.
    val tilde = FilterBy.ExactMatch(TestField.Name, "~a")
    val accented = FilterBy.ExactMatch(TestField.Name, "é")
    expect.same(Vector(tilde, accented), CanonicalFilters.sorted(Set[FilterBy[TestField]](accented, tilde)))

  pureTest("sorted separates same-field filters that differ only in value type"):
    val asInt = FilterBy.ExactMatch(TestField.Id, 1)
    val asLong = FilterBy.ExactMatch(TestField.Id, 1L)
    val canonical = CanonicalFilters.sorted(Set[FilterBy[TestField]](asLong, asInt))
    List(
      expect.same(Vector(asInt, asLong), canonical),
      expect.same(Vector(FieldValue.IntV(1), FieldValue.LongV(1L)), canonical.map(_.encodedValue))
    ).combineAll

  pureTest("fingerprintPart is empty for an unfiltered query"):
    expect.same("", CanonicalFilters.fingerprintPart(Set.empty[FilterBy[TestField]]))

  pureTest("fingerprintPart encodes field name length, predicate tag and tagged value"):
    // 04 'n' 'a' 'm' 'e' | 01 exact | 03 StringV 05 'a' 'l' 'i' 'c' 'e'
    expect.same(
      "046e616d65010305616c696365",
      CanonicalFilters.fingerprintPart(Set[FilterBy[TestField]](FilterBy.ExactMatch(TestField.Name, "alice")))
    )

  pureTest("fingerprintPart does not depend on Set insertion order"):
    val name = FilterBy.ExactMatch(TestField.Name, "alice")
    val id = FilterBy.ExactMatch(TestField.Id, 1L)
    expect.same(
      CanonicalFilters.fingerprintPart(Set[FilterBy[TestField]](name, id)),
      CanonicalFilters.fingerprintPart(Set[FilterBy[TestField]](id, name))
    )

  pureTest("fingerprintPart differs by field, by value and by value type"):
    def part(filter: FilterBy[TestField]): String = CanonicalFilters.fingerprintPart(Set(filter))
    val name = part(FilterBy.ExactMatch(TestField.Name, "alice"))
    val otherField = part(FilterBy.ExactMatch(TestField.Description, "alice"))
    val otherValue = part(FilterBy.ExactMatch(TestField.Name, "bob"))
    val asInt = part(FilterBy.ExactMatch(TestField.Id, 1))
    val asLong = part(FilterBy.ExactMatch(TestField.Id, 1L))
    List(
      expect(name != otherField),
      expect(name != otherValue),
      expect(asInt != asLong)
    ).combineAll

  pureTest("a crafted string value cannot imitate a second filter entry"):
    // A delimiter-joined encoding would render both of these as `a:exact:s:b,c:exact:s:d`.
    val twoFilters = Set[FilterBy[DelimiterField]](
      FilterBy.ExactMatch(DelimiterField.A, "b"),
      FilterBy.ExactMatch(DelimiterField.C, "d")
    )
    val craftedSingleFilter = Set[FilterBy[DelimiterField]](
      FilterBy.ExactMatch(DelimiterField.A, "b,c:exact:s:d")
    )
    expect(
      CanonicalFilters.fingerprintPart(twoFilters) != CanonicalFilters.fingerprintPart(craftedSingleFilter)
    )

  pureTest("a crafted string value cannot imitate the field-name boundary"):
    // Concatenating name and value without a length prefix would render both of these as `acd`.
    val nameCarriesTheValue = Set[FilterBy[DelimiterField]](FilterBy.ExactMatch(DelimiterField.A, "cd"))
    val valueCarriesTheName = Set[FilterBy[DelimiterField]](FilterBy.ExactMatch(DelimiterField.AC, "d"))
    expect(
      CanonicalFilters.fingerprintPart(nameCarriesTheValue) !=
        CanonicalFilters.fingerprintPart(valueCarriesTheName)
    )

  pureTest("TimestampV keeps the caller's offset instead of normalising to UTC"):
    // Same instant, different offsets: distinct filters, distinct fingerprints — consistent with
    // OffsetDateTime.equals and therefore with filter identity.
    val shifted = utc.withOffsetSameInstant(ZoneOffset.ofHours(2))
    val filters = Set[FilterBy[TestField]](
      FilterBy.ExactMatch(TestField.CreatedAt, utc),
      FilterBy.ExactMatch(TestField.CreatedAt, shifted)
    )
    List(
      expect.same(utc.toInstant, shifted.toInstant),
      expect.same(2, filters.size),
      expect(
        CanonicalFilters.fingerprintPart(Set[FilterBy[TestField]](FilterBy.ExactMatch(TestField.CreatedAt, utc))) !=
          CanonicalFilters
            .fingerprintPart(Set[FilterBy[TestField]](FilterBy.ExactMatch(TestField.CreatedAt, shifted)))
      )
    ).combineAll
