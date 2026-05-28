package folio

object KeysetSyntax:
  def keysetOf[V](values: V*)(using codec: CursorValueCodec[V]): Position.Keyset =
    Position.Keyset(values.iterator.map(codec.toKeysetValue).toList)
