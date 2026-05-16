package folio

private[folio] sealed trait CursorAdvance[T]:
  def next(position: Position, rows: Seq[T], limit: Limit): Position
  def previous(position: Position, rows: Seq[T], limit: Limit): Position

private[folio] object CursorAdvance:
  private def offsetNext(offset: Position.Offset, limit: Limit): Position.Offset =
    Position.Offset(offset.offset + limit.value)

  private def offsetPrevious(offset: Position.Offset, limit: Limit): Position.Offset =
    Position.Offset(math.max(0L, offset.offset - limit.value))

  def offsetOnly[T]: CursorAdvance[T] = new CursorAdvance[T]:
    def next(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offsetNext(offset, limit)
        case keyset: Position.Keyset => keyset

    def previous(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offsetPrevious(offset, limit)
        case keyset: Position.Keyset => keyset

  def keysetAware[T](rowId: RowId[T]): CursorAdvance[T] = new CursorAdvance[T]:
    def next(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offsetNext(offset, limit)
        case _: Position.Keyset      =>
          rows.lastOption.fold(position)(last => Position.Keyset(Some(rowId(last))))

    def previous(position: Position, rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offsetPrevious(offset, limit)
        case _: Position.Keyset      =>
          rows.headOption.fold(position)(first => Position.Keyset(Some(rowId(first))))
