package folio

object KeysetSyntax:
  def keysetOf[V](values: V*)(using codec: FieldValueCodec[V]): Position.Keyset =
    Position.Keyset(values.iterator.map(codec.toFieldValue(_).present).toList)
