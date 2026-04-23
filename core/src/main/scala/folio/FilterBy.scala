package folio

sealed trait FilterBy[FIELD: FieldSchema]:
  def field: FIELD
  def value: String

object FilterBy:
  case class ExactMatch[FIELD: FieldSchema](field: FIELD, value: String) extends FilterBy[FIELD]
