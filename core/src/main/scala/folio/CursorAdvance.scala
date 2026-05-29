package folio

import scala.collection.immutable.ListSet

private[folio] sealed trait CursorAdvance[FIELD, T]:
  def next(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position
  def previous(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position

private[folio] object CursorAdvance:
  def offsetOnly[FIELD, T]: CursorAdvance[FIELD, T] = new CursorAdvance[FIELD, T]:
    def next(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.next(limit)
        case keyset: Position.Keyset => keyset

    def previous(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position =
      position match
        case offset: Position.Offset => offset.previous(limit)
        case keyset: Position.Keyset => keyset

  def keysetAware[FIELD, T](keysetField: KeysetField[FIELD, T]): CursorAdvance[FIELD, T] =
    new CursorAdvance[FIELD, T]:
      def next(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position =
        position match
          case offset: Position.Offset => offset.next(limit)
          case _: Position.Keyset      => rows.lastOption.fold(position)(encodeRow(sortBys, _))

      def previous(position: Position, sortBys: ListSet[SortBy[FIELD]], rows: Seq[T], limit: Limit): Position =
        position match
          case offset: Position.Offset => offset.previous(limit)
          case _: Position.Keyset      => rows.headOption.fold(position)(encodeRow(sortBys, _))

      private def encodeRow(sortBys: ListSet[SortBy[FIELD]], row: T): Position.Keyset =
        val cursorFields = cursorFieldsFor(sortBys, keysetField.field)
        val values = cursorFields.map: field =>
          keysetField.fields
            .get(field)
            .map(_.encodedFromRow(row))
            .getOrElse(keysetField.codec.toKeysetValue(keysetField.rowId(row)))

        Position.Keyset(values)

  private[folio] def cursorFieldsFor[FIELD](
      sortBys: ListSet[SortBy[FIELD]],
      idField: FIELD
  ): List[FIELD] =
    val sortFields = sortBys.toList.map(_.field)
    if sortFields.contains(idField) then sortFields else sortFields :+ idField
