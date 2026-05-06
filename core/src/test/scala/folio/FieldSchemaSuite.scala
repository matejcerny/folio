package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object FieldSchemaSuite extends SimpleIOSuite:

  pureTest("name extension returns correct column name"):
    List(
      expect.same(TestField.Id.name, "id"),
      expect.same(TestField.Name.name, "name"),
      expect.same(TestField.CreatedAt.name, "created_at")
    ).combineAll

  pureTest("fromName roundtrip for all fields"):
    val schema = summon[FieldSchema[TestField]]

    List(
      expect.same(schema.fromName("id"), Right(TestField.Id)),
      expect.same(schema.fromName("name"), Right(TestField.Name)),
      expect.same(schema.fromName("created_at"), Right(TestField.CreatedAt))
    ).combineAll

  pureTest("fromName returns Left for unknown name"):
    val schema = summon[FieldSchema[TestField]]

    expect(clue(schema.fromName("unknown")).isLeft)
