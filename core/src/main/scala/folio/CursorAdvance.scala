package folio

private[folio] sealed trait CursorAdvance[FIELD, T]:
  def next(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position
  def previous(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position

private[folio] object CursorAdvance:
  def offsetOnly[FIELD, T]: CursorAdvance[FIELD, T] = new CursorAdvance[FIELD, T]:
    def next(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.next(limit)
        case keyset: Position.Keyset => keyset

    def previous(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.previous(limit)
        case keyset: Position.Keyset => keyset

  def keysetAware[FIELD, T](keysetField: KeysetField[FIELD, T]): CursorAdvance[FIELD, T] =
    new CursorAdvance[FIELD, T]:
      def next(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position =
        position match
          case offset: Position.Offset => offset.next(limit)
          case _: Position.Keyset      => rows.lastOption.fold(position)(encodeRow(ordering, _))

      def previous(position: Position, ordering: Vector[OrderBy[FIELD]], rows: Seq[T], limit: Limit): Position =
        position match
          case offset: Position.Offset => offset.previous(limit)
          case _: Position.Keyset      => rows.headOption.fold(position)(encodeRow(ordering, _))

      private def encodeRow(ordering: Vector[OrderBy[FIELD]], row: T): Position.Keyset =
        val cursorFields = cursorFieldsFor(ordering, keysetField.field)
        val values = cursorFields.map: field =>
          keysetField.fields
            .get(field)
            .map(_.encodedFromRow(row))
            .getOrElse(keysetField.codec.toFieldValue(keysetField.rowId(row)).present)

        Position.Keyset(values)

  private[folio] def cursorFieldsFor[FIELD](
      ordering: Vector[OrderBy[FIELD]],
      idField: FIELD
  ): List[FIELD] =
    val orderingFields = ordering.toList.map(_.field)
    if orderingFields.contains(idField) then orderingFields else orderingFields :+ idField
