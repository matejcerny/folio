package folio

import cats.syntax.foldable.*
import weaver.SimpleIOSuite
import TestFixtures.*

object FieldSchemaSuite extends SimpleIOSuite:

  pureTest("name extension returns correct column name"):
    List(
      expect.same("id", TestField.Id.name),
      expect.same("name", TestField.Name.name),
      expect.same("created_at", TestField.CreatedAt.name)
    ).combineAll

  pureTest("fromName roundtrip for all fields"):
    val schema = summon[FieldSchema[TestField]]

    List(
      expect.sameR(TestField.Id, schema.fromName("id")),
      expect.sameR(TestField.Name, schema.fromName("name")),
      expect.sameR(TestField.CreatedAt, schema.fromName("created_at"))
    ).combineAll

  pureTest("fromName returns Left for unknown name"):
    val schema = summon[FieldSchema[TestField]]

    expect(clue(schema.fromName("unknown")).isLeft)
