/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
            .getOrElse(keysetField.codec.toKeysetValue(keysetField.rowId(row)))

        Position.Keyset(values)

  private[folio] def cursorFieldsFor[FIELD](
      ordering: Vector[OrderBy[FIELD]],
      idField: FIELD
  ): List[FIELD] =
    val orderingFields = ordering.toList.map(_.field)
    if orderingFields.contains(idField) then orderingFields else orderingFields :+ idField
