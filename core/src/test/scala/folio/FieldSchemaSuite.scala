package folio

import weaver.SimpleIOSuite

object FieldSchemaSuite extends SimpleIOSuite:

  pureTest("name extension returns correct column name"):
    expect.same(TestField.Id.name, "id") and
      expect.same(TestField.Name.name, "name") and
      expect.same(TestField.CreatedAt.name, "created_at")

  pureTest("fromName roundtrip for all fields"):
    val schema = summon[FieldSchema[TestField]]
    expect.same(schema.fromName("id"), Right(TestField.Id)) and
      expect.same(schema.fromName("name"), Right(TestField.Name)) and
      expect.same(schema.fromName("created_at"), Right(TestField.CreatedAt))

  pureTest("fromName returns Left for unknown name"):
    val schema = summon[FieldSchema[TestField]]
    expect(clue(schema.fromName("unknown")).isLeft)
