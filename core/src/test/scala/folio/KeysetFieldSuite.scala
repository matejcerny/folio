package folio

import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object KeysetFieldSuite extends SimpleIOSuite:

  private val reregistrationMessage = "The unique field cannot be re-registered with withField"

  pureTest("uniqueBy registers the unique field as required (non-absentable)"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    List(
      expect.same(TestField.Id, keysetField.field),
      expect.same(Set(TestField.Id), keysetField.fields.keySet),
      expect.same(Set.empty[TestField], keysetField.absentableFields)
    ).combineAll

  pureTest("withField (T => V) registers a non-absentable field"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
    List(
      expect.same(Set(TestField.Id, TestField.Name), keysetField.fields.keySet),
      expect.same(Set.empty[TestField], keysetField.absentableFields)
    ).combineAll

  pureTest("withField (T => Option[V]) registers an absentable field"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, _.lastSeen)
    List(
      expect.same(Set(TestField.Id, TestField.LastSeen), keysetField.fields.keySet),
      expect.same(Set(TestField.LastSeen), keysetField.absentableFields)
    ).combineAll

  pureTest("absentableFields reports only the absentable fields"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
      .withField(TestField.CreatedAt, _.createdAt)
      .withField(TestField.LastSeen, _.lastSeen)
    expect.same(Set[TestField](TestField.LastSeen), keysetField.absentableFields)

  pureTest("absentable extractor encodes None as AnchorValue.Absent and Some as the inner codec value"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, _.lastSeen)
    val extractor = keysetField.fields(TestField.LastSeen)
    val rowWithValue = Row(0L, "alice", "2024-01-01", "2024-01-01", Some("2024-02-03"))
    val rowAbsent = Row(0L, "alice", "2024-01-01", "2024-01-01", None)
    List(
      expect.same(FieldValue.StringV("2024-02-03").present, extractor.encodedFromRow(rowWithValue)),
      expect.same(AnchorValue.Absent, extractor.encodedFromRow(rowAbsent)),
      expect(extractor.isAbsentable)
    ).combineAll

  pureTest("required extractor encodes the value via the inner codec and is not absentable"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.Name, _.name)
    val extractor = keysetField.fields(TestField.Name)
    val row = Row(0L, "alice", "2024-01-01", "2024-01-01", None)
    List(
      expect.same(FieldValue.StringV("alice").present, extractor.encodedFromRow(row)),
      expect(!extractor.isAbsentable)
    ).combineAll

  pureTest("withField (T => V) rejects re-registering the unique field"):
    val attempt = Try(
      KeysetField
        .uniqueBy(TestField.Id, (row: Row) => row.id)
        .withField(TestField.Id, (row: Row) => row.name)
    )
    List(
      expect(attempt.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException])),
      expect.same(Some(reregistrationMessage), attempt.failed.toOption.map(_.getMessage))
    ).combineAll

  pureTest("withField (T => Option[V]) rejects re-registering the unique field"):
    val attempt = Try(
      KeysetField
        .uniqueBy(TestField.Id, (row: Row) => row.id)
        .withField(TestField.Id, (row: Row) => row.lastSeen)
    )
    List(
      expect(attempt.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException])),
      expect.same(Some(reregistrationMessage), attempt.failed.toOption.map(_.getMessage))
    ).combineAll

  pureTest("uniqueBy does not accept Option-typed extractor (compile error)"):
    val errors = typeCheckErrors(
      "KeysetField.uniqueBy(folio.TestField.Id, (row: folio.Row) => row.lastSeen)"
    )
    expect(errors.nonEmpty)

  pureTest("FieldValueCodec[Option[Long]] is not derived (compile error)"):
    val errors = typeCheckErrors("summon[folio.FieldValueCodec[Option[Long]]]")
    expect(errors.nonEmpty)
