package folio

private[folio] sealed trait CursorAdvance[T]:
  def next(position: Position, rows: Seq[T], limit: Limit): Position
  def previous(position: Position, rows: Seq[T], limit: Limit): Position

private[folio] object CursorAdvance:
  def offsetOnly[T]: CursorAdvance[T] = new CursorAdvance[T]:
    def next(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.next(limit)
        case keyset: Position.Keyset => keyset

    def previous(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.previous(limit)
        case keyset: Position.Keyset => keyset

  def keysetAware[T](keysetField: KeysetField[?, T]): CursorAdvance[T] = new CursorAdvance[T]:
    def next(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.next(limit)
        case _: Position.Keyset      =>
          rows.lastOption.fold(position): last =>
            Position.Keyset(List(keysetField.codec.toKeysetValue(keysetField.rowId(last))))

    def previous(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.previous(limit)
        case _: Position.Keyset      =>
          rows.headOption.fold(position): first =>
            Position.Keyset(List(keysetField.codec.toKeysetValue(keysetField.rowId(first))))
