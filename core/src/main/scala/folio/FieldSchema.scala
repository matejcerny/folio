package folio

/** Typeclass that binds a user-defined field type to a string identifier used as the column/attribute name in queries
  * and cursor encoding.
  */
trait FieldSchema[FIELD]:
  def name(field: FIELD): String
  def fromName(name: String): Either[String, FIELD]

extension [FIELD](field: FIELD)(using fieldSchema: FieldSchema[FIELD]) def name: String = fieldSchema.name(field)

/** Identifies the "id" field within FIELD. Used by [[CursorPosition.fromQuery]] to choose between [[CursorPosition.Id]]
  * (keyset) and [[CursorPosition.Incremental]] (offset) strategies.
  */
trait IdField[FIELD]:
  def idField: FIELD
