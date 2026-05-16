package folio

import cats.Applicative
import cats.syntax.functor.*
import cats.syntax.traverse.*
import folio.FolioError.*
import scala.compiletime.{ summonFrom, summonInline }

case class Page[T](
    limit: Limit,
    previousCursor: Option[Cursor],
    nextCursor: Option[Cursor],
    data: Seq[T]
)

object Page:

  def empty[T](limit: Limit): Page[T] = Page(limit, previousCursor = None, nextCursor = None, data = Seq.empty)

  /** Build a page using whichever pagination strategy [[Position.fromQuery]] selects.
    *
    * `fetchRows` should fetch `query.limit + 1` rows for hasMore detection (callers can use [[Limit.fetchLimit]] from
    * the supplied [[ResolvedQuery]]). The extra row is dropped before the page is returned.
    *
    * Keyset is selected when both `IdField[FIELD]` and `RowId[T]` are in scope; otherwise offset-only is used.
    */
  inline def withPagination[F[_]: Applicative, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]
  )(using CursorCodec): F[Either[CursorDecodingError, Page[T]]] =
    val advance: CursorAdvance[T] = summonFrom:
      case _: IdField[FIELD] => CursorAdvance.keysetAware(summonInline[RowId[T]])
      case _                 => CursorAdvance.offsetOnly[T]
    // Resolve the fallback "first position" here so summonFrom inside Position.fromQuery
    // sees the concrete FIELD's IdField at the inline call site.
    val firstPosition = Position.fromQuery(query)
    paginate(query, fetchRows, advance, firstPosition)

  private def paginate[F[_]: Applicative, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]],
      advance: CursorAdvance[T],
      firstPosition: Position
  )(using CursorCodec): F[Either[CursorDecodingError, Page[T]]] =
    val currentDecodedCursor = query.cursor match
      case Some(cursor) => Cursor.decode(cursor, query)
      case None         => Right(DecodedCursor(Direction.Forward, firstPosition))

    currentDecodedCursor.traverse: current =>
      val limit = query.limit.getOrElse(Limit.Default)

      val (isBackward, sortBys, position) = current match
        case DecodedCursor(Direction.Backward, position) => (true, query.sortBys.flipOrder, position)
        case DecodedCursor(Direction.Forward, position)  => (false, query.sortBys, position)

      fetchRows(ResolvedQuery(query.filters, limit.fetchLimit, sortBys, position))
        .map: rowsPlusOne =>
          val hasMore = limit.hasMore(rowsPlusOne)
          val rows = rowsPlusOne.take(limit.value)
          val ordered = if isBackward then rows.reverse else rows

          if ordered.isEmpty then Page.empty(limit)
          else
            val nextCursor = Option.when(isBackward || hasMore):
              DecodedCursor(Direction.Forward, advance.next(position, ordered, limit)).encode(query)
            val previousCursor = Option.when((isBackward && hasMore) || (!isBackward && !current.isFirst)):
              DecodedCursor(Direction.Backward, advance.previous(position, ordered, limit)).encode(query)

            Page(limit, previousCursor, nextCursor, ordered)
