package folio

case class DecodedCursor(direction: Direction, position: Position):
  def isFirst: Boolean = position match
    case Position.Keyset.First if direction == Direction.Forward => true
    case Position.Offset.First if direction == Direction.Forward => true
    case _                                                       => false

extension [FIELD: FieldSchema](query: Query[FIELD])
  inline def toCursor(direction: Direction = Direction.Forward)(using CursorCodec): Cursor =
    Cursor.encode(DecodedCursor(direction, Position.fromQuery(query)), query)

extension (decoded: DecodedCursor)
  def encode[FIELD: FieldSchema](query: Query[FIELD])(using CursorCodec): Cursor =
    Cursor.encode(decoded, query)
